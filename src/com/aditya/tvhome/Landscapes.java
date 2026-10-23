package com.aditya.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Curated landscape photos from Wikimedia Commons Featured Pictures (CC BY / BY-SA /
 * public domain; credits travel with each photo). Each 1920px image (~600 KB) is
 * downloaded once, on first use, into app storage.
 *
 * Decoding is to screen size with the legibility scrim painted into the pixels, so
 * the home screen draws one opaque full-screen texture and nothing on top of it.
 * Full 32-bit colour (16-bit bands visibly in skies), then moved to a GPU-only
 * hardware bitmap so the 8 MB exists once rather than in app memory and the GPU.
 */
final class Landscapes {

    static final String CREDIT = "Photos: Wikimedia Commons Featured Pictures";

    static final class Photo {
        final int index;
        final String url, by, license, place;

        Photo(int index, String url, String by, String license, String place) {
            this.index = index;
            this.url = url;
            this.by = by;
            this.license = license;
            this.place = place;
        }

        String credit() {
            return "Photo: " + by + " · " + license;
        }
    }

    /** How the scrim is baked into a decoded photo. */
    enum Look {
        /** Strong top and bottom shading so the top bar and tiles stay readable. */
        HOME,
        /** Untouched; the photo is the point. */
        SCREENSAVER
    }

    interface Ready {
        void onBitmap(Photo photo, Bitmap bitmap);
    }

    private static List<Photo> photos;

    private Landscapes() {
    }

    static synchronized List<Photo> all(Context c) {
        if (photos != null) return photos;
        List<Photo> l = new ArrayList<>();
        try (InputStream in = c.getResources().openRawResource(R.raw.landscapes)) {
            JSONArray a = new JSONArray(readAll(in));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Photo(i, o.getString("u"), o.getString("by"), o.getString("lic"), o.getString("p")));
            }
        } catch (Exception ignored) {
        }
        photos = l;
        return l;
    }

    /** Photo of the day, shifted by the user's "next photo" presses. */
    static Photo ofTheDay(Context c, int offset) {
        List<Photo> l = all(c);
        if (l.isEmpty()) return null;
        int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        return l.get(Math.floorMod(day + offset, l.size()));
    }

    private static File file(Context c, Photo p) {
        File dir = new File(c.getFilesDir(), "wall");
        dir.mkdirs();
        return new File(dir, p.index + ".jpg");
    }

    static boolean isCached(Context c, Photo p) {
        return file(c, p).length() > 0;
    }

    /** Download (if needed) and decode on the worker thread; deliver on the main thread. */
    static void load(Context c, Photo p, int w, int h, Look look, Ready ready) {
        Context app = c.getApplicationContext();
        Bg.run(() -> {
            File f = file(app, p);
            if (f.length() == 0 && !download(p.url, f)) return;
            Bitmap b = decode(f, w, h, look);
            if (b != null) Bg.main(() -> ready.onBitmap(p, b));
        });
    }

    /** Warm the disk cache for a photo we'll want soon (e.g. the next slide). */
    static void prefetch(Context c, Photo p) {
        Context app = c.getApplicationContext();
        Bg.run(() -> {
            File f = file(app, p);
            if (f.length() == 0) download(p.url, f);
        });
    }

    private static boolean download(String url, File dest) {
        File tmp = new File(dest.getPath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            // Wikimedia asks clients to identify themselves.
            conn.setRequestProperty("User-Agent", "CleanHome/1.0 (personal TV launcher)");
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decode(File f, int w, int h, Look look) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            f.delete();
            return null;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
        if (src == null) return null;

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // Centre-crop the photo onto the screen-size canvas.
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int sx = (src.getWidth() - sw) / 2, sy = (src.getHeight() - sh) / 2;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, w, h), p);
        src.recycle();

        Paint shade = new Paint();
        if (look == Look.HOME) {
            c.drawColor(0x59000000);
            shade.setShader(new LinearGradient(0, 0, 0, h * 0.32f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.32f, shade);
            shade.setShader(new LinearGradient(0, h * 0.30f, 0, h, 0x00000000, 0xB3000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.30f, w, h, shade);
        }
        // Screensaver photos stay untouched; their text sits on soft halos instead.
        return Ui.toHardware(out);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
