package local.chiq.keys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Configuration via adb (shell/system only: the receiver requires android.permission.DUMP).
 *   Assign:          am broadcast -n local.chiq.keys/.ConfigReceiver --ei key 192 --es target @ha
 *   Default:         am broadcast -n local.chiq.keys/.ConfigReceiver --ei key 192 --es target default
 *   HA address:      am broadcast -n local.chiq.keys/.ConfigReceiver --es ha_url http://192.168.1.22:8123
 *   Trigger button:  am broadcast -n local.chiq.keys/.ConfigReceiver --ei test 192
 *   Profile:         am broadcast -n local.chiq.keys/.ConfigReceiver --es profile chiq_mt58   (or none, auto)
 *   Only if not set yet (for setup scripts): add --ez keep true
 *   Double/hold:     add --es action double (or long); applies to target and test
 *   Global on/off:   --ez double_on true --ez long_on true   (default: both off)
 *   Timing:          --ei double_ms 350 --ei long_ms 500
 *   Button type:     am broadcast -n local.chiq.keys/.ConfigReceiver --ei key 192 --es type free
 *   Name:            am broadcast -n local.chiq.keys/.ConfigReceiver --ei key 192 --es name "My button"
 *   Intent:          am broadcast -n local.chiq.keys/.ConfigReceiver --ei key 192 \
 *                      --es target "'intent:#Intent;action=android.settings.WIFI_SETTINGS;end'"
 * Targets as in KeyMap: package, package/class, intent:..., scheme:..., @livetv, @home, @epg, @ha,
 * http(s)://..., @none, @system. Every broadcast returns the current configuration as result data.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        boolean keep = i.getBooleanExtra("keep", false);
        String profile = i.getStringExtra("profile");
        if (profile != null && !(keep && KeyMap.profileChosen(c))) {
            Profiles.Profile det = Profiles.detect();
            KeyMap.setProfile(c, "auto".equals(profile) ? (det != null ? det.id : "none") : profile);
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
        StringBuilder sb = new StringBuilder("profile=" + (p != null ? p.id
                : KeyMap.profileChosen(c) ? "none" : "(not chosen yet)"));
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
}
