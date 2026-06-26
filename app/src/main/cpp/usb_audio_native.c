/*
 * usb_audio_native.c — USB Audio 独占模式 native 层
 *
 * 核心职责：
 *   1. 通过 libusb 接管 USB Audio 设备（绕过内核 snd-usb-audio）
 *   2. 解析 USB Audio Class 描述符，枚举设备支持的格式
 *   3. 选择 alt setting 并配置采样率/位深
 *   4. Isochronous 传输 PCM 数据
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>
#include <android/log.h>
#include <libusb.h>

#define TAG "UsbAudioNative"
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__); file_log("I", __VA_ARGS__); } while(0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__); file_log("W", __VA_ARGS__); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); file_log("E", __VA_ARGS__); } while(0)

/* ── 文件日志（写到 /sdcard/fold/） ── */
#define NATIVE_LOG_PATH "/storage/emulated/0/fold/usb_native_log.txt"
static FILE *g_log_file = NULL;
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

static void file_log(const char *level, const char *fmt, ...) {
    pthread_mutex_lock(&g_log_mutex);
    if (!g_log_file) {
        g_log_file = fopen(NATIVE_LOG_PATH, "a");
        if (!g_log_file) {
            pthread_mutex_unlock(&g_log_mutex);
            return;
        }
    }

    /* 时间戳 */
    time_t now = time(NULL);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);
    char time_str[32];
    strftime(time_str, sizeof(time_str), "%Y-%m-%d %H:%M:%S", &tm_buf);

    fprintf(g_log_file, "[%s] [%s] ", time_str, level);

    va_list args;
    va_start(args, fmt);
    vfprintf(g_log_file, fmt, args);
    va_end(args);

    fprintf(g_log_file, "\n");
    fflush(g_log_file);

    pthread_mutex_unlock(&g_log_mutex);
}

static void file_log_open(void) {
    pthread_mutex_lock(&g_log_mutex);
    /* 每次 init 清空旧日志，方便看最新的 */
    g_log_file = fopen(NATIVE_LOG_PATH, "w");
    if (g_log_file) {
        time_t now = time(NULL);
        struct tm tm_buf;
        localtime_r(&now, &tm_buf);
        char time_str[32];
        strftime(time_str, sizeof(time_str), "%Y-%m-%d %H:%M:%S", &tm_buf);
        fprintf(g_log_file, "=== USB Audio Native Log ===\n");
        fprintf(g_log_file, "Started: %s\n\n", time_str);
        fflush(g_log_file);
    }
    pthread_mutex_unlock(&g_log_mutex);
}

static void file_log_close(void) {
    pthread_mutex_lock(&g_log_mutex);
    if (g_log_file) {
        fprintf(g_log_file, "\n=== Log Closed ===\n");
        fclose(g_log_file);
        g_log_file = NULL;
    }
    pthread_mutex_unlock(&g_log_mutex);
}

/* ── USB Audio Class 常量 ── */
#define USB_CLASS_AUDIO            0x01
#define USB_SUBCLASS_AUDIOSTREAMING 0x02
#define UAC_HEADER                 0x01
#define UAC_AS_GENERAL             0x01
#define UAC_FORMAT_TYPE            0x02
#define UAC_FORMAT_TYPE_I          0x01

/* ── UAC2 控制请求常量（来自 Linux 内核 usb/audio-v2.h） ── */
#define UAC2_CS_CUR                0x01
#define UAC2_CS_CONTROL_SAM_FREQ   0x01
#define UAC2_CLOCK_SOURCE          0x0A

/* ── Isochronous 传输参数 ── */
#define NUM_ISO_TRANSFERS    4      /* 同时提交的 transfer 数量 */
#define PACKETS_PER_TRANSFER 8      /* 每个 transfer 包含的 packet 数 */
#define MAX_PACKET_SIZE      1024   /* 单个 iso packet 最大字节数 */

/* ── PCM ring buffer ── */
#define RING_BUFFER_SIZE     (1024 * 1024)  /* 1MB */

/* ── 全局状态 ── */
typedef struct {
    /* libusb */
    libusb_context       *ctx;
    libusb_device_handle *handle;

    /* USB Audio 设备信息 */
    int interface_num;           /* streaming interface 编号 */
    int alt_setting;             /* 当前 alt setting */
    int endpoint_addr;           /* iso endpoint 地址 */
    int feedback_endpoint_addr;  /* feedback endpoint (0 = 无) */
    int max_packet_size;         /* endpoint 的 base payload 最大字节 */
    int max_bytes_per_uf;        /* 每微帧最大字节（high-bandwidth 多事务） */
    int extra_txns;              /* high-bandwidth 额外事务数 (0,1,2) */

    /* 音频格式 */
    int sample_rate;
    int bit_depth;
    int channels;
    int bytes_per_sample;        /* 每通道字节数 */
    int frame_size;              /* 一帧 = channels * bytes_per_sample */

    /* Isochronous 传输 */
    struct libusb_transfer *transfers[NUM_ISO_TRANSFERS];
    uint8_t *transfer_buffers[NUM_ISO_TRANSFERS];
    int iso_packets_per_transfer;
    int packet_size;             /* 实际每包字节数 = frame_size * samples_per_packet */
    int samples_per_packet;      /* 每 packet 的采样数（用于非分数模式） */
    int samples_base;            /* 分数计数：基础采样数（每微帧） */
    int samples_num;             /* 分数计数：分子累加器 */
    int samples_den;             /* 分数计数：分母（简化后的 8000/gcd） */
    int samples_rem;             /* 分数计数：简化后的分子（sample_rate/gcd） */
    int clock_source_id;         /* UAC2 时钟源 ID（从 AC 接口获取） */
    int clock_interface;         /* UAC2 时钟源所在接口号（通常是 AC=0） */

    /* PCM Ring Buffer */
    uint8_t *ring_buffer;
    int ring_write_pos;
    int ring_read_pos;
    int ring_available;
    pthread_mutex_t ring_mutex;

    /* 线程 */
    pthread_t event_thread;
    volatile int running;

    /* 状态 */
    int is_playing;
    int underrun_count;

    /* 自适应 buffer 管理 */
    int prebuffer_bytes;         /* 动态调整的预填充阈值（字节） */
    int consecutive_underruns;   /* 连续 underrun 计数 */
    int consecutive_ok;          /* 连续正常计数 */
    int ring_watermark;          /* 最低 ring buffer 水位记录 */

} UsbAudioState;

static UsbAudioState g_state = {0};

/* ── Ring Buffer 操作 ── */

static void ring_reset(void) {
    pthread_mutex_lock(&g_state.ring_mutex);
    g_state.ring_write_pos = 0;
    g_state.ring_read_pos  = 0;
    g_state.ring_available  = 0;
    pthread_mutex_unlock(&g_state.ring_mutex);
}

