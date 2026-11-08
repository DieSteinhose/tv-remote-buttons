# TV Remote Buttons

Give the buttons of your Android TV / Google TV remote new functions: open any app or a specific
page inside an app, jump to a TV settings submenu, show the program guide, or trigger Home Assistant.
Each button can have separate actions for a single press, pressing twice and holding.

![Overview](docs/overview.png)

The app is small (about 7 MB RAM), needs no Gradle and no libraries, and is built for D-pad
navigation. Its UI is available in English and German.

## Features

- **Remap app buttons** such as YouTube, Netflix, Prime Video, Live TV, Guide, Apps or Settings,
  and any other button you teach it.
- **Targets**
  - any installed app
  - a specific page of an app, such as a TV settings submenu (Network, Picture, Sound, Timer, …)
  - the live TV app and its program guide
  - the home screen
  - any intent or deep link (`intent:#Intent;…;end`, `market://…`, …)
  - Home Assistant webhooks or any other HTTP endpoint
  - *do nothing*, or keep the factory behavior
- **Press, press twice, hold.** Off by default so every button reacts instantly; switch them on
  under *Press options*. Only buttons that actually have a double-press assignment wait for a
  second press.
- **Device profiles.** On first start the app detects the TV and recommends a matching profile that
  already knows the buttons of its remote. Without a profile you simply teach the buttons yourself.
- **Teaching buttons.** Press the button, then pick its type (Live TV, Netflix, YouTube, Settings,
  Input, …). The type sets the name and suggests a target.
- **Upload buttons.** Anyone can send the buttons of their remote (TV model, remote name, keycodes,
  types) with one click and without an account, so that new device profiles can be added. Reports
  become public issues labeled `device-report`; apps and assignments are never sent.
- **Configuration via adb** for setup scripts (see below).

## How it works

An `AccessibilityService` with `flagRequestFilterKeyEvents` sees key presses before the system hands
them to their factory targets. Assigned buttons are consumed and their target is run; every other key
passes through untouched. Home, Back, Power, volume and D-pad keys are never intercepted.

The service only receives key events and window-change events (to know which app is in front, needed
for the program guide). It cannot read screen content.

## Build

Requirements: Android SDK with `platforms/android-34` and `build-tools/34.0.0`, a JDK (11 or newer),
`rsvg-convert` (librsvg) and ImageMagick (`magick`).

```sh
./build.sh            # -> build/tv-remote-buttons.apk
```

`ANDROID_HOME` defaults to `~/Android/Sdk`.

### Signing

On the first build a local key is created in `~/.android/tv-remote-buttons.jks`. To sign with an
existing key, create a git-ignored `signing.env` next to `build.sh`:

```sh
KEYSTORE=$HOME/.android/my.jks
KEY_ALIAS=mykey
KEYSTORE_PASS=secret
```

Keep using the same key: Android only installs an update if it is signed with the key of the
installed app.

### Releases

Signed APKs are published under Releases. To publish one, raise `android:versionCode` and
`android:versionName` in `AndroidManifest.xml`, commit, then run:

```sh
./release.sh ["release notes"]   # builds, tags v<versionName>, uploads the APK
```

## Install and enable

```sh
adb install -r build/tv-remote-buttons.apk
```

Open **TV Remote Buttons** from the app list. If the service is not running yet, the app offers
**Turn on service**, which opens *Settings › Accessibility*. You can also enable it via adb:

```sh
adb shell settings put secure enabled_accessibility_services local.chiq.keys/.KeyService
adb shell settings put secure accessibility_enabled 1
```

The first command replaces the list of enabled accessibility services. If others are enabled, append
this one with a `:` separator instead.

## Using the settings screen

- **Overview:** all known buttons with their current target. Select one to change it.
- **Assignment:** the categories on the left are General, Television, Settings, Apps, App submenus,
  Home Assistant, Intent / Link and Button. The matching targets appear on the right. With press
  options switched on, tabs at the top choose between *Press*, *Press twice* and *Hold*.
- **Add button:** press the new button on the remote, then pick its type.
- **Press options:** switch *press twice* and *hold* on or off and choose their timing
  (250/350/500 ms and 400/500/800/1000 ms).
- **Device profile:** switch or remove the profile. Your own assignments are kept.
- **Upload buttons:** shows exactly what would be sent, with an optional note, and creates a
  device report.

Note: an app cannot hand an intercepted key back to the system. If a button has a *press twice* or
*hold* assignment, its single press can therefore no longer trigger the factory function. Give the
single press a target of its own.

## Device profiles

