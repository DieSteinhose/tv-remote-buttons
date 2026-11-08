package de.diesteinhose.tvremotebuttons;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends a button press as an HTTP POST (JSON) to a URL, e.g. a Home Assistant webhook. */
final class Webhook {
    private Webhook() {}

    /**
     * @param action   KeyMap.SINGLE/DOUBLE/LONG (in the JSON as "action": single/double/long)
     * @param feedback show a message on success too ("Try it" on the settings screen)
     */
    static void post(Context ctx, String url, int code, int action, boolean feedback) {
        final Context app = ctx.getApplicationContext();
        final String body = "{\"key\":\"" + KeyMap.label(code) + "\",\"keycode\":" + code
                + ",\"action\":\"" + KeyMap.actionName(action) + "\""
                + ",\"name\":\"" + json(KeyMap.name(app, code)) + "\",\"device\":\"" + json(Build.MODEL) + "\"}";
        new Thread(() -> {
            String err = null;
            int rc = -1;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(3000);
                con.setReadTimeout(5000);
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                rc = con.getResponseCode();
                Log.i(KeyMap.TAG, "Webhook " + url + " -> HTTP " + rc);
                if (rc >= 400) err = "HTTP " + rc;
            } catch (Exception e) {
                Log.w(KeyMap.TAG, "Webhook " + url + " failed: " + e);
                err = e.getClass().getSimpleName();
            } finally {
                if (con != null) con.disconnect();
            }
            final int status = rc;
            if (err != null || feedback) {
                final String msg = err != null ? app.getString(R.string.webhook_failed, err)
                        : app.getString(R.string.webhook_sent, status);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, msg, Toast.LENGTH_SHORT).show());
            }
        }, "webhook").start();
    }

    private static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
