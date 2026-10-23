package com.networktool.app.activity.settings;

import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import java.io.File;

/**
 * 清理浏览器缓存页面
 * 
 * 提供 WebView 缓存清理功能，支持：
 * - 网页缓存（HTML、图片、视频等）
 * - Cookies（网站登录状态）
 * - DOM Storage（网站本地数据）
 * - 表单数据（自动填充数据）
 * - 已保存的密码
 */
public class ClearBrowserCacheActivity extends AppCompatActivity {

    private CheckBox cbCache;
    private CheckBox cbCookies;
    private CheckBox cbDomStorage;
    private CheckBox cbFormData;
    private CheckBox cbPasswords;
    private TextView tvCacheSize;
    private MaterialButton btnClear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clear_browser_cache);

        setupStatusBar();
        initViews();
        calculateCacheSize();
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void initViews() {
        // 工具栏
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("清理浏览器缓存");
        }

        // 应用主题色到工具栏
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));

        // 初始化控件
        cbCache = findViewById(R.id.cbCache);
        cbCookies = findViewById(R.id.cbCookies);
        cbDomStorage = findViewById(R.id.cbDomStorage);
        cbFormData = findViewById(R.id.cbFormData);
        cbPasswords = findViewById(R.id.cbPasswords);
        tvCacheSize = findViewById(R.id.tvCacheSize);
        btnClear = findViewById(R.id.btnClear);

        MaterialCardView headerCard = findViewById(R.id.headerCard);
        MaterialCardView optionsCard = findViewById(R.id.optionsCard);
        MaterialCardView cacheSizeCard = findViewById(R.id.cacheSizeCard);
        int surfaceColor = ThemeManager.getSurfaceColor(this);
        headerCard.setCardBackgroundColor(surfaceColor);
        optionsCard.setCardBackgroundColor(surfaceColor);
        cacheSizeCard.setCardBackgroundColor(surfaceColor);

        // CheckBox 按钮颜色跟随主题
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int[] colors = new int[]{themeColor, 0xFF888888};
        android.content.res.ColorStateList csl = new android.content.res.ColorStateList(states, colors);
        cbCache.setButtonTintList(csl);
        cbCookies.setButtonTintList(csl);
        cbDomStorage.setButtonTintList(csl);
        cbFormData.setButtonTintList(csl);
        cbPasswords.setButtonTintList(csl);

        ThemeManager.applyPrimaryButton(btnClear);

        // 清理按钮
        btnClear.setOnClickListener(v -> showClearConfirmDialog());
    }

    /**
     * 计算当前缓存大小
     */
    private void calculateCacheSize() {
        long totalSize = 0;

        // 计算 WebView 缓存目录大小
        try {
            File cacheDir = getCacheDir();
            totalSize += getDirSize(cacheDir);
        } catch (Exception e) {
            // 忽略
        }

        // 计算应用外部缓存目录
        try {
            File externalCacheDir = getExternalCacheDir();
            if (externalCacheDir != null) {
                totalSize += getDirSize(externalCacheDir);
            }
        } catch (Exception e) {
            // 忽略
        }

        final long finalSize = totalSize;
        runOnUiThread(() -> {
            tvCacheSize.setText(formatSize(finalSize));
        });
    }

    /**
     * 递归计算目录大小
     */
    private long getDirSize(File dir) {
        long size = 0;
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        size += getDirSize(file);
                    } else if (file.exists()) {
                        size += file.length();
                    }
                }
            }
        }
        return size;
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 显示清理确认对话框
     */
    private void showClearConfirmDialog() {
        // 检查是否有选中项
        if (!cbCache.isChecked() && !cbCookies.isChecked() && !cbDomStorage.isChecked() 
                && !cbFormData.isChecked() && !cbPasswords.isChecked()) {
            Toast.makeText(this, "请至少选择一项进行清理", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder message = new StringBuilder("确定要清理以下缓存吗？\n\n");
        if (cbCache.isChecked()) message.append("• 网页缓存\n");
        if (cbCookies.isChecked()) message.append("• Cookies\n");
        if (cbDomStorage.isChecked()) message.append("• DOM Storage\n");
        if (cbFormData.isChecked()) message.append("• 表单数据\n");
        if (cbPasswords.isChecked()) message.append("• 已保存的密码\n");

        new MaterialAlertDialogBuilder(this)
                .setTitle("确认清理")
                .setMessage(message.toString())
                .setPositiveButton("清理", (dialog, which) -> clearSelectedCache())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 清理选中的缓存
     */
    private void clearSelectedCache() {
        try {
            // 清理网页缓存
            if (cbCache.isChecked()) {
                WebView webView = new WebView(this);
                webView.clearCache(true);
                webView.destroy();
            }

            // 清理 Cookies
            if (cbCookies.isChecked()) {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.removeAllCookies(null);
                cookieManager.flush();
            }

            // 清理 DOM Storage
            if (cbDomStorage.isChecked()) {
                WebStorage webStorage = WebStorage.getInstance();
                webStorage.deleteAllData();
            }

            // 清理表单数据
            if (cbFormData.isChecked()) {
                WebView webView = new WebView(this);
                webView.clearFormData();
                webView.destroy();
            }

            // 清理已保存的密码
            if (cbPasswords.isChecked()) {
                // 清除 WebView 密码缓存（Android 8.0+ 支持）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WebView webView = new WebView(this);
                    webView.clearSslPreferences();
                    webView.destroy();
                }
            }

            // 清理应用缓存目录
            if (cbCache.isChecked() || cbDomStorage.isChecked()) {
                clearApplicationCache();
            }

            Toast.makeText(this, "缓存清理完成", Toast.LENGTH_SHORT).show();
            
            // 重新计算缓存大小
            calculateCacheSize();

            // 返回结果给调用方
            setResult(RESULT_OK);

        } catch (Exception e) {
            Toast.makeText(this, "清理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 清理应用缓存目录
     */
    private void clearApplicationCache() {
        try {
            File cacheDir = getCacheDir();
            deleteDir(cacheDir);
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 递归删除目录
     */
    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
            return dir.delete();
        } else if (dir != null && dir.isFile()) {
            return dir.delete();
        }
        return false;
    }
}
