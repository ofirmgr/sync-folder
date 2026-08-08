# Project memory

- App performs one-way upload from user-selected SAF folder to Drive. Persisted URI access and
  permission validation are part of correctness; do not replace them with raw filesystem paths.
- Folder enumeration prefers bulk `DocumentsContract` queries with `DocumentFile` fallback. Preserve
  provider compatibility and inspect device logs before changing traversal.
- Drive auth should attempt silent token refresh, then expose reauthentication state. Never weaken
  permission/auth handling to make a sync pass.
- Background scheduling must re-arm future work without replacing/cancelling worker that cold-started
  app. `ExistingWorkPolicy.REPLACE` on active unique work can cause `WorkerStoppedException`.
- Scheduling architecture can change across WorkManager, alarms, boot recovery, foreground service,
  and MediaStore triggers. Inspect current diff and device logs before edits or architecture claims;
  do not canonize uncommitted behavior in this document.
- Interval values, Xiaomi state, entry counts, test outcomes, and device permissions are snapshots,
  not project memory. Verify live.
