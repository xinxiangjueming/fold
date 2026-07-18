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
#include <cstdio>
#include <ctime>

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#ifndef USBDEVFS_GETCAPABILITIES
#define USBDEVFS_GETCAPABILITIES _IOR('U', 26, __u32)
#endif

#define TAG "UsbAudioOutput"

static FILE *gLogFile = nullptr;

static void openLogFile() {
    if (gLogFile) return;
    gLogFile = fopen("/storage/emulated/0/fold/fold.log", "a");
    if (gLogFile) setbuf(gLogFile, nullptr);
}

static void closeLogFile() {
    if (gLogFile) {
        fclose(gLogFile);
        gLogFile = nullptr;
    }
}

static void logToFile(const char *level, const char *fmt, ...) {
    openLogFile();
    if (!gLogFile) return;
    time_t now = time(nullptr);
    struct tm *t = localtime(&now);
    char timeBuf[32];
    strftime(timeBuf, sizeof(timeBuf), "%m-%d %H:%M:%S", t);
    fprintf(gLogFile, "%s %s/%s: ", timeBuf, level, TAG);
    va_list args;
    va_start(args, fmt);
    vfprintf(gLogFile, fmt, args);
    va_end(args);
    fprintf(gLogFile, "\n");
    fflush(gLogFile);
    // Close after each write to avoid holding file handle indefinitely
    closeLogFile();
}

#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__); logToFile("I", __VA_ARGS__); } while(0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__); logToFile("W", __VA_ARGS__); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); logToFile("E", __VA_ARGS__); } while(0)

// ── Errno classification ───────────────────────────────────────────────────

static const char* errnoName(int err) {
    switch (err) {
        case 0:          return "OK";
        case EPERM:      return "EPERM(1)-Operation not permitted";
        case ENOENT:     return "ENOENT(2)-No such file or directory";
        case ESRCH:      return "ESRCH(3)-No such process";
        case EINTR:      return "EINTR(4)-Interrupted system call";
        case EIO:        return "EIO(5)-Input/output error";
        case ENXIO:      return "ENXIO(6)-No such device or address";
        case EBADF:      return "EBADF(9)-Bad file descriptor";
        case ENOMEM:     return "ENOMEM(11)-Out of memory";
        case EACCES:     return "EACCES(13)-Permission denied";
        case EFAULT:     return "EFAULT(14)-Bad address";
        case EBUSY:      return "EBUSY(16)-Device or resource busy";
        case EEXIST:     return "EEXIST(17)-File exists";
        case ENODEV:     return "ENODEV(19)-No such device";
        case ENOTDIR:    return "ENOTDIR(20)-Not a directory";
        case EISDIR:     return "EISDIR(21)-Is a directory";
        case EINVAL:     return "EINVAL(22)-Invalid argument";
        case ENOSPC:     return "ENOSPC(28)-No space left on device";
        case EPIPE:      return "EPIPE(32)-Broken pipe";
        // EAGAIN == EWOULDBLOCK (both are 11 on Linux)
        case EAGAIN:     return "EAGAIN(11)-Resource temporarily unavailable";
        case ENOTSOCK:   return "ENOTSOCK(38)-Socket operation on non-socket";
        case ENOPROTOOPT:return "ENOPROTOOPT(42)-Protocol not available";
        case EPROTONOSUPPORT: return "EPROTONOSUPPORT(43)-Protocol not supported";
        case ENOTSUP:    return "ENOTSUP(95)-Operation not supported";
        case ETIMEDOUT:  return "ETIMEDOUT(110)-Connection timed out";
        case ENODATA:    return "ENODATA(61)-No data available";
        default:         return "UNKNOWN";
    }
}

static const char* ioctlCodeName(unsigned long code) {
    if (code == USBDEVFS_SUBMITURB)      return "SUBMITURB";
    if (code == USBDEVFS_REAPURB)        return "REAPURB";
    if (code == USBDEVFS_REAPURBNDELAY)  return "REAPURBNDELAY";
    if (code == USBDEVFS_DISCARDURB)     return "DISCARDURB";
    if (code == USBDEVFS_CLAIMINTERFACE) return "CLAIMINTERFACE";
    if (code == USBDEVFS_RELEASEINTERFACE) return "RELEASEINTERFACE";
    if (code == USBDEVFS_SETINTERFACE)   return "SETINTERFACE";
    if (code == USBDEVFS_IOCTL)          return "IOCTL";
    if (code == USBDEVFS_CONTROL)        return "CONTROL";
    return "UNKNOWN";
}

static void logUsbFdState(const char* caller, int fd, int ifId) {
    // Check if fd is still valid by doing a no-op ioctl
    unsigned int ifnum = (unsigned int)ifId;
    int ret = ioctl(fd, USBDEVFS_GETCAPABILITIES, &ifnum);
    LOGI("%s: fd=%d ifId=%d fdValid=%s errno=%d(%s)",
         caller, fd, ifId,
         (ret >= 0 || errno != EBADF) ? "yes" : "NO",
         (ret < 0) ? errno : 0,
         (ret < 0) ? errnoName(errno) : "ok");
}

// ── Float → PCM conversion (bit-perfect, matching FFmpeg's libswresample) ──

static inline float clampf(float v) { return v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v); }

