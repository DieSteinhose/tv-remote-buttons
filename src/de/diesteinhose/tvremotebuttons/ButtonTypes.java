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
            new Type("media", R.string.type_media, R.drawable.ic_usb, KeyMap.SYSTEM),
            new Type("free", R.string.type_free, R.drawable.ic_star, KeyMap.SYSTEM),
            new Type("other", R.string.type_other, R.drawable.ic_remote, KeyMap.SYSTEM),
    };

    static Type byId(String id) {
        if (id != null) for (Type t : ALL) if (t.id.equals(id)) return t;
        return null;
    }
}