static int ring_write(const uint8_t *data, int len) {
    pthread_mutex_lock(&g_state.ring_mutex);
    int space = RING_BUFFER_SIZE - g_state.ring_available;
    int to_write = (len < space) ? len : space;
    int written = 0;
    while (written < to_write) {
        int chunk = to_write - written;
        int until_end = RING_BUFFER_SIZE - g_state.ring_write_pos;
        if (chunk > until_end) chunk = until_end;
        memcpy(g_state.ring_buffer + g_state.ring_write_pos, data + written, chunk);
        g_state.ring_write_pos = (g_state.ring_write_pos + chunk) % RING_BUFFER_SIZE;
        g_state.ring_available += chunk;
        written += chunk;
    }
    pthread_mutex_unlock(&g_state.ring_mutex);
    return written;
}

/* 返回实际读取的字节数（不含零填充部分），用于 underrun 检测 */
static int ring_read(uint8_t *dest, int len) {
    pthread_mutex_lock(&g_state.ring_mutex);
    int avail = g_state.ring_available;
    int to_read = (len < avail) ? len : avail;
    int read_count = 0;
    while (read_count < to_read) {
        int chunk = to_read - read_count;
        int until_end = RING_BUFFER_SIZE - g_state.ring_read_pos;
        if (chunk > until_end) chunk = until_end;
        memcpy(dest + read_count, g_state.ring_buffer + g_state.ring_read_pos, chunk);
        g_state.ring_read_pos = (g_state.ring_read_pos + chunk) % RING_BUFFER_SIZE;
        g_state.ring_available -= chunk;
        read_count += chunk;
    }
    pthread_mutex_unlock(&g_state.ring_mutex);
    /* underrun 部分填零 */
    if (read_count < len) {
        memset(dest + read_count, 0, len - read_count);
    }
    return read_count;  /* 返回实际数据字节数，不含零填充 */
}

/* ── libusb event 处理线程 ── */

static void *event_thread_func(void *arg) {
    /* 设置实时调度优先级，减少 GC/系统调度导致的 ISO 传输抖动
     * SCHED_FIFO + 高优先级确保 USB 音频线程不被普通线程抢占 */
    struct sched_param param;
    int max_prio = sched_get_priority_max(SCHED_FIFO);
    param.sched_priority = max_prio > 10 ? max_prio - 10 : max_prio;
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) == 0) {
        LOGI("Event thread: SCHED_FIFO priority=%d", param.sched_priority);
    } else {
        LOGW("Event thread: failed to set SCHED_FIFO (need CAP_SYS_NICE), continuing with default priority");
    }

    LOGI("Event thread started");
    while (g_state.running) {
        struct timeval tv = {0, 100000}; /* 100ms timeout */
        int ret = libusb_handle_events_timeout_completed(g_state.ctx, &tv, NULL);
        if (ret < 0 && ret != LIBUSB_ERROR_TIMEOUT) {
            LOGE("libusb_handle_events error: %s", libusb_error_name(ret));
        }
    }
    LOGI("Event thread exiting");
    return NULL;
}

/*
 * 发送 UAC2 SET_CUR 请求设置时钟源采样率
 * 等价于 Linux 内核 snd_usb_set_sample_rate_v2v3()
 * 这是 ADAPTIVE endpoint 正常工作所必需的
 */
static int send_sample_rate_cur(int sample_rate) {
    if (!g_state.handle || g_state.clock_source_id <= 0) {
        LOGW("Cannot set sample rate: no handle or clock_source_id");
        return -1;
    }

    uint8_t data[4];
    data[0] = (uint8_t)(sample_rate & 0xFF);
    data[1] = (uint8_t)((sample_rate >> 8) & 0xFF);
    data[2] = (uint8_t)((sample_rate >> 16) & 0xFF);
    data[3] = (uint8_t)((sample_rate >> 24) & 0xFF);

    /* UAC2 SET_CUR on clock source:
     * bmRequestType: USB_TYPE_CLASS | USB_RECIP_INTERFACE | USB_DIR_OUT = 0x21
     * bRequest: UAC2_CS_CUR = 0x01
     * wValue: UAC2_CS_CONTROL_SAM_FREQ << 8 = 0x0100
     * wIndex: ac_interface_number | (clock_source_id << 8)
     * 注意：必须用 AC 接口号（通常是 0），不是 AS 接口号
     */
    uint16_t wValue = UAC2_CS_CONTROL_SAM_FREQ << 8;
    uint16_t wIndex = g_state.clock_interface | (g_state.clock_source_id << 8);

    LOGI("SET_CUR clock source: rate=%d clock_id=%d wValue=0x%04X wIndex=0x%04X",
         sample_rate, g_state.clock_source_id, wValue, wIndex);

    int ret = libusb_control_transfer(
        g_state.handle,
        LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE | LIBUSB_ENDPOINT_OUT,
        UAC2_CS_CUR,
        wValue,
        wIndex,
        data,
        sizeof(data),
        1000  /* 1s timeout */
    );

    if (ret < 0) {
        LOGW("SET_CUR sample rate failed: %s (ret=%d)", libusb_error_name(ret), ret);
        return ret;
    }

    LOGI("SET_CUR sample rate OK: %d Hz", sample_rate);
    return 0;
}

/* ── Isochronous 回调 ── */

static void LIBUSB_CALL iso_callback(struct libusb_transfer *transfer) {
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED ||
        transfer->status == LIBUSB_TRANSFER_TIMED_OUT) {

        if (g_state.is_playing) {
            uint8_t *buf = transfer->buffer;
            int num_pkts = transfer->num_iso_packets;

            /*
             * 计算每个 packet 的大小（分数计数）并设置 iso_packet_desc
             * 使用共享累加器实现 44100Hz 等非整数倍采样率的精确包大小
             */
            int total_size = 0;
            for (int i = 0; i < num_pkts; i++) {
                int this_samples = g_state.samples_base;
                g_state.samples_num += g_state.samples_rem;
                if (g_state.samples_num >= g_state.samples_den) {
                    this_samples++;
                    g_state.samples_num -= g_state.samples_den;
                }
                int this_pkt_size = this_samples * g_state.frame_size;
                transfer->iso_packet_desc[i].length = this_pkt_size;
                total_size += this_pkt_size;
            }

            /*
             * 自适应预填充检查：
             * - 播放初期：等待 buffer 积累足够数据，避免 underrun
             * - 运行中：如果连续 underrun，增加预填充阈值（最大 200ms）
             * - 如果稳定运行，逐步降低阈值（最小 20ms）
             */
            pthread_mutex_lock(&g_state.ring_mutex);
            int avail = g_state.ring_available;
            pthread_mutex_unlock(&g_state.ring_mutex);

            /* 记录最低水位 */
            if (avail < g_state.ring_watermark) {
                g_state.ring_watermark = avail;
            }

            if (avail < g_state.prebuffer_bytes) {
                /* 数据不足，发送静音等待 buffer 填充 */
                memset(buf, 0, total_size);
                g_state.consecutive_underruns++;
                g_state.consecutive_ok = 0;

                /* 连续 underrun 时增加预填充阈值 */
                if (g_state.consecutive_underruns >= 3) {
                    int max_prebuf = g_state.sample_rate * g_state.frame_size / 5; /* 200ms */
                    g_state.prebuffer_bytes = (g_state.prebuffer_bytes * 3 / 2 < max_prebuf)
                        ? g_state.prebuffer_bytes * 3 / 2 : max_prebuf;
                    LOGW("Adaptive buffer: increased prebuffer to %d bytes (%d ms), "
                         "consecutive underruns=%d",
                         g_state.prebuffer_bytes,
                         g_state.prebuffer_bytes * 1000 / (g_state.sample_rate * g_state.frame_size),
                         g_state.consecutive_underruns);
                    g_state.consecutive_underruns = 0;
                }
            } else {
                /* 数据充足，正常读取 */
                int actual = ring_read(buf, total_size);
                if (actual < total_size) {
                    g_state.consecutive_underruns++;
                    g_state.consecutive_ok = 0;
                } else {
                    g_state.consecutive_underruns = 0;
                    g_state.consecutive_ok++;

                    /* 稳定运行 500 次后，逐步降低预填充阈值 */
                    if (g_state.consecutive_ok >= 500) {
                        int min_prebuf = g_state.sample_rate * g_state.frame_size / 50; /* 20ms */
                        if (g_state.prebuffer_bytes > min_prebuf) {
                            g_state.prebuffer_bytes = g_state.prebuffer_bytes * 9 / 10;
                            LOGI("Adaptive buffer: decreased prebuffer to %d bytes (%d ms)",
                                 g_state.prebuffer_bytes,
                                 g_state.prebuffer_bytes * 1000 / (g_state.sample_rate * g_state.frame_size));
                        }
                        g_state.consecutive_ok = 0;
                    }
                }
            }

            int ret = libusb_submit_transfer(transfer);
            if (ret < 0) {
                LOGE("Re-submit failed: %s", libusb_error_name(ret));
            }
        }
    } else if (transfer->status == LIBUSB_TRANSFER_CANCELLED) {
        LOGI("Transfer cancelled");
    } else {
        LOGW("Transfer status: %d", transfer->status);
    }
}

