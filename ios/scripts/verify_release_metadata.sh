#!/usr/bin/env bash
# Verify privacy and release declarations in a built/archive app bundle.
# Usage: ./scripts/verify_release_metadata.sh path/to/TacticalMaps.app
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 path/to/TacticalMaps.app" >&2
  exit 64
fi

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_PATH="$1"
SOURCE_PRIVACY="$IOS_ROOT/TacticalMaps/Resources/PrivacyInfo.xcprivacy"
SOURCE_INFO="$IOS_ROOT/TacticalMaps/Resources/Info.plist"
BUILT_PRIVACY="$APP_PATH/PrivacyInfo.xcprivacy"
BUILT_INFO="$APP_PATH/Info.plist"
PLIST_BUDDY=/usr/libexec/PlistBuddy

fail() {
  echo "release metadata check failed: $*" >&2
  exit 1
}

for plist in "$SOURCE_PRIVACY" "$SOURCE_INFO" "$BUILT_PRIVACY" "$BUILT_INFO"; do
  [[ -f "$plist" ]] || fail "missing $plist"
  plutil -lint "$plist" >/dev/null || fail "invalid plist: $plist"
done

value() {
  "$PLIST_BUDDY" -c "Print :$2" "$1" 2>/dev/null
}

expect() {
  local plist="$1" key="$2" expected="$3" actual
  actual="$(value "$plist" "$key")" || fail "missing $key in $plist"
  [[ "$actual" == "$expected" ]] || fail "$key in $plist was '$actual', expected '$expected'"
}

expect_absent() {
  if "$PLIST_BUDDY" -c "Print :$2" "$1" >/dev/null 2>&1; then
    fail "unexpected $2 in $1"
  fi
}

# These values are tied to audited production use and Apple's approved-reason
# list. Keep the unit test's order-independent semantic assertion in sync.
for manifest in "$SOURCE_PRIVACY" "$BUILT_PRIVACY"; do
  expect "$manifest" "NSPrivacyAccessedAPITypes:0:NSPrivacyAccessedAPIType" \
    "NSPrivacyAccessedAPICategoryFileTimestamp"
  expect "$manifest" "NSPrivacyAccessedAPITypes:0:NSPrivacyAccessedAPITypeReasons:0" "C617.1"
  expect "$manifest" "NSPrivacyAccessedAPITypes:0:NSPrivacyAccessedAPITypeReasons:1" "3B52.1"
  expect_absent "$manifest" "NSPrivacyAccessedAPITypes:0:NSPrivacyAccessedAPITypeReasons:2"

  expect "$manifest" "NSPrivacyAccessedAPITypes:1:NSPrivacyAccessedAPIType" \
    "NSPrivacyAccessedAPICategorySystemBootTime"
  expect "$manifest" "NSPrivacyAccessedAPITypes:1:NSPrivacyAccessedAPITypeReasons:0" "35F9.1"
  expect_absent "$manifest" "NSPrivacyAccessedAPITypes:1:NSPrivacyAccessedAPITypeReasons:1"

  expect "$manifest" "NSPrivacyAccessedAPITypes:2:NSPrivacyAccessedAPIType" \
    "NSPrivacyAccessedAPICategoryUserDefaults"
  expect "$manifest" "NSPrivacyAccessedAPITypes:2:NSPrivacyAccessedAPITypeReasons:0" "CA92.1"
  expect_absent "$manifest" "NSPrivacyAccessedAPITypes:2:NSPrivacyAccessedAPITypeReasons:1"
  expect_absent "$manifest" "NSPrivacyAccessedAPITypes:3"

  expect "$manifest" "NSPrivacyCollectedDataTypes:0:NSPrivacyCollectedDataType" \
    "NSPrivacyCollectedDataTypePreciseLocation"
  expect "$manifest" "NSPrivacyCollectedDataTypes:0:NSPrivacyCollectedDataTypeLinked" "false"
  expect "$manifest" "NSPrivacyCollectedDataTypes:0:NSPrivacyCollectedDataTypeTracking" "false"
  expect "$manifest" "NSPrivacyCollectedDataTypes:0:NSPrivacyCollectedDataTypePurposes:0" \
    "NSPrivacyCollectedDataTypePurposeAppFunctionality"
  expect "$manifest" "NSPrivacyCollectedDataTypes:1:NSPrivacyCollectedDataType" \
    "NSPrivacyCollectedDataTypeEmailsOrTextMessages"
  expect "$manifest" "NSPrivacyCollectedDataTypes:1:NSPrivacyCollectedDataTypeLinked" "false"
  expect "$manifest" "NSPrivacyCollectedDataTypes:1:NSPrivacyCollectedDataTypeTracking" "false"
  expect "$manifest" "NSPrivacyCollectedDataTypes:1:NSPrivacyCollectedDataTypePurposes:0" \
    "NSPrivacyCollectedDataTypePurposeAppFunctionality"
  expect_absent "$manifest" "NSPrivacyCollectedDataTypes:2"
