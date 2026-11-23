# Editor UI

The library opens a single take for grading or opens **Projects** for sequence editing. Both previews share the RapidRAW Vulkan grade controls.

## Grade a take or clip

- In the single-take editor, select a grading section in the adjustment panel. In a project, select a video clip and open **Grade** in the inspector.
- Use Basic, Curves, Color, Details, Effects and Masks to adjust the grade. The preview shows the current grade on the displayed frame.
- Tap the **Video scopes** icon at the top right of the preview or sequence monitor, then choose **RGB histogram**, **Luma waveform**, or **Off**. These scopes sample the displayed image at up to four updates per second. Their values describe the output after grading and the display transform.
- Double-tap a grading slider or its numeric readout to restore its default. Tap the readout once to enter an exact value. Temperature and tint defaults apply zero offset to as-shot white balance.
- Saved single-take grades follow their source into projects; project grades are stored per clip. The transport supports play/pause, scrubbing and frame stepping.

## Device review

Build with `./gradlew :app:assembleRelease`. On a test device, use a matching signing key or a separate application ID. Open footage from the selected library folder or Android's file picker.

Exercise both orientations and both monitors with Bayer and recorded RGB takes. Check scope selection, scope updates on seeks and grade changes, numeric entry, reset of nonzero defaults, curve editing, and saved grades after reopening.

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
