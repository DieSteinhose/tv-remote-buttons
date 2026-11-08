# Supported devices

TVs and remotes with a device profile in [`profiles/profiles.json`](profiles/profiles.json). The
app downloads the profiles at startup, so a TV listed here works without a new app version. Other
Android TV / Google TV devices work too; there you teach the buttons yourself.

A profile covers a product family, not a single model: vendors use the same software and remotes
for all screen sizes and regions of a series, and often for several series. If your exact model is
not listed but the brand, platform and remote match, the profile very likely fits; the app
recommends it automatically.

Is your TV missing? Open the app, choose **Upload buttons**, map the buttons and send the report.
An agent reviews each report, extends the profiles and this list, and opens a pull request.

| TV | Profile | Tested series (sizes) | Mapped buttons |
|---|---|---|---|
| [CHiQ Google TV (MediaTek)](#chiq_mt58) | `chiq_mt58` | GTVU65 | 9 |
| [Philips Google TV (TPV)](#philips_tpv) | `philips_tpv` | OLED888 (65″) | 4 |

## chiq_mt58

**CHiQ Google TV (MediaTek)** - brand CHiQ / Changhong, hardware `mt58…`

- Tested: GTVU65 (model `AI PONT`, Android 14)
- Remote: Bluetooth `TVC0GWBT` (vendor `0x1d5a`, product `0xc082`)
- Live TV: `com.mediatek.wwtv.tvcenter` (MediaTek), program guide supported
- Reports: #2

| Button | Key | Type | Default |
|---|---|---|---|
| YouTube | `TV_INPUT_COMPOSITE_1` | youtube | factory |
| Netflix | `STEM_1` | netflix | factory |
| Live TV | `FEATURED_APP_1` | livetv | live TV |
| Prime Video | `TV_INPUT_COMPONENT_1` | prime | factory |
| Apps | `ALL_APPS` | apps | factory |
| Guide (2nd Live TV button) | `GUIDE` | guide | program guide |
| Settings | `NOTIFICATION` | settings | TV settings |
| Custom button | `BUTTON_5` | free | factory |
| USB | `REFRESH` | media | factory |

Notes: the remote sends `MEDIA_SKIP_FORWARD` after every button as a release marker, so that key is
protected. YouTube, Netflix and Prime work out of the box; Live TV, Guide and Settings get own
targets because their factory functions are broken on this TV.

## philips_tpv

**Philips Google TV (TPV)** - brand Philips (TP Vision), remotes `TPV_MutilRC` / `TPV_SMTRC`

- Tested series: OLED888 in 65″ (model number 65OLED888/12, platform Philips Google TV TA1 /
  PH1M_WW_9972, MediaTek mt5896, Android 12)
- Likely also fits: other sizes and Philips Google TV series with the same remote
- Remote: Philips remote, keys arrive via `TPV_MutilRC` and `TPV_SMTRC` (microphone)
- Live TV: `org.droidtv.playtv/.PlayTvActivity`
- Reports: #3

| Button | Key | Type | Default |
|---|---|---|---|
| TV button (also microphone) | `811` | livetv | factory |
| Netflix | `NETFLIX` | netflix | factory |
| YouTube | `781` | youtube | factory |
| Prime Video | `788` | prime | factory |

Notes: the app buttons are Philips "global keys": the TV still runs their factory function (for
example the Play Store page of a disabled app) even when the app intercepts them, so a different
target may be covered by the factory app. The TV button (scan code `0x2ff`) and the microphone
(`0xd9`) both send keycode 811 and can only be assigned together; by default both keep their
factory function.
