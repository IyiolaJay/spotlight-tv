package io.github.iyiolajay.spotlight;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The pills' menus: a frosted glass panel that grows out of its pill. The frost is a snapshot of what's
 * behind the panel, taken once as it opens, shrunk and blurred, so nothing is blurred while it's open.
 * Left and Right go to the next pill's menu, like a menu bar. Views are built per open; nothing runs
 * while it's closed.
 */
final class GlassMenu {
    static final class Item {
        final CharSequence label;
        final boolean dim, external;
        final Runnable action;
        boolean separated;
        /** All apps rows: a small picture of the app, and a state that OK flips without closing. */
        Drawable thumb;
        Supplier<CharSequence> state;

        private Item(CharSequence label, boolean dim, boolean external, Runnable action) {
            this.label = label;
            this.dim = dim;
            this.external = external;
            this.action = action;
        }

        static Item of(CharSequence label, Runnable action) {
            return new Item(label, false, false, action);
        }

        /** Listed but greyed, like an input with nothing plugged in. */
        static Item dimmed(CharSequence label, Runnable action) {
            return new Item(label, true, false, action);
        }

        /** Opens a screen of the TV's own Settings app; marked with an arrow. */
        static Item external(CharSequence label, Runnable action) {
            return new Item(label, false, true, action);
        }

        /** A row with the app's artwork and a state ("Add" / "On Home"); OK runs {@code flip}, the menu stays. */
        static Item toggle(CharSequence label, Drawable thumb, Supplier<CharSequence> state, Runnable flip) {
            Item it = new Item(label, false, false, flip);
            it.thumb = thumb;
            it.state = state;
            return it;
        }

        /** Drawn with a divider above it. */
        Item separated() {
            separated = true;
            return this;
        }
    }

    /** Opens the neighbouring pill's menu: -1 to the left, +1 to the right. */
    interface Neighbours {
        void step(int direction);
    }

    private static final int TEXT = 0xFFF5F5F7, INK = 0xFF0B0C10, DIM = 0x75EBEBF5, DIM_INK = 0xFF6B6B72;
    /** Longer lists scroll inside a window this many rows tall. */
    private static final int MAX_ROWS = 7;
    /** The snapshot is taken at 1/4 size; blurring it there costs a few thousand pixels, not a million. */
    private static final int SNAPSHOT_SCALE = 4;

    private final Activity host;
    private final FrameLayout root;
    private final Stage st;
    private final LinearLayout panel, content;
    private final FrameLayout window;
    private final Glass.Frost frost;
    private final Glass.Pill highlight;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final List<LinearLayout> rows = new ArrayList<>();
    private final List<Integer> rowTops = new ArrayList<>();
    private Neighbours neighbours;
    private int index, generation, scroll, windowH;
    private boolean showing;

    GlassMenu(Activity host, FrameLayout root, Stage st) {
        this.host = host;
        this.root = root;
        this.st = st;
        panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = st.px(10);
        panel.setPadding(pad, pad, pad, pad);
        frost = new Glass.Frost(st);
        panel.setBackground(frost);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        // Rows live in a window; a long list slides inside it
        window = new FrameLayout(host);
        content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        window.addView(content);
        highlight = new Glass.Pill(st, true, 18);
    }

    boolean isShowing() {
        return showing;
    }

