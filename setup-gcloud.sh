#!/bin/bash
# Sets up Google Cloud project + Drive API + OAuth credentials for sync-folder.
# Automates: project creation, API enablement, SHA-1 extraction, local.properties write.
# Opens browser for the 2 steps that have no public CLI API (consent screen + OAuth clients).
set -e

PROJECT_ID="${1:-sync-folder-app}"
SUPPORT_EMAIL="${2:-support@example.com}"
PACKAGE="com.ofir.syncfolder"
KEYSTORE="$HOME/.android/debug.keystore"
LOCAL_PROPS="$(dirname "$0")/local.properties"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}▶ $*${NC}"; }
warn()  { echo -e "${YELLOW}⚠ $*${NC}"; }
error() { echo -e "${RED}✗ $*${NC}"; exit 1; }
step()  { echo -e "\n${YELLOW}[$1/6]${NC} $2"; }

# ── Prerequisites ──────────────────────────────────────────────────────────────
command -v gcloud &>/dev/null || error "gcloud not installed. Run: brew install --cask google-cloud-sdk"
command -v keytool &>/dev/null || error "keytool not found. Install JDK 17: brew install --cask temurin@17"

# ── Step 1: Authenticate ───────────────────────────────────────────────────────
step 1 "Authenticate with Google"
if ! gcloud auth print-access-token &>/dev/null; then
  gcloud auth login
fi
info "Authenticated as $(gcloud config get-value account)"

# ── Step 2: Create / verify project ───────────────────────────────────────────
step 2 "Create GCP project: $PROJECT_ID"
if gcloud projects describe "$PROJECT_ID" &>/dev/null; then
  info "Project '$PROJECT_ID' already exists — using it."
else
  gcloud projects create "$PROJECT_ID" --name="Sync Folder"
  info "Project created."
fi
gcloud config set project "$PROJECT_ID"
warn "Billing must be enabled for Drive API to work."
warn "If not enabled: https://console.cloud.google.com/billing/linkedaccount?project=$PROJECT_ID"

# ── Step 3: Enable Drive API ───────────────────────────────────────────────────
step 3 "Enable Google Drive API"
gcloud services enable drive.googleapis.com --project "$PROJECT_ID"
info "Google Drive API enabled."

# ── Step 4: Get debug keystore SHA-1 ──────────────────────────────────────────
step 4 "Extract debug keystore SHA-1"
if [ ! -f "$KEYSTORE" ]; then
  warn "Debug keystore not found at $KEYSTORE."
  warn "It is created automatically when you build in Android Studio."
  warn "Or generate it manually:"
  warn "  keytool -genkey -v -keystore $KEYSTORE -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US'"
  read -p "Press Enter once the keystore exists, then we'll continue... " _
fi
SHA1=$(keytool -list -v \
  -keystore "$KEYSTORE" \
  -alias androiddebugkey \
  -storepass android \
  -keypass android 2>/dev/null | grep "SHA1:" | awk '{print $2}')
[ -z "$SHA1" ] && error "Could not read SHA-1 from keystore."
info "SHA-1: $SHA1"

# ── Step 5: Browser — OAuth consent screen + clients ──────────────────────────
# Google has no public REST API for creating Android/Web OAuth clients, so the
# Console is required for these two sub-steps.
step 5 "Create OAuth credentials in Google Cloud Console"

BROWSER_OPEN="open"
command -v xdg-open &>/dev/null && BROWSER_OPEN="xdg-open"

echo ""
echo "  Two things to do in the browser:"
echo ""
echo "  5a) OAuth consent screen"
echo "      • User type: External"
echo "      • App name: Sync Folder"
echo "      • Support email: $SUPPORT_EMAIL"
echo "      • Add test user: $SUPPORT_EMAIL"
echo "      • Add scope: https://www.googleapis.com/auth/drive.file"
echo ""
echo "  5b) Create credentials (do this TWICE):"
echo "      • Create OAuth Client ID → Android"
echo "          Package name : $PACKAGE"
echo "          SHA-1        : $SHA1"
echo "      • Create OAuth Client ID → Web application"
echo "          (no redirect URIs needed)"
echo "          Copy the 'Client ID' — you'll paste it below."
echo ""

read -p "  Press Enter to open the Credentials page... " _
$BROWSER_OPEN "https://console.cloud.google.com/apis/credentials?project=$PROJECT_ID" 2>/dev/null \
  || echo "  Open manually: https://console.cloud.google.com/apis/credentials?project=$PROJECT_ID"

echo ""
read -p "  Paste the Web application Client ID (ends in .apps.googleusercontent.com): " WEB_CLIENT_ID
[ -z "$WEB_CLIENT_ID" ] && error "Client ID cannot be empty."

# ── Step 6: Write to local.properties ─────────────────────────────────────────
step 6 "Update local.properties"
if grep -q "^server_client_id=" "$LOCAL_PROPS" 2>/dev/null; then
  # macOS sed requires '' after -i; GNU sed requires just -i
  sed -i '' "s|^server_client_id=.*|server_client_id=$WEB_CLIENT_ID|" "$LOCAL_PROPS" 2>/dev/null \
    || sed -i  "s|^server_client_id=.*|server_client_id=$WEB_CLIENT_ID|" "$LOCAL_PROPS"
else
  echo "server_client_id=$WEB_CLIENT_ID" >> "$LOCAL_PROPS"
fi
info "local.properties updated."

echo ""
echo -e "${GREEN}✓ Setup complete!${NC}"
echo ""
echo "  Next steps:"
echo "    ./bootstrap.sh          # download Gradle wrapper (if not using Android Studio)"
echo "    ./gradlew assembleDebug # build APK"
echo "    ./gradlew installDebug  # install on connected device/emulator"
