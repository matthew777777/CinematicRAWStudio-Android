# DCG/DSG CRAW14 interoperability

CRAW payload bit depth is the stored sample range, independent of the camera's
stock static white-level metadata. A verified RAW14 recording must use 14-bit
payloads and preserve the original codes; no shift/mask to 10 bits is allowed.
Frame dynamic white/black levels remain calibration metadata in that code domain.

The vendored Decoder keeps packed DNG depth at least as wide as the source
payload, including when container metadata still describes a 10-bit mode.
Compact conversion preserves header depth and samples exactly. The host Compact
roundtrip suite includes 14 bits across every Bayer phase and RGB, all codecs,
multiple frames and preserved ancillary data. This does not certify sensor ADC
precision, dynamic range or recording cadence.
