#include "usb_audio_output.h"

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <atomic>
#include <poll.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#define TAG "UsbAudioOutput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Float → PCM conversion ───────────────────────────────────────────────

static void convertFloatToInt16(const float *in, int16_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        float s = in[i];
        if (s > 1.0f)  s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        out[i] = (int16_t)(s * 32767.0f);
    }
}

static void convertFloatToInt24(const float *in, uint8_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        float s = in[i];
        if (s > 1.0f)  s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        int32_t v = (int32_t)(s * 8388607.0f);
        out[i * 3 + 0] = (uint8_t)(v & 0xFF);
        out[i * 3 + 1] = (uint8_t)((v >> 8) & 0xFF);
        out[i * 3 + 2] = (uint8_t)((v >> 16) & 0xFF);
    }
}

static void convertFloatToInt32(const float *in, int32_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        double s = (double)in[i];
        if (s > 1.0)  s = 1.0;
        if (s < -1.0) s = -1.0;
        out[i] = (int32_t)(s * 2147483647.0);
    }
}

// ── Ring buffer management ────────────────────────────────────────────────

static void allocRing(UsbAudioContext *ctx) {
    if (ctx->ringAllocated) return;
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        ctx->ring[i].urb    = nullptr;
        ctx->ring[i].buffer = nullptr;
        ctx->ring[i].dataLength = 0;
    }
    size_t urbStructSize = sizeof(struct usbdevfs_urb) +
                           USB_AUDIO_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        ctx->ring[i].urb    = calloc(1, urbStructSize);
        ctx->ring[i].buffer = (uint8_t *)calloc(1, USB_AUDIO_URB_BUFFER_SIZE);
        if (!ctx->ring[i].urb || !ctx->ring[i].buffer) {
            LOGE("allocRing: allocation failed at slot %d", i);
            for (int j = 0; j <= i; j++) {
                free(ctx->ring[j].urb);
                free(ctx->ring[j].buffer);
                ctx->ring[j].urb    = nullptr;
                ctx->ring[j].buffer = nullptr;
            }
            return;
        }
    }
    ctx->ringAllocated = true;
    ctx->submitIdx = 0;
    ctx->reapIdx   = 0;
    ctx->urbsInFlight = 0;
    LOGI("allocRing: %d URB slots allocated (urbSize=%zu)", USB_AUDIO_NUM_URBS, urbStructSize);
}

static void freeRing(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated) return;
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        free(ctx->ring[i].urb);
        free(ctx->ring[i].buffer);
        ctx->ring[i].urb    = nullptr;
        ctx->ring[i].buffer = nullptr;
        ctx->ring[i].dataLength = 0;
    }
    ctx->ringAllocated = false;
    ctx->submitIdx = 0;
    ctx->reapIdx   = 0;
    ctx->urbsInFlight = 0;
    LOGI("freeRing: all URB slots freed");
}

// ── USB feedback helpers ──────────────────────────────────────────────────

static int readFeedback(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback == 0 || ctx->fd < 0) return 0;

    struct usbdevfs_urb urb;
    memset(&urb, 0, sizeof(urb));
    urb.type      = USBDEVFS_URB_TYPE_ISO;
    urb.endpoint  = ctx->endpointFeedback;
    urb.buffer    = ctx->feedbackBuffer;
    urb.buffer_length = sizeof(ctx->feedbackBuffer);
    urb.number_of_packets = 1;
    urb.iso_frame_desc[0].length = 4;
    urb.iso_frame_desc[0].actual_length = 0;
    urb.iso_frame_desc[0].status = 0;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, &urb);
    if (ret < 0) {
        LOGW("readFeedback: SUBMITURB failed: %s", strerror(errno));
        return -1;
    }

    struct usbdevfs_urb *reaped = nullptr;
    struct pollfd pfd;
    pfd.fd     = ctx->fd;
    pfd.events = POLLOUT;

    for (int attempt = 0; attempt < 40; attempt++) {
        poll(&pfd, 1, 1);
        ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (ret >= 0 && reaped == &urb) {
            if (urb.status == 0 && urb.iso_frame_desc[0].actual_length >= 3) {
                uint8_t *buf = (uint8_t *)urb.buffer;
                uint32_t raw = (uint32_t)buf[0]
                             | ((uint32_t)buf[1] << 8)
                             | ((uint32_t)buf[2] << 16);
                if (urb.iso_frame_desc[0].actual_length >= 4) {
                    raw |= ((uint32_t)buf[3] << 24);
                }
                double fpmf = (double)raw / 65536.0;
                ctx->calibratedFpmf = fpmf;
                LOGI("readFeedback: Q16.16 raw=0x%08X fpmf=%.6f", raw, fpmf);
                return 0;
            }
            break;
        }
    }

    ioctl(ctx->fd, USBDEVFS_DISCARDURB, &urb);
    poll(&pfd, 1, 2);
    ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
    LOGW("readFeedback: no valid feedback received");
    return -1;
}

