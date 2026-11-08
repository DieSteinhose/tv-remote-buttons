package de.diesteinhose.tvremotebuttons;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Device profiles in two layers, combined by the app:
 *   - remote profiles: the buttons of a remote (keycode, type, default target), recognized by its
 *     input devices (name / vendor / product), optionally limited to some TV profiles,
 *   - TV profiles: live TV app, program guide and TV-specific keys, recognized by brand and hardware.
 * They are not compiled into the app but loaded from profiles/devices.json in the GitHub
 * repository, so new TVs and remotes need no new app version:
 *   1. the cached copy in the app's files directory (last successful download),
 *   2. otherwise the copy bundled in the APK (assets/devices.json),
 *   3. in the background the current file is fetched from GitHub and replaces the cache.
 * (profiles/profiles.json is generated from it for app versions up to 1.6.)
 *
 * On first start the detected remote and TV profiles are recommended.
 *
 * Find keycodes for a new profile with
 *   adb shell setprop log.tag.RemoteButtons DEBUG; adb logcat -s RemoteButtons
 * or use "Upload buttons" in the app, which maps all buttons and proposes a profile.
 */
final class Profiles {
    static final String SOURCE_URL =
            "https://raw.githubusercontent.com/DieSteinhose/tv-remote-buttons/main/profiles/devices.json";
    /** Profile file format understood by this app version. */
    static final int SCHEMA = 2;
    private static final String CACHE = "devices.json";
    private static final long REFRESH_MS = 60 * 60 * 1000L;

    private Profiles() {}

    static final class Key {
        final int code;
        final String type;
        /** Default target (see KeyMap); SYSTEM = factory behavior. */
        final String def;
        /** Own name per language ("en", "de", ...) instead of the type name; may be empty. */
        final Map<String, String> names;

        Key(int code, String type, String def, Map<String, String> names) {
            this.code = code; this.type = type; this.def = def; this.names = names;
        }

        /** Own name in the current language (falls back to English), or null. */
        String name() { return localized(names); }
    }

    /** An input device rule: name prefix and/or vendor/product id (-1 = any). */
    static final class Input {
        final String name;
        final int vendor, product;

        Input(String name, int vendor, int product) { this.name = name; this.vendor = vendor; this.product = product; }

        boolean matches(InputDevice d) { return matches(d.getName(), d.getVendorId(), d.getProductId()); }

        boolean matches(String devName, int devVendor, int devProduct) {
            if (name == null && vendor < 0 && product < 0) return false;
            return (name == null || (devName != null && devName.startsWith(name)))
                    && (vendor < 0 || devVendor == vendor) && (product < 0 || devProduct == product);
        }
    }

    /** Remote profile: the buttons of a remote. Recognized by its input devices. */
    static final class Remote {
        final String id, title;
        final Key[] keys;
        /** Keys of this remote that are never intercepted (e.g. a release marker). */
        final int[] protect;
        final Input[] inputs;
        /** Only for these TV profiles (the TV software turns scan codes into keycodes); empty = any. */
        final String[] tvs;
        /** Ids of schema-1 profiles this remote replaces (for migrating the saved choice). */
        final String[] legacy;

        Remote(String id, String title, Key[] keys, int[] protect, Input[] inputs, String[] tvs, String[] legacy) {
            this.id = id; this.title = title; this.keys = keys; this.protect = protect; this.inputs = inputs;
            this.tvs = tvs; this.legacy = legacy;
        }

        /** Is one of its input devices connected? */
        boolean connected(Context c) {
            for (InputDevice d : inputDevices(c)) for (Input i : inputs) if (i.matches(d)) return true;
            return false;
        }

        boolean fits(String tvId) {
            if (tvs.length == 0) return true;
            for (String t : tvs) if (t.equals(tvId)) return true;
            return false;
        }
    }

    /** TV profile: live TV app, program guide and TV-specific keys. Recognized by brand and hardware. */
    static final class Tv {
        final String id, title;
        /** Live TV activity (package/class), or null (then known apps are searched). */
        final String liveTv;
        /** Program guide via VIEW on the programs URI sent to the live TV app (MediaTek). */
        final boolean epg;
        final int[] protect;
        final String[] brands;
        final String hardware;
        final String[] legacy;

