# Legacy RCD history removal — 2026-11-10

The active source had already replaced the old renderer with coherent MHC and
RapidRAW in the local replacement commit. This cleanup removes the retired
implementation from every advertised branch/tag history and pushes the current
source over SSH. It does not recreate the removed algorithm.

## Scope

Remove historical `third_party/rawlens-gpu/`, its Kotlin bindings under
`app/src/main/java/com/matthew/rawlens/`, the obsolete `assets/shaders/` payload,
`GpuRenderer.kt`, `tools/sync_rawlens_gpu.sh`, and the native CMake integration.
The entire obsolete renderer closure is removed because it was already retired
from the current source. Current `assets/mhc/`, the pinned MHC reference and
RapidRAW remain. Historical intermediate commits are not guaranteed buildable
after removal; the current cleaned source is built and inspected separately.

The original importing commit description is corrected to identify the removal
and retire its implementation-specific performance claims. Historical reports
may mention the retired algorithm for audit purposes; their instructions and
measurements are not current code or current acceptance. Stale handoff directions
were replaced and current project documentation now names MHC/RapidRAW.

## Backup and distribution

A private recovery bundle and original 0.1.0 release APK were preserved outside
the repository. They are not published, build inputs or approved implementation
references. The public 0.1.0 APK was confirmed to contain the removed shader
payload and its release was converted to a private draft, preserving its record
and asset. Do not republish that APK. GitHub Actions reported no artifacts.

An isolated mirror rehearsal must pass a complete reachable-blob/path scan,
then the same rewrite is applied locally. Verify that the current source tree
is identical before/after filtering. Use explicit expected-old-ref leases for
an atomic SSH push of main and tag 0.1.0; preserve unrelated remote changes by
failing the push if either ref moved.

## Validation

The current release APK assembly and host deviceTest unit suite passed. APK
inspection found no retired shader paths, native library or checked implementation
signatures in shaders/native libraries/DEX. Exact test counts and APK hash are in
`benchmarks/2026-11-10/rcd-removal/build-verification.json`.
No device/runtime/performance acceptance was run or implied by this cleanup.
The current source's recorded validation limitations remain in `rapidraw-engine.md`.
Production signing/upload is separate from source cleanup.

## GitHub-hosted historical copies

A force push removes the implementation from reachable advertised refs. It does
not guarantee purging old commit/blob caches, clones, forks, previously downloaded
APKs or user attachments. Verify those separately and prepare a support request
for any GitHub-hosted old commit views still served. No ownership determination
or legal-clearance claim is made. No issue/support message is automatically sent.
