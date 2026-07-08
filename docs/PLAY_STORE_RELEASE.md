# Google Play release checklist

This checklist is specific to the current `com.ofir.syncfolder` app.

## 1. Lock the public identity

Before creating the app in Play Console, confirm:

- Store name: **Sync Folder**
- Package/application ID: `com.ofir.syncfolder`
- Publisher name: your public publisher name
- Default language
- Support email: your public support email
- Public privacy-policy URL

The package ID cannot be changed after the first Play release.

## 2. Create the Play Console app

Create a Google Play Developer account, complete identity/device verification,
and create a new app in Play Console. Select **App**, **Free**, and the correct
default language.

If the personal developer account was created after November 13, 2023, Google
currently requires a closed test with at least 12 opted-in testers for 14
continuous days before production access can be requested.

## 3. Create an upload key

Keep this keystore private and backed up:

```bash
keytool -genkeypair -v \
  -keystore "$HOME/sync-folder-upload.jks" \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and fill in the
real values. The real keystore and `keystore.properties` are excluded from Git.

## 4. Configure OAuth for the Play signing certificate

The existing setup script creates credentials for the local debug certificate.
Google Play installs builds signed with the **Play app signing certificate**,
which has a different SHA-1.

After enrolling in Play App Signing:

1. Open **Play Console > Setup > App integrity > App signing**.
2. Copy the SHA-1 of the **App signing key certificate**.
3. In the same Google Cloud project used by the app, create an Android OAuth
   client with:
   - Package: `com.ofir.syncfolder`
   - SHA-1: the Play app-signing SHA-1
4. Keep the Web OAuth client ID in `local.properties`:

```properties
server_client_id=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
privacy_policy_url=https://example.com/sync-folder/privacy/
terms_url=https://example.com/sync-folder/terms/
accessibility_url=https://example.com/sync-folder/accessibility/
```

Set the OAuth consent screen to **Production**, list the exact `drive.file`
scope, add the privacy-policy URL, app homepage/support details, and complete
Google's basic OAuth verification. `drive.file` is a non-sensitive scope, so
it does not require restricted-scope security assessment.

## 5. Host and link the legal documents

Customize and host these documents at stable public HTTPS URLs:

- `docs/privacy-policy.md`
- `docs/terms-of-use.md`
- `docs/accessibility-statement.md`

The privacy URL must be:

- Entered in `local.properties` as `privacy_policy_url`
- Added in **Play Console > Policy and programs > App content > Privacy policy**
- Added to the Google Cloud OAuth consent screen

The app's `terms_url` and `accessibility_url` must point to the other two
documents. Keep all documents consistent with the released app.

## 6. Suggested Play declarations

Verify these against the final binary before submission:

- Ads: **No**
- App access: Google sign-in is required; tell reviewers to use their own
  Google Account and authorize the limited Drive permission.
- Target audience: adults/general productivity audience; do not include
  children unless the app is redesigned for Families policy compliance.
- Content rating: productivity/utility, no objectionable content.
- Data deletion: users can sign out/uninstall to remove local app data, revoke
  Google access in Google Account settings, and delete uploaded files in Drive.

### Data safety starting point

The app sends data off-device, so it should not answer “no data collected”
without reviewing Google's current definitions.

- Personal info: the selected email address is accessed and stored locally but
  is not sent by the app to a developer-operated server. Confirm the current
  Google Sign-In SDK disclosure before deciding whether to declare it.
- Files and docs: user-selected files and file names are transmitted off-device
  to the user's Google Drive for app functionality. This is likely “collected”
  under Google's off-device definition.
- Data is encrypted in transit.
- Data is not sold and is not used for advertising or analytics.
- Transfer to Google Drive is user-initiated/expected, which may qualify for
  Google's user-initiated sharing exception. Confirm the exact “collection”
  and “sharing” answers in the current Play Console questionnaire.

## 7. Store listing assets

Prepare:

- App icon: 512 x 512 PNG
- Feature graphic: 1024 x 500 PNG or JPEG
- At least 2 phone screenshots; 4-8 is better
- Short description, up to 80 characters
- Full description, up to 4,000 characters
- Support email: your public support email
- Website is strongly recommended

Suggested copy:

**Short description**

> Automatically upload new and changed files from a folder to Google Drive.

**Full description**

> Sync Folder keeps a folder on your Android device backed up to Google Drive.
> Choose any folder with Android's system picker, then upload new or changed
> files on demand or automatically in the background.
>
> Features:
> - One-way upload to a matching folder in Google Drive
> - Manual sync or automatic sync approximately every 15 minutes
> - Optional file-extension and date filters
> - Skips unchanged files
> - Limited Google Drive access using the `drive.file` permission
> - No ads, analytics, or developer-operated file server
> - Clear consent before the first upload and before background synchronization
>
> Sync Folder does not delete local files or mirror Drive deletions back to the
> device. It is a convenience tool, not a replacement for an independent
> backup. Verify important files in Google Drive.

## 8. Accessibility checks

Before release, manually test:

- TalkBack traversal, labels, dialogs, status changes, and error announcements
- Font size and display size at the largest practical system settings
- Portrait and landscape layouts without clipped controls
- Color contrast in light and dark mode
- External Google Sign-In and system folder-picker flows
- Keyboard or switch-access navigation where available

Do not describe the app as certified compliant unless an independent assessor
has actually certified it.

## 9. Build and verify the upload bundle

```bash
./gradlew validatePlayRelease lintRelease testReleaseUnitTest bundleRelease
jarsigner -verify -verbose -certs \
  app/build/outputs/bundle/release/app-release.aab
```

Upload:

`app/build/outputs/bundle/release/app-release.aab`

Start with Internal testing, then Closed testing if required. Run the Play
pre-launch report and fix crashes, sign-in failures, accessibility issues, and
background-sync failures before requesting production.
