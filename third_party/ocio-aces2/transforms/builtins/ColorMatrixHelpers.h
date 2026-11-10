// SPDX-License-Identifier: GPL-3.0-or-later
// Chromaticity/matrix host adapter for the unchanged OCIO ACES2 algorithm.
#pragma once
#include "ops/matrix/MatrixOpData.h"
namespace OCIO_NAMESPACE {
struct Chromaticities { double m_xy[2]; Chromaticities(double x,double y):m_xy{x,y}{} };
struct Primaries { Chromaticities m_red,m_grn,m_blu,m_wht; Primaries(Chromaticities r,Chromaticities g,Chromaticities b,Chromaticities w):m_red(r),m_grn(g),m_blu(b),m_wht(w){} };
namespace CIE_XYZ_ILLUM_E { inline const Primaries primaries{{1,0},{0,1},{0,0},{1.0/3,1.0/3}}; }
namespace ACES_AP0 { inline const Primaries primaries{{.7347,.2653},{0,1},{.0001,-.077},{.32168,.33767}}; }
namespace ACES_AP1 { inline const Primaries primaries{{.713,.293},{.165,.830},{.128,.044},{.32168,.33767}}; }
namespace REC709 { inline const Primaries primaries{{.64,.33},{.30,.60},{.15,.06},{.3127,.3290}}; }
inline constexpr int ADAPTATION_NONE=0;
inline MatrixOpData::MatrixArray rgbToXyz(const Primaries& p) {
    MatrixOpData::MatrixArray m;
    const Chromaticities cs[]={p.m_red,p.m_grn,p.m_blu};
    for(int c=0;c<3;++c) { m.v[c]=cs[c].m_xy[0]; m.v[4+c]=cs[c].m_xy[1]; m.v[8+c]=1-cs[c].m_xy[0]-cs[c].m_xy[1]; }
    auto inv=m.inverse();
    const double w[]={p.m_wht.m_xy[0]/p.m_wht.m_xy[1],1,(1-p.m_wht.m_xy[0]-p.m_wht.m_xy[1])/p.m_wht.m_xy[1]};
    double s[3]{};
    for(int r=0;r<3;++r) for(int c=0;c<3;++c) s[r]+=inv->v[r*4+c]*w[c];
    for(int r=0;r<3;++r) for(int c=0;c<3;++c) m.v[r*4+c]*=s[c];
    return m;
}
inline std::shared_ptr<MatrixOpData::MatrixArray> build_conversion_matrix(const Primaries& src,const Primaries& dst,int) {
    auto a=rgbToXyz(src); auto b=rgbToXyz(dst).inverse(); auto out=std::make_shared<MatrixOpData::MatrixArray>();
    for(int r=0;r<3;++r) for(int c=0;c<3;++c) { out->v[r*4+c]=0; for(int k=0;k<3;++k) out->v[r*4+c]+=b->v[r*4+k]*a.v[k*4+c]; }
    return out;
}
}
