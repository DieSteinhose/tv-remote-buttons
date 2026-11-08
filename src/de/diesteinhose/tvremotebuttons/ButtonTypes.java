package de.diesteinhose.tvremotebuttons;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Button types: give a button its name, icon and a suggested target.
 * Chosen when teaching a new button and used by device profiles.
 */
final class ButtonTypes {
    private ButtonTypes() {}

    static final class Type {
        final String id;
        final int name;
        final int icon;
        /** Suggested target; null = first installed app from {@link #apps}, otherwise factory behavior. */
        final String target;
        final String[] apps;

        Type(String id, int name, int icon, String target, String... apps) {
            this.id = id; this.name = name; this.icon = icon; this.target = target; this.apps = apps;
        }

        /** Suggested target on this device. */
        String suggest(Context c) {
            if (target != null) return target;
            PackageManager pm = c.getPackageManager();
            for (String pkg : apps) {
                if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) return pkg;
            }
            return KeyMap.SYSTEM;
        }
    }

    static final Type[] ALL = {
            new Type("livetv", R.string.type_livetv, R.drawable.ic_tv, KeyMap.LIVE_TV),
            new Type("guide", R.string.type_guide, R.drawable.ic_list, KeyMap.EPG),
            new Type("netflix", R.string.type_netflix, R.drawable.ic_play, null, "com.netflix.ninja"),
            new Type("youtube", R.string.type_youtube, R.drawable.ic_play, null,
                    "com.google.android.youtube.tv", "org.smarttube.stable", "com.teamsmart.videomanager.tv"),
            new Type("prime", R.string.type_prime, R.drawable.ic_play, null, "com.amazon.amazonvideo.livingroom"),
            new Type("disney", R.string.type_disney, R.drawable.ic_play, null, "com.disney.disneyplus"),
            new Type("play", R.string.type_play, R.drawable.ic_play, null, "com.android.vending"),
            new Type("apps", R.string.type_apps, R.drawable.ic_apps, KeyMap.SYSTEM),
            new Type("settings", R.string.type_settings, R.drawable.ic_gear, KeyMap.TV_SETTINGS),
            new Type("input", R.string.type_input, R.drawable.ic_input, KeyMap.SYSTEM),
            new Type("assistant", R.string.type_assistant, R.drawable.ic_mic, KeyMap.SYSTEM),
            new Type("media", R.string.type_media, R.drawable.ic_usb, KeyMap.SYSTEM),
            new Type("free", R.string.type_free, R.drawable.ic_star, KeyMap.SYSTEM),
            new Type("other", R.string.type_other, R.drawable.ic_remote, KeyMap.SYSTEM),
    };

    /**
     * Type suggested for a pressed button: from the active profile, the keycode name (e.g.
     * NETFLIX) or the app the button opened by itself; null if nothing fits.
     */
    static String guess(Context c, int code, String openedPkg) {
        Profiles.Profile p = KeyMap.profile(c);
        if (p != null) for (Profiles.Key k : p.keys) if (k.code == code && byId(k.type) != null) return k.type;

        String n = KeyMap.label(code);
        if (n.contains("NETFLIX")) return "netflix";
        if (n.contains("YOUTUBE")) return "youtube";
        if (n.contains("PRIME") || n.contains("AMAZON")) return "prime";
        if (n.contains("DISNEY")) return "disney";
        if (n.contains("PLAY_STORE") || n.contains("GOOGLE_PLAY")) return "play";
        if (n.equals("TV") || n.contains("LIVE_TV") || n.contains("LIVETV")) return "livetv";
        if (n.equals("GUIDE") || n.contains("EPG")) return "guide";
        if (n.contains("ALL_APPS") || n.equals("APPS")) return "apps";
        if (n.equals("SETTINGS") || n.equals("TV_SETTINGS")) return "settings";
        if (n.startsWith("TV_INPUT") || n.equals("SOURCE") || n.equals("INPUT")) return "input";
        if (n.contains("ASSIST") || n.equals("SEARCH") || n.contains("VOICE")) return "assistant";
        if (n.equals("USB")) return "media";
        if (n.matches("BUTTON_\\d+")) return "free";

        // The Play Store opens for many app buttons whose app is missing, so it says nothing.
        if (openedPkg == null || openedPkg.equals("com.android.vending")) return null;
        String tv = KeyMap.liveTv(c);
        if (tv != null && tv.startsWith(openedPkg + "/")) return "livetv";
        if (openedPkg.equals("com.android.tv.settings")) return "settings";
        for (Type t : ALL) for (String app : t.apps) if (app.equals(openedPkg)) return t.id;
        return null;
    }

    static Type byId(String id) {
        if (id != null) for (Type t : ALL) if (t.id.equals(id)) return t;
        return null;
    }
}
