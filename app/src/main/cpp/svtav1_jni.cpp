// JNI bridge to SVT-AV1 for "Compress" as AV1 (com.authrec.record.SvtAv1Encoder): 10-bit Main
// profile, the smallest files. Same shape as x265_jni.cpp, plus what AV1 in MP4 needs: the
// track's av1C record (built from the sequence header) and samples without temporal delimiters.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <EbSvtAv1Enc.h>
#include "codec_util.h"

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "AuthRec", __VA_ARGS__)

namespace {

struct Session {
    EbComponentType* encoder = nullptr;
    EbSvtAv1EncConfiguration config{};
    EbSvtIOFormat io{};
    EbBufferHeaderType in{};
    std::vector<uint16_t> planes[3];
    int width = 0;
    int height = 0;
    bool eosSent = false;
    bool done = false;
    // A hidden alt-ref frame waiting for the shown frame it goes out with: an MP4 sample is one
    // temporal unit, i.e. exactly one shown frame.
    std::vector<uint8_t> pending;
};

/** Reads bits MSB first from an OBU payload. */
struct Bits {
    const uint8_t* p;
    size_t size;
    size_t pos = 0;  // in bits
    uint32_t get(int n) {
        uint32_t v = 0;
        for (int i = 0; i < n; i++) {
            size_t byte = pos >> 3;
            int bit = byte < size ? (p[byte] >> (7 - (pos & 7))) & 1 : 0;
            v = (v << 1) | (uint32_t) bit;
            pos++;
        }
        return v;
    }
    uint32_t uvlc() {
        int zeros = 0;
        while (zeros < 32 && get(1) == 0) zeros++;
        if (zeros >= 32) return UINT32_MAX;
        return get(zeros) + ((1u << zeros) - 1);
    }
};

size_t leb128(const uint8_t* p, size_t avail, uint64_t* value) {
    *value = 0;
    for (size_t i = 0; i < 8 && i < avail; i++) {
        *value |= (uint64_t) (p[i] & 0x7f) << (i * 7);
        if (!(p[i] & 0x80)) return i + 1;
    }
    return 0;
}

/** Walks the OBUs in [data]; calls [f](type, start, totalSize, payloadStart, payloadSize) for each. */
template <typename F>
bool forEachObu(const uint8_t* data, size_t size, F f) {
    size_t at = 0;
    while (at < size) {
        uint8_t header = data[at];
        int type = (header >> 3) & 0x0f;
        bool extension = header & 0x04;
        bool hasSize = header & 0x02;
        size_t h = 1 + (extension ? 1 : 0);
        uint64_t payload;
        if (hasSize) {
            size_t n = leb128(data + at + h, size - at - h, &payload);
            if (n == 0) return false;
            h += n;
        } else {
            payload = size - at - h;
        }
        if (at + h + payload > size) return false;
        f(type, at, h + payload, at + h, (size_t) payload);
        at += h + payload;
    }
    return true;
}

constexpr int OBU_SEQUENCE_HEADER = 1;
constexpr int OBU_TEMPORAL_DELIMITER = 2;

/** av1C (AV1CodecConfigurationRecord) for 10-bit 4:2:0 from the sequence header OBU. */
std::vector<uint8_t> av1c(const uint8_t* seq, size_t seqSize, const uint8_t* payload, size_t payloadSize) {
    Bits b{payload, payloadSize};
    uint32_t profile = b.get(3);
    b.get(1);  // still_picture
    uint32_t reduced = b.get(1);
    uint32_t level = 31;  // "maximum" if the header can't be followed
    uint32_t tier = 0;
    if (reduced) {
        level = b.get(5);
    } else {
        uint32_t timing = b.get(1);
        uint32_t decoderModel = 0;
        if (timing) {
            b.get(32);
            b.get(32);
            if (b.get(1)) b.uvlc();
            decoderModel = b.get(1);
            if (decoderModel) {
                b.get(5);
                b.get(32);
                b.get(5);
                b.get(5);
            }
        }
        b.get(1);  // initial_display_delay_present_flag
        b.get(5);  // operating_points_cnt_minus_1: operating point 0 is all we need
        b.get(12);
        level = b.get(5);
        if (level > 7) tier = b.get(1);
    }
    std::vector<uint8_t> out = {
        0x81,  // marker 1, version 1
        (uint8_t) ((profile << 5) | (level & 0x1f)),
        // tier, high_bitdepth 1, twelve_bit 0, monochrome 0, subsampling 1/1, chroma position 0
        (uint8_t) ((tier << 7) | (1 << 6) | (0 << 5) | (0 << 4) | (1 << 3) | (1 << 2) | 0),
        0x00,  // no initial presentation delay
    };
    out.insert(out.end(), seq, seq + seqSize);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_authrec_record_SvtAv1Encoder_nativeOpen(JNIEnv*, jclass, jint width, jint height, jint fpsNum, jint fpsDen,
                                                 jint preset, jint crf, jint maxKbps, jint keyint) {
    auto* s = new Session();
    s->width = width;
    s->height = height;
    if (svt_av1_enc_init_handle(&s->encoder, &s->config) != EB_ErrorNone) {
        LOGW("SVT-AV1: init_handle failed");
        delete s;
        return 0;
    }
    EbSvtAv1EncConfiguration& c = s->config;
    c.source_width = (uint32_t) width;
    c.source_height = (uint32_t) height;
    c.frame_rate_numerator = (uint32_t) fpsNum;
    c.frame_rate_denominator = (uint32_t) fpsDen;
    c.encoder_bit_depth = 10;
    c.encoder_color_format = EB_YUV420;
    c.color_primaries = EB_CICP_CP_BT_709;
    c.transfer_characteristics = EB_CICP_TC_BT_709;
    c.matrix_coefficients = EB_CICP_MC_BT_709;
    c.color_range = EB_CR_STUDIO_RANGE;
    std::string p = std::to_string(preset), q = std::to_string(crf), k = std::to_string(keyint);
    svt_av1_enc_parse_parameter(&c, "preset", p.c_str());
    svt_av1_enc_parse_parameter(&c, "crf", q.c_str());
    svt_av1_enc_parse_parameter(&c, "keyint", k.c_str());
    if (maxKbps > 0) {  // capped CRF, as in x265_jni.cpp
        std::string m = std::to_string(maxKbps);
        svt_av1_enc_parse_parameter(&c, "mbr", m.c_str());
    }
    if (svt_av1_enc_set_parameter(s->encoder, &c) != EB_ErrorNone || svt_av1_enc_init(s->encoder) != EB_ErrorNone) {
        LOGW("SVT-AV1: settings refused");
        svt_av1_enc_deinit_handle(s->encoder);
        delete s;
        return 0;
    }
    int cw = width / 2;
    int ch = height / 2;
    s->planes[0].resize((size_t) width * height);
    s->planes[1].resize((size_t) cw * ch);
    s->planes[2].resize((size_t) cw * ch);
    s->io.luma = reinterpret_cast<uint8_t*>(s->planes[0].data());
    s->io.cb = reinterpret_cast<uint8_t*>(s->planes[1].data());
    s->io.cr = reinterpret_cast<uint8_t*>(s->planes[2].data());
    s->io.y_stride = (uint32_t) width;  // in samples
    s->io.cb_stride = (uint32_t) cw;
    s->io.cr_stride = (uint32_t) cw;
    s->in.size = sizeof(EbBufferHeaderType);
    s->in.p_buffer = reinterpret_cast<uint8_t*>(&s->io);
    s->in.n_filled_len = (uint32_t) ((width * height + 2 * cw * ch) * 2);
    s->in.n_alloc_len = s->in.n_filled_len;
    s->in.pic_type = EB_AV1_INVALID_PICTURE;
    return reinterpret_cast<jlong>(s);
}

/** The track's csd-0: an av1C record holding the sequence header. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_authrec_record_SvtAv1Encoder_nativeHeaders(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    EbBufferHeaderType* header = nullptr;
    if (svt_av1_enc_stream_header(s->encoder, &header) != EB_ErrorNone || !header) return nullptr;
    std::vector<uint8_t> record;
    forEachObu(header->p_buffer, header->n_filled_len, [&](int type, size_t start, size_t total, size_t payload, size_t payloadSize) {
        if (type == OBU_SEQUENCE_HEADER && record.empty()) {
            record = av1c(header->p_buffer + start, total, header->p_buffer + payload, payloadSize);
        }
    });
    svt_av1_enc_stream_header_release(header);
    if (record.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize) record.size());
    env->SetByteArrayRegion(out, 0, (jsize) record.size(), reinterpret_cast<const jbyte*>(record.data()));
    return out;
}

namespace {

/** Appends the packet's OBUs except temporal delimiters (the muxer's samples are TUs) to [to]. */
void appendObus(const EbBufferHeaderType* pkt, std::vector<uint8_t>& to) {
    forEachObu(pkt->p_buffer, pkt->n_filled_len, [&](int type, size_t start, size_t total, size_t, size_t) {
        if (type != OBU_TEMPORAL_DELIMITER) to.insert(to.end(), pkt->p_buffer + start, pkt->p_buffer + start + total);
    });
}

/**
 * Takes packets until one holds a shown frame; writes it (with any alt-ref before it) to [out].
 * Returns its size, 0 = none ready (or the stream is over), -1 = didn't fit.
 */
jint takePacket(Session* s, JNIEnv* env, jobject out, jlongArray meta) {
    while (!s->done) {
        EbBufferHeaderType* pkt = nullptr;
        // Non-blocking while frames still come in; blocking once the stream has ended.
        EbErrorType r = svt_av1_enc_get_packet(s->encoder, &pkt, s->eosSent ? 1 : 0);
        if (r == EB_ErrorMax) {
            LOGW("SVT-AV1: encode error");
            if (pkt) svt_av1_enc_release_out_buffer(&pkt);
            s->done = true;
            return -2;
        }
        if (r != EB_ErrorNone || !pkt) return 0;
        if (pkt->flags & EB_BUFFERFLAG_EOS) {
            s->done = true;
            svt_av1_enc_release_out_buffer(&pkt);
            return 0;
        }
        appendObus(pkt, s->pending);
        bool altRef = pkt->flags & EB_BUFFERFLAG_IS_ALT_REF;
        jlong m[3] = {pkt->pts, pkt->dts, pkt->pic_type == EB_AV1_KEY_PICTURE ? 1 : 0};
        svt_av1_enc_release_out_buffer(&pkt);
        if (altRef) continue;
        jint size = (jint) s->pending.size();
        if (size > env->GetDirectBufferCapacity(out)) return -1;
        memcpy(env->GetDirectBufferAddress(out), s->pending.data(), s->pending.size());
        s->pending.clear();
        env->SetLongArrayRegion(meta, 0, 3, m);
        return size;
    }
    return 0;
}

}  // namespace

/**
 * As X265Encoder.nativeEncode: y == null drains (the first such call ends the stream); returns
 * the size of a temporal unit written to [out], 0 = none (or, draining, all out), -1 = didn't fit,
 * -2 = encoder error.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_authrec_record_SvtAv1Encoder_nativeEncode(JNIEnv* env, jclass, jlong handle,
                                                   jobject y, jint yRow, jint yPix,
                                                   jobject u, jint uRow, jint uPix,
                                                   jobject v, jint vRow, jint vPix,
                                                   jint bytesPerSample, jint shift, jlong pts,
                                                   jobject out, jlongArray meta) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s->done) return 0;
    if (y != nullptr) {
        int cw = s->width / 2;
        int ch = s->height / 2;
        authrec::planeTo10(static_cast<const uint8_t*>(env->GetDirectBufferAddress(y)), yRow, yPix, bytesPerSample, shift,
                           s->width, s->height, s->planes[0].data(), s->width);
        authrec::planeTo10(static_cast<const uint8_t*>(env->GetDirectBufferAddress(u)), uRow, uPix, bytesPerSample, shift,
                           cw, ch, s->planes[1].data(), cw);
        authrec::planeTo10(static_cast<const uint8_t*>(env->GetDirectBufferAddress(v)), vRow, vPix, bytesPerSample, shift,
                           cw, ch, s->planes[2].data(), cw);
        s->in.pts = pts;
        s->in.flags = 0;
        if (svt_av1_enc_send_picture(s->encoder, &s->in) != EB_ErrorNone) return -2;
    } else if (!s->eosSent) {
        EbBufferHeaderType eos{};
        eos.size = sizeof(EbBufferHeaderType);
        eos.flags = EB_BUFFERFLAG_EOS;
        svt_av1_enc_send_picture(s->encoder, &eos);
        s->eosSent = true;
    }
    return takePacket(s, env, out, meta);
}

extern "C" JNIEXPORT void JNICALL
Java_com_authrec_record_SvtAv1Encoder_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->encoder) {
        svt_av1_enc_deinit(s->encoder);
        svt_av1_enc_deinit_handle(s->encoder);
    }
    delete s;
}