static int allocFeedbackUrb(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback == 0) return 0;
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    ctx->feedbackUrb = calloc(1, sz);
    if (!ctx->feedbackUrb) {
        LOGE("allocFeedbackUrb: calloc failed");
        return -1;
    }
    ctx->feedbackInFlight = false;
    LOGI("allocFeedbackUrb: allocated");
    return 0;
}

static int submitFeedbackUrb(UsbAudioContext *ctx) {
    if (!ctx->feedbackUrb || ctx->endpointFeedback == 0) return 0;
    if (ctx->feedbackInFlight) return 0;

    struct usbdevfs_urb *urb = (struct usbdevfs_urb *)ctx->feedbackUrb;
    memset(urb, 0, sizeof(struct usbdevfs_urb));
    urb->type      = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint  = ctx->endpointFeedback;
    urb->buffer    = ctx->feedbackBuffer;
    urb->buffer_length = sizeof(ctx->feedbackBuffer);
    urb->number_of_packets = 1;
    urb->iso_frame_desc[0].length = 4;
    urb->iso_frame_desc[0].actual_length = 0;
    urb->iso_frame_desc[0].status = 0;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb);
    if (ret < 0) {
        LOGW("submitFeedbackUrb: SUBMITURB failed: %s", strerror(errno));
        return -1;
    }
    ctx->feedbackInFlight = true;
    return 0;
}

static void handleFeedbackCompletion(UsbAudioContext *ctx, struct usbdevfs_urb *urb) {
    if (urb->status == 0 && urb->iso_frame_desc[0].actual_length >= 3) {
        uint8_t *buf = (uint8_t *)urb->buffer;
        uint32_t raw = (uint32_t)buf[0]
                     | ((uint32_t)buf[1] << 8)
                     | ((uint32_t)buf[2] << 16);
        if (urb->iso_frame_desc[0].actual_length >= 4) {
            raw |= ((uint32_t)buf[3] << 24);
        }
        double fpmf = (double)raw / 65536.0;
        ctx->calibratedFpmf = fpmf;
    }
    ctx->feedbackInFlight = false;
    submitFeedbackUrb(ctx);
}

// ── URB submission / reap ─────────────────────────────────────────────────

static int submitRingUrb(UsbAudioContext *ctx, const int *pktSizes, int numPackets, int totalBytes) {
    if (!ctx->ringAllocated || numPackets <= 0) return -1;

    int idx = ctx->submitIdx;
    UrbSlot *slot = &ctx->ring[idx];

    struct usbdevfs_urb *urb = (struct usbdevfs_urb *)slot->urb;
    size_t clearSize = sizeof(struct usbdevfs_urb) +
                       numPackets * sizeof(struct usbdevfs_iso_packet_desc);
    memset(urb, 0, clearSize);

    urb->type              = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint          = ctx->endpointOut;
    urb->buffer            = slot->buffer;
    urb->buffer_length     = (totalBytes <= USB_AUDIO_URB_BUFFER_SIZE) ? totalBytes : USB_AUDIO_URB_BUFFER_SIZE;
    urb->flags             = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = numPackets;

    for (int p = 0; p < numPackets; p++) {
        urb->iso_frame_desc[p].length        = pktSizes[p];
        urb->iso_frame_desc[p].actual_length = 0;
        urb->iso_frame_desc[p].status        = 0;
    }

    slot->dataLength = urb->buffer_length;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb);
    if (ret < 0) {
        LOGE("submitRingUrb[%d]: SUBMITURB failed: %s", idx, strerror(errno));
        return -1;
    }

    ctx->submitIdx = (idx + 1) % USB_AUDIO_NUM_URBS;
    ctx->urbsInFlight++;
    return 0;
}

