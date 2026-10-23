package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * If AGSL fails (it shouldn't on Android 13+), it falls back to a gradient and plain translucent glass: the home screen
 * is never left blank.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, glass, liquid;
    private final Frost frost = new Frost();
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    private final Recording auroraNode = Recording.create("aurora");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private boolean auroraDirty = true, imageFrost, imageGlass;
    private Bitmap frostImage, glassA, glassB;
    private BitmapShader glassShaderA;
    private final boolean withGlass;

    // Glass without AGSL (Android 8 to 12): the blurred photo clipped to the shape, a tint, a soft sheen from the top
    // and a light rim brighter at the top left, like light catching the edge. No allocations while drawing: the
    // gradients are unit-sized and placed with a matrix.
    private Bitmap flatGlassA, flatGlassB;
    private BitmapShader flatFrostShader, flatShaderA, flatShaderB;
    private float flatMix, offX, offY;
    private final Paint flatImage = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint flatSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix flatMatrix = new Matrix();
    private final RectF flatRect = new RectF();
    private final LinearGradient sheenUnit = new LinearGradient(0, 0, 0, 1,
            new int[] {0x30FFFFFF, 0x0CFFFFFF, 0x00FFFFFF, 0x0A000000}, new float[] {0, 0.42f, 0.7f, 1}, Shader.TileMode.CLAMP);
    private final LinearGradient rimUnit = new LinearGradient(0, 0, 1, 1,
            new int[] {0xB3FFFFFF, 0x33FFFFFF, 0x0FFFFFFF, 0x4DFFFFFF}, new float[] {0, 0.28f, 0.62f, 1}, Shader.TileMode.CLAMP);

    Scene() {
        this(true);
    }

    Scene(boolean withGlass) {
        this.withGlass = withGlass;
        try {
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL, flat look instead
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
        flatSheen.setShader(sheenUnit);
        flatEdge.setStyle(Paint.Style.STROKE);
        flatEdge.setShader(rimUnit);
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        auroraNode.setPosition(0, 0, lw, lh);
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
        if (aurora == null) fallback.setShader(Ui.gradient(h, palette.color(1), palette.color(0)));
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        if (aurora == null) return;
        aurora.setFloatUniform("speed", speed);
        aurora.setFloatUniform("dim", dim);
        palette.apply(aurora);
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        time = seconds;
        if (aurora == null) return;
        aurora.setFloatUniform("t", seconds);
        if (withGlass && (!imageFrost || !imageGlass)) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) glass.setInputShader("frost", s);
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        if (liquid == null) {           // no AGSL (Android 8 to 12): the flat glass draws the photo itself
            flatGlassA = fill(flatGlassA, from);
            if (flatShaderA == null) flatShaderA = new BitmapShader(flatGlassA, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (to != null) {
                flatGlassB = fill(flatGlassB, to);
                if (flatShaderB == null) flatShaderB = new BitmapShader(flatGlassB, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            flatMix = 0;
            return;
        }
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        glassShaderA = linear(glassA);
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", to != null ? linear(glassB) : glassShaderA);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        flatMix = Math.max(0, Math.min(1, t));
        if (liquid != null) liquid.setFloatUniform("mixB", flatMix);
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquid == null) {
            flatGlass(c, r, radius, tint, Math.min(0.85f, tintAlpha + 0.06f), 0, 0, lift, 1, alpha, true);
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        imageFrost = true;
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        if (glass == null) {            // as above: the flat glass draws it
            if (flatFrostShader == null) flatFrostShader = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            return;
        }
        BitmapShader s = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        glass.setInputShader("frost", s);
        setFrostScale();
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        offX = x;
        offY = y;
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (aurora == null) {
            if (fallback.getShader() == null) applyPalette();
            c.drawRect(0, 0, w, h, fallback);
            return;
        }
        if (auroraDirty) {
            Canvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        auroraNode.draw(c);
        c.restore();
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null) {
            flatGlass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, alpha, false);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }

    /**
     * Glass without AGSL. `sharp`: the lightly blurred photo (the shelf's Liquid Glass); otherwise the frosted one
     * (cards, buttons, the drawer). Without a photo it's a tinted fill with the same sheen and rim.
     */
    private void flatGlass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha,
                           float lift, float body, float alpha, boolean sharp) {
        float a = Math.min(1, alpha);
        radius = Math.min(radius, Math.min(r.width(), r.height()) / 2);
        if (body > 0) {
            BitmapShader photo = sharp || flatFrostShader == null || !imageFrost ? flatShaderA : flatFrostShader;
            if (!imageGlass && photo == flatShaderA) photo = null;
            if (photo != null) {
                flatImageDraw(c, r, radius, photo, photo == flatFrostShader ? Backdrop.FW : Backdrop.GW,
                        photo == flatFrostShader ? Backdrop.FH : Backdrop.GH, a);
                if (photo == flatShaderA && flatMix > 0.004f && flatShaderB != null)
                    flatImageDraw(c, r, radius, flatShaderB, Backdrop.GW, Backdrop.GH, a * flatMix);
            }
            float fillAlpha = photo == null ? Math.max(0.35f, tintAlpha) : tintAlpha;
            flatFill.setColor(Ui.alpha(tint | 0xFF000000, fillAlpha * a));
            c.drawRoundRect(r, radius, radius, flatFill);
            if (glowAlpha > 0.004f) {
                flatFill.setColor(Ui.alpha(glow | 0xFF000000, glowAlpha * 0.6f * a));
                c.drawRoundRect(r, radius, radius, flatFill);
            }
            flatMatrix.setScale(1, Math.max(1, r.height()));
            flatMatrix.postTranslate(0, r.top);
            sheenUnit.setLocalMatrix(flatMatrix);
            flatSheen.setAlpha(Math.round(255 * a * Math.min(1, 0.7f + lift * 6)));
            c.drawRoundRect(r, radius, radius, flatSheen);
        }
        // The rim: about 1.5 px on a 1080p screen, brightest at the top left.
        float stroke = Math.max(1.2f, h / 720f);
        flatRect.set(r);
        flatRect.inset(stroke / 2, stroke / 2);
        flatMatrix.setScale(Math.max(1, r.width()), Math.max(1, r.height()));
        flatMatrix.postTranslate(r.left, r.top);
        rimUnit.setLocalMatrix(flatMatrix);
        flatEdge.setStrokeWidth(stroke);
        flatEdge.setAlpha(Math.round(255 * a));
        float rr = Math.max(0, radius - stroke / 2);
        c.drawRoundRect(flatRect, rr, rr, flatEdge);
    }

    /** The photo (bw × bh, stretched over the screen) seen through `r`, as if the glass were a window onto it. */
    private void flatImageDraw(Canvas c, RectF r, float radius, BitmapShader s, int bw, int bh, float alpha) {
        flatMatrix.setScale((float) w / bw, (float) h / bh);
        flatMatrix.postTranslate(-offX, -offY);
        s.setLocalMatrix(flatMatrix);
        flatImage.setShader(s);
        flatImage.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(r, radius, radius, flatImage);
        flatImage.setShader(null);
    }
}
