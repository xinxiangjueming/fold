/**
 * @file dsp-engine.cpp
 * @brief Native DSP engine: 10-band EQ + bass/treble shelves.
 * ARM NEON SIMD for stereo processing. Zero heap allocation on hot path.
 */

#include "dsp-engine.h"

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <cstdlib>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define DSP_NEON_ENABLED 1
#else
#define DSP_NEON_ENABLED 0
#endif

#define DSP_TAG "DspEngine"
#define DSP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, DSP_TAG, __VA_ARGS__)
#define DSP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DSP_TAG, __VA_ARGS__)

static inline BiquadCoeffs identityBiquad() {
    BiquadCoeffs c;
    c.b0 = 1.f;
    c.b1 = c.b2 = c.a1 = c.a2 = 0.f;
    return c;
}

/**
 * RBJ peaking EQ biquad coefficients.
 * f0: center freq, gainDb: gain, sampleHz: sample rate
 */
static BiquadCoeffs computePeakingEq(float f0, float gainDb, int sampleHz) {
    if (fabsf(gainDb) < kDspFlatThresholdDb) {
        return identityBiquad();
    }
    const float A = powf(10.f, gainDb / 40.f);
    const float omega = 2.f * (float)M_PI * f0 / (float)sampleHz;
    const float sinOmega = sinf(omega);
    const float cosOmega = cosf(omega);
    const float alpha = sinOmega / (2.f * kDspBandQ);
    const float a0_inv = 1.f / (1.f + alpha / A);

    BiquadCoeffs c;
    c.b0 = (1.f + alpha * A) * a0_inv;
    c.b1 = (-2.f * cosOmega) * a0_inv;
    c.b2 = (1.f - alpha * A) * a0_inv;
    c.a1 = (-2.f * cosOmega) * a0_inv;
    c.a2 = (1.f - alpha / A) * a0_inv;
    return c;
}

/** RBJ low-shelf biquad coefficients (S = 1). */
static BiquadCoeffs computeLowShelf(float f0, float gainDb, int sampleHz) {
    if (fabsf(gainDb) < kDspFlatThresholdDb) {
        return identityBiquad();
    }
    const float A = powf(10.f, gainDb / 40.f);
    const float omega = 2.f * (float)M_PI * f0 / (float)sampleHz;
    const float cosOmega = cosf(omega);
    const float sinOmega = sinf(omega);
    const float sqrtA = sqrtf(A);
    const float alphaS = sinOmega / sqrtf(2.f);
    const float twoSqrtA = 2.f * sqrtA * alphaS;
    const float a0_inv = 1.f / ((A + 1.f) + (A - 1.f) * cosOmega + twoSqrtA);

    BiquadCoeffs c;
    c.b0 = A * ((A + 1.f) - (A - 1.f) * cosOmega + twoSqrtA) * a0_inv;
    c.b1 = 2.f * A * ((A - 1.f) - (A + 1.f) * cosOmega) * a0_inv;
    c.b2 = A * ((A + 1.f) - (A - 1.f) * cosOmega - twoSqrtA) * a0_inv;
    c.a1 = -2.f * ((A - 1.f) + (A + 1.f) * cosOmega) * a0_inv;
    c.a2 = ((A + 1.f) + (A - 1.f) * cosOmega - twoSqrtA) * a0_inv;
    return c;
}

/** RBJ high-shelf biquad coefficients (S = 1). */
static BiquadCoeffs computeHighShelf(float f0, float gainDb, int sampleHz) {
    if (fabsf(gainDb) < kDspFlatThresholdDb) {
        return identityBiquad();
    }
    const float A = powf(10.f, gainDb / 40.f);
    const float omega = 2.f * (float)M_PI * f0 / (float)sampleHz;
    const float cosOmega = cosf(omega);
    const float sqrtA = sqrtf(A);
    const float sinOmega = sinf(omega);
    const float alphaS = sinOmega / sqrtf(2.f);
    const float twoSqrtA = 2.f * sqrtA * alphaS;
    const float a0_inv = 1.f / ((A + 1.f) - (A - 1.f) * cosOmega + twoSqrtA);

    BiquadCoeffs c;
    c.b0 = A * ((A + 1.f) + (A - 1.f) * cosOmega + twoSqrtA) * a0_inv;
    c.b1 = -2.f * A * ((A - 1.f) + (A + 1.f) * cosOmega) * a0_inv;
    c.b2 = A * ((A + 1.f) - (A - 1.f) * cosOmega - twoSqrtA) * a0_inv;
    c.a1 = 2.f * ((A - 1.f) - (A + 1.f) * cosOmega) * a0_inv;
    c.a2 = ((A + 1.f) - (A - 1.f) * cosOmega - twoSqrtA) * a0_inv;
    return c;
}

