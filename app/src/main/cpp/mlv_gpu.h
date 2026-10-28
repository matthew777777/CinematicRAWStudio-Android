// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vulkan port of MLV-App's per-pixel processing (shaders/mlv_grade.comp) and
// the output conversion (shaders/mlv_output.comp). Reads MLV-App's own tables
// from a processing object; input is RawLens Packed RCD camera RGB (an
// RGBA16F AHardwareBuffer imported without copying).
#pragma once

#include <android/hardware_buffer.h>
#include <cstdint>
#include <string>
#include <vector>

extern "C" {
#include "raw_processing.h"
}

namespace mlvgpu {

// True when every enabled stage of `p` runs in the shader (no blur-based
// clarity/shadows/highlights, gradient, LUT, film filter, grain or
// spatial filters, no dual ISO).
bool supports(const processingObject_t* p);

class Engine {
public:
    Engine();
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    // SPIR-V of the two shaders. Returns an empty string on success.
    std::string init(const std::vector<uint32_t>& gradeSpv, const std::vector<uint32_t>& outputSpv);
    // Processes `src` (w x h RGBA16F) with `p`, fits it into outW x outH and
    // fills the P010 planes (and RGBA8 when wanted). Returns "" on success.
    std::string run(const processingObject_t* p, AHardwareBuffer* src, int w, int h, int outW, int outH, bool rgba);
    // After run(): tightly packed planes (Y row stride outW * 2 bytes; UV
    // interleaved Cb, Cr, row stride outW * 2 bytes) and RGBA8 (outW * 4).
    const uint16_t* lumaPlane() const;
    const uint16_t* chromaPlane() const;
    const uint8_t* rgba() const;
    // Processed RGB16 of the last run (parity tests): r | g << 16, b per pixel.
    std::vector<uint32_t> readProcessed() const;

private:
    struct Impl;
    Impl* d_;
};

}  // namespace mlvgpu