    /**
     * Opens under {@code anchor} (the pill's bounds in the root, at its focused size). {@code header}
     * is shown above the rows with a divider, or null; {@code width} is in design px.
     */
    void show(Rect anchor, View header, List<Item> list, float width, Neighbours n) {
        hide(false);
        // A closing animation still running must not finish onto this menu
        panel.animate().cancel();
        items.clear();
        items.addAll(list);
        rows.clear();
        rowTops.clear();
        neighbours = n;
        index = 0;
        scroll = 0;
        panel.removeAllViews();
        content.removeAllViews();
        if (header != null) {
            panel.addView(header);
            panel.addView(divider());
        }
        int rowH = st.px(62), listH = 0;
        for (Item it : items) {
            if (it.separated) {
                View d = divider();
                content.addView(d);
                LinearLayout.LayoutParams dl = (LinearLayout.LayoutParams) d.getLayoutParams();
                listH += dl.height + dl.topMargin + dl.bottomMargin;
            }
            LinearLayout row = row(it);
            rows.add(row);
            rowTops.add(listH);
            content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
            listH += rowH;
        }
        windowH = Math.min(listH, MAX_ROWS * rowH + st.px(13));
        // Full height for the rows, so the ones below the window are laid out too
        window.updateViewLayout(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH));
        content.setTranslationY(0);
        panel.addView(window, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, windowH));
        decorate(0, true);

        int w = st.px(width), edge = st.px(36);
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = panel.getMeasuredHeight();
        int x = Math.max(edge, Math.min(anchor.left, root.getWidth() - edge - w));
        int y = anchor.bottom + st.px(14);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.width = w;
        lp.leftMargin = x;
        lp.topMargin = y;
        panel.setLayoutParams(lp);
        // Grows out of the pill it belongs to
        panel.setPivotX(anchor.centerX() - x);
        panel.setPivotY(0);
        showing = true;
        snapshotThenReveal(new Rect(x, y, x + w, y + h), ++generation);
    }

    /** Waits for a frame without the previous menu on it, copies what's behind the panel, then shows it. */
    private void snapshotThenReveal(Rect area, int gen) {
        Runnable copy = () -> {
            if (gen != generation) return;
            int[] o = new int[2];
            root.getLocationInWindow(o);
            Rect src = new Rect(area);
            src.offset(o[0], o[1]);
            if (!src.intersect(0, 0, root.getWidth() + o[0], root.getHeight() + o[1])) {
                reveal(gen, null);
                return;
            }
            Bitmap small = Bitmap.createBitmap(Math.max(1, src.width() / SNAPSHOT_SCALE),
                    Math.max(1, src.height() / SNAPSHOT_SCALE), Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(host.getWindow(), src, small,
                        result -> reveal(gen, result == PixelCopy.SUCCESS ? frosted(small) : null), main);
            } catch (RuntimeException e) {
                reveal(gen, null);
            }
        };
        root.invalidate();
        if (Build.VERSION.SDK_INT >= 29) root.getViewTreeObserver().registerFrameCommitCallback(copy);
        else main.postDelayed(copy, 34);
    }

    private void reveal(int gen, Bitmap backdrop) {
        if (gen != generation || !showing) return;
        frost.setBackdrop(backdrop);
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(.94f);
        panel.setScaleY(.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
    }

    void hide(boolean animate) {
        if (!showing) return;
        showing = false;
        generation++;
        panel.animate().cancel();
        if (!animate || panel.getVisibility() != View.VISIBLE) {
            gone();
            return;
        }
        panel.animate().alpha(0f).scaleY(.96f).setDuration(130).withEndAction(this::gone).start();
    }

    private void gone() {
        panel.setVisibility(View.GONE);
        frost.setBackdrop(null);
        // Let go of the rows (and any app artwork they hold) until the next open
        content.removeAllViews();
        panel.removeAllViews();
        rows.clear();
        items.clear();
    }

    /** Handles keys while open; false for the ones it leaves alone (volume and the like). */
    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first && neighbours != null) neighbours.step(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first) activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (first) hide(true);
                return true;
            default:
                return false;
        }
    }

    private void move(int delta) {
        int i = index + delta;
        if (i < 0 || i >= items.size()) return;
        decorate(index, false);
        index = i;
        decorate(index, true);
        // Keep the focused row inside the window
        int top = rowTops.get(i), bottom = top + st.px(62);
        int to = top < scroll ? top : bottom > scroll + windowH ? bottom - windowH : scroll;
        if (to != scroll) {
            scroll = to;
            content.animate().translationY(-scroll).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
        }
    }

    private void activate() {
        if (index >= items.size()) return;
        Item it = items.get(index);
        if (it.state != null) {
            if (it.action != null) it.action.run();
            decorate(index, true);
            return;
        }
        hide(true);
        if (it.action != null) it.action.run();
    }

    /** The focused row is solid white with dark text; one highlight drawable moves between rows. */
    private void decorate(int i, boolean focused) {
        if (i >= rows.size()) return;
        LinearLayout row = rows.get(i);
        Item it = items.get(i);
        row.setBackground(focused ? highlight : null);
        int c = focused ? (it.dim ? DIM_INK : INK) : (it.dim ? DIM : TEXT);
        ((TextView) row.findViewWithTag("label")).setTextColor(c);
        View arrow = row.findViewWithTag("arrow");
        if (arrow != null) ((ImageView) arrow).setColorFilter(Motion.withAlpha(c, focused ? .75f : .5f));
        TextView state = row.findViewWithTag("state");
        if (state != null) {
            state.setText(it.state.get());
            state.setTextColor(Motion.withAlpha(c, focused ? .7f : .6f));
        }
    }

    private LinearLayout row(Item it) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(st.px(it.thumb != null ? 12 : 22), 0, st.px(22), 0);
        if (it.thumb != null) {
            // Its own copy: the same artwork may be on the card at the same time, at another size
            Drawable.ConstantState cs = it.thumb.getConstantState();
            ImageView pic = new ImageView(host);
            pic.setImageDrawable(cs != null ? cs.newDrawable(host.getResources()) : it.thumb);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            float radius = st.fx(7);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            pic.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(st.px(72), st.px(40));
            lp.setMarginEnd(st.px(16));
            row.addView(pic, lp);
        }
        TextView label = st.text(host, 22, 500, TEXT, 0);
        label.setTag("label");
        label.setText(it.label);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (it.state != null) {
            TextView state = st.text(host, 17, 500, Motion.withAlpha(TEXT, .6f), .02f);
            state.setTag("state");
            state.setText(it.state.get());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(st.px(14));
            row.addView(state, lp);
        }
        if (it.external) {
            ImageView arrow = new ImageView(host);
            arrow.setTag("arrow");
            arrow.setImageResource(R.drawable.ic_open);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(st.px(18), st.px(18));
            lp.setMarginStart(st.px(14));
            row.addView(arrow, lp);
        }
        return row;
    }

    private View divider() {
        View d = new View(host);
        d.setBackgroundColor(Glass.white(.14f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, st.px(1)));
        lp.setMargins(st.px(18), st.px(6), st.px(18), st.px(6));
        d.setLayoutParams(lp);
        return d;
    }

    /** The snapshot made into frosted glass: blurred (three box passes, close to a Gaussian), a little more saturated. */
    private static Bitmap frosted(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        // CSS blur(36px) on the full-size screen is a sigma of 9 here; three radius-8 boxes come to about that
        int r = 8;
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, r, true);
            box(tmp, px, w, h, r, false);
        }
        // Brights are pulled down a little too, so white text stays clear over a white card
        for (int i = 0; i < px.length; i++) px[i] = saturate(px[i], 1.5f, .85f);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** One box-blur pass along rows (or columns), clamping at the edges. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w, len = rows ? w : h, span = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[at(line, Math.max(0, Math.min(len - 1, k)), w, rows)];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, rows)] = 0xFF000000 | (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int add = in[at(line, Math.min(len - 1, i + r + 1), w, rows)];
                int sub = in[at(line, Math.max(0, i - r), w, rows)];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    private static int at(int line, int i, int w, boolean rows) {
        return rows ? line * w + i : i * w + line;
    }

    private static int saturate(int p, float s, float gain) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        float l = .2126f * r + .7152f * g + .0722f * b;
        return Color.rgb(clamp((l + (r - l) * s) * gain), clamp((l + (g - l) * s) * gain), clamp((l + (b - l) * s) * gain));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
