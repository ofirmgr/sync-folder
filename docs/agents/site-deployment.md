# Public website deployment

## Authoritative content and build

The existing Markdown files in `docs/` remain the content source for both GitHub Pages
and the Vercel site. `scripts/build-site.mjs` strips Jekyll frontmatter, uses each
document's `permalink`, and converts local Markdown links into public website routes.
The homepage is `docs/index.md`; the legal documents keep their current text and dates.

| Source | Route on the Vercel site |
| --- | --- |
| `docs/index.md` | `/` |
| `docs/privacy-policy.md` | `/privacy/` |
| `docs/terms-of-use.md` | `/terms/` |
| `docs/accessibility-statement.md` | `/accessibility/` |

Use Node.js 24, then `npm ci` and `npm run build:site`. The ignored `site-dist/`
directory contains only public HTML, CSS, and a standalone `vercel.json`.
The artifact's config explicitly disables installation/build and serves `.` so it
cannot inherit source-build commands or `site-dist` output settings from the project.
Preview with `python3 -m http.server 4173 --bind 127.0.0.1 --directory site-dist`.
The root `vercel.json` also supports a future Git-integrated build.

## Migration status and deployment boundary

Deployed with the owner's approval on 2026-11-10 (Asia/Jerusalem).
Isolated Vercel project: `sync-folder-site`, scope `ofirmgrs-projects`.
Hostname: `https://sync-folder.ofir.fun/`.
Production deployment: `dpl_G8BkWw9wgS7NPWXt1XMhdKEL1MW3` (READY).
Deploy only the generated `site-dist/`
artifact if using the CLI; never upload the Android checkout, `local.properties`,
keystore material, environment files, or private device data.

Keep `ofirmgr.github.io` (the Vercel project serving `ofir.fun`/`www.ofir.fun`),
`rental-portal`, both domains' existing records, and all mail configuration intact.
Only the dedicated hostname should be assigned to the new project. Domain-level
DNS verification of `ofir.fun` is already complete; preserve its Google TXT records.

All four HTTPS routes and the stylesheet returned 200 anonymously, without redirects,
and matched the local public artifact byte for byte. All eight existing `ofir.fun`
DNS records were unchanged after hostname assignment. Existing `www.ofir.fun` and
`www.dira360.co.il` returned 200 and retained their original project assignments.

The following branding values were saved in `sync-folder-742823` with approval:

- Home: `https://sync-folder.ofir.fun/`
- Privacy: `https://sync-folder.ofir.fun/privacy/`
- Terms: `https://sync-folder.ofir.fun/terms/`
- Authorized domain: `ofir.fun`

The previous authorized domain `ofirmgr.github.io` was retained. Google's save dialog
confirmed that saving updates the existing pending verification request, and the UI
reported `Branding changes saved!`. Verification Center still showed
`Your branding is currently under review.`; homepage, privacy policy, and branding
guidelines were in progress. A separate new submission was not made: the existing
request was updated. The owner approved the reviewed reply, and Gmail confirmed it
was sent in the existing OAuth verification thread on 2026-11-10 (Asia/Jerusalem).
Domain ownership, successful saving, and sending the reply are not brand approval.

Future deployments and branding changes still require explicit approval. Keep
production public: anonymous checks must not be replaced with authenticated
`vercel curl` checks that can bypass Deployment Protection.

## Android and existing links

`app/build.gradle.kts` still uses the working GitHub Pages legal URLs. Updating Android
defaults was not part of this website/branding deployment. Existing installed apps and published Play links keep working
through GitHub Pages; retain those pages and their ownership verification files.
Changing the Android defaults, Play listing, or installing a build is a later step.
