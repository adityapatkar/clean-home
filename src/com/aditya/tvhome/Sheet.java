package com.aditya.tvhome;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Side sheet for every menu, with tvOS-style page navigation.
 *
 * Opening it freezes a blurred snapshot of the home screen as the scrim (one ~130 KB
 * bitmap, released on close) and springs a smoked-glass panel out of the control that
 * opened it. Rows can act and close, open a sub-page (slides in from the right, Back
 * slides it away), or be one of a set of options: choosing one moves the checkmark,
 * pauses a beat so the change registers, then returns to the parent page, which shows
 * the new value. Focus is trapped in the sheet until it closes.
 */
final class Sheet {

    /** A page of rows; rebuilt from its source whenever it is shown, so values are current. */
    interface Page {
        CharSequence title();

        CharSequence subtitle();

        List<Row> rows();
    }

    static Page page(CharSequence title, CharSequence subtitle, List<Row> rows) {
        return new Page() {
            @Override
            public CharSequence title() {
                return title;
            }

            @Override
            public CharSequence subtitle() {
                return subtitle;
            }

            @Override
            public List<Row> rows() {
                return rows;
            }
        };
    }

    interface PageSource {
        Page build();
    }

    static final class Row {
        final Icon.Kind icon;
        final CharSequence label;
        final CharSequence detail;
        final Runnable action;
        final boolean keepOpen;
        PageSource next;
        Boolean checked;

        /** Runs {@code action} and closes the sheet. */
        Row(Icon.Kind icon, CharSequence label, CharSequence detail, Runnable action) {
            this(icon, label, detail, action, false);
        }

        Row(Icon.Kind icon, CharSequence label, CharSequence detail, Runnable action, boolean keepOpen) {
            this.icon = icon;
            this.label = label;
            this.detail = detail;
            this.action = action;
            this.keepOpen = keepOpen;
        }

        /** Opens a sub-page; the row shows a chevron. */
        static Row nav(Icon.Kind icon, CharSequence label, CharSequence detail, PageSource next) {
            Row r = new Row(icon, label, detail, null, true);
            r.next = next;
            return r;
        }

        /** One choice in a set: shows a checkmark when selected, applies, then goes back. */
        static Row option(CharSequence label, CharSequence detail, boolean checked, Runnable apply) {
            Row r = new Row(null, label, detail, apply, true);
            r.checked = checked;
            return r;
        }

        /** Information only: focusable for reading, does nothing on select. */
        static Row info(Icon.Kind icon, CharSequence label, CharSequence detail) {
            return new Row(icon, label, detail, null, true);
        }
    }

    private final Context c;
    private final FrameLayout host;
    private final ViewGroup content;
    private FrameLayout layer;
    private FrameLayout stage;
    private View restoreFocus;
    private Bitmap blur;
    private boolean capturing;
    private View panelView, scrimView;

    /** Navigation stack: sources plus the row index that was focused when we left each. */
    private final List<PageSource> stack = new ArrayList<>();
    private final List<Integer> stackFocus = new ArrayList<>();
    private View currentPage;
    private List<View> currentRows = new ArrayList<>();
    private boolean busy;

    private final List<View> hidden = new ArrayList<>();
    private final List<Integer> hiddenWas = new ArrayList<>();

    Sheet(Context c, FrameLayout host, ViewGroup content) {
        this.c = c;
        this.host = host;
        this.content = content;
    }

    boolean isShowing() {
        return layer != null;
    }

    void show(CharSequence title, CharSequence subtitle, List<Row> rows) {
        show(title, subtitle, rows, null);
    }

    void show(CharSequence title, CharSequence subtitle, List<Row> rows, View anchor) {
        Page p = page(title, subtitle, rows);
        show(() -> p, anchor);
    }

    /**
     * @param anchor the control that opened the sheet; the panel grows out of it
     *               (tvOS-style) and sits beside it. May be null.
     */
    void show(PageSource source, View anchor) {
        if (capturing) return;
        if (layer != null) {
            removeNow();
        } else {
            restoreFocus = host.findFocus();
        }
        capturing = true;
        Glass.captureBlur((android.app.Activity) c, host, bmp -> {
            capturing = false;
            blur = bmp;
            content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            stack.clear();
            stackFocus.clear();
            stack.add(source);
            open(source.build(), anchor);
        });
    }