/**
 * Reap the oldest submitted URB.
 * Returns: 0 = success, -1 = error, -2 = timeout
 */
static int reapOldestUrb(UsbAudioContext *ctx) {
    if (ctx->urbsInFlight <= 0) return 0;

    struct usbdevfs_urb *reaped = nullptr;
    struct pollfd pfd;
    pfd.fd     = ctx->fd;
    pfd.events = POLLOUT;

    int retries = 0;
    while (ctx->urbsInFlight > 0 && retries < 1600) { // ~200ms timeout
        poll(&pfd, 1, 0);
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (ret >= 0 && reaped != nullptr) {
            if (ctx->feedbackInFlight && reaped == (struct usbdevfs_urb *)ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx, reaped);
                continue;
            }
            ctx->urbsInFlight--;
            ctx->reapIdx = (ctx->reapIdx + 1) % USB_AUDIO_NUM_URBS;
            return 0;  // success
        }

        if (errno == EAGAIN) {
            usleep(125);
            retries++;
            continue;
        }
        LOGW("reapOldestUrb: ioctl error: %s", strerror(errno));
        return -1;  // error
    }
    if (retries >= 1600) {
        LOGW("reapOldestUrb: timeout after 200ms, inflight=%d", ctx->urbsInFlight);
    }
    return -2;  // timeout
}

static void drainAllUrbs(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated || ctx->fd < 0) return;
    LOGI("drainAllUrbs: %d URBs in flight", ctx->urbsInFlight);

    // Phase 1: natural reap
    struct pollfd pfd;
    pfd.fd     = ctx->fd;
    pfd.events = POLLOUT;
    int timeout_ms = 200;
    while (ctx->urbsInFlight > 0 && timeout_ms > 0) {
        poll(&pfd, 1, 5);
        struct usbdevfs_urb *reaped = nullptr;
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (ret >= 0 && reaped != nullptr) {
            if (ctx->feedbackInFlight && reaped == (struct usbdevfs_urb *)ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx, reaped);
            } else {
                ctx->urbsInFlight--;
                ctx->reapIdx = (ctx->reapIdx + 1) % USB_AUDIO_NUM_URBS;
            }
            timeout_ms = 200;
        } else {
            timeout_ms -= 5;
        }
    }

    // Phase 2: DISCARD remaining
    if (ctx->urbsInFlight > 0) {
        int idx = ctx->reapIdx;
        for (int i = 0; i < ctx->urbsInFlight && i < USB_AUDIO_NUM_URBS; i++) {
            struct usbdevfs_urb *urb = (struct usbdevfs_urb *)ctx->ring[idx].urb;
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, urb);
            idx = (idx + 1) % USB_AUDIO_NUM_URBS;
        }
    }

    // Phase 3: reap completions after DISCARD
    int safety = USB_AUDIO_NUM_URBS * 2;
    while (ctx->urbsInFlight > 0 && safety-- > 0) {
        poll(&pfd, 1, 1);
        struct usbdevfs_urb *reaped = nullptr;
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (ret >= 0 && reaped != nullptr) {
            if (ctx->feedbackInFlight && reaped == (struct usbdevfs_urb *)ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx, reaped);
            } else {
                ctx->urbsInFlight--;
                ctx->reapIdx = (ctx->reapIdx + 1) % USB_AUDIO_NUM_URBS;
            }
        }
    }

    // Phase 4: flush stale feedback URB
    if (ctx->feedbackInFlight) {
        struct usbdevfs_urb *urb = (struct usbdevfs_urb *)ctx->feedbackUrb;
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, urb);
        struct usbdevfs_urb *reaped = nullptr;
        poll(&pfd, 1, 2);
        ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        ctx->feedbackInFlight = false;
    }

    LOGI("drainAllUrbs: done, remaining in flight: %d", ctx->urbsInFlight);
}

