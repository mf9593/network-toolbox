package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP调试Activity
 *
 * 功能说明：
 * - 发送HTTP请求（GET/POST）
 * - 自定义请求头和请求体
 * - 显示响应结果
 *
 * 使用说明：
 * - GET请求：填写URL后点击发送
 * - POST请求：填写URL、发送数据、请求头后点击发送
 * - 支持JSON格式的请求头和请求体
 *
 * 使用方式：
 * - URL输入框填写目标网址（必填）
 * - 发送数据框填写POST请求体（可选，填写后自动切换为POST）
 * - 请求头框填写JSON格式的请求头（可选）
 * - Cookie框填写Cookie字符串（可选）
 *
 * 示例：
 * - GET请求：只填URL，点击发送
 * - POST请求：填写URL、请求体（JSON）、请求头（可选），点击发送
 */
public class HttpDebugActivity extends AppCompatActivity {

    private static final String TAG = "HttpDebugActivity";

    /* 工具栏 */
    private Toolbar toolbar;

    /* URL输入框 */
    private TextInputEditText urlEditText;

    /* 请求头输入框 */
    private TextInputEditText headerEditText;

    /* 请求体输入框 */
    private TextInputEditText bodyEditText;

    /* Cookie输入框 */
    private TextInputEditText cookieEditText;

    /* 发送请求按钮 */
    private MaterialButton sendButton;

    /* 复制结果按钮 */
    private MaterialButton copyButton;

    /* 结果展示卡片 */
    private MaterialCardView resultCard;

    /* 提示卡片标题/内容 */
    private TextView cardTitle;
    private TextView cardMessage;

    /* 结果标题/内容 */
    private TextView resultTitle;
    private TextView resultText;

    /* 进度条 */
    private ProgressBar progressBar;

    /* OkHttp客户端 */
    private OkHttpClient okHttpClient;