static void clearAllBiquadState(DspContext *ctx) {
    for (int ch = 0; ch < kDspMaxChannels; ++ch) {
        for (int b = 0; b < kDspEqBandCount; ++b) {
            ctx->eqState[ch][b] = BiquadState{};
        }
        ctx->bassState[ch] = BiquadState{};
        ctx->trebleState[ch] = BiquadState{};
    }
}

static inline float applyBiquadScalar(float x, const BiquadCoeffs &c, BiquadState &state) {
    const float y = c.b0 * x + state.w1;
    state.w1 = c.b1 * x - c.a1 * y + state.w2;
    state.w2 = c.b2 * x - c.a2 * y;
    return y;
}

/** NEON-accelerated biquad for stereo interleaved buffer. */
static void applyBiquadStereo(float *__restrict buf, int numFrames,
                              const BiquadCoeffs &c,
                              BiquadState &stL, BiquadState &stR) {
#if DSP_NEON_ENABLED
    float32x2_t vW1 = {stL.w1, stR.w1};
    float32x2_t vW2 = {stL.w2, stR.w2};
    const float32x2_t vB0 = vdup_n_f32(c.b0);
    const float32x2_t vB1 = vdup_n_f32(c.b1);
    const float32x2_t vB2 = vdup_n_f32(c.b2);
    const float32x2_t vA1 = vdup_n_f32(c.a1);
    const float32x2_t vA2 = vdup_n_f32(c.a2);

    int i = 0;
    for (; i <= numFrames - 2; i += 2) {
        float32x2_t x0 = vld1_f32(buf + 2 * i);
        float32x2_t y0 = vmla_f32(vW1, vB0, x0);
        float32x2_t nW1 = vmls_f32(vmla_f32(vW2, vB1, x0), vA1, y0);
        float32x2_t nW2 = vsub_f32(vmul_f32(vB2, x0), vmul_f32(vA2, y0));
        vst1_f32(buf + 2 * i, y0);

        float32x2_t x1 = vld1_f32(buf + 2 * (i + 1));
        float32x2_t y1 = vmla_f32(nW1, vB0, x1);
        vW1 = vmls_f32(vmla_f32(nW2, vB1, x1), vA1, y1);
        vW2 = vsub_f32(vmul_f32(vB2, x1), vmul_f32(vA2, y1));
        vst1_f32(buf + 2 * (i + 1), y1);
    }
    if (i < numFrames) {
        float32x2_t x = vld1_f32(buf + 2 * i);
        float32x2_t y = vmla_f32(vW1, vB0, x);
        vW1 = vmls_f32(vmla_f32(vW2, vB1, x), vA1, y);
        vW2 = vsub_f32(vmul_f32(vB2, x), vmul_f32(vA2, y));
        vst1_f32(buf + 2 * i, y);
    }
    stL.w1 = vget_lane_f32(vW1, 0);
    stR.w1 = vget_lane_f32(vW1, 1);
    stL.w2 = vget_lane_f32(vW2, 0);
    stR.w2 = vget_lane_f32(vW2, 1);
#else
    for (int i = 0; i < numFrames; ++i) {
        buf[2 * i]     = applyBiquadScalar(buf[2 * i], c, stL);
        buf[2 * i + 1] = applyBiquadScalar(buf[2 * i + 1], c, stR);
    }
#endif
}

