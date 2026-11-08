# Notes for AI assistants

- Code comments, log messages, README and workflows are in English. UI texts exist in English
  (`res/values`) and German (`res/values-de`); keep both in sync.
- Device profiles live in `profiles/devices.json` (schema 2: remote profiles and TV profiles, combined
  by the app; loaded at runtime from GitHub, a copy is bundled into the APK). `profiles/profiles.json`
  (schema 1, for app versions up to 1.6) is generated from it - never edit it by hand. Check with
  `python3 .github/scripts/check_profiles.py` (`--fix` formats and regenerates). New TVs and remotes
  need a profile change, not a new app version. Every profile needs a `### <id>` section in
  `DEVICES.md` (the supported-devices list); the check enforces it.
- Device reports (issues labeled `device-report`) are reviewed by `report-agent.yml` (Claude Haiku,
  instructions in `.github/agent/profile-agent.md`), which proposes profile pull requests.
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
