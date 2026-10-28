# MLV-App parity

Grading and raw-stage tools are MLV-App's own code (`third_party/mlv-app`,
vendored unchanged from MLV-App c4357a3 by `tools/sync_mlvapp.sh`), with two
bug-fix patches in `tools/patches/mlv-app/`. Parity is measured on a real
CRAW frame, host build (MLV-App's release flags) against the app's Android
arm64 build on the phone.

## Image processing — `tests/mlvapp-parity/run.sh`

| stage | host vs Android (of 65535) |
|---|---|
| exposure, white balance, contrast, saturation, vibrance, toning, gradation and Hue-vs curves, sharpening, CA, vignette, base pipeline | 0 (bit-exact) |
| clarity, shadows/highlights, RBF denoise (recursive bilateral filter) | ≤ 13 in 0.01–0.02 % of samples (Apple libm vs bionic) |
| all stages together | ≤ 61 (< ¼ of an 8-bit step) |

Every stage is independent of the thread count.

## Raw-stage tools — `tests/mlvapp-parity/run_rawtools.sh take.craw`

Vertical stripes, bad pixels (auto/force), pattern noise and chroma smoothing
(2×2/3×3/5×5) are each bit-exact host vs Android, and the Android build is
deterministic for every case (1 vs 8 threads, and between runs).

Open issue (reported by the test, not gating): combinations after vertical
stripe correction differ host vs Android by up to 1 (bad pixels, pattern
noise) and up to 21–25 code values (chroma smoothing) of 1023, and the host
reference itself changed between runs for these combinations. The Android
result does not vary. Root cause not yet isolated.

## MLV-App bugs found

- `0001` Sharpen read an uninitialised pointer on the last row (`p_row` set
  instead of `n_row`); the last row changed with the thread count. Patched.
- `0002` Bad pixels were interpolated in place in a parallel loop; neighbouring
  bad pixels made the result depend on thread timing. Patched (sequential).
- Chroma smoothing (c4357a3): the UI passes `CS_2x2/3x3/5x5 = 1/2/3` to
  `chroma_smooth()`, which handles 2/3/5 — in MLV-App "2×2" does nothing and
  "3×3"/"5×5" run 2×2/3×3 (the test confirms method 1 is a no-op). The app
  runs the method each label names.
- Apple-only chroma-smoothing path (GCD) reads scratch memory it never wrote
  (results change with `MallocPreScribble`); the app uses the OpenMP path.

## Deliberate differences

- As-shot white balance: MLV-App maps the camera WB mode to preset kelvins;
  the app finds the kelvin/tint at which MLV-App renders the take's
  AsShotNeutral grey (MLV-App's own processing is the reference).
- Map-file bad pixels, focus pixels and dual ISO (Canon-specific), dark frame
  and deflicker (disabled in MLV-App) are not offered.
