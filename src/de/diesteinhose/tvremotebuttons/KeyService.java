package de.diesteinhose.tvremotebuttons;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Intercepts assigned buttons before the system passes them to their factory targets.
 * All other keys pass through unchanged (return false).
 *
 * Log every key (to find keycodes):
 *   adb shell setprop log.tag.RemoteButtons DEBUG ; adb logcat -s RemoteButtons
 */
public class KeyService extends AccessibilityService {

    /** Set by the settings screen: capture the next key instead of running it. */
    interface Capture { void onKey(int keyCode); }
    static volatile Capture capture;
    /** Keep capturing after a key (mapping wizard); keys within 400 ms of a capture are ignored. */
    static volatile boolean captureAll;
    private long lastCapture;
    /** Uptime of the last captured key; the settings screen uses it to return to the front. */
    static volatile long lastCaptureAt;
    /** App that came to the front right after the last capture (the button's factory function). */
    static volatile String openedAfterCapture;
    static volatile boolean running;
    private int capturedCode = -1;

    // Foreground app (from window changes) - for the two-step program guide start
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String fgPkg;
    private long epgPendingUntil;

    @Override
    protected void onServiceConnected() {
        running = true;
        Profiles.refresh(this, false, null);
        Log.i(KeyMap.TAG, "Service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (Log.isLoggable(KeyMap.TAG, Log.DEBUG)) {
            Log.d(KeyMap.TAG, KeyEvent.keyCodeToString(code) + " (" + code + ") scan=0x"
                    + Integer.toHexString(e.getScanCode()) + (down ? " DOWN" : " UP")
                    + " rep=" + e.getRepeatCount() + " dev=" + e.getDeviceId() + " t=" + e.getEventTime());
        }

        if (KeyMap.protectedKey(this, code)) return false;

        Capture cap = capture;
        if (cap != null && down) {
            boolean all = captureAll;
            // repeats, and trailing markers right after a captured key (e.g. the CHiQ release marker)
            if (e.getRepeatCount() > 0 || (all && e.getEventTime() - lastCapture < 400)) return true;
            if (!all) capture = null;
            lastCapture = e.getEventTime();
            lastCaptureAt = SystemClock.uptimeMillis();
            openedAfterCapture = null;
            capturedCode = code;
            Log.i(KeyMap.TAG, "Captured " + KeyMap.label(code));
            KeyMap.rememberSource(this, e);
            cap.onKey(code);
            return true;
        }
        if (cap != null && captureAll && code != capturedCode) return true; // stray UP while mapping
        if (code == capturedCode) { // swallow the rest of the captured key (repeats, UP)
            if (!down) capturedCode = -1;
            return true;
        }

        // Another key is still waiting for its second press: run its single press now
        if (down && waitingSecond && code != multiCode) flushSingle();

        boolean multi = KeyMap.extra(this, code, KeyMap.DOUBLE) != null || KeyMap.extra(this, code, KeyMap.LONG) != null;
        if (multi) return multi(e, code, down);

        String target = KeyMap.target(this, code);
        if (target == null) return false;
        if (down && e.getRepeatCount() == 0) perform(code, KeyMap.SINGLE);
        return true; // swallow DOWN, repeats and UP
    }

    // ---------------------------------------------------------------- Press twice / hold
    // Only for buttons with such an assignment. Single press: on release if there is no double
    // assignment, otherwise once no second press arrives within double_ms. Hold: after long_ms.

    private int multiCode = -1;
    private boolean held, longDone, secondPress, waitingSecond;
    private final Runnable longTimer = () -> {
        if (held) { longDone = true; perform(multiCode, KeyMap.LONG); }
    };
    private final Runnable singleTimer = () -> {
        waitingSecond = false;
        perform(multiCode, KeyMap.SINGLE);
    };

    private boolean multi(KeyEvent e, int code, boolean down) {
        if (down) {
            if (e.getRepeatCount() > 0) return true;
            if (waitingSecond && code == multiCode) {          // second press
                handler.removeCallbacks(singleTimer);
                waitingSecond = false;
                secondPress = true;
                held = false;
                perform(code, KeyMap.DOUBLE);
                return true;
            }
            multiCode = code;
            held = true;
            longDone = secondPress = false;
            if (KeyMap.extra(this, code, KeyMap.LONG) != null) handler.postDelayed(longTimer, KeyMap.longMs(this));
            return true;
        }
        if (code != multiCode) return true;                   // stray release
        held = false;
        handler.removeCallbacks(longTimer);
        if (secondPress || longDone) {                        // already handled
            secondPress = longDone = false;
            return true;
        }
        if (KeyMap.extra(this, code, KeyMap.DOUBLE) != null) {
            waitingSecond = true;
            handler.postDelayed(singleTimer, KeyMap.doubleMs(this));
        } else {
            perform(code, KeyMap.SINGLE);
        }
        return true;
    }

    private void flushSingle() {
        handler.removeCallbacks(singleTimer);
        waitingSecond = false;
        perform(multiCode, KeyMap.SINGLE);
    }

    /** Runs the target of an action (nothing if unassigned or factory behavior). */
    private void perform(int code, int action) {
        String t = KeyMap.target(this, code, action);
        if (t == null) return;
        Log.i(KeyMap.TAG, KeyMap.label(code) + " " + KeyMap.actionName(action) + " -> " + t);
        if (KeyMap.EPG.equals(t)) epg();
        else KeyMap.run(this, t, code, action, false);
    }

    /**
     * Program guide: if live TV is already in front, a VIEW on the programs URI opens (or closes)
     * the guide directly. Otherwise the first intent only brings live TV to the front; the second
     * one follows as soon as its window appears.
     */
    private void epg() {
        String tv = KeyMap.liveTv(this);
        if (tv == null) return;
        liveTvClass = tv.substring(tv.indexOf('/') + 1);
        boolean tvInFront = tv.substring(0, tv.indexOf('/')).equals(fgPkg);
        epgPendingUntil = tvInFront ? 0 : SystemClock.uptimeMillis() + 8000;
        KeyMap.launch(this, KeyMap.EPG);
    }

    private String liveTvClass;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = ev.getPackageName();
        if (pkg == null || "com.android.systemui".contentEquals(pkg) || getPackageName().contentEquals(pkg)) return;
        fgPkg = pkg.toString();
        if (SystemClock.uptimeMillis() - lastCaptureAt < 2500) openedAfterCapture = fgPkg;
        CharSequence cls = ev.getClassName();
        if (epgPendingUntil != 0 && cls != null && liveTvClass != null && liveTvClass.contentEquals(cls)) {
            boolean inTime = SystemClock.uptimeMillis() < epgPendingUntil;
            epgPendingUntil = 0;
            if (inTime) handler.postDelayed(() -> KeyMap.launch(this, KeyMap.EPG), 800);
        }
    }

    @Override
    public void onInterrupt() { }
}
