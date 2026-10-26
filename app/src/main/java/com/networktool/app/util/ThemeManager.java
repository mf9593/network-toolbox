package com.networktool.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.networktool.app.NetworkToolApplication;
import com.networktool.app.R;

/**
 * 主题管理工具类
 *
 * 统一管理应用的主题色、深色模式和常用语义色。
 * 所有页面颜色应优先从这里获取，避免在 XML/Java 中硬编码。
 */
public class ThemeManager {

    /* SharedPreferences 存储键名 */
    private static final String PREF_NAME = "theme_prefs";
    private static final String PREF_KEY_THEME_COLOR = "theme_color";
    private static final String PREF_KEY_THEME_COLOR_DARK = "theme_color_dark";
    private static final String PREF_KEY_THEME_MODE = "theme_mode";

    /* 深色模式选项 */
    public static final int THEME_MODE_SYSTEM = 0;
    public static final int THEME_MODE_LIGHT = 1;
    public static final int THEME_MODE_DARK = 2;

    /* 默认主题色 - 经典绿 */
    public static final int DEFAULT_COLOR = 0xFF00DAC5;
    public static final int DEFAULT_COLOR_DARK = 0xFF00B5A1;

    /* 预设主题颜色数组 */
    public static final int[] PRESET_COLORS = {
            0xFF00DAC5, // 经典绿（默认）
            0xFF2196F3, // 蓝色
            0xFF005FB5, // 深蓝
            0xFF7943FC, // 紫色
            0xFFFE5722, // 红色
            0xFFFF8880, // 粉色
            0xFF8BC24A, // 绿色
            0xFF639D3B, // 深草绿
            0xFF00BCD4  // 青色
    };

    /* 预设主题颜色名称 */
    public static final String[] PRESET_COLOR_NAMES = {
            "经典绿",
            "蓝色",
            "深蓝",
            "紫色",
            "红色",
            "粉色",
            "绿色",
            "深草绿",
            "青色"
    };

    private static SharedPreferences prefs() {
        return NetworkToolApplication.getInstance()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取当前主题颜色
     */
    public static int getThemeColor() {
        return prefs().getInt(PREF_KEY_THEME_COLOR, DEFAULT_COLOR);
    }

    /**
     * 获取当前主题颜色的深色版本
     */
    public static int getThemeColorDark() {
        return prefs().getInt(PREF_KEY_THEME_COLOR_DARK, DEFAULT_COLOR_DARK);
    }

    /**
     * 设置主题颜色
     */
    public static void setThemeColor(int color) {
        int darkColor = darkenColor(color, 0.85f);
        prefs().edit()
                .putInt(PREF_KEY_THEME_COLOR, color)
                .putInt(PREF_KEY_THEME_COLOR_DARK, darkColor)
                .apply();
    }

    /**
     * 设置主题颜色（通过预设索引）
     */
    public static void setThemeColorByIndex(int index) {
        if (index >= 0 && index < PRESET_COLORS.length) {
            setThemeColor(PRESET_COLORS[index]);
        }
    }

    /**
     * 获取当前主题颜色的预设索引
     */
    public static int getCurrentColorIndex() {
        int currentColor = getThemeColor();
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == currentColor) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取深色模式设置
     */
    public static int getThemeMode() {
        return prefs().getInt(PREF_KEY_THEME_MODE, THEME_MODE_SYSTEM);
    }

    /**
     * 设置深色模式并立即应用
     */
    public static void setThemeMode(int mode) {
        int safeMode = (mode == THEME_MODE_LIGHT || mode == THEME_MODE_DARK)
                ? mode : THEME_MODE_SYSTEM;
        prefs().edit().putInt(PREF_KEY_THEME_MODE, safeMode).apply();
        applyThemeMode();
    }

    /**
     * 将保存的深色模式应用到 AppCompat
     */
    public static void applyThemeMode() {
        int nightMode;
        switch (getThemeMode()) {
            case THEME_MODE_LIGHT:
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case THEME_MODE_DARK:
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    /**
     * 获取深色模式文案
     */
    public static String getThemeModeLabel() {
        return getThemeModeLabel(getThemeMode());
    }

    public static String getThemeModeLabel(int mode) {
        switch (mode) {
            case THEME_MODE_LIGHT:
                return "浅色";
            case THEME_MODE_DARK:
                return "深色";
            default:
                return "跟随系统";
        }
    }

    /**
     * 底部导航栏颜色状态列表
     */
    public static ColorStateList getNavigationColorStateList(Context context) {
        int themeColor = getAccessibleThemeColor(context);
        int inactiveColor = getOnSurfaceVariantColor(context);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int[] colors = new int[]{
                themeColor,
                inactiveColor
        };
        return new ColorStateList(states, colors);
    }

    /**
     * 主题色 ColorStateList
     */
    public static ColorStateList getThemeColorStateList(Context context) {
        return ColorStateList.valueOf(getThemeColor());
    }

    /**
     * 使颜色变暗
     */
    public static int darkenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = hsv[2] * factor;
        return Color.HSVToColor(hsv);
    }

    /**
     * 使颜色变亮
     */
    public static int lightenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1.0f, hsv[2] / factor);
        return Color.HSVToColor(hsv);
    }

