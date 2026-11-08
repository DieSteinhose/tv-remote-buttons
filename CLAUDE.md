# Notes for AI assistants

- Code comments, log messages and README are in English. UI texts exist in English
  (`res/values`) and German (`res/values-de`); keep both in sync.
- Build with `./build.sh`. Keep the signing key from the git-ignored `signing.env`, otherwise
  updates on installed devices fail with a signature mismatch.
- **Releases:** whenever you build an APK, ask the user whether to publish it. Only after an
  explicit yes: raise `android:versionCode` and `android:versionName` in `AndroidManifest.xml`
  (unless this version has not been released yet), commit, then run `./release.sh`.
- Commit or push only when the user asks for it.
