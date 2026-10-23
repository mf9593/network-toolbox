package com.networktool.app.activity.tool;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.gson.Gson;
import com.networktool.app.R;
import com.networktool.app.activity.settings.ClearBrowserCacheActivity;
import com.networktool.app.util.ThemeManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页测速Activity
 *
 * 功能说明：
 * - 使用WebView加载在线测速网站进行网络速度测试
 * - 支持切换多个测速站点
 * - 支持标题栏和下拉刷新
 * - 根据主题色动态调整页面样式
 *
 * 测速站点列表：
 * - 广东电信测速
 * - 南京航空航天大学
 * - 南京大学测速站
 * - 快点网速测试
 * - 中国科学技术大学测速网站
 * - 一键测速网
 * - 测速网
 *
 * 使用方式：
 * - 进入页面默认加载广东电信测速
 * - 点击右上角切换按钮选择其他站点
 * - 下拉刷新重新加载页面
 */
public class SpeedTestActivity extends AppCompatActivity {

    private static final String TAG = "SpeedTestActivity";

    /* 顶部工具栏 */
    private MaterialToolbar toolbar;

    /* 清理缓存按钮 */
    private ImageButton clearCacheButton;

    /* 切换测速网站按钮 */
    private ImageButton switchSiteButton;

    /* WebView组件，用于加载测速网页 */
    private WebView webView;

    /* 进度条，显示页面加载进度 */
    private ProgressBar progressBar;

    /* 下拉刷新布局 */
    private SwipeRefreshLayout refreshLayout;

    /* 测速站点列表（从 assets JSON 文件加载） */
    private List<SpeedTestSite> sites = new ArrayList<>();

    /* 当前选中的站点索引 */
    private int currentSiteIndex = 0;

    /* 用于接收清理缓存页面的返回结果 */
    private ActivityResultLauncher<Intent> clearCacheLauncher;

    /**
     * Activity创建时的初始化
     *
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speed_test);

        // 注册清理缓存页面的返回结果
        clearCacheLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 返回后重新加载页面
                    if (result.getResultCode() == RESULT_OK) {
                        webView.clearCache(true);
                        webView.reload();
                    }
                }
        );

        // 设置沉浸式状态栏
        setupStatusBar();

        // 设置工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("网络测速");
        }

        // 应用主题色到工具栏
        applyToolbarTheme();

        // 初始化视图组件
        initViews();

        // 配置WebView设置
        configureWebView();

        // 从 assets 加载测速站点列表
        loadSitesFromAssets();

        // 加载默认测速网页
        loadSpeedTest(0);
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupStatusBar() {
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        
        // 设置状态栏颜色为透明，让内容延伸到状态栏下方
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    /**
     * 应用主题色到工具栏
     */
    private void applyToolbarTheme() {
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        
        // 工具栏背景使用主题色
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        
        // 工具栏标题使用对比色
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        
        // 工具栏返回按钮图标使用对比色
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    /**
     * 初始化视图组件
     * 绑定布局中的各个控件
     */
    private void initViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        refreshLayout = findViewById(R.id.refreshLayout);
        switchSiteButton = findViewById(R.id.switchSiteButton);
        clearCacheButton = findViewById(R.id.clearCacheButton);

        // 清理缓存按钮点击事件
        clearCacheButton.setOnClickListener(v -> clearWebViewCache());

        // 切换测速网站按钮点击事件
        switchSiteButton.setOnClickListener(v -> showSiteSelectorDialog());

        // 配置下拉刷新
        refreshLayout.setColorSchemeColors(ThemeManager.getThemeColor());
        refreshLayout.setOnRefreshListener(() -> {
            // 清除缓存并重新加载
            webView.clearCache(true);
            webView.reload();
            // 延迟关闭刷新动画
            webView.postDelayed(() -> refreshLayout.setRefreshing(false), 1000);
        });
    }

    /**
     * 配置WebView设置
     * 启用JavaScript、缩放、DOM存储等功能
     */
    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        // 启用JavaScript支持（测速网站需要）
        settings.setJavaScriptEnabled(true);

