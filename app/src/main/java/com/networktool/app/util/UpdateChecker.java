package com.networktool.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.networktool.app.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 通过 GitHub Releases API 检查更新。
 *
 * 仓库地址由 gradle.properties 的 github.owner/github.repo 提供，
 * 不包含任何用户隐私数据。
 */
public final class UpdateChecker {

    public static final class UpdateInfo {
        public final String version;
        public final String releaseUrl;
        public final String notes;
        public final boolean newer;

        UpdateInfo(String version, String releaseUrl, String notes, boolean newer) {
            this.version = version;
            this.releaseUrl = releaseUrl;
            this.notes = notes;
            this.newer = newer;
        }
    }

    public interface Callback {
        void onResult(UpdateInfo info);

        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern SAFE_PART = Pattern.compile("^[A-Za-z0-9_.-]+$");

    private UpdateChecker() {
    }

    public static boolean isConfigured() {
        return BuildConfig.GITHUB_OWNER != null
                && !BuildConfig.GITHUB_OWNER.trim().isEmpty()
                && BuildConfig.GITHUB_REPO != null
                && !BuildConfig.GITHUB_REPO.trim().isEmpty();
    }

    public static String getRepoUrl() {
        if (!isConfigured()) return null;
        return "https://github.com/" + BuildConfig.GITHUB_OWNER.trim()
                + "/" + BuildConfig.GITHUB_REPO.trim();
    }

    public static void check(Context context, Callback callback) {
        if (!isConfigured()) {
            callback.onError("尚未配置 GitHub 开源仓库地址");
            return;
        }

        final String owner = BuildConfig.GITHUB_OWNER.trim();
        final String repo = BuildConfig.GITHUB_REPO.trim();
        if (!SAFE_PART.matcher(owner).matches() || !SAFE_PART.matcher(repo).matches()) {
            callback.onError("GitHub 仓库地址配置无效");
            return;
        }

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.github.com/repos/" + owner + "/" + repo
                        + "/releases/latest");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "NetworkToolbox-Android");
                int code = connection.getResponseCode();
                if (code == 404) {
                    MAIN.post(() -> callback.onError("仓库还没有发布 Release"));
                    return;
                }
                if (code == 403 || code == 429) {
                    MAIN.post(() -> callback.onError("GitHub API 请求过于频繁，请稍后再试"));
                    return;
                }
                if (code != 200) {
                    MAIN.post(() -> callback.onError("检查更新失败：HTTP " + code));
                    return;
                }

                String body;
                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }
                    body = builder.toString();
                }

                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "");
                String version = tag.startsWith("v") || tag.startsWith("V")
                        ? tag.substring(1) : tag;
                String releaseUrl = json.optString("html_url", getRepoUrl());
                String notes = json.optString("body", "");
                boolean newer = compareVersion(version, BuildConfig.VERSION_NAME) > 0;
                final UpdateInfo info = new UpdateInfo(version, releaseUrl, notes, newer);
                MAIN.post(() -> callback.onResult(info));
            } catch (Exception e) {
                final String message = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                MAIN.post(() -> callback.onError("检查更新失败：" + message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "github-update-check").start();
    }

    /**
     * 比较语义化版本号，忽略 v 前缀和 -beta 等后缀。
     */
    public static int compareVersion(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return av > bv ? 1 : -1;
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        if (version == null) return new int[]{0};
        String clean = version.trim();
        int suffix = clean.indexOf('-');
        if (suffix >= 0) clean = clean.substring(0, suffix);
        String[] parts = clean.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                result[i] = 0;
            }
        }
        return result;
    }
}
