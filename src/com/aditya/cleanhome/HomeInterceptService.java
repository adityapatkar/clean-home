package com.aditya.cleanhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Fire OS always sends the Home button to Amazon's launcher and never passes the key
 * to third-party apps, so this service watches for Amazon's home screen and bounces
 * it to HomeActivity.
 *
 * After a Home press Android freezes launches by ordinary apps for 5 seconds, so our
 * own startActivity() would only queue. Instead the service clicks our tile in
 * Amazon's pinned app row: Amazon's launcher is allowed to launch during the freeze.
 * A cover overlay goes up the instant Amazon's home appears, so the redirect looks
 * like our home fading in rather than Amazon's home flashing.
 *
 * Switching HDMI inputs needs a permission only Amazon-signed apps get, so input
 * changes are made the same way: open Amazon's home, click Inputs, click the input.
 */
public class HomeInterceptService extends AccessibilityService {

    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    /** Only the main home screen is bounced; Amazon's app list and input pickers stay usable. */
    static final String AMAZON_HOME_ACTIVITY = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String INPUT_PLAYER = "com.amazon.tv.inputpreference.service";
    private static final String SETTINGS = "com.amazon.tv.settings.v2";

    private static final String[] TILE_LABELS = {"Clean Home"};
    /** App tiles are FrameLayouts; Amazon's "Home" tab in the nav bar is a ViewGroup. */
    private static final String TILE_CLASS = "android.widget.FrameLayout";
    private static final String NAV_CLASS = "android.view.ViewGroup";

    private static final long DEDUPE_MS = 1500;
    private static final long CLICK_GIVE_UP_MS = 1500;
    private static final long RETRY_MS = 50;
    private static final long COVER_MAX_MS = 6000;
    /** Amazon's home flashes for a moment on the way into Fire TV Settings. */
    private static final long AFTER_SETTINGS_MS = 2500;
    /** Amazon re-shows its home window while it hands over to the HDMI player. */
    private static final long AFTER_INPUT_MS = 3000;

    private static HomeInterceptService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long bounceStarted = -DEDUPE_MS;
    private long lastSettingsWindow = -AFTER_SETTINGS_MS;
    private long pausedUntil;
    private long lastHomeShown = -10_000;
    private int quickReturns;
    private boolean clicked;
    private View cover;

    private String inputLabel;
    private int inputKind;
    private long inputRequested;
    private boolean inputsOpened;
    private int inputAttempts;
    private long lastInputClick = -AFTER_INPUT_MS;

    // ------------------------------------------------------------ entry points

    static boolean isRunning() {
        return instance != null;
    }

    /** Called by HomeActivity once it is on screen. */
    static void homeShown() {
        HomeInterceptService s = instance;
        if (s == null) return;
        s.hideCover();
        s.lastHomeShown = SystemClock.uptimeMillis();
    }

