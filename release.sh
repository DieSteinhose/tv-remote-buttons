#!/usr/bin/env bash
# Starts a release: tags the current commit as v<versionName> and pushes the tag. GitHub Actions
# (.github/workflows/release.yml) then builds the APK, signs it with the release key from the
# repository secrets and publishes it under Releases, where the in-app updater finds it.
# Usage: ./release.sh ["release notes"]   (without notes, GitHub generates them from the commits)
#
# Before a new release, raise android:versionCode and android:versionName in AndroidManifest.xml
# and commit. An existing tag is never overwritten.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
TAG="v$VERSION"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Uncommitted changes - commit first." >&2; exit 1
fi
git fetch -q --tags origin
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "$TAG already exists - raise versionName/versionCode in AndroidManifest.xml first." >&2; exit 1
fi
python3 .github/scripts/check_profiles.py >/dev/null

git push origin HEAD
if [ $# -gt 0 ]; then git tag -a "$TAG" -m "$*"; else git tag -a "$TAG" -m ""; fi
git push origin "$TAG"
echo "Tag $TAG pushed - GitHub Actions now builds and publishes the release:"
echo "  https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/actions/workflows/release.yml"
