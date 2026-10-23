package com.networktool.app.activity.tool;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.activity.settings.ClearBrowserCacheActivity;
import com.networktool.app.adapter.TabAdapter;
import com.networktool.app.model.TabItem;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.TabManager;
import com.networktool.app.util.ThemeManager;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置浏览器Activity - VIA风格设计
 *
 * 特点：
 * - 标题栏可输入网址，回车跳转
 * - 默认首页是搜索框
 * - 网页标题显示在标题栏
 */
public class WebViewActivity extends AppCompatActivity {

    private static final String TAG = "WebViewActivity";

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ENABLE_DEBUG = "enable_debug";

    private LinearLayout urlBar;
    private View homePage;
    private LinearLayout bottomBar;
    private FrameLayout titleContainer;
    private LinearLayout urlInputRow;
    private GridLayout bookmarksGrid;
    private View bookmarkBaidu;
    private View bookmarkBing;
    private View bookmarkGoogle;
    private View bookmarkSogou;
    private EditText urlEditText;
    private EditText homeSearchEditText;
    private TextView pageTitle;
    private ImageButton navButton;
    private ImageButton refreshButton;
    private ImageButton copyButton;
    private ImageButton menuButton;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton homeButton;
    private ImageButton tabButton;
    private ImageButton bottomMenuButton;
    private ImageButton homeSearchButton;
    private ProgressBar progressBar;
    private ProgressBar loadingProgress;
    private WebView webView;

    // 标签页相关
    private TabManager tabManager;
    private Map<String, WebView> webViewMap = new HashMap<>();
    private String currentTabId = "";

    private String currentUrl = "";
    private String currentTitle = "";
    private boolean isLoading = false;
    private boolean isHomePage = true;
    private boolean enableDebug = false;