    /**
     * Switch to a TV input via Amazon's own picker. {@code kind}: 0 HDMI/other,
     * 1 tuner, 2 composite. Returns false if the service is not running.
     */
    static boolean switchInput(Context from, String label, int kind) {
        HomeInterceptService s = instance;
        if (s == null) return false;
        s.inputLabel = label;
        s.inputKind = kind;
        s.inputRequested = SystemClock.uptimeMillis();
        s.inputsOpened = false;
        s.inputAttempts = 0;
        s.showCover();
        from.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(AMAZON_HOME, AMAZON_HOME_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        return true;
    }

    /**
     * Fire OS goes to its home just before the screen turns off, and on power-on that
     * screen simply resumes - no window change, so no event. Check on every wake.
     */
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
            // Give the resumed window a moment to become the active one.
            handler.postDelayed(HomeInterceptService.this::bounceIfAmazonInFront, 400);
            handler.postDelayed(HomeInterceptService.this::bounceIfAmazonInFront, 1500);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        // Covers the service (re)starting at boot or after an update while Amazon is up.
        handler.postDelayed(this::bounceIfAmazonInFront, 800);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        try {
            unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        hideCover();
        return super.onUnbind(intent);
    }

    private boolean screenOn() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void bounceIfAmazonInFront() {
        if (!screenOn() || inputLabel != null || Prefs.amazonHomeAllowed(this)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !AMAZON_HOME.contentEquals(String.valueOf(root.getPackageName()))) return;
        long now = SystemClock.uptimeMillis();
        if (now - bounceStarted < DEDUPE_MS) return;
        startBounce(now);
    }

    // ------------------------------------------------------------ events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        long now = SystemClock.uptimeMillis();

        if (SETTINGS.contentEquals(pkg)) lastSettingsWindow = now;
        if (INPUT_PLAYER.contentEquals(pkg)) {
            lastInputClick = now;
            inputLabel = null;
            hideCover();
            return;
        }
        if (!AMAZON_HOME.contentEquals(pkg)
                || !AMAZON_HOME_ACTIVITY.contentEquals(String.valueOf(event.getClassName()))) return;

        if (inputLabel != null && now - inputRequested < COVER_MAX_MS) {
            handler.removeCallbacks(pickInput);
            handler.post(pickInput);
            return;
        }
        if (Prefs.amazonHomeAllowed(this)) return;
        // Fire OS shows its home as it goes to sleep; redirecting a dark screen is
        // pointless (and used to trip the loop guard). The wake check handles it.
        if (!screenOn()) return;
        if (now - lastSettingsWindow < AFTER_SETTINGS_MS) return;
        if (now - lastInputClick < AFTER_INPUT_MS) return;
        if (now < pausedUntil) return;
        // Amazon reports its home window twice per Home press.
        if (now - bounceStarted < DEDUPE_MS) return;
        if (looping(now)) return;
        startBounce(now);
    }

    private void startBounce(long now) {
        bounceStarted = now;
        clicked = false;
        showCover();
        handler.removeCallbacks(clickTile);
        handler.post(clickTile);
    }

    /**
     * Safety valve for a genuine fight (something reopening Amazon's home right after
     * ours appears): three returns within a second of our home showing, in a row,
     * pauses redirects for 20 s. Ordinary Home presses, however quick, never count.
     */
    private boolean looping(long now) {
        if (now - lastHomeShown < 1000) quickReturns++;
        else quickReturns = 0;
        if (quickReturns >= 3) {
            quickReturns = 0;
            pausedUntil = now + 20_000;
            hideCover();
            return true;
        }
        return false;
    }

    private final Runnable clickTile = new Runnable() {
        @Override
        public void run() {
            if (clicked) return;
            if (SystemClock.uptimeMillis() - bounceStarted > CLICK_GIVE_UP_MS) {
                // Tile not pinned in Amazon's row: launch directly. During the
                // post-Home freeze this lands after up to 5 seconds.
                launchDirect();
                return;
            }
            clicked = clickNode(TILE_LABELS, TILE_CLASS);
            if (!clicked) handler.postDelayed(this, RETRY_MS);
        }
    };

    private final Runnable pickInput = new Runnable() {
        @Override
        public void run() {
            if (inputLabel == null) return;
            if (SystemClock.uptimeMillis() - inputRequested > COVER_MAX_MS) {
                inputLabel = null;
                hideCover();
                return;
            }
            if (!inputsOpened) {
                // Amazon reveals the input row when the Inputs tab gains focus.
                inputsOpened = focusInputsTab();
                handler.postDelayed(this, RETRY_MS * 2);
                return;
            }
            long sinceClick = SystemClock.uptimeMillis() - lastInputClick;
            if (sinceClick < 1200) {
                // Amazon fires a second window event mid-handover; clicking again
                // then can hit a recycled tile and pick the wrong input.
                handler.postDelayed(this, 1200 - sinceClick);
                return;
            }
            // The player takes ~0.5 s to open; click again only if it hasn't by then
            // (a click that lands while the row animates in just focuses the tile).
            if (inputAttempts < 3 && clickInput()) {
                inputAttempts++;
                lastInputClick = SystemClock.uptimeMillis();
                handler.postDelayed(this, 1200);
            } else {
                handler.postDelayed(this, RETRY_MS * 2);
            }
        }
    };

