package io.github.iyiolajay.spotlight;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Spotlight home (design E): app names on a picker wheel down the left, the focused app's card in a
 * glass slab on the right with its actions underneath, and All apps / Inputs / Wi-Fi / Settings pills top right,
 * over the black sand beach.
 *
 * Focus is kept here, not in Android's focus search, so the D-pad is deterministic: three zones (the
 * pills, the list, the card's actions) and nothing falls through between them. Everything that moves
 * is a view transform or alpha, or a colour fade on a few gradient fills; names are pre-rendered into
 * alpha masks, so the wheel scales textures instead of re-rasterising glyphs each frame.
 */
public class HomeActivity extends Activity {
    static final int TOP = 0, LIST = 1, ACTIONS = 2;
    // The pills, left to right
    private static final int LIBRARY = 0, INPUTS = 1, WIFI = 2, SETTINGS = 3, PILLS = 4;
    // The picker wheel: distance from the focused row -> offset, scale, opacity (rows bunch up as they fall away)
    private static final int[] OFFSET = {0, 120, 214, 292, 354, 402, 440};
    private static final float[] SCALE = {1f, .5f, .43f, .38f, .34f, .31f, .3f};
    private static final float[] ALPHA = {1f, .62f, .4f, .24f, .12f, .05f, 0f};
    // Above the band the wheel fades out sooner, before it reaches the clock
    private static final float[] ALPHA_ABOVE = {1f, .62f, .4f, .24f, 0f, 0f, 0f};
    private static final long HOLD_MS = 450;
    private static final int TEXT = 0xFFF5F5F7, INK = 0xFF0B0C10;
    private static final String TV_SETTINGS = "com.android.tv.settings";
    // Some firmware puts Live TV over Home at power-on (UK Freeview Play sets); see watchForBootLiveTv()
    private static final long BOOT_WINDOW_MS = 150_000, WATCH_UNTIL_MS = 120_000, WATCH_MS = 40_000, TICK_MS = 250;
    /** Home's process starts with the TV, so this is once per boot. */
    private static boolean bootWatched;

    private Stage st;
    private FrameLayout root;
    private SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** List order: favourites in the order they were added, then everything else by name. */
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> byName = new ArrayList<>();
    private final List<String> favourites = new ArrayList<>();
    private Map<String, Long> lastOpened = Collections.emptyMap();

    private Ambience ambience;
    private TextView greeting, clock, date, countGroup, countValue, metaWhen, metaValue;
    private final View[] pills = new View[PILLS];
    private final ImageView[] pillIcons = new ImageView[PILLS];
    private final TextView[] pillTexts = new TextView[PILLS];
    private final TextView[] chips = new TextView[3];
    private final Drawable[][] pillBg = new Drawable[PILLS][], chipBg = new Drawable[3][];
    private MaskView signature, rim;
    private FrameLayout slab;
    private ImageView cardFront, cardBack;
    private View meta, metaTag, band, bar;
    private GradientDrawable barShape;
    private final List<MaskView> rows = new ArrayList<>();
    private final Map<String, MaskView> rowByPkg = new HashMap<>();
    private GlassMenu menu;

    private int zone = LIST, top, focus, act;
    private AppEntry shown;
    private int barColor;
    private ValueAnimator barFade;
    private boolean holding;
    /** Home opened something itself; read by the boot watcher's thread. */
    private volatile boolean leaving;
    private int libraryTicket;
    private String restorePkg;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Runnable reload = this::reloadApps;

