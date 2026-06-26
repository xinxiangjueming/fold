# USB 小尾巴音频独占模式 — 技术预研

## 1. 问题定义

Android 系统的 AudioFlinger mixer 会将所有音频流重采样到 48kHz/16bit，再混合输出。对于 USB DAC（小尾巴），这意味着：
- 原始 44.1kHz/192kHz 等采样率被强制 SRC
- bit-perfect 输出不可能实现
- DSD/MQA 等原生格式无法透传

**目标**：绕过 AudioFlinger mixer，将原始 PCM（或 DSD）数据直接送往 USB Audio HAL，实现 bit-perfect 输出。

---

## 2. Android 音频架构概览

```
App (AudioTrack / AAudio / Oboe)
    ↓
AudioFlinger
    ├── MixerThread (普通路径 — 会 SRC)
    └── DirectOutputThread (直通路径 — 跳过 mixer)
        ↓
Audio HAL (audio_hw)
    ↓
USB Audio HAL / kernel driver (snd-usb-audio)
    ↓
USB Endpoint → DAC 芯片
```

关键点：只要音频流被路由到 **DirectOutputThread** 而非 MixerThread，就能绕过 SRC。

---

## 3. 技术方案对比

### 方案 A：Framework API 路径（推荐首选）

**依赖**：`AudioMixerAttributes` + `setPreferredMixerAttributes()`，常量 `MIXER_BEHAVIOR_BIT_PERFECT`

| 项目 | 说明 |
|------|------|
| 最低版本 | **Android 14 (API 34)** 首次引入，Android 12/13 不可用 |
| 核心 API | `AudioManager.setPreferredMixerAttributes()` |
| 工作原理 | 请求 `MIXER_BEHAVIOR_BIT_PERFECT`，AudioFlinger 将流路由到 DirectOutputThread |
| 优点 | 官方 API、无需 root、兼容性由 Google 保证 |
| 缺点 | 仅 API 34+、OEM 实现可能不完整、无法控制 USB endpoint 细节 |

核心调用流程：
```kotlin
// 1. 枚举 USB 音频设备
val audioManager = getSystemService(AudioManager::class.java)
val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
val usbDevice = devices.first { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }

// 2. 创建 AudioTrack
val audioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()
val format = AudioFormat.Builder()
    .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED) // 或源文件原生格式
    .setSampleRate(192000)   // 与源文件一致
    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
    .build()
val audioTrack = AudioTrack.Builder()
    .setAudioAttributes(audioAttributes)
    .setAudioFormat(format)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()

// 3. 请求 mixer 独占/RAW 模式
val mixerAttributes = AudioMixerAttributes.Builder(format)
    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
    .build()
val result = audioManager.setPreferredMixerAttributes(
    audioAttributes, usbDevice, mixerAttributes
)
// result == true → 已获得独占路径

// 4. 正常写入 PCM 数据
audioTrack.write(buffer, offset, size)
```

**兼容性风险**：
- 不同厂商对 `setPreferredMixerAttributes` 的支持程度不同
- 部分设备可能返回 `ERROR_NO_INIT` 或 `ERROR_INVALID_OPERATION`
- 需要实际测试主流机型

---

### 方案 B：USB Host API + libusb（完全控制）

**适用场景**：需要绕过整个 Android 音频栈，直接控制 USB Audio endpoint

| 项目 | 说明 |
|------|------|
| 最低版本 | Android 5.0+ (USB Host API) |
| 核心依赖 | libusb 1.0.23+ (NDK/JNI) |
| 工作原理 | 直接发送 USB Audio Class isochronous 传输 |
| 优点 | 完全控制、支持 DSD/MQA、版本兼容性好 |
| 缺点 | 复杂度极高、需要 NDK、USB 时序控制困难 |

Android USB Host API 的关键限制：**不支持 isochronous transfer**。必须用 libusb：

```kotlin
// 1. 获取 USB 设备权限
val usbManager = getSystemService(UsbManager::class.java)
val device = usbManager.deviceList.values.first { /* USB Audio Class */ }
usbManager.requestPermission(device, pendingIntent)

// 2. 打开设备并传递 FD 到 native
val connection = usbManager.openDevice(device)
val fd = connection.fileDescriptor
nativeInitUsbAudio(fd) // JNI 调用
```

