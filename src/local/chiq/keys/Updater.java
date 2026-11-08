package local.chiq.keys;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updater: checks the latest GitHub release, downloads its APK straight into a
 * PackageInstaller session and lets Android ask the user to confirm the installation.
 * Updates only install if the APK is signed with the same key as the installed app.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/DieSteinhose/tv-remote-buttons/releases/latest";

    private Updater() {}

    static final class Release {
        final String version, apkUrl;
        Release(String version, String apkUrl) { this.version = version; this.apkUrl = apkUrl; }
    }

    interface Checked { void done(Release newer, String error); }
    interface Progress { void update(int percent, String error, boolean needsPermission); }

    /** Looks for a release newer than the installed version; result on the main thread. */
    static void check(Context c, Checked cb) {
        String current = versionName(c);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release newer = null;
            String error = null;
            try {
                JSONObject r = new JSONObject(Profiles.download(LATEST));
                String tag = r.getString("tag_name").replaceFirst("^v", "");
                JSONArray assets = r.getJSONArray("assets");
                String apk = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url");
                }
                if (apk != null && compare(tag, current) > 0) newer = new Release(tag, apk);
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            }
            final Release n = newer;
            final String err = error;
            main.post(() -> cb.done(n, err));
        }, "update-check").start();
    }

    /** Numeric comparison of "1.10" vs "1.9" style versions. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * Downloads and installs the release. Without the "install unknown apps" permission it opens the
     * matching settings page instead (needsPermission = true).
     */
    static void install(Context ctx, Release r, Progress p) {
        Context c = ctx.getApplicationContext();
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
            p.update(0, null, true);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            HttpURLConnection con = null;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                sessionId = pi.createSession(params);
                con = (HttpURLConnection) new URL(r.apkUrl).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(10000);
                con.setReadTimeout(30000);
                if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
                long total = con.getContentLengthLong();
                try (PackageInstaller.Session s = pi.openSession(sessionId)) {
                    try (InputStream in = con.getInputStream(); OutputStream out = s.openWrite("app.apk", 0, total)) {
                        byte[] buf = new byte[16384];
                        long done = 0;
                        int last = -1;
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != last) {
                                last = pct;
                                main.post(() -> p.update(pct, null, false));
                            }
                        }
                        s.fsync(out);
                    }
                    Intent cb = new Intent(c, Result.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(c, sessionId, cb, flags).getIntentSender());
                }
                main.post(() -> p.update(100, null, false));
            } catch (Exception e) {
                Log.w(KeyMap.TAG, "Update failed", e);
                if (sessionId >= 0) try { pi.abandonSession(sessionId); } catch (Exception ignored) { }
                String err = e.getClass().getSimpleName();
                main.post(() -> p.update(0, err, false));
            } finally {
                if (con != null) con.disconnect();
            }
        }, "update").start();
    }

    /** Receives the installer status: asks the user to confirm, or reports a failure. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(KeyMap.TAG, "Install status " + status + ": " + msg);
                Toast.makeText(c, c.getString(R.string.update_failed, msg != null ? msg : String.valueOf(status)),
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
