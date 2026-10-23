package com.aditya.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Landscape screensaver: a new photo every minute with a slow Ken Burns drift and a
 * long crossfade, plus time, date and weather. The info block and the photo credit
 * hop to a different corner with each photo, so nothing stays lit in one place.
 * If no photo can be loaded (offline, first run) the drifting colour glow shows.
 *
 * Memory: at most two RGB_565 screen-size photos (2 x 4 MB) exist at once; the one
 * that fades out is recycled.
 */
final class Screensaver extends FrameLayout implements Weather.Listener {

    private static final long SLIDE_MS = 60_000;
    private static final long FADE_MS = 2_000;
    private static final int[] HUES = {0xFF2E5BFF, 0xFF7A3CFF, 0xFFFF3C8E, 0xFFFF8A3C, 0xFF19C3A6};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Backdrop glow;
    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] shown = new Bitmap[2];
    private int front = -1;
    private final LinearLayout info;
    private final TextView time, date, temp, condition, place, credit;
    private final ImageView weatherIcon;
    private final LinearLayout weatherRow, creditBlock;
    private final List<Landscapes.Photo> order = new ArrayList<>();
    private int next, corner;
    private final int w, h;

    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            String t = DateFormat.getTimeFormat(getContext()).format(now);
            time.setText(t.replace(" AM", "").replace(" PM", ""));
            date.setText(DateFormat.format("EEEE, MMMM d", now));
            handler.postDelayed(this, 60_000 - now.getTime() % 60_000);
        }
    };

    private final Runnable slide = new Runnable() {
        @Override
        public void run() {
            showNext();
            // If the photo can't be fetched (offline), try another one soon.
            handler.postDelayed(this, front < 0 ? 8_000 : SLIDE_MS);
        }
    };

    Screensaver(Context c) {
        super(c);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        w = dm.widthPixels;
        h = dm.heightPixels;

        glow = new Backdrop(c);
        addView(glow, new LayoutParams(-1, -1));
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            v.setAlpha(0f);
            layers[i] = v;
            addView(v, new LayoutParams(-1, -1));
        }

        int shadow = 0x99000000;
        float sr = Ui.dp(c, 14);
        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        time = Ui.text(c, 112, Ui.LABEL, Ui.THIN);
        time.setShadowLayer(sr, 0, Ui.dp(c, 1), shadow);
        date = Ui.text(c, 24, Ui.LABEL, Ui.LIGHT);
        date.setShadowLayer(sr, 0, Ui.dp(c, 1), shadow);
        date.setPadding(Ui.dp(c, 4), Ui.dp(c, 2), 0, 0);
        info.addView(time);
        info.addView(date);

        weatherRow = new LinearLayout(c);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setPadding(Ui.dp(c, 4), Ui.dp(c, 14), 0, 0);
        weatherIcon = new ImageView(c);
        int is = Ui.dp(c, 30);
        weatherRow.addView(weatherIcon, new LinearLayout.LayoutParams(is, is));
        temp = Ui.text(c, 30, Ui.LABEL, Ui.REGULAR);
        temp.setShadowLayer(sr, 0, Ui.dp(c, 1), shadow);
        temp.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 12), 0);
        weatherRow.addView(temp);
        condition = Ui.text(c, 20, Ui.LABEL, Ui.REGULAR);
        condition.setShadowLayer(sr, 0, Ui.dp(c, 1), shadow);
        weatherRow.addView(condition);
        weatherRow.setVisibility(GONE);
        info.addView(weatherRow);
        info.setBackground(halo(c));
        info.setPadding(HALO_PAD, HALO_PAD, HALO_PAD, HALO_PAD);
        addView(info, new LayoutParams(-2, -2));

        creditBlock = new LinearLayout(c);
        creditBlock.setOrientation(LinearLayout.VERTICAL);
        place = Ui.text(c, 16, Ui.LABEL, Ui.MEDIUM);
        place.setShadowLayer(sr, 0, Ui.dp(c, 1), shadow);
        credit = Ui.text(c, 12, Ui.LABEL_2, Ui.REGULAR);
        credit.setShadowLayer(sr, 0, Ui.dp(c, 1), shadow);
        credit.setPadding(0, Ui.dp(c, 4), 0, 0);
        creditBlock.addView(place);
        creditBlock.addView(credit);
        creditBlock.setAlpha(0f);
        creditBlock.setBackground(halo(c));
        creditBlock.setPadding(HALO_PAD, HALO_PAD, HALO_PAD, HALO_PAD);
        addView(creditBlock, new LayoutParams(-2, -2));
    }

    private final int HALO_PAD = Ui.dp(getContext(), 88);

    /**
     * A soft dark pool behind text, the way tvOS keeps overlay text readable on any
     * photo without a visible box: an elliptical gradient that reaches zero exactly
     * at the block's edges, so there is never a seam.
     */
    private static android.graphics.drawable.Drawable halo(Context c) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Matrix m = new android.graphics.Matrix();

            {
                p.setShader(new android.graphics.RadialGradient(0, 0, 1,
                        new int[]{0x73000000, 0x4D000000, 0x1F000000, 0x08000000, 0x00000000},
                        new float[]{0f, 0.38f, 0.66f, 0.86f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            }

            @Override
            protected void onBoundsChange(android.graphics.Rect b) {
                m.setScale(b.width() / 2f, b.height() / 2f);
                m.postTranslate(b.exactCenterX(), b.exactCenterY());
                p.getShader().setLocalMatrix(m);
            }

            @Override
            public void draw(android.graphics.Canvas canvas) {
                canvas.drawRect(getBounds(), p);
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
        };
    }

    // ------------------------------------------------------------ slides

    private void showNext() {
        if (order.isEmpty()) return;
        Landscapes.Photo p = order.get(next % order.size());
        next++;
        Landscapes.prefetch(getContext(), order.get(next % order.size()));
        Landscapes.load(getContext(), p, w, h, Landscapes.Look.SCREENSAVER, (photo, bmp) -> {
            if (!isAttachedToWindow()) {
                bmp.recycle();
                return;
            }
            crossfadeTo(photo, bmp);
        });
    }

    private void crossfadeTo(Landscapes.Photo photo, Bitmap bmp) {
        int back = front < 0 ? 0 : 1 - front;
        ImageView in = layers[back];
        in.animate().cancel();
        in.setImageBitmap(bmp);
        shown[back] = bmp;
        in.bringToFront();
        info.bringToFront();
        creditBlock.bringToFront();

        // Ken Burns: a slow push-in from a random corner, spanning the whole slide.
        in.setPivotX(random.nextBoolean() ? 0 : w);
        in.setPivotY(random.nextBoolean() ? 0 : h);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator()).start();
        in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(SLIDE_MS + FADE_MS)
                .setInterpolator(new LinearInterpolator()).start();

        int old = front;
        front = back;
        handler.postDelayed(() -> {
            if (old >= 0 && shown[old] != null && old != front) {
                layers[old].setImageDrawable(null);
                layers[old].setAlpha(0f);
                shown[old].recycle();
                shown[old] = null;
            }
            glow.setVisibility(GONE);
        }, FADE_MS + 100);

        place.setText(photo.place);
        credit.setText(photo.credit());
        moveCorners();
    }

    /** Info corners: 0 bottom-left, 1 top-left, 2 top-right, 3 bottom-right. */
    private void moveCorners() {
        corner = (corner + 1) % 4;
        info.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            layoutCorners();
            info.animate().alpha(1f).setDuration(900).start();
            creditBlock.animate().alpha(1f).setDuration(900).start();
        }).start();
        creditBlock.animate().alpha(0f).setDuration(600).start();
    }

    private void layoutCorners() {
        boolean left = corner <= 1;
        place(info, left, corner == 1 || corner == 2);
        // The credit always sits on the bottom edge, opposite the info block.
        place(creditBlock, !left, false);
        weatherRow.setGravity((left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
    }

    private void place(LinearLayout v, boolean left, boolean top) {
        int m = Ui.dp(getContext(), 56) - HALO_PAD;
        LayoutParams lp = new LayoutParams(-2, -2,
                (left ? Gravity.START : Gravity.END) | (top ? Gravity.TOP : Gravity.BOTTOM));
        lp.setMargins(m, m, m, m);
        v.setLayoutParams(lp);
        v.setGravity(left ? Gravity.START : Gravity.END);
    }

    // ------------------------------------------------------------ weather

    @Override
    public void onWeather(Weather.Now n) {
        weatherIcon.setImageDrawable(new Icon(n.icon(), Ui.LABEL));
        temp.setText(n.temp + "°");
        condition.setText(n.condition() + "  ·  H " + n.high + "°  L " + n.low + "°");
        weatherRow.setVisibility(VISIBLE);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        glow.setTint(HUES[random.nextInt(HUES.length)]);
        glow.setDrift(true);
        corner = random.nextInt(4);
        layoutCorners();

        order.clear();
        order.addAll(Landscapes.all(getContext()));
        Collections.shuffle(order, random);
        // Start on a photo that is already on disk so the first frame isn't empty.
        for (int i = 0; i < order.size(); i++) {
            if (Landscapes.isCached(getContext(), order.get(i))) {
                Collections.swap(order, 0, i);
                break;
            }
        }
        next = 0;
        handler.post(minute);
        handler.post(slide);
        Weather.observe(getContext(), this);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        Weather.stop(this);
        glow.setDrift(false);
        for (int i = 0; i < 2; i++) {
            layers[i].animate().cancel();
            layers[i].setImageDrawable(null);
            if (shown[i] != null) {
                shown[i].recycle();
                shown[i] = null;
            }
        }
        super.onDetachedFromWindow();
    }
}
