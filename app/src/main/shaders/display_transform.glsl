// SPDX-License-Identifier: GPL-3.0-or-later
// AgX portion: Copyright (C) 2021 The Android Open Source Project, Apache-2.0.
// Google Filament AgX Base, pinned to 2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad.
// Filament's Rec.2020 input/output is explicitly adapted to our Rec.709 scene/display.
vec3 filamentAgx(vec3 scene) {
    const mat3 to2020=mat3(.6274039,.0690973,.0163914, .3292830,.9195404,.0880133, .0433131,.0113623,.8955953);
    const mat3 inset=mat3(.856627153315983,.137318972929847,.11189821299995, .0951212405381588,.761241990602591,.0767994186031903, .0482516061458583,.101439036467562,.811302368396859);
    const mat3 outset=mat3(1.127100581814437,-.141329763498438,-.141329763498438, -.110606643096603,1.157823702216272,-.110606643096603, -.016493938717834,-.016493938717834,1.251936406595041);
    const mat3 to709=mat3(1.660491,-.1245505,-.0181508, -.5876411,1.1328999,-.1005789, -.0728499,-.0083494,1.1187297);
    vec3 v=inset*max(to2020*scene,vec3(0));
    v=clamp((log2(max(v,vec3(1e-10)))+12.47393)/16.5,0.0,1.0);
    vec3 x2=v*v,x4=x2*x2,x6=x4*x2;
    v=-17.86*x6*v+78.01*x6-126.7*x4*v+92.06*x4-28.72*x2*v+4.361*x2-.1718*v+.002857;
    v=to709*pow(max(outset*v,vec3(0)),vec3(2.2));
    v=clamp(v,0.0,1.0);
    return mix(1.055*pow(v,vec3(1.0/2.4))-.055,12.92*v,lessThanEqual(v,vec3(.0031308)));
}
