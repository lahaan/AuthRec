// Shared by the encoder bridges: decoder output planes (MediaCodec Image: 8-bit, or P010 with
// the 10-bit value in the top bits of 16) to the planar buffers the encoders take.
#pragma once

#include <cstdint>
#include <cstddef>

namespace authrec {

/** One plane of [w]×[h] samples to 10-bit (0..1023) in 16-bit words. */
inline void planeTo10(const uint8_t* src, int rowStride, int pixelStride, int bytesPerSample, int shift,
                      int w, int h, uint16_t* dst, int dstStride) {
    for (int y = 0; y < h; y++) {
        const uint8_t* row = src + (size_t) y * rowStride;
        uint16_t* d = dst + (size_t) y * dstStride;
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

/** One plane to 8-bit, rounding 10-bit sources. */
inline void planeTo8(const uint8_t* src, int rowStride, int pixelStride, int bytesPerSample, int shift,
                     int w, int h, uint8_t* dst, int dstStride) {
    for (int y = 0; y < h; y++) {
        const uint8_t* row = src + (size_t) y * rowStride;
        uint8_t* d = dst + (size_t) y * dstStride;
        if (bytesPerSample == 2) {
            for (int x = 0; x < w; x++) {
                const uint8_t* p = row + (size_t) x * pixelStride;
                int v10 = (p[0] | (p[1] << 8)) >> shift;
                int v8 = (v10 + 2) >> 2;
                d[x] = (uint8_t) (v8 > 255 ? 255 : v8);
            }
        } else {
            for (int x = 0; x < w; x++) d[x] = row[(size_t) x * pixelStride];
        }
    }
}

}  // namespace authrec