    /**
     * 获取对比色（黑或白）
     *
     * 使用 WCAG 相对亮度比较黑/白两种前景的实际对比度，避免深色主题下
     * 仍然返回深色文字，导致按钮文字看不清。
     */
    public static int getContrastColor(int backgroundColor) {
        double luminance = relativeLuminance(backgroundColor);
        double whiteContrast = 1.05 / (luminance + 0.05);
        double blackContrast = (luminance + 0.05) / 0.05;
        return whiteContrast >= blackContrast ? Color.WHITE : Color.BLACK;
    }

    /**
     * 应用主题色到填充按钮，并同步文字和图标对比色。
     */
    public static void applyPrimaryButton(com.google.android.material.button.MaterialButton button) {
        if (button == null) return;
        int color = getThemeColor();
        int contrast = getContrastColor(color);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        button.setTextColor(contrast);
        button.setIconTint(ColorStateList.valueOf(contrast));
    }

    /**
     * 获取柔和的主题背景色
     */
    public static int getSoftThemeBackground(int alpha) {
        return blendColorWithWhite(getThemeColor(), alpha);
    }

    /**
     * 将颜色与白色混合
     */
    public static int blendColorWithWhite(int color, int alpha) {
        return blendColorWithBase(color, 0xFFFFFFFF, alpha);
    }