    // Home starts before the user is unlocked (it runs from the first seconds of boot); apps are
    // only listed once Android lets them be seen
    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Installs, removals and enable/disable arrive in bursts; reload once they settle
            main.removeCallbacks(reload);
            main.postDelayed(reload, 600);
        }
    };

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            bindClock();
        }
    };

    // ConfigReceiver (ADB) writes "ping" after changing favourites or hidden apps
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (!"ping".equals(key)) return;
        reloadApps();
        bindName();
    };

    /** OK held past HOLD_MS: an app's actions, or a pill/chip activates. */
    private final Runnable hold = () -> {
        holding = false;
        press(false);
        if (zone == LIST) {
            if (!apps.isEmpty()) setZone(ACTIONS, 1);
        } else {
            activate();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        st = new Stage(w, getResources().getFont(R.font.outfit));
        prefs = ConfigReceiver.prefs(this);
        restorePkg = prefs.getString("pkg", null);
        getWindow().setBackgroundDrawable(landscape());
        root = new FrameLayout(this);
        // Focused pills and chips grow 6% past the rows that hold them; nothing here needs cutting to its box
        root.setClipChildren(false);
        build();
        setContentView(root);
        menu = new GlassMenu(this, root, st);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        listen(packageReceiver, pf);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        listen(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        watchNetwork();
        applyZone(false);
        reloadApps();
        watchForBootLiveTv();
    }

    private void listen(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    /** The beach (scrim baked in), decoded straight into graphics memory: drawn every frame, never touched by the CPU. */
    private Drawable landscape() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.HARDWARE;
        Bitmap b = BitmapFactory.decodeResource(getResources(), R.drawable.landscape, o);
        return b == null ? new ColorDrawable(0xFF05060A) : new BitmapDrawable(getResources(), b);
    }

    // ---- layout, in the design's 1920 x 1080 px

    private <T extends View> T add(T v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size(w), size(h), Gravity.TOP | Gravity.START);
        lp.leftMargin = st.px(x);
        lp.topMargin = st.px(y);
        root.addView(v, lp);
        return v;
    }

    /** Design px, or MATCH_PARENT (-1) / WRAP_CONTENT (-2) as they are. */
    private int size(float v) {
        return v < 0 ? (int) v : st.px(v);
    }

    private void build() {
        ambience = add(new Ambience(this, st, .16f), 0, 0, -1, -1);

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        greeting = st.text(this, 17, 500, 0xB8EBEBF5, .24f);
        greeting.setAllCaps(true);
        clock = st.text(this, 62, 300, TEXT, -.03f);
        clock.setFontFeatureSettings("tnum");
        date = st.text(this, 19, 450, 0xB3EBEBF5, .14f);
        date.setAllCaps(true);
        // Fixed width: a new minute or greeting then redraws in place instead of re-laying out the screen
        when.addView(greeting, below(0));
        when.addView(clock, below(10));
        when.addView(date, below(10));
        add(when, 96, 56, 900, -2);
        bindClock();

        LinearLayout pillRow = new LinearLayout(this);
        String[] labels = {getString(R.string.all_apps), getString(R.string.inputs), getString(R.string.wifi), null};
        int[] icons = {R.drawable.ic_apps, R.drawable.ic_inputs, R.drawable.ic_wifi, R.drawable.ic_settings};
        for (int i = 0; i < PILLS; i++) {
            pills[i] = pill(i, icons[i], labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(labels[i] == null ? st.px(58) : -2, st.px(58));
            if (i > 0) lp.setMarginStart(st.px(14));
            pillRow.addView(pills[i], lp);
        }
        pills[SETTINGS].setContentDescription(getString(R.string.settings));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.rightMargin = st.px(96);
        blp.topMargin = st.px(62);
        root.addView(pillRow, blp);

        // The person's name, very faint, bottom right; part of it runs off the screen (see bindName)
        signature = new MaskView(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.END);
        slp.topMargin = st.px(766);
        root.addView(signature, slp);
        bindName();

        // The slab's drop shadow (0 50px 110px, .45): a quarter-size blurred mask, stretched
        float spread = 165;
        MaskView shadow = new MaskView(this);
        shadow.setMask(Glass.shadowMask(st.fx(816), st.fx(484), st.fx(50), st.fx(55), st.fx(spread), 4));
        shadow.setColor(Glass.black(.45f), 0);
        add(shadow, 1036 - spread, 273 + 50 - spread, 816 + 2 * spread, 484 + 2 * spread);

        // The glass slab: plate, the app-coloured lower rim, the card (two, to crossfade), and the sheen on top
        slab = add(new FrameLayout(this), 1036, 273, 816, 484);
        slab.setBackground(new Glass.Plate(st));
        slab.setForeground(new Glass.Spec(st));
        rim = new MaskView(this);
        int strip = st.px(160);
        rim.setMask(Glass.rimMask(st, st.px(816), st.px(484), strip));
        slab.addView(rim, new FrameLayout.LayoutParams(-1, strip, Gravity.BOTTOM));
        cardBack = card();
        cardFront = card();

        LinearLayout m = new LinearLayout(this);
        m.setGravity(Gravity.CENTER_VERTICAL);
        TextView tag = st.text(this, 13, 600, 0xD9EBEBF5, .2f);
        tag.setAllCaps(true);
        tag.setText(R.string.favourite);
        tag.setPadding(st.px(10), st.px(5), st.px(10), st.px(5));
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(st.fx(8));
        outline.setStroke(Math.max(1, st.px(1)), Glass.white(.32f));
        tag.setBackground(outline);
        metaTag = tag;
        metaWhen = st.text(this, 22, 400, 0xB8EBEBF5, 0);
        metaWhen.setText(R.string.last_opened);
        metaValue = st.text(this, 22, 500, 0xFFFFFFFF, 0);
        // The gap lives on the tag, so it goes when the tag does
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.setMarginEnd(st.px(16));
        m.addView(tag, tagLp);
        m.addView(metaWhen);
        m.addView(metaValue, beside(7));
        meta = add(m, 1064, 762, 760, 36);

        LinearLayout row = new LinearLayout(this);
        int[] chipText = {R.string.open, R.string.add_favourite, R.string.app_info};
        for (int i = 0; i < 3; i++) {
            TextView c = st.text(this, 21, 500, TEXT, 0);
            c.setText(chipText[i]);
            c.setGravity(Gravity.CENTER);
            c.setPadding(st.px(26), 0, st.px(26), 0);
            chipBg[i] = new Drawable[] {new Glass.Pill(st, false, 0), new Glass.Pill(st, true, 0)};
            c.setBackground(chipBg[i][0]);
            chips[i] = c;
            row.addView(c, beside(i == 0 ? 0 : 14, st.px(60)));
        }
        add(row, 1064, 818, -2, 60);

        band = add(new View(this), 72, 500, 800, 120);
        band.setBackground(new Glass.Band(st));
        band.forceHasOverlappingRendering(false);
        barShape = new GradientDrawable();
        barShape.setCornerRadius(st.fx(3));
        bar = add(new View(this), 94, 530, 6, 60);
        bar.setBackground(barShape);

        // App names go in between the bar and the count (see syncRows)
        LinearLayout count = new LinearLayout(this);
        countGroup = st.text(this, 16, 500, 0x8FEBEBF5, .24f);
        countGroup.setAllCaps(true);
        countValue = st.text(this, 16, 500, 0xE0EBEBF5, .24f);
        countValue.setFontFeatureSettings("tnum");
        count.addView(countGroup);
        count.addView(countValue, beside(18));
        add(count, 124, 986, -2, -2);
    }

    private LinearLayout.LayoutParams below(float top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = st.px(top);
        return lp;
    }

    private LinearLayout.LayoutParams beside(float start) {
        return beside(start, -2);
    }

    private LinearLayout.LayoutParams beside(float start, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, height);
        lp.setMarginStart(st.px(start));
        return lp;
    }

    private LinearLayout pill(int i, int icon, CharSequence label) {
        LinearLayout p = new LinearLayout(this);
        p.setGravity(Gravity.CENTER);
        if (label != null) p.setPadding(st.px(20), 0, st.px(24), 0);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(TEXT);
        p.addView(iv, new LinearLayout.LayoutParams(st.px(23), st.px(23)));
        pillIcons[i] = iv;
        if (label != null) {
            TextView t = st.text(this, 20, 500, TEXT, 0);
            t.setText(label);
            t.setMaxWidth(st.px(260));
            t.setEllipsize(TextUtils.TruncateAt.END);
            p.addView(t, beside(12));
            pillTexts[i] = t;
        }
        pillBg[i] = new Drawable[] {new Glass.Pill(st, false, 0), new Glass.Pill(st, true, 0)};
        p.setBackground(pillBg[i][0]);
        return p;
    }

    private ImageView card() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setForeground(new Glass.Lens(st));
        float radius = st.fx(26);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
        v.forceHasOverlappingRendering(false);
        v.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(st.px(760), st.px(428), Gravity.TOP | Gravity.START);
        lp.leftMargin = st.px(28);
        lp.topMargin = st.px(28);
        slab.addView(v, lp);
        return v;
    }

    // ---- apps

    void reloadApps() {
        if (!getSystemService(UserManager.class).isUserUnlocked()) return;
        Set<String> hidden = never();
        hidden.addAll(list("hidden", R.array.hidden_by_default));
        Context app = getApplicationContext();
        worker.execute(() -> {
            List<AppEntry> all = Apps.load(app, hidden);
            Map<String, Long> used = Apps.lastOpened(app);
            main.post(() -> applyApps(all, used));
        });
    }

    /** Firmware tools and the like: not on Home and not in All apps either. */
    private Set<String> never() {
        return new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_packages)));
    }

    /** A comma list from prefs (set over ADB or on the TV), else the config.xml default. */
    private List<String> list(String key, int fallback) {
        String saved = prefs.getString(key, null);
        if (saved == null) return Arrays.asList(getResources().getStringArray(fallback));
        List<String> out = new ArrayList<>();
        for (String s : saved.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private void applyApps(List<AppEntry> all, Map<String, Long> used) {
        if (isDestroyed()) return;
        AppEntry was = focused();
        String keep = restorePkg != null ? restorePkg : was != null ? was.pkg : null;
        restorePkg = null;
        lastOpened = used;
        byName.clear();
        byName.addAll(all);
        favourites.clear();
        favourites.addAll(new LinkedHashSet<>(list("favourites", R.array.favourite_packages)));
        order();
        syncRows();
        focus = 0;
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(keep)) focus = i;
        // Nothing to focus: the pills. Apps arriving (after unlock at boot): back to the list
        if (apps.isEmpty()) zone = TOP;
        else if (was == null && zone == TOP) zone = LIST;
        shown = null;
        showFocus(focus, false);
        applyZone(false);
    }

    private void order() {
        Map<String, AppEntry> left = new HashMap<>();
        for (AppEntry a : byName) left.put(a.pkg, a);
        apps.clear();
        for (String p : favourites) {
            AppEntry a = left.remove(p);
            if (a != null) apps.add(a);
        }
        for (AppEntry a : byName) if (left.containsKey(a.pkg)) apps.add(a);
    }

    private int favouriteCount() {
        int n = 0;
        for (AppEntry a : apps) if (favourites.contains(a.pkg)) n++;
        return n;
    }

    /** One name mask per app, kept across reorders; new ones are rendered once. */
    private void syncRows() {
        Map<String, MaskView> old = new HashMap<>(rowByPkg);
        rowByPkg.clear();
        rows.clear();
        int at = root.indexOfChild(bar) + 1;
        int pad = st.px(12);
        for (AppEntry a : apps) {
            MaskView v = old.remove(a.pkg);
            if (v != null && !a.label.equals(v.getTag())) {
                root.removeView(v);
                v = null;
            }
            if (v == null) {
                v = new MaskView(this);
                v.setTag(a.label);
                Bitmap name = MaskView.text(a.label, st.font, 300, st.fx(92), -.035f, st.px(730), st.px(108), pad);
                v.setMask(name);
                v.setColor(TEXT, 0);
                v.setAlpha(0f);
                v.setPivotX(pad);
                v.setPivotY(name.getHeight() / 2f);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(name.getWidth(), name.getHeight(), Gravity.TOP | Gravity.START);
                lp.leftMargin = st.px(124) - pad;
                lp.topMargin = st.px(506);
                root.addView(v, at, lp);
            }
            rows.add(v);
            rowByPkg.put(a.pkg, v);
        }
        for (MaskView v : old.values()) root.removeView(v);
    }

    private AppEntry focused() {
        return focus >= 0 && focus < apps.size() ? apps.get(focus) : null;
    }

    // ---- focus

    private void setFocus(int i) {
        int from = focus;
        focus = i;
        showFocus(from, true);
    }

    /** Wheel, card, colours, meta, chips and count for the focused app. */
    private void showFocus(int from, boolean animate) {
        wheel(animate);
        AppEntry a = focused();
        if (a == null) return;
        if (a != shown) {
            showCard(a, Integer.signum(focus - from), animate && shown != null);
            showColor(a.color, animate && shown != null);
            shown = a;
        }
        showMeta(animate);
        bindChips();
        bindCount();
    }

    private void wheel(boolean animate) {
        for (int i = 0; i < rows.size(); i++) {
            MaskView v = rows.get(i);
            int d = i - focus, m = Math.min(Math.abs(d), OFFSET.length - 1);
            float ty = st.fx(d < 0 ? -OFFSET[m] : OFFSET[m]);
            float a = (d < 0 ? ALPHA_ABOVE : ALPHA)[m] * (zone == LIST || d != 0 ? 1f : .8f);
            v.animate().cancel();
            if (!animate || (a == 0 && v.getAlpha() == 0)) {
                v.setTranslationY(ty);
                v.setScaleX(SCALE[m]);
                v.setScaleY(SCALE[m]);
                v.setAlpha(a);
            } else {
                v.animate().translationY(ty).scaleX(SCALE[m]).scaleY(SCALE[m]).alpha(a)
                        .setDuration(520).setInterpolator(Motion.WHEEL).start();
            }
        }
    }

    /** The new artwork slides in from the direction of travel while the old one leaves the other way. */
    private void showCard(AppEntry a, int dir, boolean animate) {
        ImageView in = cardBack, out = cardFront;
        cardFront = in;
        cardBack = out;
        in.animate().cancel();
        out.animate().cancel();
        in.setImageDrawable(a.banner);
        if (!animate) {
            in.setAlpha(1f);
            in.setTranslationY(0);
            in.setScaleX(1f);
            in.setScaleY(1f);
            out.setAlpha(0f);
            return;
        }
        float slide = st.fx(28) * (dir == 0 ? 1 : dir);
        in.setTranslationY(slide);
        in.setScaleX(.98f);
        in.setScaleY(.98f);
        in.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Motion.WHEEL).start();
        out.animate().alpha(0f).translationY(-slide).scaleX(.98f).scaleY(.98f).setDuration(520).setInterpolator(Motion.WHEEL).start();
    }

    private void showColor(int c, boolean animate) {
        ambience.setColor(c, animate ? 560 : 0);
        rim.setColor(c, animate ? 520 : 0);
        signature.setColor(Motion.withAlpha(Motion.mixWhite(c, .6f), .1f), animate ? 700 : 0);
        barFade = Motion.color(barFade, barColor, c, animate ? 320 : 0, v -> {
            barColor = v;
            barShape.setColor(v);
        });
    }

    /** Fades out, swaps, fades back in: while the wheel spins it stays out of the way. */
    private void showMeta(boolean animate) {
        meta.animate().cancel();
        if (!animate) {
            bindMeta();
            meta.setAlpha(1f);
            return;
        }
        meta.animate().alpha(0f).setDuration(120).setInterpolator(Motion.EASE_OUT).withEndAction(() -> {
            bindMeta();
            meta.animate().alpha(1f).setDuration(300).setInterpolator(Motion.EASE_OUT).start();
        }).start();
    }

    private void bindMeta() {
        AppEntry a = focused();
        if (a == null) return;
        boolean fav = favourites.contains(a.pkg);
        metaTag.setVisibility(fav ? View.VISIBLE : View.GONE);
        Long t = lastOpened.get(a.pkg);
        int v = t == null ? View.GONE : View.VISIBLE;
        metaWhen.setVisibility(v);
        metaValue.setVisibility(v);
        if (t != null) metaValue.setText(opened(t));
    }

    /** "today at 20:12", "yesterday at 21:40", "Monday at 19:05", else the date. */
    private String opened(long t) {
        ZoneId here = ZoneId.systemDefault();
        long days = LocalDate.now(here).toEpochDay() - Instant.ofEpochMilli(t).atZone(here).toLocalDate().toEpochDay();
        String time = DateFormat.getTimeFormat(this).format(new Date(t));
        if (days <= 0) return getString(R.string.today_at, time);
        if (days == 1) return getString(R.string.yesterday_at, time);
        if (days < 7) return getString(R.string.day_at, DateFormat.format("EEEE", t), time);
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMM"), t).toString();
    }

    private void bindChips() {
        AppEntry a = focused();
        boolean fav = a != null && favourites.contains(a.pkg);
        String label = getString(fav ? R.string.remove_favourite : R.string.add_favourite);
        if (!label.contentEquals(chips[1].getText())) chips[1].setText(label);
    }

    private void bindCount() {
        int favs = favouriteCount();
        boolean inFavs = focus < favs;
        countGroup.setText(inFavs ? R.string.favourites : R.string.all_apps);
        int n = inFavs ? favs : apps.size() - favs, i = inFavs ? focus + 1 : focus - favs + 1;
        countValue.setText(String.format(Locale.ROOT, "%02d / %02d", i, n));
    }

    // ---- zones

    private void setZone(int z) {
        setZone(z, act);
    }

    private void setZone(int z, int action) {
        zone = z;
        act = action;
        applyZone(true);
    }

    /** Pills and chips lift when focused; the band and bar dim when the list isn't. */
    private void applyZone(boolean animate) {
        for (int i = 0; i < PILLS; i++) lift(pills[i], pillBg[i], pillIcons[i], pillTexts[i], zone == TOP && top == i, animate);
        for (int i = 0; i < 3; i++) lift(chips[i], chipBg[i], null, chips[i], zone == ACTIONS && act == i, animate);
        fade(band, zone == LIST ? 1f : .45f, animate ? 260 : 0);
        fade(bar, zone == LIST ? 1f : .5f, animate ? 260 : 0);
        if (focus < rows.size()) fade(rows.get(focus), zone == LIST ? 1f : .8f, animate ? 320 : 0);
    }

    private void lift(View v, Drawable[] bg, ImageView icon, TextView text, boolean on, boolean animate) {
        if (v.getBackground() != bg[on ? 1 : 0]) v.setBackground(bg[on ? 1 : 0]);
        int c = on ? INK : TEXT;
        if (icon != null) icon.setColorFilter(c);
        if (text != null) text.setTextColor(c);
        scale(v, on ? 1.06f : 1f, animate ? 240 : 0);
    }

    private static void scale(View v, float s, long ms) {
        v.animate().scaleX(s).scaleY(s).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
    }

    private static void fade(View v, float a, long ms) {
        v.animate().alpha(a).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
    }

    /** OK held down: the slab (or the pill/chip) gives a little under the press. */
    private void press(boolean on) {
        if (zone == LIST) {
            scale(slab, on ? .97f : 1f, 240);
            scale(band, on ? .985f : 1f, 240);
        } else if (zone == TOP) {
            scale(pills[top], on ? 1f : 1.06f, 240);
        } else {
            scale(chips[act], on ? 1f : 1.06f, 240);
        }
    }

    // ---- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (menu.isShowing() && menu.onKey(e)) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(e.getKeyCode());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Opens on release; held past HOLD_MS it shows the app's actions instead
                if (down && e.getRepeatCount() == 0) {
                    holding = true;
                    press(true);
                    main.postDelayed(hold, HOLD_MS);
                } else if (down && holding && e.isLongPress()) {
                    // Some remotes (and `input keyevent --longpress`) flag the hold themselves, then let go at once
                    main.removeCallbacks(hold);
                    hold.run();
                } else if (!down && holding) {
                    cancelHold();
                    activate();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0 && zone == LIST && !apps.isEmpty()) setZone(ACTIONS, 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) back();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void cancelHold() {
        if (!holding) return;
        holding = false;
        main.removeCallbacks(hold);
        press(false);
    }

    private void move(int key) {
        cancelHold();
        int n = apps.size();
        if (zone == TOP) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && top > 0) setTop(top - 1);
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && top < PILLS - 1) setTop(top + 1);
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN && n > 0) setZone(LIST);
        } else if (zone == LIST) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (focus > 0) setFocus(focus - 1);
                else setZone(TOP);
            } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (focus < n - 1) setFocus(focus + 1);
            } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && n > 0) {
                setZone(ACTIONS, 0);
            }
        } else {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (act > 0) setZone(ACTIONS, act - 1);
                else setZone(LIST);
            } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (act < 2) setZone(ACTIONS, act + 1);
            } else if (key == KeyEvent.KEYCODE_DPAD_UP) {
                setZone(LIST);
            }
        }
    }

    private void setTop(int i) {
        top = i;
        applyZone(true);
    }

    private void back() {
        cancelHold();
        if (zone != LIST && !apps.isEmpty()) setZone(LIST);
        else if (focus != 0) setFocus(0);
    }

    private void activate() {
        if (zone == TOP) {
            openMenu(top);
            return;
        }
        AppEntry a = focused();
        if (a == null) return;
        if (zone == LIST || act == 0) launch(a);
        else if (act == 1) toggleFavourite(a);
        else open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null)), null);
    }

    private void toggleFavourite(AppEntry a) {
        if (!favourites.remove(a.pkg)) favourites.add(a.pkg);
        prefs.edit().putString("favourites", TextUtils.join(",", favourites)).apply();
        order();
        syncRows();
        // The app moves to its new place in the list and focus goes with it
        int from = focus;
        focus = apps.indexOf(a);
        showFocus(from, true);
    }

    // ---- opening things

    /** Zooms the app up out of its card: the system scales the card's picture into the app's window. */
    private void launch(AppEntry a) {
        Bundle opts = null;
        ImageView card = cardFront;
        if (card.getWidth() > 0) {
            try {
                Bitmap thumb = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(thumb);
                c.clipPath(Glass.rrect(new RectF(0, 0, card.getWidth(), card.getHeight()), st.fx(26)));
                card.draw(c);
                opts = ActivityOptions.makeThumbnailScaleUpAnimation(card, thumb, 0, 0).toBundle();
            } catch (RuntimeException e) {
                opts = ActivityOptions.makeScaleUpAnimation(card, 0, 0, card.getWidth(), card.getHeight()).toBundle();
            }
        }
        open(a.launchIntent(), opts);
    }

    void open(Intent i) {
        open(i, null);
    }

    private void open(Intent i, Bundle opts) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        leaving = true;
        try {
            startActivity(i, opts);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- the pills' menus

    private void openMenu(int i) {
        if (i < 0 || i >= PILLS) return;
        if (zone != TOP || top != i) {
            zone = TOP;
            top = i;
            applyZone(true);
        }
        Rect at = bounds(pills[i], 1.06f);
        GlassMenu.Neighbours next = d -> openMenu(top + d);
        if (i == LIBRARY) {
            openLibrary(at, next);
        } else if (i == INPUTS) {
            menu.show(at, null, Inputs.items(this), 300, next);
        } else if (i == WIFI) {
            Network.Status s = Network.read(this);
            List<GlassMenu.Item> items = new ArrayList<>();
            addSetting(items, R.string.network_internet, new Intent(Settings.ACTION_WIFI_SETTINGS));
            menu.show(at, Network.header(this, st, s), items, 420, next);
        } else {
            menu.show(at, null, settingsItems(), 360, next);
        }
    }

    /**
     * All apps: every app on the TV, the ones not on Home first. OK puts an app on Home or takes it off,
     * with the panel still open. Apps already on Home reuse their loaded artwork; only the others are
     * read, once, when it opens, and let go when it closes.
     */
    private void openLibrary(Rect at, GlassMenu.Neighbours next) {
        menu.hide(false);
        Map<String, AppEntry> loaded = new HashMap<>();
        for (AppEntry a : byName) loaded.put(a.pkg, a);
        Set<String> never = never();
        Context app = getApplicationContext();
        int ticket = ++libraryTicket;
        worker.execute(() -> {
            List<AppEntry> all = Apps.load(app, never, loaded);
            main.post(() -> {
                // Still wanted: nothing else was opened or chosen while it loaded
                if (ticket != libraryTicket || zone != TOP || top != LIBRARY || menu.isShowing() || isDestroyed()) return;
                menu.show(at, libraryHeader(), libraryItems(all), 560, next);
            });
        });
    }

    private List<GlassMenu.Item> libraryItems(List<AppEntry> all) {
        Set<String> off = new HashSet<>(list("hidden", R.array.hidden_by_default));
        List<GlassMenu.Item> notOnHome = new ArrayList<>(), onHome = new ArrayList<>();
        for (AppEntry a : all) {
            GlassMenu.Item it = GlassMenu.Item.toggle(a.label, a.banner,
                    () -> getString(isOnHome(a) ? R.string.on_home : R.string.add_to_home), () -> toggleHome(a));
            (off.contains(a.pkg) ? notOnHome : onHome).add(it);
        }
        if (!notOnHome.isEmpty() && !onHome.isEmpty()) onHome.get(0).separated();
        notOnHome.addAll(onHome);
        return notOnHome;
    }

    private View libraryHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(st.px(20), st.px(16), st.px(20), st.px(14));
        TextView title = st.text(this, 27, 500, TEXT, -.01f);
        title.setText(R.string.all_apps);
        TextView hint = st.text(this, 18, 400, 0xB8EBEBF5, 0);
        hint.setText(R.string.library_hint);
        box.addView(title);
        box.addView(hint, below(8));
        return box;
    }

    private boolean isOnHome(AppEntry a) {
        return !list("hidden", R.array.hidden_by_default).contains(a.pkg);
    }

    /** Puts an app on Home or takes it off; the list behind the panel follows straight away. */
    private void toggleHome(AppEntry a) {
        Set<String> off = new LinkedHashSet<>(list("hidden", R.array.hidden_by_default));
        boolean adding = off.remove(a.pkg);
        if (!adding) off.add(a.pkg);
        prefs.edit().putString("hidden", TextUtils.join(",", off)).apply();
        AppEntry was = focused();
        for (int i = byName.size() - 1; i >= 0; i--) if (byName.get(i).pkg.equals(a.pkg)) byName.remove(i);
        if (adding) {
            Collator collator = Collator.getInstance();
            int at = 0;
            while (at < byName.size() && collator.compare(byName.get(at).label, a.label) < 0) at++;
            byName.add(at, a);
        }
        order();
        syncRows();
        int from = focus;
        focus = 0;
        for (int i = 0; i < apps.size(); i++) if (was != null && apps.get(i).pkg.equals(was.pkg)) focus = i;
        if (was != null && !adding && was.pkg.equals(a.pkg)) focus = Math.min(from, apps.size() - 1);
        focus = Math.max(0, focus);
        showFocus(from, true);
    }

    /** Shortcuts into the TV's Settings app; a screen this TV doesn't have isn't listed. */
    private List<GlassMenu.Item> settingsItems() {
        List<GlassMenu.Item> out = new ArrayList<>();
        addSetting(out, R.string.display_sound, new Intent(Settings.ACTION_SOUND_SETTINGS));
        addSetting(out, R.string.pair_accessory, new Intent("com.google.android.intent.action.CONNECT_INPUT"));
        addSetting(out, R.string.apps, new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Two screens answer the date intent on this TV (one is a sleep timer); ask for the clock by name
        Intent dateTime = new Intent(Settings.ACTION_DATE_SETTINGS).setClassName(TV_SETTINGS, TV_SETTINGS + ".system.DateTimeActivity");
        if (dateTime.resolveActivity(getPackageManager()) == null) dateTime = new Intent(Settings.ACTION_DATE_SETTINGS);
        addSetting(out, R.string.date_time, dateTime);
        addSetting(out, R.string.about, new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        int before = out.size();
        addSetting(out, R.string.all_settings, new Intent(Settings.ACTION_SETTINGS));
        if (before > 0 && out.size() > before) out.get(before).separated();
        return out;
    }

    private void addSetting(List<GlassMenu.Item> out, int label, Intent target) {
        if (target.resolveActivity(getPackageManager()) == null) return;
        out.add(GlassMenu.Item.external(getString(label), () -> open(target)));
    }

    /** Where {@code v} sits in the root at {@code scale}: menus open before its focus animation ends. */
    private Rect bounds(View v, float scale) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        root.offsetDescendantRectToMyCoords(v, r);
        r.inset(-Math.round(r.width() * (scale - 1) / 2), -Math.round(r.height() * (scale - 1) / 2));
        return r;
    }

    // ---- clock and network

    /** The name set at install (ConfigReceiver "name"), else config.xml's; empty means none. */
    private String name() {
        String n = prefs.getString("name", null);
        return (n != null ? n : getString(R.string.default_name)).trim();
    }

    /** The name in the greeting and as the faint signature. Without one, neither is shown. */
    private void bindName() {
        String n = name();
        if (n.isEmpty()) {
            signature.setVisibility(View.GONE);
            signature.setMask(null);
        } else {
            int pad = st.px(24), widest = st.px(1300);
            Bitmap sig = MaskView.text(n, st.font, 200, st.fx(340), -.045f, Integer.MAX_VALUE, st.px(340), pad);
            if (sig.getWidth() > widest) {
                // A long name gets smaller rather than cut off
                float size = st.fx(340) * widest / sig.getWidth();
                sig = MaskView.text(n, st.font, 200, size, -.045f, Integer.MAX_VALUE, st.px(340), pad);
            }
            signature.setMask(sig);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) signature.getLayoutParams();
            lp.width = sig.getWidth();
            lp.height = sig.getHeight();
            lp.rightMargin = st.px(52) - pad;
            signature.setLayoutParams(lp);
            signature.setVisibility(View.VISIBLE);
        }
        bindClock();
    }

    private void bindClock() {
        if (greeting == null) return;
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String part = getString(hour >= 5 && hour < 12 ? R.string.good_morning : hour >= 12 && hour < 17
                ? R.string.good_afternoon : R.string.good_evening);
        String n = name();
        greeting.setText(n.isEmpty() ? part : getString(R.string.greeting_named, part, n));
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    private void watchNetwork() {
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(android.net.Network n, NetworkCapabilities nc) {
                main.post(HomeActivity.this::updateNetwork);
            }

            @Override
            public void onLost(android.net.Network n) {
                main.post(HomeActivity.this::updateNetwork);
            }
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCallback);
    }

    private void updateNetwork() {
        Network.Status s = Network.read(this);
        String label = Network.label(this, s);
        if (!label.contentEquals(pillTexts[WIFI].getText())) pillTexts[WIFI].setText(label);
        pillIcons[WIFI].setImageResource(Network.icon(s));
    }

    // ---- lifecycle

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the menu, or go back to the first app. Android pauses
        // the activity before onNewIntent, so "already home" is window focus without BROUGHT_TO_FRONT.
        boolean alreadyHome = hasWindowFocus() && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0;
        if (!alreadyHome) return;
        if (menu.isShowing()) {
            menu.hide(true);
            return;
        }
        if (zone != LIST && !apps.isEmpty()) setZone(LIST);
        if (focus != 0) setFocus(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        leaving = false;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        listen(clockReceiver, f);
        bindClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNetwork();
        // Back from an app: its "Last opened" has changed
        Context app = getApplicationContext();
        worker.execute(() -> {
            Map<String, Long> used = Apps.lastOpened(app);
            main.post(() -> {
                if (used.equals(lastOpened)) return;
                lastOpened = used;
                if (focused() != null) bindMeta();
            });
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelHold();
        menu.hide(false);
        AppEntry a = focused();
        prefs.edit().putString("pkg", a == null ? null : a.pkg).apply();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(clockReceiver);
    }

    /**
     * Some firmware starts Live TV on top of Home at power-on: UK Freeview Play sets do it for
     * certification, and no setting turns it off short of changing the TV's country. Turned on with
     * ConfigReceiver "boot_to_home" (or config.xml), Home watches from the first seconds of boot (it
     * is direct-boot aware): until two minutes after boot it checks a few times a second which app
     * is in front, and the moment it's one of live_tv_packages it comes straight back, once.
     * Anything opened from Home ends the watch.
     */
    private void watchForBootLiveTv() {
        if (bootWatched || SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return;
        bootWatched = true;
        if (!prefs.getBoolean("boot_to_home", getResources().getBoolean(R.bool.boot_to_home))) return;
        Set<String> liveTv = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.live_tv_packages)));
        // Everything since this boot began, with room for the clock settling
        long since = System.currentTimeMillis() - SystemClock.elapsedRealtime() - 60_000;
        long until = Math.max(SystemClock.elapsedRealtime() + WATCH_MS, WATCH_UNTIL_MS);
        Context app = getApplicationContext();
        // Its own short-lived thread: the main thread is busy while the TV is still starting up
        HandlerThread thread = new HandlerThread("boot-watch");
        thread.start();
        Handler watch = new Handler(thread.getLooper());
        watch.post(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || leaving) {
                    thread.quitSafely();
                    return;
                }
                if (liveTv.contains(Apps.foreground(app, since))) {
                    app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .setPackage(app.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    thread.quitSafely();
                    return;
                }
                // Other apps don't end the watch: boot splash screens come and go before Live TV does
                if (SystemClock.elapsedRealtime() < until) watch.postDelayed(this, TICK_MS);
                else thread.quitSafely();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(unlockReceiver);
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (netCallback != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }
}
