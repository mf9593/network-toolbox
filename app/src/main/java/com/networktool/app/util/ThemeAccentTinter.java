package com.networktool.app.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.networktool.app.R;

/**
 * 让界面上的「强调色」跟随用户选择的主题色。
 *
 * <p>布局 XML 里的 {@code ?attr/colorPrimary} / {@code ?attr/colorAccent} 只在主题创建时
 * 解析一次（本应用的主题色是运行时套用的），用户切换主题色后这些控件会一直停在默认色上，
 * 看起来就是“强调色没有跟随主题”。</p>
 *
 * <p>页面显示时遍历一次视图树，把颜色仍等于默认强调色的控件换成当前主题色。
 * 只有颜色完全等于默认值时才替换，不会破坏业务里自定义的颜色。</p>
 */
public final class ThemeAccentTinter {

    private ThemeAccentTinter() {
    }

    /** 遍历并修正某个根视图下的强调色 */
    public static void apply(View root) {
        if (root == null) return;
        try {
            Context context = root.getContext();
            if (context == null) return;
            int oldPrimary = ContextCompat.getColor(context, R.color.colorPrimary);
            int oldAccent = ContextCompat.getColor(context, R.color.colorAccent);
            int oldContainer = ContextCompat.getColor(context, R.color.colorPrimaryContainer);
            int newAccent = ThemeManager.getAccessibleThemeColor(context);
            int newContainer = ThemeManager.getChipSurfaceColor(context);
            walk(root, oldPrimary, oldAccent, oldContainer, newAccent, newContainer);
        } catch (Throwable ignored) {
        }
    }

    private static void walk(View view, int oldPrimary, int oldAccent, int oldContainer,
                             int newAccent, int newContainer) {
        if (view == null) return;
        try {
            tint(view, oldPrimary, oldAccent, oldContainer, newAccent, newContainer);
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), oldPrimary, oldAccent, oldContainer,
                        newAccent, newContainer);
            }
        }
    }

    private static void tint(View view, int oldPrimary, int oldAccent, int oldContainer,
                             int newAccent, int newContainer) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            ColorStateList mapped = remap(textView.getTextColors(), oldPrimary, oldAccent,
                    oldContainer, newAccent, newContainer);
            if (mapped != null) {
                textView.setTextColor(mapped);
            }
        }
        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            ColorStateList mapped = remap(imageView.getImageTintList(), oldPrimary, oldAccent,
                    oldContainer, newAccent, newContainer);
            if (mapped != null) {
                imageView.setImageTintList(mapped);
            }
        }
        if (view instanceof ProgressBar) {
            ProgressBar bar = (ProgressBar) view;
            ColorStateList progress = remap(bar.getProgressTintList(), oldPrimary, oldAccent,
                    oldContainer, newAccent, newContainer);
            if (progress != null) {
                bar.setProgressTintList(progress);
            }
            ColorStateList indeterminate = remap(bar.getIndeterminateTintList(), oldPrimary,
                    oldAccent, oldContainer, newAccent, newContainer);
            if (indeterminate != null) {
                bar.setIndeterminateTintList(indeterminate);
            }
        }
        ColorStateList backgroundTint = remap(view.getBackgroundTintList(), oldPrimary,
                oldAccent, oldContainer, newAccent, newContainer);
        if (backgroundTint != null) {
            view.setBackgroundTintList(backgroundTint);
        }
    }

    /**
     * 命中默认强调色时换成主题色，否则返回 null（表示不需要改动）。
     * 只处理状态单一的色值列表，避免破坏正常的状态色。
     */
    private static ColorStateList remap(ColorStateList source, int oldPrimary, int oldAccent,
                                        int oldContainer, int newAccent, int newContainer) {
        if (source == null || source.isStateful()) return null;
        int color = source.getDefaultColor();
        int mapped = color;
        if (color == oldPrimary || color == oldAccent) {
            mapped = withAlphaOf(color, newAccent);
        } else if (color == oldContainer) {
            mapped = withAlphaOf(color, newContainer);
        }
        if (mapped == color) return null;
        return ColorStateList.valueOf(mapped);
    }

    /** 保留原颜色的透明度，只替换 RGB */
    private static int withAlphaOf(int original, int replacement) {
        return (original & 0xFF000000) | (replacement & 0x00FFFFFF);
    }
}
