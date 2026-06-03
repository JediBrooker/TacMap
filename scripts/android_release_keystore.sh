#!/usr/bin/env bash
#
# Generate the Android release (upload) keystore for TacticalMaps and print
# the SHA-1 / SHA-256 fingerprints you need for the Google Maps API key.
#
# Run ONCE. The resulting .jks is the permanent identity of the app on the
# Play Store — if you lose it you cannot ship updates under the same listing
# (unless enrolled in Play App Signing, which is strongly recommended; see
# android/PLAY_STORE_PREP.md). Back it up somewhere safe and OFFLINE.
#
# keytool prompts for the passwords interactively — they are deliberately NOT
# passed as CLI args so they never land in your shell history. The output
# directory (android/keystore/) is gitignored, so the key is never committed.
#
# Usage:
#   scripts/android_release_keystore.sh [output.jks] [alias]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$REPO_ROOT/android/keystore/release.jks}"
ALIAS="${2:-tacticalmaps}"

KEYTOOL="$(command -v keytool || true)"
if [ -z "$KEYTOOL" ]; then
  # Fall back to the Homebrew OpenJDK 17 that the project builds against.
  if [ -x /opt/homebrew/opt/openjdk@17/bin/keytool ]; then
    KEYTOOL=/opt/homebrew/opt/openjdk@17/bin/keytool
  else
    echo "ERROR: keytool not found. Install a JDK (e.g. brew install openjdk@17)." >&2
    exit 1
  fi
fi

if [ -e "$OUT" ]; then
  echo "ERROR: $OUT already exists. Refusing to overwrite a signing key." >&2
  echo "       Delete it manually only if you are certain it is unused." >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

echo "Creating release keystore at: $OUT"
echo "Key alias: $ALIAS"
echo
echo "You will be asked for:"
echo "  1. a keystore password (store password)"
echo "  2. your name / org details (the certificate 'distinguished name')"
echo "  3. a key password (press RETURN to reuse the keystore password)"
echo

"$KEYTOOL" -genkeypair \
  -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

echo
echo "==================================================================="
echo "Keystore created. Fingerprints (needed to restrict the Maps key):"
echo "==================================================================="
"$KEYTOOL" -list -v -keystore "$OUT" -alias "$ALIAS" \
  | grep -E "SHA1:|SHA256:" || true

cat <<EOF

-------------------------------------------------------------------
NEXT STEPS
-------------------------------------------------------------------
1) Tell Gradle where the key is. Add to a PRIVATE, uncommitted file
   ~/.gradle/gradle.properties (NOT the repo):

     TACTICALMAPS_RELEASE_STORE_FILE=$OUT
     TACTICALMAPS_RELEASE_STORE_PASSWORD=<keystore password>
     TACTICALMAPS_RELEASE_KEY_ALIAS=$ALIAS
     TACTICALMAPS_RELEASE_KEY_PASSWORD=<key password>

2) Build the signed App Bundle for Play:

     cd android && ./gradlew :app:bundleRelease
     # output: android/app/build/outputs/bundle/release/app-release.aab

3) Restrict your PRODUCTION Google Maps API key (Google Cloud Console →
   APIs & Services → Credentials) to:
     - Application restriction: Android apps
     - Package name:  com.tacticalmaps
     - SHA-1:         <the SHA1 printed above>
   If you enable Play App Signing (recommended), ALSO add the SHA-1 that
   Google shows under Play Console → Test and release → App integrity →
   "App signing key certificate". Otherwise the map renders blank for
   users who install from the Play Store.

4) BACK UP $OUT offline. Losing it means you can no longer update the app
   under the same Play listing (unless Play App Signing is enabled).
-------------------------------------------------------------------
EOF