// FFmpeg normalizes: int / 2^N (e.g., int16 / 32768.0)
// Reconversion: float × 2^N gives exact round-trip for 16-bit and 24-bit because:
//   - 2^N is exactly representable in float32 (power of 2)
//   - float32 has 24-bit mantissa, covering int16 (16-bit) and int24 (24-bit) exactly
static void convertFloatToInt16(const float *in, int16_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        float s = clampf(in[i]) * 32768.0f;
        if (s > 32767.0f) s = 32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        out[i] = (int16_t)s;
    }
}

static void convertFloatToInt24(const float *in, uint8_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        float s = clampf(in[i]) * 8388608.0f;
        if (s > 8388607.0f) s = 8388607.0f;
        if (s < -8388608.0f) s = -8388608.0f;
        int32_t v = (int32_t)s;
        out[i * 3 + 0] = (uint8_t)(v & 0xFF);
        out[i * 3 + 1] = (uint8_t)((v >> 8) & 0xFF);
        out[i * 3 + 2] = (uint8_t)((v >> 16) & 0xFF);
    }
}

static void convertFloatToInt32(const float *in, int32_t *out, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
        // Use double: float32 can't represent 2147483648.0 exactly (needs 31 bits,
        // float32 has 24-bit mantissa). Double has 53-bit mantissa — sufficient.
        double s = (double)clampf(in[i]) * 2147483648.0;
        if (s > 2147483647.0) s = 2147483647.0;
        if (s < -2147483648.0) s = -2147483648.0;
        out[i] = (int32_t)s;
    }
}

// ── Ring buffer management ────────────────────────────────────────────────

static void allocRing(UsbAudioContext *ctx) {
    if (ctx->ringAllocated) {
        LOGI("allocRing: already allocated, skipping");
        return;
    }
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
            LOGE("allocRing: ALLOCATION FAILED at slot %d — urb=%p buffer=%p (size=%zu each)",
                 i, ctx->ring[i].urb, ctx->ring[i].buffer, urbStructSize);
            LOGE("allocRing: system may be low on memory, needed %d slots of %zu bytes",
                 USB_AUDIO_NUM_URBS, urbStructSize + USB_AUDIO_URB_BUFFER_SIZE);
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
    if (ctx->endpointFeedback == 0) return 1; // no feedback endpoint, not an error
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    ctx->feedbackUrb = calloc(1, sz);
    if (!ctx->feedbackUrb) {
        LOGE("allocFeedbackUrb: calloc failed");
        return 0;
    }
    ctx->feedbackInFlight = false;
    LOGI("allocFeedbackUrb: allocated");
    return 1;
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
        // Sanity check: feedback should be within ±1% of nominal
        double nominal = ctx->sampleRate / 8000.0;
        if (fpmf > nominal * 0.99 && fpmf < nominal * 1.01) {
            ctx->calibratedFpmf = fpmf;
        } else {
            LOGW("Feedback sanity check failed: fpmf=%.4f nominal=%.4f, ignoring", fpmf, nominal);
        }
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

    // Log URB slot state before clearing (for debugging double-submit)
    if (ctx->urbsInFlight >= 70) {
        LOGI("submitRingUrb: slot=%d urb=%p buffer=%p inflight=%d", idx, slot->urb, slot->buffer, ctx->urbsInFlight);
    }

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
        int err = errno;
        ctx->urbSubmitFailures++;
        ctx->lastTransferErrorCode = err;
        ctx->lastTransferErrorSource = 1; // submit
        LOGE("submitRingUrb[%d]: SUBMITURB FAILED: %s (errno=%d)", idx, strerror(err), err);
        LOGE("submitRingUrb:   fd=%d ep=0x%02X urbsInFlight=%d submitIdx=%d reapIdx=%d",
             ctx->fd, ctx->endpointOut, ctx->urbsInFlight, ctx->submitIdx, ctx->reapIdx);
        LOGE("submitRingUrb:   pkts=%d totalBytes=%d urbBuf=%p bufferLen=%d",
             numPackets, totalBytes, slot->buffer, urb->buffer_length);
        if (err == EBADF) {
            LOGE("submitRingUrb:   >>> FD INVALID — kernel driver may have reclaimed interface");
            logUsbFdState("submitRingUrb", ctx->fd, ctx->interfaceId);
        } else if (err == ENODEV) {
            LOGE("submitRingUrb:   >>> DEVICE REMOVED or reset");
        } else if (err == EBUSY) {
            LOGE("submitRingUrb:   >>> INTERFACE BUSY — another driver holds it");
        } else if (err == ENOENT) {
            LOGE("submitRingUrb:   >>> URB already unlinked or fd mismatch");
        } else if (err == EPIPE) {
            LOGE("submitRingUrb:   >>> STALL on endpoint 0x%02X", ctx->endpointOut);
        }
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
        // Log detailed state on first retry
        if (retries == 0) {
            LOGI("reapOldestUrb: attempting reap, submitIdx=%d reapIdx=%d inflight=%d fd=%d",
                 ctx->submitIdx, ctx->reapIdx, ctx->urbsInFlight, ctx->fd);
        }
        poll(&pfd, 1, 0);
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (ret >= 0 && reaped != nullptr) {
            if (ctx->feedbackInFlight && reaped == (struct usbdevfs_urb *)ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx, reaped);
                ctx->feedbackPacketCount++;
                continue;
            }
            ctx->urbsInFlight--;
            ctx->reapIdx = (ctx->reapIdx + 1) % USB_AUDIO_NUM_URBS;
            ctx->urbCompleteCount++;
            // Count ISO packet errors from this URB
            for (int p = 0; p < reaped->number_of_packets; p++) {
                ctx->audioIsoPacketTotal++;
                ctx->isoPacketTotal++;
                if (reaped->iso_frame_desc[p].status != 0) {
                    ctx->audioIsoPacketErrors++;
                    ctx->isoPacketErrors++;
                }
            }
            return 0;  // success
        }

        if (errno == EAGAIN) {
            usleep(125);
            retries++;
            continue;
        }
        int err = errno;
        ctx->urbReapFailures++;
        ctx->lastTransferErrorCode = err;
        ctx->lastTransferErrorSource = 2; // reap
        LOGE("reapOldestUrb: REAPURBNDELAY FAILED: %s (errno=%d)", strerror(err), err);
        LOGE("reapOldestUrb:   fd=%d urbsInFlight=%d submitIdx=%d reapIdx=%d retries=%d",
             ctx->fd, ctx->urbsInFlight, ctx->submitIdx, ctx->reapIdx, retries);
        if (err == EBADF) {
            LOGE("reapOldestUrb:   >>> FD INVALID");
            logUsbFdState("reapOldestUrb", ctx->fd, ctx->interfaceId);
        } else if (err == ENODEV) {
            LOGE("reapOldestUrb:   >>> DEVICE REMOVED");
        }
        return -1;  // error
    }
    if (retries >= 1600) {
        ctx->lastTransferErrorCode = ETIMEDOUT;
        ctx->lastTransferErrorSource = 3; // timeout
        LOGE("reapOldestUrb: TIMEOUT after 200ms (%d retries)", retries);
        LOGE("reapOldestUrb:   fd=%d urbsInFlight=%d submitIdx=%d reapIdx=%d",
             ctx->fd, ctx->urbsInFlight, ctx->submitIdx, ctx->reapIdx);
        logUsbFdState("reapOldestUrb", ctx->fd, ctx->interfaceId);
    }
    return -2;  // timeout
}

