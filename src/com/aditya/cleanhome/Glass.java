package com.aditya.cleanhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * "Liquid glass" material: an optional frosted backdrop, a translucent body that is
 * brighter at the top, and a specular rim light that fades toward the bottom edge.
 * Shaders are rebuilt only when bounds change, never per frame.
 */
final class Glass extends Drawable {

    private final float radius;
    private final float rim;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    // The frosted backdrop is a bitmap shader drawn as a round rect: clipPath would
    // force software rendering on MediaTek GPUs.
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix frostMatrix = new android.graphics.Matrix();
    private final RectF rect = new RectF();
    private final RectF outer = new RectF();
    private int topTint, bottomTint;

    private Bitmap backdrop;
    private int offsetX, offsetY, screenW, screenH;

    /** @param strength 0..1; higher is a more opaque, brighter pane. */
    Glass(float radiusPx, float rimPx, float strength) {
        radius = radiusPx;
        rim = rimPx;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(rimPx);
        setStrength(strength);
    }

    void setStrength(float strength) {
        topTint = argb(0.10f + 0.10f * strength, 255);
        bottomTint = argb(0.04f + 0.06f * strength, 255);
        rebuild(getBounds());
        invalidateSelf();
    }

    /** Dark smoked glass for menus that sit over busy content. */
    void setSmoked(boolean smoked) {
        if (smoked) {
            topTint = 0xB8262830;
            bottomTint = 0xC8141519;
        }
        rebuild(getBounds());
        invalidateSelf();
    }

    /**
     * Blurred image of what is behind this pane. {@code screenW}/{@code screenH} are the
     * full-screen size the bitmap represents; {@code x}/{@code y} the pane's screen offset.
     */
    void setBackdrop(Bitmap blurred, int x, int y, int screenW, int screenH) {
        backdrop = blurred;
        offsetX = x;
        offsetY = y;
        this.screenW = screenW;
        this.screenH = screenH;
        frost.setShader(blurred == null ? null
                : new android.graphics.BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        updateFrostMatrix();
        invalidateSelf();
    }

    private void updateFrostMatrix() {
        if (backdrop == null || frost.getShader() == null) return;
        Rect b = getBounds();
        frostMatrix.setScale(screenW / (float) backdrop.getWidth(), screenH / (float) backdrop.getHeight());
        frostMatrix.postTranslate(b.left - offsetX, b.top - offsetY);
        frost.getShader().setLocalMatrix(frostMatrix);
    }

    private static int argb(float a, int v) {
        return (Math.round(a * 255) << 24) | (v << 16) | (v << 8) | v;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rebuild(b);
    }

    private void rebuild(Rect b) {
        if (b.isEmpty()) return;
        float h = rim / 2;
        rect.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        outer.set(b);
        updateFrostMatrix();
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom, topTint, bottomTint, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x73FFFFFF, 0x1AFFFFFF, 0x0DFFFFFF, 0x26FFFFFF},
                new float[]{0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        if (backdrop != null) c.drawRoundRect(outer, radius, radius, frost);
        c.drawRoundRect(rect, radius, radius, body);
        c.drawRoundRect(rect, radius, radius, edge);
    }

    @Override
    public void setAlpha(int alpha) {
        body.setAlpha(alpha);
        edge.setAlpha(alpha);
        frost.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean getPadding(Rect padding) {
        return false;
    }

    // ---------------------------------------------------------------- blur

    interface Blurred {
        void onBlurred(Bitmap b);
    }

    /**
     * Frosted copy of what's on screen. PixelCopy reads the already-composited window
     * straight from the GPU, scaled into a 1/8-size bitmap, so nothing is re-drawn on
     * the CPU; the box blur on ~32k pixels then takes a few ms. Falls back to a
     * software snapshot if PixelCopy is unavailable.
     */
    static void captureBlur(android.app.Activity a, android.view.View v, Blurred done) {
        int w = Math.max(1, v.getWidth() / 8), h = Math.max(1, v.getHeight() / 8);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        // Exactly one of: PixelCopy result, or the timeout fallback. PixelCopy can stay
        // silent (e.g. while the window surface is being replaced), and a menu that
        // never opens is far worse than one frame of software rendering.
        boolean[] delivered = {false};
        Runnable fallback = () -> {
            if (delivered[0]) return;
            delivered[0] = true;
            done.onBlurred(snapshotBlur(v));
        };
        try {
            android.view.PixelCopy.request(a.getWindow(), bmp, result -> {
                if (delivered[0]) {
                    bmp.recycle();
                    return;
                }
                main.removeCallbacks(fallback);
                delivered[0] = true;
                if (result == android.view.PixelCopy.SUCCESS) {
                    blurInPlace(bmp);
                    done.onBlurred(bmp);
                } else {
                    bmp.recycle();
                    done.onBlurred(snapshotBlur(v));
                }
            }, main);
            main.postDelayed(fallback, 150);
        } catch (RuntimeException e) {
            bmp.recycle();
            fallback.run();
        }
    }

    private static void blurInPlace(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 3, true);
            boxBlur(tmp, px, w, h, 3, false);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Software fallback: 1/8-scale snapshot of a view tree + the same blur. */
    static Bitmap snapshotBlur(android.view.View v) {
        int w = Math.max(1, v.getWidth() / 8), h = Math.max(1, v.getHeight() / 8);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(w / (float) v.getWidth(), h / (float) v.getHeight());
        try {
            v.draw(c);
        } catch (RuntimeException e) {
            // Hardware bitmaps (banners, photo) can't be drawn in software; a plain
            // dark frost is an acceptable fallback for this rare path.
            bmp.eraseColor(0xFF15161A);
            return bmp;
        }
        blurInPlace(bmp);
        return bmp;
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int a = 0, rr = 0, g = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[index(line, clamp(i, len), w, horizontal)];
                a += p >>> 24; rr += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; bb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (bb / div);
                int pIn = in[index(line, clamp(i + r + 1, len), w, horizontal)];
                int pOut = in[index(line, clamp(i - r, len), w, horizontal)];
                a += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                g += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
