# Notes for AI assistants

- Code comments, log messages, README and workflows are in English. UI texts exist in English
  (`res/values`) and German (`res/values-de`); keep both in sync.
- Device profiles live in `profiles/profiles.json` (loaded by the app at runtime from GitHub, a copy
  is bundled into the APK). Check with `python3 .github/scripts/check_profiles.py` (`--fix` formats).
  New TVs need a profile change, not a new app version.
- Build with `./build.sh`. Local `signing.env` (git-ignored) points to the release key
  `~/.android/tv-remote-buttons.jks`, the same key as the GitHub secrets `SIGNING_*`. Never commit
  keys, `signing.env` or `worker/.wrangler/`.
- **Releases:** whenever you build an APK, ask the user whether to publish it. Only after an
  explicit yes: raise `android:versionCode` and `android:versionName` in `AndroidManifest.xml`
  (unless this version has not been released yet), commit, then run `./release.sh` - it pushes the
  tag and GitHub Actions builds, signs and publishes the release.
- The report worker (`worker/`) is deployed manually with `npx wrangler@4 deploy`.
- Commit or push only when the user asks for it.
- No `Co-Authored-By: Claude` trailers or other AI attribution in commit messages or PRs.