Profiles live in [`Profiles.java`](src/local/chiq/keys/Profiles.java). A profile defines:

- the buttons of the remote (keycode, type, default target),
- the live TV activity of the device,
- whether the program guide can be opened,
- extra keys that must never be intercepted.

Included profiles:

| Profile | Devices | Notes |
|---|---|---|
| `chiq_mt58` | CHiQ Google TV with MediaTek MT58xx (tested: GTVU65, Android 14) | Bluetooth remote `TVC0GWBT`; the remote sends `MEDIA_SKIP_FORWARD` after every button as a release marker, so that key is protected. |

To add a profile, log the keycodes of the remote and press every button once:

```sh
adb shell setprop log.tag.RemoteButtons DEBUG
adb logcat -s RemoteButtons
```

Then add a `Profile` to `Profiles.ALL`. Pull requests with new profiles are welcome. Without
programming, use **Upload buttons** in the app: teach all buttons of your remote first, then upload.

## Device reports (upload)

**Upload buttons** posts a JSON report to a small [Cloudflare Worker](worker/), which files it as a
GitHub issue labeled `device-report`. The GitHub token only exists in the worker, so the app needs
no account. The worker checks size and structure, allows at most 5 reports per minute and IP
address, does not store IP addresses, and renders all report text as code so it cannot add links
or mentions.

A report contains:

- device: brand, manufacturer, model, product, hardware, Android version
- active and detected profile, app version, press options (on/off)
- external input devices (name, vendor and product ID)
- per button: keycode, type, own name, scan code and remote (for taught buttons), and whether it
  comes from the profile
- the optional note

It does not contain assignments, installed apps or the Home Assistant address.

To run your own worker:

```sh
cd worker
npx wrangler login
npx wrangler secret put GITHUB_TOKEN   # fine-grained token: your repo, Issues read/write
npx wrangler deploy                    # prints https://tv-remote-buttons.<account>.workers.dev
```

Set `REPO` in `worker/wrangler.toml` to your repository and `Report.URL` in
[`Report.java`](src/local/chiq/keys/Report.java) to `<worker URL>/report`, then create the label:
`gh label create device-report`.

## Home Assistant

Choose **Home Assistant** as a target. The button then sends a POST to
`<address>/api/webhook/chiq_tv_<button>`, with `_double` or `_long` appended for press twice and
hold. The address defaults to `http://homeassistant.local:8123` and can be changed on the overview.

Payload:

```json
{"key": "BUTTON_5", "keycode": 192, "action": "single", "name": "Custom button", "device": "AI PONT"}
```

Example automation trigger:

```yaml
triggers:
  - trigger: webhook
    webhook_id: chiq_tv_button_5
    allowed_methods: [POST]
    local_only: true
```

Any other `http(s)://` URL works as a target as well.

## Configuration via adb

The `ConfigReceiver` accepts broadcasts from the shell only (it requires `android.permission.DUMP`).
Every broadcast prints the current configuration.

```sh
R="am broadcast -n local.chiq.keys/.ConfigReceiver"
adb shell $R                                              # show configuration
adb shell $R --es profile auto                            # detected profile (or an id, or none)
adb shell $R --ei key 247 --es target org.smarttube.stable
adb shell $R --ei key 192 --es action long --es target @ha
adb shell $R --ei key 192 --es target default             # back to the profile default
adb shell $R --ei test 192                                # trigger a button's target
adb shell $R --ez double_on true --ei double_ms 350       # press options
adb shell "$R --ei key 192 --es target 'intent:#Intent;action=android.settings.WIFI_SETTINGS;end'"
```

Add `--ez keep true` to change only what is not configured yet, which is useful in setup scripts.

Target notation:

| Target | Meaning |
|---|---|
| `com.example.app` | start an app |
| `com.example.app/com.example.app.SomeActivity` | start a specific activity |
| `intent:#Intent;…;end` | any intent |
| `scheme:…` | deep link (VIEW) |
| `@livetv`, `@epg` | live TV app, program guide |
| `@home` | home screen |
| `@ha`, `http(s)://…` | webhook |
| `@none` | do nothing |
| `@system` | factory behavior |

## Limitations

- Key presses injected with `adb shell input keyevent` bypass accessibility key filtering. Test with
  the real remote, or use `--ei test <keycode>`.
- The program guide shortcut currently works with the MediaTek live TV app (`chiq_mt58` profile).
- The package name `local.chiq.keys` and the webhook prefix `chiq_tv_` date back to the first device
  this was written for. They are kept so that existing installations and webhooks keep working.