// ── Shared submitPcmToUrbs ────────────────────────────────────────────────

void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int byteCount) {
    if (!ctx->running.load() || ctx->fd < 0 || byteCount <= 0) return;
    if (!ctx->workBuffer) return;

    int frameSize = ctx->bytesPerFrame;
    if (frameSize <= 0) return;

    // Step 1: Merge residual from previous call + new data
    int dataLen = byteCount;
    const uint8_t *data = pcmData;
    uint8_t *mergedBuf = nullptr;

    if (ctx->residualBytes > 0 && byteCount > 0) {
        dataLen = ctx->residualBytes + byteCount;
        mergedBuf = (uint8_t *)malloc(dataLen);
        if (mergedBuf) {
            memcpy(mergedBuf, ctx->residualBuffer, ctx->residualBytes);
            memcpy(mergedBuf + ctx->residualBytes, pcmData, byteCount);
            data = mergedBuf;
        } else {
            ctx->residualBytes = 0;
        }
    }
    ctx->residualBytes = 0;

    // Align to frame boundary
    int alignedLen = (dataLen / frameSize) * frameSize;

    // Step 2: Use fractional accumulator for variable packet sizes (like decent-player)
    double fpmf = ctx->calibratedFpmf;
    if (fpmf <= 0.0) fpmf = (double)(ctx->sampleRate) / 8000.0;

    int offset = 0;
    while (offset < alignedLen && ctx->running.load()) {
        // Build one URB with USB_AUDIO_PACKETS_PER_URB packets
        int pktSizes[USB_AUDIO_PACKETS_PER_URB];
        int numPackets = 0;
        int urbBytes = 0;

        for (int p = 0; p < USB_AUDIO_PACKETS_PER_URB; p++) {
            int remaining = alignedLen - offset - urbBytes;
            if (remaining <= 0) break;

            ctx->frameAccumulator += fpmf;
            int frames = (int)ctx->frameAccumulator;
            ctx->frameAccumulator -= frames;
            int b = frames * frameSize;

            if (b > remaining) {
                // Not enough data for a full packet — save for next call
                break;
            }

            pktSizes[p] = b;
            urbBytes += b;
            numPackets++;
        }
        if (numPackets <= 0 || urbBytes <= 0) break;

        // Never submit short URBs (< full packet count)
        if (numPackets < USB_AUDIO_PACKETS_PER_URB) {
            int leftover = dataLen - offset;
            if (leftover > 0 && leftover < (int)sizeof(ctx->residualBuffer)) {
                memcpy(ctx->residualBuffer, data + offset, leftover);
                ctx->residualBytes = leftover;
            }
            break;
        }

        // Reap if ring is full
        if (ctx->urbsInFlight >= USB_AUDIO_NUM_URBS) {
            int result = reapOldestUrb(ctx);
            if (result == -2) {
                LOGE("submitPcmToUrbs: reap timeout, inflight=%d", ctx->urbsInFlight);
                drainAllUrbs(ctx);
                ctx->running.store(false);
                free(mergedBuf);
                return;
            } else if (result < 0) {
                LOGE("submitPcmToUrbs: reap error, inflight=%d", ctx->urbsInFlight);
                ctx->running.store(false);
                free(mergedBuf);
                return;
            }
        }

        // Copy data into ring buffer slot
        memcpy(ctx->ring[ctx->submitIdx].buffer, data + offset, urbBytes);

        if (submitRingUrb(ctx, pktSizes, numPackets, urbBytes) < 0) {
            LOGE("submitPcmToUrbs: submit failed, stopping stream");
            ctx->running.store(false);
            free(mergedBuf);
            return;
        }
        ctx->framesWritten += urbBytes / frameSize;
        offset += urbBytes;
    }

    // Save any leftover bytes for the next call
    int leftover = dataLen - offset;
    if (leftover > 0 && leftover < (int)sizeof(ctx->residualBuffer) && ctx->residualBytes == 0) {
        memcpy(ctx->residualBuffer, data + offset, leftover);
        ctx->residualBytes = leftover;
    }

    free(mergedBuf);
}

