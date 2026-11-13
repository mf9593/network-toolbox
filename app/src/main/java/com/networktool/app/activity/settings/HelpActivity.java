package com.networktool.app.activity.settings;

import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.networktool.app.R;
import com.networktool.app.util.MarkdownRenderer;
import com.networktool.app.util.ThemeManager;

/**
 * 使用帮助Activity
 */
public class HelpActivity extends AppCompatActivity {

    private WebView webView;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        // 设置工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("使用帮助");
        }

        // 应用主题色到工具栏
        applyToolbarTheme();

        setupStatusBar();

        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        // 章节折叠用的是 HTML5 原生 <details>，不需要脚本；
        // 但「点目录跳到对应章节并自动展开」需要一小段脚本把 details.open 置 true，
        // 所以这里开 JS。内容是 assets 里的本地 help.md，不加载任何外部页面，
        // 同时关掉文件协议访问，避免脚本能读到本地文件。
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);

        // 从 assets 读取 MD 文件并渲染
        String htmlContent = MarkdownRenderer.renderMarkdown(this, "help.md");
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
    }

    /**
     * 应用主题色到工具栏
     */
    private void applyToolbarTheme() {
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupStatusBar() {
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
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
