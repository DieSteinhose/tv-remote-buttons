package local.chiq.keys;

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
 * Device profiles: which buttons a remote has (keycode, type, default target) plus device-specific
 * details. They are not compiled into the app but loaded from profiles/profiles.json in the GitHub
 * repository, so new TVs and remotes need no new app version:
 *   1. the cached copy in the app's files directory (last successful download),
 *   2. otherwise the copy bundled in the APK (assets/profiles.json),
 *   3. in the background the current file is fetched from GitHub and replaces the cache.
 *
 * A profile matches by TV (brand, hardware) and by remote (input device name / vendor / product).
 * On first start the best matching profile is recommended.
 *
 * Find keycodes for a new profile with
 *   adb shell setprop log.tag.RemoteButtons DEBUG; adb logcat -s RemoteButtons
 * or use "Upload buttons" in the app, which maps all buttons and proposes a profile.
 */
final class Profiles {
    static final String SOURCE_URL =
            "https://raw.githubusercontent.com/DieSteinhose/tv-remote-buttons/main/profiles/profiles.json";
    /** Profile file format understood by this app version. */
    static final int SCHEMA = 1;
    private static final String CACHE = "profiles.json";
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

    /** A remote: matches input devices by name prefix and/or vendor/product id (-1 = any). */
    static final class Remote {
        final String name;
        final int vendor, product;

        Remote(String name, int vendor, int product) { this.name = name; this.vendor = vendor; this.product = product; }

        boolean matches(InputDevice d) {
            if (name == null && vendor < 0 && product < 0) return false;
            return (name == null || d.getName().startsWith(name))
                    && (vendor < 0 || d.getVendorId() == vendor) && (product < 0 || d.getProductId() == product);
        }
    }

    static final class Profile {
        final String id, title;
        /** Live TV activity (package/class) of this device, or null (then known apps are searched). */
        final String liveTv;
        /** Program guide via VIEW on the programs URI sent to the live TV app (MediaTek). */
        final boolean epg;
        /** Additional keys that are never intercepted. */
        final int[] protect;
        final Key[] keys;
        final String[] brands;
        final String hardware;
        final Remote[] remotes;

        Profile(String id, String title, String liveTv, boolean epg, int[] protect, Key[] keys,
                String[] brands, String hardware, Remote[] remotes) {
            this.id = id; this.title = title; this.liveTv = liveTv; this.epg = epg; this.protect = protect;
            this.keys = keys; this.brands = brands; this.hardware = hardware; this.remotes = remotes;
        }

        boolean tvMatches() {
            if (brands.length == 0 && hardware == null) return false;
            boolean b = brands.length == 0;
            for (String x : brands) b |= x.equalsIgnoreCase(Build.BRAND) || x.equalsIgnoreCase(Build.MANUFACTURER);
            return b && (hardware == null || Build.HARDWARE.toLowerCase(Locale.ROOT).startsWith(hardware));
        }

        boolean remoteMatches(Context c) {
            for (InputDevice d : inputDevices(c)) for (Remote r : remotes) if (r.matches(d)) return true;
            return false;
        }

        /** 3 = TV and remote match, 2 = only the remote, 1 = only the TV, 0 = no match. */
        int score(Context c) {
            boolean tv = tvMatches(), remote = remotes.length > 0 && remoteMatches(c);
            return tv && remote ? 3 : remote ? 2 : tv ? 1 : 0;
        }

        Key key(int code) {
            for (Key k : keys) if (k.code == code) return k;
            return null;
        }
    }

    private static volatile List<Profile> all;

    /** All profiles usable by this app version (loaded once, replaced after a download). */
    static List<Profile> all(Context c) {
        List<Profile> a = all;
        if (a == null) {
            synchronized (Profiles.class) {
                if (all == null) all = loadLocal(c.getApplicationContext());
                a = all;
            }
        }
        return a;
    }

    static Profile byId(Context c, String id) {
        if (id != null) for (Profile p : all(c)) if (p.id.equals(id)) return p;
        return null;
    }

    /** Best matching profile for this TV and its remote(s), or null. */
    static Profile detect(Context c) {
        Profile best = null;
        int bestScore = 0;
        for (Profile p : all(c)) {
            int s = p.score(c);
            if (s > bestScore) { best = p; bestScore = s; }
        }
        return best;
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
        KeyMap.prefs(c).edit().putLong("profiles_fetched", now).apply();
        new Thread(() -> {
            try {
                String json = download(SOURCE_URL);
                List<Profile> parsed = parse(json, versionCode(c));
                File cache = new File(c.getFilesDir(), CACHE);
                String old = cache.exists() ? new String(readAll(new FileInputStream(cache)), StandardCharsets.UTF_8) : null;
                if (json.equals(old)) return;
                try (FileOutputStream out = new FileOutputStream(cache)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                all = parsed;
                Log.i(KeyMap.TAG, "Profiles updated: " + parsed.size());
                if (onChange != null) new Handler(Looper.getMainLooper()).post(onChange);
            } catch (Exception e) {
                Log.w(KeyMap.TAG, "Profiles not updated: " + e);
            }
        }, "profiles").start();
    }

    private static List<Profile> loadLocal(Context c) {
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
            return Collections.emptyList();
        }
    }

    /** Parses profiles.json; profiles needing a newer app ("minApp") are skipped. */
    static List<Profile> parse(String json, int appVersionCode) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema", 1) > SCHEMA) throw new IllegalStateException("schema " + root.optInt("schema"));
        List<Profile> out = new ArrayList<>();
        JSONArray arr = root.getJSONArray("profiles");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (p.optInt("minApp", 0) > appVersionCode) continue;
            JSONObject m = p.optJSONObject("match");
            if (m == null) m = new JSONObject();
            JSONArray b = m.optJSONArray("brand");
            String[] brands = new String[b == null ? 0 : b.length()];
            for (int k = 0; k < brands.length; k++) brands[k] = b.getString(k);
            JSONArray r = m.optJSONArray("remotes");
            Remote[] remotes = new Remote[r == null ? 0 : r.length()];
            for (int k = 0; k < remotes.length; k++) {
                JSONObject o = r.getJSONObject(k);
                remotes[k] = new Remote(o.has("name") ? o.getString("name") : null,
                        hex(o.optString("vendor", null)), hex(o.optString("product", null)));
            }
            JSONArray pr = p.optJSONArray("protect");
            int[] protect = new int[pr == null ? 0 : pr.length()];
            for (int k = 0; k < protect.length; k++) protect[k] = keyCode(pr.get(k));
            JSONArray ks = p.getJSONArray("keys");
            List<Key> keys = new ArrayList<>();
            for (int k = 0; k < ks.length(); k++) {
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
            out.add(new Profile(p.getString("id"), p.optString("title", p.getString("id")),
                    p.optString("liveTv", null), p.optBoolean("epg", false), protect,
                    keys.toArray(new Key[0]), brands,
                    m.has("hardware") ? m.getString("hardware").toLowerCase(Locale.ROOT) : null, remotes));
        }
        return out;
    }

    /** "STEM_1", "KEYCODE_STEM_1" or a number -> keycode (0 if unknown). */
    private static int keyCode(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        String s = String.valueOf(v);
        return KeyEvent.keyCodeFromString(s.startsWith("KEYCODE_") || s.matches("\\d+") ? s : "KEYCODE_" + s);
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
        String name = Build.PRODUCT != null && !Build.PRODUCT.equalsIgnoreCase(Build.MODEL) ? Build.PRODUCT : "";
        return (brand + " " + name).trim() + " (" + Build.MODEL + ")";
    }
}