done

# Xcode currently copies this app manifest byte-for-byte. This catches a stale
# archive even if its older manifest happens to remain syntactically valid.
cmp -s "$SOURCE_PRIVACY" "$BUILT_PRIVACY" || \
  fail "built PrivacyInfo.xcprivacy is not the current source manifest"

expect "$SOURCE_INFO" "UIBackgroundModes:0" "location"
expect_absent "$SOURCE_INFO" "UIBackgroundModes:1"
expect_absent "$SOURCE_INFO" "NSLocationAlwaysUsageDescription"
expect_absent "$SOURCE_INFO" "NSLocationAlwaysAndWhenInUseUsageDescription"
expect "$BUILT_INFO" "UIBackgroundModes:0" "location"
expect_absent "$BUILT_INFO" "UIBackgroundModes:1"

SOURCE_LOCATION="$(value "$SOURCE_INFO" NSLocationWhenInUseUsageDescription)"
BUILT_LOCATION="$(value "$BUILT_INFO" NSLocationWhenInUseUsageDescription)"
[[ "$BUILT_LOCATION" == "$SOURCE_LOCATION" ]] || fail "built location permission copy is stale"
for phrase in "user-started GPX tracks" "background" "encrypted position" \
              "joined Sync peers" "only when you enable Share my location"; do
  [[ "$SOURCE_LOCATION" == *"$phrase"* ]] || fail "location permission copy omits '$phrase'"
done

SOURCE_SHORT_VERSION="$(value "$SOURCE_INFO" CFBundleShortVersionString)"
SOURCE_BUILD_VERSION="$(value "$SOURCE_INFO" CFBundleVersion)"
BUILT_SHORT_VERSION="$(value "$BUILT_INFO" CFBundleShortVersionString)"
BUILT_BUILD_VERSION="$(value "$BUILT_INFO" CFBundleVersion)"
[[ "$SOURCE_SHORT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  fail "source marketing version is not semantic x.y.z: $SOURCE_SHORT_VERSION"
[[ "$SOURCE_BUILD_VERSION" =~ ^[1-9][0-9]*$ ]] || \
  fail "source build number is not a positive integer: $SOURCE_BUILD_VERSION"
[[ "$BUILT_SHORT_VERSION" == "$SOURCE_SHORT_VERSION" ]] || \
  fail "built version $BUILT_SHORT_VERSION differs from source $SOURCE_SHORT_VERSION"
[[ "$BUILT_BUILD_VERSION" == "$SOURCE_BUILD_VERSION" ]] || \
  fail "built build number $BUILT_BUILD_VERSION differs from source $SOURCE_BUILD_VERSION"
expect "$BUILT_INFO" CFBundleIdentifier "com.tacticalmaps.app"

# Parity only: whether the current value is legally correct is an explicit
# account-holder/App Store Connect decision documented outside this script.
SOURCE_ENCRYPTION="$(value "$SOURCE_INFO" ITSAppUsesNonExemptEncryption)"
BUILT_ENCRYPTION="$(value "$BUILT_INFO" ITSAppUsesNonExemptEncryption)"
[[ "$BUILT_ENCRYPTION" == "$SOURCE_ENCRYPTION" ]] || \
  fail "built ITSAppUsesNonExemptEncryption differs from source"

echo "release metadata check passed: $APP_PATH ($BUILT_SHORT_VERSION build $BUILT_BUILD_VERSION)"