        Tv(String id, String title, String liveTv, boolean epg, int[] protect, String[] brands, String hardware,
           String[] legacy) {
            this.id = id; this.title = title; this.liveTv = liveTv; this.epg = epg; this.protect = protect;
            this.brands = brands; this.hardware = hardware; this.legacy = legacy;
        }

        boolean matches() {
            if (brands.length == 0 && hardware == null) return false;
            boolean b = brands.length == 0;
            for (String x : brands) b |= x.equalsIgnoreCase(Build.BRAND) || x.equalsIgnoreCase(Build.MANUFACTURER);
            return b && (hardware == null || Build.HARDWARE.toLowerCase(Locale.ROOT).startsWith(hardware));
        }
    }

    /** The active combination of a remote and a TV profile; either part may be null. */
    static final class Profile {
        final Remote remote;
        final Tv tv;
        final Key[] keys;
        final String liveTv;
        final boolean epg;
        final int[] protect;

        Profile(Remote remote, Tv tv) {
            this.remote = remote; this.tv = tv;
            keys = remote != null ? remote.keys : new Key[0];
            liveTv = tv != null ? tv.liveTv : null;
            epg = tv != null && tv.epg;
            int[] a = remote != null ? remote.protect : new int[0], b = tv != null ? tv.protect : new int[0];
            protect = new int[a.length + b.length];
            System.arraycopy(a, 0, protect, 0, a.length);
            System.arraycopy(b, 0, protect, a.length, b.length);
        }

        Key key(int code) {
            for (Key k : keys) if (k.code == code) return k;
            return null;
        }
    }

    /** All profiles usable by this app version. */
    static final class Db {
        final List<Remote> remotes;
        final List<Tv> tvs;
        Db(List<Remote> remotes, List<Tv> tvs) { this.remotes = remotes; this.tvs = tvs; }
        int size() { return remotes.size() + tvs.size(); }
    }

    private static volatile Db db;

    /** The profiles (loaded once, replaced after a download). */
    static Db db(Context c) {
        Db d = db;
        if (d == null) {
            synchronized (Profiles.class) {
                if (db == null) db = loadLocal(c.getApplicationContext());
                d = db;
            }
        }
        return d;
    }

    static Remote remote(Context c, String id) {
        if (id != null) for (Remote r : db(c).remotes) if (r.id.equals(id)) return r;
        return null;
    }

    static Tv tv(Context c, String id) {
        if (id != null) for (Tv t : db(c).tvs) if (t.id.equals(id)) return t;
        return null;
    }

    /** TV profile matching this TV, or null. */
    static Tv detectTv(Context c) {
        for (Tv t : db(c).tvs) if (t.matches()) return t;
        return null;
    }

    /** Remote profile of a connected remote that fits the TV profile (specific ones first), or null. */
    static Remote detectRemote(Context c, Tv tv) {
        Remote any = null;
        for (Remote r : db(c).remotes) {
            if (!r.connected(c) || !r.fits(tv != null ? tv.id : null)) continue;
            if (r.tvs.length > 0) return r;
            if (any == null) any = r;
        }
        return any;
    }

    /**
     * Remote profile for a key press: the one whose input devices match the device the key came
     * from ("name|vendor|product", see KeyMap.rememberSource), preferring profiles that know the
     * key and profiles made for this TV. Null if none matches.
     */
    static Remote identify(Context c, String source, int code, Tv tv) {
        if (source == null) return null;
        String[] d = source.split("\\|", -1);
        int vendor = -1, product = -1;
        try {
            if (d.length >= 3) { vendor = Integer.parseInt(d[1]); product = Integer.parseInt(d[2]); }
        } catch (NumberFormatException ignored) { }
        Remote best = null;
        int bestScore = 0;
        for (Remote r : db(c).remotes) {
            if (!r.fits(tv != null ? tv.id : null)) continue;
            boolean dev = false;
            for (Input i : r.inputs) dev |= i.matches(d[0], vendor, product);
            if (!dev) continue;
            boolean knows = false;
            for (Key k : r.keys) knows |= k.code == code;
            int score = 1 + (knows ? 2 : 0) + (r.tvs.length > 0 ? 1 : 0);
            if (score > bestScore) { best = r; bestScore = score; }
        }
        return best;
    }

