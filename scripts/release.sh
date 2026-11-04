#!/usr/bin/env bash
# Builds a signed release APK and publishes it as a GitHub release, which the app's updater picks up.
#
# Usage: scripts/release.sh <X.Y.Z> [notes-file.md]
# Needs: a clean git tree, the signing key (~/.config/bloom/signing.properties), and `gh` logged in.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-}"
NOTES_FILE="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Usage: $0 X.Y.Z [notes.md]" >&2; exit 1; }
IFS=. read -r MAJOR MINOR PATCH <<< "$VERSION"
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))
CURRENT=$(sed -n 's/^bloom.versionCode=//p' gradle.properties)

SIGNING="${BLOOM_SIGNING_PROPERTIES:-$HOME/.config/bloom/signing.properties}"
[[ -f "$SIGNING" ]] || { echo "Signing settings not found at $SIGNING" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Commit or stash your changes first." >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then echo "Tag v$VERSION already exists." >&2; exit 1; fi
# Equal is fine only for a version that was never tagged (checked above), e.g. the first release.
(( CODE >= CURRENT )) || { echo "Version $VERSION ($CODE) must be newer than the current $CURRENT." >&2; exit 1; }

sed -i "s/^bloom.versionName=.*/bloom.versionName=$VERSION/; s/^bloom.versionCode=.*/bloom.versionCode=$CODE/" gradle.properties

# Phones are arm64; leaving out the emulator ABI keeps the download ~20 MB smaller.
./gradlew --console=plain :app:testDebugUnitTest :app:assembleRelease -Pbloom.abis=arm64-v8a

APK=app/build/outputs/apk/release/app-release.apk
APKSIGNER=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner | sort -V | tail -1)
"$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -q "CN=Bloom" || { echo "APK isn't signed with the Bloom key." >&2; exit 1; }

mkdir -p build/release
OUT="build/release/bloom-$VERSION.apk"
cp "$APK" "$OUT"

if ! git diff --quiet gradle.properties; then
  git add gradle.properties
  git commit -m "Release $VERSION"
fi
git tag -a "v$VERSION" -m "Bloom $VERSION"
git push origin HEAD --follow-tags

if [[ -n "$NOTES_FILE" ]]; then
  gh release create "v$VERSION" "$OUT" --title "Bloom $VERSION" --notes-file "$NOTES_FILE"
else
  gh release create "v$VERSION" "$OUT" --title "Bloom $VERSION" --generate-notes
fi
echo "Published Bloom $VERSION ($CODE): $(gh release view "v$VERSION" --json url -q .url)"