    // 用于接收清理缓存页面的返回结果
    private ActivityResultLauncher<Intent> clearCacheLauncher;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);
        getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));

        // 注册清理缓存页面的返回结果
        clearCacheLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 返回后刷新 WebView
                    if (result.getResultCode() == RESULT_OK) {
                        webView.reload();
                    }
                }
        );

        // 初始化标签页管理器
        tabManager = new TabManager();
        tabManager.setOnTabChangeListener(new TabManager.OnTabChangeListener() {
            @Override
            public void onTabListChanged() {
                updateTabButton();
            }

            @Override
            public void onCurrentTabChanged(int tabIndex) {
                if (tabIndex >= 0) {
                    TabItem tab = tabManager.getTab(tabIndex);
                    if (tab != null) {
                        switchToTab(tab.getTabId());
                    }
                }
            }
        });

        initViews();
        setupListeners();

        // 应用主题颜色
        applyThemeColors();

        // 检查是否有传入URL
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        enableDebug = getIntent().getBooleanExtra(EXTRA_ENABLE_DEBUG, false);

        if (enableDebug) {
            // 网页调试模式，直接打开百度
            TabItem tab = tabManager.createTab("https://www.baidu.com", "百度");
            currentTabId = tab.getTabId();
            showWebView();
            loadUrl("https://www.baidu.com");
        } else if (!TextUtils.isEmpty(url)) {
            TabItem tab = tabManager.createTab(url, title != null ? title : "");
            currentTabId = tab.getTabId();
            showWebView();
            loadUrl(url);
            if (!TextUtils.isEmpty(title)) {
                urlEditText.setText(title);
            }
        } else {
            // 创建一个默认标签页
            tabManager.createTab(null, "新标签页");
            showHomePage();
        }
        
    }

    private void initViews() {
        urlBar = findViewById(R.id.urlBar);
        homePage = findViewById(R.id.homePage);
        bottomBar = findViewById(R.id.bottomBar);
        titleContainer = findViewById(R.id.titleContainer);
        urlInputRow = findViewById(R.id.urlInputRow);
        bookmarksGrid = findViewById(R.id.bookmarksGrid);
        bookmarkBaidu = findViewById(R.id.bookmarkBaidu);
        bookmarkBing = findViewById(R.id.bookmarkBing);
        bookmarkGoogle = findViewById(R.id.bookmarkGoogle);
        bookmarkSogou = findViewById(R.id.bookmarkSogou);
        urlEditText = findViewById(R.id.urlEditText);
        homeSearchEditText = findViewById(R.id.homeSearchEditText);
        pageTitle = findViewById(R.id.pageTitle);
        navButton = findViewById(R.id.navButton);
        refreshButton = findViewById(R.id.refreshButton);
        copyButton = findViewById(R.id.copyButton);
        menuButton = findViewById(R.id.menuButton);
        backButton = findViewById(R.id.backButton);
        forwardButton = findViewById(R.id.forwardButton);
        homeButton = findViewById(R.id.homeButton);
        tabButton = findViewById(R.id.tabButton);
        bottomMenuButton = findViewById(R.id.bottomMenuButton);
        homeSearchButton = findViewById(R.id.homeSearchButton);
        progressBar = findViewById(R.id.progressBar);
        loadingProgress = findViewById(R.id.loadingProgress);
        webView = findViewById(R.id.webView);
    }

    /**
     * 应用主题颜色
     */
    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        homeSearchButton.setImageTintList(android.content.res.ColorStateList.valueOf(accentColor));
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(themeColor));

        // 首页书签图标统一跟随主题色
        if (bookmarksGrid != null) {
            for (int i = 0; i < bookmarksGrid.getChildCount(); i++) {
                View item = bookmarksGrid.getChildAt(i);
                if (!(item instanceof ViewGroup)) continue;
                ViewGroup group = (ViewGroup) item;
                for (int j = 0; j < group.getChildCount(); j++) {
                    View child = group.getChildAt(j);
                    if (child instanceof android.widget.ImageView) {
                        ((android.widget.ImageView) child).setImageTintList(
                                android.content.res.ColorStateList.valueOf(accentColor));
                    }
                }
            }
        }
    }

    private void setupListeners() {
        // 导航按钮（返回/关闭）
        navButton.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            } else if (!isHomePage) {
                showHomePage();
            } else {
                finish();
            }
        });

        // 刷新按钮
        refreshButton.setOnClickListener(v -> {
            if (isHomePage) return;
            if (isLoading) {
                webView.stopLoading();
            } else {
                webView.reload();
            }
        });

        // 更多菜单
        menuButton.setOnClickListener(v -> showMoreOptions());
        
        // 底部菜单按钮
        bottomMenuButton.setOnClickListener(v -> showMoreOptions());

        // 地址栏回车键
        urlEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String url = urlEditText.getText().toString().trim();
                if (!TextUtils.isEmpty(url)) {
                    loadUrl(url);
                }
                return true;
            }
            return false;
        });

        // 首页搜索框回车键
        homeSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String query = homeSearchEditText.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    showWebView();
                    loadUrl(query);
                }
                return true;
            }
            return false;
        });

        // 首页搜索按钮
        homeSearchButton.setOnClickListener(v -> {
            String query = homeSearchEditText.getText().toString().trim();
            if (!TextUtils.isEmpty(query)) {
                showWebView();
                loadUrl(query);
            }
        });

        // 标题栏点击展开网址输入
        titleContainer.setOnClickListener(v -> expandUrlInput());

        // 复制按钮
        copyButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                copyToClipboard(url);
                Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
            }
        });

        // 网址输入框焦点变化 - 失焦时折叠
        urlEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                collapseUrlInput();
            }
        });

        // 底部工具栏
        backButton.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        forwardButton.setOnClickListener(v -> {
            if (webView.canGoForward()) {
                webView.goForward();
            }
        });

        homeButton.setOnClickListener(v -> showHomePage());
        
        tabButton.setOnClickListener(v -> showTabList());
        
        // 书签点击事件
        bookmarkBaidu.setOnClickListener(v -> loadBookmark("https://www.baidu.com"));
        bookmarkBing.setOnClickListener(v -> loadBookmark("https://www.bing.com"));
        bookmarkGoogle.setOnClickListener(v -> loadBookmark("https://www.google.com"));
        bookmarkSogou.setOnClickListener(v -> loadBookmark("https://www.sogou.com"));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        // WebView 将在需要时动态创建
    }

    /**
     * 为指定标签页创建或获取 WebView
     */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView getOrCreateWebView(String tabId) {
        if (webViewMap.containsKey(tabId)) {
            return webViewMap.get(tabId);
        }

        WebView newWebView = new WebView(this);
        webViewMap.put(tabId, newWebView);

        WebSettings settings = newWebView.getSettings();

        // 基本设置
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 缩放设置
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 用户代理
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.105 Mobile Safari/537.36");

        // 其他设置
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(true);

        // 混合内容模式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        newWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                } else {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception e) {
                        AppErrorLogger.logError(TAG, "无法打开链接: " + url, e);
                        String host = Uri.parse(url).getHost();
                        String msg = host != null ? "未安装「" + host + "」应用，无法打开此链接" : "未安装对应应用，无法打开此链接";
                        new MaterialAlertDialogBuilder(WebViewActivity.this)
                                .setTitle("无法打开链接")
                                .setMessage(msg + "\n" + url)
                                .setPositiveButton("确定", null)
                                .show();
                    }
                    return true;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                
                // 更新当前标签页状态
                int tabIndex = getTabIndexById(tabId);
                if (tabIndex >= 0) {
                    tabManager.updateTabLoadingState(tabIndex, true, 0);
                }
                
                isLoading = true;
                currentUrl = url;
                urlEditText.setText(url);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
                loadingProgress.setVisibility(View.VISIBLE);
                updateRefreshButton();
                updateNavigationButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isLoading = false;
                progressBar.setVisibility(View.GONE);
                loadingProgress.setVisibility(View.GONE);
                currentTitle = view.getTitle();
                
                // 显示网页标题
                if (!TextUtils.isEmpty(currentTitle)) {
                    pageTitle.setText(currentTitle);
                    urlEditText.setText(currentTitle);
                    
                    // 更新标签页标题
                    int tabIndex = getTabIndexById(tabId);
                    if (tabIndex >= 0) {
                        tabManager.updateTab(tabIndex, url, currentTitle);
                    }
                }
                updateRefreshButton();
                updateNavigationButtons();

                // 如果是调试模式，注入eruda
                if (enableDebug) {
                    injectEruda(view);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    Toast.makeText(WebViewActivity.this, "页面加载失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                String message = "SSL证书错误: " + (error != null ? error.toString() : "未知错误");
                new MaterialAlertDialogBuilder(WebViewActivity.this)
                        .setTitle("安全警告")
                        .setMessage(message + "\n\n是否继续加载？")
                        .setPositiveButton("继续", (dialog, which) -> handler.proceed())
                        .setNegativeButton("取消", (dialog, which) -> handler.cancel())
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (errorResponse.getStatusCode() == 404 && request.isForMainFrame()) {
                    Toast.makeText(WebViewActivity.this, "页面不存在 (404)", Toast.LENGTH_SHORT).show();
                }
            }
        });

        newWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
                
                // 更新标签页进度
                int tabIndex = getTabIndexById(tabId);
                if (tabIndex >= 0) {
                    tabManager.updateTabLoadingState(tabIndex, newProgress < 100, newProgress);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                currentTitle = title;
                if (!TextUtils.isEmpty(title) && !isHomePage) {
                    urlEditText.setText(title);
                    
                    // 更新标签页标题
                    int tabIndex = getTabIndexById(tabId);
                    if (tabIndex >= 0) {
                        tabManager.updateTab(tabIndex, null, title);
                    }
                }
            }
        });

        return newWebView;
    }

    /**
     * 展开网址输入
     */
    private void expandUrlInput() {
        if (!isHomePage) {
            urlEditText.setText(currentUrl);
        }
        pageTitle.setVisibility(View.GONE);
        urlInputRow.setVisibility(View.VISIBLE);
        urlEditText.requestFocus();
        urlEditText.setSelection(urlEditText.getText() != null ? urlEditText.getText().length() : 0);
    }

    /**
     * 折叠网址输入，显示标题
     */
    private void collapseUrlInput() {
        pageTitle.setVisibility(View.VISIBLE);
        urlInputRow.setVisibility(View.GONE);
    }

    /**
     * 显示首页
     */
    private void showHomePage() {
        isHomePage = true;
        homePage.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        pageTitle.setText("主页");
        collapseUrlInput();
        homeSearchEditText.setText("");
        homeSearchEditText.requestFocus();
    }

    /**
     * 显示WebView
     */
    private void showWebView() {
        isHomePage = false;
        homePage.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
        pageTitle.setVisibility(View.VISIBLE);
        urlInputRow.setVisibility(View.GONE);
    }

    /**
     * 切换到指定标签页
     */
    private void switchToTab(String tabId) {
        if (tabId.equals(currentTabId)) {
            return;
        }
        
        // 保存当前 WebView 状态
        if (!currentTabId.isEmpty() && webViewMap.containsKey(currentTabId)) {
            // 从布局中移除当前 WebView
            FrameLayout container = findViewById(R.id.webViewContainer);
            WebView oldWebView = webViewMap.get(currentTabId);
            if (oldWebView != null && oldWebView.getParent() != null) {
                container.removeView(oldWebView);
            }
        }
        
        currentTabId = tabId;
        
        // 获取或创建新标签页的 WebView
        WebView newWebView = getOrCreateWebView(tabId);
        
        // 将新 WebView 添加到布局中
        FrameLayout container = findViewById(R.id.webViewContainer);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        newWebView.setLayoutParams(params);
        
        if (newWebView.getParent() != null) {
            ((ViewGroup) newWebView.getParent()).removeView(newWebView);
        }
        container.addView(newWebView);
        
        // 替换引用
        webView = newWebView;
        
        // 更新 UI
        TabItem tab = tabManager.getCurrentTab();
        if (tab != null) {
            if (tab.getUrl() != null && !tab.getUrl().isEmpty()) {
                showWebView();
                urlEditText.setText(tab.getTitle() != null ? tab.getTitle() : tab.getUrl());
                pageTitle.setText(tab.getTitle() != null ? tab.getTitle() : "网页");
            } else {
                showHomePage();
            }
        }
        
        updateTabButton();
    }

    /**
     * 显示标签页列表
     */
    private void showTabList() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tab_list, null);
        androidx.recyclerview.widget.RecyclerView recyclerView = dialogView.findViewById(R.id.tabRecyclerView);
        com.google.android.material.button.MaterialButton newTabButton = dialogView.findViewById(R.id.newTabButton);
        com.google.android.material.button.MaterialButton closeAllButton = dialogView.findViewById(R.id.closeAllButton);
        
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(dialogView);
        final AppCompatDialog dialog = builder.create();
        
        // 使用数组包装 adapter 以解决 lambda 中的初始化问题
        final TabAdapter[] adapterRef = new TabAdapter[1];
        
        adapterRef[0] = new TabAdapter(new TabAdapter.OnTabClickListener() {
            @Override
            public void onTabClick(int position) {
                TabItem tab = tabManager.getTab(position);
                if (tab != null) {
                    tabManager.switchToTab(position);
                }
                dialog.dismiss();
            }

            @Override
            public void onTabClose(int position) {
                TabItem closedTab = tabManager.getTab(position);
                if (closedTab != null) {
                    String tabId = closedTab.getTabId();
                    WebView removedWv = webViewMap.remove(tabId);
                    if (removedWv != null) {
                        FrameLayout container = findViewById(R.id.webViewContainer);
                        if (removedWv.getParent() != null) {
                            container.removeView(removedWv);
                        }
                        removedWv.destroy();
                    }
                }
                int newIndex = tabManager.closeTab(position);
                if (tabManager.getTabCount() == 0) {
                    tabManager.createTab(null, "新标签页");
                    showHomePage();
                } else if (newIndex >= 0) {
                    TabItem tab = tabManager.getTab(newIndex);
                    if (tab != null) {
                        switchToTab(tab.getTabId());
                    }
                }
                adapterRef[0].updateTabs(tabManager.getAllTabs(), tabManager.getCurrentTabIndex());
            }
        });
        
        adapterRef[0].updateTabs(tabManager.getAllTabs(), tabManager.getCurrentTabIndex());
        
        androidx.recyclerview.widget.LinearLayoutManager layoutManager = new androidx.recyclerview.widget.LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapterRef[0]);
        
        // 新建标签页按钮
        newTabButton.setOnClickListener(v -> {
            tabManager.createTab(null, "新标签页");
            adapterRef[0].updateTabs(tabManager.getAllTabs(), tabManager.getCurrentTabIndex());
        });
        
        // 关闭全部按钮
        closeAllButton.setOnClickListener(v -> {
            for (WebView wv : webViewMap.values()) {
                if (wv != null) {
                    FrameLayout container = findViewById(R.id.webViewContainer);
                    if (wv.getParent() != null) {
                        container.removeView(wv);
                    }
                    wv.destroy();
                }
            }
            webViewMap.clear();
            tabManager.clearAllTabs();
            tabManager.createTab(null, "新标签页");
            adapterRef[0].updateTabs(tabManager.getAllTabs(), tabManager.getCurrentTabIndex());
            showHomePage();
            dialog.dismiss();
        });
        
        dialog.show();
    }

    /**
     * 更新标签页按钮显示
     */
    private void updateTabButton() {
        int count = tabManager.getTabCount();
        tabButton.setContentDescription("标签页 (" + count + "个)");
    }

    /**
     * 根据标签页ID获取索引
     */
    private int getTabIndexById(String tabId) {
        List<TabItem> tabs = tabManager.getAllTabs();
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getTabId().equals(tabId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 加载URL
     */
    private void loadUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }

        // 处理URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 检查是否是搜索关键词
            if (!url.contains(".") || url.contains(" ")) {
                // 使用百度搜索
                try {
                    url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(url, "UTF-8");
                } catch (Exception e) {
                    url = "https://www.baidu.com/s?wd=" + url;
                }
            } else {
                url = "https://" + url;
            }
        }

        currentUrl = url;
        webView.loadUrl(url);
        
        // 更新当前标签页URL
        int tabIndex = tabManager.getCurrentTabIndex();
        if (tabIndex >= 0) {
            tabManager.updateTab(tabIndex, url, null);
        }
    }

    /**
     * 加载书签
     */
    private void loadBookmark(String url) {
        showWebView();
        loadUrl(url);
    }

    /**
     * 注入eruda调试工具
     */
    private void injectEruda(WebView webView) {
        String erudaScript = "(function() {" +
                "if (typeof eruda !== 'undefined') { eruda.show(); return; }" +
                "var script = document.createElement('script');" +
                "script.src = 'https://cdn.jsdelivr.net/npm/eruda@3.0.1/eruda.min.js';" +
                "script.onload = function() { eruda.init({ defaults: { displaySize: 50 } }); eruda.show(); };" +
                "script.onerror = function() { console.error('eruda加载失败'); };" +
                "document.head.appendChild(script);" +
                "})();";
        webView.evaluateJavascript(erudaScript, null);
    }

    /**
     * 更新刷新按钮状态
     */
    private void updateRefreshButton() {
        if (isLoading) {
            refreshButton.setImageResource(R.drawable.ic_close);
        } else {
            refreshButton.setImageResource(R.drawable.ic_refresh);
        }
    }

    /**
     * 更新导航按钮状态
     */
    private void updateNavigationButtons() {
        backButton.setAlpha(webView.canGoBack() ? 1.0f : 0.3f);
        forwardButton.setAlpha(webView.canGoForward() ? 1.0f : 0.3f);
    }

    /**
     * 分享当前链接
     */
    private void shareCurrentUrl() {
        if (TextUtils.isEmpty(currentUrl)) {
            Toast.makeText(this, "没有可分享的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, currentUrl);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, currentTitle);
        startActivity(Intent.createChooser(shareIntent, "分享链接"));
    }

    /**
     * 显示更多选项
     */
    private void showMoreOptions() {
        String[] options;
        if (isHomePage) {
            options = new String[]{"在新浏览器打开首页", "清除缓存", "关于", "退出"};
        } else {
            options = new String[]{"刷新页面", "在新浏览器打开", "复制链接", "清除缓存", "分享", "关于", "退出"};
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("更多选项")
                .setItems(options, (dialog, which) -> {
                    if (isHomePage) {
                        switch (which) {
                            case 0:
                                openInExternalBrowser("https://www.baidu.com");
                                break;
                            case 1:
                                clearCache();
                                break;
                            case 2:
                                showAbout();
                                break;
                            case 3:
                                finish();
                                break;
                        }
                    } else {
                        switch (which) {
                            case 0:
                                webView.reload();
                                break;
                            case 1:
                                openInExternalBrowser(currentUrl);
                                break;
                            case 2:
                                copyToClipboard(currentUrl);
                                Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                clearCache();
                                break;
                            case 4:
                                shareCurrentUrl();
                                break;
                            case 5:
                                showAbout();
                                break;
                            case 6:
                                finish();
                                break;
                        }
                    }
                })
                .show();
    }

    private void openInExternalBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "无法打开外部浏览器: " + e.getMessage(), e);
            Toast.makeText(this, "无法打开外部浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearCache() {
        Intent intent = new Intent(this, ClearBrowserCacheActivity.class);
        clearCacheLauncher.launch(intent);
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("关于内置浏览器")
                .setMessage("内置浏览器\n简洁、快速、安全\n\n支持HTTP/HTTPS网页浏览\n支持搜索、分享等功能")
                .setPositiveButton("确定", null)
                .show();
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("URL", text);
        clipboard.setPrimaryClip(clip);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // 展开URL输入时先折叠
        if (urlInputRow.getVisibility() == View.VISIBLE) {
            collapseUrlInput();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else if (!isHomePage) {
            showHomePage();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理所有 WebView
        for (WebView wv : webViewMap.values()) {
            if (wv != null) {
                wv.destroy();
            }
        }
        webViewMap.clear();
    }
}
