package com.aditya.cleanhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Favourites, app order, hidden apps, screensaver choice and the "let Amazon home show" window. */
final class Prefs {

    private static final String ORDER = "order";
    private static final String FAVORITES = "favorites";
    private static final String HIDDEN = "hidden";
    private static final String AMAZON_UNTIL = "amazon_until";
    private static final String SCREENSAVER = "screensaver";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    private static List<String> list(Context c, String key) {
        String s = sp(c).getString(key, null);
        if (s == null) return null;
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private static void putList(Context c, String key, List<String> keys) {
        sp(c).edit().putString(key, TextUtils.join("\n", keys)).apply();
    }

    static List<String> order(Context c) {
        List<String> l = list(c, ORDER);
        return l == null ? new ArrayList<>() : l;
    }

    static void setOrder(Context c, List<String> keys) {
        putList(c, ORDER, keys);
    }

    /** Ordered favourite package names, or null before the user has ever set any. */
    static List<String> favorites(Context c) {
        return list(c, FAVORITES);
    }

    static void setFavorites(Context c, List<String> packages) {
        putList(c, FAVORITES, packages);
    }

    static Set<String> hidden(Context c) {
        return new HashSet<>(sp(c).getStringSet(HIDDEN, new HashSet<>()));
    }

    static void setHidden(Context c, Set<String> packages) {
        sp(c).edit().putStringSet(HIDDEN, packages).apply();
    }

    /** One-time switch to the user's chosen favourites (v3). Returns true once. */
    static boolean migrateFavorites(Context c) {
        if (sp(c).getInt("favorites_version", 0) >= 3) return false;
        sp(c).edit().putInt("favorites_version", 3).apply();
        return true;
    }

    static boolean photoBackground(Context c) {
        return sp(c).getBoolean("photo_bg", true);
    }

    static void setPhotoBackground(Context c, boolean on) {
        sp(c).edit().putBoolean("photo_bg", on).apply();
    }

    static int photoOffset(Context c) {
        return sp(c).getInt("photo_offset", 0);
    }

    static void setPhotoOffset(Context c, int offset) {
        sp(c).edit().putInt("photo_offset", offset).apply();
    }

    static boolean clockScreensaver(Context c) {
        return sp(c).getBoolean(SCREENSAVER, true);
    }

    static void setClockScreensaver(Context c, boolean on) {
        sp(c).edit().putBoolean(SCREENSAVER, on).apply();
    }

    static void allowAmazonHome(Context c, long millis) {
        sp(c).edit().putLong(AMAZON_UNTIL, System.currentTimeMillis() + millis).apply();
    }

    static boolean amazonHomeAllowed(Context c) {
        return System.currentTimeMillis() < sp(c).getLong(AMAZON_UNTIL, 0);
    }
}
