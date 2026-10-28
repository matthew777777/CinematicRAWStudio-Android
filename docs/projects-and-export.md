# Projects and export — first milestone

The existing single-take editor remains available. The library's Projects button
opens saved sequences; CinemaDNG is a separate original-source conversion action.

## Project engine

`SequenceProject` is a versioned immutable model of source assets, tracks, clips,
source ranges, timeline positions, linked camera audio, and per-clip MLV-App
receipts. All times are integer microseconds; project frame rates are rational.
Ends are exclusive. Editing validates source bounds, non-overlap on each track,
and track/source compatibility before committing an edit.

The first UI exposes one video track, linked A1 camera audio, A2/A3 additional
audio tracks, and additional audio tracks on demand. Clips can be moved, trimmed
by dragging their edges or entering frame numbers, split at the playhead, and
removed. Linked camera audio follows picture edits. Gaps remain explicit;
this milestone does not perform automatic ripple edits or transitions.

Projects autosave through a serial, conflated writer and Android AtomicFile.
Navigation flushes the latest snapshot. Undo/redo keeps up to 80 prior edits.
Takes imported from the library carry their existing saved grade into the project.
Sources are referenced by persisted document URIs; Relink source replaces a
missing source while keeping the clip's edit decisions, provided its range fits.

## Preview and video export

`SequenceSchedule` resolves source frames and active audio spans. The monitor
and exporter both use `ProjectFrameRenderer`; it runs the existing RawLens RCD
and MLV-App grade, then fits the processed frame to the project/output canvas.
The current single-take receipt is wrapped in this same model for video export.
No grade is silently discarded when GPU processing is unavailable.

Preview follows a single 48 kHz stereo AudioTrack clock. The same disk-backed
mixer feeds export: it resamples mono/stereo sources, applies clip/track gain,
mute/solo, aligns source ranges, sums tracks, and clamps output to avoid integer
wrap. Supported compressed imports use Android decoders; PCM WAV supports
8/16/24/32-bit integers and 32-bit float. Multichannel audio is rejected explicitly.
Preview may drop video frames when RAW development cannot keep up; export
renders every scheduled frame. Playback pauses while the export queue owns the
RAW pipeline. Vulkan mode changes and processing are serialized across modes.

MP4 delivery supports hardware H.264/HEVC **8-bit SDR** and HEVC Main10
**10-bit SDR**, selectable
resolution, bitrate presets/custom bitrate, and optional mixed AAC audio. Settings
are checked against device codec capabilities. Main10 uses the retained native
MLV-App processed RGB16 (or the native recorded-RGB developer output), with
bilinear fitting and BT.709 conversion directly into the codec’s P010 planes.
It never encodes the ARGB8 preview as a substitute for this precision. The actual
HEVC SPS must report 10-bit luma and chroma; an 8-bit fallback fails explicitly.
Some hardware under-reports Main10, so configuration and bitstream verification
are the final gates. HDR, ProRes, APV, and DNx export remain outside this milestone.

AAC priming is measured for the actual encoder using a short calibration signal
and cached per device/encoder/configuration. An MP4 edit list removes the measured
priming and trailing padding from presentation, preserving all pre-roll media.
The metadata rewrite preserves existing media/chunk offsets. Final output is
staged and written to the newly selected document only after encoding succeeds.

The APK reference informs codec/resolution/quality/job controls. MLV-App's export
loop in `platform/qt/MainWindow.cpp` provides the processing-before-scaling and
serial-rendering reference. This does **not** claim parity with MLV-App's 16-bit
FFmpeg/intermediate export paths. Main10 uses the same 16-bit processed scratch
as the preview’s pre-quantization stage; delivery is limited-range Rec.709 SDR.

## Export jobs

Jobs persist complete project/grade/settings snapshots and output URIs. A
foreground data-sync service processes them serially with progress, cancellation,
retry, notification, and a bounded wake lock. A cancelled/failed job does not stop
the next job. A process-interrupted job is labeled Interrupted and requires Retry.
Library options → Export jobs opens the queue without opening footage.

## Original CinemaDNG

Library → CinemaDNG selects one or more original takes and an output folder.
Each take becomes a separate job and uniquely named subfolder. Frames use the
existing CRAW `Clip::frameDng` writer, preserving recorded samples, calibration,
white balance, shading opcodes, capture frame rate, and source timecode. Bayer
sources stay CFA; recorded binned RGB sources retain their recorded RGB data.
Original WAV audio and a conversion manifest/edit receipt accompany the frames.
**Creative editor/timeline adjustments are not baked into this original-source
conversion.** Video export is the separate path for rendering the edited look.

## Adjustment controls

RAW adjustment sliders use relative quarter-speed drags. Double-tap a slider to
restore that control's receipt default (temperature returns to as-shot). Tap its
numeric value for validated entry with OK/Cancel; exposure is entered in EV and
gamma in displayed units. The same controls serve single clips and project grades.

## Validation

- `./gradlew :app:testDebugUnitTest`: rational/NTSC timing, exclusive cuts, linked
  trim/split/move, collision rejection, mute/solo/gains, JSON/receipt round-trip,
  PCM windows, and MP4 AAC edit metadata preserving media offsets.
- `ProjectInstrumentation` on the connected device: real Bayer/RGB cuts, graded
  preview versus decoded H.264/HEVC/Main10 output, SPS-confirmed 10-bit luma/chroma,
  exact frame counts, two mixed audio
  tones at different input rates, AAC start/duration, single-clip graded export,
  original DNG output, persistent queue, cancellation followed by completion.
- Headless run: pass `-e headless true` to `ProjectInstrumentation` to exercise
  exports without activity launches, UI interaction, or queue foreground tests.
- UI checks before headless-only restriction: numeric OK/Cancel, double-tap reset, reduced drag sensitivity,
  project creation/import/playback/save/reopen, portrait/landscape, export panels.
- Test media is written to the app's `project-review` directory. Original footage
  is never modified by editing, export, or these checks.

Latest headless device run passed H.264, HEVC 8-bit, and HEVC Main10 edited
sequences with mixed audio; independent ffprobe inspection reported Main 10 /
`yuv420p10le`. Additional two-frame Main10 exports passed at 1280×720 and
2040×1144 at 30 fps. Eleven JVM tests passed. These are short integration samples,
not a long-duration export stress test. No activity was launched in that run.
