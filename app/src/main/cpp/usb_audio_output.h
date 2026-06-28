#ifndef USB_AUDIO_OUTPUT_H
#define USB_AUDIO_OUTPUT_H

#include <cstdint>
#include <atomic>

#define USB_AUDIO_NUM_URBS        80
#define USB_AUDIO_PACKETS_PER_URB 8
#define USB_AUDIO_URB_BUFFER_SIZE 4096

struct UrbSlot {
    void    *urb;
    uint8_t *buffer;
    int      dataLength;
};

struct UsbAudioContext {
    int fd;
    int interfaceId;
    int endpointOut;
    int endpointFeedback;
    int sampleRate;
    int channelCount;
    int bitDepth;
    int bytesPerSample;
    int bytesPerFrame;
    int maxPacketSize;

    std::atomic<bool> running;

    int64_t  framesWritten;

    UrbSlot ring[USB_AUDIO_NUM_URBS];
    int     submitIdx;
    int     reapIdx;
    int     urbsInFlight;
    bool    ringAllocated;

    double frameAccumulator;
    double calibratedFpmf;

    // Single large buffer for residual + conversion (avoids heap fragmentation)
    uint8_t *workBuffer;       // 256KB, allocated once
    int      residualBytes;    // bytes stored at workBuffer[0..residualBytes-1]

    // Residual buffer for short-URB prevention (saves leftover when not enough for full URB)
    uint8_t  residualBuffer[USB_AUDIO_URB_BUFFER_SIZE];

    void    *feedbackUrb;
    uint8_t  feedbackBuffer[4];
    bool     feedbackInFlight;
};

void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int byteCount);

void padInt16ToInt32(const int16_t *in, int32_t *out, int sampleCount);
void padInt24ToInt32(const uint8_t *in, int32_t *out, int sampleCount);
void shiftInt32From24(const int32_t *in, int32_t *out, int sampleCount);

#endif
