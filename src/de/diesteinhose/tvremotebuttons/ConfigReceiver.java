package de.diesteinhose.tvremotebuttons;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Configuration via adb (shell/system only: the receiver requires android.permission.DUMP).
 *   Assign:          am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ei key 192 --es target @ha
 *   Default:         am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ei key 192 --es target default
 *   HA address:      am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --es ha_url http://homeassistant.local:8123
 *   Trigger button:  am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ei test 192
 *   Profiles:        am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --es profile auto   (or none, or an id)
 *                    ... --es remote chiq_tvc0gwbt --es tv chiq_mt58   (each also auto or none)
 *   Only if not set yet (for setup scripts): add --ez keep true
 *   Double/hold:     add --es action double (or long); applies to target and test
 *   Global on/off:   --ez double_on true --ez long_on true   (default: both off)
 *   Timing:          --ei double_ms 350 --ei long_ms 500
 *   Button type:     am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ei key 192 --es type free
 *   Name:            am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ei key 192 --es name "My button"
 *   Backup:          am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ez export true
 *                      (result data: all settings as base64 JSON)
 *   Restore:         am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --es import <base64 from export>
 *   Intent:          am broadcast -n de.diesteinhose.tvremotebuttons/.ConfigReceiver --ei key 192 \
 *                      --es target "'intent:#Intent;action=android.settings.WIFI_SETTINGS;end'"
 * Targets as in KeyMap: package, package/class, intent:..., scheme:..., @livetv, @home, @epg, @ha,
 * http(s)://..., @none, @system. Every broadcast returns the current configuration as result data.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i.getBooleanExtra("export", false)) {
            setResultData(exportPrefs(c));
            return;
        }
        String imp = i.getStringExtra("import");
        if (imp != null) {
            setResultData(importPrefs(c, imp));
            return;
        }
        boolean keep = i.getBooleanExtra("keep", false);
        String profile = i.getStringExtra("profile");
        if (profile != null && !(keep && KeyMap.profileChosen(c))) KeyMap.setProfileById(c, profile);
        String remote = i.getStringExtra("remote"), tv = i.getStringExtra("tv");
        if ((remote != null || tv != null) && !(keep && KeyMap.profileChosen(c))) {
            if ("auto".equals(remote)) {
                Profiles.Remote r = Profiles.detectRemote(c, Profiles.detectTv(c));
                remote = r != null ? r.id : "none";
            }
            if ("auto".equals(tv)) {
                Profiles.Tv t = Profiles.detectTv(c);
                tv = t != null ? t.id : "none";
            }
            KeyMap.setProfiles(c, remote, tv);
        }
        SharedPreferences.Editor ed = KeyMap.prefs(c).edit();
        String ha = i.getStringExtra("ha_url");
        if (ha != null) ed.putString("ha_url", ha);
        if (i.hasExtra("double_on")) ed.putBoolean("double_on", i.getBooleanExtra("double_on", false));
        if (i.hasExtra("long_on")) ed.putBoolean("long_on", i.getBooleanExtra("long_on", false));
        if (i.hasExtra("double_ms")) ed.putInt("double_ms", i.getIntExtra("double_ms", 350));
        if (i.hasExtra("long_ms")) ed.putInt("long_ms", i.getIntExtra("long_ms", 500));
        String act = i.getStringExtra("action");
        int action = "double".equals(act) ? KeyMap.DOUBLE : "long".equals(act) ? KeyMap.LONG : KeyMap.SINGLE;
        int key = i.getIntExtra("key", -1);
        String target = i.getStringExtra("target");
        String pk = KeyMap.prefKey(key, action);
        if (key >= 0 && target != null && !(keep && KeyMap.prefs(c).contains(pk))) {
            if ("default".equals(target)) ed.remove(pk);
            else ed.putString(pk, target);
        }
        String type = i.getStringExtra("type");
        if (key >= 0 && type != null) {
            if ("default".equals(type)) ed.remove("t" + key);
            else ed.putString("t" + key, type);
        }
        String name = i.getStringExtra("name");
        if (key >= 0 && name != null) ed.putString("n" + key, name);
        ed.commit();

        int test = i.getIntExtra("test", -1);
        if (test >= 0) {
            String t = KeyMap.target(c, test, action);
            Log.i(KeyMap.TAG, "Test " + KeyMap.label(test) + " " + KeyMap.actionName(action) + " -> " + t);
            if (t != null) KeyMap.run(c, t, test, action, false);
        }
        Profiles.Profile p = KeyMap.profile(c);
        boolean chosen = KeyMap.profileChosen(c);
        StringBuilder sb = new StringBuilder("device=" + Profiles.deviceName()
                + "\nremote=" + (p != null && p.remote != null ? p.remote.id : chosen ? "none" : "(not chosen yet)")
                + "\ntv=" + (p != null && p.tv != null ? p.tv.id : chosen ? "none" : "(not chosen yet)"));
        sb.append("\nha_url=").append(KeyMap.haUrl(c));
        sb.append("\ndouble ").append(KeyMap.enabled(c, KeyMap.DOUBLE) ? "on " : "off ").append(KeyMap.doubleMs(c))
          .append(" ms, long ").append(KeyMap.enabled(c, KeyMap.LONG) ? "on " : "off ").append(KeyMap.longMs(c)).append(" ms");
        for (KeyMap.Entry e : KeyMap.entries(c)) {
            sb.append('\n').append(e.code).append(' ').append(KeyMap.label(e.code)).append(" (").append(e.name).append(')');
            for (int a = KeyMap.SINGLE; a <= KeyMap.LONG; a++) {
                String t = KeyMap.target(c, e.code, a);
                if (a != KeyMap.SINGLE && t == null) continue;
                sb.append(a == KeyMap.SINGLE ? " -> " : "  | " + KeyMap.actionName(a) + " -> ")
                  .append(t == null ? KeyMap.SYSTEM : t);
                String url = t == null ? null : KeyMap.webhookUrl(c, t, e.code, a);
                if (url != null) sb.append(" [").append(url).append(']');
            }
        }
        setResultData(sb.toString());
    }

    /** All settings as base64 JSON: {"name": {"t": "s|i|l|b", "v": value}, ...}. */
    private static String exportPrefs(Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> e : KeyMap.prefs(c).getAll().entrySet()) {
                Object v = e.getValue();
                String t = v instanceof String ? "s" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                        : v instanceof Boolean ? "b" : null;
                if (t != null) o.put(e.getKey(), new org.json.JSONObject().put("t", t).put("v", v));
            }
            return android.util.Base64.encodeToString(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
        } catch (org.json.JSONException e) {
            return "error: " + e;
        }
    }

    /** Replaces all settings with an export (see exportPrefs). */
    private static String importPrefs(Context c, String b64) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(new String(android.util.Base64.decode(b64, 0),
                    java.nio.charset.StandardCharsets.UTF_8));
            SharedPreferences.Editor ed = KeyMap.prefs(c).edit().clear();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                org.json.JSONObject e = o.getJSONObject(k);
                switch (e.getString("t")) {
                    case "s": ed.putString(k, e.getString("v")); break;
                    case "i": ed.putInt(k, e.getInt("v")); break;
                    case "l": ed.putLong(k, e.getLong("v")); break;
                    case "b": ed.putBoolean(k, e.getBoolean("v")); break;
                    default: break;
                }
            }
            ed.commit();
            return "imported " + o.length() + " settings";
        } catch (Exception e) {
            return "error: " + e;
        }
    }
}