    /**
     * 将颜色与任意底色混合
     */
    public static int blendColorWithBase(int color, int baseColor, int alpha) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int br = Color.red(baseColor);
        int bg = Color.green(baseColor);
        int bb = Color.blue(baseColor);
        float factor = alpha / 255.0f;
        int newR = Math.round(br + (r - br) * factor);
        int newG = Math.round(bg + (g - bg) * factor);
        int newB = Math.round(bb + (b - bb) * factor);
        return Color.rgb(
                Math.max(0, Math.min(255, newR)),
                Math.max(0, Math.min(255, newG)),
                Math.max(0, Math.min(255, newB))
        );
    }

    private static boolean isDarkTheme() {
        Configuration config = NetworkToolApplication.getInstance().getResources().getConfiguration();
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int getSurfaceBaseColor() {
        return isDarkTheme() ? 0xFF181C1F : 0xFFFFFFFF;
    }

    /**
     * 颜色值转十六进制字符串
     */
    public static String colorToHex(int color) {
        return "#" + Integer.toHexString(color).substring(2).toUpperCase();
    }

    /**
     * 是否为深色模式
     */
    public static boolean isDarkMode(Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 获取适合作为小图标/文字/选中态的主题色。
     * 浅色模式下适当加深，深色模式下适当提亮，保证在表面色上有足够对比度。
     */
    public static int getAccessibleThemeColor(Context context) {
        int color = getThemeColor();
        boolean dark = isDarkMode(context);
        for (int i = 0; i < 16; i++) {
            double luminance = relativeLuminance(color);
            if (!dark && luminance <= 0.20) break;
            if (dark && luminance >= 0.35) break;
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hsv[2] = dark
                    ? Math.max(0.12f, Math.min(1f, hsv[2] * 1.12f))
                    : Math.max(0.12f, hsv[2] * 0.82f);
            color = Color.HSVToColor(hsv);
        }
        return color;
    }

    /**
     * WCAG 相对亮度
     */
    private static double relativeLuminance(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /**
     * 获取适配深色模式的主题色
     */
    public static int getAdaptiveThemeColor(Context context) {
        int color = getThemeColor();
        if (isDarkMode(context)) {
            return lightenColor(color, 1.1f);
        }
        return color;
    }

    /**
     * 获取卡片/气泡背景色（90% 底色 + 10% 主题色）
     */
    public static int getSurfaceColor(Context context) {
        int base = isDarkMode(context) ? 0xFF181C1F : 0xFFFFFFFF;
        return blendColorWithBase(getThemeColor(), base, 25);
    }

    /**
     * 获取不掺入主题色的系统表面色，供二级页面状态栏使用
     */
    public static int getSurfaceBaseColor(Context context) {
        return isDarkMode(context) ? 0xFF181C1F : 0xFFFFFFFF;
    }

    /**
     * 获取 Chip 背景色（比 Surface 略深）
     */
    public static int getChipSurfaceColor(Context context) {
        int base = isDarkMode(context) ? 0xFF181C1F : 0xFFFFFFFF;
        return blendColorWithBase(getThemeColor(), base, 38);
    }

    /**
     * 获取次级容器色，用于页面分区、筛选栏背景
     */
    public static int getSurfaceContainerColor(Context context) {
        int base = isDarkMode(context) ? 0xFF181C1F : 0xFFFFFFFF;
        return blendColorWithBase(getThemeColor(), base, 14);
    }

    /**
     * 获取更明显的容器色
     */
    public static int getSurfaceContainerHighColor(Context context) {
        int base = isDarkMode(context) ? 0xFF181C1F : 0xFFFFFFFF;
        return blendColorWithBase(getThemeColor(), base, 24);
    }

    /**
     * 无参版本，自动适配深色模式
     */
    public static int getSurfaceColor() {
        return blendColorWithBase(getThemeColor(), getSurfaceBaseColor(), 25);
    }

    /**
     * 无参版本，自动适配深色模式
     */
    public static int getChipSurfaceColor() {
        return blendColorWithBase(getThemeColor(), getSurfaceBaseColor(), 38);
    }

    /**
     * 获取 onSurface 文本色
     */
    public static int getOnSurfaceColor(Context context) {
        return resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface,
                isDarkMode(context) ? 0xFFE6E9EC : 0xFF171A1C);
    }

    /**
     * 获取 onSurfaceVariant 次要文本色
     */
    public static int getOnSurfaceVariantColor(Context context) {
        return resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant,
                isDarkMode(context) ? 0xFFAAB4BE : 0xFF5B6470);
    }

    /**
     * 获取分割线颜色
     */
    public static int getOutlineVariantColor(Context context) {
        return resolveThemeColor(context, com.google.android.material.R.attr.colorOutlineVariant,
                isDarkMode(context) ? 0xFF3A4248 : 0xFFD8DEE5);
    }


    /**
     * 统一给「筛选型」ChipGroup 上色：未选中背景透明（只留一圈淡描边），
     * 选中时填充主题色。这样切换主题色后所有页面的 chip 都跟着变。
     */
    public static void styleFilterChips(Context context,
                                        com.google.android.material.chip.ChipGroup group) {
        if (context == null || group == null) return;
        try {
            int themeColor = getThemeColor();
            int contrastColor = getContrastColor(themeColor);
            int outline = getOutlineVariantColor(context);
            int uncheckedText = getOnSurfaceVariantColor(context);

            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
            };
            // 未选中：完全透明；选中：主题色
            android.content.res.ColorStateList background =
                    new android.content.res.ColorStateList(states,
                            new int[]{themeColor, android.graphics.Color.TRANSPARENT});
            android.content.res.ColorStateList text =
                    new android.content.res.ColorStateList(states,
                            new int[]{contrastColor, uncheckedText});
            android.content.res.ColorStateList stroke =
                    new android.content.res.ColorStateList(states,
                            new int[]{themeColor, outline});
            float density = context.getResources().getDisplayMetrics().density;

            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View child = group.getChildAt(i);
                if (child instanceof com.google.android.material.chip.Chip) {
                    com.google.android.material.chip.Chip chip =
                            (com.google.android.material.chip.Chip) child;
                    chip.setChipBackgroundColor(background);
                    chip.setTextColor(text);
                    chip.setChipStrokeColor(stroke);
                    chip.setChipStrokeWidth(density);
                    chip.setCheckedIconTint(android.content.res.ColorStateList.valueOf(contrastColor));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 统一给「快捷入口型」chip 上色：背景透明 + 淡描边 */
    public static void styleAssistChips(Context context,
                                        com.google.android.material.chip.ChipGroup group) {
        if (context == null || group == null) return;
        try {
            int outline = getOutlineVariantColor(context);
            int textColor = getOnSurfaceVariantColor(context);
            float density = context.getResources().getDisplayMetrics().density;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View child = group.getChildAt(i);
                if (child instanceof com.google.android.material.chip.Chip) {
                    com.google.android.material.chip.Chip chip =
                            (com.google.android.material.chip.Chip) child;
                    chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.TRANSPARENT));
                    chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(outline));
                    chip.setChipStrokeWidth(density);
                    chip.setTextColor(textColor);
                    chip.setChipIconTint(android.content.res.ColorStateList.valueOf(textColor));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析主题属性颜色
     */
    private static int resolveThemeColor(Context context, int attr, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) {
                    return ContextCompat.getColor(context, value.resourceId);
                }
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * 统一设置 MaterialCardView 背景色
     */
    public static void setupResultCard(com.google.android.material.card.MaterialCardView card, Context context) {
        if (card != null) {
            card.setCardBackgroundColor(getSurfaceColor(context));
        }
    }

    /**
     * 成功状态色
     */
    public static int getSuccessColor() {
        return 0xFF2E7D32;
    }

    /**
     * 成功状态色（深色模式使用更亮的绿色）
     */
    public static int getSuccessColor(Context context) {
        return isDarkMode(context) ? 0xFF81C784 : 0xFF2E7D32;
    }

    /**
     * 错误状态色
     */
    public static int getErrorColor() {
        return 0xFFC62828;
    }

    /**
     * 错误状态色（深色模式使用更亮的红色）
     */
    public static int getErrorColor(Context context) {
        return isDarkMode(context) ? 0xFFEF9A9A : 0xFFC62828;
    }
}
