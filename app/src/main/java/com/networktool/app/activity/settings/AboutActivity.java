package com.networktool.app.activity.settings;

import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.BuildConfig;
import com.networktool.app.NetworkToolApplication;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.UrlOpenHelper;
import com.networktool.app.util.UpdateChecker;

public class AboutActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        setupStatusBar();
        setupToolbar();
        initViews();
    }

    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("关于软件");
        }

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
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
        String version = BuildConfig.VERSION_NAME;
        if (version == null || version.isEmpty()) version = "1.0";

        TextView appVersionText = findViewById(R.id.appVersionText);
        appVersionText.setText("版本 " + version + " (" + BuildConfig.VERSION_CODE + ")");

        TextView descriptionText = findViewById(R.id.descriptionText);
        descriptionText.setText("网络工具箱是一款面向 Android 平台的网络调试与诊断工具，专为网络工程师、开发者和技术爱好者打造。\n\n应用提供轻量、高效、无需 Root 的本地化网络分析体验，覆盖从物理层到应用层的常见网络协议调试需求。\n\n核心功能涵盖网络诊断、WiFi 数据管理、摄像头协议调试、局域网通信及设备扫描等场景。\n\n所有数据仅在本地处理，不会上传至任何第三方服务器，充分保障用户隐私安全。");

        // 开源地址 - 点击跳转内置浏览器
        TextView openSourceUrlText = findViewById(R.id.openSourceUrlText);
        String repoUrl = UpdateChecker.getRepoUrl();
        if (repoUrl == null || repoUrl.isEmpty()) {
            openSourceUrlText.setText("GitHub 仓库尚未配置");
            openSourceUrlText.setEnabled(false);
        } else {
            openSourceUrlText.setText(repoUrl);
            openSourceUrlText.setMovementMethod(LinkMovementMethod.getInstance());
            openSourceUrlText.setOnClickListener(v -> openInWebView(repoUrl, "开源地址"));
        }

        // 开源组件与许可 - 直接展示实际使用的组件与版本
        TextView openSourceToolsText = findViewById(R.id.openSourceToolsText);
        openSourceToolsText.setText("本应用以 Apache License 2.0 开源，使用了以下开源组件：\n\n"
                + "• AndroidX（AppCompat、Core、ConstraintLayout、RecyclerView、"
                + "ViewPager2、SwipeRefreshLayout、CoordinatorLayout）— Apache-2.0\n"
                + "• Material Components 1.11.0 — Apache-2.0\n"
                + "• OkHttp 4.12.0（含 Okio 3.6.0）— Apache-2.0\n"
                + "• Gson 2.10.1 — Apache-2.0\n"
                + "• Jsoup 1.15.3 — MIT\n"
                + "• AndroidSVG 1.4 — Apache-2.0\n"
                + "• ZXing Core 3.5.2、ZXing Android Embedded 4.3.0 — Apache-2.0\n"
                + "• ExoPlayer 2.19.1（core / ui / rtsp）— Apache-2.0");

        TextView sponsorListText = findViewById(R.id.sponsorListText);
        sponsorListText.setText("感谢每一位使用和支持网络工具箱的用户");

        // 重置数据
        View resetButton = findViewById(R.id.resetDataButton);
        resetButton.setOnClickListener(v -> showResetDataDialog());
    }

    private void openInWebView(String url, String title) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            // 按用户设置的跳转方式打开（内置浏览器或默认浏览器）
            UrlOpenHelper.open(this, url, title);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void showResetDataDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("重置数据")
                .setMessage("将清除以下数据：\n• 用户协议\n• WiFi配置数据\n• 局域网聊天数据库\n• 应用设置\n\n是否继续？")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        NetworkToolApplication.getInstance().putSharedData("用户协议", null);
                        clearWifiData();
                        clearChatDatabase();
                        clearAppSettings();
                        Toast.makeText(this, "重置成功", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "重置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearWifiData() {
        getSharedPreferences("wifi_config", MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private void clearChatDatabase() {
        try {
            deleteDatabase("lan_chat.db");
            com.networktool.app.util.LanChatNotificationManager.getInstance(this)
                    .cancelNotification();
        } catch (Exception ignored) {
        }
    }

    private void clearAppSettings() {
        getSharedPreferences("lan_chat", MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
