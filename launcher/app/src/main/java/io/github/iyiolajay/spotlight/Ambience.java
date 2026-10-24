package io.github.iyiolajay.spotlight;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The light the focused app gives off: a soft shade of its colour in the top-right corner, fading out
 * before mid-screen, and a glow around the card. Two gradient fills, redrawn only while the colour fades.
 */
final class Ambience extends View {
    private final Paint tint = new Paint(Paint.DITHER_FLAG), glow = new Paint(Paint.DITHER_FLAG);
    private final RectF tintBox, glowBox;
    private final float strength;
    private int color;
    private ValueAnimator fade;

    /** {@code strength}: the shade's opacity in the very corner (the design's Tint, 16%). */
    Ambience(Context c, Stage st, float strength) {
        super(c);
        this.strength = strength;
        tintBox = new RectF(st.fx(1920 - 1150), 0, st.fx(1920), st.fx(760));
        glowBox = new RectF(st.fx(934), st.fx(205), st.fx(934 + 1020), st.fx(205 + 620));
    }

    void setColor(int c, long ms) {
        fade = Motion.color(fade, color, c, ms, this::apply);
    }

    private void apply(int c) {
        color = c;
        int t = Motion.mixWhite(c, .15f);
        tint.setShader(Glass.radial(tintBox.right, tintBox.top, tintBox.width(), tintBox.height(),
                new int[] {Motion.withAlpha(t, strength), Motion.withAlpha(t, strength * .42f), Motion.withAlpha(t, 0)},
                new float[] {0, .38f, 1}));
        glow.setShader(Glass.radial(glowBox.centerX(), glowBox.centerY(), glowBox.width() / 2, glowBox.height() / 2,
                new int[] {Motion.withAlpha(c, .34f), Motion.withAlpha(c, .10f), Motion.withAlpha(c, 0)},
                new float[] {0, .6f, 1}));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (color == 0) return;
        c.drawRect(tintBox, tint);
        c.drawRect(glowBox, glow);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