```c
// 3. Native 层 (C/C++)
void nativeInitUsbAudio(JNIEnv *env, jobject thiz, int fd) {
    libusb_context *ctx;
    libusb_init(&ctx);

    libusb_device_handle *handle;
    libusb_wrap_sys_device(ctx, fd, &handle);  // 关键：用 Android 给的 fd

    // 脱离内核驱动（获取独占）
    libusb_detach_kernel_driver(handle, 0);

    // claim interface
    libusb_claim_interface(handle, usb_audio_streaming_interface);

    // 设置 alternate setting (选择采样率/位深组合)
    libusb_set_interface_alt_setting(handle, interface_num, alt_setting);

    // 提交 isochronous transfer
    struct libusb_transfer *xfr = libusb_alloc_transfer(num_iso_packets);
    libusb_fill_iso_transfer(xfr, handle, endpoint, buffer, length,
                             num_iso_packets, callback, user_data, timeout);
    libusb_submit_transfer(xfr);
}
```

**USB Audio Class 要自己实现的部分**：
- 解析 USB 描述符（bDescriptorType, bDescriptorSubtype）
- 解析 Audio Streaming interface descriptor（采样率、位深、通道数）
- 处理 SET_CUR / GET_CUR 请求设置采样率
- 处理 isochronous feedback endpoint（时钟同步）
- UAC1 和 UAC2 协议差异处理

**这是最复杂的方案**，相当于在用户态实现了一个 USB Audio Class 驱动。

---

### 方案 C：HAL 层 ALSA 直接访问（需 root）

| 项目 | 说明 |
|------|------|
| 最低版本 | 任意版本 |
| 工作原理 | 直接操作 `/dev/snd/pcmCxDxp` ALSA 设备节点 |
| 优点 | 最底层控制、bit-perfect 保证 |
| 缺点 | **必须 root**、SELinux 限制、不适合公开发布 |

```c
// 直接用 tinyalsa 或 ALSA API
snd_pcm_open(&pcm, "hw:1,0", SND_PCM_STREAM_PLAYBACK, 0);
snd_pcm_hw_params_set_format(pcm, hw_params, SND_PCM_FORMAT_S24_3LE);
snd_pcm_hw_params_set_rate(pcm, hw_params, 192000, 0);
snd_pcm_writei(pcm, buffer, frames);
```

**这不是可行的 SDK 方案**，但可以作为参考实现，了解 UAPP（USB Audio Player Pro）在 root 设备上的做法。

---

## 4. 推荐方案：A + B 混合架构

```
┌─────────────────────────────────────────────┐
│              UsbExclusiveAudio SDK           │
├─────────────────────────────────────────────┤
│  UsbDeviceDetector                          │
│  ├── USB 设备发现 & 权限请求                 │
│  ├── USB Audio Class 描述符解析              │
│  └── 支持的采样率/位深/格式枚举              │
├─────────────────────────────────────────────┤
│  AudioEngine (策略模式)                      │
│  ├── FrameworkEngine (API 34+)              │
│  │   └── AudioMixerAttributes RAW mode      │
│  └── LibusbEngine (API 31-33 / 兼容降级)    │
│      └── USB Host + libusb isochronous      │
├─────────────────────────────────────────────┤
│  AudioPipeline                              │
│  ├── 解码器 (FFmpeg / MediaCodec)           │
│  ├── 格式转换 (重采样到设备原生格式)         │
│  └── DSD passthrough (可选)                 │
├─────────────────────────────────────────────┤
│  Public API                                 │
│  ├── ExclusiveUsbPlayer                     │
│  │   ├── open(device)                       │
│  │   ├── play(source)                       │
│  │   ├── pause() / stop() / seek()         │
│  │   └── getCurrentFormat() → bit-perfect?  │
│  └── UsbDeviceManager                       │
│      ├── getConnectedDevices()              │
│      ├── getDeviceCapabilities()            │
│      └── observeDeviceChanges()             │
└─────────────────────────────────────────────┘
```

### 版本策略：

| Android 版本 | 策略 | 效果 |
|-------------|------|------|
| **API 34+** | FrameworkEngine (AudioMixerAttributes RAW) | 官方独占、bit-perfect |
| **API 31-33** | LibusbEngine (USB Host + libusb) | 完全控制、bit-perfect |
| **API < 31** | 不支持 | 抛出异常或 fallback 到普通 AudioTrack |

---

## 5. 关键技术难点

### 5.1 USB 设备检测与能力查询

```kotlin
// 解析 USB Audio Class 描述符
fun parseUsbAudioDescriptors(device: UsbDevice): UsbAudioCapabilities {
    // 遍历 configuration → interface → endpoint
    // 找到 bInterfaceClass = 0x01 (Audio)
    // 找到 bInterfaceSubClass = 0x02 (AudioStreaming)
    // 解析 bDescriptorSubtype = 0x01 (AS_GENERAL) 获取格式类型
    // 解析 bDescriptorSubtype = 0x02 (FORMAT_TYPE) 获取采样率列表
    return UsbAudioCapabilities(
        sampleRates = listOf(44100, 48000, 96000, 192000),
        bitDepths = listOf(16, 24, 32),
        channels = 2,
        supportedFormats = listOf(PCM, DSD)
    )
}
```

