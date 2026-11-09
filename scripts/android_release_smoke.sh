#!/usr/bin/env bash
#
# Release-build launch smoke test for the Android app.
#
# Why this exists: 3.1.0 (build 81) shipped to Play and crashed on every
# device at launch with a VerifyError. R8 had emitted bytecode that ART's
# verifier rejected. Every check we had passed because CI and local dev only
# ever ran DEBUG builds, which are not minified. This script takes the actual
# minified release APK, signs it with the debug keystore so it installs, and
# checks that the app process is still alive and logcat is clean ~10s after a
# cold start. That is the cheapest possible test that would have caught it.
#
# Works locally against any adb-visible device/emulator and in CI. It does
# not look at pixels on purpose (headless emulator screencaps are black), it
# goes by process liveness + logcat.
#
# Usage:
#   scripts/android_release_smoke.sh [path/to/app-release-unsigned.apk]
#
# With no argument it runs :app:assembleRelease first and uses that output.
# Env knobs:
#   SMOKE_SERIAL     adb serial to target (default: the only attached device)
#   SMOKE_WAIT_SECS  how long to leave the app running before checking (default 10)
#   SMOKE_SKIP_BUILD set to 1 to skip the gradle build when no apk path given
#   DEBUG_KEYSTORE   keystore used to sign (default ~/.android/debug.keystore,
#                    created with the stock android/android creds if missing)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="com.tacmap"
ACTIVITY="com.tacmap/.app.MainActivity"
WAIT_SECS="${SMOKE_WAIT_SECS:-10}"
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
WORK_DIR="${SMOKE_WORK_DIR:-$REPO_ROOT/android/app/build/outputs/apk/release/smoke}"

log()  { printf '[smoke] %s\n' "$*"; }
fail() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# ---- locate tooling ---------------------------------------------------------

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/local/lib/android/sdk; do
    if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || fail "cannot find the Android SDK, set ANDROID_HOME"

ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || fail "adb not found"

# newest build-tools that actually has apksigner, zipalign lives next to it
BUILD_TOOLS=""
for dir in $(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V -r); do
  if [ -x "$dir/apksigner" ] && [ -x "$dir/zipalign" ]; then
    BUILD_TOOLS="${dir%/}"
    break
  fi
done
[ -n "$BUILD_TOOLS" ] || fail "no build-tools with apksigner + zipalign under $SDK/build-tools"
log "using build-tools $(basename "$BUILD_TOOLS")"

if [ -n "${SMOKE_SERIAL:-}" ]; then
  ADB="$ADB -s $SMOKE_SERIAL"
fi

# ---- pick the device --------------------------------------------------------

DEVICE_COUNT="$($ADB devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
if [ "$DEVICE_COUNT" -eq 0 ]; then
  fail "no adb device in 'device' state. Boot an emulator or plug a phone in."
fi
if [ "$DEVICE_COUNT" -gt 1 ] && [ -z "${SMOKE_SERIAL:-}" ]; then
  $ADB devices >&2
  fail "more than one device attached, pick one with SMOKE_SERIAL=<serial>"
fi
log "device: $($ADB shell getprop ro.product.model | tr -d '\r') API $($ADB shell getprop ro.build.version.sdk | tr -d '\r')"

# ---- get the apk ------------------------------------------------------------

UNSIGNED_APK="${1:-}"
if [ -z "$UNSIGNED_APK" ]; then
  UNSIGNED_APK="$REPO_ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  if [ "${SMOKE_SKIP_BUILD:-0}" != "1" ]; then
    log "building :app:assembleRelease (minified, unsigned)"
    (cd "$REPO_ROOT/android" && ./gradlew --no-daemon -q :app:assembleRelease)
  fi
fi
[ -f "$UNSIGNED_APK" ] || fail "apk not found: $UNSIGNED_APK"
log "apk: $UNSIGNED_APK"

# ---- sign with the debug key so it installs ---------------------------------

if [ ! -f "$KEYSTORE" ]; then
  # fresh CI runner has never built a debug apk so there is no debug keystore
  # yet. make the stock one, same creds the android gradle plugin would use.
  log "no debug keystore at $KEYSTORE, creating one"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

mkdir -p "$WORK_DIR"
ALIGNED_APK="$WORK_DIR/app-release-aligned.apk"
SIGNED_APK="$WORK_DIR/app-release-debugsigned.apk"
rm -f "$ALIGNED_APK" "$SIGNED_APK"

"$BUILD_TOOLS/zipalign" -p -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$SIGNED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" verify "$SIGNED_APK"
log "signed: $SIGNED_APK"

# ---- install ----------------------------------------------------------------

# the debug build from connectedDebugAndroidTest has the same package name
# but a different (or same, doesnt matter) cert and we want a clean slate
# anyway. uninstall fails harmlessly when nothing is installed.
$ADB uninstall "$PACKAGE" >/dev/null 2>&1 || true
$ADB uninstall "$PACKAGE.test" >/dev/null 2>&1 || true
log "installing"
if ! INSTALL_OUT="$($ADB install -r "$SIGNED_APK" 2>&1)"; then
  printf '%s\n' "$INSTALL_OUT" >&2
  fail "adb install failed"
fi

# location permission so the runtime prompt never gets in the way. not
# fatal if the grant is refused (some images are picky), the app handles
# denied location fine, we just dont want a dialog eating the first frame.
$ADB shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
$ADB shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true

# ---- launch and watch -------------------------------------------------------

$ADB logcat -c || true
log "starting $ACTIVITY"
START_OUT="$($ADB shell am start -W -n "$ACTIVITY" 2>&1 | tr -d '\r')"
printf '%s\n' "$START_OUT" | sed 's/^/[smoke]   /'
if printf '%s\n' "$START_OUT" | grep -q "^Error"; then
  fail "am start reported an error"
fi

log "waiting ${WAIT_SECS}s for the app to settle"
sleep "$WAIT_SECS"

PID="$($ADB shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
LOGCAT="$($ADB logcat -d 2>/dev/null || true)"

# crash blocks come out of AndroidRuntime; VerifyError can also show up as a
# plain dalvikvm/art line before the FATAL block so grep for both
CRASH_LINES="$(printf '%s\n' "$LOGCAT" | grep -E "FATAL EXCEPTION|VerifyError|Verifier rejected" | grep -E "$PACKAGE|AndroidRuntime|art |dalvikvm" || true)"

STATUS=0
if [ -z "$PID" ]; then
  log "FAIL: $PACKAGE is not running ${WAIT_SECS}s after launch"
  STATUS=1
fi
if [ -n "$CRASH_LINES" ]; then
  log "FAIL: crash / verifier markers in logcat"
  STATUS=1
fi

if [ "$STATUS" -ne 0 ]; then
  echo "----- logcat (crash block for $PACKAGE) -----" >&2
  # print the whole crash block, not just the matched lines, so the stack
  # trace is right there in the CI log. AndroidRuntime carries the FATAL
  # block, ActivityManager says when the proc started/died. plain $PACKAGE
  # matches are left out on purpose, on a play image Finsky spams dozens
  # of billing lines per second about the package and buries the trace.
  printf '%s\n' "$LOGCAT" \
    | grep -E "AndroidRuntime|VerifyError|Verifier rejected|ActivityManager: .*$PACKAGE" \
    | tail -n 200 >&2
  echo "----------------------------------------------" >&2
  exit 1
fi

log "OK: $PACKAGE running (pid $PID), no crash or VerifyError in logcat"