/* ── JNI 方法 ── */

/*
 * 初始化 libusb 并接管 USB 设备
 * @param fd 从 Android UsbManager.openDevice() 获取的文件描述符
 */
JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeInit(
    JNIEnv *env, jobject thiz, jint fd)
{
    int ret;

    /* 打开文件日志 */
    file_log_open();
    LOGI("=== nativeInit called, fd=%d ===", fd);

    const struct libusb_version *ver = libusb_get_version();
    LOGI("libusb version: %d.%d.%d.%d (%s)", ver->major, ver->minor, ver->micro, ver->nano, ver->describe);

    /* 清理旧 session（扫描阶段可能 claim 了 interface） */
    if (g_state.handle) {
        LOGI("Cleaning up previous libusb session");
        for (int r = 0; r < 10; r++) {
            libusb_release_interface(g_state.handle, r);
        }
        libusb_close(g_state.handle);
        g_state.handle = NULL;
    }
    if (g_state.ctx) {
        libusb_exit(g_state.ctx);
        g_state.ctx = NULL;
    }

    memset(&g_state, 0, sizeof(g_state));
    pthread_mutex_init(&g_state.ring_mutex, NULL);

    /* 跳过 USB 总线扫描（Android SELinux 下会失败，我们用 wrap_sys_device 不需要） */
    ret = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (ret < 0) {
        LOGW("libusb_set_option(NO_DEVICE_DISCOVERY) failed: %s", libusb_error_name(ret));
    } else {
        LOGI("Set LIBUSB_OPTION_NO_DEVICE_DISCOVERY");
    }

    /* 初始化 libusb */
    ret = libusb_init(&g_state.ctx);
    if (ret < 0) {
        LOGE("libusb_init failed: %s (ret=%d)", libusb_error_name(ret), ret);
        return ret;
    }
    LOGI("libusb_init OK, ctx=%p", g_state.ctx);

    /* 用 Android 给的 fd 包装成 libusb handle */
    LOGI("Calling libusb_wrap_sys_device, fd=%d, ctx=%p", (int)fd, g_state.ctx);
    ret = libusb_wrap_sys_device(g_state.ctx, (intptr_t)fd, &g_state.handle);
    if (ret < 0) {
        LOGE("libusb_wrap_sys_device failed: %s (ret=%d)", libusb_error_name(ret), ret);
        libusb_exit(g_state.ctx);
        return ret;
    }
    LOGI("libusb_wrap_sys_device OK, handle=%p", g_state.handle);

    LOGI("USB device initialized, fd=%d", fd);

    /* 尝试脱离内核驱动（获取独占访问） */
    for (int i = 0; i < 10; i++) {
        if (libusb_kernel_driver_active(g_state.handle, i) == 1) {
            ret = libusb_detach_kernel_driver(g_state.handle, i);
            if (ret < 0) {
                LOGW("detach kernel driver iface %d: %s", i, libusb_error_name(ret));
            } else {
                LOGI("Detached kernel driver from interface %d", i);
            }
        }
    }

    /* 分配 ring buffer */
    g_state.ring_buffer = (uint8_t *)malloc(RING_BUFFER_SIZE);
    if (!g_state.ring_buffer) {
        LOGE("ring buffer alloc failed (requested %d bytes)", RING_BUFFER_SIZE);
        libusb_close(g_state.handle);
        libusb_exit(g_state.ctx);
        return -1;
    }
    LOGI("Ring buffer allocated: %d bytes at %p", RING_BUFFER_SIZE, g_state.ring_buffer);

    LOGI("=== nativeInit SUCCESS ===");
    return 0;
}

/*
 * 解析 USB Audio Class 描述符，枚举所有支持的格式
 * 返回格式化字符串（供 Kotlin 显示）
 */
