package com.aditya.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Current conditions from Open-Meteo (open-source, no API key; data CC BY 4.0) for
 * the place set in config.properties at build time. The last good reading is kept in prefs so the top bar has
 * weather immediately, even offline; a refresh happens at most every 30 minutes.
 */
final class Weather {

    static final String CREDIT = "Weather data by Open-Meteo.com (CC BY 4.0)";

    private static String url(Context c) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + c.getString(R.string.weather_lat)
                + "&longitude=" + c.getString(R.string.weather_lon)
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=" + c.getString(R.string.weather_unit)
                + "&timezone=auto&forecast_days=1";
    }

    static String place(Context c) {
        return c.getString(R.string.weather_place);
    }
    private static final long FRESH_MS = 30 * 60_000;

    static final class Now {
        final int temp, feels, high, low, code;
        final boolean day;

        Now(int temp, int feels, int high, int low, int code, boolean day) {
            this.temp = temp;
            this.feels = feels;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
        }

        String condition() {
            return describe(code);
        }

        Icon.Kind icon() {
            return iconFor(code, day);
        }
    }

    interface Listener {
        void onWeather(Now now);
    }

    private static Now current;
    private static long fetchedAt;
    private static boolean loading;
    private static final List<Listener> listeners = new ArrayList<>();

    private Weather() {
    }

    /** Delivers the cached reading at once (if any) and refreshes it when stale. */
    static void observe(Context c, Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        if (current == null) restore(c);
        if (current != null) l.onWeather(current);
        refreshIfStale(c);
    }

    static void stop(Listener l) {
        listeners.remove(l);
    }

    static void refreshIfStale(Context c) {
        if (loading || System.currentTimeMillis() - fetchedAt < FRESH_MS) return;
        loading = true;
        Context app = c.getApplicationContext();
        String url = url(app);
        Bg.run(() -> {
            String body = download(url);
            Bg.main(() -> {
                loading = false;
                Now n = body == null ? null : parse(body);
                if (n == null) return;
                current = n;
                fetchedAt = System.currentTimeMillis();
                prefs(app).edit().putString("json", body).putLong("at", fetchedAt).apply();
                for (Listener l : new ArrayList<>(listeners)) l.onWeather(n);
            });
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    private static void restore(Context c) {
        SharedPreferences p = prefs(c);
        String body = p.getString("json", null);
        if (body == null) return;
        current = parse(body);
        fetchedAt = p.getLong("at", 0);
    }

    private static String download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "CleanHome/1.0");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                byte[] buf = new byte[1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Now parse(String body) {
        try {
            JSONObject j = new JSONObject(body);
            JSONObject cur = j.getJSONObject("current");
            JSONObject daily = j.getJSONObject("daily");
            return new Now(
                    (int) Math.round(cur.getDouble("temperature_2m")),
                    (int) Math.round(cur.getDouble("apparent_temperature")),
                    (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                    (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                    cur.getInt("weather_code"),
                    cur.getInt("is_day") == 1);
        } catch (Exception e) {
            return null;
        }
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return code >= 65 ? "Heavy rain" : "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "—";
    }

    static Icon.Kind iconFor(int code, boolean day) {
        if (code <= 1) return day ? Icon.Kind.SUN : Icon.Kind.MOON;
        if (code == 2) return day ? Icon.Kind.PARTLY : Icon.Kind.PARTLY_NIGHT;
        if (code == 3) return Icon.Kind.CLOUD;
        if (code == 45 || code == 48) return Icon.Kind.FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Icon.Kind.SNOW;
        if (code >= 95) return Icon.Kind.STORM;
        return Icon.Kind.RAIN;
    }
}
