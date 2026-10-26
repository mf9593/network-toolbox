package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 网页调试Activity
 *
 * 功能说明：
 * - 输入URL获取网页内容
 * - 使用不同方法匹配内容：match、gmatch、Jsoup、Xpath
 * - 显示匹配结果
 *
 * 支持的匹配方法：
 * - match：正则表达式匹配第一个结果
 * - gmatch：正则表达式全局匹配所有结果
 * - Jsoup：使用Jsoup选择器解析HTML
 * - Xpath：暂未实现（需要额外库支持）
 *
 * 使用方式：
 * - URL输入框填写目标网页链接
 * - 规则输入框填写匹配规则
 * - 选择匹配方法
 * - 点击解析按钮获取结果
 *
 * 示例：
 * - match: 规则 "<title>(.*?)</title>" 匹配网页标题
 * - gmatch: 规则 "<a href=\"(.*?)\">" 匹配所有链接
 * - Jsoup: 规则 "a[href]" 选择所有带href属性的a标签
 */
public class WebDebugActivity extends AppCompatActivity {

    /* 工具栏 */
    private Toolbar toolbar;

    /* URL输入框 */
    private TextInputEditText urlEditText;

    /* 匹配规则输入框 */
    private TextInputEditText ruleEditText;

    /* 匹配方法选择组 */
    private ChipGroup methodChipGroup;

    /* 解析按钮 */
    private MaterialButton parseButton;

    /* 复制按钮 */
    private MaterialButton copyButton;

    /* 结果展示卡片 */
    private MaterialCardView resultCard;

    /* 结果标题文本 */
    private TextView resultTitleText;

    /* 结果内容文本 */
    private TextView resultText;

    /* 进度条 */
    private ProgressBar progressBar;

    private static final String TAG = "WebDebugActivity";

    /* OkHttp客户端 */
    private OkHttpClient okHttpClient;

    /* 当前选中的匹配方法 */
    private String currentMethod = "match";