JNIEXPORT jstring JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeParseDescriptors(
    JNIEnv *env, jobject thiz)
{
    libusb_device *dev = libusb_get_device(g_state.handle);
    struct libusb_config_descriptor *config;
    int ret = libusb_get_active_config_descriptor(dev, &config);
    if (ret < 0) {
        return (*env)->NewStringUTF(env, "ERROR: 无法获取配置描述符");
    }

    char result[4096] = {0};
    int offset = 0;

    offset += snprintf(result + offset, sizeof(result) - offset,
                       "=== USB Audio 描述符解析 ===\n\n");

    /* 遍历所有 interface */
    for (int i = 0; i < config->bNumInterfaces; i++) {
        const struct libusb_interface *iface = &config->interface[i];

        for (int j = 0; j < iface->num_altsetting; j++) {
            const struct libusb_interface_descriptor *alt = &iface->altsetting[j];

            /* 只关心 Audio Streaming interfaces */
            if (alt->bInterfaceClass != USB_CLASS_AUDIO ||
                alt->bInterfaceSubClass != USB_SUBCLASS_AUDIOSTREAMING) {
                continue;
            }

            offset += snprintf(result + offset, sizeof(result) - offset,
                               "--- Interface %d, Alt Setting %d (class=0x%02X sub=0x%02X proto=0x%02X) ---\n",
                               alt->bInterfaceNumber, alt->bAlternateSetting,
                               alt->bInterfaceClass, alt->bInterfaceSubClass,
                               alt->bInterfaceProtocol);
            offset += snprintf(result + offset, sizeof(result) - offset,
                               "Endpoints: %d, Extra: %d bytes\n", alt->bNumEndpoints, alt->extra_length);

            /* 解析 class-specific 描述符 */
            const uint8_t *extra = alt->extra;
            int extra_len = alt->extra_length;
            int pos = 0;

            while (pos < extra_len) {
                uint8_t bLength = extra[pos];
                if (bLength < 2 || pos + bLength > extra_len) break;

                uint8_t bDescriptorType = extra[pos + 1];
                uint8_t bDescriptorSubtype = 0;

                if (bLength >= 3) bDescriptorSubtype = extra[pos + 2];

                /* CS_INTERFACE = 0x24 */
                if (bDescriptorType == 0x24) {
                    if (bDescriptorSubtype == UAC_AS_GENERAL && bLength >= 7) {
                        /* AS_GENERAL descriptor */
                        uint16_t wFormatTag = extra[pos + 5] | (extra[pos + 6] << 8);
                        offset += snprintf(result + offset, sizeof(result) - offset,
                                           "  AS_GENERAL: format_tag=0x%04X %s\n",
                                           wFormatTag,
                                           wFormatTag == 1 ? "(PCM)" :
                                           wFormatTag == 0x8000 ? "(MPEG)" : "(other)");
                    }
                    else if (bDescriptorSubtype == UAC_FORMAT_TYPE && bLength >= 8) {
                        /* FORMAT_TYPE descriptor */
                        uint8_t bFormatType = extra[pos + 3];

                        if (bFormatType == UAC_FORMAT_TYPE_I) {
                            /* UAC2 protocol=0x20 有 bSubSlotSize 字段 */
                            int uac_ver = (alt->bInterfaceProtocol == 0x20) ? 2 : 1;
                            uint8_t bNrChannels, bSubframeSize, bBitResolution, bSamFreqType;

                            if (uac_ver == 2 && bLength >= 8) {
                                bNrChannels    = extra[pos + 4];
                                bSubframeSize  = extra[pos + 5];
                                bBitResolution = extra[pos + 6];
                                bSamFreqType   = extra[pos + 7];
                            } else if (bLength >= 8) {
                                bNrChannels    = extra[pos + 4];
                                bSubframeSize  = extra[pos + 5];
                                bBitResolution = extra[pos + 6];
                                bSamFreqType   = extra[pos + 7];
                            } else {
                                break;
                            }

                            offset += snprintf(result + offset, sizeof(result) - offset,
                                               "  UAC%d FORMAT_TYPE_I: %dch %dbit (subframe=%d)\n",
                                               uac_ver, bNrChannels, bBitResolution, bSubframeSize);

                            if (bSamFreqType == 0 && bLength >= 14) {
                                /* 连续范围 */
                                uint32_t min_freq = extra[pos+8] | (extra[pos+9]<<8) | (extra[pos+10]<<16);
                                uint32_t max_freq = extra[pos+11]| (extra[pos+12]<<8)| (extra[pos+13]<<16);
                                offset += snprintf(result + offset, sizeof(result) - offset,
                                                   "  采样率范围: %u - %u Hz\n", min_freq, max_freq);
                            } else {
                                /* 离散列表 */
                                offset += snprintf(result + offset, sizeof(result) - offset,
                                                   "  采样率列表: ");
                                for (int k = 0; k < bSamFreqType && (pos + 8 + k*3 + 2) < extra_len; k++) {
                                    uint32_t freq = extra[pos+8+k*3]
                                                  | (extra[pos+9+k*3] << 8)
                                                  | (extra[pos+10+k*3] << 16);
                                    offset += snprintf(result + offset, sizeof(result) - offset,
                                                       "%u ", freq);
                                }
                                offset += snprintf(result + offset, sizeof(result) - offset, "\n");
                            }
                        }
                    }
                }

                pos += bLength;
            }

            /* endpoint 信息 */
            for (int e = 0; e < alt->bNumEndpoints; e++) {
                const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
                uint8_t ep_type = ep->bmAttributes & 0x03;
                if (ep_type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                    uint8_t ep_addr = ep->bEndpointAddress;
                    uint16_t max_pkt = ep->wMaxPacketSize;
                    offset += snprintf(result + offset, sizeof(result) - offset,
                                       "  ISO EP: 0x%02X (dir=%s) max_pkt=%d\n",
                                       ep_addr,
                                       (ep_addr & 0x80) ? "IN" : "OUT",
                                       max_pkt);
                }
            }

            offset += snprintf(result + offset, sizeof(result) - offset, "\n");
        }
    }

    libusb_free_config_descriptor(config);
    return (*env)->NewStringUTF(env, result);
}

/*
 * 选择合适的 alt setting 并 claim interface
 * 支持 UAC1 和 UAC2 描述符
 */
JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeSetFormat(
    JNIEnv *env, jobject thiz,
    jint sample_rate, jint bit_depth, jint channels)
{
    g_state.sample_rate    = sample_rate;
    g_state.bit_depth      = bit_depth;
    g_state.channels       = channels;
    g_state.bytes_per_sample = (bit_depth <= 16) ? 2 : (bit_depth <= 24) ? 4 : 4;
    g_state.frame_size     = channels * g_state.bytes_per_sample;

    LOGI("=== nativeSetFormat: %dHz %dbit %dch (frame_size=%d) ===",
         sample_rate, bit_depth, channels, g_state.frame_size);

    libusb_device *dev = libusb_get_device(g_state.handle);
    struct libusb_config_descriptor *config;
    int ret = libusb_get_active_config_descriptor(dev, &config);
    if (ret < 0) {
        LOGE("get_active_config_descriptor failed: %d", ret);
        return ret;
    }
    LOGI("Config: %d interfaces", config->bNumInterfaces);

    int found = 0;
    int found_inferred_subslot = 0;   /* fallback: inferred from endpoint max_pkt */

    for (int i = 0; i < config->bNumInterfaces && !found; i++) {
        const struct libusb_interface *iface = &config->interface[i];
        LOGI("Interface %d: %d alt settings", i, iface->num_altsetting);

        for (int j = 0; j < iface->num_altsetting && !found; j++) {
            const struct libusb_interface_descriptor *alt = &iface->altsetting[j];

            LOGI("  Alt %d: class=0x%02X subclass=0x%02X protocol=0x%02X endpoints=%d extra_len=%d",
                 alt->bAlternateSetting,
                 alt->bInterfaceClass, alt->bInterfaceSubClass, alt->bInterfaceProtocol,
                 alt->bNumEndpoints, alt->extra_length);

            if (alt->bInterfaceClass != USB_CLASS_AUDIO ||
                alt->bInterfaceSubClass != USB_SUBCLASS_AUDIOSTREAMING) {
                continue;
            }

            /* 跳过 zero-bandwidth (alt=0 通常没有 endpoint) */
            if (alt->bAlternateSetting == 0) {
                LOGI("    (zero-bandwidth, skip)");
                continue;
            }

            /* 确定 UAC 版本 */
            int uac_version = 1;
            if (alt->bInterfaceProtocol == 0x20) uac_version = 2;
            LOGI("    UAC version: %d", uac_version);

            /* 找 iso out endpoint */
            int ep_addr = -1;
            int max_pkt = 0;          /* 实际每包最大 payload 字节 */
            int max_bytes_per_uf = 0; /* 每微帧最大字节（含 high-bandwidth 多事务） */
            int extra_txns = 0;       /* high-bandwidth 额外事务数 */
            for (int e = 0; e < alt->bNumEndpoints; e++) {
                const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
                uint8_t ep_type = ep->bmAttributes & 0x03;
                /* 找 OUT (host→device) isochronous endpoint */
                if (ep_type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS &&
                    !(ep->bEndpointAddress & 0x80)) {
                    ep_addr = ep->bEndpointAddress;
                    /*
                     * wMaxPacketSize:
                     *   bits 0-10: 最大包大小（payload）
                     *   bits 11-10: 每微帧额外事务数（0=1tx, 1=2tx, 2=3tx）
                     * USB 2.0 High-Speed high-bandwidth isochronous
                     */
                    uint16_t raw_mps = ep->wMaxPacketSize;
                    max_pkt = raw_mps & 0x7FF;
                    extra_txns = (raw_mps >> 11) & 0x03;
                    max_bytes_per_uf = max_pkt * (1 + extra_txns);
                    LOGI("    EP OUT: addr=0x%02X raw_wMaxPacketSize=0x%04X "
                         "base_pkt=%d extra_txns=%d max_bytes_per_uf=%d",
                         ep_addr, raw_mps, max_pkt, extra_txns, max_bytes_per_uf);
                }
            }
            if (ep_addr < 0) {
                LOGI("    No ISO OUT endpoint, skip");
                continue;
            }

            /* dump extra 描述符 (class-specific) */
            const uint8_t *extra = alt->extra;
            int extra_len = alt->extra_length;
            if (extra_len > 0) {
                char hexdump[512] = {0};
                int hoff = 0;
                for (int h = 0; h < extra_len && hoff < (int)sizeof(hexdump) - 4; h++) {
                    hoff += snprintf(hexdump + hoff, sizeof(hexdump) - hoff, "%02X ", extra[h]);
                }
                LOGI("    Extra (%d bytes): %s", extra_len, hexdump);
            }

            /* 解析 FORMAT_TYPE 描述符 */
            int pos = 0;
            while (pos < extra_len) {
                uint8_t bLength = extra[pos];
                if (bLength < 2 || pos + bLength > extra_len) break;

                uint8_t bDescType = extra[pos + 1];
                uint8_t bSubtype = (bLength >= 3) ? extra[pos + 2] : 0;

                if (bDescType == 0x24 && bSubtype == UAC_FORMAT_TYPE &&
                    bLength >= 4 && extra[pos + 3] == UAC_FORMAT_TYPE_I) {

                    uint8_t nr_ch, subframe, bit_res, freq_type;

                    /*
                     * UAC2 FORMAT_TYPE_I: MOONDROP Rays 等设备的 bLength=6（应为10）
                     * 不能依赖 bLength 判断字段是否可用，直接按固定偏移读取
                     * 如果 bBitResolution 为 0，用 bSubSlotSize 替代
                     */
                    if (uac_version == 2) {
                        /* 至少需要 6 字节：header(4) + bNrChannels + bSubSlotSize */
                        if (extra_len - pos < 6) {
                            LOGW("    UAC2 FORMAT_TYPE_I too short (%d bytes from pos), skip",
                                 extra_len - pos);
                            goto next;
                        }
                        nr_ch     = extra[pos + 4];
                        subframe  = extra[pos + 5];
                        /* bBitResolution 可能因 bLength bug 越界 */
                        bit_res   = (extra_len - pos >= 7) ? extra[pos + 6] : 0;
                        freq_type = (extra_len - pos >= 8) ? extra[pos + 7] : 0;

                        /* bBitResolution=0 时用 subslot size 替代 */
                        if (bit_res == 0) {
                            LOGI("    UAC2 FORMAT_TYPE_I: bit_res=0 (bLength bug), using subslot=%d",
                                 subframe);
                            bit_res = subframe;
                        }

                        LOGI("    UAC2 FORMAT_TYPE_I: %dch subslot=%d res=%dbit freq_type=%d (bLength=%d, IGNORED)",
                             nr_ch, subframe, bit_res, freq_type, bLength);
                    } else {
                        if (bLength < 8) {
                            LOGW("    UAC1 FORMAT_TYPE_I bLength=%d < 8, skip", bLength);
                            goto next;
                        }
                        nr_ch     = extra[pos + 4];
                        subframe  = extra[pos + 5];
                        bit_res   = extra[pos + 6];
                        freq_type = extra[pos + 7];
                        LOGI("    UAC1 FORMAT_TYPE_I: %dch subframe=%d res=%dbit freq_type=%d",
                             nr_ch, subframe, bit_res, freq_type);
                    }

                    /* 匹配检查 */
                    if (nr_ch != channels) {
                        LOGI("    channel mismatch: want %d got %d", channels, nr_ch);
                        goto next;
                    }
                    if (bit_res != bit_depth) {
                        LOGI("    bit_depth mismatch: want %d got %d", bit_depth, bit_res);
                        goto next;
                    }

                    /* 检查采样率（同样忽略 bLength，用 extra_len 做边界检查） */
                    int rate_ok = 0;
                    int freq_base = pos + 8;
                    if (freq_type == 0) {
                        /* 连续范围：需要 6 字节 min+max */
                        if (freq_base + 5 < extra_len) {
                            uint32_t min_f = extra[freq_base]|(extra[freq_base+1]<<8)|(extra[freq_base+2]<<16);
                            uint32_t max_f = extra[freq_base+3]|(extra[freq_base+4]<<8)|(extra[freq_base+5]<<16);
                            LOGI("    Rate range: %u - %u Hz, want %d", min_f, max_f, sample_rate);
                            if ((uint32_t)sample_rate >= min_f && (uint32_t)sample_rate <= max_f)
                                rate_ok = 1;
                        } else {
                            /* 没有频率数据——设备 bug，用 subslot 信息推断 */
                            LOGW("    No freq data in descriptor (extra_len=%d), assuming supported", extra_len);
                            rate_ok = 1;
                        }
                    } else {
                        for (int k = 0; k < freq_type; k++) {
                            int off = freq_base + k * 3;
                            if (off + 2 >= extra_len) break;
                            uint32_t freq = extra[off]|(extra[off+1]<<8)|(extra[off+2]<<16);
                            LOGI("    Supported freq[%d]: %u Hz", k, freq);
                            if (freq == (uint32_t)sample_rate) { rate_ok = 1; }
                        }
                    }
                    if (!rate_ok) {
                        LOGI("    Rate %d not supported", sample_rate);
                        goto next;
                    }

                    /* 匹配成功！ */
                    g_state.interface_num   = alt->bInterfaceNumber;
                    g_state.alt_setting     = alt->bAlternateSetting;
                    g_state.endpoint_addr   = ep_addr;
                    g_state.max_packet_size = max_pkt;
                    g_state.max_bytes_per_uf = max_bytes_per_uf;
                    g_state.extra_txns      = extra_txns;
                    found = 1;

                    LOGI("*** MATCHED: iface=%d alt=%d ep=0x%02X max_pkt=%d max_bytes_uf=%d extra_txns=%d uac=%d ***",
                         g_state.interface_num, g_state.alt_setting,
                         g_state.endpoint_addr, g_state.max_packet_size,
                         g_state.max_bytes_per_uf, g_state.extra_txns, uac_version);
                }

next:
                pos += bLength;
            }
        }
    }

    /*
     * Fallback: 对于 bLength=6 的截断 UAC2 描述符（如 MOONDROP Rays），
     * bSubSlotSize 可能不代表实际 subslot 大小。
     * 用 endpoint max_pkt 推断正确的 subslot，重新匹配。
     */
    if (!found) {
        int inferred_subslot = g_state.bytes_per_sample;
        int samples_per_uf = (sample_rate + 7999) / 8000;

        for (int i = 0; i < config->bNumInterfaces && !found; i++) {
            const struct libusb_interface *iface = &config->interface[i];
            for (int j = 0; j < iface->num_altsetting && !found; j++) {
                const struct libusb_interface_descriptor *alt = &iface->altsetting[j];
                if (alt->bInterfaceClass != USB_CLASS_AUDIO ||
                    alt->bInterfaceSubClass != USB_SUBCLASS_AUDIOSTREAMING ||
                    alt->bAlternateSetting == 0) continue;

                int uac_version = (alt->bInterfaceProtocol == 0x20) ? 2 : 1;
                int ep_addr = -1, max_pkt = 0, max_bytes_per_uf = 0, extra_txns = 0;
                for (int e = 0; e < alt->bNumEndpoints; e++) {
                    const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
                    if ((ep->bmAttributes & 0x03) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS &&
                        !(ep->bEndpointAddress & 0x80)) {
                        ep_addr = ep->bEndpointAddress;
                        uint16_t raw_mps = ep->wMaxPacketSize;
                        max_pkt = raw_mps & 0x7FF;
                        extra_txns = (raw_mps >> 11) & 0x03;
                        max_bytes_per_uf = max_pkt * (1 + extra_txns);
                    }
                }
                if (ep_addr < 0) continue;

                const uint8_t *extra = alt->extra;
                int extra_len = alt->extra_length;
                int pos = 0;
                while (pos < extra_len) {
                    uint8_t bLength = extra[pos];
                    if (bLength < 2 || pos + bLength > extra_len) break;
                    uint8_t bDescType = extra[pos + 1];
                    uint8_t bSubtype = (bLength >= 3) ? extra[pos + 2] : 0;

                    if (bDescType == 0x24 && bSubtype == UAC_FORMAT_TYPE &&
                        bLength >= 4 && extra[pos + 3] == UAC_FORMAT_TYPE_I) {

                        uint8_t nr_ch = extra[pos + 4];
                        uint8_t desc_subslot = (extra_len - pos >= 6) ? extra[pos + 5] : 0;

                        /*
                         * 对于 bLength=6 的截断描述符（如 MOONDROP Rays），
                         * bNrChannels 和 bSubSlotSize 可能都被损坏（值非标准）。
                         * 不检查 nr_ch，只基于 desc_subslot 匹配。
                         */

                        int rate_ok = 0;
                        int freq_type = (extra_len - pos >= 8) ? extra[pos + 7] : 0;
                        int freq_base = pos + 8;
                        if (freq_type == 0) {
                            if (freq_base + 5 < extra_len) {
                                uint32_t min_f = extra[freq_base]|(extra[freq_base+1]<<8)|(extra[freq_base+2]<<16);
                                uint32_t max_f = extra[freq_base+3]|(extra[freq_base+4]<<8)|(extra[freq_base+5]<<16);
                                if ((uint32_t)sample_rate >= min_f && (uint32_t)sample_rate <= max_f) rate_ok = 1;
                            } else {
                                rate_ok = 1;  /* 无频率数据，假设支持 */
                            }
                        } else {
                            for (int k = 0; k < freq_type; k++) {
                                int off = freq_base + k * 3;
                                if (off + 2 >= extra_len) break;
                                uint32_t freq = extra[off]|(extra[off+1]<<8)|(extra[off+2]<<16);
                                if (freq == (uint32_t)sample_rate) { rate_ok = 1; break; }
                            }
                        }
                        if (!rate_ok) goto next_fb;

                        int pkt_needed = (int)(inferred_subslot * channels * samples_per_uf);
                        if (desc_subslot == inferred_subslot && pkt_needed <= max_pkt) {
                            /* 描述符 subslot 匹配且 packet 大小足够 */
                            found = 1;
                            found_inferred_subslot = 1;
                            g_state.interface_num   = alt->bInterfaceNumber;
                            g_state.alt_setting     = alt->bAlternateSetting;
                            g_state.endpoint_addr   = ep_addr;
                            g_state.max_packet_size = max_pkt;
                            g_state.max_bytes_per_uf = max_bytes_per_uf;
                            g_state.extra_txns      = extra_txns;
                            LOGI("*** FALLBACK MATCHED (subslot=%d): iface=%d alt=%d ep=0x%02X "
                                 "max_pkt=%d uac=%d ***",
                                 desc_subslot, g_state.interface_num, g_state.alt_setting,
                                 g_state.endpoint_addr, g_state.max_packet_size,
                                 uac_version);
                        } else if (desc_subslot != inferred_subslot && pkt_needed <= max_pkt) {
                            /*
                             * 描述符 subslot 不匹配，但 endpoint 有足够带宽。
                             * 记录为候选——优先选择 max_pkt 最小的（更可能是正确格式）。
                             * 对于 MOONDROP Rays：bSubSlotSize=2/3/4 对应 16/24/32bit，
                             * 各 alt 的 max_pkt 递增 (248,372,496)。
                             */
                            if (!found || max_pkt < g_state.max_packet_size) {
                                found = 1;
                                found_inferred_subslot = 1;
                                g_state.interface_num   = alt->bInterfaceNumber;
                                g_state.alt_setting     = alt->bAlternateSetting;
                                g_state.endpoint_addr   = ep_addr;
                                g_state.max_packet_size = max_pkt;
                                g_state.max_bytes_per_uf = max_bytes_per_uf;
                                g_state.extra_txns      = extra_txns;
                                LOGI("*** FALLBACK ACCEPTED (pkt fit): iface=%d alt=%d "
                                     "desc_subslot=%d ep=0x%02X max_pkt=%d uac=%d ***",
                                     g_state.interface_num, g_state.alt_setting,
                                     desc_subslot, g_state.endpoint_addr,
                                     g_state.max_packet_size, uac_version);
                            }
                        }
                    }
next_fb:
                    pos += bLength;
                }
            }
        }
    }

    /* 从 AudioControl 接口的 clock source 实体提取时钟源 ID
     * UAC2 的 clock source 定义在 AC 接口（bInterfaceSubClass=0x01），
     * 不在 AS 接口。旧代码只搜 AS 接口导致 clock_source_id=0，SET_CUR 不发送，
     * 设备不知道采样率 → 输出噪声 */
    if (g_state.clock_source_id == 0) {
        for (int i = 0; i < config->bNumInterfaces; i++) {
            const struct libusb_interface *iface = &config->interface[i];
            if (iface->num_altsetting == 0) continue;
            /* 搜索所有接口的 alt setting 0（主描述符） */
            for (int a = 0; a < iface->num_altsetting; a++) {
                const uint8_t *extra = iface->altsetting[a].extra;
                int extra_len = iface->altsetting[a].extra_length;
                int apos = 0;
                while (apos + 2 < extra_len) {
                    uint8_t bLen = extra[apos];
                    if (bLen < 3 || apos + bLen > extra_len) break;
                    uint8_t bDescType = extra[apos + 1];
                    uint8_t bSubtype = extra[apos + 2];
                    if (bDescType == 0x24 && bSubtype == 0x0A && bLen >= 4) {
                        /* CLOCK_SOURCE 实体 */
                        g_state.clock_source_id = extra[apos + 3];
                        g_state.clock_interface = iface->altsetting[a].bInterfaceNumber;
                        LOGI("Clock source ID=%d from interface %d alt %d (bLen=%d)",
                             g_state.clock_source_id,
                             g_state.clock_interface,
                             iface->altsetting[a].bAlternateSetting, bLen);
                        goto found_clock;
                    }
                    apos += bLen;
                }
            }
        }
        found_clock:
        if (g_state.clock_source_id == 0) {
            LOGW("No clock source found in any interface descriptor");
        }
    }

    libusb_free_config_descriptor(config);

    if (!found) {
        LOGE("No matching alt setting found for %dHz %dbit %dch",
             sample_rate, bit_depth, channels);
        return -1;
    }

    /* 先释放所有已占用的 interface（格式扫描阶段可能 claim 过） */
    if (g_state.is_playing) {
        LOGI("Previous session still active, stopping first");
        g_state.is_playing = 0;
        g_state.running = 0;
        if (g_state.event_thread) {
            pthread_join(g_state.event_thread, NULL);
            g_state.event_thread = 0;
        }
    }
    /* 释放所有 interface，避免扫描阶段遗留的 claim */
    for (int r = 0; r < 10; r++) {
        libusb_release_interface(g_state.handle, r);
    }

    /* Claim interface */
    ret = libusb_claim_interface(g_state.handle, g_state.interface_num);
    if (ret < 0) {
        LOGE("claim_interface failed: %s", libusb_error_name(ret));
        return ret;
    }
    LOGI("Claimed interface %d", g_state.interface_num);

    /* Select alt setting */
    ret = libusb_set_interface_alt_setting(
        g_state.handle, g_state.interface_num, g_state.alt_setting);
    if (ret < 0) {
        LOGE("set_interface_alt_setting failed: %s", libusb_error_name(ret));
        return ret;
    }
    LOGI("Set alt setting %d", g_state.alt_setting);

    /*
     * 计算分数采样率（修正 44100Hz 等非 8000 整数倍的采样率）
     *
     * USB 2.0 High-Speed 微帧 125μs = 8000 微帧/秒
     * 每微帧采样数 = sample_rate / 8000
     *
     * 44100Hz → 5.5125 = 5 + 5125/10000 → 每包 5 或 6 samples 交替
     * 48000Hz → 6.0 = 整数 → 每包固定 6 samples
     * 96000Hz → 12.0 = 整数
     *
     * 参考 Linux 内核 sound/usb/endpoint.c 的处理方式
     */
    {
        int rate_gcd = sample_rate;
        int div = 8000;
        /* 求 GCD 简化分数 */
        int a = rate_gcd, b = div;
        while (b) { int t = b; b = a % b; a = t; }
        rate_gcd = a;

        g_state.samples_base = sample_rate / 8000;          /* 整数部分 */
        g_state.samples_num = 0;                             /* 分子累加器初始值 */
        g_state.samples_den = (8000 / rate_gcd);             /* 分母（简化后的 8000/gcd） */
        g_state.samples_rem = (sample_rate / rate_gcd);      /* 分子（简化后的 rate/gcd） */

        /*
         * samples_per_packet 仍保留（用于 max_pkt 检查和向后兼容）
         * 实际传输时用分数计数
         */
        g_state.samples_per_packet = g_state.samples_base + 1;  /* 最大值 */
        g_state.packet_size = g_state.samples_per_packet * g_state.frame_size;

        LOGI("Fractional: rate=%d base=%d rem=%d den=%d max_pkt_size=%d max_pkt=%d",
             sample_rate, g_state.samples_base,
             g_state.samples_rem, g_state.samples_den,
             g_state.packet_size, g_state.max_packet_size);
    }

    /* 检查是否超过每微帧最大字节数 */
    if (g_state.packet_size > g_state.max_bytes_per_uf) {
        LOGE("packet_size %d > max_bytes_per_microframe %d — format not supported by endpoint",
             g_state.packet_size, g_state.max_bytes_per_uf);
        LOGE("This means the device doesn't support %dHz %dbit %dch on this endpoint",
             sample_rate, bit_depth, channels);
        libusb_release_interface(g_state.handle, g_state.interface_num);
        return -2; /* -2 = 格式不支持 */
    }

    /* 对于 high-bandwidth，每个 packet 不超过 base max_pkt */
    if (g_state.packet_size > g_state.max_packet_size) {
        LOGI("High-bandwidth mode: packet_size=%d > base max_pkt=%d, "
             "will use %d transactions per microframe",
             g_state.packet_size, g_state.max_packet_size,
             (g_state.packet_size + g_state.max_packet_size - 1) / g_state.max_packet_size);
    }

    LOGI("ISO params: samples_per_packet=%d packet_size=%d frame_size=%d",
         g_state.samples_per_packet, g_state.packet_size, g_state.frame_size);

    return 0;
}

