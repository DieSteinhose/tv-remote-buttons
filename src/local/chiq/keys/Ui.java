package local.chiq.keys;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Building blocks of the dark TV interface (D-pad): colors, rows, categories, text field, focus paths.
 * All views are created in code (no layout files, no libraries).
 */
final class Ui {
    // Base colors
    static final int BG = 0xFF0F1115, SURFACE = 0xFF181B21, SURFACE_HI = 0xFF262B35, LINE = 0xFF2E3440,
            ACCENT = 0xFF5B9BFF, TEXT = 0xFFECEEF2, TEXT2 = 0xFFA0A7B4, TEXT3 = 0xFF6B7385,
            RED = 0xFFF07178, GREEN = 0xFF5FD38D;
    // Colors of the target kinds
    static final int C_APP = 0xFF5B9BFF, C_SUB = 0xFFB18CFF, C_TV = 0xFFF5A35B, C_SYS = 0xFF4FD1C5,
            C_HOOK = 0xFF41BDF5, C_INTENT = 0xFFE5C07B, C_OFF = 0xFF8B93A1;

    private final Activity a;
    private final float density;

    Ui(Activity a) {
        this.a = a;
        this.density = a.getResources().getDisplayMetrics().density;
    }

    int px(float v) { return Math.round(v * density); }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    static GradientDrawable round(int fill, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        return g;
    }

    GradientDrawable framed(int fill, int radius, float strokeDp, int stroke) {
        GradientDrawable g = round(fill, radius);
        g.setStroke(Math.max(1, px(strokeDp)), stroke);
        return g;
    }

