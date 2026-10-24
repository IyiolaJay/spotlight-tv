package io.github.iyiolajay.spotlight;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The design's glass, drawn with gradients only: no blur and nothing animating inside a surface.
 * A CSS inset highlight ("inset 0 1px 0") becomes a crescent, the shape minus itself moved by 1px.
 * Each surface builds its paths and shaders once, when its size is known.
 */
final class Glass {
    private Glass() {}

    static int white(float a) {
        return Color.argb(Math.round(a * 255), 255, 255, 255);
    }

    static int black(float a) {
        return Color.argb(Math.round(a * 255), 0, 0, 0);
    }

    static RectF inset(RectF r, float d) {
        return new RectF(r.left + d, r.top + d, r.right - d, r.bottom - d);
    }

    static Path rrect(RectF r, float radius) {
        Path p = new Path();
        p.addRoundRect(r, radius, radius, Path.Direction.CW);
        return p;
    }

    /** What {@code r} keeps once it's covered by itself moved down {@code dy}: a lit top (dy > 0) or a bottom edge. */
    static Path crescent(RectF r, float radius, float dy) {
        RectF moved = new RectF(r);
        moved.offset(0, dy);
        Path p = rrect(r, radius);
        p.op(rrect(moved, radius), Path.Op.DIFFERENCE);
        return p;
    }

    /** A CSS linear-gradient at {@code deg} (0 points up, 90 right) across {@code r}. */
    static LinearGradient linear(float deg, RectF r, int[] colors, float[] stops) {
        double a = Math.toRadians(deg);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float half = (Math.abs(r.width() * dx) + Math.abs(r.height() * dy)) / 2f;
        return new LinearGradient(r.centerX() - dx * half, r.centerY() - dy * half,
                r.centerX() + dx * half, r.centerY() + dy * half, colors, stops, Shader.TileMode.CLAMP);
    }

