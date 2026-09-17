#!/usr/bin/env bash
#
# Build, update, and launch TacMap on one pinned, pre-existing iPhone
# simulator. This script never creates, clones, erases, or deletes a simulator.
#
# First use (choose an existing standard iPhone by UDID):
#   ./scripts/install_simulator.sh --device <EXISTING_IPHONE_SIMULATOR_UDID>
#
# Later uses:
#   ./scripts/install_simulator.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

PIN_FILE=".tacmap-simulator-udid"
EXPECTED_BUNDLE_ID="com.tacticalmaps.app"
DERIVED_DATA_PATH="${TACMAP_DERIVED_DATA_PATH:-build/simulator}"

usage() {
  echo "Usage: $0 [--device EXISTING_IPHONE_SIMULATOR_UDID]" >&2
}

requested_udid=""
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || "$1" != "--device" ]]; then
    usage
    exit 2
  fi
  requested_udid="$2"
fi

if [[ -n "$requested_udid" ]]; then
  simulator_udid="$requested_udid"
elif [[ -f "$PIN_FILE" ]]; then
  IFS= read -r simulator_udid < "$PIN_FILE"
else
  echo "No iPhone simulator is pinned." >&2
  echo "Choose one existing standard iPhone from 'xcrun simctl list devices available', then run:" >&2
  echo "  $0 --device <UDID>" >&2
  exit 2
fi

if [[ ! "$simulator_udid" =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]]; then
  echo "Invalid simulator UDID: $simulator_udid" >&2
  exit 2
fi

if ! device_record="$({ xcrun simctl list --json; } | /usr/bin/python3 -c '
import json
import sys

inventory = json.load(sys.stdin)
udid = sys.argv[1]
device = next(
    (candidate for runtime in inventory["devices"].values() for candidate in runtime
     if candidate.get("udid") == udid and candidate.get("isAvailable", False)),
    None,
)
if device is None:
    raise SystemExit(2)
device_type = next(
    (candidate for candidate in inventory["devicetypes"]
     if candidate.get("identifier") == device.get("deviceTypeIdentifier")),
    None,
)
if (device_type is None or device_type.get("productFamily") != "iPhone"
        or device.get("name") != device_type.get("name")):
    print(
        "Refusing a renamed, custom, or non-iPhone simulator: "
        + device.get("name", "unknown")
        + " [" + device.get("deviceTypeIdentifier", "unknown") + "]",
        file=sys.stderr,
    )
    raise SystemExit(3)
print("\t".join((
    device["name"], device["state"], device["deviceTypeIdentifier"]
)))
' "$simulator_udid")"; then
  echo "Pinned simulator is not available: $simulator_udid" >&2
  echo "This script will not create a replacement. Select another existing standard iPhone with --device." >&2
  exit 1
fi
IFS=$'\t' read -r device_name device_state device_type_identifier <<< "$device_record"
device_line="$device_name ($simulator_udid) ($device_state) [$device_type_identifier]"

if [[ -n "$requested_udid" ]]; then
  printf '%s\n' "$simulator_udid" > "$PIN_FILE"
fi

if [[ "$device_state" == "Shutdown" ]]; then
  echo "Booting pinned simulator: $device_line"
  xcrun simctl boot "$simulator_udid"
fi
xcrun simctl bootstatus "$simulator_udid" -b

echo "Generating the Xcode project"
xcodegen generate --quiet

echo "Building a normally signed simulator app"
xcodebuild -quiet \
  -project TacticalMaps.xcodeproj \
  -scheme TacticalMaps \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_udid" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  build

app_path="$DERIVED_DATA_PATH/Build/Products/Debug-iphonesimulator/TacticalMaps.app"
./scripts/verify_simulator_app.sh "$app_path"

# Deliberately install over the existing app. Do not uninstall first: updates
# must preserve the app container and the device-bound Keychain records.
echo "Installing on the pinned simulator (preserving existing app data)"
xcrun simctl install "$simulator_udid" "$app_path"
xcrun simctl launch --terminate-running-process "$simulator_udid" "$EXPECTED_BUNDLE_ID"
open -a Simulator

echo "TacMap is running on the pinned simulator: $simulator_udid"
