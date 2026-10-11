// JNI bridge to x265 for "Compress" (com.authrec.record.X265Encoder).
//
// One encoder session per compression: frames come in as the planes of a MediaCodec decoder's
// output Image (P010 for 10-bit sources: 16-bit samples with the value in the top 10 bits;
// 8-bit YUV for H.264 / HEVC 8-bit ones), are converted to x265's planar 10-bit 4:2:0 input,
// and every access unit that comes out (Annex B, decode order) is copied into a direct buffer
// together with its pts, dts and whether it's a key frame.

#include <jni.h>
#include <android/log.h>
#include <x265.h>

#include <cstdint>
#include <cstring>
#include <vector>

#define LOG_TAG "AuthRec"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    x265_param* param = nullptr;
    x265_encoder* encoder = nullptr;
    x265_picture* in = nullptr;
    x265_picture* out = nullptr;
    std::vector<uint16_t> planes[3];
    int width = 0;
    int height = 0;
};

/** Copies one plane of [w]×[h] samples into 10-bit, from 8- or 16-bit with any row/pixel stride. */
void convertPlane(const uint8_t* src, int rowStride, int pixelStride, int bytesPerSample, int shift,
                  int w, int h, uint16_t* dst) {
    for (int y = 0; y < h; y++) {
        const uint8_t* row = src + (size_t) y * rowStride;
        uint16_t* d = dst + (size_t) y * w;
        if (bytesPerSample == 2) {
            for (int x = 0; x < w; x++) {
                const uint8_t* p = row + (size_t) x * pixelStride;
                d[x] = (uint16_t) ((p[0] | (p[1] << 8)) >> shift);
            }
        } else {
            for (int x = 0; x < w; x++) d[x] = (uint16_t) (row[(size_t) x * pixelStride] << 2);
        }
    }
}

