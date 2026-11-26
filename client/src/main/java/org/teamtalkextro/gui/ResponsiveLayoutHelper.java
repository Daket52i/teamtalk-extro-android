package org.teamtalkextro.gui;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Pure responsive layout calculations and tablet UI helpers for phones,
 * foldables, 7"/10"/12" tablets, and Chromebooks.
 */
public final class ResponsiveLayoutHelper {

    public static final int TABLET_MIN_WIDTH_DP = 600;
    public static final int LARGE_TABLET_WIDTH_DP = 960;

    private ResponsiveLayoutHelper() {
    }

    /**
     * Computes RecyclerView grid span count for the server list:
     * - < 600dp: 1 column (phones)
     * - 600..959dp: 2 columns (tablets / wide landscape)
     * - >= 960dp: 3 columns (large tablets / desktop)
     */
    public static int computeServerGridSpan(int screenWidthDp) {
        if (screenWidthDp >= LARGE_TABLET_WIDTH_DP) {
            return 3;
        }
        if (screenWidthDp >= TABLET_MIN_WIDTH_DP) {
            return 2;
        }
        return 1;
    }

    public static boolean isTabletWidth(int smallestWidthDp) {
        return smallestWidthDp >= TABLET_MIN_WIDTH_DP;
    }

    /**
     * Computes capped width in pixels when screen width exceeds maxDp, or -1 (MATCH_PARENT)
     * when on narrower screens.
     */
    public static int computeMaxContentWidthPx(int screenWidthDp, int maxDp, float density) {
        if (maxDp <= 0 || screenWidthDp <= maxDp) {
            return ViewGroup.LayoutParams.MATCH_PARENT;
        }
        return Math.round(maxDp * Math.max(0.5f, density));
    }

    public static int getScreenWidthDp(Context context) {
        if (context == null) return 360;
        Configuration cfg = context.getResources().getConfiguration();
        if (cfg != null && cfg.screenWidthDp > 0) {
            return cfg.screenWidthDp;
        }
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm != null && dm.density > 0 ? Math.round(dm.widthPixels / dm.density) : 360;
    }

    public static int getSmallestWidthDp(Context context) {
        if (context == null) return 360;
        Configuration cfg = context.getResources().getConfiguration();
        if (cfg != null && cfg.smallestScreenWidthDp > 0) {
            return cfg.smallestScreenWidthDp;
        }
        return getScreenWidthDp(context);
    }

    /**
     * Centers a container view and constrains its width to {@code maxDp} on tablets/wide displays,
     * keeping MATCH_PARENT on phones.
     */
    public static void applyCenteredMaxWidth(View container, int maxDp) {
        if (container == null || container.getContext() == null) return;
        Context ctx = container.getContext();
        int screenWidthDp = getScreenWidthDp(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        int targetWidth = computeMaxContentWidthPx(screenWidthDp, maxDp, density);

        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp == null) return;
        lp.width = targetWidth;
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER_HORIZONTAL;
        } else if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).gravity = Gravity.CENTER_HORIZONTAL;
        }
        container.setLayoutParams(lp);
    }
}