// ── Integer padding functions ─────────────────────────────────────────────

void padInt16ToInt32(const int16_t *in, int32_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        out[i] = (int32_t)in[i] << 16;
    }
}

void padInt24ToInt32(const uint8_t *in, int32_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        int32_t v = (int32_t)(in[i * 3 + 0]
                   | ((uint32_t)in[i * 3 + 1] << 8)
                   | ((uint32_t)in[i * 3 + 2] << 16));
        if (v & 0x800000) v |= (int32_t)0xFF000000; // sign-extend
        out[i] = v << 8;
    }
}

void shiftInt32From24(const int32_t *in, int32_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        out[i] = in[i] << 8;
    }
}

// ── JNI entry points ──────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeCreate(
    JNIEnv *env, jobject thiz,
    jint fd, jint ifId, jint epOut, jint epFb,
    jint rate, jint ch, jint bits, jint maxPkt)
{
    auto *ctx = new UsbAudioContext();
    ctx->fd              = fd;
    ctx->interfaceId     = ifId;
    ctx->endpointOut     = epOut;
    ctx->endpointFeedback = epFb;
    ctx->sampleRate      = rate;
    ctx->channelCount    = ch;
    ctx->bitDepth        = bits;
    ctx->bytesPerSample  = (bits <= 16) ? 2 : (bits <= 24) ? 3 : 4;
    ctx->bytesPerFrame   = ch * ctx->bytesPerSample;
    ctx->maxPacketSize   = maxPkt;

    ctx->running.store(false);

    ctx->workBuffer = (uint8_t *)malloc(256 * 1024); // 256KB single work buffer
    ctx->residualBytes = 0;
    ctx->framesWritten   = 0;
    ctx->submitIdx       = 0;
    ctx->reapIdx         = 0;
    ctx->urbsInFlight    = 0;
    ctx->ringAllocated   = false;

    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf   = (double)rate / 8000.0;

    ctx->feedbackUrb      = nullptr;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));

    LOGI("nativeCreate: fd=%d ifId=%d epOut=0x%02X epFb=0x%02X rate=%d ch=%d bits=%d maxPkt=%d",
         fd, ifId, epOut, epFb, rate, ch, bits, maxPkt);

    // Detach kernel driver from the audio streaming interface
    struct usbdevfs_ioctl cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.ifno = ifId;
    cmd.ioctl_code = USBDEVFS_DISCONNECT;
    int ret = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    if (ret < 0) {
        LOGW("nativeCreate: USBDEVFS_DISCONNECT ifno=%d: %s (may not be attached)", ifId, strerror(errno));
    } else {
        LOGI("nativeCreate: detached kernel driver from interface %d", ifId);
    }

    // Claim the audio streaming interface
    unsigned int ifnum = (unsigned int)ifId;
    ret = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifnum);
    if (ret < 0) {
        LOGE("nativeCreate: USBDEVFS_CLAIMINTERFACE ifno=%d failed: %s", ifId, strerror(errno));
        delete ctx;
        return 0;
    }

    // Also claim interface 0 (AudioControl) for SET_CUR/GET_CUR clock control
    if (ifId != 0) {
        unsigned int ifnum0 = 0;
        ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifnum0); // best effort
    }

    // Allocate ring buffers and feedback URB at creation time (like decent-player)
    allocRing(ctx);
    if (!ctx->ringAllocated) {
        LOGE("nativeCreate: allocRing failed");
        delete ctx;
        return 0;
    }

    if (!allocFeedbackUrb(ctx)) {
        LOGE("nativeCreate: allocFeedbackUrb failed");
        freeRing(ctx);
        delete ctx;
        return 0;
    }

    LOGI("nativeCreate: success, ring allocated, feedback URB allocated");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeSetAltSetting(
    JNIEnv *env, jobject thiz, jlong handle, jint alt)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx || ctx->fd < 0) return JNI_FALSE;

    struct usbdevfs_setinterface si;
    si.interface  = ctx->interfaceId;
    si.altsetting = alt;
    int ret = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);
    if (ret < 0) {
        LOGE("nativeSetAltSetting: ioctl failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    LOGI("nativeSetAltSetting: iface=%d alt=%d OK", ctx->interfaceId, alt);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeSetSampleRate(
    JNIEnv *env, jobject thiz, jlong handle, jint rate, jint csId)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx || ctx->fd < 0) return JNI_FALSE;

    uint8_t data[4];
    data[0] = (uint8_t)(rate & 0xFF);
    data[1] = (uint8_t)((rate >> 8) & 0xFF);
    data[2] = (uint8_t)((rate >> 16) & 0xFF);
    data[3] = (uint8_t)((rate >> 24) & 0xFF);

    struct usbdevfs_ctrltransfer ctrl;
    ctrl.bRequestType = 0x21; // CLASS | INTERFACE | OUT
    ctrl.bRequest     = 0x01; // UAC2_CS_CUR
    ctrl.wValue       = 0x0100; // SAM_FREQ << 8
    ctrl.wIndex       = (uint16_t)(ctx->interfaceId | (csId << 8));
    ctrl.wLength      = 4;
    ctrl.timeout      = 1000;
    ctrl.data         = data;

    int ret = ioctl(ctx->fd, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        LOGE("nativeSetSampleRate: SET_CUR failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    ctx->sampleRate = rate;
    ctx->calibratedFpmf = (double)rate / 8000.0;
    LOGI("nativeSetSampleRate: %d Hz, csId=%d OK", rate, csId);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeStart(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx || ctx->fd < 0) return JNI_FALSE;

    ctx->framesWritten    = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes    = 0;
    ctx->urbsInFlight     = 0;
    ctx->submitIdx        = 0;
    ctx->reapIdx          = 0;

    // Ring and feedback URB are already allocated in create
    if (!ctx->ringAllocated) {
        LOGE("nativeStart: ring not allocated");
        return JNI_FALSE;
    }

    // Initial calibration from the DAC's async feedback endpoint
    double nominalFpmf = ctx->sampleRate / 8000.0;
    ctx->calibratedFpmf = nominalFpmf;

    if (ctx->endpointFeedback > 0) {
        int fbResult = readFeedback(ctx);
        if (fbResult == 0 && ctx->calibratedFpmf > 0) {
            LOGI("Start: initial feedback=%.4f fpmf (%.1f Hz), nominal=%.4f (%.1f Hz)",
                 ctx->calibratedFpmf, ctx->calibratedFpmf * 8000.0,
                 nominalFpmf, nominalFpmf * 8000.0);
        } else {
            LOGW("Start: feedback not responding, using nominal %.4f fpmf", nominalFpmf);
            ctx->calibratedFpmf = nominalFpmf;
        }

        // Start continuous feedback
        submitFeedbackUrb(ctx);
    }

    ctx->running.store(true);

    LOGI("nativeStart: %dHz %dbit %dch, fpmf=%.4f feedback=%s",
         ctx->sampleRate, ctx->bitDepth, ctx->channelCount,
         ctx->calibratedFpmf,
         ctx->feedbackInFlight ? "continuous" : "one-shot");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeWrite(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray data)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx || !ctx->running.load() || !ctx->workBuffer) return;

    int len = env->GetArrayLength(data);
    if (len <= 0) return;
    float *fbuf = env->GetFloatArrayElements(data, nullptr);
    if (!fbuf) return;

    int totalSamples = len;
    int outBytes = totalSamples * ctx->bytesPerSample;

    // Convert into a temporary stack buffer, then pass to submitPcmToUrbs
    // (which will merge with residual in workBuffer)
    uint8_t tempBuf[64 * 1024]; // 64KB stack buffer
    uint8_t *dst = (outBytes <= (int)sizeof(tempBuf)) ? tempBuf : ctx->workBuffer;
    switch (ctx->bitDepth) {
        case 16: convertFloatToInt16(fbuf, (int16_t *)dst, totalSamples); break;
        case 24: convertFloatToInt24(fbuf, dst, totalSamples); break;
        case 32: convertFloatToInt32(fbuf, (int32_t *)dst, totalSamples); break;
        default: break;
    }

    env->ReleaseFloatArrayElements(data, fbuf, JNI_ABORT);
    submitPcmToUrbs(ctx, dst, outBytes);
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeWriteRaw(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint inputBitDepth)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx || !ctx->running.load()) return;

    int len = env->GetArrayLength(data);
    if (len <= 0) return;
    jbyte *raw = env->GetByteArrayElements(data, nullptr);
    if (!raw) return;

    int inBps = (inputBitDepth <= 16) ? 2 : (inputBitDepth <= 24) ? 3 : 4;
    int outBps = ctx->bytesPerSample;

    if (inBps == outBps) {
        // Same format: pass directly
        submitPcmToUrbs(ctx, (const uint8_t *)raw, len);
        env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
    } else {
        // Need conversion
        int totalSamples = len / inBps;
        int outBytes = totalSamples * outBps;
        uint8_t tempBuf[64 * 1024];
        uint8_t *out = (outBytes <= (int)sizeof(tempBuf)) ? tempBuf : nullptr;
        if (!out) {
            env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
            return;
        }
        if (inBps == 2 && outBps == 4) {
            padInt16ToInt32((const int16_t *)raw, (int32_t *)out, totalSamples);
        } else if (inBps == 3 && outBps == 4) {
            padInt24ToInt32((const uint8_t *)raw, (int32_t *)out, totalSamples);
        } else if (inBps == 4 && outBps == 4) {
            shiftInt32From24((const int32_t *)raw, (int32_t *)out, totalSamples);
        } else {
            env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
            return;
        }
        env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
        submitPcmToUrbs(ctx, out, outBytes);
    }
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeStop(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return;

    ctx->running.store(false);
    LOGI("nativeStop: stopping, %lld frames written", (long long)ctx->framesWritten);
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeFlush(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return;

    ctx->residualBytes    = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBuffer[0] = 0;  // clear residual buffer
    LOGI("nativeFlush: residual cleared");
}

JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeDrainUrbs(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return 0;

    drainAllUrbs(ctx);
    int remaining = ctx->urbsInFlight;
    LOGI("nativeDrainUrbs: %d URBs drained, %d remaining", remaining, remaining);
    return remaining;
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return;

    ctx->running.store(false);

    // Best effort drain — may fail if device disconnected
    if (ctx->ringAllocated && ctx->urbsInFlight > 0) {
        drainAllUrbs(ctx);
    }
    freeRing(ctx);

    if (ctx->feedbackUrb) {
        free(ctx->feedbackUrb);
        ctx->feedbackUrb = nullptr;
    }

    // Release interface — may fail if device disconnected
    if (ctx->fd >= 0) {
        unsigned int ifnum = (unsigned int)ctx->interfaceId;
        int ret = ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ifnum);
        if (ret < 0) {
            LOGW("nativeDestroy: release interface %d failed: %s (device disconnected?)", ctx->interfaceId, strerror(errno));
        }
    }

    if (ctx->workBuffer) {
        free(ctx->workBuffer);
        ctx->workBuffer = nullptr;
    }

    LOGI("nativeDestroy: context deleted, fd=%d, frames=%lld", ctx->fd, (long long)ctx->framesWritten);
    delete ctx;
}

JNIEXPORT jlong JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeGetFramesWritten(
    JNIEnv *env, jobject thiz, jlong handle)
{
    if (handle == 0) return 0;
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return 0;
    // Always return framesWritten regardless of running state
    // This allows position tracking even after playback stops
    return ctx->framesWritten;
}

JNIEXPORT jboolean JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeIsRunning(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return JNI_FALSE;
    return ctx->running.load() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
