# Supported devices

Device profiles live in [`profiles/devices.json`](profiles/devices.json) and come in two layers
that the app combines:

- **Remote profiles** know the buttons of a remote (keycodes, types, default targets). A remote is
  recognized by its input device (name, vendor and product ID), so it also works on other TVs.
- **TV profiles** know the TV itself: its live TV app, the program guide and quirks of its software.
  A TV is recognized by brand and chip platform.

Vendors use the same software and remotes for whole product families - all screen sizes and
regions of a series, often several series. If your exact model is not listed but the remote or the
TV family matches, the profile very likely fits; the app detects and recommends it automatically.

The app downloads the profiles at startup, so new devices need no new app version. Other Android TV
/ Google TV devices work too; there you teach the buttons yourself. Is your remote or TV missing?
Open the app, choose **Upload buttons**, map the buttons and send the report. An agent reviews each
report, extends the profiles and this list, and opens a pull request.

## Remotes

| Remote | Profile | Works with | Buttons |
|---|---|---|---|
| [CHiQ remote TVC0GWBT](#chiq_tvc0gwbt) | `chiq_tvc0gwbt` | [CHiQ Google TV](#chiq_mt58) | 11 |
| [Philips remote (TP Vision)](#philips_tpv_rc) | `philips_tpv_rc` | [Philips TV](#philips_tpv) | 4 |

### chiq_tvc0gwbt

**CHiQ remote TVC0GWBT (Bluetooth and infrared)** - Bluetooth input device `TVC0GWBT` (vendor
`0x1d5a`, product `0xc082`); without a Bluetooth connection the same buttons arrive via infrared at
the TV's `MStar Smart TV IR Receiver` (`0x3697`/`0x0001`) with the same keycodes

- Tested with: [CHiQ Google TV](#chiq_mt58) GTVU65 (Bluetooth: #2, infrared: #7)
- Limited to CHiQ TVs (`match.tvs`): the IR receiver is built into many MediaTek TVs, whose software
  maps infrared codes differently

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
| Microphone | `TV_RADIO_SERVICE` | assistant | factory |
| Switch profile | `PROFILE_SWITCH` | other | factory |

Notes: via Bluetooth the remote sends `MEDIA_SKIP_FORWARD` after every button as a release marker,
so that key is protected (via infrared there is no marker). YouTube, Netflix and Prime work out of
the box; Live TV, Guide and Settings get own targets because their factory functions are broken on
the CHiQ TV.

### philips_tpv_rc

**Philips remote (TP Vision)** - the buttons arrive via the Philips input devices `TPV_MutilRC` and
`TPV_SMTRC` (microphone); the Bluetooth remote itself appears as `PHLRC` (`0x248a`/`0x8266`)

- Tested with: [Philips TV](#philips_tpv) 65OLED888/12
- Reports: #3, #5

| Button | Key | Type | Default |
|---|---|---|---|
| TV button (also microphone) | `811` | livetv | factory |
| Netflix | `NETFLIX` | netflix | factory |
| YouTube | `781` | youtube | factory |
| Prime Video | `788` | prime | factory |

Notes: the TV button (scan code `0x2ff`) and the microphone (`0xd9`) both send keycode 811 and can
only be assigned together; by default both keep their factory function.

## TVs

| TV | Profile | Tested series (sizes) | Remotes |
|---|---|---|---|
| [CHiQ Google TV (MediaTek)](#chiq_mt58) | `chiq_mt58` | GTVU65 | [TVC0GWBT](#chiq_tvc0gwbt) |
| [Philips TV (TP Vision)](#philips_tpv) | `philips_tpv` | OLED888 (65″) | [Philips remote](#philips_tpv_rc) |

### chiq_mt58

**CHiQ Google TV (MediaTek)** - brand CHiQ / Changhong, hardware `mt58…`

- Tested: GTVU65 (model `AI PONT`, hardware `mt5867`, Android 14; reports #2, #7)
- Live TV: `com.mediatek.wwtv.tvcenter` (MediaTek), program guide supported
- Remotes: [TVC0GWBT](#chiq_tvc0gwbt) (Bluetooth and infrared)

### philips_tpv

**Philips TV (TP Vision)** - brand Philips

- Tested series: OLED888 in 65″ (model number 65OLED888/12, platform Philips Google TV TA1 /
  PH1M_WW_9972, MediaTek mt5896, Android 12)
- Likely also fits: other sizes and Philips Google TV series
- Live TV: `org.droidtv.playtv/.PlayTvActivity`
- Remotes: [Philips remote](#philips_tpv_rc)

Notes: the app buttons are Philips "global keys": the TV still runs their factory function (for
example the Play Store page of a disabled app) even when the app intercepts them, so a different
target may be covered by the factory app.
