---
layout: page
title: Sync Folder Privacy Policy
permalink: /privacy/
---

# Sync Folder Privacy Policy

Last updated: November 6, 2026

Sync Folder is published by Ofir Meguri. This policy explains how the
Sync Folder Android application handles information.

The Google Play edition syncs user-selected folders. The separately distributed
standalone edition also offers optional SMS backup. References to SMS in this
policy apply only to the standalone edition.

## Information the app accesses

Sync Folder accesses:

- The Google Account email address selected during sign-in.
- Files and folder names inside the local folder the user explicitly selects.
- Basic file metadata, such as file size and modification time.
- If SMS backup is explicitly enabled and SMS permission granted: all SMS available
  through Android, including text, phone numbers, dates, message status, thread IDs,
  SIM subscription IDs, and other message metadata.
- Google Drive files and folders created or used by Sync Folder, through the
  limited `drive.file` permission.

## How information is used

The app uses this information only to provide one-way synchronization from the
selected folder and optionally SMS backup files on the device to the user's Google Drive. If Auto Sync is
enabled, the app checks the selected folder and uploads files in the background
approximately every 15 minutes. When SMS backup is enabled, each automatic sync also
checks for SMS changes in a separate worker when a network connection is available.
A pending or running SMS backup continues without being restarted.

Files and SMS backup files are transmitted directly from the user's device to Google Drive over
HTTPS. Ofir Meguri does not operate a server that receives or stores the
user's files, SMS, file metadata, Google access tokens, or Google Account email
address.

## Sharing and third parties

The app sends selected files, enabled SMS backup files, and related metadata to Google Drive only at the
user's direction and for the synchronization feature. Google's handling of
that information is governed by the user's agreement with Google and Google's
privacy policy.

The app does not sell personal information. It does not include advertising or
analytics SDKs.

## Information stored on the device

The app stores the selected Google Account email address, selected folder
reference, synchronization preferences, last synchronization time, and a local
record of previously synchronized file metadata. This information remains on
the device. The app also stores the SMS backup preference, a random installation identifier
used to name the monthly Drive backup files, and, for each backed-up month, the
Drive file identifier and a count of the messages already uploaded so an
interrupted backup can resume. Each SMS is encoded in memory for upload; no
temporary SMS export is stored on disk.

## Data retention and deletion

Signing out removes the Google Account email address from the app and disables
automatic synchronization and SMS backup. Uninstalling the app removes its locally stored
settings and synchronization records. Files already uploaded to Google Drive
remain in the user's Drive until the user deletes them there.

Disabling SMS backup stops future SMS uploads but leaves Drive backups in place.
Messages are stored in one file per calendar month and updated on later syncs.
Messages deleted from the phone remain in Drive until the user deletes their backup
files. Files written by earlier versions of the app, including per-message files and
combined snapshots, are also retained. Android SMS permission can be revoked in app
settings.

Users can revoke Sync Folder's Google Account access from their Google Account
security settings.

## Security

Data sent to Google Drive is encrypted in transit using HTTPS. The app requests
only the limited Google Drive access needed for files created or used by the
app.

## Contact

For privacy questions or requests, contact:

Ofir Meguri
ofirmgr@gmail.com
Israel