    /**
     * Activity创建时的初始化
     *
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_http_debug);

        // 设置沉浸式状态栏
        setupStatusBar();

        // 初始化工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setupToolbar();

        // 初始化OkHttp客户端
        okHttpClient = new OkHttpClient.Builder()
                .followRedirects(true)      // 允许重定向
                .followSslRedirects(true)   // 允许SSL重定向
                .build();

        // 初始化视图和监听器
        initViews();
        setupListeners();
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

    /**
     * 设置工具栏主题
     */
    private void setupToolbar() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("HTTP 调试");
        }
        
        // 应用主题色到工具栏
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
    }

    /**
     * 初始化视图组件
     * 绑定布局中的各个控件
     */
    private void initViews() {
        urlEditText = findViewById(R.id.urlEditText);
        headerEditText = findViewById(R.id.headerEditText);
        bodyEditText = findViewById(R.id.bodyEditText);
        cookieEditText = findViewById(R.id.cookieEditText);
        sendButton = findViewById(R.id.sendButton);
        copyButton = findViewById(R.id.copyButton);
        resultCard = findViewById(R.id.resultCard);
        cardTitle = findViewById(R.id.cardTitle);
        cardMessage = findViewById(R.id.cardMessage);
        resultTitle = findViewById(R.id.resultTitle);
        resultText = findViewById(R.id.resultText);
        progressBar = findViewById(R.id.progressBar);

        ThemeManager.setupResultCard(findViewById(R.id.hintCard), this);
        ThemeManager.setupResultCard(resultCard, this);
        copyButton.setEnabled(false);
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 发送按钮点击事件
        sendButton.setOnClickListener(v -> sendRequest());

        // 复制按钮点击事件
        copyButton.setOnClickListener(v -> {
            String result = resultText.getText().toString();
            if (TextUtils.isEmpty(result)) return;
            copyToClipboard(result);
            showToast("已复制");
        });
        
        // 应用主题色
        applyThemeColors();
    }
    
    /**
     * 应用主题色到控件
     */
    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        
        // 发送按钮使用主题色
        ThemeManager.applyPrimaryButton(sendButton);
        
        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        int onSurface = ThemeManager.getOnSurfaceColor(this);
        ThemeManager.applyPrimaryButton(sendButton);
        copyButton.setTextColor(accentColor);
        copyButton.setIconTint(android.content.res.ColorStateList.valueOf(accentColor));
        cardTitle.setTextColor(accentColor);
        cardMessage.setTextColor(onSurface);
        resultTitle.setTextColor(accentColor);
        resultText.setTextColor(onSurface);
    }

    /**
     * 发送HTTP请求
     * 根据请求体是否为空自动判断GET或POST请求
     */
    private void sendRequest() {
        // 获取输入内容
        String url = urlEditText.getText().toString().trim();
        String bodyStr = bodyEditText.getText().toString().trim();
        String headerStr = headerEditText.getText().toString().trim();

        // 验证URL
        if (TextUtils.isEmpty(url)) {
            showToast("请输入内容");
            return;
        }

        // 检查http协议头
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showToast("请输入http协议头");
            return;
        }

        // 显示加载中
        showLoading(true);
        copyButton.setEnabled(false);
        resultCard.setVisibility(View.GONE);

        try {
            // 构建请求
            Request.Builder requestBuilder = new Request.Builder().url(url);

            // 添加请求头（JSON格式）
            if (!TextUtils.isEmpty(headerStr)) {
                try {
                    JSONObject headerJson = new JSONObject(headerStr);
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = headerJson.getString(key);
                        requestBuilder.addHeader(key, value);
                    }
                } catch (Exception e) {
                    showToast("请求头格式错误，请使用JSON格式");
                    showLoading(false);
                    return;
                }
            }

            // 添加Cookie
            String cookie = cookieEditText.getText().toString().trim();
            if (!TextUtils.isEmpty(cookie)) {
                requestBuilder.addHeader("Cookie", cookie);
            }

            // 判断是GET还是POST请求
            boolean isPost = !TextUtils.isEmpty(bodyStr);

            if (isPost) {
                // POST请求
                try {
                    // 尝试解析为JSON
                    JSONObject bodyJson = new JSONObject(bodyStr);
                    MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
                    RequestBody requestBody = RequestBody.create(bodyJson.toString(), mediaType);
                    requestBuilder.post(requestBody);
                } catch (Exception e) {
                    // 如果不是JSON，直接作为文本发送
                    MediaType mediaType = MediaType.parse("text/plain; charset=utf-8");
                    RequestBody requestBody = RequestBody.create(bodyStr, mediaType);
                    requestBuilder.post(requestBody);
                }
            } else {
                // GET请求
                requestBuilder.get();
            }

            // 构建请求对象
            Request request = requestBuilder.build();

            // 异步发送请求
            okHttpClient.newCall(request).enqueue(new Callback() {
                /**
                 * 请求失败回调
                 */
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        AppErrorLogger.logError(TAG, "HTTP请求失败: " + e.getMessage(), e);
                        showToast("请求失败");
                        resultTitle.setText("请求失败");
                        resultText.setText("错误: " + e.getMessage());
                        copyButton.setEnabled(true);
                        resultCard.setVisibility(View.VISIBLE);
                    });
                }

                /**
                 * 请求成功回调
                 */
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    // 读取响应内容
                    String responseBody = response.body() != null ? response.body().string() : "";
                    String headers = response.headers().toString();
                    int code = response.code();

                    // 在主线程更新UI
                    runOnUiThread(() -> {
                        showLoading(false);
                        resultCard.setVisibility(View.VISIBLE);
                        copyButton.setEnabled(true);
                        if (code >= 200 && code < 300) {
                            resultTitle.setText("请求完成 · HTTP " + code);
                            resultText.setText("响应头：" + headers + "\n\n" + responseBody);
                        } else {
                            resultTitle.setText("请求失败 · HTTP " + code);
                            resultText.setText("响应头：" + headers + "\n\n" + responseBody);
                        }
                    });
                }
            });

        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "HTTP请求错误: " + e.getMessage(), e);
            showLoading(false);
            showToast("请求错误: " + e.getMessage());
        }
    }

    /**
     * 显示或隐藏加载状态
     *
     * @param show 是否显示
     */
    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(!show);
    }

    /**
     * 复制文本到剪贴板
     *
     * @param text 要复制的文本
     */
    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("text", text);
        clipboard.setPrimaryClip(clip);
    }

    /**
     * 显示Toast提示
     *
     * @param message 提示消息
     */
    private void showToast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 处理选项菜单点击事件
     *
     * @param item 被点击的菜单项
     * @return 是否处理了事件
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
