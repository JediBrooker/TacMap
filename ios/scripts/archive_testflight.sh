#!/usr/bin/env bash
#
# Build a Release .xcarchive of TacticalMaps suitable for uploading to
# TestFlight. After the script finishes, open the archive in Xcode:
#
#     open <archive_path>     # opens Xcode Organizer at this archive
#
# In Organizer choose Distribute App → App Store Connect → Upload.
# Manual code signing is configured in project.yml (team 6MY34D5RKG,
# provisioning profile "TacticalMaps").
#
# Run with no arguments:
#     ./scripts/archive_testflight.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."   # project root: ios/

# Locate Xcode (Command Line Tools alone won't archive — needs full Xcode).
if [[ -z "${DEVELOPER_DIR:-}" ]]; then
  if [[ -d /Applications/Xcode.app/Contents/Developer ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  else
    echo "✗ Can't find Xcode. Install Xcode and run xcode-select -s /Applications/Xcode.app." >&2
    exit 1
  fi
fi

# Regenerate the Xcode project so any project.yml changes (version bump,
# new resources, etc.) are reflected before we archive.
echo "→ Regenerating Xcode project from project.yml"
xcodegen generate --quiet

# Stamp the archive with the current build number for easy identification.
BUILD=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" TacticalMaps/Resources/Info.plist)
SHORT=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" TacticalMaps/Resources/Info.plist)
ARCHIVE_DIR="build/archives"
ARCHIVE_PATH="$ARCHIVE_DIR/TacticalMaps-$SHORT-$BUILD.xcarchive"
mkdir -p "$ARCHIVE_DIR"

echo "→ Archiving TacticalMaps $SHORT (build $BUILD) → $ARCHIVE_PATH"
xcodebuild \
  -project TacticalMaps.xcodeproj \
  -scheme TacticalMaps \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE_PATH" \
  -allowProvisioningUpdates \
  archive

echo
echo "✓ Archive ready: $ARCHIVE_PATH"
echo
echo "Next steps:"
echo "  1. open '$ARCHIVE_PATH'         # opens Xcode Organizer"
echo "  2. Distribute App → App Store Connect → Upload"
echo "  3. Wait for the build to appear in App Store Connect → TestFlight"
echo "  4. Add it to your TestFlight group and notify testers"
