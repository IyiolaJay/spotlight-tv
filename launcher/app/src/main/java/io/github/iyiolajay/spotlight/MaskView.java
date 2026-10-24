package io.github.iyiolajay.spotlight;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Draws an alpha-only bitmap in one colour, stretched to the view: the app names, the signature, the
 * plate's coloured rim and its shadow. Text is rendered into the mask once, so scaling a name on the
 * picker wheel moves a texture instead of re-rasterising glyphs every frame.
 */
final class MaskView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private Bitmap mask;
    private int color;
    private ValueAnimator fade;

    MaskView(Context c) {
        super(c);
    }

    void setMask(Bitmap b) {
        mask = b;
        invalidate();
    }

    Bitmap mask() {
        return mask;
    }

    /** Fades to {@code c} over {@code ms} (0: at once). */
    void setColor(int c, long ms) {
        fade = Motion.color(fade, color, c, ms, this::applyColor);
    }

    private void applyColor(int c) {
        color = c;
        paint.setColor(c);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mask == null) return;
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(mask, null, dst, paint);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * One line of text as an alpha mask, set like CSS line-height: the glyphs centred in a box
     * {@code lineH} tall. Ellipsised to {@code maxW}; {@code pad} keeps overhanging glyphs inside.
     */
    static Bitmap text(CharSequence s, Typeface font, int weight, float size, float tracking, int maxW,
                       int lineH, int pad) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(font);
        p.setFontVariationSettings("'wght' " + weight);
        p.setTextSize(size);
        p.setLetterSpacing(tracking);
        CharSequence t = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END);
        int w = (int) Math.ceil(p.measureText(t, 0, t.length())) + 2 * pad;
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), lineH, Bitmap.Config.ALPHA_8);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        new Canvas(b).drawText(t, 0, t.length(), pad, baseline, p);
        return b;
    }
}