/** Writes the NAL units of one access unit to [out]; returns the byte count or -1 if it didn't fit. */
jint copyNals(x265_nal* nals, uint32_t count, uint8_t* out, jlong capacity) {
    jlong size = 0;
    for (uint32_t i = 0; i < count; i++) size += nals[i].sizeBytes;
    if (size > capacity) return -1;
    uint8_t* p = out;
    for (uint32_t i = 0; i < count; i++) {
        memcpy(p, nals[i].payload, nals[i].sizeBytes);
        p += nals[i].sizeBytes;
    }
    return (jint) size;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_authrec_record_X265Encoder_nativeOpen(JNIEnv* env, jclass, jint width, jint height, jint fpsNum, jint fpsDen,
                                               jstring preset, jfloat crf, jint keyint, jint bframes,
                                               jint primaries, jint transfer, jint matrix, jboolean fullRange) {
    auto* s = new Session();
    s->width = width;
    s->height = height;
    s->param = x265_param_alloc();
    const char* presetName = env->GetStringUTFChars(preset, nullptr);
    int ok = x265_param_default_preset(s->param, presetName, nullptr);
    env->ReleaseStringUTFChars(preset, presetName);
    if (ok < 0) {
        LOGW("x265: unknown preset");
        x265_param_free(s->param);
        delete s;
        return 0;
    }
    x265_param* p = s->param;
    p->logLevel = X265_LOG_WARNING;
    p->sourceWidth = width;
    p->sourceHeight = height;
    p->fpsNum = (uint32_t) fpsNum;
    p->fpsDenom = (uint32_t) fpsDen;
    p->internalCsp = X265_CSP_I420;
    p->rc.rateControlMode = X265_RC_CRF;
    p->rc.rfConstant = crf;
    p->keyframeMax = keyint;
    p->bOpenGOP = 0;          // every key frame an IDR: MP4 sync samples players can seek to
    if (bframes >= 0) p->bframes = bframes;  // else the preset's
    p->bRepeatHeaders = 0;    // VPS/SPS/PPS go in the track header (csd-0) instead
    p->bAnnexB = 1;
    p->vui.bEnableVideoSignalTypePresentFlag = 1;
    p->vui.bEnableColorDescriptionPresentFlag = 1;
    p->vui.colorPrimaries = primaries;
    p->vui.transferCharacteristics = transfer;
    p->vui.matrixCoeffs = matrix;
    p->vui.bEnableVideoFullRangeFlag = fullRange ? 1 : 0;
    if (x265_param_apply_profile(p, "main10") < 0) LOGW("x265: main10 profile not applied");

    s->encoder = x265_encoder_open(p);
    if (!s->encoder) {
        LOGW("x265: encoder_open failed");
        x265_param_free(p);
        delete s;
        return 0;
    }
    s->in = x265_picture_alloc();
    s->out = x265_picture_alloc();
    x265_picture_init(p, s->in);
    x265_picture_init(p, s->out);
    int cw = width / 2;
    int ch = height / 2;
    s->planes[0].resize((size_t) width * height);
    s->planes[1].resize((size_t) cw * ch);
    s->planes[2].resize((size_t) cw * ch);
    s->in->bitDepth = 10;
    s->in->colorSpace = X265_CSP_I420;
    for (int i = 0; i < 3; i++) s->in->planes[i] = s->planes[i].data();
    s->in->stride[0] = width * 2;
    s->in->stride[1] = cw * 2;
    s->in->stride[2] = cw * 2;
    return reinterpret_cast<jlong>(s);
}

/** VPS, SPS and PPS (Annex B), for the MP4 track's csd-0. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_authrec_record_X265Encoder_nativeHeaders(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    x265_nal* nals = nullptr;
    uint32_t count = 0;
    if (x265_encoder_headers(s->encoder, &nals, &count) < 0) return nullptr;
    size_t size = 0;
    for (uint32_t i = 0; i < count; i++) size += nals[i].sizeBytes;
    jbyteArray out = env->NewByteArray((jsize) size);
    size_t at = 0;
    for (uint32_t i = 0; i < count; i++) {
        env->SetByteArrayRegion(out, (jsize) at, (jsize) nals[i].sizeBytes, reinterpret_cast<const jbyte*>(nals[i].payload));
        at += nals[i].sizeBytes;
    }
    return out;
}

/**
 * Encodes one frame (or, with y == null, drains the frames x265 still holds). Returns the size of
 * the access unit written to [out] (0 = none yet; after a drain call, 0 = all out), or -1 if it
 * didn't fit. meta: [pts, dts, key frame 1/0].
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_authrec_record_X265Encoder_nativeEncode(JNIEnv* env, jclass, jlong handle,
                                                 jobject y, jint yRow, jint yPix,
                                                 jobject u, jint uRow, jint uPix,
                                                 jobject v, jint vRow, jint vPix,
                                                 jint bytesPerSample, jint shift, jlong pts,
                                                 jobject out, jlongArray meta) {
    auto* s = reinterpret_cast<Session*>(handle);
    x265_picture* in = nullptr;
    if (y != nullptr) {
        int cw = s->width / 2;
        int ch = s->height / 2;
        convertPlane(static_cast<const uint8_t*>(env->GetDirectBufferAddress(y)), yRow, yPix, bytesPerSample, shift,
                     s->width, s->height, s->planes[0].data());
        convertPlane(static_cast<const uint8_t*>(env->GetDirectBufferAddress(u)), uRow, uPix, bytesPerSample, shift,
                     cw, ch, s->planes[1].data());
        convertPlane(static_cast<const uint8_t*>(env->GetDirectBufferAddress(v)), vRow, vPix, bytesPerSample, shift,
                     cw, ch, s->planes[2].data());
        s->in->pts = pts;
        in = s->in;
    }
    x265_nal* nals = nullptr;
    uint32_t count = 0;
    int r = x265_encoder_encode(s->encoder, &nals, &count, in, s->out);
    if (r <= 0 || count == 0) return 0;
    jint size = copyNals(nals, count, static_cast<uint8_t*>(env->GetDirectBufferAddress(out)), env->GetDirectBufferCapacity(out));
    jlong m[3] = {s->out->pts, s->out->dts, IS_X265_TYPE_I(s->out->sliceType) ? 1 : 0};
    env->SetLongArrayRegion(meta, 0, 3, m);
    return size;
}

extern "C" JNIEXPORT void JNICALL
Java_com_authrec_record_X265Encoder_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->encoder) x265_encoder_close(s->encoder);
    if (s->in) x265_picture_free(s->in);
    if (s->out) x265_picture_free(s->out);
    if (s->param) x265_param_free(s->param);
    delete s;
}
