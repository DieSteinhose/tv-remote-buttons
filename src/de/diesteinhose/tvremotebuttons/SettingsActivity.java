package de.diesteinhose.tvremotebuttons;

import static de.diesteinhose.tvremotebuttons.Ui.*;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Settings screen for the TV (D-pad navigation), dark, two columns, English/German.
 *   Welcome:     on first start; recommends the profile matching the device.
 *   Overview:    title and status on the left; buttons with targets, press options, profile and
 *                Home Assistant on the right.
 *   Assignment:  action tabs and categories on the left, targets of the chosen category on the right.
 *   Teaching:    press a button, then choose its type (name and suggested target).
 */
public class SettingsActivity extends Activity {
    private static final int[] CATEGORIES = {R.string.cat_general, R.string.cat_tv, R.string.cat_settings,
            R.string.cat_apps, R.string.cat_sub, R.string.cat_hook, R.string.cat_intent, R.string.cat_key};
    private static final int[] CAT_ICONS = {R.drawable.ic_undo, R.drawable.ic_tv, R.drawable.ic_gear,
            R.drawable.ic_apps, R.drawable.ic_list, R.drawable.ic_bolt, R.drawable.ic_code, R.drawable.ic_edit};
    private static final int CAT_GENERAL = 0, CAT_TV = 1, CAT_SETTINGS = 2, CAT_APPS = 3, CAT_SUB = 4,
            CAT_HOOK = 5, CAT_INTENT = 6, CAT_KEY = 7;

    private Ui ui;
    /** What "Back" currently does; null = close the screen. */
    private Runnable back;
    /** Focus this button the next time the overview is built. */
    private int focusCode = -1;
    private TreeMap<String, String> appCache;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    /** Unsaved input per text field, kept when switching categories (until back on the overview). */
    private final Map<String, String> drafts = new HashMap<>();

