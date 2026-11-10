# Project memory

- App performs one-way upload from user-selected SAF folder to Drive. Persisted URI access and
  permission validation are part of correctness; do not replace them with raw filesystem paths.
- Folder enumeration prefers bulk `DocumentsContract` queries with `DocumentFile` fallback. Preserve
  provider compatibility and inspect device logs before changing traversal.
- Drive auth should attempt silent token refresh, then expose reauthentication state. Never weaken
  permission/auth handling to make a sync pass.
- Background scheduling must re-arm future work without replacing/cancelling worker that cold-started
  app. `ExistingWorkPolicy.REPLACE` on active unique work can cause `WorkerStoppedException`.
- Auto Sync is scheduled by WorkManager, with alarm and boot recovery plus optional MediaStore triggers.
  These background entry points must not start a `dataSync` foreground service. Only user-initiated
  transfers promote their workers. See `SyncWorker.kt`, `SyncBootReceiver.kt`, and `SyncAlarmReceiver.kt`
  (2026-11-06). Inspect current diff and device logs before changes.
- Interval values, Xiaomi state, entry counts, test outcomes, and device permissions are snapshots,
  not project memory. Verify live.

- SMS backup (2026-09-22, `SmsSyncEngine.kt`, `SmsBackupWriter.kt`, `SmsSyncWorker.kt`):
  opt-in `sms_sync` plus READ_SMS; SMS-only work is supported. One NDJSON file per
  calendar month, `Sync Folder SMS/sms-<installation UUID>-<YYYY-MM>.ndjson`
  (`schemaVersion: 3`): a header line then one `message` object per line, bucketed by
  the device's local zone. Deleted phone messages and legacy files remain in Drive;
  no automatic deletion or restore. Folder filters and Room records do not apply.
  Runtime disclosure, privacy policy, terms, README, and the standalone manifest
  are related consumers.

- In the standalone edition, each automatic `SyncWorker.doWork` triggers enabled SMS backup through
  `SmsSyncWorker.enqueueAutoSync` before folder validation or upload (2026-09-22,
  `SyncWorker.kt`, `SmsSyncWorker.kt`). SMS uses separate unique work named
  `sms_sync_chain`, with immediate eligibility and `KEEP`; it has no separate timer
  or self-rearm. SMS-only setups still use the shared automatic sync schedule.
  Schema 2 wrote one Drive file per message and ran
  ahead of the folder upload inside `SyncWorker`; a 10k-message backfill at two Drive
  API calls each meant folder sync never got a turn, and the 15-minute chain cancelled
  the run long before it finished, restarting it from message 1 every time. Keep the
  workers independent, and enqueue SMS with `KEEP` so a pending or running backfill
  is not cancelled. Only the folder upload requires a selected folder.

- SMS resume state is per month, not per message (`Prefs.smsMonthState`, 2026-09-22).
  A cheap `(_ID, DATE)` scan fingerprints each month by count plus highest row ID; only
  months whose fingerprint changed are rebuilt and re-uploaded, and state is committed
  after each month so a cancelled run keeps finished months. This is the SMS equivalent
  of folder sync's `FileRecord` table. `DriveClient.uploadFile` takes an optional
  `knownFileId` to skip the name lookup, so a month costs one request, not two.

- SMS additions preserve existing preference keys and folder-sync consent version 1.
  SMS consent is separate and defaults off; do not bump the shared terms gate solely
  for optional SMS, because every background entry point uses it and the view model
  disables Auto Sync for older consent versions (2026-09-20, `Prefs.kt`, `SyncViewModel.kt`).

- The `standalone` flavor owns the SMS manifest permission, UI availability, and worker implementation.
  `play` shares the application ID and preference schema but builds without the SMS worker or permission
  (`app/build.gradle.kts`, `app/src/standalone`, `app/src/play`, 2026-11-06).

- OAuth branding ownership (2026-11-07, Google Search Console and Google Auth Platform):
  verify the root URL-prefix property `https://ofirmgr.github.io/` as well as the app path.
  Its HTML verification file is served by `ofirmgr/ofirmgr.github.io` from `main` at
  `/google8da5631e8cb88aec.html` (commit `b3f8be4`). Keep that file published to retain ownership.
  The copy in this repo's `docs/` verifies `/sync-folder/` only. Cloud may require 24 hours
  after ownership verification before a branding retry recognizes it; check live status.

- Domain ownership (2026-11-10, live Google Search Console, GCP IAM, and Vercel DNS):
  verified the Domain property `sc-domain:ofir.fun` through DNS using a Google account
  confirmed as `roles/owner` on `sync-folder-742823`. Search Console showed `Ownership verified`.
  Added only apex TXT record `rec_eca54418804d1b72e705673e` in Vercel scope
  `ofirmgrs-projects`; all seven pre-existing DNS records were preserved. Keep the verification
  TXT published. Website hosting, OAuth branding URLs/authorized domains, and the branding
  submission were not changed by this verification; domain ownership is not brand approval.

- Public website (2026-11-10, approved Vercel deployment, anonymous HTTPS checks, and
  Google Cloud UI): `npm run build:site` renders the existing `docs/` Markdown into
  ignored `site-dist/`. The separate `sync-folder-site` project serves
  `https://sync-folder.ofir.fun/`; all four routes and CSS returned 200 and matched the
  artifact. Existing DNS records, the personal site, and Dira360 were preserved.
  Google saved the new homepage/privacy/terms URLs and added `ofir.fun` while retaining
  `ofirmgr.github.io`; saving updated the existing pending request. Branding remains
  under review. The reviewed reply was sent with explicit owner approval on the same
  date. Source mapping, deployment ID, and verification status are in
  `docs/agents/site-deployment.md`. Android defaults and published Play links still use
  the working GitHub Pages site; an Android release was outside this deployment.