    /** Back: up one page, or close from the root page. */
    void back() {
        if (busy) return;
        if (stack.size() > 1) pop();
        else dismiss();
    }

    // ------------------------------------------------------------ open / close

    private void open(Page page, View anchor) {
        layer = new FrameLayout(c);
        ImageView scrim = new ImageView(c);
        scrim.setScaleType(ImageView.ScaleType.FIT_XY);
        scrim.setImageBitmap(blur);
        scrim.setColorFilter(0x59000000);
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        int m = Ui.dp(c, 28);
        FrameLayout panel = new FrameLayout(c);
        Glass glass = new Glass(Ui.dp(c, Ui.RADIUS_PANEL), Ui.dp(c, 1.2f), 1f);
        glass.setSmoked(true);
        panel.setBackground(glass);
        // No elevation: a large soft shadow on a scaling panel is re-rasterised every
        // frame and costs more than the rest of the menu; the frosted scrim already
        // separates the panel from the home screen.
        stage = panel;

        // Sized to its content like tvOS menus; top-bar menus hang from the top,
        // tile menus sit level with the tile.
        boolean fromTopBar = anchor != null && anchorCenterY(anchor) < host.getHeight() / 4;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(c, 420), -2,
                Gravity.END | (fromTopBar ? Gravity.TOP : Gravity.CENTER_VERTICAL));
        lp.setMargins(m, fromTopBar ? Ui.dp(c, 96) : m, m, m);
        layer.addView(panel, lp);
        // Height changes between pages glide instead of jumping.
        LayoutTransition lt = new LayoutTransition();
        lt.disableTransitionType(LayoutTransition.APPEARING);
        lt.disableTransitionType(LayoutTransition.DISAPPEARING);
        lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setDuration(LayoutTransition.CHANGING, 240);
        lt.setInterpolator(LayoutTransition.CHANGING, new DecelerateInterpolator(1.6f));
        layer.setLayoutTransition(lt);
        host.addView(layer, new FrameLayout.LayoutParams(-1, -1));

        currentPage = buildPage(page);
        panel.addView(currentPage, new FrameLayout.LayoutParams(-1, -2));