/*
 * 开始 isochronous 播放
 */
JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeStart(
    JNIEnv *env, jobject thiz)
{
    int ret;

    /* 安全检查：确保设备连接有效 */
    if (!g_state.handle) {
        LOGE("nativeStart called but g_state.handle is NULL");
        return -1;
    }
    if (g_state.endpoint_addr <= 0) {
        LOGE("nativeStart called but endpoint_addr=0x%02X (invalid)", g_state.endpoint_addr);
        return -1;
    }

    ring_reset();

    /* 发送 UAC2 SET_CUR 设置时钟源采样率
     * 这是 Linux 内核 snd_usb_init_sample_rate() 的等价操作
     * 对于 ADAPTIVE endpoint，设备需要知道期望的采样率 */
    if (g_state.clock_source_id > 0) {
        send_sample_rate_cur(g_state.sample_rate);
    }

    /* 启动 libusb event 线程 */
    g_state.running = 1;
    ret = pthread_create(&g_state.event_thread, NULL, event_thread_func, NULL);
    if (ret != 0) {
        LOGE("pthread_create failed: %d", ret);
        g_state.running = 0;
        return -1;
    }

    /*
     * 分配并提交 iso transfers
     * 使用 max_packet_size（最大可能包大小）分配 buffer
     * 实际每包大小由 iso_callback 中的分数计数动态计算
     */
    int buf_size = g_state.packet_size * PACKETS_PER_TRANSFER;

    /* 分配并提交 iso transfers */
    for (int i = 0; i < NUM_ISO_TRANSFERS; i++) {
        g_state.transfer_buffers[i] = (uint8_t *)calloc(1, buf_size);
        g_state.transfers[i] = libusb_alloc_transfer(PACKETS_PER_TRANSFER);

        if (!g_state.transfers[i] || !g_state.transfer_buffers[i]) {
            LOGE("Transfer alloc failed at %d", i);
            goto cleanup_transfers;
        }

        libusb_fill_iso_transfer(
            g_state.transfers[i],
            g_state.handle,
            g_state.endpoint_addr,
            g_state.transfer_buffers[i],
            buf_size,
            PACKETS_PER_TRANSFER,
            iso_callback,
            NULL,
            1000  /* 1s timeout */
        );

        /* 用分数计数设置每个 packet 的初始长度 */
        for (int p = 0; p < PACKETS_PER_TRANSFER; p++) {
            int this_samples = g_state.samples_base;
            g_state.samples_num += g_state.samples_rem;
            if (g_state.samples_num >= g_state.samples_den) {
                this_samples++;
                g_state.samples_num -= g_state.samples_den;
            }
            g_state.transfers[i]->iso_packet_desc[p].length = this_samples * g_state.frame_size;
        }

        ret = libusb_submit_transfer(g_state.transfers[i]);
        if (ret < 0) {
            LOGE("submit_transfer[%d] failed: %s (handle=%p, endpoint=0x%02X)",
                 i, libusb_error_name(ret), g_state.handle, g_state.endpoint_addr);
            goto cleanup_transfers;
        }
    }

    g_state.is_playing = 1;
    g_state.underrun_count = 0;
    g_state.samples_num = 0;  /* 重置累加器 */

    /* 初始化自适应 buffer 管理：50ms 预填充 */
    g_state.prebuffer_bytes = g_state.sample_rate * g_state.frame_size / 20; /* 50ms */
    g_state.consecutive_underruns = 0;
    g_state.consecutive_ok = 0;
    g_state.ring_watermark = RING_BUFFER_SIZE; /* 初始设为最大值 */

    LOGI("Playback started: %dHz %dbit %dch, %d iso transfers x %d packets, prebuffer=%d bytes (%dms)",
         g_state.sample_rate, g_state.bit_depth, g_state.channels,
         NUM_ISO_TRANSFERS, PACKETS_PER_TRANSFER,
         g_state.prebuffer_bytes,
         g_state.prebuffer_bytes * 1000 / (g_state.sample_rate * g_state.frame_size));

    return 0;

cleanup_transfers:
    /* 取消并释放所有已提交的 transfers */
    for (int i = 0; i < NUM_ISO_TRANSFERS; i++) {
        if (g_state.transfers[i]) {
            libusb_cancel_transfer(g_state.transfers[i]);
        }
    }
    /* 等待 event thread 处理完 cancel 回调 */
    g_state.running = 0;
    pthread_join(g_state.event_thread, NULL);
    for (int i = 0; i < NUM_ISO_TRANSFERS; i++) {
        if (g_state.transfers[i]) {
            libusb_free_transfer(g_state.transfers[i]);
            g_state.transfers[i] = NULL;
        }
        if (g_state.transfer_buffers[i]) {
            free(g_state.transfer_buffers[i]);
            g_state.transfer_buffers[i] = NULL;
        }
    }
    return -1;
}

