package com.aditya.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HomeActivity extends Activity {

    private static final int FAVORITE_COLUMNS = 5;
    private static final int APP_COLUMNS = 6;
    private static final long IDLE_MS = 5 * 60_000;
    private static final float FAVORITE_LIFT = 1.10f;
    private static final float APP_LIFT = 1.14f;

    /** Seeded favourites for the first run, in order of preference. */
    private static final List<String> DEFAULT_FAVORITES = Arrays.asList(
            "com.netflix.ninja", "com.amazon.firetv.youtube", "com.peacock.peacockfiretv",
            "tv.standard.nebula", "com.cbs.ott");

    /** System tools that are useful occasionally but are not "apps" to most people. */
    private static final Set<String> UTILITIES = new HashSet<>(Arrays.asList(
            "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.ssm",
            "com.amazon.vizzini.ftvcds", "com.amazon.spiderpork", "com.amazon.hedwig",
            "com.android.documentsui", "com.android.providers.downloads.ui",
            "com.amazon.tv.localgallery", "com.amazon.venezia", "com.amazon.cloud9",
            "com.apple.airplay.amazon.airplay", "com.esaba.downloader",
            "com.surfshark.vpnclient.android"));

    /**
     * Never shown: Amazon's own home (opening it just bounces back here) and the
     * legacy Prime Video stub, which only hands off to the current Prime Video app.
     */
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            HomeInterceptService.AMAZON_HOME, "com.amazon.avod"));

    private static final String ACCESSIBILITY_SERVICE_ID =
            "com.aditya.tvhome/com.aditya.tvhome.HomeInterceptService";
    private static final String DREAM_ID = "com.aditya.tvhome/com.aditya.tvhome.ClockDream";
    private static final String AMAZON_DREAM_ID = "com.amazon.ftv.screensaver/.app.services.ScreensaverService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<Tile> favorites = new ArrayList<>();
    private final List<Tile> apps = new ArrayList<>();
    private final List<Tile> utilities = new ArrayList<>();

    private FrameLayout root;
    private LinearLayout content;
    private Backdrop backdrop;
    private Sheet sheet;
    private GridLayout favGrid, appGrid, utilGrid;
    private TextView favHeader, appHeader, utilHeader;
    private TextView clock, day, date, netLabel;
    private Icon netIcon;
    private ScrollView scroll;
    private ImageView wallpaper;
    private android.graphics.Bitmap wallpaperBitmap;
    private TextView photoCredit;
    private TextView weatherTemp, weatherText, weatherRange;
    private ImageView weatherIcon;
    private View weatherPill;
    private View lastAnchor;
    private boolean needsReload;
    private boolean resetOnResume;
    private TextView hint;
    private Screensaver idleSaver;
    private int favW, favH, appW, appH, gap;

    private Tile moving;
    private int pendingTint;
    private final Runnable applyTint = () -> backdrop.setTint(pendingTint);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            String t = DateFormat.getTimeFormat(HomeActivity.this).format(now);
            clock.setText(t.replace(" AM", "").replace(" PM", ""));
            day.setText(DateFormat.format("EEEE", now));
            date.setText(DateFormat.format("MMMM d", now));
            handler.postDelayed(this, 60_000 - now.getTime() % 60_000);
        }
    };

    private final Runnable idle = this::showIdleSaver;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            colorCache.remove(intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart());
            reload();
        }
    };

    private final BroadcastReceiver networkChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNetwork();
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        // The backdrop is opaque and full-screen; a window background would be pure overdraw.
        getWindow().setBackgroundDrawable(null);
        sheet = new Sheet(this, root, content);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        IntentFilter n = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        n.addAction(WifiManager.RSSI_CHANGED_ACTION);
        registerReceiver(networkChanged, n);
        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(networkChanged);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeInterceptService.homeShown();
        if (resetOnResume) {
            resetOnResume = false;
            scroll.scrollTo(0, 0);
            focusFirst();
        }
        repairSettings();
        handler.post(tick);
        updateNetwork();
        armIdle();
        Weather.observe(this, weatherListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (needsReload) {
            needsReload = false;
            reload();
        }
        if (wallpaperBitmap == null) loadWallpaper();
    }

    @Override
    protected void onStop() {
        dropWallpaper();
        super.onStop();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(idle);
        hideIdleSaver();
        if (moving != null) endMove();
        Weather.stop(weatherListener);
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // The wallpaper is already dropped in onStop; under real pressure while hidden,
        // also let the banner tiles go (they are rebuilt on return).
        if (level >= TRIM_MEMORY_BACKGROUND && !hasWindowFocus()) {
            favGrid.removeAllViews();
            appGrid.removeAllViews();
            utilGrid.removeAllViews();
            favorites.clear();
            apps.clear();
            utilities.clear();
            needsReload = true;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Every Home press arrives here (directly or via the redirect). Like tvOS, Home
        // always lands at the top on the first favourite; Back from an app (no new
        // intent) returns to wherever you were.
        if (sheet.isShowing()) sheet.dismiss();
        if (moving != null) endMove();
        if (hasWindowFocus()) {
            scroll.smoothScrollTo(0, 0);
            focusFirst();
        } else {
            resetOnResume = true;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // requestFocus() before the window is attached is silently dropped, which
        // would make the first key press only place focus instead of moving it.
        if (hasFocus && getCurrentFocus() == null) focusFirst();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        armIdle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (idleSaver != null) {
            // Any key wakes the screen without also acting on the home screen.
            if (e.getAction() == KeyEvent.ACTION_UP) hideIdleSaver();
            return true;
        }
        if (moving != null && handleMoveKey(e)) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            if (sheet.isShowing()) {
                sheet.back();
            } else if (scroll.getScrollY() > 0) {
                scroll.smoothScrollTo(0, 0);
                focusFirst();
            }
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !sheet.isShowing()) {
            View f = getCurrentFocus();
            if (f instanceof Tile) showAppMenu((Tile) f);
            else showSettings(null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ layout

    private View buildLayout() {
        int margin = Ui.dp(this, Ui.MARGIN);
        int edge = Ui.dp(this, 20);
        gap = Ui.dp(this, 20);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int inner = screenW - 2 * margin;
        favW = (inner - (FAVORITE_COLUMNS - 1) * gap) / FAVORITE_COLUMNS;
        favH = favW * 9 / 16;
        appW = (inner - (APP_COLUMNS - 1) * gap) / APP_COLUMNS;
        appH = appW * 9 / 16;

        root = new FrameLayout(this);
        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.FIT_XY);
        wallpaper.setAlpha(0f);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(margin, Ui.dp(this, 30), margin, 0);
        // A view's content is clipped by its *parent's* clipChildren, so this must stay
        // on: it is what keeps scrolled tiles from drawing over the top bar. The
        // scroll area bleeds into the side margins so lifted tiles still have room.
        content.setClipToPadding(false);
        // The bar bleeds into the side margins (and pads back) so the end buttons'
        // focus zoom has room; content clips its children to their own bounds.
        int bleed = Ui.dp(this, 8);
        View bar = buildTopBar();
        bar.setPadding(bleed, 0, bleed, 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        blp.setMargins(-bleed, 0, -bleed, 0);
        content.addView(bar, blp);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(this, 44));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setClipChildren(false);
        sections.setClipToPadding(false);
        // Inner padding leaves room for the focused tile's lift so it is never clipped.
        sections.setPadding(edge, Ui.dp(this, 8), edge, Ui.dp(this, 56));

        favHeader = header("Favorites");
        favGrid = grid(FAVORITE_COLUMNS);
        appHeader = header("Apps");
        appGrid = grid(APP_COLUMNS);
        utilHeader = header("Utilities");
        utilGrid = grid(APP_COLUMNS);
        sections.addView(favHeader);
        sections.addView(favGrid);
        sections.addView(appHeader);
        sections.addView(appGrid);
        sections.addView(utilHeader);
        sections.addView(utilGrid);
        // Photo credit lives at the end of the grid, so it never sits on top of a tile.
        photoCredit = Ui.text(this, 13, Ui.LABEL_2, Ui.REGULAR);
        photoCredit.setPadding(Ui.dp(this, 2), Ui.dp(this, 28), 0, 0);
        sections.addView(photoCredit);
        scroll.addView(sections);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1);
        slp.setMargins(-edge, Ui.dp(this, 18), -edge, 0);
        content.addView(scroll, slp);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));


        hint = Ui.text(this, 15, Ui.LABEL, Ui.MEDIUM);
        int hp = Ui.dp(this, 20), vp = Ui.dp(this, 12);
        hint.setPadding(hp, vp, hp, vp);
        hint.setBackground(new Glass(Ui.dp(this, 100), Ui.dp(this, 1), 0.8f));
        hint.setText("Use the arrows to move  ·  Select to finish");
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = Ui.dp(this, 32);
        root.addView(hint, hlp);
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);

        clock = Ui.text(this, 54, Ui.LABEL, Ui.THIN);
        bar.addView(clock);
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), 0, 0);
        day = Ui.text(this, 17, Ui.LABEL, Ui.MEDIUM);
        date = Ui.text(this, 15, Ui.LABEL_2, Ui.REGULAR);
        date.setPadding(0, Ui.dp(this, 4), 0, 0);
        when.addView(day);
        when.addView(date);
        bar.addView(when);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        bar.addView(buildWeather(), new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));

        // Network status: informational, so not focusable.
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        int sp = Ui.dp(this, 16);
        status.setPadding(sp, 0, Ui.dp(this, 18), 0);
        status.setBackground(new Glass(Ui.dp(this, 100), Ui.dp(this, 1), 0.4f));
        netIcon = new Icon(Icon.Kind.WIFI, Ui.LABEL);
        ImageView ni = new ImageView(this);
        ni.setImageDrawable(netIcon);
        int is = Ui.dp(this, 20);
        status.addView(ni, new LinearLayout.LayoutParams(is, is));
        netLabel = Ui.text(this, 15, Ui.LABEL, Ui.MEDIUM);
        netLabel.setPadding(Ui.dp(this, 10), 0, 0, 0);
        status.addView(netLabel);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        stlp.leftMargin = Ui.dp(this, 12);
        bar.addView(status, stlp);

        bar.addView(roundButton(Icon.Kind.INPUT, "Inputs", v -> showInputs(v)));
        bar.addView(roundButton(Icon.Kind.GEAR, "Settings", v -> showSettings(v)));
        return bar;
    }

    /** Weather glass pill: icon, temperature, condition and today's range. Hidden until data arrives. */
    private View buildWeather() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 18), 0);
        pill.setBackground(new Glass(Ui.dp(this, 100), Ui.dp(this, 1), 0.4f));
        weatherIcon = new ImageView(this);
        int is = Ui.dp(this, 24);
        pill.addView(weatherIcon, new LinearLayout.LayoutParams(is, is));
        weatherTemp = Ui.text(this, 21, Ui.LABEL, Ui.MEDIUM);
        weatherTemp.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
        pill.addView(weatherTemp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        weatherText = Ui.text(this, 13, Ui.LABEL, Ui.MEDIUM);
        weatherRange = Ui.text(this, 12, Ui.LABEL_2, Ui.REGULAR);
        weatherRange.setPadding(0, Ui.dp(this, 3), 0, 0);
        col.addView(weatherText);
        col.addView(weatherRange);
        pill.addView(col);
        pill.setVisibility(View.GONE);
        weatherPill = pill;
        return pill;
    }

    private final Weather.Listener weatherListener = n -> {
        weatherIcon.setImageDrawable(new Icon(n.icon(), Ui.LABEL));
        weatherTemp.setText(n.temp + "\u00b0");
        weatherText.setText(n.condition());
        weatherRange.setText("H " + n.high + "\u00b0  \u00b7  L " + n.low + "\u00b0");
        weatherPill.setVisibility(View.VISIBLE);
    };

    // ------------------------------------------------------------ wallpaper

    /** Loads today's photo (if photos are on) and fades it in over the glow. */
    private void loadWallpaper() {
        loadWallpaper(false);
    }

    private void loadWallpaper(boolean announce) {
        if (!Prefs.photoBackground(this)) {
            dropWallpaper();
            photoCredit.setText(null);
            return;
        }
        Landscapes.Photo p = Landscapes.ofTheDay(this, Prefs.photoOffset(this));
        if (p == null) return;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        Landscapes.load(this, p, w, h, Landscapes.Look.HOME, (photo, bmp) -> {
            if (isDestroyed() || !Prefs.photoBackground(this)) {
                bmp.recycle();
                return;
            }
            android.graphics.Bitmap old = wallpaperBitmap;
            wallpaperBitmap = bmp;
            photoCredit.setText(photo.place + "  \u00b7  " + photo.credit());
            if (announce) showHud(Icon.Kind.PHOTO, photo.place);
            boolean wasShowing = wallpaper.getAlpha() > 0.99f && old != null;
            if (wasShowing) {
                // Crossfade: the old photo fades out on a temporary view above the new one.
                ImageView leaving = new ImageView(this);
                leaving.setScaleType(ImageView.ScaleType.FIT_XY);
                leaving.setImageBitmap(old);
                root.addView(leaving, root.indexOfChild(wallpaper) + 1, new FrameLayout.LayoutParams(-1, -1));
                wallpaper.setImageBitmap(bmp);
                leaving.animate().alpha(0f).setDuration(700).withEndAction(() -> {
                    root.removeView(leaving);
                    old.recycle();
                }).start();
                return;
            }
            wallpaper.setImageBitmap(bmp);
            if (old != null) old.recycle();
            wallpaper.animate().alpha(1f).setDuration(350).withEndAction(() -> {
                // Fully covered: stop drawing the glow underneath (saves a full-screen fill).
                if (wallpaperBitmap != null) backdrop.setVisibility(View.INVISIBLE);
            }).start();
        });
        Landscapes.prefetch(this, Landscapes.ofTheDay(this, Prefs.photoOffset(this) + 1));
    }

    /** Frees the ~4 MB photo while Home is not visible; it reloads from disk on return. */
    private void dropWallpaper() {
        backdrop.setVisibility(View.VISIBLE);
        wallpaper.animate().cancel();
        wallpaper.setAlpha(0f);
        wallpaper.setImageDrawable(null);
        if (wallpaperBitmap != null) {
            wallpaperBitmap.recycle();
            wallpaperBitmap = null;
        }
    }

    private View roundButton(Icon.Kind kind, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setContentDescription(description);
        Glass glass = new Glass(Ui.dp(this, 100), Ui.dp(this, 1), 0.5f);
        b.setBackground(glass);
        Icon icon = new Icon(kind, Ui.LABEL);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        int is = Ui.dp(this, 22);
        b.addView(iv, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
        android.graphics.drawable.GradientDrawable lit = new android.graphics.drawable.GradientDrawable();
        lit.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        lit.setColor(Ui.FOCUS_FILL);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? lit : glass);
            icon.setColor(has ? Ui.ON_FOCUS : Ui.LABEL);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .setDuration(Ui.FOCUS_MS).start();
        });
        b.setOnClickListener(click);
        int s = Ui.dp(this, 48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.leftMargin = Ui.dp(this, 12);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView header(String text) {
        TextView t = Ui.text(this, 16, Ui.LABEL_2, Ui.MEDIUM);
        t.setText(text);
        t.setLetterSpacing(0.02f);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 14), 0, Ui.dp(this, 14));
        return t;
    }

    private GridLayout grid(int columns) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(columns);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        return g;
    }

    // ------------------------------------------------------------ apps

    private void reload() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        // Leanback entries first: they point at the TV-optimised activity and carry banners.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || EXCLUDED.contains(pkg) || found.containsKey(pkg)) continue;
                found.put(pkg, ri);
            }
        }

        List<String> favs = Prefs.favorites(this);
        if (favs == null || Prefs.migrateFavorites(this)) {
            favs = new ArrayList<>();
            for (String p : DEFAULT_FAVORITES) if (found.containsKey(p) && favs.size() < FAVORITE_COLUMNS) favs.add(p);
            Prefs.setFavorites(this, favs);
        }

        Set<String> hidden = Prefs.hidden(this);
        Map<String, Tile.App> all = new LinkedHashMap<>();
        for (ResolveInfo ri : found.values()) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName)) continue;
            boolean fav = favs.contains(ai.packageName);
            // Render at the lifted size so focus stays crisp.
            float lift = fav ? FAVORITE_LIFT : APP_LIFT;
            Drawable banner = loadBanner(pm, ai, Math.round((fav ? favW : appW) * lift),
                    Math.round((fav ? favH : appH) * lift));
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            Integer color = colorCache.get(ai.packageName);
            if (color == null) {
                color = Tile.dominantColor(banner != null ? banner : icon);
                colorCache.put(ai.packageName, color);
            }
            banner = Ui.toHardware(getResources(), banner);
            all.put(ai.packageName, new Tile.App(new ComponentName(ai.packageName, ai.name),
                    ri.loadLabel(pm), banner, icon, color));
        }

        Object focusedPkg = getCurrentFocus() instanceof Tile ? ((Tile) getCurrentFocus()).app.pkg : null;
        favorites.clear();
        apps.clear();
        utilities.clear();
        favGrid.removeAllViews();
        appGrid.removeAllViews();
        utilGrid.removeAllViews();

        for (String p : favs) {
            Tile.App a = all.remove(p);
            if (a != null) favorites.add(tile(a, favW, favH, FAVORITE_LIFT));
        }
        List<Tile.App> rest = new ArrayList<>(all.values());
        List<String> order = Prefs.order(this);
        Collections.sort(rest, (a, b) -> {
            int ia = order.indexOf(a.pkg), ib = order.indexOf(b.pkg);
            if (ia >= 0 && ib >= 0) return ia - ib;
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
            return a.label.toString().compareToIgnoreCase(b.label.toString());
        });
        for (Tile.App a : rest) {
            Tile t = tile(a, appW, appH, APP_LIFT);
            (UTILITIES.contains(a.pkg) ? utilities : apps).add(t);
        }
        fill(favGrid, favorites, FAVORITE_COLUMNS);
        fill(appGrid, apps, APP_COLUMNS);
        fill(utilGrid, utilities, APP_COLUMNS);
        // The full-size banners just went out of scope; free their native pixels now
        // rather than whenever the next GC happens. Runs only on (re)load.
        System.gc();
        favHeader.setVisibility(favorites.isEmpty() ? View.GONE : View.VISIBLE);
        utilHeader.setVisibility(utilities.isEmpty() ? View.GONE : View.VISIBLE);

        if (focusedPkg != null) {
            for (Tile t : allTiles()) {
                if (t.app.pkg.equals(focusedPkg)) {
                    t.requestFocus();
                    return;
                }
            }
        }
        focusFirst();
    }

    /**
     * A tile-sized RGB_565 banner. Fire TV banners are often 640x360 or larger and
     * the platform decodes them as 32-bit; decoding the resource ourselves with
     * subsampling straight to 16-bit means the big original is never allocated, and
     * each banner costs ~1/8 of what loadBanner() would. Vector/XML banners fall
     * back to the platform loader and are rasterised at tile size.
     */
    private Drawable loadBanner(PackageManager pm, ActivityInfo ai, int w, int h) {
        int res = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (res == 0) return null;
        try {
            android.content.res.Resources r = pm.getResourcesForApplication(ai.applicationInfo);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeResource(r, res, o);
            if (o.outWidth > 0) {
                o.inJustDecodeBounds = false;
                // Decode resource pixels as stored, without density up-scaling.
                o.inScaled = false;
                o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= w) o.inSampleSize *= 2;
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeResource(r, res, o);
                if (b != null) return shrink(new android.graphics.drawable.BitmapDrawable(getResources(), b), w, h);
            }
        } catch (Exception ignored) {
        }
        Drawable d = ai.loadBanner(pm);
        if (d == null) d = ai.applicationInfo.loadBanner(pm);
        return d == null ? null : shrink(d, w, h);
    }

    /** Copies a drawable into an exact tile-size 16-bit bitmap (centre-cropped). */
    private Drawable shrink(Drawable d, int w, int h) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        // Centre-crop like the ImageView would.
        int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
        float scale = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
        android.graphics.Rect old = new android.graphics.Rect(d.getBounds());
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        // RGB_565 has no alpha: start from the tile colour so transparent banners
        // don't turn black at the corners.
        c.drawColor(0xFF1C1D22);
        d.draw(c);
        d.setBounds(old);
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap src = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            // Our own temporary decode can go now; platform-cached ones are left alone.
            if (src != null && src.getConfig() == android.graphics.Bitmap.Config.RGB_565 && src != b) src.recycle();
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    private Tile tile(Tile.App a, int w, int h, float lift) {
        Tile t = new Tile(this, a, w, h, lift);
        t.setOnClickListener(v -> {
            if (moving == t) endMove();
            else if (moving == null) launch(a);
        });
        t.setOnLongClickListener(v -> {
            if (moving == null) showAppMenu(t);
            return true;
        });
        t.setOnFocusChangeListener(chain(t.getOnFocusChangeListener(), (v, has) -> {
            if (!has) return;
            // Recolour only once focus settles: a full-screen crossfade on every
            // key press would make fast navigation redraw the whole screen.
            pendingTint = a.color;
            handler.removeCallbacks(applyTint);
            handler.postDelayed(applyTint, 250);
        }));
        return t;
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) {
        return (v, has) -> {
            if (a != null) a.onFocusChange(v, has);
            b.onFocusChange(v, has);
        };
    }

    private void fill(GridLayout g, List<Tile> tiles, int columns) {
        g.removeAllViews();
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(0, 0, (i % columns == columns - 1) ? 0 : gap, Ui.dp(this, 14));
            g.addView(t, lp);
        }
    }

    private List<Tile> allTiles() {
        List<Tile> l = new ArrayList<>(favorites);
        l.addAll(apps);
        l.addAll(utilities);
        return l;
    }

    private void focusFirst() {
        List<Tile> l = allTiles();
        if (!l.isEmpty()) l.get(0).requestFocus();
    }

    private void launch(Tile.App app) {
        start(new Intent(Intent.ACTION_MAIN)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), app.label);
    }

    private void start(Intent i, CharSequence what) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open " + what, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ moving

    private List<Tile> sectionOf(Tile t) {
        if (favorites.contains(t)) return favorites;
        if (utilities.contains(t)) return utilities;
        return apps;
    }

    private void startMove(Tile t) {
        moving = t;
        t.setMoving(true);
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setDuration(Ui.SHEET_MS).start();
        t.requestFocus();
    }

    private void endMove() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        hint.animate().alpha(0f).setDuration(Ui.SHEET_MS).withEndAction(() -> hint.setVisibility(View.GONE)).start();
        saveOrder();
    }

    private boolean handleMoveKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_UP) endMove();
            return true;
        }
        int delta;
        List<Tile> section = sectionOf(moving);
        int columns = section == favorites ? FAVORITE_COLUMNS : APP_COLUMNS;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = -columns; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = columns; break;
            default: return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int from = section.indexOf(moving);
        int to = Math.max(0, Math.min(section.size() - 1, from + delta));
        if (to == from) return true;
        section.remove(from);
        section.add(to, moving);
        GridLayout g = (GridLayout) moving.getParent();
        // Re-adding the views keeps the moved tile's state; nothing is re-inflated.
        fill(g, section, columns);
        moving.requestFocus();
        return true;
    }

    private void saveOrder() {
        List<String> favs = new ArrayList<>();
        for (Tile t : favorites) favs.add(t.app.pkg);
        Prefs.setFavorites(this, favs);
        List<String> order = new ArrayList<>();
        for (Tile t : apps) order.add(t.app.pkg);
        for (Tile t : utilities) order.add(t.app.pkg);
        Prefs.setOrder(this, order);
    }

    // ------------------------------------------------------------ menus

    private void showAppMenu(Tile t) {
        Tile.App a = t.app;
        boolean fav = favorites.contains(t);
        List<Sheet.Row> rows = new ArrayList<>();
        rows.add(new Sheet.Row(Icon.Kind.OPEN, "Open", null, () -> launch(a)));
        rows.add(new Sheet.Row(fav ? Icon.Kind.STAR_OFF : Icon.Kind.STAR,
                fav ? "Remove from Favorites" : "Add to Favorites", null, () -> toggleFavorite(a.pkg, !fav)));
        rows.add(new Sheet.Row(Icon.Kind.MOVE, "Move", null, () -> startMove(t)));
        rows.add(new Sheet.Row(Icon.Kind.HIDE, "Hide from Home", null, () -> setHidden(a.pkg, true)));
        rows.add(new Sheet.Row(Icon.Kind.INFO, "App Info", null, () -> start(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "app info")));
        sheet.show(a.label, null, rows, t);
    }

    private void toggleFavorite(String pkg, boolean add) {
        List<String> favs = Prefs.favorites(this);
        if (favs == null) favs = new ArrayList<>();
        favs.remove(pkg);
        if (add) favs.add(pkg);
        Prefs.setFavorites(this, favs);
        reload();
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> hidden = Prefs.hidden(this);
        if (hide) hidden.add(pkg); else hidden.remove(pkg);
        Prefs.setHidden(this, hidden);
        reload();
    }

    private void showInputs(View anchor) {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<Sheet.Row> rows = new ArrayList<>();
        if (tim != null) {
            List<TvInputInfo> list = new ArrayList<>(tim.getTvInputList());
            // HDMI first, then antenna, then composite; numbered ports in order.
            Collections.sort(list, (x, y) -> {
                int kx = inputRank(x), ky = inputRank(y);
                return kx != ky ? kx - ky : x.getId().compareTo(y.getId());
            });
            for (TvInputInfo info : list) {
                boolean tuner = info.getType() == TvInputInfo.TYPE_TUNER && info.getId().startsWith("com.mediatek");
                if (!info.isPassthroughInput() && !tuner) continue;
                if (info.isHidden(this)) continue;
                CharSequence custom = info.loadCustomLabel(this);
                String label = custom != null ? custom.toString()
                        : tuner ? "Antenna" : String.valueOf(info.loadLabel(this));
                int kind = tuner ? 1 : info.getType() == TvInputInfo.TYPE_COMPOSITE ? 2 : 0;
                Icon.Kind icon = tuner ? Icon.Kind.ANTENNA : Icon.Kind.INPUT;
                rows.add(new Sheet.Row(icon, label, null, () -> {
                    if (!HomeInterceptService.switchInput(this, label, kind)) {
                        Toast.makeText(this, "Turn on the Home button service first", Toast.LENGTH_SHORT).show();
                    }
                }));
            }
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show();
            return;
        }
        sheet.show("Inputs", null, rows, anchor);
    }

    private static int inputRank(TvInputInfo i) {
        if (i.getType() == TvInputInfo.TYPE_HDMI) return 0;
        if (i.getType() == TvInputInfo.TYPE_TUNER) return 1;
        return 2;
    }

    private void showSettings(View anchor) {
        if (anchor != null) lastAnchor = anchor;
        sheet.show(this::settingsPage, lastAnchor);
    }

    /** Built fresh each time it's shown, so every row's value is current. */
    private Sheet.Page settingsPage() {
        List<Sheet.Row> rows = new ArrayList<>();
        rows.add(new Sheet.Row(Icon.Kind.GEAR, "Fire TV Settings", null, () -> start(
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "settings")));
        boolean photo = Prefs.photoBackground(this);
        rows.add(Sheet.Row.nav(Icon.Kind.PHOTO, "Background", photo ? "Landscape" : "Ambient Glow",
                this::backgroundPage));
        if (photo) rows.add(new Sheet.Row(Icon.Kind.PHOTO, "Next Landscape", null, this::nextLandscape));
        rows.add(Sheet.Row.nav(Icon.Kind.MOON, "Screensaver",
                Prefs.clockScreensaver(this) ? "Landscapes" : "Amazon", this::screensaverPage));
        int hiddenCount = Prefs.hidden(this).size();
        rows.add(Sheet.Row.nav(Icon.Kind.HIDE, "Hidden Apps", hiddenCount == 0 ? "None" : String.valueOf(hiddenCount),
                this::hiddenPage));
        rows.add(new Sheet.Row(Icon.Kind.HOME, "Open Amazon Home", "2 min", () -> {
            Prefs.allowAmazonHome(this, 120_000);
            start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(HomeInterceptService.AMAZON_HOME, HomeInterceptService.AMAZON_HOME_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Amazon home");
        }));
        rows.add(Sheet.Row.nav(Icon.Kind.INFO, "About & Credits", null, this::creditsPage));
        String status = HomeInterceptService.isRunning() ? "Home button: on" : "Home button: off";
        return Sheet.page("Settings", "Clean Home  \u00b7  " + status, rows);
    }

    private Sheet.Page backgroundPage() {
        boolean photo = Prefs.photoBackground(this);
        List<Sheet.Row> rows = new ArrayList<>();
        rows.add(Sheet.Row.option("Landscape", "A new photo each day", photo, () -> {
            Prefs.setPhotoBackground(this, true);
            loadWallpaper();
        }));
        rows.add(Sheet.Row.option("Ambient Glow", "Tinted by the app in focus", !photo, () -> {
            Prefs.setPhotoBackground(this, false);
            loadWallpaper();
        }));
        return Sheet.page("Background", null, rows);
    }

    private Sheet.Page screensaverPage() {
        boolean ours = Prefs.clockScreensaver(this);
        List<Sheet.Row> rows = new ArrayList<>();
        rows.add(Sheet.Row.option("Landscapes", "Photos, time & weather", ours, () -> {
            Prefs.setClockScreensaver(this, true);
            applyScreensaver();
        }));
        rows.add(Sheet.Row.option("Amazon", "Fire TV's own screensaver", !ours, () -> {
            Prefs.setClockScreensaver(this, false);
            applyScreensaver();
        }));
        rows.add(new Sheet.Row(Icon.Kind.EYE, "Preview", null, () -> handler.postDelayed(this::showIdleSaver, 300)));
        return Sheet.page("Screensaver", "Starts after 5 minutes without using the remote.", rows);
    }

    private Sheet.Page hiddenPage() {
        Set<String> hidden = Prefs.hidden(this);
        PackageManager pm = getPackageManager();
        List<Sheet.Row> rows = new ArrayList<>();
        for (String pkg : hidden) {
            CharSequence label = pkg;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            CharSequence name = label;
            rows.add(new Sheet.Row(Icon.Kind.EYE, label, "Show", () -> {
                setHidden(pkg, false);
                showHud(Icon.Kind.EYE, name + " is back on Home");
            }));
        }
        if (rows.isEmpty()) rows.add(Sheet.Row.info(Icon.Kind.INFO, "No hidden apps", null));
        return Sheet.page("Hidden Apps", rows.size() > 1 || !hidden.isEmpty()
                ? "Select an app to put it back on Home." : "Hide apps from their menu (hold Select).", rows);
    }

    private Sheet.Page creditsPage() {
        List<Sheet.Row> rows = new ArrayList<>();
        rows.add(Sheet.Row.info(Icon.Kind.SUN, "Weather", "Open-Meteo.com \u00b7 CC BY 4.0"));
        rows.add(Sheet.Row.info(Icon.Kind.PHOTO, "Landscapes", "Wikimedia Commons"));
        for (Landscapes.Photo p : Landscapes.all(this)) {
            rows.add(Sheet.Row.info(null, p.place, p.by + " \u00b7 " + p.license));
        }
        return Sheet.page("About & Credits", "Weather for " + Weather.place(this)
                + "  \u00b7  Featured Pictures landscapes", rows);
    }

    /** Closes the menu so the new photo can be seen arriving, then names it. */
    private void nextLandscape() {
        Prefs.setPhotoOffset(this, Prefs.photoOffset(this) + 1);
        handler.postDelayed(() -> loadWallpaper(true), 220);
    }

    // ------------------------------------------------------------ HUD

    private View hud;

    /** A small glass banner under the top bar confirming something happened. */
    private void showHud(Icon.Kind icon, CharSequence text) {
        if (hud != null) root.removeView(hud);
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Ui.dp(this, 22);
        v.setPadding(Ui.dp(this, 18), 0, hp, 0);
        v.setBackground(new Glass(Ui.dp(this, 100), Ui.dp(this, 1), 0.9f));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icon(icon, Ui.LABEL));
        int is = Ui.dp(this, 20);
        v.addView(iv, new LinearLayout.LayoutParams(is, is));
        TextView t = Ui.text(this, 16, Ui.LABEL, Ui.MEDIUM);
        t.setText(text);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        v.addView(t);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 48),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.dp(this, 104);
        root.addView(v, lp);
        hud = v;
        v.setAlpha(0f);
        v.setTranslationY(-Ui.dp(this, 14));
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(Spring.GENTLE).start();
        v.postDelayed(() -> v.animate().alpha(0f).translationY(-Ui.dp(this, 8)).setDuration(260)
                .withEndAction(() -> {
                    root.removeView(v);
                    if (hud == v) hud = null;
                }).start(), 2400);
    }

    // ------------------------------------------------------------ status

    private void updateNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        if (caps == null) {
            netIcon = swapIcon(Icon.Kind.WIFI_OFF);
            netLabel.setText("Offline");
            netLabel.setTextColor(Ui.WARN);
            return;
        }
        boolean online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            netIcon = swapIcon(Icon.Kind.ETHERNET);
            netLabel.setText(online ? "Ethernet" : "No internet");
        } else {
            netIcon = swapIcon(Icon.Kind.WIFI);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int level = wm == null ? 3 : WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
            netIcon.setSignal(Math.max(1, level));
            netLabel.setText(online ? "Wi‑Fi" : "No internet");
        }
        netLabel.setTextColor(online ? Ui.LABEL : Ui.WARN);
    }

    private Icon swapIcon(Icon.Kind kind) {
        ImageView iv = (ImageView) ((ViewGroup) netLabel.getParent()).getChildAt(0);
        Icon icon = new Icon(kind, Ui.LABEL);
        iv.setImageDrawable(icon);
        return icon;
    }

    // ------------------------------------------------------------ screensaver

    private void armIdle() {
        handler.removeCallbacks(idle);
        // When we own the system screensaver, Fire OS starts it everywhere (not just
        // on Home); the in-launcher copy is only a fallback for when we can't set it.
        if (Prefs.clockScreensaver(this) && !ownsSystemScreensaver()) handler.postDelayed(idle, IDLE_MS);
    }

    private boolean ownsSystemScreensaver() {
        return DREAM_ID.equals(Settings.Secure.getString(getContentResolver(), "screensaver_components"));
    }

    /** In-launcher screensaver: the preview, and a fallback when the system dream isn't ours. */
    private void showIdleSaver() {
        if (idleSaver != null) return;
        if (sheet.isShowing()) sheet.dismiss();
        idleSaver = new Screensaver(this);
        idleSaver.setAlpha(0f);
        root.addView(idleSaver, new FrameLayout.LayoutParams(-1, -1));
        idleSaver.animate().alpha(1f).setDuration(900).start();
    }

    private void hideIdleSaver() {
        if (idleSaver == null) return;
        View v = idleSaver;
        idleSaver = null;
        v.animate().alpha(0f).setDuration(250).withEndAction(() -> root.removeView(v)).start();
        armIdle();
    }

    /**
     * With WRITE_SECURE_SETTINGS (granted once over ADB) the launcher keeps its own
     * Home service enabled and owns the screensaver choice. Without it, this is a no-op.
     */
    private void repairSettings() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = getContentResolver();
        try {
            String enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(ACCESSIBILITY_SERVICE_ID)) {
                String v = enabled == null || enabled.isEmpty() || enabled.equals("null")
                        ? ACCESSIBILITY_SERVICE_ID : enabled + ":" + ACCESSIBILITY_SERVICE_ID;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, v);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (RuntimeException ignored) {
        }
        applyScreensaver();
    }

    private void applyScreensaver() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Settings.Secure.putString(getContentResolver(), "screensaver_components",
                    Prefs.clockScreensaver(this) ? DREAM_ID : AMAZON_DREAM_ID);
        } catch (RuntimeException ignored) {
        }
    }
}
