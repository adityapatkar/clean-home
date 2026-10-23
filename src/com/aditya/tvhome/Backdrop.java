package com.aditya.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Soft ambient light behind the home screen, tinted by the focused app's colour.
 *
 * The glow is rendered in software into a tiny 160x90 bitmap and stretched by the
 * GPU: a blurry gradient loses nothing at that size, and it means a colour change
 * costs one ~0.2 ms software draw plus a crossfade of two small textures, instead
 * of re-rasterising full-screen radial gradients for every animation frame.
 * With {@code drift} on (screensaver) the glows wander slowly, redrawn at 20 fps.
 */
final class Backdrop extends FrameLayout {

    private static final int W = 160, H = 90;

    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final Canvas canvas = new Canvas();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint();
    private final float[] hsv = new float[3];
    private int front;
    private int color = 0xFF3A4A6B;
    private boolean drift;
    private long driftStart;

    private final Runnable driftFrame = new Runnable() {
        @Override
        public void run() {
            if (!drift) return;
            render(front, color);
            layers[front].invalidate();
            postDelayed(this, 50);
        }
    };

    Backdrop(Context c) {
        super(c);
        base.setShader(new android.graphics.LinearGradient(0, 0, 0, H, 0xFF0B0D12, Ui.BG, Shader.TileMode.CLAMP));
        for (int i = 0; i < 2; i++) {
            bitmaps[i] = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            v.setImageBitmap(bitmaps[i]);
            v.setAlpha(i == 0 ? 1f : 0f);
            layers[i] = v;
            addView(v, new LayoutParams(-1, -1));
        }
        render(0, color);
    }

    void setDrift(boolean on) {
        drift = on;
        driftStart = System.currentTimeMillis();
        removeCallbacks(driftFrame);
        if (on) post(driftFrame);
    }

    void setTint(int c) {
        if (c == color) return;
        color = c;
        if (drift) {
            // Screensaver: the drift loop redraws continuously; just change colour.
            return;
        }
        int back = 1 - front;
        render(back, c);
        layers[back].invalidate();
        layers[back].animate().cancel();
        layers[front].animate().cancel();
        layers[back].bringToFront();
        layers[back].setAlpha(0f);
        layers[back].animate().alpha(1f).setDuration(350).start();
        front = back;
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private void render(int index, int c) {
        Bitmap b = bitmaps[index];
        canvas.setBitmap(b);
        // The base gradient is baked in, so the whole backdrop is one opaque layer:
        // the screen gets filled once per frame instead of three times.
        canvas.drawRect(0, 0, W, H, base);
        float ax = W * 0.18f, ay = H * 0.05f, bx = W * 0.92f, by = H * 0.95f;
        if (drift) {
            double t = (System.currentTimeMillis() - driftStart) / 1000.0;
            ax = (float) (W * (0.30 + 0.25 * Math.sin(t / 23.0)));
            ay = (float) (H * (0.30 + 0.25 * Math.cos(t / 31.0)));
            bx = (float) (W * (0.70 + 0.22 * Math.cos(t / 27.0)));
            by = (float) (H * (0.70 + 0.22 * Math.sin(t / 19.0)));
        }
        Color.colorToHSV(c, hsv);
        hsv[0] = (hsv[0] + 40) % 360;
        int second = Color.HSVToColor(hsv);
        paint.setShader(new RadialGradient(ax, ay, W * 0.55f,
                withAlpha(c, 0x70), withAlpha(c, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(new RadialGradient(bx, by, W * 0.45f,
                withAlpha(second, 0x48), withAlpha(second, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        canvas.setBitmap(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(driftFrame);
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE) removeCallbacks(driftFrame);
    }
}
