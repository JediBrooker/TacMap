#!/usr/bin/env bash
#
# Generate the Android release upload keystore for TacMap and print certificate
# fingerprints for release records and Play App Signing verification.
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
# Key material and any temporary files created by keytool must never inherit a
# permissive interactive-shell umask.
umask 077

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_INPUT="${1:-$REPO_ROOT/android/keystore/release.jks}"
ALIAS="${2:-tacticalmaps}"

case "$OUT_INPUT" in
  /*) OUT_ABS="$OUT_INPUT" ;;
  *) OUT_ABS="$PWD/$OUT_INPUT" ;;
esac
case "$OUT_ABS" in
  */../*|*/..|*/./*|*/.)
    echo "ERROR: output path must not contain . or .. components: $OUT_INPUT" >&2
    exit 1
    ;;
esac
OUT_NAME="$(basename "$OUT_ABS")"
OUT_DIR_INPUT="$(dirname "$OUT_ABS")"
if [ -z "$OUT_NAME" ] || [ "$OUT_NAME" = "." ] || [ "$OUT_NAME" = ".." ]; then
  echo "ERROR: invalid signing-key output filename: $OUT_INPUT" >&2
  exit 1
fi

stat_mode() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

stat_owner_uid() {
  stat -f '%u' "$1" 2>/dev/null || stat -c '%u' "$1"
}

reject_symlink_components() {
  local candidate="$1"
  while :; do
    if [ -L "$candidate" ]; then
      echo "ERROR: refusing a signing-key path containing a symlink: $candidate" >&2
      exit 1
    fi
    [ "$candidate" = "/" ] && break
    candidate="$(dirname "$candidate")"
  done
}

require_safe_ancestors() {
  local candidate="$1" mode
  while :; do
    [ -d "$candidate" ] || {
      echo "ERROR: signing-key ancestor is not a directory: $candidate" >&2
      exit 1
    }
    mode="$(stat_mode "$candidate")"
    if (( (8#$mode & 0022) != 0 )); then
      echo "ERROR: signing-key ancestor is group/world writable: $candidate (mode $mode)" >&2
      exit 1
    fi
    [ "$candidate" = "/" ] && break
    candidate="$(dirname "$candidate")"
  done
}

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

if [ -e "$OUT_ABS" ] || [ -L "$OUT_ABS" ]; then
  echo "ERROR: $OUT_INPUT already exists or is a symlink. Refusing to overwrite a signing key." >&2
  echo "       Delete it manually only if you are certain it is unused." >&2
  exit 1
fi

reject_symlink_components "$OUT_DIR_INPUT"

# Validate the nearest existing ancestor before creating anything. This keeps a
# custom path out of shared directories such as /tmp, where another account
# could pre-place or swap a signing-key target. With umask 077, mkdir then makes
# every new component private without changing permissions on existing paths.
EXISTING_ANCESTOR="$OUT_DIR_INPUT"
while [ ! -e "$EXISTING_ANCESTOR" ] && [ ! -L "$EXISTING_ANCESTOR" ]; do
  PARENT="$(dirname "$EXISTING_ANCESTOR")"
  [ "$PARENT" != "$EXISTING_ANCESTOR" ] || break
  EXISTING_ANCESTOR="$PARENT"
done
reject_symlink_components "$EXISTING_ANCESTOR"
[ -d "$EXISTING_ANCESTOR" ] || {
  echo "ERROR: output ancestor is not a directory: $EXISTING_ANCESTOR" >&2
  exit 1
}
EXISTING_ANCESTOR="$(cd "$EXISTING_ANCESTOR" && pwd -P)"
require_safe_ancestors "$EXISTING_ANCESTOR"

if [ ! -d "$OUT_DIR_INPUT" ]; then
  mkdir -p "$OUT_DIR_INPUT"
fi
reject_symlink_components "$OUT_DIR_INPUT"
OUT_DIR="$(cd "$OUT_DIR_INPUT" && pwd -P)"
require_safe_ancestors "$OUT_DIR"

OUT_DIR_MODE="$(stat_mode "$OUT_DIR")"
OUT_DIR_OWNER="$(stat_owner_uid "$OUT_DIR")"
if [ "$OUT_DIR_OWNER" != "$(id -u)" ] || (( (8#$OUT_DIR_MODE & 0077) != 0 )); then
  echo "ERROR: output directory must be owned by the current user and private: $OUT_DIR (mode $OUT_DIR_MODE)" >&2
  exit 1
fi
if [ ! -w "$OUT_DIR" ]; then
  echo "ERROR: output directory is not writable: $OUT_DIR" >&2
  exit 1
fi

OUT="$OUT_DIR/$OUT_NAME"
if [ -e "$OUT" ] || [ -L "$OUT" ]; then
  echo "ERROR: $OUT already exists or is a symlink. Refusing to overwrite a signing key." >&2
  exit 1
fi

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

chmod 600 "$OUT"

echo
echo "==================================================================="
echo "Keystore created. Upload-certificate fingerprints:"
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

3) In Play Console, create or select application id com.tacmap and enable
   Play App Signing. Record both this upload-certificate fingerprint and the
   separate app-signing certificate fingerprint shown by Play. TacMap does not
   use the Google Maps SDK and does not need a Maps API-key restriction.

4) BACK UP $OUT offline. Losing it means you can no longer upload updates
   under the same Play listing (unless Play App Signing is enabled).
-------------------------------------------------------------------
EOF
