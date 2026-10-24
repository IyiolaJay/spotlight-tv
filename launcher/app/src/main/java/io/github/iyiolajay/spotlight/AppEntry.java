package io.github.iyiolajay.spotlight;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String pkg;
    final ComponentName component;
    final boolean leanback;
    final String label;
    /** The artwork for the card, at the best density the app ships. */
    final Drawable banner;
    /** A bright version of the artwork's main colour: the tint, glow, rim, bar and signature. */
    final int color;

    AppEntry(String pkg, ComponentName component, boolean leanback, String label, Drawable banner, int color) {
        this.pkg = pkg;
        this.component = component;
        this.leanback = leanback;
        this.label = label;
        this.banner = banner;
        this.color = color;
    }

    Intent launchIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
}
