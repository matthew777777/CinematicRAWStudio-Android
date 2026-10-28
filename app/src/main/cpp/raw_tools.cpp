// SPDX-License-Identifier: GPL-3.0-or-later
#include "raw_tools.h"

#include <algorithm>
#include <cstring>

RawTools::~RawTools() {
    free_luts(raw2ev_, ev2raw_);
    pixel_map noFocusPixels{};  // free_pixel_maps dereferences both maps
    free_pixel_maps(&noFocusPixels, &badPixels_);
}

void RawTools::apply(const RawToolSettings& s, uint16_t* px, int width, int height, int cfa, int black, int white, int bits) {
    if (!s.enabled || (s.verticalStripes == 0 && s.badPixels == 0 && s.chromaSmooth == 0 && s.patternNoise == 0)) return;
    std::lock_guard<std::mutex> lock(mutex_);
    // New settings: searches and stripe estimates start over (MLV-App resets its caches).
    if (!(s == last_)) {
        reset_bpm_status(&badPixels_, &badPixelStatus_);
        computeStripes_ = 1;
        last_ = s;
    }
    // RGGB phase: GRBG shifts one column, GBRG one row, BGGR both.
    const int dx = (cfa == 1 || cfa == 3) ? 1 : 0, dy = (cfa == 2 || cfa == 3) ? 1 : 0;
    const int w = (width - 2 * dx) & ~1, h = (height - 2 * dy) & ~1;
    if (w < 16 || h < 16) return;
    buf_.resize(size_t(w) * h);
    for (int y = 0; y < h; ++y) std::memcpy(&buf_[size_t(y) * w], px + size_t(y + dy) * width + dx, size_t(w) * 2);

    // make_14bit (llrawproc.c): shift samples and levels up to 14 bits.
    const int shift = std::max(0, 14 - bits);
    const int black14 = black << shift, white14 = white << shift;
    if (shift) for (auto& v : buf_) v = static_cast<uint16_t>(v << shift);
    if (lutBlack_ != black14) {
        free_luts(raw2ev_, ev2raw_);
        raw2ev_ = get_raw2ev(black14);
        ev2raw_ = get_ev2raw(black14);
        lutBlack_ = black14;
    }

    if (s.verticalStripes)
        fix_vertical_stripes(&stripes_, buf_.data(), black14, white14, w * h * 14 / 8, w, h, s.verticalStripes, &computeStripes_);
    if (s.badPixels == 1 || s.badPixels == 2)
        fix_bad_pixels(&badPixels_, &badPixelStatus_, buf_.data(), 0, w, h, 0, 0, w, h, black14, s.badPixels,
                       s.bpsMethod, s.bpiMethod, 0, raw2ev_, ev2raw_);
    if (s.patternNoise)
        fix_pattern_noise(reinterpret_cast<int16_t*>(buf_.data()), w, h, white14, 0);
    if (s.chromaSmooth) {
        // chroma_smooth() takes 2, 3 or 5. MLV-App c4357a3 passes its UI enum
        // (CS_2x2 = 1, CS_3x3 = 2, CS_5x5 = 3) unchanged, so there 2x2 does
        // nothing and 3x3/5x5 run 2x2/3x3; here each label runs its method.
        static const int kMethod[4] = {0, 2, 3, 5};
        chroma_smooth(kMethod[std::clamp(s.chromaSmooth, 0, 3)], buf_.data(), w, h, black14, white14, raw2ev_, ev2raw_);
    }

    // undo_14bit (llrawproc.c): back to the original bit depth with rounding.
    if (shift) {
        const uint32_t round = 1u << (shift - 1);
        for (auto& v : buf_) v = static_cast<uint16_t>((v + round) >> shift);
    }
    for (int y = 0; y < h; ++y) std::memcpy(px + size_t(y + dy) * width + dx, &buf_[size_t(y) * w], size_t(w) * 2);
}
