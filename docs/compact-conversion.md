# Same-directory CRAW Compact conversion

The encoder/decoder default-branch commits were checked through GitHub on 2026-11-10 and the vendored library sources updated to match the local checkouts at those exact commits:

- [Encoder 96f41089f7b4e593dde513c593f8c7fc3cb27536](https://github.com/matthew777777/CinematicRAWStudio-Encoder/commit/96f41089f7b4e593dde513c593f8c7fc3cb27536)
- [Decoder a0715ad8b34d53954b427834f466fa77073501e5](https://github.com/matthew777777/CinematicRAWStudio-Decoder/commit/a0715ad8b34d53954b427834f466fa77073501e5)

Both support Compact (codec 4), including recorded RGB. The snapshot has a file-hash manifest and can be checked with `python3 tools/verify_craw_sources.py`. `tools/sync_craw.sh DECODER ENCODER` refreshes the library snapshot and records new hashes; it does not claim a later network check automatically.

## User flow

Long-press a Library take → **Convert to CRAW Compact**, or Library options → **Convert takes to CRAW Compact** for multiple takes.

The conversion writes a unique temporary sibling and publishes a `take.compact.craw` file in the same source directory. Existing outputs get a numeric suffix; source files are not overwritten. If source-folder write permission is missing, the app requests that folder and checks it actually contains the source document. It never silently exports to a different directory. A provider must support seekable files and renaming the temporary sibling.

The job runs in the existing foreground export queue, supports cancellation, persists partial-output locations, and appears in Exports. Grades and timeline trims are not baked into the RAW copy. Saved per-source grades are copied to the new URI.

The native adapter follows the upstream `craw_compact` container algorithm. It encodes codec 4 and retains original lossless frame payloads when those are already smaller. Therefore a Compact archive can contain mixed codecs, intentionally, and may not shrink every take. Already-Compact frames are copied. The original META/frame JSON, indices/timestamps, opaque chunk flags, audio, IMU, lens shading and unknown ancillary payloads are preserved; the container index/trailer is rebuilt.

## Verification and optional original removal

Before declaring success the app reopens the published file, checks the complete container/index/trailer and CRCs, compares every decoded RAW sample and frame format, compares frame metadata/prefixes/timestamps, and compares every ancillary chunk byte-for-byte. Recovered/incomplete sources, corrupt data, existing non-empty destinations and source/output aliases are rejected.

A completed verification record is persisted. When the queue is idle the app asks whether to **Keep original** or **Delete original Balanced/Live/etc.** There is no automatic source deletion. Dismissal keeps the original. The dialog reports both file sizes, including cases with no size reduction.

After explicit deletion confirmation the app rechecks the actual original and Compact files again. Only then does it preserve grade data, record durable source-URI/document-ID replacement mappings and remove the original. Existing saved/in-memory project links resolve to the Compact copy. Cancelled/failed verification keeps the original; failed deletion keeps both files. Restarting the app retains a pending decision. Completed outputs are not deleted by clearing/removing export history.

SAF content URIs are normalized through the granted parent for background/restarted access. The deletion operation uses the original file, not its replacement alias. Publication and original removal refresh the Library.

## Local checks

`python3 tests/compact/run.py` compiles the actual Android conversion adapter against the vendored encoder/decoder and tests all Bayer phases plus recorded RGB at 10/12/16-bit, source codecs 1–4, full RAW equality, preserved audio/IMU/shading/opaque chunks, corruption, valid-CRC sample/audio changes, recovered sources and overwrite/self-verification rejection. The source files must remain byte-identical.

JVM checks cover deletion eligibility, persistent verification records with >4 GB sizes, rejection of incomplete native reports, and replacement chains/cycles. Android test and release APKs are built locally; the SAF dialogs/background notification/deletion flow has not been manually exercised on a phone in this task. No user take was converted or deleted by the development checks.