// ── JNI entry points ──────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_fold_audio_DspEngine_nativeCreate(
    JNIEnv *, jobject, jint sampleRate, jint channelCount) {

    auto *ctx = static_cast<DspContext *>(calloc(1, sizeof(DspContext)));
    if (!ctx) return 0;

    ctx->sampleRate = sampleRate;
    ctx->channelCount = channelCount;
    ctx->eqEnabled = true;
    ctx->eqFlat = true;
    ctx->bassFlat = true;
    ctx->trebleFlat = true;

    for (int b = 0; b < kDspEqBandCount; ++b) {
        ctx->eqCoeffs[b] = identityBiquad();
    }
    ctx->bassCoeffs = identityBiquad();
    ctx->trebleCoeffs = identityBiquad();

    clearAllBiquadState(ctx);

    DSP_LOGI("nativeCreate: rate=%d ch=%d", sampleRate, channelCount);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_DspEngine_nativeSetEqBands(
    JNIEnv *env, jobject, jlong handle, jfloatArray bandGains,
    jfloat bassDb, jfloat trebleDb) {

    auto *ctx = reinterpret_cast<DspContext *>(handle);
    if (!ctx) return;

    jfloat *gains = env->GetFloatArrayElements(bandGains, nullptr);
    bool anyActive = false;
    for (int b = 0; b < kDspEqBandCount; ++b) {
        const float db = gains[b];
        ctx->eqCoeffs[b] = computePeakingEq(kDspEqCenterHz[b], db, ctx->sampleRate);
        if (fabsf(db) >= kDspFlatThresholdDb) anyActive = true;
    }
    env->ReleaseFloatArrayElements(bandGains, gains, JNI_ABORT);
    ctx->eqFlat = !anyActive;

    ctx->bassCoeffs = computeLowShelf(kDspBassShelfHz, bassDb, ctx->sampleRate);
    ctx->bassFlat = fabsf(bassDb) < kDspFlatThresholdDb;

    ctx->trebleCoeffs = computeHighShelf(kDspTrebleShelfHz, trebleDb, ctx->sampleRate);
    ctx->trebleFlat = fabsf(trebleDb) < kDspFlatThresholdDb;
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_DspEngine_nativeSetEqEnabled(
    JNIEnv *, jobject, jlong handle, jboolean enabled) {
    auto *ctx = reinterpret_cast<DspContext *>(handle);
    if (ctx) ctx->eqEnabled = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_DspEngine_nativeProcessAudio(
    JNIEnv *env, jobject, jlong handle, jfloatArray pcmBuffer) {

    auto *ctx = reinterpret_cast<DspContext *>(handle);
    if (!ctx) return;

    const int totalSamples = env->GetArrayLength(pcmBuffer);
    if (totalSamples <= 0) return;

    jfloat *buf = env->GetFloatArrayElements(pcmBuffer, nullptr);

    const int ch = ctx->channelCount;
    const int numFrames = totalSamples / ch;

    const bool eqEn = ctx->eqEnabled;
    const bool eqFlat = ctx->eqFlat;
    const bool bassFlat = ctx->bassFlat;
    const bool trebleFlat = ctx->trebleFlat;

    // Stage 1: 10-band peaking EQ
    if (eqEn && !eqFlat) {
        for (int b = 0; b < kDspEqBandCount; ++b) {
            if (ch == 2) {
                applyBiquadStereo(buf, numFrames,
                                  ctx->eqCoeffs[b],
                                  ctx->eqState[0][b],
                                  ctx->eqState[1][b]);
            } else {
                for (int i = 0; i < totalSamples; ++i) {
                    buf[i] = applyBiquadScalar(buf[i], ctx->eqCoeffs[b], ctx->eqState[0][b]);
                }
            }
        }
    }

    // Stage 2: Bass low-shelf (always active, independent of EQ toggle)
    if (!bassFlat) {
        if (ch == 2) {
            applyBiquadStereo(buf, numFrames, ctx->bassCoeffs,
                              ctx->bassState[0], ctx->bassState[1]);
        } else {
            for (int i = 0; i < totalSamples; ++i) {
                buf[i] = applyBiquadScalar(buf[i], ctx->bassCoeffs, ctx->bassState[0]);
            }
        }
    }

    // Stage 3: Treble high-shelf (always active, independent of EQ toggle)
    if (!trebleFlat) {
        if (ch == 2) {
            applyBiquadStereo(buf, numFrames, ctx->trebleCoeffs,
                              ctx->trebleState[0], ctx->trebleState[1]);
        } else {
            for (int i = 0; i < totalSamples; ++i) {
                buf[i] = applyBiquadScalar(buf[i], ctx->trebleCoeffs, ctx->trebleState[0]);
            }
        }
    }

    env->ReleaseFloatArrayElements(pcmBuffer, buf, 0);
}

JNIEXPORT void JNICALL
Java_com_example_fold_audio_DspEngine_nativeDestroy(
    JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<DspContext *>(handle);
    if (!ctx) return;
    free(ctx);
    DSP_LOGI("nativeDestroy");
}

} // extern "C"
