package local.chiq.keys;

import android.os.Build;
import android.view.KeyEvent;

/**
 * Device profiles: which buttons a remote has (keycode, type, default target) plus device-specific
 * details. On first start the profile matching the device is recommended.
 *
 * New profile: find the keycodes with
 *   adb shell setprop log.tag.RemoteButtons DEBUG; adb logcat -s RemoteButtons
 * (press every button once), then add the profile to ALL below.
 */
final class Profiles {
    private Profiles() {}

    static final class Key {
        final int code;
        final String type;
        /** Default target (see KeyMap); SYSTEM = factory behavior. */
        final String def;
        /** Own name (string resource) instead of the type name, 0 = type name. */
        final int name;

        Key(int code, String type, String def) { this(code, type, def, 0); }
        Key(int code, String type, String def, int name) {
            this.code = code; this.type = type; this.def = def; this.name = name;
        }
    }

    static final class Profile {
        final String id;
        final String title;
        /** Live TV activity (package/class) of this device, or null (then known apps are searched). */
        final String liveTv;
        /** Program guide via VIEW on the programs URI sent to the live TV app (MediaTek). */
        final boolean epg;
        /** Additional keys that are never intercepted. */
        final int[] protect;
        final Key[] keys;
        private final String brand, platform;

        Profile(String id, String title, String brand, String platform, String liveTv, boolean epg,
                int[] protect, Key... keys) {
            this.id = id; this.title = title; this.brand = brand; this.platform = platform;
            this.liveTv = liveTv; this.epg = epg; this.protect = protect; this.keys = keys;
        }

        /** Does the profile match this device? */
        boolean matches() {
            boolean b = brand.equalsIgnoreCase(Build.BRAND) || brand.equalsIgnoreCase(Build.MANUFACTURER);
            return b && (platform == null || Build.HARDWARE.toLowerCase(java.util.Locale.ROOT).startsWith(platform));
        }

        Key key(int code) {
            for (Key k : keys) if (k.code == code) return k;
            return null;
        }
    }

    /**
     * CHiQ Google TV with MediaTek MT58xx (tested: GTVU65, Android 14), Bluetooth remote "TVC0GWBT".
     * YouTube/Netflix/Prime work out of the box -> factory behavior. Live TV, Guide and Settings depend
     * on the Google TV home screen (Settings was broken from the factory) -> own targets.
     * The remote sends MEDIA_SKIP_FORWARD after EVERY button as a release marker -> protected.
     */
    static final Profile CHIQ = new Profile("chiq_mt58", "CHiQ Google TV (MediaTek)", "CHiQ", "mt58",
            "com.mediatek.wwtv.tvcenter/com.mediatek.wwtv.tvcenter.nav.TurnkeyUiMainActivity", true,
            new int[]{KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD},
            new Key(KeyEvent.KEYCODE_TV_INPUT_COMPOSITE_1, "youtube", KeyMap.SYSTEM),
            new Key(KeyEvent.KEYCODE_STEM_1, "netflix", KeyMap.SYSTEM),
            new Key(KeyEvent.KEYCODE_FEATURED_APP_1, "livetv", KeyMap.LIVE_TV),
            new Key(KeyEvent.KEYCODE_TV_INPUT_COMPONENT_1, "prime", KeyMap.SYSTEM),
            new Key(KeyEvent.KEYCODE_ALL_APPS, "apps", KeyMap.SYSTEM),
            new Key(KeyEvent.KEYCODE_GUIDE, "guide", KeyMap.EPG, R.string.key_guide2),
            new Key(KeyEvent.KEYCODE_NOTIFICATION, "settings", KeyMap.TV_SETTINGS),
            new Key(KeyEvent.KEYCODE_BUTTON_5, "free", KeyMap.SYSTEM),
            new Key(KeyEvent.KEYCODE_REFRESH, "media", KeyMap.SYSTEM, R.string.key_usb));

    static final Profile[] ALL = {CHIQ};

    static Profile byId(String id) {
        if (id != null) for (Profile p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    /** Profile matching this device, or null. */
    static Profile detect() {
        for (Profile p : ALL) if (p.matches()) return p;
        return null;
    }

    /** "CHiQ GTVU65 (AI PONT)" */
    static String deviceName() {
        String brand = Build.BRAND == null || Build.BRAND.isEmpty() ? Build.MANUFACTURER : Build.BRAND;
        String name = Build.PRODUCT != null && !Build.PRODUCT.equalsIgnoreCase(Build.MODEL) ? Build.PRODUCT : "";
        return (brand + " " + name).trim() + " (" + Build.MODEL + ")";
    }
}