        // 启用缩放功能
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false); // 隐藏缩放按钮

        // 启用DOM存储和数据库
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // 启用硬件加速
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 设置User-Agent（模拟移动浏览器）
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 11; Pixel 4 XL Build/RQ3A.210705.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/89.0.4389.105 Mobile Safari/537.36"
        );

        // 图片自适应
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 允许混合内容（HTTPS页面加载HTTP资源）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // 隐藏滚动条
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);

        // 设置WebViewClient处理页面加载事件
        webView.setWebViewClient(new SpeedTestWebViewClient());

        // 设置WebChromeClient处理进度变化
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // 更新进度条
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                // 获取网页标题，可以更新工具栏标题
                super.onReceivedTitle(view, title);
            }
        });
    }

    /**
     * 加载测速网页
     *
     * @param siteIndex 站点索引（0-6）
     */
    private void loadSpeedTest(int siteIndex) {
        currentSiteIndex = siteIndex;
        progressBar.setVisibility(View.VISIBLE);
        webView.loadUrl(sites.get(siteIndex).url);
    }

    /**
     * 显示测速站点选择对话框
     */
    private void showSiteSelectorDialog() {
        // 从 sites 列表提取名称数组
        String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            names[i] = sites.get(i).name;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择测速站点");
        builder.setSingleChoiceItems(names, currentSiteIndex, (dialog, which) -> {
            // 记录选择的站点
            currentSiteIndex = which;
        });
        builder.setPositiveButton("进入测速", (dialog, which) -> {
            // 加载选中的站点
            loadSpeedTest(currentSiteIndex);
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /**
     * WebViewClient内部类
     * 处理WebView的各种加载事件
     */
    private class SpeedTestWebViewClient extends WebViewClient {

        /**
         * 页面开始加载时的回调
         */
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
        }

        /**
         * 页面加载完成时的回调
         */
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);

            // 自动点击"知道了"按钮（针对广东电信测速页面）
            String jsCode = "(function() { var knowBtn = document.querySelector('.know'); if (knowBtn) knowBtn.click(); })();";
            view.postDelayed(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    view.evaluateJavascript(jsCode, null);
                }
            }, 500);
        }
        
        /**
         * SSL证书错误处理
         * 为了兼容某些证书有问题的网站，选择忽略错误继续加载
         */
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            // 忽略SSL证书错误，继续加载
            handler.proceed();
        }

        /**
         * HTTP错误处理
         */
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            // 处理HTTP错误，如412、404等
            if (errorResponse.getStatusCode() == 412) {
                // 清除缓存后重新加载
                webView.clearCache(true);
                webView.postDelayed(() -> webView.reload(), 1000);
            }
        }
    }

    /**
     * 处理选项菜单点击事件
     *
     * @param item 被点击的菜单项
     * @return 是否处理了事件
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // 点击返回按钮，关闭Activity
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Activity销毁时清理WebView资源
     * 防止内存泄漏
     */
    @Override
    protected void onDestroy() {
        // 清理WebView
        if (webView != null) {
            webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            webView.clearHistory();
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 清理 WebView 缓存
     * 跳转到统一的清理缓存页面
     */
    private void clearWebViewCache() {
        Intent intent = new Intent(this, ClearBrowserCacheActivity.class);
        clearCacheLauncher.launch(intent);
    }

    /**
     * 处理返回键
     * 如果WebView有历史记录，则返回上一页
     */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 从 assets/speed_test_sites.json 加载测速站点列表
     */
    private void loadSitesFromAssets() {
        try {
            InputStream is = getAssets().open("speed_test_sites.json");
            InputStreamReader reader = new InputStreamReader(is, "UTF-8");
            SiteList siteList = new Gson().fromJson(reader, SiteList.class);
            reader.close();
            is.close();

            if (siteList != null && siteList.sites != null) {
                sites = siteList.sites;
            }
        } catch (Exception e) {
            // 加载失败时使用空列表
            sites = new ArrayList<>();
        }
    }

    /**
     * 测速站点数据模型
     */
    private static class SpeedTestSite {
        String name;
        String url;
    }

    /**
     * JSON 解析用的站点列表容器
     */
    private static class SiteList {
        List<SpeedTestSite> sites;
    }
}
