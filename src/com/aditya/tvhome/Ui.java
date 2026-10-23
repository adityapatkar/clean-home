package com.aditya.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/** Design tokens shared by every screen. Sizes are in dp/sp for a 960x540dp TV canvas. */
final class Ui {

    // Palette: near-black base, Apple-style label hierarchy.
    static final int BG = 0xFF06070A;
    static final int LABEL = 0xFFF5F5F7;
    static final int LABEL_2 = 0x99EBEBF5;
    static final int LABEL_3 = 0x4DEBEBF5;
    static final int ON_FOCUS = 0xFF111114;
    static final int FOCUS_FILL = 0xF2FFFFFF;
    static final int ACCENT = 0xFF64D2FF;
    static final int WARN = 0xFFFF9F0A;

    // Layout: 48dp side margins keep content inside the TV title-safe area.
    static final int MARGIN = 48;
    static final int RADIUS_TILE = 14;
    static final int RADIUS_PANEL = 28;

    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // Motion: short and decelerating, so focus never feels like it lags the remote.
    static final int FOCUS_MS = 160;
    static final int SHEET_MS = 240;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * Moves a finished, never-again-modified bitmap to GPU-only memory. A normal
     * bitmap lives twice - in app memory and again as a GPU texture once drawn; a
     * HARDWARE bitmap exists only on the GPU. Must be the last step: hardware
     * bitmaps can't be read or drawn in software.
     */
    static android.graphics.Bitmap toHardware(android.graphics.Bitmap b) {
        if (b == null || b.getConfig() == android.graphics.Bitmap.Config.HARDWARE) return b;
        android.graphics.Bitmap hw = b.copy(android.graphics.Bitmap.Config.HARDWARE, false);
        if (hw == null) return b;
        b.recycle();
        return hw;
    }

    static android.graphics.drawable.Drawable toHardware(android.content.res.Resources r,
                                                         android.graphics.drawable.Drawable d) {
        if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return d;
        android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        // Only our own tile-sized copies (RGB_565); never recycle platform-cached bitmaps.
        if (b == null || b.getConfig() != android.graphics.Bitmap.Config.RGB_565) return d;
        return new android.graphics.drawable.BitmapDrawable(r, toHardware(b));
    }

    /** Ambient colour for a banner, pulled toward a saturated mid-tone so the backdrop glows. */
    static int vivid(int argb) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(argb, hsv);
        if (hsv[1] < 0.12f) {
            // Greyscale banners (white/black logos) get a neutral cool glow instead of mud.
            return 0xFF3A4A6B;
        }
        hsv[1] = Math.min(1f, hsv[1] * 1.15f + 0.1f);
        hsv[2] = Math.max(0.55f, Math.min(0.85f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }
}