    /** Background with states: focused/pressed, selected, normal (null = none). */
    static StateListDrawable states(Drawable focused, Drawable selected, Drawable normal) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        if (selected != null) s.addState(new int[]{android.R.attr.state_selected}, selected);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** Two-column screen; returns the right ScrollView. */
    ScrollView setScreen(View left, View right) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(px(48), 0, px(36), 0);
        root.addView(scroll(left, px(40)), new LinearLayout.LayoutParams(0, -1, 34f));
        root.addView(new View(a), new LinearLayout.LayoutParams(px(32), 1));
        ScrollView rs = scroll(right, px(28));
        root.addView(rs, new LinearLayout.LayoutParams(0, -1, 66f));
        a.setContentView(root);
        return rs;
    }

    ScrollView scroll(View content, int top) {
        ScrollView s = new EdgeScroll(a, px(64));
        s.setVerticalScrollBarEnabled(false);
        s.setVerticalFadingEdgeEnabled(true);
        s.setFadingEdgeLength(px(36));
        s.setClipToPadding(false);
        s.setFillViewport(true);
        // margin so that focused (enlarged) rows and their frame are not clipped
        content.setPadding(px(12), top, px(12), px(40));
        if (content instanceof ViewGroup) ((ViewGroup) content).setClipToPadding(false);
        s.addView(content, new ViewGroup.LayoutParams(-1, -2));
        return s;
    }

    /** ScrollView that keeps the focused row away from the screen edge. */
    static final class EdgeScroll extends ScrollView {
        private final int margin;
        EdgeScroll(android.content.Context c, int margin) { super(c); this.margin = margin; }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            android.graphics.Rect big = new android.graphics.Rect(r);
            big.inset(0, -margin);
            return super.computeScrollDeltaToGetChildRectOnScreen(big);
        }
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    TextView text(String s, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    TextView eyebrow(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.getDefault()), 12, TEXT3);
        t.setTypeface(medium());
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, px(4));
        return t;
    }

    TextView headline(String s) {
        TextView t = text(s, 30, TEXT);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return t;
    }

    TextView body(String s) {
        TextView t = text(s, 15, TEXT2);
        t.setLineSpacing(px(3), 1f);
        return t;
    }

    TextView note(String s) {
        TextView t = text(s, 13, TEXT3);
        t.setLineSpacing(px(2), 1f);
        t.setPadding(px(4), px(6), px(4), px(10));
        return t;
    }

    TextView section(String s) {
        TextView t = eyebrow(s);
        t.setPadding(px(4), px(18), 0, px(8));
        return t;
    }

    /** App icon, or a tinted vector icon on a tinted tile. */
    ImageView iconBox(Drawable app, int vec, int color, int sizeDp) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app != null) {
            iv.setImageDrawable(app);
        } else {
            iv.setImageResource(vec);
            iv.setImageTintList(ColorStateList.valueOf(color));
            iv.setBackground(round(alpha(color, 0x2A), px(sizeDp / 4f)));
            int p = px(sizeDp / 4.5f);
            iv.setPadding(p, p, p, p);
        }
        iv.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return iv;
    }

    TextView badge(String s, int color) {
        TextView t = text(s, 11, color);
        t.setTypeface(medium());
        t.setLetterSpacing(0.04f);
        t.setPadding(px(9), px(3), px(9), px(3));
        t.setBackground(round(alpha(color, 0x29), px(99)));
        return t;
    }

    /** Focusable row: icon, title, subtitle, optionally a badge on the right. */
    LinearLayout row(Drawable icon, int vec, int color, String title, String sub,
                             String badge, int badgeColor, Runnable click) {
        LinearLayout r = new LinearLayout(a);
        r.setId(View.generateViewId());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(58));
        r.setPadding(px(12), px(9), px(16), px(9));
        r.setBackground(states(framed(SURFACE_HI, px(12), 2, ACCENT), null, round(SURFACE, px(12))));
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(v -> click.run());
        r.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(120).start());

        if (icon != null || vec != 0) {
            ImageView iv = iconBox(icon, vec, color, 38);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(38), px(38));
            lp.setMarginEnd(px(14));
            r.addView(iv, lp);
        }
        LinearLayout texts = column();
        TextView t = text(title, 17, TEXT);
        t.setTypeface(medium());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 13, TEXT2);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, px(2), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (badge != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(px(12));
            r.addView(badge(badge, badgeColor), lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, px(6));
        r.setLayoutParams(lp);
        return r;
    }

    void addChevron(LinearLayout row) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(R.drawable.ic_chevron);
        iv.setImageTintList(ColorStateList.valueOf(TEXT3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(22), px(22));
        lp.setMarginStart(px(8));
        row.addView(iv, lp);
    }

    /** Category on the left: selected = accent color, focused = card with frame. */
    TextView categoryRow(String name, int icon) {
        TextView t = text(name, 16, TEXT2);
        Drawable d = a.getDrawable(icon).mutate();
        d.setBounds(0, 0, px(20), px(20));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(px(14));
        t.setId(View.generateViewId());
        t.setTypeface(medium());
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(px(40));
        t.setPadding(px(14), px(6), px(14), px(6));
        ColorStateList colors = new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{TEXT, ACCENT, TEXT2});
        t.setTextColor(colors);
        t.setCompoundDrawableTintList(colors);
        t.setBackground(states(framed(SURFACE_HI, px(10), 2, ACCENT), round(alpha(ACCENT, 0x1F), px(10)),
                round(0, px(10))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, px(3));
        t.setLayoutParams(lp);
        return t;
    }

    interface TextCheck { boolean ok(String text); }
    interface TextResult { void on(String text); }

    /** Text field with "Save" and optionally "Try it"; errors appear below it. */
    EditText editor(LinearLayout into, Map<String, String> drafts, String draftKey, String saved, String hint, boolean uri,
                            TextCheck check, TextResult save, TextResult tryIt) {
        String initial = drafts.containsKey(draftKey) ? drafts.get(draftKey) : saved;
        EditText in = new EditText(a);
        in.setId(View.generateViewId());
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | (uri ? InputType.TYPE_TEXT_VARIATION_URI : 0));
        in.setImeOptions(EditorInfo.IME_ACTION_DONE);
        in.setText(initial);
        in.setSelection(initial.length());
        in.addTextChangedListener(new Draft(drafts, draftKey));
        // single-line field: up/down always leave it (otherwise they only move the cursor to start/end)
        in.setOnKeyListener((v, keyCode, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int dir = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                    : keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : 0;
            if (dir == 0) return false;
            View next = v.focusSearch(dir);
            if (next != null) next.requestFocus();
            return true;
        });
        in.setHint(hint);
        in.setTextSize(17);
        in.setTextColor(TEXT);
        in.setHintTextColor(TEXT3);
        in.setPadding(px(16), px(12), px(16), px(12));
        in.setBackground(states(framed(SURFACE_HI, px(12), 2, ACCENT), null, framed(SURFACE, px(12), 1, LINE)));
        into.addView(in, new LinearLayout.LayoutParams(-1, -2));

        TextView err = text("", 13, RED);
        err.setPadding(px(4), px(6), 0, 0);
        err.setVisibility(View.GONE);
        into.addView(err);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setPadding(0, px(10), 0, px(4));
        buttons.addView(button(a.getString(R.string.save), true, () -> run(in, check, err, save)));
        if (tryIt != null) buttons.addView(button(a.getString(R.string.try_it), false, () -> run(in, check, err, tryIt)));
        into.addView(buttons);
        in.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { run(in, check, err, save); return true; }
            return false;
        });
        return in;
    }

    /** Remembers the text of a text field under its key. */
    static final class Draft implements android.text.TextWatcher {
        private final Map<String, String> drafts;
        private final String key;
        Draft(Map<String, String> drafts, String key) { this.drafts = drafts; this.key = key; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(android.text.Editable e) { drafts.put(key, e.toString()); }
    }

    static void run(EditText in, TextCheck check, TextView err, TextResult r) {
        String s = in.getText().toString().trim();
        if (check.ok(s)) {
            err.setVisibility(View.GONE);
            r.on(s);
        } else {
            err.setText(err.getContext().getString(R.string.invalid));
            err.setVisibility(View.VISIBLE);
        }
    }

    TextView button(String label, boolean primary, Runnable click) {
        TextView b = text(label, 15, TEXT);
        b.setId(View.generateViewId());
        b.setTypeface(medium());
        b.setFocusable(true);
        b.setClickable(true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(px(42));
        b.setPadding(px(22), 0, px(22), 0);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{BG, primary ? ACCENT : TEXT}));
        b.setBackground(states(round(primary ? ACCENT : TEXT, px(99)), null,
                framed(SURFACE, px(99), 1, primary ? alpha(ACCENT, 0x80) : LINE)));
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(px(10));
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------------ Focus

    static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v.isFocusable()) return v;
            if (v instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) v);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Left from the content always leads back to the selected category. */
    static void linkLeft(ViewGroup g, int id) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.isFocusable()) v.setNextFocusLeftId(id);
            if (v instanceof ViewGroup) linkLeft((ViewGroup) v, id);
        }
    }

    void requestFocusLater(View v) {
        if (v != null) v.post(v::requestFocus);
    }

    void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }
}
