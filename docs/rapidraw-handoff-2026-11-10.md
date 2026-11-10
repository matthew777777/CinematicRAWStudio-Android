# Current renderer handoff — 2026-11-10

The earlier implementation checkpoint is superseded. The current tree uses
`RapidMhcSource` with the pinned coherent MHC reference, `RapidComputeSession`
and the RapidRAW host for editor, project monitoring and export. The old
RawLens renderer, wrappers, native backend, shader payloads and sync script
have been removed. Do not restore them from old branches, objects or backups.

See `rapidraw-engine.md`, `../README.md`, and `rcd-history-removal-2026-11-10.md`
for current architecture, validation limits and the history cleanup record.
Continue from current source and independently verified public references.
