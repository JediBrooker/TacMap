#!/usr/bin/env bash
# Reject simulator bundles that were only linker-signed. Those bundles launch,
# but Security.framework cannot reliably identify them for Keychain access.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/TacticalMaps.app" >&2
  exit 2
fi

app_path="$1"
expected_bundle_id="com.tacticalmaps.app"
expected_application_id="6MY34D5RKG.com.tacticalmaps.app"

if [[ ! -d "$app_path" ]]; then
  echo "Simulator app not found: $app_path" >&2
  exit 1
fi

bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_path/Info.plist")"
if [[ "$bundle_id" != "$expected_bundle_id" ]]; then
  echo "Unexpected simulator bundle identifier: $bundle_id" >&2
  exit 1
fi

executable_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app_path/Info.plist")"
otool_output="$(otool -l "$app_path/$executable_name")"
if [[ "$otool_output" != *"sectname __entitlements"* ]]; then
  echo "Simulator app has no linker-embedded application entitlements; Keychain access would fail." >&2
  exit 1
fi
binary_strings="$(strings "$app_path/$executable_name")"
if [[ "$binary_strings" != *"<key>application-identifier</key>"* ]] ||
   [[ "$binary_strings" != *"$expected_application_id"* ]]; then
  echo "Simulator app has no expected application identifier entitlement: $expected_application_id" >&2
  exit 1
fi

if ! codesign --verify --deep --strict "$app_path"; then
  echo "Invalid or incomplete simulator app signature: $app_path" >&2
  exit 1
fi

signature_details="$(codesign -dv --verbose=4 "$app_path" 2>&1)"
if [[ "$signature_details" != *"Identifier=$expected_bundle_id"* ]] ||
   [[ "$signature_details" == *"linker-signed"* ]] ||
   [[ "$signature_details" == *"Info.plist=not bound"* ]] ||
   [[ "$signature_details" == *"Sealed Resources=none"* ]]; then
  echo "Linker-only simulator build rejected: secure Keychain storage would fail." >&2
  echo "$signature_details" >&2
  exit 1
fi

echo "Verified normally signed simulator app: $app_path"
