package com.aditya.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Line icons drawn from geometry on a 24-unit grid, in the spirit of SF Symbols.
 * Paths are built once per instance; draw() only scales the canvas.
 */
final class Icon extends Drawable {

    enum Kind {
        WIFI, WIFI_OFF, ETHERNET, GEAR, INPUT, STAR, STAR_OFF, MOVE, HIDE, INFO, HOME, OPEN, MOON, EYE, ANTENNA,
        PHOTO, SUN, CLOUD, PARTLY, PARTLY_NIGHT, RAIN, SNOW, STORM, FOG, CHECK
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path strokePath = new Path();
    private final Path fillPath = new Path();
    private int level = 3;

    Icon(Kind kind, int color) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.8f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setColor(color);
        build(kind);
    }

    /** Wi-Fi signal level 0..3 (unlit arcs are drawn dimmed). */
    void setSignal(int level) {
        this.level = level;
        invalidateSelf();
    }

    void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidateSelf();
    }

    private Kind kind;

    private void build(Kind k) {
        kind = k;
        Path s = strokePath, f = fillPath;
        switch (k) {
            case WIFI:
            case WIFI_OFF:
                f.addCircle(12, 18.5f, 1.6f, Path.Direction.CW);
                if (k == Kind.WIFI_OFF) {
                    s.moveTo(4, 4);
                    s.lineTo(20, 20);
                }
                break;
            case ETHERNET:
                s.addRoundRect(new RectF(5, 9, 19, 19), 2, 2, Path.Direction.CW);
                s.moveTo(9, 9); s.lineTo(9, 5); s.lineTo(15, 5); s.lineTo(15, 9);
                s.moveTo(9, 15); s.lineTo(9, 19);
                s.moveTo(12, 15); s.lineTo(12, 19);
                s.moveTo(15, 15); s.lineTo(15, 19);
                break;
            case GEAR: {
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cx = 12 + (float) Math.cos(a) * 8.2f, cy = 12 + (float) Math.sin(a) * 8.2f;
                    f.addCircle(cx, cy, 2.1f, Path.Direction.CW);
                }
                f.addCircle(12, 12, 7.2f, Path.Direction.CW);
                Path hole = new Path();
                hole.addCircle(12, 12, 2.9f, Path.Direction.CW);
                f.op(hole, Path.Op.DIFFERENCE);
                break;
            }
            case INPUT:
                s.addRoundRect(new RectF(3, 5, 21, 17), 2.5f, 2.5f, Path.Direction.CW);
                s.moveTo(8, 20.5f); s.lineTo(16, 20.5f);
                s.moveTo(8, 11); s.lineTo(15, 11);
                s.moveTo(12.5f, 8.5f); s.lineTo(15, 11); s.lineTo(12.5f, 13.5f);
                break;
            case STAR:
            case STAR_OFF: {
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + Math.PI / 5 * i;
                    float r = i % 2 == 0 ? 9.5f : 4.2f;
                    float x = 12 + (float) Math.cos(a) * r, y = 12.8f + (float) Math.sin(a) * r;
                    if (i == 0) s.moveTo(x, y); else s.lineTo(x, y);
                }
                s.close();
                if (k == Kind.STAR) f.addPath(s);
                else { s.moveTo(4, 4); s.lineTo(20, 20); }
                break;
            }
            case MOVE:
                s.moveTo(12, 3); s.lineTo(12, 21); s.moveTo(3, 12); s.lineTo(21, 12);
                s.moveTo(9, 6); s.lineTo(12, 3); s.lineTo(15, 6);
                s.moveTo(9, 18); s.lineTo(12, 21); s.lineTo(15, 18);
                s.moveTo(6, 9); s.lineTo(3, 12); s.lineTo(6, 15);
                s.moveTo(18, 9); s.lineTo(21, 12); s.lineTo(18, 15);
                break;
            case HIDE:
            case EYE:
                s.moveTo(2.5f, 12); s.quadTo(12, 2.5f, 21.5f, 12); s.quadTo(12, 21.5f, 2.5f, 12);
                s.addCircle(12, 12, 3, Path.Direction.CW);
                if (k == Kind.HIDE) { s.moveTo(4, 4); s.lineTo(20, 20); }
                break;
            case INFO:
                s.addCircle(12, 12, 9, Path.Direction.CW);
                s.moveTo(12, 11); s.lineTo(12, 16.5f);
                f.addCircle(12, 7.8f, 1.2f, Path.Direction.CW);
                break;
            case HOME:
                s.moveTo(3.5f, 11); s.lineTo(12, 4); s.lineTo(20.5f, 11);
                s.moveTo(6, 9.5f); s.lineTo(6, 20); s.lineTo(18, 20); s.lineTo(18, 9.5f);
                s.moveTo(10, 20); s.lineTo(10, 14.5f); s.lineTo(14, 14.5f); s.lineTo(14, 20);
                break;
            case OPEN:
                f.moveTo(8, 5); f.lineTo(19, 12); f.lineTo(8, 19); f.close();
                break;
            case MOON: {
                f.addCircle(12, 12, 8.5f, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(16.5f, 8.5f, 7f, Path.Direction.CW);
                f.op(bite, Path.Op.DIFFERENCE);
                break;
            }
            case CHECK:
                s.moveTo(4.5f, 12.5f); s.lineTo(9.5f, 17.5f); s.lineTo(19.5f, 6.5f);
                break;
            case PHOTO:
                s.addRoundRect(new RectF(3, 5, 21, 19), 2.5f, 2.5f, Path.Direction.CW);
                s.moveTo(3.5f, 17); s.lineTo(9, 11.5f); s.lineTo(13, 15.5f); s.lineTo(15.5f, 13); s.lineTo(20.5f, 18);
                f.addCircle(15.5f, 9, 1.6f, Path.Direction.CW);
                break;
            case SUN:
                sun(s, f, 12, 12, 4.2f, 8.5f);
                break;
            case CLOUD:
                cloud(s, 0, 2);
                break;
            case PARTLY:
                sun(s, f, 8.5f, 8.5f, 3, 6);
                cloud(s, 2, 4);
                break;
            case PARTLY_NIGHT: {
                Path moon = new Path();
                moon.addCircle(8.5f, 8.5f, 4.5f, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(11, 6.5f, 3.8f, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                f.addPath(moon);
                cloud(s, 2, 4);
                break;
            }
            case RAIN:
                cloud(s, 0, -1);
                for (int i = 0; i < 3; i++) { s.moveTo(8 + i * 4, 17); s.lineTo(7 + i * 4, 20.5f); }
                break;
            case SNOW:
                cloud(s, 0, -1);
                for (int i = 0; i < 3; i++) f.addCircle(8 + i * 4, 18.8f, 1.1f, Path.Direction.CW);
                break;
            case STORM:
                cloud(s, 0, -1);
                f.moveTo(12.5f, 14.5f); f.lineTo(9.5f, 19); f.lineTo(12, 19); f.lineTo(10.5f, 22.5f);
                f.lineTo(15, 17.5f); f.lineTo(12.5f, 17.5f); f.lineTo(14, 14.5f); f.close();
                break;
            case FOG:
                cloud(s, 0, -2);
                s.moveTo(5, 17); s.lineTo(19, 17);
                s.moveTo(7, 20.5f); s.lineTo(17, 20.5f);
                break;
            case ANTENNA:
                s.moveTo(12, 10); s.lineTo(12, 21);
                s.moveTo(8, 21); s.lineTo(16, 21);
                s.moveTo(8.5f, 6.5f); s.quadTo(12, 3, 15.5f, 6.5f);
                s.moveTo(6, 4); s.quadTo(12, -1.5f, 18, 4);
                f.addCircle(12, 9, 1.6f, Path.Direction.CW);
                break;
        }
    }

    private static void sun(Path s, Path f, float cx, float cy, float r, float rays) {
        f.addCircle(cx, cy, r, Path.Direction.CW);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            s.moveTo(cx + c * (r + 1.8f), cy + sn * (r + 1.8f));
            s.lineTo(cx + c * rays, cy + sn * rays);
        }
    }

    /** Cloud outline on the 24 grid, shifted by (dx, dy). */
    private static void cloud(Path s, float dx, float dy) {
        s.moveTo(7 + dx, 17 + dy);
        s.cubicTo(3.8f + dx, 17 + dy, 3 + dx, 12.5f + dy, 6.4f + dx, 11.7f + dy);
        s.cubicTo(7 + dx, 7.4f + dy, 13.2f + dx, 6.4f + dy, 14.8f + dx, 10.3f + dy);
        s.cubicTo(18.6f + dx, 9.6f + dy, 21 + dx, 13 + dy, 19.6f + dx, 15.4f + dy);
        s.cubicTo(19 + dx, 16.5f + dy, 18 + dx, 17 + dy, 17 + dx, 17 + dy);
        s.close();
    }

    private final RectF arc = new RectF();

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        int save = c.save();
        c.translate(b.left, b.top);
        float scale = Math.min(b.width(), b.height()) / 24f;
        c.scale(scale, scale);
        if (kind == Kind.WIFI || kind == Kind.WIFI_OFF) {
            int full = stroke.getAlpha();
            for (int i = 1; i <= 3; i++) {
                float r = 4.5f * i;
                arc.set(12 - r, 18.5f - r, 12 + r, 18.5f + r);
                stroke.setAlpha(kind == Kind.WIFI && i <= level ? full : full / 3);
                c.drawArc(arc, 225, 90, false, stroke);
            }
            stroke.setAlpha(full);
        }
        c.drawPath(fillPath, fill);
        c.drawPath(strokePath, stroke);
        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        stroke.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
