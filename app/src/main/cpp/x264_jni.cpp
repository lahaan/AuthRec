// JNI bridge to x264 for "Compress" as H.264 (com.authrec.record.X264Encoder): 8-bit High
// profile, the format everything plays. Same shape as x265_jni.cpp: decoder Image planes in
// (10-bit sources rounded to 8), access units (Annex B, decode order) out with pts/dts/key flag.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <vector>

extern "C" {
#include <x264.h>
}
#include "codec_util.h"

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "AuthRec", __VA_ARGS__)

namespace {

struct Session {
    x264_t* encoder = nullptr;
    x264_param_t param;
    x264_picture_t in;
    x264_picture_t out;
    int width = 0;
    int height = 0;
};

jint copyFrame(x264_nal_t* nals, int count, int size, JNIEnv* env, jobject out) {
    if (size <= 0) return 0;
    if (size > env->GetDirectBufferCapacity(out)) return -1;
    // x264 writes one frame's NAL units back to back, starting at the first payload.
    memcpy(env->GetDirectBufferAddress(out), nals[0].p_payload, size);
    return size;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_authrec_record_X264Encoder_nativeOpen(JNIEnv* env, jclass, jint width, jint height, jint fpsNum, jint fpsDen,
                                               jstring preset, jfloat crf, jint maxKbps, jint keyint,
                                               jint primaries, jint transfer, jint matrix, jboolean fullRange) {
    auto* s = new Session();
    s->width = width;
    s->height = height;
    const char* presetName = env->GetStringUTFChars(preset, nullptr);
    int ok = x264_param_default_preset(&s->param, presetName, nullptr);
    env->ReleaseStringUTFChars(preset, presetName);
    if (ok < 0) {
        LOGW("x264: unknown preset");
        delete s;
        return 0;
    }
    x264_param_t& p = s->param;
    p.i_log_level = X264_LOG_WARNING;
    p.i_csp = X264_CSP_I420;
    p.i_width = width;
    p.i_height = height;
    p.i_fps_num = (uint32_t) fpsNum;
    p.i_fps_den = (uint32_t) fpsDen;
    p.i_timebase_num = 1;
    p.i_timebase_den = 1000000;  // pts in microseconds, as MediaCodec has them
    p.b_vfr_input = 0;           // rate control by frame rate, not timestamps
    p.rc.i_rc_method = X264_RC_CRF;
    p.rc.f_rf_constant = crf;
    if (maxKbps > 0) {  // capped CRF, as in x265_jni.cpp
        p.rc.i_vbv_max_bitrate = maxKbps;
        p.rc.i_vbv_buffer_size = maxKbps * 2;
    }
    p.i_keyint_max = keyint;
    p.b_repeat_headers = 0;      // SPS/PPS go in the track header (csd-0/csd-1)
    p.b_annexb = 1;
    p.vui.i_colorprim = primaries;
    p.vui.i_transfer = transfer;
    p.vui.i_colmatrix = matrix;
    p.vui.b_fullrange = fullRange ? 1 : 0;
    p.i_threads = X264_THREADS_AUTO;
    if (x264_param_apply_profile(&p, "high") < 0) LOGW("x264: high profile not applied");

    s->encoder = x264_encoder_open(&p);
    if (!s->encoder) {
        LOGW("x264: encoder_open failed");
        delete s;
        return 0;
    }
    if (x264_picture_alloc(&s->in, X264_CSP_I420, width, height) < 0) {
        x264_encoder_close(s->encoder);
        delete s;
        return 0;
    }
    x264_picture_init(&s->out);
    return reinterpret_cast<jlong>(s);
}

/** SPS and PPS (Annex B, each with its start code) as two arrays: the AVC track's csd-0 and csd-1. */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_authrec_record_X264Encoder_nativeHeaders(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    x264_nal_t* nals = nullptr;
    int count = 0;
    if (x264_encoder_headers(s->encoder, &nals, &count) < 0) return nullptr;
    jobjectArray out = env->NewObjectArray(2, env->FindClass("[B"), nullptr);
    for (int i = 0; i < count; i++) {
        int slot = nals[i].i_type == NAL_SPS ? 0 : nals[i].i_type == NAL_PPS ? 1 : -1;
        if (slot < 0) continue;  // the SEI with x264's version string stays out of the header
        jbyteArray a = env->NewByteArray(nals[i].i_payload);
        env->SetByteArrayRegion(a, 0, nals[i].i_payload, reinterpret_cast<const jbyte*>(nals[i].p_payload));
        env->SetObjectArrayElement(out, slot, a);
    }
    return out;
}

/** As X265Encoder.nativeEncode: y == null drains; returns the access unit size, 0 = none, -1 = didn't fit. */
extern "C" JNIEXPORT jint JNICALL
Java_com_authrec_record_X264Encoder_nativeEncode(JNIEnv* env, jclass, jlong handle,
                                                 jobject y, jint yRow, jint yPix,
                                                 jobject u, jint uRow, jint uPix,
                                                 jobject v, jint vRow, jint vPix,
                                                 jint bytesPerSample, jint shift, jlong pts,
                                                 jobject out, jlongArray meta) {
    auto* s = reinterpret_cast<Session*>(handle);
    x264_nal_t* nals = nullptr;
    int count = 0;
    int size;
    if (y != nullptr) {
        int cw = s->width / 2;
        int ch = s->height / 2;
        x264_image_t& img = s->in.img;
        authrec::planeTo8(static_cast<const uint8_t*>(env->GetDirectBufferAddress(y)), yRow, yPix, bytesPerSample, shift,
                          s->width, s->height, img.plane[0], img.i_stride[0]);
        authrec::planeTo8(static_cast<const uint8_t*>(env->GetDirectBufferAddress(u)), uRow, uPix, bytesPerSample, shift,
                          cw, ch, img.plane[1], img.i_stride[1]);
        authrec::planeTo8(static_cast<const uint8_t*>(env->GetDirectBufferAddress(v)), vRow, vPix, bytesPerSample, shift,
                          cw, ch, img.plane[2], img.i_stride[2]);
        s->in.i_pts = pts;
        size = x264_encoder_encode(s->encoder, &nals, &count, &s->in, &s->out);
    } else {
        if (x264_encoder_delayed_frames(s->encoder) <= 0) return 0;
        size = x264_encoder_encode(s->encoder, &nals, &count, nullptr, &s->out);
    }
    if (size <= 0) return 0;
    jint n = copyFrame(nals, count, size, env, out);
    jlong m[3] = {s->out.i_pts, s->out.i_dts, s->out.b_keyframe ? 1 : 0};
    env->SetLongArrayRegion(meta, 0, 3, m);
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_authrec_record_X264Encoder_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->encoder) x264_encoder_close(s->encoder);
    x264_picture_clean(&s->in);
    delete s;
}