        // Depth: the frosted home screen fades in and drifts slightly toward the viewer.
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::hideUnderneath).start();
        scrim.animate().scaleX(1.03f).scaleY(1.03f).setDuration(480)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();

        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.setTranslationX(Ui.dp(c, 24));
        for (View v : currentRows) {
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(c, 10));
        }
        panel.post(() -> {
            // Grow out of the control that opened the menu.
            panel.setPivotX(panel.getWidth());
            float py = anchor == null ? panel.getHeight() / 2f : anchorCenterY(anchor) - panel.getTop();
            panel.setPivotY(Math.max(0, Math.min(panel.getHeight(), py)));
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0)
                    .setDuration(520).setInterpolator(Spring.GENTLE).start();
            cascade(currentRows, 70);
        });
        panelView = panel;
        scrimView = scrim;
        focusRow(0);
    }

    void dismiss() {
        if (layer == null) return;
        final FrameLayout l = layer;
        layer = null;
        stage = null;
        stack.clear();
        stackFocus.clear();
        restoreUnderneath();
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restoreFocus != null && restoreFocus.isAttachedToWindow()) restoreFocus.requestFocus();
        // Leave quickly and quietly: shrink back toward the anchor while fading.
        if (panelView != null) {
            panelView.animate().cancel();
            panelView.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).translationX(Ui.dp(c, 16))
                    .setDuration(170).setInterpolator(new AccelerateInterpolator(1.4f))
                    .withLayer().start();
        }
        if (scrimView != null) scrimView.animate().cancel();
        View scrim = scrimView;
        panelView = null;
        scrimView = null;
        (scrim != null ? scrim : l).animate().alpha(0f).setDuration(220).withEndAction(() -> {
            host.removeView(l);
            releaseBlur();
        }).start();
    }

    private void removeNow() {
        restoreUnderneath();
        host.removeView(layer);
        layer = null;
        stage = null;
        releaseBlur();
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void releaseBlur() {
        if (blur != null && layer == null) {
            blur.recycle();
            blur = null;
        }
    }

    // ------------------------------------------------------------ navigation

    private void push(PageSource next) {
        if (busy || stage == null) return;
        stackFocus.add(focusedIndex());
        stack.add(next);
        swap(next.build(), true, 0);
    }

    private void pop() {
        if (busy || stack.size() < 2) return;
        stack.remove(stack.size() - 1);
        int focus = stackFocus.remove(stackFocus.size() - 1);
        // Rebuild the parent so it shows the value just chosen.
        swap(stack.get(stack.size() - 1).build(), false, focus);
    }

    /** Slide the current page out and the new one in (forward = from the right). */
    private void swap(Page page, boolean forward, int focusIndex) {
        busy = true;
        View old = currentPage;
        float d = Ui.dp(c, 56) * (forward ? 1 : -1);
        old.animate().cancel();
        old.animate().alpha(0f).translationX(-d).setDuration(150)
                .setInterpolator(new AccelerateInterpolator(1.2f)).withEndAction(() -> {
                    if (stage == null) return;
                    stage.removeView(old);
                }).start();

        View fresh = buildPage(page);
        currentPage = fresh;
        fresh.setAlpha(0f);
        fresh.setTranslationX(d);
        stage.addView(fresh, new FrameLayout.LayoutParams(-1, -2));
        fresh.animate().alpha(1f).translationX(0).setStartDelay(90).setDuration(360)
                .setInterpolator(Spring.GENTLE).withEndAction(() -> busy = false).start();
        focusRow(focusIndex);
    }

    private int focusedIndex() {
        for (int i = 0; i < currentRows.size(); i++) if (currentRows.get(i).hasFocus()) return i;
        return 0;
    }

    private void focusRow(int i) {
        if (currentRows.isEmpty()) return;
        currentRows.get(Math.max(0, Math.min(currentRows.size() - 1, i))).requestFocus();
    }

    private void cascade(List<View> rows, long startDelay) {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).animate().alpha(1f).translationY(0).setStartDelay(startDelay + i * 28L)
                    .setDuration(300).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }

    // ------------------------------------------------------------ building

    private View buildPage(Page page) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 24);
        v.setPadding(p, Ui.dp(c, 28), p, Ui.dp(c, 20));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (stack.size() > 1) {
            // Sub-pages show where Back goes.
            TextView chevron = Ui.text(c, 22, Ui.LABEL_2, Ui.LIGHT);
            chevron.setText("‹");
            chevron.setPadding(0, 0, Ui.dp(c, 12), Ui.dp(c, 3));
            head.addView(chevron);
        }
        TextView t = Ui.text(c, 24, Ui.LABEL, Ui.MEDIUM);
        t.setText(page.title());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        v.addView(head);
        if (page.subtitle() != null) {
            TextView s = Ui.text(c, 14, Ui.LABEL_2, Ui.REGULAR);
            s.setText(page.subtitle());
            s.setPadding(0, Ui.dp(c, 8), 0, 0);
            s.setLineSpacing(0, 1.15f);
            v.addView(s);
        }

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 6));
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        List<View> made = new ArrayList<>();
        List<Row> rows = page.rows();
        List<ImageView> checks = new ArrayList<>();
        for (Row r : rows) {
            View row = row(r, rows, checks);
            made.add(row);
            list.addView(row);
        }
        // Wrap focus top <-> bottom so long menus are quick to traverse.
        for (View row : made) row.setId(View.generateViewId());
        if (made.size() > 1) {
            made.get(0).setNextFocusUpId(made.get(made.size() - 1).getId());
            made.get(made.size() - 1).setNextFocusDownId(made.get(0).getId());
        }
        scroll.addView(list);
        v.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        currentRows = made;
        return v;
    }

    private View row(Row r, List<Row> siblings, List<ImageView> checks) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        // Fade each part directly rather than through an offscreen layer: on this
        // tile-based GPU every layer is a render-target switch, which costs more
        // than the whole rest of the menu.
        v.forceHasOverlappingRendering(false);
        int h = Ui.dp(c, 16);
        v.setPadding(h, 0, h, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(c, 14));
        bg.setColor(0x00FFFFFF);
        v.setBackground(bg);

        Icon icon = r.icon == null ? null : new Icon(r.icon, Ui.LABEL);
        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            int s = Ui.dp(c, 22);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.rightMargin = Ui.dp(c, 16);
            v.addView(iv, ilp);
        }
        // Options carry a leading checkmark slot so labels line up whether or not checked.
        Icon checkIcon = null;
        ImageView check = null;
        if (r.checked != null) {
            checkIcon = new Icon(Icon.Kind.CHECK, Ui.ACCENT);
            check = new ImageView(c);
            check.setImageDrawable(checkIcon);
            int s = Ui.dp(c, 20);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, s);
            clp.rightMargin = Ui.dp(c, 16);
            v.addView(check, clp);
            check.setAlpha(r.checked ? 1f : 0f);
            checks.add(check);
        }
        TextView label = Ui.text(c, 17, Ui.LABEL, Ui.REGULAR);
        label.setText(r.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = null;
        if (r.detail != null) {
            detail = Ui.text(c, 15, Ui.LABEL_2, Ui.REGULAR);
            detail.setText(r.detail);
            v.addView(detail);
        }
        TextView chevron = null;
        if (r.next != null) {
            chevron = Ui.text(c, 22, Ui.LABEL_3, Ui.LIGHT);
            chevron.setText("›");
            chevron.setPadding(Ui.dp(c, 12), 0, 0, Ui.dp(c, 3));
            v.addView(chevron);
        }
        final TextView d = detail, ch = chevron;
        final Icon ci = checkIcon;
        v.setOnFocusChangeListener((view, has) -> {
            bg.setColor(has ? Ui.FOCUS_FILL : 0x00FFFFFF);
            label.setTextColor(has ? Ui.ON_FOCUS : Ui.LABEL);
            if (d != null) d.setTextColor(has ? 0x99111114 : Ui.LABEL_2);
            if (ch != null) ch.setTextColor(has ? 0x66111114 : Ui.LABEL_3);
            if (icon != null) icon.setColor(has ? Ui.ON_FOCUS : Ui.LABEL);
            if (ci != null) ci.setColor(has ? 0xFF0A84FF : Ui.ACCENT);
        });
        final ImageView myCheck = check;
        v.setOnClickListener(view -> {
            if (busy) return;
            if (r.next != null) {
                push(r.next);
            } else if (r.checked != null) {
                choose(r, myCheck, checks);
            } else {
                if (!r.keepOpen) dismiss();
                if (r.action != null) r.action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 56));
        lp.bottomMargin = Ui.dp(c, 4);
        v.setLayoutParams(lp);
        return v;
    }

    /** Move the checkmark (others fade, this one pops), apply, then return to the parent. */
    private void choose(Row r, ImageView check, List<ImageView> checks) {
        busy = true;
        for (ImageView other : checks) {
            if (other != check) other.animate().alpha(0f).setDuration(140).start();
        }
        check.setScaleX(0.4f);
        check.setScaleY(0.4f);
        check.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Spring.LIVELY).start();
        if (r.action != null) r.action.run();
        // A beat to see the check land, then back to the page that shows the value.
        check.postDelayed(() -> {
            busy = false;
            if (stack.size() > 1) pop();
            else dismiss();
        }, 420);
    }

    // ------------------------------------------------------------ helpers

    /**
     * Once the frosted scrim is opaque, the home screen under it is invisible anyway;
     * stop drawing it so the menu's animation frames only render the menu.
     */
    private void hideUnderneath() {
        if (layer == null) return;
        for (int i = 0; i < host.getChildCount(); i++) {
            View v = host.getChildAt(i);
            if (v == layer || v.getVisibility() != View.VISIBLE) continue;
            hidden.add(v);
            hiddenWas.add(v.getVisibility());
            v.setVisibility(View.INVISIBLE);
        }
    }

    private void restoreUnderneath() {
        for (int i = 0; i < hidden.size(); i++) hidden.get(i).setVisibility(hiddenWas.get(i));
        hidden.clear();
        hiddenWas.clear();
    }

    private int anchorCenterY(View anchor) {
        int[] a = new int[2], h = new int[2];
        anchor.getLocationOnScreen(a);
        host.getLocationOnScreen(h);
        return a[1] - h[1] + anchor.getHeight() / 2;
    }
}
