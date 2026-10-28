// SPDX-License-Identifier: GPL-3.0-or-later
// Raw-tool parity: runs the app's RawTools adapter (MLV-App llrawproc tools)
// on a real CRAW Bayer frame and prints a hash per tool, so host and Android
// builds can be compared. Also checks: everything off is a no-op, and
// chroma_smooth(method 1) - what MLV-App c4357a3 passes for "2x2" - changes
// nothing.  usage: rawtools frame.raw width height cfa [dump-dir]
#include "raw_tools.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static unsigned long long fnv(const std::vector<uint16_t>& v) {
    unsigned long long h = 1469598103934665603ull;
    for (uint16_t x : v) { h ^= x; h *= 1099511628211ull; }
    return h;
}

int main(int argc, char** argv) {
    const int w = atoi(argv[2]), h = atoi(argv[3]), cfa = atoi(argv[4]);
    std::vector<uint16_t> in(size_t(w) * h);
    FILE* f = fopen(argv[1], "rb");
    if (!f || fread(in.data(), 2, in.size(), f) != in.size()) return 1;
    fclose(f);
    struct Case { const char* name; RawToolSettings s; };
    RawToolSettings off; RawToolSettings st = off, bp = off, pn = off, c2 = off, c3 = off, c5 = off, all = off;
    st.verticalStripes = 2; bp.badPixels = 2; pn.patternNoise = 1; c2.chromaSmooth = 1; c3.chromaSmooth = 2; c5.chromaSmooth = 3;
    all.verticalStripes = 2; all.badPixels = 2; all.patternNoise = 1; all.chromaSmooth = 2;
    RawToolSettings sb = off, sp = off, sc = off, bpn = off, bc = off, pc = off;
    sb.verticalStripes = 2; sb.badPixels = 2;
    sp.verticalStripes = 2; sp.patternNoise = 1;
    sc.verticalStripes = 2; sc.chromaSmooth = 2;
    bpn.badPixels = 2; bpn.patternNoise = 1;
    bc.badPixels = 2; bc.chromaSmooth = 2;
    pc.patternNoise = 1; pc.chromaSmooth = 2;
    const Case cases[] = {{"off", off}, {"stripes", st}, {"badpixels", bp}, {"patternnoise", pn},
                          {"chroma2x2", c2}, {"chroma3x3", c3}, {"chroma5x5", c5},
                          {"stripes+bad", sb}, {"stripes+pn", sp}, {"stripes+cs", sc}, {"bad+pn", bpn},
                          {"bad+cs", bc}, {"pn+cs", pc}, {"all", all}};
    for (const Case& c : cases) {
        RawTools tools;
        std::vector<uint16_t> px(in);
        tools.apply(c.s, px.data(), w, h, cfa, 64, 1023, 10);
        size_t changed = 0;
        for (size_t i = 0; i < px.size(); ++i) changed += px[i] != in[i];
        printf("%-12s fnv=%016llx changed=%zu\n", c.name, fnv(px), changed);
        if (argc > 5) {  // dump outputs for value comparison: <dir>/<case>.raw
            char n[512];
            snprintf(n, sizeof n, "%s/%s.raw", argv[5], c.name);
            if (FILE* o = fopen(n, "wb")) { fwrite(px.data(), 2, px.size(), o); fclose(o); }
        }
    }
    // MLV-App's own call for "2x2" (method = CS_2x2 = 1) on 14-bit data.
    std::vector<uint16_t> m(in);
    for (auto& v : m) v = static_cast<uint16_t>(v << 4);
    std::vector<uint16_t> m0(m);
    int* r2e = get_raw2ev(64 << 4); int* e2r = get_ev2raw(64 << 4);
    chroma_smooth(1, m.data(), w, h, 64 << 4, 1023 << 4, r2e, e2r);
    printf("mlvapp-2x2-method1 unchanged=%s\n", m == m0 ? "yes" : "NO");
    return 0;
}
