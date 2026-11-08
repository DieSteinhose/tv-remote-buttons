#!/usr/bin/env bash
# Builds the APK and publishes it as a GitHub release for the version in AndroidManifest.xml.
# Usage: ./release.sh [release notes]     (without notes, GitHub generates them from the commits)
#
# Before a new release, raise android:versionCode and android:versionName in AndroidManifest.xml
# and commit. The tag is v<versionName>; an existing tag is never overwritten.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
TAG="v$VERSION"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Uncommitted changes - commit first." >&2; exit 1
fi
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || gh release view "$TAG" >/dev/null 2>&1; then
  echo "$TAG already exists - raise versionName/versionCode in AndroidManifest.xml first." >&2; exit 1
fi

./build.sh
ASSET="build/tv-remote-buttons-$VERSION.apk"
cp build/tv-remote-buttons.apk "$ASSET"

git push origin HEAD
if [ $# -gt 0 ]; then NOTES=(--notes "$*"); else NOTES=(--generate-notes); fi
gh release create "$TAG" "$ASSET" --target "$(git rev-parse HEAD)" --title "TV Remote Buttons $VERSION" "${NOTES[@]}"
git fetch -q --tags origin