static void drainAllUrbs(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated || ctx->fd < 0) {
        LOGW("drainAllUrbs: skipped — ringAllocated=%s fd=%d",
             ctx->ringAllocated ? "yes" : "no", ctx->fd);
        return;
    }
    LOGI("drainAllUrbs: START — %d URBs in flight, fd=%d", ctx->urbsInFlight, ctx->fd);

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

// ── ENOENT Recovery ────────────────────────────────────────────────────────

/**
 * Attempt to recover from ENOENT by draining URBs, releasing and re-claiming
 * the interface, and resetting the ring buffer state.
 * Returns true if recovery succeeded, false if we should give up.
 */
static bool recoverFromEnoent(UsbAudioContext *ctx) {
    ctx->recoveryCount++;
    if (ctx->recoveryCount > ctx->maxRecoveryAttempts) {
        LOGE("recoverFromEnoent: max recovery attempts (%d) exceeded, giving up", ctx->maxRecoveryAttempts);
        return false;
    }

    ctx->recovering = true;
    LOGI("=== RECOVER START === attempt %d/%d, fd=%d ifId=%d ep=0x%02X",
         ctx->recoveryCount, ctx->maxRecoveryAttempts, ctx->fd, ctx->interfaceId, ctx->endpointOut);
    LOGI("recover: inflight=%d submitIdx=%d reapIdx=%d frames=%lld",
         ctx->urbsInFlight, ctx->submitIdx, ctx->reapIdx, (long long)ctx->framesWritten);

    // Log URB pointers BEFORE recovery
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        UrbSlot *s = &ctx->ring[i];
        if (s->urb && s->buffer) {
            LOGI("recover: slot[%d] urb=%p buffer=%p dataLen=%d", i, s->urb, s->buffer, s->dataLength);
        }
    }

    // Step 1: Stop stream
    ctx->running.store(false);
    LOGI("recover: running=false");

    // Step 2: Drain all URBs
    LOGI("recover: drainAllUrbs start, inflight=%d", ctx->urbsInFlight);
    drainAllUrbs(ctx);
    LOGI("recover: drainAllUrbs done, inflight=%d", ctx->urbsInFlight);

    // Step 3: Release interface
    {
        unsigned int ifnum = (unsigned int)ctx->interfaceId;
        int ret = ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ifnum);
        LOGI("recover: RELEASEINTERFACE ifno=%d ret=%d errno=%d(%s)",
             ctx->interfaceId, ret, ret < 0 ? errno : 0, ret < 0 ? strerror(errno) : "ok");
    }

    // Step 4: Wait for kernel cleanup
    LOGI("recover: sleep 100ms");
    usleep(100000);

    // Step 5: Re-claim interface
    {
        unsigned int ifnum = (unsigned int)ctx->interfaceId;
        int ret = ioctl(ctx->fd, USBDEVFS_CLAIMINTERFACE, &ifnum);
        LOGI("recover: CLAIMINTERFACE ifno=%d ret=%d errno=%d(%s)",
             ctx->interfaceId, ret, ret < 0 ? errno : 0, ret < 0 ? strerror(errno) : "ok");
        if (ret < 0) {
            ctx->recovering = false;
            return false;
        }
    }

    // Step 6: RESETEP
    {
        unsigned int ep = (unsigned int)ctx->endpointOut;
        int ret = ioctl(ctx->fd, USBDEVFS_RESETEP, &ep);
        LOGI("recover: RESETEP ep=0x%02X ret=%d errno=%d(%s)",
             ctx->endpointOut, ret, ret < 0 ? errno : 0, ret < 0 ? strerror(errno) : "ok");
    }

    // Step 7: alt=0 → sleep → alt=1
    {
        struct usbdevfs_setinterface si;
        si.interface = ctx->interfaceId;

        si.altsetting = 0;
        int r0 = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);
        LOGI("recover: SETINTERFACE alt=0 ret=%d errno=%d(%s)",
             r0, r0 < 0 ? errno : 0, r0 < 0 ? strerror(errno) : "ok");

        LOGI("recover: sleep 200ms after alt=0");
        usleep(200000);

        si.altsetting = 1;
        int r1 = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);
        LOGI("recover: SETINTERFACE alt=1 ret=%d errno=%d(%s)",
             r1, r1 < 0 ? errno : 0, r1 < 0 ? strerror(errno) : "ok");

        LOGI("recover: sleep 200ms after alt=1");
        usleep(200000);
    }

    // Step 8: Reset ring buffer
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->residualBytes = 0;
    ctx->frameAccumulator = 0.0;
    LOGI("recover: ring reset submitIdx=0 reapIdx=0 inflight=0");

    // Step 9: Reset feedback
    if (ctx->feedbackUrb) {
        ctx->feedbackInFlight = false;
    }

    // Step 10: Log URB pointers AFTER recovery
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        UrbSlot *s = &ctx->ring[i];
        if (s->urb && s->buffer) {
            LOGI("recover: slot[%d] urb=%p buffer=%p (same as before)", i, s->urb, s->buffer);
        }
    }

    // Step 11: Send 500ms silence to let device stabilize
    ctx->running.store(true);
    {
        int silenceFrames = ctx->sampleRate / 2; // 500ms of silence
        int silenceBytes = silenceFrames * ctx->bytesPerFrame;
        if (silenceBytes > 0 && silenceBytes <= 65536) {
            uint8_t *silence = (uint8_t *)calloc(1, silenceBytes);
            if (silence) {
                LOGI("recover: sending %d bytes silence (500ms) to stabilize device", silenceBytes);
                submitPcmToUrbs(ctx, silence, silenceBytes);
                free(silence);
            }
        }
    }

    // Step 12: Restart
    ctx->recovering = false;

    LOGI("=== RECOVER DONE === fd=%d", ctx->fd);
    return true;
}

