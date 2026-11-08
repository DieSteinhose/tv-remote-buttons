package de.diesteinhose.tvremotebuttons;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Device report: TV model, remote(s), the button mapping made in the wizard (which button is
 * Netflix, YouTube, ...) and the known buttons, uploaded anonymously so that new device profiles can
 * be added. The report deliberately contains no targets/assignments, installed apps or Home
 * Assistant address. One report per TV and day (enforced by the worker, remembered here).
 *
 * Reports go to a Cloudflare Worker (see worker/) that files them as GitHub issues; no account or
 * token is needed on the device.
 */
final class Report {
    /** Endpoint of the report worker; empty = uploading disabled in this build. */
    static final String URL = "https://tv-remote-buttons.diesteinhose.workers.dev/report";

    private Report() {}

    /** Error value passed to Result when this TV already sent a report today. */
    static final String DAILY_LIMIT = "daily_limit";

    interface Result { void done(int issue, String url, String error); }

    // ---------------------------------------------------------------- mapping and daily limit

    /** One mapped button: type, keycode, key name, scan code and the remote it came from. */
    static JSONObject mappingEntry(Context c, String type, int code) {
        try {
            JSONObject o = new JSONObject().put("type", type).put("keycode", code).put("key", KeyMap.label(code));
            int scan = KeyMap.prefs(c).getInt("s" + code, -1);
            if (scan >= 0) o.put("scan", "0x" + Integer.toHexString(scan));
            String src = KeyMap.prefs(c).getString("r" + code, null);
            if (src != null) {
                String[] p = src.split("\\|", -1);
                o.put("remote", p[0]);
                if (p.length >= 3) {
                    o.put("vendor", String.format("0x%04x", Integer.parseInt(p[1])));
                    o.put("product", String.format("0x%04x", Integer.parseInt(p[2])));
                }
            }
            return o;
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalStateException(e);
        }
    }

    static void saveMapping(Context c, JSONArray mapping) {
        KeyMap.prefs(c).edit().putString("mapping", mapping.toString()).apply();
    }

    /** Saved button mapping, or null if the wizard has not been completed. */
    static JSONArray mapping(Context c) {
        String s = KeyMap.prefs(c).getString("mapping", null);
        if (s == null) return null;
        try {
            JSONArray a = new JSONArray(s);
            return a.length() > 0 ? a : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
    }

    static boolean uploadedToday(Context c) {
        return today().equals(KeyMap.prefs(c).getString("uploaded_day", null));
    }

    static boolean enabled() { return !URL.isEmpty(); }

    static JSONObject build(Context c, String note) {
        try {
            JSONObject r = new JSONObject();
            r.put("schema", 1);
            try {
                PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                r.put("app", pi.versionName).put("appCode", pi.versionCode);
            } catch (Exception ignored) { }

            r.put("device", new JSONObject()
                    .put("brand", Build.BRAND).put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL).put("product", Build.PRODUCT).put("device", Build.DEVICE)
                    .put("hardware", Build.HARDWARE).put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT));

            Profiles.Profile p = KeyMap.profile(c);
            r.put("profile", p != null ? p.id : JSONObject.NULL);
            Profiles.Profile detected = Profiles.detect(c);
            r.put("detected", detected != null ? detected.id : JSONObject.NULL);

            JSONArray remotes = new JSONArray();
            for (String[] d : remotes(c)) {
                remotes.put(new JSONObject().put("name", d[0]).put("vendor", d[1]).put("product", d[2]));
            }
            r.put("remotes", remotes);

            JSONArray keys = new JSONArray();
            for (KeyMap.Entry e : KeyMap.entries(c)) {
                JSONObject k = new JSONObject().put("keycode", e.code).put("key", KeyMap.label(e.code))
                        .put("source", e.builtin ? "profile" : "taught");
                ButtonTypes.Type t = KeyMap.type(c, e.code);
                if (t != null) k.put("type", t.id);
                String own = KeyMap.prefs(c).getString("n" + e.code, null);
                if (own != null) k.put("name", own);
                int scan = KeyMap.prefs(c).getInt("s" + e.code, -1);
                if (scan >= 0) k.put("scan", "0x" + Integer.toHexString(scan));
                String src = KeyMap.prefs(c).getString("r" + e.code, null);
                if (src != null) k.put("remote", src.split("\\|", -1)[0]);
                keys.put(k);
            }
            r.put("keys", keys);
            JSONArray mapping = mapping(c);
            r.put("mapping", mapping != null ? mapping : new JSONArray());
            r.put("press", new JSONObject()
                    .put("double", KeyMap.enabled(c, KeyMap.DOUBLE)).put("hold", KeyMap.enabled(c, KeyMap.LONG)));
            if (note != null && !note.trim().isEmpty()) r.put("note", note.trim());
            return r;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Input devices that send keys (remotes, IR receiver, keypad): {name, vendor, product} in hex.
     * Not filtered by isExternal(): vendors often mark their Bluetooth remote as internal.
     */
    static List<String[]> remotes(Context c) {
        List<String[]> out = new ArrayList<>();
        InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d == null || d.isVirtual()) continue;
            if (!d.supportsSource(InputDevice.SOURCE_KEYBOARD)) continue;
            String[] row = {d.getName(), String.format("0x%04x", d.getVendorId()), String.format("0x%04x", d.getProductId())};
            boolean dup = false;
            for (String[] o : out) dup |= o[0].equals(row[0]) && o[1].equals(row[1]) && o[2].equals(row[2]);
            if (!dup) out.add(row);
        }
        return out;
    }

    /** Uploads the report in the background; the result is delivered on the main thread. */
    static void upload(Context ctx, JSONObject report, Result result) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int issue = 0;
            String url = null, error = null;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(URL).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(15000);
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(report.toString().getBytes(StandardCharsets.UTF_8));
                }
                int rc = con.getResponseCode();
                InputStream in = rc < 400 ? con.getInputStream() : con.getErrorStream();
                String body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                JSONObject res = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                if (rc == 429 && DAILY_LIMIT.equals(res.optString("code"))) error = DAILY_LIMIT;
                else if (rc >= 400) error = res.optString("error", "HTTP " + rc);
                else { issue = res.optInt("issue"); url = res.optString("url", null); }
                if (error == null || DAILY_LIMIT.equals(error)) {
                    KeyMap.prefs(app).edit().putString("uploaded_day", today()).apply();
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            } finally {
                if (con != null) con.disconnect();
            }
            final int i = issue;
            final String u = url, err = error;
            main.post(() -> result.done(i, u, err));
        }, "report").start();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
