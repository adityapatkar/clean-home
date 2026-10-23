package com.aditya.cleanhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One app: a rounded banner card with the app name revealed beneath it on focus.
 * Focus lifts the card (scale + coloured shadow) and lights a specular sheen.
 */
final class Tile extends LinearLayout {

    static final class App {
        final ComponentName component;
        final String pkg;
        final CharSequence label;
        final Drawable banner;
        final Drawable icon;
        final int color;

        App(ComponentName component, CharSequence label, Drawable banner, Drawable icon, int color) {
            this.component = component;
            this.pkg = component.getPackageName();
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.color = color;
        }
    }

    final App app;
    private final FrameLayout card;
    private final TextView name;
    private final Sheen sheen;
    private final float lift;
    private ObjectAnimator pulse;

    Tile(Context c, App app, int w, int h, float focusScale) {
        super(c);
        this.app = app;
        setOrientation(VERTICAL);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);
        lift = focusScale;

        float radius = Ui.dp(c, Ui.RADIUS_TILE);
        card = new FrameLayout(c);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setBackgroundColor(0xFF1C1D22);
        card.setOutlineSpotShadowColor(app.color);
        card.setOutlineAmbientShadowColor(app.color);

        if (app.banner != null) {
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(app.banner);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // No TV banner: icon over a soft wash of the app's own colour.
            card.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{mix(app.color, 0xFF1C1D22, 0.55f), 0xFF17181C}));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(c);
            img.setImageDrawable(app.icon);
            int s = h * 9 / 20;
            col.addView(img, new LayoutParams(s, s));
            TextView label = Ui.text(c, 13, Ui.LABEL, Ui.MEDIUM);
            label.setText(app.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(c, 10);
            label.setPadding(p, Ui.dp(c, 8), p, 0);
            col.addView(label, new LayoutParams(-1, -2));
            card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
        sheen = new Sheen(radius, Ui.dp(c, 1.5f));
        card.setForeground(sheen);
        addView(card, new LayoutParams(w, h));

        name = Ui.text(c, 14, Ui.LABEL, Ui.MEDIUM);
        name.setText(app.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setAlpha(0f);
        LayoutParams nlp = new LayoutParams(w, -2);
        nlp.topMargin = Ui.dp(c, 12);
        addView(name, nlp);

        setOnFocusChangeListener((v, has) -> animateFocus(has));
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) * (1 - t) + Color.red(b) * t),
                (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private void animateFocus(boolean has) {
        float s = has ? lift : 1f;
        card.animate().scaleX(s).scaleY(s)
                .translationZ(has ? Ui.dp(getContext(), 12) : 0)
                .setInterpolator(has ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator())
                .setDuration(Ui.FOCUS_MS).withLayer().start();
        // The name sits just under the lifted card.
        float drop = has ? card.getHeight() * (lift - 1f) / 2f : 0f;
        name.animate().alpha(has ? 1f : 0f).translationY(drop)
                .setDuration(Ui.FOCUS_MS).start();
        sheen.setLit(has);
    }

    /** Gentle breathing while the tile is being moved. */
    void setMoving(boolean moving) {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        card.setAlpha(1f);
        if (moving) {
            pulse = ObjectAnimator.ofFloat(card, View.ALPHA, 1f, 0.6f);
            pulse.setDuration(650);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        }
    }

    /** Specular highlight across the top-left of a focused card, plus a hairline rim. */
    private static final class Sheen extends Drawable {
        private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius;
        private int alpha;

        Sheen(float radius, float rimWidth) {
            this.radius = radius;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(rimWidth);
        }

        /** Instant on/off: fading it would re-render the card's layer every frame. */
        void setLit(boolean lit) {
            alpha = lit ? 255 : 0;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect b) {
            r.set(b);
            float h = rim.getStrokeWidth() / 2;
            r.inset(h, h);
            light.setShader(new LinearGradient(b.left, b.top, b.left + b.width() * 0.6f, b.top + b.height(),
                    new int[]{0x40FFFFFF, 0x0FFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.4f, 1f},
                    Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xCCFFFFFF, 0x40FFFFFF,
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            if (alpha == 0) return;
            light.setAlpha(alpha);
            rim.setAlpha(alpha);
            c.drawRect(getBounds(), light);
            c.drawRoundRect(r, radius, radius, rim);
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------ colour

    private static Bitmap sampleBitmap;
    private static Canvas sampleCanvas;

    /** Average colour of a drawable, weighted toward saturated pixels. Uses one shared 12x7 buffer. */
    static int dominantColor(Drawable d) {
        if (d == null) return 0xFF3A4A6B;
        if (sampleBitmap == null) {
            sampleBitmap = Bitmap.createBitmap(12, 7, Bitmap.Config.ARGB_8888);
            sampleCanvas = new Canvas(sampleBitmap);
        }
        sampleBitmap.eraseColor(0);
        Rect old = new Rect(d.getBounds());
        d.setBounds(0, 0, 12, 7);
        d.draw(sampleCanvas);
        d.setBounds(old);
        float r = 0, g = 0, b = 0, wsum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 12; x++) {
                int p = sampleBitmap.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                float w = 0.15f + hsv[1] * hsv[2];
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                b += Color.blue(p) * w;
                wsum += w;
            }
        }
        if (wsum == 0) return 0xFF3A4A6B;
        return Ui.vivid(Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (b / wsum)));
    }
}
