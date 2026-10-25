package com.networktool.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.networktool.app.activity.tool.WebViewActivity;

/**
 * 网址打开方式统一入口。
 *
 * 支持两个模式：
 * - {@link #MODE_INTERNAL}：应用内置浏览器
 * - {@link #MODE_EXTERNAL}：系统默认浏览器
 *
 * 在「设置 → 网址打开方式」中选择；所有打开网页的地方都应通过
 * {@link #open(Context, String, String)} 跳转，保证行为一致。
 */
public final class UrlOpenHelper {

    public static final String MODE_INTERNAL = "internal";
    public static final String MODE_EXTERNAL = "external";

    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_URL_OPEN_MODE = "url_open_mode";

    private UrlOpenHelper() {
    }

    public static String getMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_URL_OPEN_MODE, MODE_INTERNAL);
        if (!MODE_INTERNAL.equals(mode) && !MODE_EXTERNAL.equals(mode)) {
            return MODE_INTERNAL;
        }
        return mode;
    }

    public static void setMode(Context context, String mode) {
        if (!MODE_INTERNAL.equals(mode) && !MODE_EXTERNAL.equals(mode)) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_URL_OPEN_MODE, mode).apply();
    }

    public static String getModeLabel(Context context) {
        return MODE_INTERNAL.equals(getMode(context)) ? "内置浏览器" : "默认浏览器";
    }

    /**
     * 按用户设置打开网址。
     *
     * @param context Activity / 应用上下文
     * @param url     目标地址（http/https）
     * @param title   内置浏览器标题
     */
    public static void open(Context context, String url, String title) {
        if (context == null || url == null || url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            openExternal(context, url);
            return;
        }
        try {
            if (MODE_INTERNAL.equals(getMode(context))) {
                Intent intent = new Intent(context, WebViewActivity.class);
                intent.putExtra(WebViewActivity.EXTRA_URL, url);
                intent.putExtra(WebViewActivity.EXTRA_TITLE, title);
                context.startActivity(intent);
            } else {
                openExternal(context, url);
            }
        } catch (Exception ignored) {
            openExternal(context, url);
        }
    }

    /**
     * 始终使用系统默认浏览器打开。
     */
    public static void openExternal(Context context, String url) {
        try {
            if (url == null || url.isEmpty()) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (context.getPackageManager().resolveActivity(intent, 0) == null) {
                fallbackInternal(context, url);
                return;
            }
            context.startActivity(intent);
        } catch (Exception e) {
            AppErrorLogger.logError("UrlOpenHelper", "打开外部浏览器失败", e);
            fallbackInternal(context, url);
        }
    }

    private static void fallbackInternal(Context context, String url) {
        try {
            Intent intent = new Intent(context, WebViewActivity.class);
            intent.putExtra(WebViewActivity.EXTRA_URL, url);
            intent.putExtra(WebViewActivity.EXTRA_TITLE, "网页");
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}