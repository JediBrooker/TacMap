#!/usr/bin/env bash
# Creates ios/Secrets.xcconfig if it isn't there yet.
#
# xcodegen needs the file to exist before it can generate the project, but the
# file is gitignored (it holds the Esri key), so a fresh clone and CI both need
# it materialised first. Local dev gets an empty key and a working build; CI
# passes the real one in via the ESRI_API_KEY env var / repo secret.
#
# Idempotent. Never overwrites an existing Secrets.xcconfig.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$here/ios/Secrets.xcconfig"
example="$here/ios/Secrets.example.xcconfig"

if [ -f "$target" ]; then
  echo "ios/Secrets.xcconfig already exists, leaving it alone"
  exit 0
fi

cp "$example" "$target"
if [ -n "${ESRI_API_KEY:-}" ]; then
  # BSD sed (macOS) wants the empty -i arg. Only touch the assignment line.
  sed -i '' "s|^ESRI_API_KEY =.*|ESRI_API_KEY = ${ESRI_API_KEY}|" "$target"
  echo "wrote ios/Secrets.xcconfig with ESRI_API_KEY from the environment"
else
  echo "wrote ios/Secrets.xcconfig with an empty ESRI_API_KEY (Esri basemap disabled)"
fi
