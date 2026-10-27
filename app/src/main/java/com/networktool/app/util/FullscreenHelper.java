package com.networktool.app.util;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放器「真全屏」助手。
 *
 * <p>进入全屏时：把播放器容器从小窗口改成铺满整屏，隐藏同一父布局里的其它控件
 * 以及页面上的标题栏 / 标签栏，同时切横屏并用沉浸式标记隐藏状态栏、导航栏；
 * 退出时逐项还原。只做框架层的事（LayoutParams + systemUiVisibility），
 * 不依赖任何第三方库，ONVIF 预览页和 SADP 预览页共用。</p>
 */
public final class FullscreenHelper {

    private final Activity activity;
    private final View container;
    private final View[] chromeViews;

    private ViewGroup.LayoutParams savedLayoutParams;
    private final List<View> hiddenViews = new ArrayList<>();
    private final List<Integer> savedVisibilities = new ArrayList<>();
    private boolean fullscreen;

    /**
     * @param activity    页面
     * @param container   播放器容器（全屏时铺满整屏的那个 View）
     * @param chromeViews 全屏时要临时隐藏的页面控件（标题栏、标签栏等）
     */
    public FullscreenHelper(Activity activity, View container, View... chromeViews) {
        this.activity = activity;
        this.container = container;
        this.chromeViews = chromeViews;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    /**
     * 进入全屏。
     *
     * @return false 表示条件不足（容器为空），调用方可据此提示
     */
    public boolean enter() {
        if (fullscreen || container == null || activity == null) {
            return false;
        }
        hiddenViews.clear();
        savedVisibilities.clear();

        // 1) 容器所在父布局里的其它控件（表单、滚动区）全部让位
        ViewGroup parent = container.getParent() instanceof ViewGroup
                ? (ViewGroup) container.getParent() : null;
        if (parent != null) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child != container) {
                    hide(child);
                }
            }
        }

        // 2) 页面上的标题栏 / 标签栏 / 另一页内容也隐藏
        if (chromeViews != null) {
            for (View view : chromeViews) {
                hide(view);
            }
        }

        // 3) 容器铺满整屏（同时去掉原来的 margin）
        savedLayoutParams = container.getLayoutParams();
        container.setLayoutParams(newFillParams(parent));

        // 4) 横屏 + 沉浸
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        applyImmersive();
        fullscreen = true;
        return true;
    }

    /** 退出全屏，逐项还原 */
    public void exit() {
        if (!fullscreen) {
            return;
        }
        for (int i = 0; i < hiddenViews.size(); i++) {
            View view = hiddenViews.get(i);
            if (view != null) {
                view.setVisibility(savedVisibilities.get(i));
            }
        }
        hiddenViews.clear();
        savedVisibilities.clear();

        if (savedLayoutParams != null && container != null) {
            container.setLayoutParams(savedLayoutParams);
            savedLayoutParams = null;
        }

        if (activity != null) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            View decor = activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            // 状态栏颜色还回去，避免退出后顶部留一条透明带
            activity.getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(activity));
        }
        fullscreen = false;
    }

    /** 窗口焦点回来时重新贴一次沉浸标记，否则系统栏会自己冒出来 */
    public void refreshImmersive() {
        if (fullscreen) {
            applyImmersive();
        }
    }

    private void applyImmersive() {
        if (activity == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // 系统栏透明，播放器画面才能真正顶到屏幕边缘
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }

    private ViewGroup.LayoutParams newFillParams(ViewGroup parent) {
        if (parent instanceof LinearLayout) {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void hide(View view) {
        if (view == null) {
            return;
        }
        hiddenViews.add(view);
        savedVisibilities.add(view.getVisibility());
        view.setVisibility(View.GONE);
    }
}
