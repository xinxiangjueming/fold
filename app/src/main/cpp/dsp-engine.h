/**
 * @file dsp-engine.h
 * @brief Native DSP processing engine for Fold music player.
 *
 * 10-band parametric EQ (RBJ biquad) + bass/treble shelves.
 * ARM NEON SIMD acceleration for stereo processing.
 */

#pragma once

#include <cstddef>

/** Number of ISO 10-band graphic equalizer bands. */
static constexpr int kDspEqBandCount = 10;

/** Maximum number of audio channels supported. */
static constexpr int kDspMaxChannels = 2;

/** Q factor (1-octave bandwidth, sqrt(2)) for peaking EQ bands. */
static constexpr float kDspBandQ = 1.4142135f;

/** Threshold below which gain is treated as 0 dB flat. */
static constexpr float kDspFlatThresholdDb = 0.001f;

/** Bass low-shelf frequency in Hz. */
static constexpr float kDspBassShelfHz = 250.0f;

/** Treble high-shelf frequency in Hz. */
static constexpr float kDspTrebleShelfHz = 4000.0f;

/** ISO 10-band graphic EQ center frequencies (1-octave spacing). */
static constexpr float kDspEqCenterHz[kDspEqBandCount] = {
    31.f, 62.f, 125.f, 250.f, 500.f,
    1000.f, 2000.f, 4000.f, 8000.f, 16000.f
};

/** Normalized second-order IIR biquad coefficients (a0 = 1). */
struct BiquadCoeffs {
    float b0 = 1.f;
    float b1 = 0.f;
    float b2 = 0.f;
    float a1 = 0.f;
    float a2 = 0.f;
};

/** Direct Form II Transposed biquad state for a single channel. */
struct BiquadState {
    float w1 = 0.f;
    float w2 = 0.f;
};

/** Aggregate state for the native DSP processing chain. */
struct DspContext {
    // 10-band peaking EQ
    BiquadCoeffs eqCoeffs[kDspEqBandCount];
    BiquadState eqState[kDspMaxChannels][kDspEqBandCount];
    bool eqEnabled;
    bool eqFlat;

    // Bass low-shelf (250 Hz)
    BiquadCoeffs bassCoeffs;
    BiquadState bassState[kDspMaxChannels];
    bool bassFlat;

    // Treble high-shelf (4000 Hz)
    BiquadCoeffs trebleCoeffs;
    BiquadState trebleState[kDspMaxChannels];
    bool trebleFlat;

    int sampleRate;
    int channelCount;
};
