package io.github.iyiolajay.spotlight;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads launchable apps, their sharpest artwork and colours, and when each was last opened. */
final class Apps {
    private Apps() {}

    /** TV apps first (they have banners), then phone-style apps not already listed; sorted by name. */
    static List<AppEntry> load(Context c, Set<String> hidden) {
        return load(c, hidden, Collections.emptyMap());
    }

    /** As above, reusing entries already in {@code loaded} instead of reading their artwork again. */
    static List<AppEntry> load(Context c, Set<String> hidden, Map<String, AppEntry> loaded) {
        PackageManager pm = c.getPackageManager();
        Map<String, AppEntry> out = new LinkedHashMap<>();
        collect(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, hidden, loaded, out);
        collect(c, pm, Intent.CATEGORY_LAUNCHER, false, hidden, loaded, out);
        List<AppEntry> list = new ArrayList<>(out.values());
        Collator collator = Collator.getInstance();
        Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private static void collect(Context c, PackageManager pm, String category, boolean leanback,
                                Set<String> hidden, Map<String, AppEntry> loaded, Map<String, AppEntry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(c.getPackageName()) || hidden.contains(pkg) || out.containsKey(pkg)) continue;
            // Factory/design menus can flip board flags just by opening; never offer them
            if (pkg.contains("factory")) continue;
            // Phone-style entries: only apps the user installed, not firmware stubs and test screens
            if (!leanback && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            AppEntry known = loaded.get(pkg);
            if (known != null && known.component.getClassName().equals(ai.name)) {
                out.put(pkg, known);
                continue;
            }
            Drawable banner = sharpest(pm, ai.applicationInfo, ai.banner != 0 ? ai.banner : ai.applicationInfo.banner);
            if (banner == null) banner = ai.loadBanner(pm);
            if (banner == null) {
                Drawable icon = sharpest(pm, ai.applicationInfo, ai.getIconResource());
                banner = new FallbackBanner(icon != null ? icon : ri.loadIcon(pm));
            }
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            out.put(pkg, new AppEntry(pkg, new ComponentName(pkg, ai.name), leanback, label, banner, colorOf(banner)));
        }
    }

    /**
     * The drawable at the highest density the app ships, decoded at that file's own size: the card is
     * 760px wide, so a TV-density banner (320px) would be stretched soft. Null when there is none.
     */
    private static Drawable sharpest(PackageManager pm, ApplicationInfo app, int id) {
        if (id == 0) return null;
        try {
            Resources r = pm.getResourcesForApplication(app);
            TypedValue v = new TypedValue();
            r.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, v, true);
            // Unqualified or density-free files gain nothing here; the normal loader handles them
            if (v.density == TypedValue.DENSITY_DEFAULT || v.density == TypedValue.DENSITY_NONE) return null;
            // Asking for the matched file's own density stops Android rescaling it to the screen's
            return r.getDrawableForDensity(id, v.density, null);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    /** A bright, saturated version of the artwork's dominant hue (the design's app colour). */
    static int colorOf(Drawable d) {
        float hue = dominantHue(d, 48, 27);
        return hue < 0 ? 0xFFB8C4E0 : Color.HSVToColor(new float[] {hue, 0.75f, 1f});
    }

    /**
     * Hue averaged over saturated pixels (as vectors, so reds either side of 0 don't cancel),
     * weighted towards vivid ones; -1 when the artwork is essentially grey.
     */
    static float dominantHue(Drawable d, int w, int h) {
        Drawable.ConstantState cs = d.getConstantState();
        Drawable copy = cs != null ? cs.newDrawable().mutate() : d;
        Rect old = copy.copyBounds();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(bmp));
        copy.setBounds(old);
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();

        float[] hsv = new float[3];
        double sx = 0, sy = 0, weight = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            double wgt = hsv[1] * hsv[1] * hsv[2];
            double a = Math.toRadians(hsv[0]);
            sx += Math.cos(a) * wgt;
            sy += Math.sin(a) * wgt;
            weight += wgt;
        }
        if (weight < px.length * 0.02) return -1;
        float hue = (float) Math.toDegrees(Math.atan2(sy, sx));
        return hue < 0 ? hue + 360 : hue;
    }

    /**
     * When each app was last on screen, from usage access (granted over ADB: appops GET_USAGE_STATS).
     * One read when Home loads or comes back; empty without the permission.
     */
    static Map<String, Long> lastOpened(Context c) {
        Map<String, Long> out = new HashMap<>();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600 * 1000, now);
        } catch (RuntimeException e) {
            return out;
        }
        if (stats == null) return out;
        for (UsageStats s : stats) {
            long t = Build.VERSION.SDK_INT >= 29 ? s.getLastTimeVisible() : s.getLastTimeUsed();
            if (t > 0) out.merge(s.getPackageName(), t, Math::max);
        }
        return out;
    }

    private static final String DREAM = "android.service.dreams.DreamActivity";

    /** The app most recently brought to the front since {@code since} (wall clock), or null. */
    static String foreground(Context c, long since) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null) return null;
        String top = null;
        try {
            UsageEvents events = usm.queryEvents(since, System.currentTimeMillis() + 1000);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                // The screensaver reports itself in front at boot, beside the app that really is
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && !DREAM.equals(e.getClassName())) {
                    top = e.getPackageName();
                }
            }
        } catch (RuntimeException ignored) {
            // no usage access
        }
        return top;
    }

    /** For apps without a TV banner: the icon centred on a dark tile tinted from the icon. */
    static final class FallbackBanner extends Drawable {
        private final Drawable icon;
        private final Paint bg = new Paint();

        FallbackBanner(Drawable icon) {
            this.icon = icon;
            float hue = dominantHue(icon, 24, 24);
            bg.setColor(hue < 0 ? 0xFF2C2C2E : Color.HSVToColor(new float[] {hue, 0.5f, 0.3f}));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawRect(b, bg);
            int s = Math.round(b.height() * 0.5f);
            int l = b.centerX() - s / 2, t = b.centerY() - s / 2;
            icon.setBounds(l, t, l + s, t + s);
            icon.draw(c);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
