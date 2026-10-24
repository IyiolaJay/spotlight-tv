package io.github.iyiolajay.spotlight;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The design's measurements and type. It is laid out on a 1920 x 1080 stage (SpotlightBeach.dc.html);
 * px() turns its numbers into this screen's pixels, so every position in the code reads as in the mockup.
 */
final class Stage {
    final float k;
    final Typeface font;

    Stage(int screenWidth, Typeface font) {
        k = screenWidth / 1920f;
        this.font = font;
    }

    int px(float v) {
        return Math.round(v * k);
    }

    float fx(float v) {
        return v * k;
    }

    /** A single line of Outfit at {@code size} design px; {@code tracking} in em, as CSS letter-spacing. */
    TextView text(Context c, float size, int weight, int color, float tracking) {
        TextView t = new TextView(c);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, fx(size));
        t.setTextColor(color);
        t.setLetterSpacing(tracking);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }
}