/*
 * 写入 PCM 数据到 ring buffer（从 Kotlin 调用）
 */
JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeWritePcm(
    JNIEnv *env, jobject thiz, jbyteArray data, jint offset, jint length)
{
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    int written = ring_write((uint8_t *)(buf + offset), length);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return written;
}

/*
 * 停止播放
 */
JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeStop(
    JNIEnv *env, jobject thiz)
{
    LOGI("Stopping playback...");

    if (!g_state.is_playing && !g_state.running) {
        LOGI("Already stopped, skip");
        return;
    }

    g_state.is_playing = 0;

    /* 取消所有 pending transfers */
    for (int i = 0; i < NUM_ISO_TRANSFERS; i++) {
        if (g_state.transfers[i]) {
            libusb_cancel_transfer(g_state.transfers[i]);
        }
    }

    /* 等待 event thread 退出 */
    if (g_state.running) {
        g_state.running = 0;
        pthread_join(g_state.event_thread, NULL);
    }

    /* 释放 transfers 并置 NULL 防止悬空指针 */
    for (int i = 0; i < NUM_ISO_TRANSFERS; i++) {
        if (g_state.transfers[i]) {
            libusb_free_transfer(g_state.transfers[i]);
            g_state.transfers[i] = NULL;
        }
        if (g_state.transfer_buffers[i]) {
            free(g_state.transfer_buffers[i]);
            g_state.transfer_buffers[i] = NULL;
        }
    }

    /* 释放 alt setting (切回 zero-bandwidth) */
    if (g_state.handle) {
        libusb_set_interface_alt_setting(
            g_state.handle, g_state.interface_num, 0);
        libusb_release_interface(g_state.handle, g_state.interface_num);
    }

    LOGI("Playback stopped, underruns: %d, ring watermark: %d bytes (%d ms), prebuffer: %d bytes",
         g_state.underrun_count, g_state.ring_watermark,
         g_state.ring_watermark * 1000 / (g_state.sample_rate * g_state.frame_size + 1),
         g_state.prebuffer_bytes);
}

/*
 * 获取 underrun 计数
 */
JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeGetUnderrunCount(
    JNIEnv *env, jobject thiz)
{
    return g_state.underrun_count;
}

/*
 * 获取当前采样率
 */
JNIEXPORT jint JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeGetSampleRate(
    JNIEnv *env, jobject thiz)
{
    return g_state.sample_rate;
}

/*
 * 释放所有资源
 */
JNIEXPORT void JNICALL
Java_com_example_fold_audio_UsbAudioNative_nativeRelease(
    JNIEnv *env, jobject thiz)
{
    if (g_state.is_playing) {
        Java_com_example_fold_audio_UsbAudioNative_nativeStop(env, thiz);
    }

    if (g_state.ring_buffer) {
        free(g_state.ring_buffer);
        g_state.ring_buffer = NULL;
    }

    if (g_state.handle) {
        libusb_close(g_state.handle);
        g_state.handle = NULL;
    }

    if (g_state.ctx) {
        libusb_exit(g_state.ctx);
        g_state.ctx = NULL;
    }

    pthread_mutex_destroy(&g_state.ring_mutex);
    LOGI("Resources released");

    /* 关闭文件日志 */
    file_log_close();
}