### 5.2 isochronous 传输时钟同步

USB Audio 设备有自己独立的时钟源。PC 端发送数据的速率必须和设备消费数据的速率匹配，否则会出现：
- buffer underrun → 爆音
- buffer overrun → 数据丢失

需要处理 **adaptive source** 或 **feedback endpoint**：
- UAC1：设备通过 feedback endpoint 告诉 host 实际消费速率
- UAC2：implicit feedback 或 explicit feedback 机制

### 5.3 线程模型

```
解码线程: decode() → PCM buffer queue
    ↓
传输线程: dequeue() → isochronous submit (最高优先级 RT)
    ↓
回调线程: transfer_complete() → recycle buffer
```

传输线程必须是 **SCHED_FIFO** 或 **SCHED_RR** 实时调度策略，否则 USB 时序会不稳定。Android NDK 支持 `pthread_setschedparam()` 设置实时优先级。

### 5.4 SELinux / 权限

- USB Host API 权限：`<uses-feature android:name="android.hardware.usb.host" />`
- libusb 需要访问 USB 文件描述符：通过 `UsbManager.openDevice()` 获取
- SELinux `neverallow` 可能阻止某些操作：需要在 app 内用 Java API 获取 FD 再传给 native
- **不要**直接 `open("/dev/bus/usb/...")`，必须通过 UsbManager

---

## 6. 需要验证的前置问题

| # | 问题 | 验证方式 |
|---|------|---------|
| 1 | 小米手机对 `setPreferredMixerAttributes` 的支持情况 | 实机测试小米 14/15，看返回值 |
| 2 | libusb 在主流非 root 手机上能否正常 claim USB Audio interface | 写 demo 在红米/小米/三星上测试 |
| 3 | 不同 USB DAC 的描述符解析兼容性 | 准备 3-5 款不同芯片的 DAC 测试 |
| 4 | Android 14 的 RAW mode 是否真的 bit-perfect | 对比 input 和 output 的 PCM 数据 hash |
| 5 | isochronous 传输在 Android 上的稳定性 | 播放测试 30min+ 看是否有爆音/断音 |

---

## 7. 工作量估算

| 模块 | 预估 | 说明 |
|------|------|------|
| USB 设备检测 & 描述符解析 | 1-2 周 | UAC1/UAC2 兼容 |
| FrameworkEngine (API 34+) | 1 周 | API 较简单 |
| LibusbEngine (API 31-33) | 3-4 周 | isochronous + 时钟同步是核心难点 |
| Audio Pipeline (解码 + 格式转换) | 2 周 | FFmpeg JNI 或 MediaCodec |
| 兼容性测试 & 修复 | 2-3 周 | 多机型 + 多 DAC |
| SDK API 设计 & 文档 | 1 周 | — |
| **合计** | **10-13 周** | 一个人全职开发 |

---

## 8. 开源参考

| 项目 | 作用 |
|------|------|
| [libusb](https://github.com/libusb/libusb) | USB 底层通信，含 `libusb_wrap_sys_device` |
| [Oboe](https://github.com/google/oboe) | Google 音频库，理解 AudioTrack/AAudio 用法 |
| [tinyalsa](https://github.com/tinyalsa/tinyalsa) | 轻量 ALSA 库，理解 HAL 层接口 |
| [android-usb-audio](https://github.com/niccokunzmann/libusb-android) | libusb Android 移植参考 |
| [AOSP USB Audio HAL](https://cs.android.com/android/platform/superproject/+/master:hardware/interfaces/audio/aidl/default/) | 理解系统侧 USB Audio 处理 |

---

## 9. 结论与建议

**推荐路线**：

1. **第一步**：写一个 PoC 验证 `AudioMixerAttributes + MIXER_POLICY_ATTRIBUTE_RAW` 在小米设备上是否可用。这决定了方案 A 的可行性。

2. **第二步**：同时调研 libusb 路径，准备一个最小 demo — 获取 USB Audio 设备的 FD、claim interface、发送一段测试 PCM 看 DAC 能否出声。

3. **第三步**：根据前两步结果决定是走纯 Framework 还是 A+B 混合。

最大的风险点是 **小米的 ROM 对 Android 14 新 API 的支持程度**，以及 **libusb 在非 root 设备上能否正常工作**。建议尽早用真机验证这两个关键假设。
