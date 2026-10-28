// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLV-App's raw-stage tools (llrawproc, vendored unchanged) on a CRAW Bayer
// frame, in MLV-App's order (applyLLRawProcObject): vertical stripes, bad
// pixels, pattern noise, chroma smoothing. MLV-App's tools assume RGGB and
// 14-bit data: the frame is phase-shifted to RGGB (one row/column dropped at
// the borders) and scaled to 14 bits exactly as MLV-App's make_14bit /
// undo_14bit do. Dark frame (needs a dark take), focus pixels and dual ISO
// (Canon) and deflicker (disabled in MLV-App) are not included.
#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

extern "C" {
#include "llrawproc/stripes.h"
#include "llrawproc/pixelproc.h"
#include "llrawproc/patternnoise.h"
}

struct RawToolSettings {
    bool enabled = true;      // MLV-App "raw fixes enabled" (receipt default true)
    int verticalStripes = 0;  // 0 off, 1 normal, 2 force
    int badPixels = 0;        // 0 off, 1 auto, 2 force (map mode needs camera map files)
    int bpsMethod = 0;        // bad pixel search: 0 normal, 1 aggressive
    int bpiMethod = 0;        // bad pixel interpolation method
    int chromaSmooth = 0;     // 0 off, 1 2x2, 2 3x3, 3 5x5
    int patternNoise = 0;     // 0 off, 1 on
    bool operator==(const RawToolSettings&) const = default;
};

class RawTools {
public:
    ~RawTools();
    // In place on a width x height CFA frame of `bits` bits (black/white in raw units).
    void apply(const RawToolSettings& s, uint16_t* px, int width, int height, int cfa, int black, int white, int bits);

private:
    std::mutex mutex_;
    RawToolSettings last_;
    stripes_correction stripes_{};
    int computeStripes_ = 1;
    pixel_map badPixels_{};
    int badPixelStatus_ = 0;
    int* raw2ev_ = nullptr;
    int* ev2raw_ = nullptr;
    int lutBlack_ = -1;
    std::vector<uint16_t> buf_;
};
