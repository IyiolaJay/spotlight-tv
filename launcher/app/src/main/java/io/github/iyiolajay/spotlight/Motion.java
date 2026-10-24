package io.github.iyiolajay.spotlight;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.function.IntConsumer;

/** The design's curves, and colour fades that start from wherever the last one got to. */
final class Motion {
    private Motion() {}

    /** Strong ease-out, for things arriving and for press feedback. */
    static final Interpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
    /** The picker wheel and the card: quick to move, long to settle. */
    static final Interpolator WHEEL = new PathInterpolator(.2f, .85f, .25f, 1f);

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    /**
     * Fades a colour from {@code from} to {@code to}, calling {@code apply} each frame. Cancels
     * {@code running}; returns the new animator (null when {@code ms} is 0 and it was applied at once).
     */
    static ValueAnimator color(ValueAnimator running, int from, int to, long ms, IntConsumer apply) {
        if (running != null) running.cancel();
        if (ms <= 0 || from == to) {
            apply.accept(to);
            return null;
        }
        ValueAnimator a = ValueAnimator.ofObject(ARGB, from, to);
        a.setDuration(ms);
        a.setInterpolator(EASE_OUT);
        a.addUpdateListener(v -> apply.accept((Integer) v.getAnimatedValue()));
        a.start();
        return a;
    }

    /** The colour part way to white, keeping its alpha. */
    static int mixWhite(int c, float t) {
        return Color.argb(Color.alpha(c),
                Math.round(Color.red(c) + (255 - Color.red(c)) * t),
                Math.round(Color.green(c) + (255 - Color.green(c)) * t),
                Math.round(Color.blue(c) + (255 - Color.blue(c)) * t));
    }

    static int withAlpha(int c, float a) {
        return (Math.round(a * 255) << 24) | (c & 0xFFFFFF);
    }
}
