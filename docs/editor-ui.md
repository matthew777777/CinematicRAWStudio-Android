# Editor UI

The shared theme follows RawLens's charcoal and sea-sand palette (`#070809`,
`#121417`, `#DDC08D`). Numeric adjustment values and timecode use monospace.
RapidRAW's compact/wide layout and focused tool panels inform the editor layout.

- Portrait keeps the preview above the timeline and adjustment panel.
- Landscape centers the preview in the space to the left of a 240–300 dp
  adjustment panel. A 36 dp single-row header and 60 dp timeline leave most
  vertical space for the preview; the system status bar hides in landscape.
  The toolbar has 16 dp end padding to keep the Edit button away from the edge.
- Landscape tools use a bounded dropdown with previous/next tool buttons, so
  the tool list does not extend past the screen edge.
- The adjustment toggle hides the panel for a larger preview in either orientation.
- Light, Color, Curves, Detail, Profile, RAW, and Lens expose the existing
  processing controls. Undo/redo and reset operate on the current take's receipt.
- Edits save per take, including on exit. Adjusting a playing take pauses playback.
  Playback uses the existing preview pipeline; grades are visible on paused frames.
- The timeline samples source frames, supports tap/drag seeking, announces its
  frame range to accessibility services, and offers previous/next frame controls.
  It is a single-take navigation timeline; sequence editing and trimming are future work.
- Clip information includes audio/recovery status and playback resolution.
- The library supports filename search, newest/name sorting, refresh, and folder
  selection, with explicit empty and folder access error states.

## Device review

Build with `./gradlew :app:assembleRelease` and install with
`adb install -r app/build/outputs/apk/release/app-release.apk`.
Open a Bayer take from the selected folder (or Android's file picker), since
Android scoped storage does not grant access to arbitrary `file://` paths.

Check portrait and landscape with the same take open: preview aspect ratio,
side-panel placement, scroll access to all sliders, category switching, curve
editing, timeline seeking, frame step, playback/pause, undo/redo, reset, saved
edits after reopening, and returning to the library. Also check a binned RGB take:
playback remains available and the UI explains that adjustments are unavailable.

## Library layout update

Landscape uses a 40 dp one-line full app title and 48 dp filter row. Search is a
200 dp × 34 dp field in that row; the grid starts directly below it and uses
larger 240 dp minimum cards. Portrait uses a single coherent title and a compact
40 dp search field. Folder selection and export jobs live in Library options.
Landscape hides the status bar and reserves horizontal safe-area padding.

The application label remains “Cinematic RAW Studio”. The launcher activity
requests “Cinematic\nRAW Studio” so launchers supporting two lines can display
the full name. A launcher enforcing one-line labels can still truncate it;
the application cannot override a launcher's text layout.