    /** A CSS elliptical radial-gradient centred on (cx, cy) with radii rx and ry. */
    static RadialGradient radial(float cx, float cy, float rx, float ry, int[] colors, float[] stops) {
        RadialGradient g = new RadialGradient(cx, cy, rx, colors, stops, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, cy);
        g.setLocalMatrix(m);
        return g;
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float width) {
        Paint p = paint(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        return p;
    }

    /** BlurMaskFilter takes a radius, not a sigma (a CSS box-shadow blur of B is a sigma of B / 2). */
    private static float blurRadius(float sigma) {
        return Math.max(.5f, (sigma - .5f) / .57735f);
    }

    abstract static class Surface extends Drawable {
        final RectF box = new RectF();

        @Override
        protected void onBoundsChange(Rect b) {
            box.set(b);
            if (!box.isEmpty()) build(box);
        }

        abstract void build(RectF r);

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The slab the card sits in (.sf-plate): a rim that catches the light top-left, a frosted fill, a lit top edge. */
    static final class Plate extends Surface {
        private final float radius, border, hair;
        private final Paint rim = paint(0), fill = paint(0), lit = paint(white(.42f));
        private RectF inner;
        private Path litEdge;

        Plate(Stage st) {
            radius = st.fx(50);
            border = st.fx(1.5f);
            hair = st.fx(1);
        }

        @Override
        void build(RectF r) {
            // As in CSS, the rim's gradient lies under the whole plate and the fill over the inside of it
            rim.setShader(linear(140, r, new int[] {white(.78f), white(.12f), white(.04f), white(.34f)},
                    new float[] {0, .3f, .62f, 1}));
            inner = inset(r, border);
            fill.setShader(linear(160, inner, new int[] {white(.17f), white(.05f), white(.09f)}, new float[] {0, .42f, 1}));
            litEdge = crescent(inner, radius - border, hair);
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(box, radius, radius, rim);
            c.drawRoundRect(inner, radius - border, radius - border, fill);
            c.drawPath(litEdge, lit);
        }
    }

    /** The sheen over the plate's top-left corner, and a faint one bottom-right (.sf-spec). */
    static final class Spec extends Surface {
        private final float radius;
        private final Paint corner = paint(0), far = paint(0);

        Spec(Stage st) {
            radius = st.fx(50);
        }

        @Override
        void build(RectF r) {
            float w = r.width(), h = r.height();
            corner.setShader(radial(r.left + .16f * w, r.top, .58f * w, .42f * h,
                    new int[] {white(.30f), white(0)}, new float[] {0, .72f}));
            far.setShader(radial(r.left + .92f * w, r.bottom, .40f * w, .30f * h,
                    new int[] {white(.10f), white(0)}, new float[] {0, .70f}));
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(box, radius, radius, corner);
            c.drawRoundRect(box, radius, radius, far);
        }
    }

    /** Glass over the app's artwork (.sf-card .lens): light falling off, a hairline ring, lit top, shaded bottom. */
    static final class Lens extends Surface {
        private final float radius, hair;
        private final Paint wash = paint(0), ring, top = paint(white(.34f)), bottom = paint(black(.24f));
        private RectF ringBox;
        private Path topEdge, bottomEdge;

        Lens(Stage st) {
            radius = st.fx(26);
            hair = st.fx(1);
            ring = stroke(white(.26f), hair);
        }

        @Override
        void build(RectF r) {
            wash.setShader(linear(165, r, new int[] {white(.24f), white(.05f), white(0), black(0), black(.16f)},
                    new float[] {0, .22f, .40f, .72f, 1}));
            ringBox = inset(r, hair / 2);
            topEdge = crescent(r, radius, 2 * hair);
            bottomEdge = crescent(r, radius, -2 * hair);
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(box, radius, radius, wash);
            c.drawRoundRect(ringBox, radius - hair / 2, radius - hair / 2, ring);
            c.drawPath(topEdge, top);
            c.drawPath(bottomEdge, bottom);
        }
    }

    /** The selection band behind the focused app's name (.sf-band). */
    static final class Band extends Surface {
        private final float radius, hair;
        private final Paint fill = paint(0), lit = paint(white(.14f)), ring;
        private RectF ringBox;
        private Path litEdge;

        Band(Stage st) {
            radius = st.fx(30);
            hair = st.fx(1);
            ring = stroke(white(.07f), hair);
        }

        @Override
        void build(RectF r) {
            fill.setShader(linear(180, r, new int[] {white(.10f), white(.05f)}, null));
            ringBox = inset(r, hair / 2);
            litEdge = crescent(r, radius, hair);
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(box, radius, radius, fill);
            c.drawPath(litEdge, lit);
            c.drawRoundRect(ringBox, radius - hair / 2, radius - hair / 2, ring);
        }
    }

    /**
     * Pills, chips and the focused menu row: frosted (.sf-pill), or solid white when focused.
     * A radius of 0 rounds the ends fully.
     */
    static final class Pill extends Surface {
        private final boolean solid;
        private final float corner, hair;
        private final Paint fill, lit = paint(white(.2f)), ring;
        private float radius;
        private RectF ringBox;
        private Path litEdge;

        Pill(Stage st, boolean solid, float radius) {
            this.solid = solid;
            corner = st.fx(radius);
            hair = st.fx(1);
            fill = paint(solid ? 0xFFF5F5F7 : white(.12f));
            ring = stroke(white(.1f), hair);
        }

        @Override
        void build(RectF r) {
            radius = corner > 0 ? corner : r.height() / 2;
            ringBox = inset(r, hair / 2);
            litEdge = crescent(r, radius, hair);
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(box, radius, radius, fill);
            if (solid) return;
            c.drawPath(litEdge, lit);
            c.drawRoundRect(ringBox, radius - hair / 2, radius - hair / 2, ring);
        }
    }

    /**
     * The menus' glass (.sf-menu): the blurred snapshot of what's behind, a smoky tint that keeps text
     * readable over the bright sky, a sheen, a lit top edge and a gradient rim. Without a snapshot it
     * falls back to a near-solid panel.
     */
    static final class Frost extends Surface {
        private final float radius, hair;
        private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = paint(0), wash = paint(0), sheen = paint(0), lit = paint(white(.32f)), rim;
        private Bitmap image;
        private RectF rimBox;
        private Path litEdge;

        Frost(Stage st) {
            radius = st.fx(28);
            hair = st.fx(1);
            rim = stroke(0, hair);
        }

        void setBackdrop(Bitmap b) {
            image = b;
            tint.setColor(b != null ? Color.argb(158, 16, 16, 22) : Color.argb(240, 24, 24, 30));
            if (b != null && !box.isEmpty()) shade();
            invalidateSelf();
        }

        private void shade() {
            BitmapShader s = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(box.width() / image.getWidth(), box.height() / image.getHeight());
            m.postTranslate(box.left, box.top);
            s.setLocalMatrix(m);
            backdrop.setShader(s);
        }

        @Override
        void build(RectF r) {
            float w = r.width(), h = r.height();
            wash.setShader(linear(165, r, new int[] {white(.10f), white(.02f), white(.05f)}, new float[] {0, .45f, 1}));
            sheen.setShader(radial(r.left + .14f * w, r.top, .70f * w, .46f * h,
                    new int[] {white(.16f), white(0)}, new float[] {0, .72f}));
            rim.setShader(linear(140, r, new int[] {white(.6f), white(.12f), white(.04f), white(.26f)},
                    new float[] {0, .3f, .62f, 1}));
            rimBox = inset(r, hair / 2);
            litEdge = crescent(r, radius, hair);
            if (image != null) shade();
        }

        @Override
        public void draw(Canvas c) {
            if (image != null) c.drawRoundRect(box, radius, radius, backdrop);
            c.drawRoundRect(box, radius, radius, tint);
            c.drawRoundRect(box, radius, radius, wash);
            c.drawRoundRect(box, radius, radius, sheen);
            c.drawPath(litEdge, lit);
            c.drawRoundRect(rimBox, radius - hair / 2, radius - hair / 2, rim);
        }
    }

    /**
     * The plate's lower edge as an alpha mask, for MaskView to colour with the app: a 2px rim at .55
     * (inset 0 -2px 0) over a glow rising from the bottom at .10 (inset 0 -28px 56px). Only the bottom
     * {@code stripH} of the plate is drawn; nothing reaches higher.
     */
    static Bitmap rimMask(Stage st, int w, int plateH, int stripH) {
        float border = st.fx(1.5f), radius = st.fx(50) - border, shift = plateH - stripH;
        RectF inner = new RectF(border, border - shift, w - border, plateH - border - shift);
        Bitmap b = Bitmap.createBitmap(w, stripH, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        c.clipPath(rrect(inner, radius));

        RectF hole = new RectF(inner);
        hole.offset(0, -st.fx(28));
        Path shadow = new Path();
        shadow.addRect(-w, -plateH, 2 * w, 2 * plateH, Path.Direction.CW);
        shadow.op(rrect(hole, radius), Path.Op.DIFFERENCE);
        Paint glow = paint(white(.10f));
        glow.setMaskFilter(new BlurMaskFilter(blurRadius(st.fx(28)), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(shadow, glow);

        c.drawPath(crescent(inner, radius, -st.fx(2)), paint(white(.55f)));
        return b;
    }

    /**
     * A drop shadow under a w x h rounded rect, as an alpha mask drawn at 1/{@code scale} size: it is all
     * blur, so the view stretching it back up loses nothing. {@code pad} is the room left around the rect.
     */
    static Bitmap shadowMask(float w, float h, float radius, float sigma, float pad, int scale) {
        int bw = (int) Math.ceil((w + 2 * pad) / scale), bh = (int) Math.ceil((h + 2 * pad) / scale);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Paint p = paint(Color.BLACK);
        p.setMaskFilter(new BlurMaskFilter(blurRadius(sigma / scale), BlurMaskFilter.Blur.NORMAL));
        RectF r = new RectF(pad / scale, pad / scale, (pad + w) / scale, (pad + h) / scale);
        new Canvas(b).drawRoundRect(r, radius / scale, radius / scale, p);
        return b;
    }
}
