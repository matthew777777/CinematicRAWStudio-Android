// SPDX-License-Identifier: GPL-3.0-or-later
// Small host adapter for OCIO's unchanged ACES2 sources. No OCIO runtime required.
#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <memory>
#ifndef OCIO_NAMESPACE
#define OCIO_NAMESPACE crs_ocio
#endif
namespace OCIO_NAMESPACE {
inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }
struct MatrixOpData {
    struct MatrixArray {
        using Values = std::array<double, 16>;
        Values v{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        Values& getValues() { return v; }
        const Values& getValues() const { return v; }
        std::shared_ptr<MatrixArray> inverse() const {
            double a[4][8]{};
            for (int r=0;r<4;++r) for(int c=0;c<4;++c) { a[r][c]=v[r*4+c]; a[r][c+4]=r==c; }
            for(int c=0;c<4;++c) {
                int pivot=c;
                for(int r=c+1;r<4;++r) if(std::abs(a[r][c])>std::abs(a[pivot][c])) pivot=r;
                for(int j=0;j<8;++j) std::swap(a[c][j],a[pivot][j]);
                const double d=a[c][c];
                for(int j=0;j<8;++j) a[c][j]/=d;
                for(int r=0;r<4;++r) if(r!=c) { const double f=a[r][c]; for(int j=0;j<8;++j) a[r][j]-=f*a[c][j]; }
            }
            auto out=std::make_shared<MatrixArray>();
            for(int r=0;r<4;++r) for(int c=0;c<4;++c) out->v[r*4+c]=a[r][c+4];
            return out;
        }
    };
};
}