// ── Shared submitPcmToUrbs ────────────────────────────────────────────────

void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int byteCount) {
    if (!ctx->running.load() || ctx->fd < 0 || byteCount <= 0) return;
    if (!ctx->workBuffer) return;

    int frameSize = ctx->bytesPerFrame;
    if (frameSize <= 0) return;

    // Log PCM data for first few calls after recovery
    static int pcmLogCount = 0;
    if (pcmLogCount < 5) {
        int hexLen = (byteCount < 32) ? byteCount : 32;
        char hexStr[65];
        for (int i = 0; i < hexLen; i++) {
            sprintf(hexStr + i * 2, "%02x", pcmData[i]);
        }
        hexStr[hexLen * 2] = '\0';
        LOGI("submitPcmToUrbs: PCM[%d] bytes=%d first32hex=%s residual=%d accum=%.4f",
             pcmLogCount, byteCount, hexStr, ctx->residualBytes, ctx->frameAccumulator);
        pcmLogCount++;
    }

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
            LOGI("submitPcmToUrbs: merged residual=%d + new=%d = total=%d bytes",
                 ctx->residualBytes, byteCount, dataLen);
        } else {
            LOGE("submitPcmToUrbs: malloc(%d) failed, dropping residual", dataLen);
            ctx->residualBytes = 0;
        }
    }
    ctx->residualBytes = 0;

    // Align to frame boundary
    int alignedLen = (dataLen / frameSize) * frameSize;
    if (alignedLen < dataLen) {
        LOGI("submitPcmToUrbs: aligned %d -> %d bytes (%d bytes tail saved)",
             dataLen, alignedLen, dataLen - alignedLen);
    }

    // Step 2: Use fractional accumulator for variable packet sizes (like decent-player)
    double fpmf = ctx->calibratedFpmf;
    if (fpmf <= 0.0) fpmf = (double)(ctx->sampleRate) / 8000.0;

    int offset = 0;
    int urbsSubmitted = 0;
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
                LOGI("submitPcmToUrbs: saved %d residual bytes (numPackets=%d < %d)",
                     leftover, numPackets, USB_AUDIO_PACKETS_PER_URB);
            }
            break;
        }

        // Reap if ring is full
        if (ctx->urbsInFlight >= USB_AUDIO_NUM_URBS) {
            int result = reapOldestUrb(ctx);
            if (result == -2) {
                ctx->pcmUnderruns++;
                ctx->pcmUnderrunBytes += alignedLen - offset;
                LOGE("submitPcmToUrbs: REAP TIMEOUT (underrun #%lld), inflight=%d, lostBytes=%d, offset=%d/%d",
                     (long long)ctx->pcmUnderruns, ctx->urbsInFlight, alignedLen - offset, offset, alignedLen);
                LOGE("submitPcmToUrbs:   ring state: submitIdx=%d reapIdx=%d urbsInFlight=%d",
                     ctx->submitIdx, ctx->reapIdx, ctx->urbsInFlight);
                logUsbFdState("submitPcmToUrbs-timeout", ctx->fd, ctx->interfaceId);
                drainAllUrbs(ctx);
                ctx->running.store(false);
                free(mergedBuf);
                return;
            } else if (result < 0) {
                ctx->pcmUnderruns++;
                ctx->pcmUnderrunBytes += alignedLen - offset;
                LOGE("submitPcmToUrbs: REAP ERROR (underrun #%lld), inflight=%d, errno=%d(%s)",
                     (long long)ctx->pcmUnderruns, ctx->urbsInFlight, ctx->lastTransferErrorCode,
                     errnoName(ctx->lastTransferErrorCode));
                LOGE("submitPcmToUrbs:   ring state: submitIdx=%d reapIdx=%d urbsInFlight=%d",
                     ctx->submitIdx, ctx->reapIdx, ctx->urbsInFlight);
                logUsbFdState("submitPcmToUrbs-reapError", ctx->fd, ctx->interfaceId);
                ctx->running.store(false);
                free(mergedBuf);
                return;
            }
        }

        // Copy data into ring buffer slot
        memcpy(ctx->ring[ctx->submitIdx].buffer, data + offset, urbBytes);

        // Log detailed state before submit (only when inflight is high)
        if (ctx->urbsInFlight >= 70) {
            LOGI("submitPcmToUrbs: PRE-SUBMIT state: submitIdx=%d reapIdx=%d inflight=%d frames=%lld isoErrs=%lld/%lld",
                 ctx->submitIdx, ctx->reapIdx, ctx->urbsInFlight,
                 (long long)ctx->framesWritten,
                 (long long)ctx->audioIsoPacketErrors, (long long)ctx->audioIsoPacketTotal);
        }

        if (submitRingUrb(ctx, pktSizes, numPackets, urbBytes) < 0) {
            ctx->pcmUnderruns++;
            ctx->pcmUnderrunBytes += alignedLen - offset;

            // ENOENT: endpoint lost from kernel — signal Java to recreate stream
            if (ctx->lastTransferErrorCode == ENOENT && !ctx->recovering) {
                LOGE("=== ENOENT DETECTED === fd=%d ep=0x%02X inflight=%d submitIdx=%d reapIdx=%d frames=%lld",
                     ctx->fd, ctx->endpointOut, ctx->urbsInFlight, ctx->submitIdx, ctx->reapIdx,
                     (long long)ctx->framesWritten);
                LOGE("ENOENT: slot urb=%p buffer=%p",
                     ctx->ring[ctx->submitIdx].urb, ctx->ring[ctx->submitIdx].buffer);
                ctx->running.store(false);
                ctx->lastTransferErrorSource = 4; // signal: recreate needed
                free(mergedBuf);
                return;
            }

            LOGE("submitPcmToUrbs: SUBMIT FAILED (underrun #%lld), errno=%d(%s), stopping stream",
                 (long long)ctx->pcmUnderruns, ctx->lastTransferErrorCode,
                 errnoName(ctx->lastTransferErrorCode));
            logUsbFdState("submitPcmToUrbs-submitFail", ctx->fd, ctx->interfaceId);
            ctx->running.store(false);
            free(mergedBuf);
            return;
        }

        ctx->framesWritten += urbBytes / frameSize;
        offset += urbBytes;
        urbsSubmitted++;

        // Log first few URBs for debugging
        if (urbsSubmitted <= 3) {
            LOGI("submitPcmToUrbs: URB#%d submitted, %d pkts, %d bytes, "
                 "inflight=%d, frames=%lld",
                 urbsSubmitted, numPackets, urbBytes, ctx->urbsInFlight,
                 (long long)ctx->framesWritten);
        }
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

    // Initialize transfer statistics
    ctx->urbCompleteCount      = 0;
    ctx->isoPacketTotal        = 0;
    ctx->isoPacketErrors       = 0;
    ctx->audioIsoPacketTotal   = 0;
    ctx->audioIsoPacketErrors  = 0;
    ctx->feedbackPacketCount   = 0;
    ctx->urbSubmitFailures     = 0;
    ctx->urbReapFailures       = 0;
    ctx->pcmUnderruns          = 0;
    ctx->pcmUnderrunBytes      = 0;
    ctx->lastTransferErrorCode = 0;
    ctx->lastTransferErrorSource = 0;
    ctx->submitIdx       = 0;
    ctx->reapIdx         = 0;
    ctx->urbsInFlight    = 0;
    ctx->ringAllocated   = false;

    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf   = (double)rate / 8000.0;

    ctx->feedbackUrb      = nullptr;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));

    // Initialize recovery state
    ctx->recoveryCount = 0;
    ctx->maxRecoveryAttempts = 3;
    ctx->recovering = false;

    LOGI("nativeCreate: fd=%d ifId=%d epOut=0x%02X epFb=0x%02X rate=%d ch=%d bits=%d maxPkt=%d",
         fd, ifId, epOut, epFb, rate, ch, bits, maxPkt);

    // Step 0: Disconnect AudioControl Interface 0 from kernel driver
    // This allows SET_CUR / ClockValid to work (kernel snd-usb-audio holds it)
    LOGI("nativeCreate: Step 0 — disconnecting AudioControl interface 0");
    {
        struct usbdevfs_ioctl cmd;
        memset(&cmd, 0, sizeof(cmd));
        cmd.ifno = 0; // AudioControl interface
        cmd.ioctl_code = USBDEVFS_DISCONNECT;
        int ret = ioctl(fd, USBDEVFS_IOCTL, &cmd);
        if (ret >= 0) {
            LOGI("nativeCreate: Step 0 OK — disconnected AudioControl interface 0");
        } else {
            int err = errno;
            LOGI("nativeCreate: Step 0 — USBDEVFS_DISCONNECT ifno=0: %s (errno=%d, may be normal)",
                 strerror(err), err);
        }
    }

    // Step 1: Detach kernel driver from the streaming interface
    {
        struct usbdevfs_ioctl cmd;
        memset(&cmd, 0, sizeof(cmd));
        cmd.ifno = ifId;
        cmd.ioctl_code = USBDEVFS_DISCONNECT;
        int ret = ioctl(fd, USBDEVFS_IOCTL, &cmd);
        if (ret >= 0) {
            LOGI("nativeCreate: Step 1 OK — detached kernel driver from interface %d", ifId);
        } else {
            int err = errno;
            // ENOENT = no driver bound (normal if already unbound)
            LOGI("nativeCreate: Step 1 — USBDEVFS_DISCONNECT ifno=%d: %s (errno=%d, may be normal)",
                 ifId, strerror(err), err);
        }
    }

    // Step 2: Claim the audio streaming interface
    LOGI("nativeCreate: Step 2 — claiming interface %d", ifId);
    {
        unsigned int ifnum = (unsigned int)ifId;
        int ret = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifnum);
        if (ret < 0) {
            int err = errno;
            LOGE("nativeCreate: Step 2 FAILED — USBDEVFS_CLAIMINTERFACE ifno=%d: %s (errno=%d)",
                 ifId, strerror(err), err);
            if (err == EBUSY) {
                LOGE("nativeCreate:   >>> KERNEL DRIVER STILL HOLDS INTERFACE %d", ifId);
                LOGE("nativeCreate:   >>> Check: is another app using this USB device?");
            } else if (err == ENODEV) {
                LOGE("nativeCreate:   >>> DEVICE REMOVED during setup");
            }
            delete ctx;
            return 0;
        }
        LOGI("nativeCreate: Step 2 OK — claimed interface %d", ifId);
    }

    logUsbFdState("nativeCreate-post-claim", fd, ifId);

    // Allocate ring buffers and feedback URB at creation time (like decent-player)
    allocRing(ctx);
    if (!ctx->ringAllocated) {
        LOGE("nativeCreate: allocRing failed - out of memory");
        delete ctx;
        return 0;
    }

    if (allocFeedbackUrb(ctx) == 0) {
        LOGE("nativeCreate: allocFeedbackUrb failed");
        freeRing(ctx);
        delete ctx;
        return 0;
    }

    LOGI("nativeCreate: success, ring=%d slots, feedback=%s", USB_AUDIO_NUM_URBS, ctx->feedbackUrb ? "allocated" : "skipped");
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
    ctrl.wIndex       = (uint16_t)(csId << 8); // Clock source is on AudioControl interface 0
    ctrl.wLength      = 4;
    ctrl.timeout      = 1000;
    ctrl.data         = data;

    LOGI("nativeSetSampleRate: SET_CUR %d Hz, csId=%d, fd=%d", rate, csId, ctx->fd);
    int ret = ioctl(ctx->fd, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        int err = errno;
        LOGE("nativeSetSampleRate: SET_CUR FAILED: %s (errno=%d)", strerror(err), err);
        LOGE("nativeSetSampleRate:   fd=%d csId=%d wValue=0x%04X wIndex=0x%04X",
             ctx->fd, csId, ctrl.wValue, ctrl.wIndex);
        if (err == EBUSY) {
            LOGE("nativeSetSampleRate:   >>> EBUSY — kernel AudioControl interface not released");
            LOGE("nativeSetSampleRate:   >>> We only disconnected interface %d (streaming), not interface 0 (AudioControl)", ctx->interfaceId);
        }
        logUsbFdState("nativeSetSampleRate", ctx->fd, ctx->interfaceId);
        return JNI_FALSE;
    }
    ctx->sampleRate = rate;
    ctx->calibratedFpmf = (double)rate / 8000.0;
    ctx->frameAccumulator = 0.0; // Reset accumulator on rate change
    LOGI("nativeSetSampleRate: SET_CUR OK — %d Hz, csId=%d, newFpmf=%.6f",
         rate, csId, ctx->calibratedFpmf);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeReadClockValid(
    JNIEnv *env, jobject thiz, jlong handle, jint csId)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx || ctx->fd < 0) return JNI_FALSE;

    uint8_t data[1] = {0};
    struct usbdevfs_ctrltransfer ctrl;
    ctrl.bRequestType = 0xA1; // CLASS | INTERFACE | IN
    ctrl.bRequest     = 0x81; // UAC2_CS_CUR
    ctrl.wValue       = 0x0200; // CLOCK_VALID_CONTROL << 8 (selector 0x02)
    ctrl.wIndex       = (uint16_t)(csId << 8);
    ctrl.wLength      = 1;
    ctrl.timeout      = 1000;
    ctrl.data         = data;

    LOGI("nativeReadClockValid: GET_CUR CLOCK_VALID, csId=%d, fd=%d", csId, ctx->fd);
    int ret = ioctl(ctx->fd, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        int err = errno;
        LOGW("nativeReadClockValid: GET_CUR FAILED: %s (errno=%d)", strerror(err), err);
        if (err == EBUSY) {
            LOGW("nativeReadClockValid:   >>> EBUSY — kernel AudioControl interface not released");
        }
        logUsbFdState("nativeReadClockValid", ctx->fd, ctx->interfaceId);
        return JNI_FALSE;
    }
    // Clock valid bit is bit 0 of the first byte
    bool valid = (data[0] & 0x01) != 0;
    LOGI("nativeReadClockValid: csId=%d, raw=0x%02X, valid=%s", csId, data[0], valid ? "true" : "false");
    return valid ? JNI_TRUE : JNI_FALSE;
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
    ctx->recoveryCount    = 0;  // Reset recovery counter on new start

    // Ring and feedback URB are already allocated in create
    if (!ctx->ringAllocated) {
        LOGE("nativeStart: ring not allocated");
        return JNI_FALSE;
    }

    // Initial calibration from the DAC's async feedback endpoint
    double nominalFpmf = ctx->sampleRate / 8000.0;
    ctx->calibratedFpmf = nominalFpmf;

    if (ctx->endpointFeedback > 0) {
        LOGI("nativeStart: reading initial feedback from ep=0x%02X", ctx->endpointFeedback);
        int fbResult = readFeedback(ctx);
        if (fbResult == 0 && ctx->calibratedFpmf > 0) {
            LOGI("nativeStart: feedback OK — calibrated=%.4f fpmf (%.1f Hz), nominal=%.4f (%.1f Hz), delta=%.2f%%",
                 ctx->calibratedFpmf, ctx->calibratedFpmf * 8000.0,
                 nominalFpmf, nominalFpmf * 8000.0,
                 (ctx->calibratedFpmf - nominalFpmf) / nominalFpmf * 100.0);
        } else {
            LOGW("nativeStart: feedback FAILED (result=%d), using nominal %.4f fpmf (%.1f Hz)",
                 fbResult, nominalFpmf, nominalFpmf * 8000.0);
            ctx->calibratedFpmf = nominalFpmf;
        }

        // Start continuous feedback
        LOGI("nativeStart: starting continuous feedback on ep=0x%02X", ctx->endpointFeedback);
        submitFeedbackUrb(ctx);
    } else {
        LOGI("nativeStart: no feedback endpoint, using nominal fpmf=%.6f", nominalFpmf);
    }

    ctx->running.store(true);

    logUsbFdState("nativeStart", ctx->fd, ctx->interfaceId);

    LOGI("nativeStart: %dHz %dbit %dch, fpmf=%.6f (%.1f frames/microframe), "
         "nominalFpmf=%.6f, endpointOut=0x%02X, maxPkt=%d, feedback=%s",
         ctx->sampleRate, ctx->bitDepth, ctx->channelCount,
         ctx->calibratedFpmf, ctx->calibratedFpmf,
         nominalFpmf,
         ctx->endpointOut, ctx->maxPacketSize,
         ctx->feedbackInFlight ? "continuous" : "one-shot");
    LOGI("nativeStart: ring=%d slots, ringAllocated=%s, urbsInFlight=%d",
         USB_AUDIO_NUM_URBS, ctx->ringAllocated ? "yes" : "NO", ctx->urbsInFlight);

    // Log expected packet sizes for debugging
    {
        int nominalFrames = (int)(nominalFpmf);
        int nominalBytes = nominalFrames * ctx->bytesPerFrame;
        int actualFrames = (int)(ctx->calibratedFpmf);
        int actualBytes = actualFrames * ctx->bytesPerFrame;
        LOGI("nativeStart: nominalPkt=%d bytes (%d frames), actualPkt=%d bytes (%d frames)",
             nominalBytes, nominalFrames, actualBytes, actualFrames);
    }
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

    // Skip if recovery is in progress
    if (ctx->recovering) return;

    ctx->residualBytes    = 0;
    ctx->frameAccumulator = 0.0;
    memset(ctx->residualBuffer, 0, USB_AUDIO_URB_BUFFER_SIZE);
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

    // Release interfaces — may fail if device disconnected
    if (ctx->fd >= 0) {
        // Release streaming interface
        unsigned int ifnum = (unsigned int)ctx->interfaceId;
        ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ifnum);
        // Release AudioControl interface 0
        unsigned int ifnum0 = 0;
        ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ifnum0);
    }

    if (ctx->workBuffer) {
        free(ctx->workBuffer);
        ctx->workBuffer = nullptr;
    }

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