    private void launchDirect() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Amazon's home window, even when our cover overlay is the active window. */
    private AccessibilityNodeInfo amazonRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && AMAZON_HOME.contentEquals(String.valueOf(root.getPackageName()))) return root;
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && AMAZON_HOME.contentEquals(String.valueOf(r.getPackageName()))) return r;
        }
        return null;
    }

    /**
     * Breadth-first search of a window's nodes. Amazon's views don't answer the
     * platform's findAccessibilityNodeInfosByText, so we walk the tree ourselves;
     * the cap keeps a pathological tree from stalling the main thread.
     */
    private interface Match {
        boolean test(AccessibilityNodeInfo n, String desc);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Match m) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 800) {
            AccessibilityNodeInfo n = queue.poll();
            // Off-screen rows hold most of Amazon's nodes; skipping them keeps this fast.
            if (!n.isVisibleToUser()) continue;
            CharSequence d = n.getContentDescription();
            if (d != null && m.test(n, d.toString())) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private boolean clickNode(String[] labels, String cls) {
        AccessibilityNodeInfo root = amazonRoot();
        if (root == null) return false;
        // Fast path: one IPC. Works for Amazon's app-row tiles.
        for (String label : labels) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                if (n.isClickable() && cls.contentEquals(String.valueOf(n.getClassName()))
                        && label.contentEquals(String.valueOf(n.getContentDescription()))) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }
        // Slow path: walk the tree (each child is an IPC), for views that don't answer text search.
        AccessibilityNodeInfo n = find(root, (node, d) -> node.isClickable()
                && cls.contentEquals(String.valueOf(node.getClassName()))
                && java.util.Arrays.asList(labels).contains(d));
        return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static final String INPUTS_TAB_ID = AMAZON_HOME + ":id/nav_bar_inputs_text_icon";

    private boolean focusInputsTab() {
        AccessibilityNodeInfo root = amazonRoot();
        if (root == null) return false;
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(INPUTS_TAB_ID);
        AccessibilityNodeInfo n = !byId.isEmpty() ? byId.get(0) : find(root, (node, d) ->
                NAV_CLASS.contentEquals(String.valueOf(node.getClassName())) && "Inputs".equals(d));
        if (n == null) return false;
        boolean focused = n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return focused || clicked;
    }

    private static final String ROW_ID = AMAZON_HOME + ":id/row_content";

    /** Input tiles are children of a row_content RecyclerView; match their names loosely. */
    private boolean clickInput() {
        AccessibilityNodeInfo root = amazonRoot();
        if (root == null) return false;
        String want = norm(inputLabel);
        String key = inputKind == 1 ? "antenna" : inputKind == 2 ? "composite" : null;
        AccessibilityNodeInfo byKind = null;
        for (AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByViewId(ROW_ID)) {
            for (int i = 0; i < row.getChildCount(); i++) {
                AccessibilityNodeInfo n = row.getChild(i);
                if (n == null || n.getContentDescription() == null || !n.isClickable()) continue;
                String d = norm(n.getContentDescription().toString());
                if (d.equals(want)) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (byKind == null && key != null && d.contains(key)) byKind = n;
            }
        }
        return byKind != null && byKind.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------ cover

    private final Runnable coverTimeout = this::hideCover;

    private void showCover() {
        handler.removeCallbacks(coverTimeout);
        handler.postDelayed(coverTimeout, COVER_MAX_MS);
        if (cover != null) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable, so Amazon's window stays active for the tile click.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        cover = new Backdrop(this);
        try {
            wm.addView(cover, lp);
        } catch (RuntimeException e) {
            cover = null;
        }
    }

    private void hideCover() {
        handler.removeCallbacks(coverTimeout);
        if (cover == null) return;
        View v = cover;
        cover = null;
        // Linger briefly so our activity's first frame is already drawn underneath.
        handler.postDelayed(() -> {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
            } catch (RuntimeException ignored) {
            }
        }, 32);
    }

    @Override
    public void onInterrupt() {
    }
}