    // State of the assignment screen
    private KeyMap.Entry entry;
    private LinearLayout pane;
    private ScrollView paneScroll;
    private final List<TextView> catViews = new ArrayList<>();
    private int cat = -1;
    /** Which action is being edited: KeyMap.SINGLE / DOUBLE / LONG. */
    private int action = KeyMap.SINGLE;
    private static final int[] ACTIONS = {R.string.act_single, R.string.act_double, R.string.act_long};
    /** Tab per action (null = action switched off globally, no tab). */
    private final TextView[] actViews = new TextView[3];
    private TextView nowView, warnView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        appCache = null; // apps may have changed
        if (back == null) showStart();
        // fetch current profiles from GitHub; rebuild the start screen if they changed
        Profiles.refresh(this, false, () -> { if (back == null) showStart(); });
    }

    private void showStart() {
        if (KeyMap.profileChosen(this)) showMain();
        else showWelcome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (KeyService.capture != null) { // cancel capturing; show the overview when coming back
            stopMapping();
            back = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (back != null) back.run();
        else super.onBackPressed();
    }

    private String s(int id, Object... args) { return getString(id, args); }

    // ------------------------------------------------------------------ Welcome and profiles

    private void showWelcome() {
        back = null;
        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.welcome)));
        left.addView(ui.headline(s(R.string.app_name)));
        TextView b = ui.body(s(R.string.welcome_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        right.addView(ui.section(s(R.string.section_device)));
        View dev = ui.row(null, R.drawable.ic_tv, C_OFF, Profiles.deviceName(),
                "Android " + Build.VERSION.RELEASE + "  ·  " + Build.HARDWARE, null, 0, () -> { });
        dev.setFocusable(false);
        right.addView(dev);

        Profiles.Profile p = Profiles.detect(this);
        View focus;
        if (p != null) {
            right.addView(ui.section(s(R.string.section_recommended)));
            focus = ui.row(null, R.drawable.ic_remote, ACCENT, s(R.string.use_profile),
                    p.title + "  ·  " + keyList(p), s(R.string.badge_recommended), ACCENT, () -> chooseProfile(p.id));
            right.addView(focus);
            right.addView(ui.row(null, R.drawable.ic_star, C_OFF, s(R.string.start_without_profile),
                    s(R.string.no_profile_sub), null, 0, () -> chooseProfile("none")));
        } else {
            right.addView(ui.note(s(R.string.no_match)));
            focus = ui.row(null, R.drawable.ic_star, ACCENT, s(R.string.start_without_profile),
                    s(R.string.no_profile_sub), null, 0, () -> chooseProfile("none"));
            right.addView(focus);
        }
        LinearLayout other = ui.row(null, R.drawable.ic_list, C_OFF, s(R.string.other_profile),
                s(R.string.other_profile_sub), null, 0, () -> showProfiles(this::showWelcome));
        ui.addChevron(other);
        right.addView(other);

        ui.setScreen(left, right);
        ui.requestFocusLater(focus);
    }

    private void showProfiles(Runnable backTo) {
        back = backTo;
        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.section_profile)));
        left.addView(ui.headline(s(R.string.choose_profile)));
        TextView b = ui.body(s(R.string.profile_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        right.addView(ui.section(s(R.string.section_profile)));
        Profiles.Profile active = KeyMap.profile(this);
        boolean chosen = KeyMap.profileChosen(this);
        View focus = null;
        for (Profiles.Profile p : Profiles.all(this)) {
            boolean on = active == p;
            String badge = on ? s(R.string.b_active) : p.score(this) > 0 ? s(R.string.badge_detected) : null;
            View r = ui.row(null, R.drawable.ic_remote, on ? ACCENT : C_SUB, p.title, keyList(p), badge, ACCENT,
                    () -> chooseProfile(p.id));
            if (on || focus == null) focus = r;
            right.addView(r);
        }
        boolean none = chosen && active == null;
        View r = ui.row(null, R.drawable.ic_star, C_OFF, s(R.string.no_profile), s(R.string.no_profile_sub),
                none ? s(R.string.b_active) : null, ACCENT, () -> chooseProfile("none"));
        if (none) focus = r;
        right.addView(r);
        ui.setScreen(left, right);
        ui.requestFocusLater(focus);
    }

    private void chooseProfile(String id) {
        KeyMap.setProfile(this, id);
        showMain();
    }

    /** "9 buttons: YouTube, Netflix, ..." */
    private String keyList(Profiles.Profile p) {
        StringBuilder sb = new StringBuilder();
        for (Profiles.Key k : p.keys) {
            ButtonTypes.Type t = ButtonTypes.byId(k.type);
            if (sb.length() > 0) sb.append(", ");
            sb.append(k.name() != null ? k.name() : t != null ? s(t.name) : KeyMap.label(k.code));
        }
        return s(R.string.profile_keys, p.keys.length, sb.toString());
    }

    // ------------------------------------------------------------------ Overview

    private void showMain() {
        back = null;
        entry = null;
        drafts.clear();
        KeyService.capture = null;
        boolean running = KeyService.running;

        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.eyebrow_remote)));
        left.addView(ui.headline(s(R.string.app_name)));
        left.addView(statusChip(running));
        left.addView(ui.body(running ? s(R.string.main_hint) : s(R.string.service_off_hint, s(R.string.app_name))));

        LinearLayout list = ui.column();
        View focus = null;
        if (!running) {
            focus = ui.row(null, R.drawable.ic_gear, RED, s(R.string.enable_service), s(R.string.enable_service_sub),
                    null, 0, this::openAccessibilitySettings);
            list.addView(focus);
        }
        list.addView(ui.section(s(R.string.section_buttons)));
        List<KeyMap.Entry> entries = KeyMap.entries(this);
        if (entries.isEmpty()) list.addView(ui.note(s(R.string.no_buttons)));
        for (KeyMap.Entry e : entries) {
            String pref = pref(e.code);
            String t = pref != null ? pref : e.def;
            Look l = look(t);
            String sub = describe(t, e.code);
            if (pref != null && e.builtin && !pref.equals(e.def)) sub += "   ·   " + s(R.string.customized);
            String dbl = KeyMap.extra(this, e.code, KeyMap.DOUBLE), lng = KeyMap.extra(this, e.code, KeyMap.LONG);
            if (dbl != null) sub += "   ·   " + s(R.string.act_double_short) + " " + describe(dbl, e.code, KeyMap.DOUBLE);
            if (lng != null) sub += "   ·   " + s(R.string.act_long_short) + ": " + describe(lng, e.code, KeyMap.LONG);
            View r = ui.row(l.icon, l.vec, l.color, e.name, sub, l.badge, l.color,
                    () -> { action = KeyMap.SINGLE; showChooser(e.code); });
            if (e.code == focusCode || focus == null) focus = r;
            list.addView(r);
        }
        View add = ui.row(null, R.drawable.ic_add, ACCENT, s(R.string.add_button), s(R.string.add_button_sub),
                null, 0, this::showCapture);
        if (focus == null) focus = add;
        list.addView(add);

        list.addView(ui.section(s(R.string.section_press)));
        LinearLayout press = ui.row(null, R.drawable.ic_play, ACCENT, s(R.string.press_row), pressSummary(),
                null, 0, () -> showPressOptions(0));
        ui.addChevron(press);
        list.addView(press);

        list.addView(ui.section(s(R.string.section_profile)));
        Profiles.Profile p = KeyMap.profile(this);
        LinearLayout prof = ui.row(null, R.drawable.ic_remote, C_SUB, p != null ? p.title : s(R.string.no_profile),
                Profiles.deviceName(), null, 0, () -> showProfiles(this::showMain));
        ui.addChevron(prof);
        list.addView(prof);

        list.addView(ui.section(s(R.string.section_ha)));
        list.addView(ui.row(null, R.drawable.ic_bolt, C_HOOK, s(R.string.ha_address), KeyMap.haUrl(this),
                null, 0, this::showHaUrl));

        list.addView(ui.section(s(R.string.section_help)));
        LinearLayout up = ui.row(null, R.drawable.ic_remote, GREEN, s(R.string.upload_row), s(R.string.upload_row_sub),
                null, 0, this::showUpload);
        ui.addChevron(up);
        list.addView(up);

        list.addView(ui.section(s(R.string.section_app)));
        list.addView(updateRow());

        ui.setScreen(left, list);
        ui.requestFocusLater(focus);
        focusCode = -1;
    }

    // ------------------------------------------------------------------ Updates

    private Updater.Release update;
    private String updateText;
    private boolean updateChecked, updating;

    /** "Version 1.2" with the update status; selecting it installs an available update. */
    private View updateRow() {
        if (!updateChecked) {
            updateChecked = true;
            updateText = s(R.string.update_checking);
            Updater.check(this, (newer, err) -> {
                update = newer;
                updateText = newer != null ? s(R.string.update_available, newer.version)
                        : err != null ? s(R.string.update_error, err) : s(R.string.update_none);
                setRowSub(updateRowView, updateText);
            });
        }
        LinearLayout r = ui.row(null, R.drawable.ic_download, update != null ? GREEN : C_OFF,
                s(R.string.app_version, Updater.versionName(this)), updateText,
                update != null ? s(R.string.b_update) : null, GREEN, () -> {
                    if (update == null) { updateChecked = false; setRowSub(updateRowView, s(R.string.update_checking)); updateRow(); return; }
                    if (updating) return;
                    updating = true;
                    Updater.install(this, update, (pct, err, needsPermission) -> {
                        if (needsPermission) { updating = false; setRowSub(updateRowView, s(R.string.update_permission)); }
                        else if (err != null) { updating = false; setRowSub(updateRowView, s(R.string.update_failed, err)); }
                        else setRowSub(updateRowView, pct < 100 ? s(R.string.update_downloading, pct) : s(R.string.update_installing));
                        if (pct >= 100) updating = false;
                    });
                });
        updateRowView = r;
        return r;
    }

    private LinearLayout updateRowView;

    /** Replaces the subtitle of a row created by Ui.row (if the row is still on screen). */
    private static void setRowSub(LinearLayout row, String text) {
        if (row == null || !row.isAttachedToWindow()) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof LinearLayout) {
                LinearLayout texts = (LinearLayout) row.getChildAt(i);
                if (texts.getChildCount() > 1) ((TextView) texts.getChildAt(1)).setText(text);
                return;
            }
        }
    }

    private View statusChip(boolean on) {
        int c = on ? GREEN : RED;
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(ui.px(12), ui.px(6), ui.px(14), ui.px(6));
        chip.setBackground(round(alpha(c, 0x24), ui.px(99)));
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(c);
        dot.setBackground(d);
        chip.addView(dot, new LinearLayout.LayoutParams(ui.px(8), ui.px(8)));
        TextView t = ui.text(s(on ? R.string.status_on : R.string.status_off), 14, c);
        t.setPadding(ui.px(8), 0, 0, 0);
        chip.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, ui.px(14), 0, ui.px(18));
        chip.setLayoutParams(lp);
        return chip;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ------------------------------------------------------------------ Press options (global)

    private String pressSummary() {
        boolean d = KeyMap.enabled(this, KeyMap.DOUBLE), l = KeyMap.enabled(this, KeyMap.LONG);
        if (!d && !l) return s(R.string.press_off);
        String out = d ? s(R.string.press_on_double, KeyMap.doubleMs(this)) : "";
        if (l) out += (d ? "   ·   " : "") + s(R.string.press_on_long, KeyMap.longMs(this));
        return out;
    }

    private static final int[][] WAIT_MS = {{250, R.string.wait_fast}, {350, R.string.std}, {500, R.string.wait_relaxed}};
    private static final int[][] HOLD_MS = {{400, R.string.hold_short}, {500, R.string.std},
            {800, R.string.hold_long}, {1000, R.string.hold_very_long}};

    /** Switch press twice / hold on or off globally and choose their timing. focus = row index. */
    private void showPressOptions(int focus) {
        back = this::showMain;
        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.app_name)));
        left.addView(ui.headline(s(R.string.section_press)));
        TextView b = ui.body(s(R.string.press_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        List<View> rows = new ArrayList<>();
        boolean d = KeyMap.enabled(this, KeyMap.DOUBLE), l = KeyMap.enabled(this, KeyMap.LONG);

        right.addView(ui.section(s(R.string.act_double)));
        add(right, rows, toggleRow(R.string.act_double, R.string.double_toggle_sub, "double_on", d, rows.size()));
        if (d) {
            right.addView(ui.section(s(R.string.section_wait)));
            for (int[] w : WAIT_MS) add(right, rows, msRow(w, "double_ms", KeyMap.doubleMs(this), rows.size()));
        }
        right.addView(ui.section(s(R.string.act_long)));
        add(right, rows, toggleRow(R.string.act_long, R.string.long_toggle_sub, "long_on", l, rows.size()));
        if (l) {
            right.addView(ui.section(s(R.string.section_hold)));
            for (int[] h : HOLD_MS) add(right, rows, msRow(h, "long_ms", KeyMap.longMs(this), rows.size()));
        }
        ui.setScreen(left, right);
        ui.requestFocusLater(rows.get(Math.min(focus, rows.size() - 1)));
    }

    private static void add(LinearLayout into, List<View> rows, View r) {
        rows.add(r);
        into.addView(r);
    }

    private View toggleRow(int title, int sub, String pref, boolean on, int index) {
        return ui.row(null, on ? R.drawable.ic_play : R.drawable.ic_block, on ? GREEN : C_OFF, s(title), s(sub),
                s(on ? R.string.on : R.string.off), on ? GREEN : C_OFF, () -> {
                    KeyMap.prefs(this).edit().putBoolean(pref, !on).apply();
                    showPressOptions(index);
                });
    }

    private View msRow(int[] opt, String pref, int current, int index) {
        boolean on = opt[0] == current;
        return ui.row(null, 0, 0, s(R.string.ms, opt[0]), s(opt[1]), on ? s(R.string.b_active) : null, ACCENT, () -> {
            KeyMap.prefs(this).edit().putInt(pref, opt[0]).apply();
            showPressOptions(index);
        });
    }

    // ------------------------------------------------------------------ Upload buttons (device report)

    /**
     * Shows what a device report contains and uploads it (anonymously, via the report worker).
     * Uploading needs a button mapping first (see showMapStep); one report per TV and day.
     */
    private void showUpload() {
        stopMapping();
        back = this::showMain;
        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.section_help)));
        left.addView(ui.headline(s(R.string.upload_row)));
        TextView b = ui.body(s(R.string.upload_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        right.addView(ui.section(s(R.string.section_mapping)));
        org.json.JSONArray mapping = Report.mapping(this);
        LinearLayout map;
        if (mapping == null) {
            map = ui.row(null, R.drawable.ic_remote, ACCENT, s(R.string.map_start), s(R.string.map_status_none),
                    null, 0, () -> showMapStep(0, true));
        } else {
            map = ui.row(null, R.drawable.ic_remote, GREEN, s(R.string.map_redo),
                    s(R.string.map_status, mapping.length(), mappingRemote(mapping)), null, 0, () -> showMapStep(0, true));
        }
        ui.addChevron(map);
        right.addView(map);

        right.addView(ui.section(s(R.string.section_sent)));
        View dev = ui.row(null, R.drawable.ic_tv, C_OFF, Profiles.deviceName(),
                "Android " + Build.VERSION.RELEASE + "  ·  " + Build.HARDWARE, null, 0, () -> { });
        dev.setFocusable(false);
        right.addView(dev);
        List<String[]> remotes = Report.remotes(this);
        if (remotes.isEmpty()) {
            View r = ui.row(null, R.drawable.ic_remote, C_OFF, s(R.string.upload_remote_none), null, null, 0, () -> { });
            r.setFocusable(false);
            right.addView(r);
        }
        for (String[] rm : remotes) {
            View r = ui.row(null, R.drawable.ic_remote, C_OFF, rm[0], rm[1] + "  ·  " + rm[2], null, 0, () -> { });
            r.setFocusable(false);
            right.addView(r);
        }

        right.addView(ui.section(s(R.string.section_note)));
        EditText note = ui.textField(drafts.containsKey("note") ? drafts.get("note") : "", s(R.string.note_hint), false);
        note.addTextChangedListener(new Ui.Draft(drafts, "note"));
        right.addView(note, new LinearLayout.LayoutParams(-1, -2));

        TextView status = ui.note("");
        status.setVisibility(View.GONE);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, ui.px(12), 0, ui.px(4));
        TextView send = ui.button(s(R.string.upload_send), true, () -> { });
        send.setOnClickListener(v -> {
            if (!Report.enabled()) { showStatus(status, s(R.string.upload_not_configured), RED); return; }
            if (Report.mapping(this) == null) { showMapStep(0, true); return; }
            if (Report.uploadedToday(this)) { showStatus(status, s(R.string.upload_today), C_INTENT); return; }
            send.setEnabled(false);
            showStatus(status, s(R.string.upload_sending), TEXT2);
            Report.upload(this, Report.build(this, note.getText().toString()), (issue, url, err) -> {
                send.setEnabled(true);
                if (Report.DAILY_LIMIT.equals(err)) showStatus(status, s(R.string.upload_today), C_INTENT);
                else if (err != null) showStatus(status, s(R.string.upload_failed, err), RED);
                else {
                    drafts.remove("note");
                    showStatus(status, s(R.string.upload_done, issue) + (url != null ? "\n" + url : ""), GREEN);
                }
            });
        });
        buttons.addView(send);
        right.addView(buttons);
        right.addView(status);
        if (Report.uploadedToday(this)) showStatus(status, s(R.string.upload_today), C_INTENT);

        ui.setScreen(left, right);
        ui.requestFocusLater(mapping == null ? map : send);
    }

    private static void showStatus(TextView v, String text, int color) {
        v.setText(text);
        v.setTextColor(color);
        v.setVisibility(View.VISIBLE);
    }

    /** Name of the remote most mapped buttons came from. */
    private String mappingRemote(org.json.JSONArray mapping) {
        Map<String, Integer> count = new HashMap<>();
        String best = "?";
        int max = 0;
        for (int i = 0; i < mapping.length(); i++) {
            String r = mapping.optJSONObject(i).optString("remote", "");
            if (r.isEmpty()) continue;
            int n = count.containsKey(r) ? count.get(r) + 1 : 1;
            count.put(r, n);
            if (n > max) { max = n; best = r; }
        }
        return best;
    }

    // ------------------------------------------------------------------ Button mapping wizard

    /** Button types asked for, in this order; the last step records any further special buttons. */
    private static final String[] MAP_TYPES = {"livetv", "guide", "netflix", "youtube", "prime", "disney", "play",
            "apps", "settings", "input", "media", "free"};
    /** type -> keycode of the current mapping run; extra special buttons in mapExtra. */
    private final Map<String, Integer> mapCodes = new java.util.LinkedHashMap<>();
    private final List<Integer> mapExtra = new ArrayList<>();
    private int mapStep = -1;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** One step of the wizard: asks for the button of MAP_TYPES[step], or for more buttons at the end. */
    private void showMapStep(int step, boolean fresh) {
        if (!KeyService.running) { ui.toast(s(R.string.service_inactive_toast)); return; }
        if (fresh) { mapCodes.clear(); mapExtra.clear(); }
        mapStep = step;
        int total = MAP_TYPES.length + 1;
        boolean more = step >= MAP_TYPES.length;
        back = this::showUpload;

        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.map_step, step + 1, total)));
        ButtonTypes.Type type = more ? null : ButtonTypes.byId(MAP_TYPES[step]);
        left.addView(ui.headline(more ? s(R.string.map_more_title) : s(R.string.map_press, s(type.name))));
        TextView b = ui.body(s(more ? R.string.map_more_body : R.string.map_press_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.addView(ui.iconBox(null, more ? R.drawable.ic_remote : type.icon, ACCENT, 96));
        TextView status = ui.headline(s(R.string.map_waiting));
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, ui.px(18), 0, ui.px(6));
        right.addView(status);
        TextView sub = ui.note("");
        sub.setGravity(Gravity.CENTER);
        right.addView(sub);
        if (more && !mapExtra.isEmpty()) sub.setText(s(R.string.map_more_count, mapExtra.size()));
        else if (!more && mapCodes.containsKey(MAP_TYPES[step])) {
            status.setText(s(R.string.map_got, KeyMap.label(mapCodes.get(MAP_TYPES[step]))));
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        buttons.setPadding(0, ui.px(18), 0, 0);
        TextView main = more ? ui.button(s(R.string.map_done), true, this::finishMapping)
                : ui.button(s(R.string.map_skip), true, () -> { mapCodes.remove(MAP_TYPES[step]); showMapStep(step + 1, false); });
        buttons.addView(main);
        if (step > 0) buttons.addView(ui.button(s(R.string.map_back), false, () -> showMapStep(step - 1, false)));
        right.addView(buttons);

        ui.setScreen(left, right);
        ui.requestFocusLater(main);

        KeyService.captureAll = true;
        KeyService.capture = code -> runOnUiThread(() -> {
            if (mapStep != step) return;                       // already moving on
            if (more) {
                if (!mapCodes.containsValue(code) && !mapExtra.contains(code)) mapExtra.add(code);
                status.setText(s(R.string.map_got, KeyMap.label(code)));
                sub.setText(s(R.string.map_more_count, mapExtra.size()));
                return;
            }
            for (Map.Entry<String, Integer> m : mapCodes.entrySet()) {
                if (m.getValue() == code && !m.getKey().equals(MAP_TYPES[step])) {
                    status.setText(s(R.string.map_taken, s(ButtonTypes.byId(m.getKey()).name)));
                    return;
                }
            }
            mapCodes.put(MAP_TYPES[step], code);
            status.setText(s(R.string.map_got, KeyMap.label(code)));
            mapStep = -1;
            handler.postDelayed(() -> showMapStep(step + 1, false), 700);
        });
    }

    /** Saves the mapping and adds buttons the app did not know yet (factory behavior, so nothing changes). */
    private void finishMapping() {
        stopMapping();
        org.json.JSONArray arr = new org.json.JSONArray();
        android.content.SharedPreferences.Editor ed = KeyMap.prefs(this).edit();
        List<Integer> known = new ArrayList<>();
        for (KeyMap.Entry e : KeyMap.entries(this)) known.add(e.code);
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<String, Integer> m : mapCodes.entrySet()) all.add(new Object[]{m.getKey(), m.getValue()});
        for (int code : mapExtra) all.add(new Object[]{"other", code});
        for (Object[] m : all) {
            int code = (Integer) m[1];
            arr.put(Report.mappingEntry(this, (String) m[0], code));
            if (!known.contains(code)) {
                ed.putString("t" + code, (String) m[0]).putString("k" + code, KeyMap.SYSTEM);
            }
        }
        ed.apply();
        Report.saveMapping(this, arr);
        showUpload();
    }

    private void stopMapping() {
        mapStep = -1;
        handler.removeCallbacksAndMessages(null);
        KeyService.captureAll = false;
        KeyService.capture = null;
    }

    // ------------------------------------------------------------------ Teaching a button

    private void showCapture() {
        if (!KeyService.running) {
            ui.toast(s(R.string.service_inactive_toast));
            return;
        }
        back = () -> { KeyService.capture = null; showMain(); };
        LinearLayout box = ui.column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(ui.iconBox(null, R.drawable.ic_remote, ACCENT, 72));
        TextView h = ui.headline(s(R.string.capture_title));
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, ui.px(20), 0, ui.px(8));
        box.addView(h);
        TextView b = ui.body(s(R.string.capture_hint));
        b.setGravity(Gravity.CENTER);
        box.addView(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        KeyService.captureAll = false;
        KeyService.capture = code -> runOnUiThread(() -> {
            action = KeyMap.SINGLE;
            if (entry(code) != null) showChooser(code);   // already known -> straight to the assignment
            else showTypePicker(code, true);
        });
    }

    /** Choose the button type: when teaching (new) or later from the "Button" category. */
    private void showTypePicker(int code, boolean isNew) {
        back = isNew ? this::showMain : () -> showChooser(code);
        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(isNew ? s(R.string.new_button, KeyMap.label(code))
                : s(R.string.button_eyebrow, KeyMap.label(code))));
        left.addView(ui.headline(s(R.string.type_title)));
        TextView b = ui.body(s(R.string.type_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        right.addView(ui.section(s(R.string.section_type)));
        ButtonTypes.Type cur = isNew ? null : KeyMap.type(this, code);
        View focus = null;
        for (ButtonTypes.Type t : ButtonTypes.ALL) {
            String suggestion = t.suggest(this);
            String name = "other".equals(t.id) ? s(t.name) + "  (" + KeyMap.label(code) + ")" : s(t.name);
            Drawable app = isPackage(suggestion) ? appIcon(suggestion) : null;
            // show the suggestion only when teaching: changing the type keeps the target as it is
            View r = ui.row(isNew ? app : null, t.icon, ACCENT, name, isNew ? "→  " + describe(suggestion, code) : null,
                    t == cur ? s(R.string.b_active) : null, ACCENT, () -> {
                        android.content.SharedPreferences.Editor ed = KeyMap.prefs(this).edit().putString("t" + code, t.id);
                        if (isNew) ed.putString("k" + code, suggestion);
                        ed.apply();
                        if (isNew) { focusCode = code; showMain(); } else showChooser(code);
                    });
            if (t == cur || focus == null) focus = r;
            right.addView(r);
        }
        ui.setScreen(left, right);
        ui.requestFocusLater(focus);
    }

    // ------------------------------------------------------------------ Home Assistant address

    private void showHaUrl() {
        back = this::showMain;
        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.section_ha)));
        left.addView(ui.headline(s(R.string.ha_address)));
        TextView b = ui.body(s(R.string.ha_body));
        b.setPadding(0, ui.px(14), 0, 0);
        left.addView(b);

        LinearLayout right = ui.column();
        right.addView(ui.section(s(R.string.section_address)));
        EditText in = ui.editor(right, drafts, "ha_url", KeyMap.haUrl(this), KeyMap.HA_URL, true,
                SettingsActivity::isHttp, v -> {
                    KeyMap.prefs(this).edit().putString("ha_url", v.replaceAll("/+$", "")).apply();
                    showMain();
                }, null);
        right.addView(ui.section(s(R.string.section_default)));
        right.addView(ui.row(null, R.drawable.ic_undo, C_OFF, s(R.string.restore_default), KeyMap.HA_URL, null, 0, () -> {
            KeyMap.prefs(this).edit().remove("ha_url").apply();
            showMain();
        }));
        ui.setScreen(left, right);
        ui.requestFocusLater(in);
    }

    // ------------------------------------------------------------------ Assignment

    private void showChooser(int code) {
        entry = entry(code);
        if (entry == null) { showMain(); return; }
        focusCode = code;
        back = this::showMain;
        String pref = pref(code);

        LinearLayout left = ui.column();
        left.addView(ui.eyebrow(s(R.string.button_eyebrow, KeyMap.label(code))));
        left.addView(ui.headline(entry.name));
        nowView = ui.body("");
        nowView.setPadding(0, ui.px(6), 0, ui.px(12));
        left.addView(nowView);
        // tabs only if press twice or hold is switched on globally
        if (!KeyMap.enabled(this, action)) action = KeyMap.SINGLE;
        java.util.Arrays.fill(actViews, null);
        if (KeyMap.enabled(this, KeyMap.DOUBLE) || KeyMap.enabled(this, KeyMap.LONG)) {
            LinearLayout tabs = new LinearLayout(this);
            tabs.setPadding(0, 0, 0, ui.px(6));
            for (int i = 0; i < ACTIONS.length; i++) {
                if (!KeyMap.enabled(this, i)) continue;
                final int a = i;
                TextView t = actionTab(s(ACTIONS[i]));
                t.setOnFocusChangeListener((v, has) -> { if (has && action != a) selectAction(a); });
                t.setOnClickListener(v -> { if (action != a) selectAction(a); });
                actViews[i] = t;
                tabs.addView(t);
            }
            left.addView(tabs);
        }
        warnView = ui.note(s(R.string.warn_factory));
        warnView.setTextColor(C_INTENT);
        left.addView(warnView);
        catViews.clear();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            TextView c = ui.categoryRow(s(CATEGORIES[i]), CAT_ICONS[i]);
            c.setOnFocusChangeListener((v, has) -> { if (has && cat != idx) selectCategory(idx); });
            c.setOnClickListener(v -> {
                if (cat != idx) selectCategory(idx);
                View first = firstFocusable(pane);
                if (first != null) first.requestFocus();
            });
            catViews.add(c);
            left.addView(c);
        }

        pane = ui.column();
        cat = -1;
        paneScroll = ui.setScreen(left, pane);
        int start = categoryOf(actionPref());
        updateActionViews();
        selectCategory(start);
        ui.requestFocusLater(catViews.get(start));
    }

    /** Tab "Press / Press twice / Hold". */
    private TextView actionTab(String label) {
        TextView t = ui.text(label, 14, TEXT2);
        t.setId(View.generateViewId());
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(ui.px(34));
        t.setPadding(ui.px(12), 0, ui.px(12), 0);
        t.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{TEXT, ACCENT, TEXT2}));
        t.setBackground(states(ui.framed(SURFACE_HI, ui.px(99), 2, ACCENT), round(alpha(ACCENT, 0x24), ui.px(99)),
                round(0, ui.px(99))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(ui.px(4));
        t.setLayoutParams(lp);
        return t;
    }

    private void selectAction(int a) {
        action = a;
        updateActionViews();
        selectCategory(categoryOf(actionPref()));
    }

    /** Update the tabs, the "Now:" line and the factory-behavior warning for the chosen action. */
    private void updateActionViews() {
        for (int k = 0; k < actViews.length; k++) if (actViews[k] != null) actViews[k].setSelected(k == action);
        String t = action == KeyMap.SINGLE ? (pref(entry.code) != null ? pref(entry.code) : entry.def) : actionPref();
        nowView.setText(s(R.string.now, t == null ? s(R.string.t_unset) : describe(t, entry.code, action)));
        boolean extras = KeyMap.extra(this, entry.code, KeyMap.DOUBLE) != null
                || KeyMap.extra(this, entry.code, KeyMap.LONG) != null;
        warnView.setVisibility(extras && KeyMap.target(this, entry.code) == null ? View.VISIBLE : View.GONE);
        // up from the first category always leads to the selected tab (not the nearest one)
        if (!catViews.isEmpty() && actViews[action] != null) catViews.get(0).setNextFocusUpId(actViews[action].getId());
    }

    /** Own assignment of the action being edited (null = none). */
    private String actionPref() {
        return KeyMap.prefs(this).getString(KeyMap.prefKey(entry.code, action), null);
    }

    private int categoryOf(String t) {
        if (t == null || KeyMap.SYSTEM.equals(t) || KeyMap.NONE.equals(t) || KeyMap.HOME.equals(t)) return CAT_GENERAL;
        if (KeyMap.LIVE_TV.equals(t) || KeyMap.EPG.equals(t) || t.equals(KeyMap.liveTv(this))) return CAT_TV;
        if (t.startsWith(KeyMap.TV_SETTINGS_PKG + "/")) return CAT_SETTINGS;
        if (KeyMap.HA.equals(t) || isHttp(t)) return CAT_HOOK;
        if (KeyMap.isUri(t)) return CAT_INTENT;
        if (t.indexOf('/') > 0) return CAT_SUB;
        return CAT_APPS;
    }

    private void selectCategory(int i) {
        cat = i;
        back = this::showMain;
        for (int k = 0; k < catViews.size(); k++) catViews.get(k).setSelected(k == i);
        clearPane();
        switch (i) {
            case CAT_GENERAL: fillGeneral(); break;
            case CAT_TV: fillTv(); break;
            case CAT_SETTINGS: fillSettings(); break;
            case CAT_APPS: fillApps(); break;
            case CAT_SUB: fillSubApps(); break;
            case CAT_HOOK: fillHook(); break;
            case CAT_INTENT: fillIntent(); break;
            default: fillKey(); break;
        }
        paneDone();
    }

    /**
     * Clear the content. If it holds the focus, move the focus to the category first: otherwise
     * Android moves it to the first category on removal, and that category's content would be added.
     */
    private void clearPane() {
        if (pane.hasFocus() && cat >= 0) catViews.get(cat).requestFocus();
        pane.removeAllViews();
    }

    /** After filling: set focus paths between category and content, scroll to the top. */
    private void paneDone() {
        linkLeft(pane, catViews.get(cat).getId());
        View first = firstFocusable(pane);
        for (TextView v : catViews) v.setNextFocusRightId(first != null ? first.getId() : View.NO_ID);
        if (paneScroll != null) paneScroll.scrollTo(0, 0);
    }

    private void fillGeneral() {
        pane.addView(ui.section(s(R.string.cat_general)));
        if (action != KeyMap.SINGLE) {
            option(null, R.drawable.ic_block, C_OFF, s(R.string.t_unset),
                    s(action == KeyMap.DOUBLE ? R.string.t_unset_double : R.string.t_unset_long), null);
            option(null, R.drawable.ic_home, C_SYS, s(R.string.t_home), s(R.string.t_home_sub), KeyMap.HOME);
            return;
        }
        if (entry.builtin) {
            Look l = look(entry.def);
            option(l.icon, l.vec, l.color, s(R.string.t_default), describe(entry.def, entry.code), null);
        }
        option(null, R.drawable.ic_undo, C_OFF, s(R.string.t_system), s(R.string.t_system_sub), KeyMap.SYSTEM);
        option(null, R.drawable.ic_block, C_OFF, s(R.string.t_none), s(R.string.t_none_sub), KeyMap.NONE);
        option(null, R.drawable.ic_home, C_SYS, s(R.string.t_home), s(R.string.t_home_sub), KeyMap.HOME);
    }

    private void fillTv() {
        pane.addView(ui.section(s(R.string.cat_tv)));
        String tv = KeyMap.liveTv(this);
        if (tv == null) {
            pane.addView(ui.note(s(R.string.t_livetv_missing)));
            return;
        }
        option(null, R.drawable.ic_tv, C_TV, s(R.string.t_livetv), appLabel(tv.substring(0, tv.indexOf('/'))), KeyMap.LIVE_TV);
        if (KeyMap.epgSupported(this)) {
            option(null, R.drawable.ic_list, C_TV, s(R.string.t_epg), s(R.string.t_epg_sub), KeyMap.EPG);
        }
    }

    private void fillSettings() {
        pane.addView(ui.section(s(R.string.cat_settings)));
        option(null, R.drawable.ic_gear, C_SYS, s(R.string.t_settings), s(R.string.t_settings_sub), KeyMap.TV_SETTINGS);
        pane.addView(ui.section(s(R.string.section_subpages)));
        for (Object[] p : KeyMap.TV_SETTINGS_PAGES) {
            String t = KeyMap.tvSettingsPage((String) p[1]);
            if (KeyMap.startable(this, t)) {
                String name = s((Integer) p[0]);
                option(null, R.drawable.ic_gear, C_SUB, name, s(R.string.t_settings_page, name), t);
            }
        }
    }

    private void fillApps() {
        pane.addView(ui.section(s(R.string.cat_apps)));
        for (Map.Entry<String, String> a : apps().entrySet()) {
            option(appIcon(a.getValue()), 0, C_APP, a.getKey(), a.getValue(), a.getValue());
        }
    }

    private void fillSubApps() {
        pane.addView(ui.section(s(R.string.cat_sub)));
        pane.addView(ui.note(s(R.string.sub_hint)));
        String pref = actionPref();
        String curPkg = pref != null && categoryOf(pref) == CAT_SUB ? pref.substring(0, pref.indexOf('/')) : null;
        for (Map.Entry<String, String> a : appsWithSystem().entrySet()) {
            String pkg = a.getValue();
            boolean here = pkg.equals(curPkg);
            LinearLayout r = ui.row(appIcon(pkg), R.drawable.ic_apps, C_SUB, a.getKey(),
                    s(here ? R.string.sub_current : R.string.sub_show),
                    here ? s(R.string.b_active) : null, ACCENT, () -> showActivities(a.getKey(), pkg));
            ui.addChevron(r);
            pane.addView(r);
        }
    }

    private void showActivities(String appName, String pkg) {
        clearPane();
        Runnable up = () -> { selectCategory(CAT_SUB); ui.requestFocusLater(firstFocusable(pane)); };
        back = up;
        pane.addView(ui.row(null, R.drawable.ic_back, C_OFF, s(R.string.all_apps), null, null, 0, up));
        pane.addView(ui.section(appName));
        PackageManager pm = getPackageManager();
        TreeMap<String, String[]> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            ActivityInfo[] acts = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities;
            if (acts != null) {
                for (ActivityInfo ai : acts) {
                    if (!ai.exported || ai.permission != null || !ai.isEnabled()) continue;
                    String cls = shortClass(ai.name);
                    String label = ai.loadLabel(pm).toString().trim();
                    String title = label.isEmpty() || label.equals(appName) || label.equals(cls) ? pretty(cls) : label;
                    if (found.containsKey(title)) title = title + "  ·  " + cls;
                    String sub = ai.name.startsWith(pkg + ".") ? ai.name.substring(pkg.length()) : ai.name;
                    found.put(title, new String[]{sub, pkg + "/" + ai.name});
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        if (found.isEmpty()) pane.addView(ui.note(s(R.string.sub_none)));
        Drawable icon = appIcon(pkg);
        for (Map.Entry<String, String[]> f : found.entrySet()) {
            option(icon, R.drawable.ic_apps, C_SUB, f.getKey(), f.getValue()[0], f.getValue()[1]);
        }
        paneDone();
        ui.requestFocusLater(firstFocusable(pane));
    }

    private void fillHook() {
        int code = entry.code;
        pane.addView(ui.section(s(R.string.cat_hook)));
        int act = action;
        option(null, R.drawable.ic_bolt, C_HOOK, s(R.string.t_ha), s(R.string.t_ha_sub,
                KeyMap.webhookId(code, act), KeyMap.webhookUrl(this, KeyMap.HA, code, act)), KeyMap.HA);
        pane.addView(ui.section(s(R.string.section_own_webhook)));
        String pref = actionPref();
        ui.editor(pane, drafts, "hook" + act, pref != null && isHttp(pref) ? pref : "", "http://…", true,
                SettingsActivity::isHttp, this::save, v -> KeyMap.run(this, v, code, act, true));
        pane.addView(ui.note(s(R.string.webhook_note)));
    }

    private void fillIntent() {
        int code = entry.code, act = action;
        String pref = actionPref();
        pane.addView(ui.section(s(R.string.section_intent)));
        EditText in = ui.editor(pane, drafts, "intent" + act, pref != null && KeyMap.isUri(pref) ? pref : "",
                "intent:#Intent;action=…;end", true,
                v -> KeyMap.isUri(v) && KeyMap.intentFor(this, v) != null, this::save,
                v -> KeyMap.run(this, v, code, act, true));
        pane.addView(ui.note(s(R.string.intent_note)));
        pane.addView(ui.section(s(R.string.section_examples)));
        String[][] ex = {
                {s(R.string.ex_wifi), "intent:#Intent;action=android.settings.WIFI_SETTINGS;end"},
                {s(R.string.ex_bt), "intent:#Intent;component=com.android.tv.settings/.accessories.AddAccessoryActivity;end"},
                {s(R.string.ex_play), "market://details?id=com.google.android.youtube.tv"},
        };
        for (String[] x : ex) {
            pane.addView(ui.row(null, x[1].startsWith("intent:") ? R.drawable.ic_code : R.drawable.ic_link, C_INTENT,
                    x[0], x[1], null, 0, () -> { in.setText(x[1]); in.setSelection(x[1].length()); in.requestFocus(); }));
        }
    }

    private void fillKey() {
        int code = entry.code;
        pane.addView(ui.section(s(R.string.cat_key)));
        int act = action;
        String t = KeyMap.target(this, code, act);
        pane.addView(ui.row(null, R.drawable.ic_play, ACCENT, s(R.string.trigger_now) + "  (" + s(ACTIONS[act]) + ")",
                t == null ? s(act == KeyMap.SINGLE ? R.string.trigger_none : R.string.t_unset)
                        : describe(t, code, act), null, 0, () -> {
                    if (t != null) KeyMap.run(this, t, code, act, true);
                }));
        ButtonTypes.Type type = KeyMap.type(this, code);
        LinearLayout tr = ui.row(null, type != null ? type.icon : R.drawable.ic_remote, C_OFF, s(R.string.change_type),
                type != null ? s(type.name) : KeyMap.label(code), null, 0, () -> showTypePicker(code, false));
        ui.addChevron(tr);
        pane.addView(tr);
        pane.addView(ui.section(s(R.string.section_name)));
        ui.editor(pane, drafts, "name", entry.name, KeyMap.label(code), false, v -> !v.isEmpty(), v -> {
            KeyMap.prefs(this).edit().putString("n" + code, v).apply();
            showChooser(code);
        }, null);
        if (KeyMap.prefs(this).contains("n" + code)) {
            pane.addView(ui.row(null, R.drawable.ic_undo, C_OFF, s(R.string.reset_name), null, null, 0, () -> {
                KeyMap.prefs(this).edit().remove("n" + code).apply();
                drafts.remove("name");
                showChooser(code);
            }));
        }
        if (!entry.builtin) {
            pane.addView(ui.section(s(R.string.section_remove)));
            pane.addView(ui.row(null, R.drawable.ic_delete, RED, s(R.string.remove_button), s(R.string.remove_button_sub),
                    null, 0, () -> {
                        KeyMap.prefs(this).edit().remove("k" + code).remove("d" + code).remove("l" + code)
                                .remove("n" + code).remove("t" + code).remove("s" + code).remove("r" + code).apply();
                        showMain();
                    }));
        }
        pane.addView(ui.note(s(R.string.keycode_note, code, KeyMap.label(code))));
    }

    /** Target row: "Active" if it is the current target; clicking saves and returns to the overview. */
    private void option(Drawable icon, int vec, int color, String title, String sub, String value) {
        String pref = actionPref();
        boolean current = action != KeyMap.SINGLE ? (value == null ? pref == null : value.equals(pref))
                : value == null ? pref == null
                : value.equals(pref != null ? pref : entry.def) && (pref != null || !entry.builtin);
        pane.addView(ui.row(icon, vec, color, title, sub, current ? s(R.string.b_active) : null, ACCENT,
                () -> save(value)));
    }

    private void save(String value) {
        String k = KeyMap.prefKey(entry.code, action);
        if (value == null) KeyMap.prefs(this).edit().remove(k).apply();
        else KeyMap.prefs(this).edit().putString(k, value).apply();
        showMain();
    }

    // ------------------------------------------------------------------ Data

    private String pref(int code) { return KeyMap.prefs(this).getString("k" + code, null); }

    private KeyMap.Entry entry(int code) {
        for (KeyMap.Entry e : KeyMap.entries(this)) if (e.code == code) return e;
        return null;
    }

    private static boolean isHttp(String s) { return s.startsWith("http://") || s.startsWith("https://"); }

    /** Plain package name (app target)? */
    private static boolean isPackage(String t) {
        return t != null && !t.startsWith("@") && t.indexOf('/') < 0 && t.indexOf(':') < 0;
    }

    private static String shortClass(String cls) { return cls.substring(cls.lastIndexOf('.') + 1); }

    /** "AccessibilityCaptionActivity" -> "Accessibility Caption" */
    private static String pretty(String cls) {
        String s = cls.replaceAll("Activity$", "");
        if (s.isEmpty()) s = cls;
        return s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('$', ' ').trim();
    }

    /** Launchable apps: label -> package name, alphabetical. */
    private TreeMap<String, String> apps() {
        if (appCache != null) return appCache;
        PackageManager pm = getPackageManager();
        TreeMap<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String c : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(c), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || out.containsValue(pkg)) continue;
                out.put(ri.loadLabel(pm).toString(), pkg);
            }
        }
        return appCache = out;
    }

    /** Apps plus the live TV app and TV settings (which may have no launcher entry). */
    private TreeMap<String, String> appsWithSystem() {
        TreeMap<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        out.putAll(apps());
        String tv = KeyMap.liveTv(this);
        for (String pkg : new String[]{tv != null ? tv.substring(0, tv.indexOf('/')) : null, KeyMap.TV_SETTINGS_PKG}) {
            if (pkg == null || out.containsValue(pkg)) continue;
            String label = appLabel(pkg);
            if (label != null) out.put(label, pkg);
        }
        return out;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Drawable appIcon(String pkg) {
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        Drawable d;
        try { d = getPackageManager().getApplicationIcon(pkg); }
        catch (PackageManager.NameNotFoundException e) { d = null; }
        iconCache.put(pkg, d);
        return d;
    }

    /** How a target is shown in the overview: icon, color, kind. */
    private static final class Look {
        final Drawable icon; final int vec; final int color; final String badge;
        Look(Drawable icon, int vec, int color, String badge) {
            this.icon = icon; this.vec = vec; this.color = color; this.badge = badge;
        }
    }

    private Look look(String t) {
        if (t == null || KeyMap.SYSTEM.equals(t)) return new Look(null, R.drawable.ic_undo, C_OFF, s(R.string.b_factory));
        if (KeyMap.NONE.equals(t)) return new Look(null, R.drawable.ic_block, C_OFF, s(R.string.b_off));
        if (KeyMap.HOME.equals(t)) return new Look(null, R.drawable.ic_home, C_SYS, s(R.string.b_system));
        if (KeyMap.TV_SETTINGS.equals(t)) return new Look(null, R.drawable.ic_gear, C_SYS, s(R.string.b_system));
        if (KeyMap.LIVE_TV.equals(t) || t.equals(KeyMap.liveTv(this))) {
            return KeyMap.liveTv(this) != null ? new Look(null, R.drawable.ic_tv, C_TV, s(R.string.b_tv))
                    : new Look(null, R.drawable.ic_block, RED, s(R.string.b_missing));
        }
        if (KeyMap.EPG.equals(t)) {
            return KeyMap.epgSupported(this) ? new Look(null, R.drawable.ic_list, C_TV, s(R.string.b_tv))
                    : new Look(null, R.drawable.ic_block, RED, s(R.string.b_missing));
        }
        if (KeyMap.HA.equals(t) || isHttp(t)) return new Look(null, R.drawable.ic_bolt, C_HOOK, s(R.string.b_webhook));
        if (t.startsWith("intent:")) return new Look(null, R.drawable.ic_code, C_INTENT, s(R.string.b_intent));
        if (KeyMap.isUri(t)) return new Look(null, R.drawable.ic_link, C_INTENT, s(R.string.b_link));
        if (t.startsWith(KeyMap.TV_SETTINGS_PKG + "/")) return new Look(null, R.drawable.ic_gear, C_SUB, s(R.string.b_sub));
        int slash = t.indexOf('/');
        Drawable icon = appIcon(slash > 0 ? t.substring(0, slash) : t);
        if (icon == null) return new Look(null, R.drawable.ic_block, RED, s(R.string.b_missing));
        return new Look(icon, 0, slash > 0 ? C_SUB : C_APP, s(slash > 0 ? R.string.b_sub : R.string.b_app));
    }

    private String describe(String t, int code) { return describe(t, code, KeyMap.SINGLE); }

    private String describe(String t, int code, int act) {
        if (t == null || KeyMap.SYSTEM.equals(t)) return s(R.string.t_system);
        if (KeyMap.HA.equals(t)) return s(R.string.t_ha_desc, KeyMap.webhookId(code, act));
        if (isHttp(t)) return s(R.string.t_webhook, t);
        if (KeyMap.NONE.equals(t)) return s(R.string.t_none);
        if (KeyMap.HOME.equals(t)) return s(R.string.t_home);
        if (KeyMap.LIVE_TV.equals(t) || t.equals(KeyMap.liveTv(this))) return s(R.string.t_livetv);
        if (KeyMap.EPG.equals(t)) return s(R.string.t_epg);
        if (KeyMap.TV_SETTINGS.equals(t)) return s(R.string.t_settings);
        for (Object[] p : KeyMap.TV_SETTINGS_PAGES) {
            if (KeyMap.tvSettingsPage((String) p[1]).equals(t)) return s(R.string.t_settings_page, s((Integer) p[0]));
        }
        if (KeyMap.isUri(t)) return t;
        int slash = t.indexOf('/');
        String pkg = slash > 0 ? t.substring(0, slash) : t;
        String app = appLabel(pkg);
        if (app == null) return s(R.string.not_installed, pkg);
        if (slash < 0) return app;
        ComponentName cn = ComponentName.unflattenFromString(t);
        return app + " › " + (cn != null ? pretty(shortClass(cn.getClassName())) : t.substring(slash + 1));
    }
}