JNIEXPORT jboolean JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeNeedsRecreate(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return JNI_FALSE;
    // lastTransferErrorSource == 4 means ENOENT detected, Java should recreate
    return (ctx->lastTransferErrorSource == 4 && !ctx->running.load()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeGetStats(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return nullptr;

    // 13 elements: urbComplete, isoTotal, isoErrors, audioIsoTotal, audioIsoErrors,
    //              feedbackPkts, submitFails, reapFails, underruns, underrunBytes,
    //              lastErrCode, lastErrSource, recoveryCount
    jlongArray result = env->NewLongArray(13);
    if (!result) return nullptr;

    jlong stats[13];
    stats[0]  = ctx->urbCompleteCount;
    stats[1]  = ctx->isoPacketTotal;
    stats[2]  = ctx->isoPacketErrors;
    stats[3]  = ctx->audioIsoPacketTotal;
    stats[4]  = ctx->audioIsoPacketErrors;
    stats[5]  = ctx->feedbackPacketCount;
    stats[6]  = ctx->urbSubmitFailures;
    stats[7]  = ctx->urbReapFailures;
    stats[8]  = ctx->pcmUnderruns;
    stats[9]  = ctx->pcmUnderrunBytes;
    stats[10] = ctx->lastTransferErrorCode;
    stats[11] = ctx->lastTransferErrorSource;
    stats[12] = ctx->recoveryCount;

    env->SetLongArrayRegion(result, 0, 13, stats);

    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_fold_audio_UsbAudioStream_nativeGetStateDump(
    JNIEnv *env, jobject thiz, jlong handle)
{
    auto *ctx = reinterpret_cast<UsbAudioContext *>(handle);
    if (!ctx) return env->NewStringUTF("no_context");

    char buf[2048];
    snprintf(buf, sizeof(buf),
        "Native USB State:\n"
        "  fd=%d interfaceId=%d endpointOut=0x%02X endpointFeedback=0x%02X\n"
        "  sampleRate=%d channelCount=%d bitDepth=%d bytesPerFrame=%d maxPacketSize=%d\n"
        "  running=%s ringAllocated=%s urbsInFlight=%d\n"
        "  submitIdx=%d reapIdx=%d framesWritten=%lld\n"
        "  fpmf=%.6f calibratedFpmf=%.6f frameAccumulator=%.4f\n"
        "  residualBytes=%d workBuffer=%p\n"
        "  feedbackUrb=%p feedbackInFlight=%s\n"
        "  --- Transfer Statistics ---\n"
        "  urbCompleteCount=%lld\n"
        "  isoPacketTotal=%lld isoPacketErrors=%lld\n"
        "  audioIsoPacketTotal=%lld audioIsoPacketErrors=%lld\n"
        "  feedbackPacketCount=%lld\n"
        "  urbSubmitFailures=%lld urbReapFailures=%lld\n"
        "  pcmUnderruns=%lld pcmUnderrunBytes=%lld\n"
        "  lastTransferErrorCode=%d(%s) lastTransferErrorSource=%d\n"
        "  --- Recovery State ---\n"
        "  recoveryCount=%d maxRecoveryAttempts=%d recovering=%s\n",
        ctx->fd, ctx->interfaceId, ctx->endpointOut, ctx->endpointFeedback,
        ctx->sampleRate, ctx->channelCount, ctx->bitDepth, ctx->bytesPerFrame, ctx->maxPacketSize,
        ctx->running.load() ? "true" : "false",
        ctx->ringAllocated ? "true" : "false",
        ctx->urbsInFlight,
        ctx->submitIdx, ctx->reapIdx, (long long)ctx->framesWritten,
        ctx->frameAccumulator, ctx->calibratedFpmf, ctx->frameAccumulator,
        ctx->residualBytes, ctx->workBuffer,
        ctx->feedbackUrb, ctx->feedbackInFlight ? "true" : "false",
        (long long)ctx->urbCompleteCount,
        (long long)ctx->isoPacketTotal, (long long)ctx->isoPacketErrors,
        (long long)ctx->audioIsoPacketTotal, (long long)ctx->audioIsoPacketErrors,
        (long long)ctx->feedbackPacketCount,
        (long long)ctx->urbSubmitFailures, (long long)ctx->urbReapFailures,
        (long long)ctx->pcmUnderruns, (long long)ctx->pcmUnderrunBytes,
        ctx->lastTransferErrorCode, errnoName(ctx->lastTransferErrorCode),
        ctx->lastTransferErrorSource,
        ctx->recoveryCount, ctx->maxRecoveryAttempts,
        ctx->recovering ? "true" : "false");

    // Dump ring buffer state for debugging
    if (ctx->ringAllocated && ctx->urbsInFlight > 0) {
        char *p = buf + strlen(buf);
        int remaining = sizeof(buf) - strlen(buf);
        int n = snprintf(p, remaining, "  --- Ring Buffer (inflight=%d) ---\n", ctx->urbsInFlight);
        p += n; remaining -= n;
        // Show slots near submitIdx and reapIdx
        for (int i = 0; i < USB_AUDIO_NUM_URBS && remaining > 100; i++) {
            UrbSlot *slot = &ctx->ring[i];
            n = snprintf(p, remaining, "  [%02d] urb=%p buf=%p len=%d%s%s\n",
                i, slot->urb, slot->buffer, slot->dataLength,
                (i == ctx->submitIdx) ? " <--SUBMIT" : "",
                (i == ctx->reapIdx) ? " <--REAP" : "");
            p += n; remaining -= n;
        }
    }

    return env->NewStringUTF(buf);
}

} // extern "C"