    /**
     * Activity创建时的初始化
     *
     * @param savedInstanceState 保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_debug);

        // 设置沉浸式状态栏
        setupStatusBar();

        // 初始化工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setupToolbar();

        // 初始化OkHttp客户端
        okHttpClient = new OkHttpClient();

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
            getSupportActionBar().setTitle("网页调试");
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
        ruleEditText = findViewById(R.id.ruleEditText);
        methodChipGroup = findViewById(R.id.methodChipGroup);
        // 请求方式 chip 跟随主题色（未选中透明，选中填主题色）
        ThemeManager.styleFilterChips(this, methodChipGroup);
        parseButton = findViewById(R.id.parseButton);
        copyButton = findViewById(R.id.copyButton);
        resultCard = findViewById(R.id.resultCard);
        resultTitleText = findViewById(R.id.resultTitleText);
        resultText = findViewById(R.id.resultText);
        progressBar = findViewById(R.id.progressBar);

        ThemeManager.setupResultCard(resultCard, this);
        copyButton.setEnabled(false);
    }

    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 匹配方法选择监听
        methodChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip chip = findViewById(checkedIds.get(0));
            if (chip != null) {
                currentMethod = chip.getText().toString();
            }
        });

        // 解析按钮点击事件
        parseButton.setOnClickListener(v -> parseWebPage());

        // 复制按钮点击事件
        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!TextUtils.isEmpty(text)) {
                copyToClipboard(text);
                showToast("复制成功");
            }
        });

        // 应用主题色到按钮
        applyThemeToButtons();
    }

    /**
     * 应用主题色到按钮
     */
    private void applyThemeToButtons() {
        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(parseButton);
        // copyButton 是 OutlinedButton，设置描边颜色
        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        copyButton.setStrokeColor(android.content.res.ColorStateList.valueOf(accentColor));
        copyButton.setTextColor(accentColor);
        copyButton.setIconTint(android.content.res.ColorStateList.valueOf(accentColor));
        resultTitleText.setTextColor(accentColor);
        resultText.setTextColor(ThemeManager.getOnSurfaceColor(this));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_web_debug, menu);
        MenuItem help = menu.findItem(R.id.action_help);
        if (help != null && help.getIcon() != null) {
            help.getIcon().setTint(ThemeManager.getOnSurfaceColor(this));
        }
        return true;
    }

    private void showHelpDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this)
                .inflate(R.layout.bottomsheet_web_debug_help, null);
        dialog.setContentView(view);

        View helpScroll = view.findViewById(R.id.helpScroll);
        MaterialButton closeButton = view.findViewById(R.id.closeHelpButton);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxHeight = Math.max(dp(160), Math.min(
                Math.round(screenHeight * 0.65f), screenHeight - dp(170)));
        helpScroll.getLayoutParams().height = maxHeight;

        closeButton.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 解析网页
     * 获取网页内容并根据规则匹配
     */
    private void parseWebPage() {
        // 获取输入内容
        String url = urlEditText.getText().toString().trim();
        String rule = ruleEditText.getText().toString().trim();

        // 验证URL
        if (TextUtils.isEmpty(url)) {
            showToast("请输入链接");
            return;
        }

        // 检查http协议头
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showToast("请输入正确的http链接");
            return;
        }

        // 验证规则
        if (TextUtils.isEmpty(rule)) {
            showToast("请输入匹配规则");
            return;
        }

        // 显示加载中
        showLoading(true);

        // 构建请求
        Request request = new Request.Builder()
                .url(url)
                .build();

        // 异步发送请求
        okHttpClient.newCall(request).enqueue(new Callback() {
            /**
             * 请求失败回调
             */
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    AppErrorLogger.logError(TAG, "连接源站失败: " + e.getMessage(), e);
                    showToast("连接源站失败: " + e.getMessage());
                });
            }

            /**
             * 请求成功回调
             */
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        AppErrorLogger.logError(TAG, "请求失败，响应码: " + response.code());
                        showToast("请求失败: " + response.code());
                    });
                    return;
                }

                // 读取网页内容
                String content = response.body().string();

                // 在主线程解析内容
                runOnUiThread(() -> {
                    showLoading(false);
                    parseContent(content, rule);
                });
            }
        });
    }

    /**
     * 解析网页内容
     * 根据选中的方法进行匹配
     *
     * @param content 网页内容
     * @param rule 匹配规则
     */
    private void parseContent(String content, String rule) {
        try {
            String result = "";
            boolean success = false;

            // 根据匹配方法执行不同逻辑
            switch (currentMethod) {
                case "match":
                    // 正则匹配第一个结果
                    result = parseWithMatch(content, rule);
                    success = !TextUtils.isEmpty(result);
                    break;

                case "gmatch":
                    // 正则全局匹配所有结果
                    result = parseWithGmatch(content, rule);
                    success = !TextUtils.isEmpty(result);
                    break;

                case "Jsoup":
                    // Jsoup选择器匹配
                    result = parseWithJsoup(content, rule);
                    success = !TextUtils.isEmpty(result);
                    break;

                case "Xpath":
                    // Xpath匹配
                    result = parseWithXpath(content, rule);
                    success = !TextUtils.isEmpty(result);
                    break;
            }

            // 显示结果
            if (success) {
                showResult("匹配结果", result);
            } else {
                showToast("匹配失败或结果为空");
            }

        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "解析网页内容异常: " + e.getMessage(), e);
            showToast("解析错误: " + e.getMessage());
        }
    }

    /**
     * 使用正则match匹配
     * 匹配第一个符合规则的结果
     *
     * @param content 网页内容
     * @param pattern 正则表达式
     * @return 匹配结果
     */
    private String parseWithMatch(String content, String pattern) {
        try {
            Pattern p = Pattern.compile(pattern, Pattern.DOTALL);
            Matcher m = p.matcher(content);
            if (m.find()) {
                // 如果有捕获组，返回第一个捕获组
                if (m.groupCount() > 0) {
                    return m.group(1);
                }
                // 否则返回整个匹配
                return m.group(0);
            }
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "正则表达式错误: " + e.getMessage(), e);
            showToast("正则表达式错误: " + e.getMessage());
        }
        return "";
    }

    /**
     * 使用正则gmatch全局匹配
     * 匹配所有符合规则的结果
     *
     * @param content 网页内容
     * @param pattern 正则表达式
     * @return 匹配结果列表
     */
    private String parseWithGmatch(String content, String pattern) {
        try {
            Pattern p = Pattern.compile(pattern, Pattern.DOTALL);
            Matcher m = p.matcher(content);
            StringBuilder sb = new StringBuilder();
            int count = 0;

            // 循环匹配所有结果
            while (m.find()) {
                count++;
                if (m.groupCount() > 0) {
                    sb.append(count).append(": ").append(m.group(1)).append("\n");
                } else {
                    sb.append(count).append(": ").append(m.group(0)).append("\n");
                }
            }

            return "共匹配到 " + count + " 个结果:\n\n" + sb.toString();
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "正则gmatch错误: " + e.getMessage(), e);
            showToast("正则表达式错误: " + e.getMessage());
        }
        return "";
    }

    /**
     * 使用Jsoup选择器匹配
     * 解析HTML并选择元素
     *
     * @param content 网页内容
     * @param selector Jsoup选择器
     * @return 匹配结果
     */
    private String parseWithJsoup(String content, String selector) {
        try {
            // 解析HTML文档
            org.jsoup.nodes.Document doc = Jsoup.parse(content);
            // 使用选择器选择元素
            Elements elements = doc.select(selector);

            if (!elements.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("共匹配到 ").append(elements.size()).append(" 个元素:\n\n");

                // 遍历所有匹配的元素
                for (Element el : elements) {
                    sb.append(el.outerHtml()).append("\n\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "Jsoup解析错误: " + e.getMessage(), e);
            showToast("Jsoup规则错误: " + e.getMessage());
        }
        return "";
    }

    /**
     * 使用XPath匹配
     * 解析HTML/XML并使用XPath查询
     *
     * @param content 网页内容
     * @param xpathExpr XPath表达式
     * @return 匹配结果
     */
    private String parseWithXpath(String content, String xpathExpr) {
        try {
            // 使用Jsoup解析HTML（自动处理HTML实体）
            org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(content);
            jsoupDoc.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
            jsoupDoc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
            jsoupDoc.outputSettings().charset("UTF-8");
            
            // 将Jsoup Document转换为W3C Document
            // 使用Jsoup的W3C DOM转换方法
            org.jsoup.helper.W3CDom w3cDom = new org.jsoup.helper.W3CDom();
            org.w3c.dom.Document w3cDoc = w3cDom.fromJsoup(jsoupDoc);
            
            // 创建XPath
            XPath xpath = XPathFactory.newInstance().newXPath();

            // 执行XPath查询；节点表达式失败时回落为字符串表达式
            Object result;
            try {
                result = xpath.evaluate(xpathExpr, w3cDoc, XPathConstants.NODESET);
            } catch (Exception nodeException) {
                result = xpath.evaluate(xpathExpr, w3cDoc, XPathConstants.STRING);
            }
            
            if (result instanceof NodeList) {
                NodeList nodes = (NodeList) result;
                if (nodes.getLength() > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("共匹配到 ").append(nodes.getLength()).append(" 个节点:\n\n");
                    
                    for (int i = 0; i < nodes.getLength(); i++) {
                        Node node = nodes.item(i);
                        if (node.getNodeType() == Node.TEXT_NODE) {
                            sb.append(i + 1).append(": ").append(node.getTextContent()).append("\n");
                        } else if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
                            sb.append(i + 1).append(": ").append(node.getNodeValue()).append("\n");
                        } else {
                            sb.append(i + 1).append(": ").append(node.getTextContent()).append("\n");
                        }
                    }
                    return sb.toString();
                }
            } else if (result instanceof String) {
                String strResult = (String) result;
                if (!strResult.isEmpty()) {
                    return strResult;
                }
            } else if (result instanceof Double) {
                Double numResult = (Double) result;
                return String.valueOf(numResult);
            } else if (result instanceof Boolean) {
                Boolean boolResult = (Boolean) result;
                return String.valueOf(boolResult);
            }
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "XPath解析错误: " + e.getMessage(), e);
            showToast("XPath解析错误: " + e.getMessage());
        }
        return "";
    }

    /**
     * 显示或隐藏加载状态
     *
     * @param show 是否显示
     */
    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        parseButton.setEnabled(!show);
    }

    /**
     * 显示匹配结果
     *
     * @param title 结果标题
     * @param content 结果内容
     */
    private void showResult(String title, String content) {
        resultCard.setVisibility(View.VISIBLE);
        resultTitleText.setText(title);
        resultText.setText(content);
        copyButton.setEnabled(true);
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
        if (item.getItemId() == R.id.action_help) {
            showHelpDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