    /** {remote id, TV id} that replace a schema-1 profile id (null entries if unknown). */
    static String[] legacy(Context c, String oldId) {
        String r = null, t = null;
        for (Remote x : db(c).remotes) for (String l : x.legacy) if (l.equals(oldId) && r == null) r = x.id;
        for (Tv x : db(c).tvs) for (String l : x.legacy) if (l.equals(oldId) && t == null) t = x.id;
        return new String[]{r, t};
    }

    /**
     * Fetches the current profiles from GitHub in the background (at most once per hour unless
     * forced). onChange runs on the main thread if the profiles changed.
     */
    static void refresh(Context ctx, boolean force, Runnable onChange) {
        Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        long last = KeyMap.prefs(c).getLong("profiles_fetched", 0);
        if (!force && now - last < REFRESH_MS) return;
        update(c, (changed, err) -> { if (changed && onChange != null) onChange.run(); });
    }

    interface Done { void done(boolean changed, String error); }

    /** Fetches the profiles now; done runs on the main thread. */
    static void update(Context ctx, Done done) {
        Context c = ctx.getApplicationContext();
        KeyMap.prefs(c).edit().putLong("profiles_fetched", System.currentTimeMillis()).apply();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean changed = false;
            String error = null;
            try {
                String json = download(SOURCE_URL);
                Db parsed = parse(json, versionCode(c));
                File cache = new File(c.getFilesDir(), CACHE);
                String old = cache.exists() ? new String(readAll(new FileInputStream(cache)), StandardCharsets.UTF_8) : null;
                if (!json.equals(old)) {
                    try (FileOutputStream out = new FileOutputStream(cache)) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    db = parsed;
                    changed = true;
                    Log.i(KeyMap.TAG, "Profiles updated: " + parsed.size());
                }
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(KeyMap.TAG, "Profiles not updated: " + e);
            }
            boolean ch = changed;
            String err = error;
            main.post(() -> done.done(ch, err));
        }, "profiles").start();
    }

    private static Db loadLocal(Context c) {
        int app = versionCode(c);
        File cache = new File(c.getFilesDir(), CACHE);
        if (cache.exists()) {
            try (InputStream in = new FileInputStream(cache)) {
                return parse(new String(readAll(in), StandardCharsets.UTF_8), app);
            } catch (Exception e) {
                Log.w(KeyMap.TAG, "Cached profiles unusable: " + e);
            }
        }
        try (InputStream in = c.getAssets().open(CACHE)) {
            return parse(new String(readAll(in), StandardCharsets.UTF_8), app);
        } catch (Exception e) {
            Log.e(KeyMap.TAG, "Bundled profiles unusable", e);
            return new Db(Collections.emptyList(), Collections.emptyList());
        }
    }

    /** Parses devices.json; entries needing a newer app ("minApp") are skipped. */
    static Db parse(String json, int appVersionCode) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema", 0) != SCHEMA) throw new IllegalStateException("schema " + root.optInt("schema"));
        List<Remote> remotes = new ArrayList<>();
        JSONArray ra = root.optJSONArray("remotes");
        for (int i = 0; ra != null && i < ra.length(); i++) {
            JSONObject p = ra.getJSONObject(i);
            if (p.optInt("minApp", 0) > appVersionCode) continue;
            JSONObject m = p.optJSONObject("match");
            if (m == null) m = new JSONObject();
            JSONArray in = m.optJSONArray("inputs");
            Input[] inputs = new Input[in == null ? 0 : in.length()];
            for (int k = 0; k < inputs.length; k++) {
                JSONObject o = in.getJSONObject(k);
                inputs[k] = new Input(o.has("name") ? o.getString("name") : null,
                        hex(o.optString("vendor", null)), hex(o.optString("product", null)));
            }
            remotes.add(new Remote(p.getString("id"), p.optString("title", p.getString("id")), keys(p),
                    codes(p.optJSONArray("protect")), inputs, strings(m.optJSONArray("tvs")),
                    strings(p.optJSONArray("legacy"))));
        }
        List<Tv> tvs = new ArrayList<>();
        JSONArray ta = root.optJSONArray("tvs");
        for (int i = 0; ta != null && i < ta.length(); i++) {
            JSONObject p = ta.getJSONObject(i);
            if (p.optInt("minApp", 0) > appVersionCode) continue;
            JSONObject m = p.optJSONObject("match");
            if (m == null) m = new JSONObject();
            tvs.add(new Tv(p.getString("id"), p.optString("title", p.getString("id")),
                    p.optString("liveTv", null), p.optBoolean("epg", false), codes(p.optJSONArray("protect")),
                    strings(m.optJSONArray("brand")),
                    m.has("hardware") ? m.getString("hardware").toLowerCase(Locale.ROOT) : null,
                    strings(p.optJSONArray("legacy"))));
        }
        return new Db(remotes, tvs);
    }

    private static Key[] keys(JSONObject p) throws Exception {
        JSONArray ks = p.optJSONArray("keys");
        List<Key> keys = new ArrayList<>();
        for (int k = 0; ks != null && k < ks.length(); k++) {
            JSONObject o = ks.getJSONObject(k);
            int code = keyCode(o.has("key") ? o.get("key") : o.get("code"));
            if (code <= 0) continue;
            Map<String, String> names = new HashMap<>();
            Object n = o.opt("name");
            if (n instanceof String) names.put("en", (String) n);
            else if (n instanceof JSONObject) {
                JSONObject no = (JSONObject) n;
                for (java.util.Iterator<String> it = no.keys(); it.hasNext(); ) {
                    String lang = it.next();
                    names.put(lang, no.getString(lang));
                }
            }
            keys.add(new Key(code, o.optString("type", "other"), o.optString("default", KeyMap.SYSTEM), names));
        }
        return keys.toArray(new Key[0]);
    }

    private static int[] codes(JSONArray a) throws Exception {
        int[] out = new int[a == null ? 0 : a.length()];
        for (int k = 0; k < out.length; k++) out[k] = keyCode(a.get(k));
        return out;
    }

    private static String[] strings(JSONArray a) throws Exception {
        String[] out = new String[a == null ? 0 : a.length()];
        for (int k = 0; k < out.length; k++) out[k] = a.getString(k);
        return out;
    }

    /** "STEM_1", "KEYCODE_STEM_1" or a number -> keycode (0 if unknown). */
    private static int keyCode(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        String s = String.valueOf(v);
        // Vendor keycodes (e.g. 811) are above KeyEvent.getMaxKeyCode(), which keyCodeFromString rejects
        if (s.matches("\\d+")) return Integer.parseInt(s);
        return KeyEvent.keyCodeFromString(s.startsWith("KEYCODE_") ? s : "KEYCODE_" + s);
    }

    private static int hex(String s) {
        if (s == null || s.isEmpty()) return -1;
        return s.startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
    }

    static String localized(Map<String, String> names) {
        if (names == null || names.isEmpty()) return null;
        String n = names.get(Locale.getDefault().getLanguage());
        if (n == null) n = names.get("en");
        return n != null ? n : names.values().iterator().next();
    }

    /** All real (non-virtual) input devices that send keys: remotes, IR receiver, keypad. */
    static List<InputDevice> inputDevices(Context c) {
        List<InputDevice> out = new ArrayList<>();
        InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d != null && !d.isVirtual() && d.supportsSource(InputDevice.SOURCE_KEYBOARD)) out.add(d);
        }
        return out;
    }

    static int versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String download(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(15000);
        try {
            if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
            return new String(readAll(con.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    /** "CHiQ GTVU65 (AI PONT)" */
    static String deviceName() {
        String brand = Build.BRAND == null || Build.BRAND.isEmpty() ? Build.MANUFACTURER : Build.BRAND;
        String model = tvModel();
        String name = model != null ? model
                : Build.PRODUCT != null && !Build.PRODUCT.equalsIgnoreCase(Build.MODEL) ? Build.PRODUCT : "";
        return (brand + " " + name).trim() + " (" + Build.MODEL + ")";
    }

    /**
     * System properties in which vendors keep the model number of the TV (Build.MODEL is often
     * just the platform, e.g. "Philips Google TV TA1"). Never the user-editable device name.
     */
    private static final String[] MODEL_PROPS = {
            "persist.sys.set_type",              // Philips / TP Vision, e.g. 65OLED888/12
            "persist.odm.tpv.sys.ACR_ModName",   // Philips / TP Vision
    };

    /** Model number of the TV from vendor properties, or null. */
    static String tvModel() {
        for (String prop : MODEL_PROPS) {
            String v = sysProp(prop);
            if (v != null && v.matches("[A-Za-z0-9][A-Za-z0-9 ./_+-]{2,39}")) return v.trim();
        }
        return null;
    }

    private static String sysProp(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v == null || v.toString().isEmpty() ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
