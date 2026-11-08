package de.diesteinhose.tvremotebuttons;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Assignments: which button (Android keycode) starts which target.
 *
 * Target notation:
 *   "package"            start an app (Leanback launcher intent, otherwise the regular one)
 *   "package/class"      start a specific activity (e.g. a submenu of the TV settings)
 *   "intent:#Intent;...;end"  any intent (format of Intent.toUri(URI_INTENT_SCHEME)), e.g.
 *                        intent:#Intent;action=android.settings.WIFI_SETTINGS;end
 *   "scheme:..."         deep link: VIEW on the URI (e.g. market://details?id=...); http(s) is a webhook
 *   LIVE_TV              the device's live TV app (from the profile, otherwise known apps)
 *   EPG                  program guide of the live TV app (profiles with epg only; press again to close)
 *   HOME                 home screen
 *   HA                   Home Assistant webhook <HA address>/api/webhook/tv_remote_<button>
 *   "http(s)://..."      webhook: HTTP POST with JSON {key, keycode, action, name, device}
 *   NONE                 swallow the key (do nothing)
 *   SYSTEM               leave the key alone (factory behavior)
 *
 * Where an assignment comes from (later wins):
 *   1. the active device profile (Profiles), pref "profile" (missing = not chosen yet, "none" = none)
 *   2. own assignments in the SharedPreferences "keys": k<code> = target, t<code> = type, n<code> = name
 *
 * Actions per button: press (k<code>), press twice (d<code>), hold (l<code>).
 * Press twice / hold only exist as own assignments; without them the button reacts immediately.
 * Both can be switched off globally (prefs "double_on"/"long_on", default off = everything reacts
 * immediately); while off, their assignments are kept but not applied.
 * Timing: prefs "double_ms" (default 350) and "long_ms" (default 500).
 */
final class KeyMap {
    static final String TAG = "RemoteButtons";

    static final String HOME = "@home";
    static final String NONE = "@none";
    static final String SYSTEM = "@system";
    static final String EPG = "@epg";
    static final String HA = "@ha";

    /** Actions of a button. */
    static final int SINGLE = 0, DOUBLE = 1, LONG = 2;
    private static final String[] PREFIX = {"k", "d", "l"};
    private static final String[] ACTION_NAME = {"single", "double", "long"};
    static final String LIVE_TV = "@livetv";
    /** Default Home Assistant address; can be changed on the device (pref "ha_url"). */
    static final String HA_URL = "http://homeassistant.local:8123";
    static final String TV_SETTINGS_PKG = "com.android.tv.settings";
    static final String TV_SETTINGS = TV_SETTINGS_PKG + "/com.android.tv.settings.MainSettings";

    /** Known live TV apps, used if the profile names none (first installed one wins). */
    static final String[] KNOWN_LIVE_TV = {
            "com.mediatek.wwtv.tvcenter/com.mediatek.wwtv.tvcenter.nav.TurnkeyUiMainActivity",
            "com.google.android.tv/com.android.tv.MainActivity",
            "com.android.tv/com.android.tv.MainActivity",
    };

    /** Submenus of the TV settings (AOSP TvSettings, partly vendor-specific) {name, activity}. */
    static final Object[][] TV_SETTINGS_PAGES = {
            {R.string.page_network, ".connectivity.NetworkActivity"},
            {R.string.page_picture, ".partnercustomizer.picture.PictureActivity"},
            {R.string.page_sound, ".device.sound.SoundActivity"},
            {R.string.page_display_sound, ".device.displaysound.DisplaySoundActivity"},
            {R.string.page_timer, ".partnercustomizer.timer.TimerActivity"},
            {R.string.page_energy, ".device.eco.EnergyModesActivity"},
            {R.string.page_apps, ".device.apps.AppsActivity"},
            {R.string.page_all_apps, ".device.apps.AllAppsActivity"},
            {R.string.page_accessory, ".accessories.AddAccessoryActivity"},
            {R.string.page_date, ".system.DateTimeActivity"},
            {R.string.page_language, ".system.LanguageActivity"},
            {R.string.page_keyboard, ".inputmethod.KeyboardActivity"},
            {R.string.page_a11y, ".oemlink.AccessibilitySettingsActivity"},
            {R.string.page_captions, ".system.CaptionSetupActivity"},
            {R.string.page_ambient, "com.google.android.tv.settings.AmbientActivity"},
            {R.string.page_accounts, "com.google.android.tv.settings.AccountActivity"},
            {R.string.page_security, ".system.SecurityActivity"},
            {R.string.page_storage, ".device.StorageResetActivity"},
            {R.string.page_about, ".about.AboutActivity"},
            {R.string.page_dev, ".system.development.DevelopmentActivity"},
    };

    /** "package/class" of a TV settings submenu. */
    static String tvSettingsPage(String cls) {
        return TV_SETTINGS_PKG + "/" + (cls.startsWith(".") ? TV_SETTINGS_PKG + cls : cls);
    }

    private KeyMap() {}

    // ------------------------------------------------------------------ Profile

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("keys", Context.MODE_PRIVATE);
    }

    /** Have the profiles been chosen yet (including "none")? */
    static boolean profileChosen(Context c) {
        migrate(c);
        return prefs(c).contains("remote");
    }

    private static Profiles.Profile cached;
    private static String cachedKey;
    private static Profiles.Db cachedDb;

    /** Active combination of remote and TV profile, or null if neither is set. */
    static Profiles.Profile profile(Context c) {
        migrate(c);
        SharedPreferences p = prefs(c);
        String key = p.getString("remote", null) + "|" + p.getString("tv", null);
        Profiles.Db db = Profiles.db(c);
        synchronized (KeyMap.class) {
            if (key.equals(cachedKey) && db == cachedDb) return cached;
            Profiles.Remote r = Profiles.remote(c, p.getString("remote", null));
            Profiles.Tv t = Profiles.tv(c, p.getString("tv", null));
            cached = r == null && t == null ? null : new Profiles.Profile(r, t);
            cachedKey = key;
            cachedDb = db;
            return cached;
        }
    }

    /** Sets the remote and/or TV profile (null = leave unchanged, unknown id = "none"). */
    static void setProfiles(Context c, String remote, String tv) {
        SharedPreferences.Editor ed = prefs(c).edit();
        if (remote != null) ed.putString("remote", Profiles.remote(c, remote) != null ? remote : "none");
        if (tv != null) ed.putString("tv", Profiles.tv(c, tv) != null ? tv : "none");
        ed.apply();
    }

    /** Uses the detected remote and TV profiles ("none" where nothing matches). */
    static void setDetected(Context c) {
        Profiles.Tv tv = Profiles.detectTv(c);
        Profiles.Remote r = Profiles.detectRemote(c, tv);
        setProfiles(c, r != null ? r.id : "none", tv != null ? tv.id : "none");
    }

    /**
     * Sets profiles by an id: a remote or TV profile id, an old schema-1 profile id (both parts),
     * "auto" (detected) or "none".
     */
    static void setProfileById(Context c, String id) {
        if ("auto".equals(id)) { setDetected(c); return; }
        if ("none".equals(id)) { setProfiles(c, "none", "none"); return; }
        String[] m = Profiles.legacy(c, id);
        if (m[0] != null || m[1] != null) { // old combined profile: both parts
            setProfiles(c, m[0] != null ? m[0] : "none", m[1] != null ? m[1] : "none");
            return;
        }
        if (Profiles.remote(c, id) != null) setProfiles(c, id, null);
        else if (Profiles.tv(c, id) != null) setProfiles(c, null, id);
        else setProfiles(c, "none", "none");
    }

    /** Replaces the profile choice of app versions up to 1.6 ("profile") by remote and TV profile. */
    private static void migrate(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("profile")) return;
        String old = p.getString("profile", "none");
        p.edit().remove("profile").apply();
        if (!p.contains("remote")) setProfileById(c, old);
    }

    /** Keys that are never intercepted: basic functions, navigation and profile-specific keys. */
    static boolean protectedKey(Context c, int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                Profiles.Profile p = profile(c);
                if (p != null) for (int k : p.protect) if (k == code) return true;
                return false;
        }
    }

    // ------------------------------------------------------------------ Buttons

    static final class Entry {
        final int code;
        final String name;
        /** Default target (profile); SYSTEM for buttons taught by the user. */
        final String def;
        /** Belongs to the profile (cannot be removed). */
        final boolean builtin;
        Entry(int code, String name, String def, boolean builtin) {
            this.code = code; this.name = name; this.def = def; this.builtin = builtin;
        }
    }

    /** All buttons: those of the profile (in its order), then the ones taught by the user. */
    static List<Entry> entries(Context c) {
        SharedPreferences p = prefs(c);
        Profiles.Profile prof = profile(c);
        List<Integer> codes = new ArrayList<>();
        if (prof != null) for (Profiles.Key k : prof.keys) codes.add(k.code);
        TreeSet<Integer> added = new TreeSet<>();
        for (String k : p.getAll().keySet()) {
            if (k.length() > 1 && "ktdl".indexOf(k.charAt(0)) >= 0) {
                try { added.add(Integer.parseInt(k.substring(1))); } catch (NumberFormatException ignored) { }
            }
        }
        for (int code : added) if (!codes.contains(code)) codes.add(code);
        List<Entry> out = new ArrayList<>();
        for (int code : codes) {
            Profiles.Key k = prof != null ? prof.key(code) : null;
            out.add(new Entry(code, name(c, code), k != null ? k.def : SYSTEM, k != null));
        }
        return out;
    }

    static boolean isBuiltin(Context c, int code) {
        Profiles.Profile p = profile(c);
        return p != null && p.key(code) != null;
    }

    /** Target for a keycode, or null if the key should be left alone. */
    static String target(Context c, int code) {
        String t = prefs(c).getString("k" + code, null);
        if (t == null) {
            Profiles.Profile p = profile(c);
            Profiles.Key k = p != null ? p.key(code) : null;
            t = k != null ? k.def : null;
        }
        return t == null || SYSTEM.equals(t) ? null : t;
    }

    /**
     * Remembers where a taught key came from, for device reports: s<code> = scan code,
     * r<code> = "name|vendorId|productId" of the input device (the remote).
     */
    static void rememberSource(Context c, KeyEvent e) {
        SharedPreferences.Editor ed = prefs(c).edit().putInt("s" + e.getKeyCode(), e.getScanCode());
        android.view.InputDevice dev = e.getDevice();
        if (dev != null) ed.putString("r" + e.getKeyCode(), dev.getName() + "|" + dev.getVendorId() + "|" + dev.getProductId());
        ed.apply();
    }

    /** Pref key of an action: k/d/l + keycode. */
    static String prefKey(int code, int action) { return PREFIX[action] + code; }

    /** Target for press twice / hold, or null (unassigned or switched off globally). */
    static String extra(Context c, int code, int action) {
        if (!enabled(c, action)) return null;
        return prefs(c).getString(prefKey(code, action), null);
    }

    /** Is the action switched on globally? (A single press always is.) */
    static boolean enabled(Context c, int action) {
        if (action == SINGLE) return true;
        return prefs(c).getBoolean(action == DOUBLE ? "double_on" : "long_on", false);
    }

    /** Target of an action; null = don't intercept (press) or unassigned (press twice / hold). */
    static String target(Context c, int code, int action) {
        return action == SINGLE ? target(c, code) : extra(c, code, action);
    }

    static String actionName(int action) { return ACTION_NAME[action]; }

    static int doubleMs(Context c) { return prefs(c).getInt("double_ms", 350); }

    static int longMs(Context c) { return prefs(c).getInt("long_ms", 500); }

    /** Button type: own (t<code>), otherwise from the profile, otherwise null. */
    static ButtonTypes.Type type(Context c, int code) {
        String id = prefs(c).getString("t" + code, null);
        if (id == null) {
            Profiles.Profile p = profile(c);
            Profiles.Key k = p != null ? p.key(code) : null;
            id = k != null ? k.type : null;
        }
        return ButtonTypes.byId(id);
    }

    /** Display name: own name, otherwise profile name, otherwise type name, otherwise keycode. */
    static String name(Context c, int code) {
        String n = prefs(c).getString("n" + code, null);
        if (n != null) return n;
        Profiles.Profile p = profile(c);
        Profiles.Key k = p != null ? p.key(code) : null;
        String own = k != null ? k.name() : null;
        if (own != null && prefs(c).getString("t" + code, null) == null) return own;
        ButtonTypes.Type t = type(c, code);
        return t != null && !"other".equals(t.id) ? c.getString(t.name) : label(code);
    }

    static String label(int code) {
        String s = KeyEvent.keyCodeToString(code);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }

    // ------------------------------------------------------------------ Live TV

    /** Live TV activity of this device (package/class) or null. */
    static String liveTv(Context c) {
        Profiles.Profile p = profile(c);
        if (p != null && p.liveTv != null && startable(c, p.liveTv)) return p.liveTv;
        for (String t : KNOWN_LIVE_TV) if (startable(c, t)) return t;
        return null;
    }

    /** Can the program guide be opened (profile with guide support)? */
    static boolean epgSupported(Context c) {
        Profiles.Profile p = profile(c);
        return p != null && p.epg && liveTv(c) != null;
    }

    static boolean startable(Context c, String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) return false;
        try {
            ActivityInfo ai = c.getPackageManager().getActivityInfo(cn, 0);
            return ai.exported && ai.permission == null && ai.isEnabled() && ai.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** VIEW on the programs URI: the live TV app then opens (or closes) its program guide. */
    static Intent epgIntent(Context c) {
        String tv = liveTv(c);
        if (tv == null) return null;
        Intent i = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.Programs.CONTENT_URI);
        i.setComponent(ComponentName.unflattenFromString(tv));
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ------------------------------------------------------------------ Running targets

    /** Intent or deep link target ("intent:..." or "scheme:...", but not an http(s) webhook)? */
    static boolean isUri(String t) {
        return t.matches("[A-Za-z][A-Za-z0-9+.-]*:.*") && !t.startsWith("http://") && !t.startsWith("https://");
    }

    static Intent intentFor(Context c, String t) {
        Intent i;
        if (EPG.equals(t)) {
            return epgIntent(c);
        } else if (HOME.equals(t)) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        } else if (LIVE_TV.equals(t)) {
            String tv = liveTv(c);
            if (tv == null) return null;
            i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(ComponentName.unflattenFromString(tv));
        } else if (t.startsWith("intent:")) {
            try {
                i = Intent.parseUri(t, Intent.URI_INTENT_SCHEME);
            } catch (java.net.URISyntaxException e) {
                Log.w(TAG, "Invalid intent: " + t);
                return null;
            }
            // the intent's own launchFlags apply; otherwise start it like a submenu
            return i.getFlags() != 0 ? i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : subpage(i);
        } else if (isUri(t)) {
            return subpage(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(t)));
        } else if (t.indexOf('/') > 0) {
            Intent m = new Intent(Intent.ACTION_MAIN);
            m.setComponent(ComponentName.unflattenFromString(t));
            return subpage(m);
        } else {
            PackageManager pm = c.getPackageManager();
            i = pm.getLeanbackLaunchIntentForPackage(t);
            if (i == null) i = pm.getLaunchIntentForPackage(t);
            if (i == null) return null;
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /**
     * A specific page of an app: if the app is already running, the page opens in its task
     * (CLEAR_TOP: if the page is already there, everything above it is closed). RESET_TASK_IF_NEEDED
     * would only bring the existing task to the front without opening the page.
     */
    private static Intent subpage(Intent i) {
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static String haUrl(Context c) {
        return prefs(c).getString("ha_url", HA_URL);
    }

    /** Webhook ID for Home Assistant: tv_remote_<button>, with _double/_long for press twice / hold. */
    static String webhookId(int code, int action) {
        return "tv_remote_" + label(code).toLowerCase(java.util.Locale.ROOT)
                + (action == SINGLE ? "" : "_" + ACTION_NAME[action]);
    }

    /** URL of a webhook target, or null if the target is not a webhook. */
    static String webhookUrl(Context c, String t, int code, int action) {
        if (HA.equals(t)) return haUrl(c).replaceAll("/+$", "") + "/api/webhook/" + webhookId(code, action);
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        return null;
    }

    /** Runs the target of a button (app, activity, home screen or webhook). */
    static void run(Context c, String t, int code) { run(c, t, code, SINGLE, false); }

    static void run(Context c, String t, int code, int action, boolean feedback) {
        String url = webhookUrl(c, t, code, action);
        if (url != null) Webhook.post(c, url, code, action, feedback);
        else launch(c, t);
    }

    static void launch(Context c, String t) {
        if (NONE.equals(t)) return;
        Intent i = intentFor(c, t);
        if (i == null) {
            Log.w(TAG, "Target not available: " + t);
            return;
        }
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            Log.e(TAG, "Start failed: " + t, e);
        }
    }
}
