package com.networktool.app.util;

import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 底部弹窗的统一行为设置。
 *
 * <p>Material 的 {@link BottomSheetDialog} 默认 {@code fitToContents=true}：
 * 弹窗高度等于内容高度，内容比屏幕高时直接超出屏幕且不能滚动，
 * 用户往上拖也没法展开、往下拖会立刻关掉。</p>
 */
public final class BottomSheetHelper {

    private static final String TAG = "BottomSheetHelper";

    private BottomSheetHelper() {
    }

    /**
     * 让弹窗铺到接近全屏，并且内容区自己滚动。
     *
     * <p>关键点：{@code fitToContents=false} 之后弹窗是「顶部对齐 + 偏移量」定位的，
     * 如果只把高度设成屏幕的百分之多少、偏移量留 0，弹窗底边会停在屏幕中间偏下的位置，
     * 底下露出一条 Activity 的界面（比如底部导航栏）。所以这里的高度必须取
     * 「可用高度 − 顶部留白」，让底边正好压到屏幕最底部。</p>
     *
     * <p>布局根节点用 match_parent：固定标题放顶部、内容放 NestedScrollView、
     * 需要常驻的按钮放底部。</p>
     *
     * @param topGapDp 展开后距屏幕顶部的留白，露出一点背景，方便拖动
     */
    public static void makeExpandable(BottomSheetDialog dialog, int topGapDp) {
        if (dialog == null) return;
        dialog.setOnShowListener(d -> {
            try {
                FrameLayout sheet = sheetOf(dialog);
                if (sheet == null) return;
                ViewGroup parent = (ViewGroup) sheet.getParent();

                int available = parent != null && parent.getHeight() > 0
                        ? parent.getHeight()
                        : dialog.getContext().getResources().getDisplayMetrics().heightPixels;
                int topGap = statusBarHeight(sheet) + dp(sheet, topGapDp);
                int height = Math.max(dp(sheet, 320), available - topGap);

                ViewGroup.LayoutParams params = sheet.getLayoutParams();
                params.height = height;
                sheet.setLayoutParams(params);

                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setFitToContents(false);
                behavior.setSkipCollapsed(true);
                behavior.setHalfExpandedRatio(0.5f);
                behavior.setExpandedOffset(topGap);
                behavior.setMaxHeight(height);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            } catch (Throwable t) {
                AppErrorLogger.catchThrowable(TAG, "设置可展开弹窗失败", t);
            }
        });
    }

    /**
     * 高度跟着内容走，但最多不超过屏幕高度的 {@code maxRatio}，超出部分内部滚动。
     *
     * <p>适合选项数量不固定的弹窗（比如协议筛选 14 项）。
     * 这里不能直接设 fitToContents=false，否则只有两三个选项的弹窗也会撑到全屏。</p>
     */
    public static void capHeight(BottomSheetDialog dialog, float maxRatio) {
        if (dialog == null) return;
        dialog.setOnShowListener(d -> {
            try {
                FrameLayout sheet = sheetOf(dialog);
                if (sheet == null) return;
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setMaxHeight(sheetHeight(dialog, maxRatio));
            } catch (Throwable t) {
                AppErrorLogger.catchThrowable(TAG, "限制弹窗高度失败", t);
            }
        });
    }

    private static FrameLayout sheetOf(BottomSheetDialog dialog) {
        return dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
    }

    private static int sheetHeight(BottomSheetDialog dialog, float ratio) {
        DisplayMetrics dm = dialog.getContext().getResources().getDisplayMetrics();
        return Math.round(dm.heightPixels * ratio);
    }

    private static int statusBarHeight(FrameLayout sheet) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(sheet);
        if (insets == null) return 0;
        return insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
    }

    private static int dp(FrameLayout view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
