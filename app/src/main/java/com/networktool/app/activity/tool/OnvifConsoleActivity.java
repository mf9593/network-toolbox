package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.networktool.app.R;
import com.networktool.app.util.OnvifUtil;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ONVIF控制台Activity
 * 提供完整的ONVIF设备调试功能，包括设备信息查询、视频配置等
 */
public class OnvifConsoleActivity extends AppCompatActivity {

    private static final int COMMAND_REBOOT = 9;

    // UI组件
    private TextInputEditText ipEditText;
    private TextInputEditText usernameEditText;
    private TextInputEditText passwordEditText;
    private MaterialButton connectButton;
    private Spinner commandSpinner;
    private MaterialButton executeButton;
    private MaterialButton anonymousRebootButton;
    private TextInputLayout tokenInputLayout;
    private TextInputEditText tokenEditText;
    private RecyclerView resultRecyclerView;
    private ProgressBar progressBar;

    // 线程相关
    private ExecutorService executorService;
    private Handler mainHandler;

    // 设备连接信息
    private String currentServiceUrl;
    private String currentUsername;
    private String currentPassword;

    // 结果列表
    private List<ResultItem> resultList;
    private ResultAdapter resultAdapter;

    // 分辨率配置
    private List<String> mainStreamResolutions;
    private List<String> subStreamResolutions;

    // 命令列表
    private String[] commands;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onvif_console);
        getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));

        // 设置Toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("ONVIF调试");
        }

        // 初始化线程池和Handler
        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        setupSpinner();
    }

    /**
     * 初始化所有UI组件
     */
    private void initViews() {
        ipEditText = findViewById(R.id.ipEditText);
        usernameEditText = findViewById(R.id.usernameEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        connectButton = findViewById(R.id.connectButton);
        commandSpinner = findViewById(R.id.commandSpinner);
        executeButton = findViewById(R.id.executeButton);
        anonymousRebootButton = findViewById(R.id.anonymousRebootButton);
        tokenInputLayout = findViewById(R.id.tokenInputLayout);
        tokenEditText = findViewById(R.id.tokenEditText);
        resultRecyclerView = findViewById(R.id.resultRecyclerView);
        progressBar = findViewById(R.id.progressBar);

        resultList = new ArrayList<>();
        resultAdapter = new ResultAdapter();
        resultRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        resultRecyclerView.setAdapter(resultAdapter);

        // 设置按钮点击监听
        connectButton.setOnClickListener(v -> connectDevice());
        executeButton.setOnClickListener(v -> executeCommand());
        anonymousRebootButton.setOnClickListener(v -> showAnonymousRebootConfirm());

        // 应用主题色
        int themeColor = ThemeManager.getThemeColor();
        applyThemeToButtons(themeColor);
        MaterialCardView connectCard = findViewById(R.id.connectCard);
        MaterialCardView commandCard = findViewById(R.id.commandCard);
        ThemeManager.setupResultCard(connectCard, this);
        ThemeManager.setupResultCard(commandCard, this);
    }

    /**
     * 应用主题色到按钮
     */
    private void applyThemeToButtons(int themeColor) {
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        ColorStateList colorStateList = ColorStateList.valueOf(themeColor);
        connectButton.setBackgroundTintList(colorStateList);
        connectButton.setTextColor(contrastColor);
        connectButton.setIconTint(ColorStateList.valueOf(contrastColor));
        executeButton.setBackgroundTintList(colorStateList);
        executeButton.setTextColor(contrastColor);
        executeButton.setIconTint(ColorStateList.valueOf(contrastColor));
        anonymousRebootButton.setTextColor(ThemeManager.getAccessibleThemeColor(this));
    }

    /**
     * 设置Spinner（命令下拉框）
     */
    private void setupSpinner() {
        commands = new String[]{
                "获取设备信息",
                "获取设备能力",
                "获取设备服务",
                "获取设备时间",
                "获取配置Profile列表",
                "获取编码参数范围",
                "获取所有编码配置",
                "获取视频流地址",
                "获取视频源配置",
                "摄像头重启（需登录）"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, commands);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        commandSpinner.setAdapter(adapter);

        // 监听命令选择变化，控制Token输入框的显示/隐藏
        commandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateTokenInputVisibility(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 初始化分辨率列表
        initResolutions();
    }

    /**
     * 初始化分辨率配置
     */
    private void initResolutions() {
        mainStreamResolutions = Arrays.asList(
                "1920x1080", "1280x720", "720x576", "720x480", "640x480", "352x288", "320x240"
        );
        subStreamResolutions = Arrays.asList(
                "720x576", "720x480", "640x480", "352x288", "320x240", "176x144"
        );
    }

    /**
     * 更新Token输入框的可见性
     * 部分命令需要输入Profile Token
     */
    private void updateTokenInputVisibility(int commandIndex) {
        // 编码参数范围和视频流地址需要 Profile Token；获取全部编码配置不需要
        boolean needToken = commandIndex == 5 || commandIndex == 7;
        tokenInputLayout.setVisibility(needToken ? View.VISIBLE : View.GONE);

        if (needToken && tokenEditText.getText() != null
                && tokenEditText.getText().toString().trim().isEmpty()) {
            tokenEditText.setText("Profile_1");
        }
    }

    /**
     * 连接设备
     */
    private void connectDevice() {
        String ip = ipEditText.getText() == null ? "" : ipEditText.getText().toString().trim();
        String username = usernameEditText.getText() == null ? "" : usernameEditText.getText().toString().trim();
        String password = passwordEditText.getText() == null ? "" : passwordEditText.getText().toString();

        if (ip.isEmpty()) {
            showToast("请输入IP地址");
            return;
        }

        // 支持 IP、IP:端口 或完整 device_service 地址
        currentServiceUrl = buildDeviceServiceUrl(ip);
        currentUsername = username;
        currentPassword = password;

        showToast("设备连接信息已设置");
        commandSpinner.setSelection(0);
        executeCommand();
    }

    /**
     * 把用户输入的 IP / IP:端口 转为 ONVIF Device Service 地址。
     */
    private String buildDeviceServiceUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (!value.contains("/onvif/") && !value.endsWith("/onvif/device_service")) {
                while (value.endsWith("/")) {
                    value = value.substring(0, value.length() - 1);
                }
                value += "/onvif/device_service";
            }
            return value;
        }
        // 未写协议时默认 HTTP
        if (!value.contains("/")) {
            value += "/onvif/device_service";
        }
        return "http://" + value;
    }

    /**
     * 执行命令
     */
    private void executeCommand() {
        if (currentServiceUrl == null || currentServiceUrl.isEmpty()) {
            showToast("请先连接设备");
            return;
        }

        int commandIndex = commandSpinner.getSelectedItemPosition();
        if (commandIndex == COMMAND_REBOOT) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("重启摄像头")
                    .setMessage("确定要重启此设备吗？\n\n设备会暂时断开连接，并可能丢失未保存的配置。")
                    .setPositiveButton("重启", (dialog, which) -> runSelectedCommand())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        runSelectedCommand();
    }

    private void runSelectedCommand() {
        if (currentServiceUrl == null || currentServiceUrl.isEmpty()) {
            showToast("请先连接设备");
            return;
        }
        showLoading(true);
        int commandIndex = commandSpinner.getSelectedItemPosition();
        executorService.execute(() -> executeOnvifCommand(commandIndex));
    }

    /**
     * 执行ONVIF命令
     */
    private void executeOnvifCommand(int commandIndex) {
        String response = null;
        String resultText = "";
        ResultItem resultItem = null;

        try {
            switch (commandIndex) {
                case 0: // 获取设备信息
                    response = OnvifUtil.getDeviceInformation(currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "请求失败，请检查 IP、端口和认证信息"
                            : OnvifUtil.parseDeviceInformation(response);
                    resultItem = new ResultItem("获取设备信息", resultText);
                    break;

                case 1: // 获取设备能力
                    response = OnvifUtil.getCapabilities(currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "请求失败，请检查认证信息"
                            : parseCapabilities(response);
                    resultItem = new ResultItem("获取设备能力", resultText);
                    break;

                case 2: // 获取设备服务
                    response = OnvifUtil.getServices(currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "请求失败，请检查认证信息"
                            : parseServices(response);
                    resultItem = new ResultItem("获取设备服务", resultText);
                    break;

                case 3: // 获取设备时间
                    response = OnvifUtil.getSystemDateAndTime(currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "获取失败" : OnvifUtil.parseSystemDateAndTime(response);
                    resultItem = new ResultItem("获取设备时间", resultText);
                    break;

                case 4: // 获取配置Profile列表
                    response = OnvifUtil.getProfiles(currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "获取失败" : OnvifUtil.parseProfilesToString(response);
                    resultItem = new ResultItem("获取配置Profile列表", resultText);
                    break;

                case 5: { // 获取编码参数范围
                    String token = getTokenInput();
                    if (token.isEmpty()) {
                        resultItem = new ResultItem("获取编码参数范围", "请输入 Profile Token");
                        break;
                    }
                    response = OnvifUtil.getVideoEncoderConfigurationOptions(
                            currentServiceUrl, token, currentUsername, currentPassword);
                    resultText = response == null ? "请求失败，设备可能不支持此命令" :
                            parseVideoEncoderConfigurationOptions(response);
                    resultItem = new ResultItem("获取编码参数范围", resultText);
                    break;
                }

                case 6: // 获取所有编码配置
                    response = OnvifUtil.getVideoEncoderConfiguration(
                            currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "请求失败，设备可能不支持此命令" :
                            parseVideoEncoderConfiguration(response);
                    resultItem = new ResultItem("获取所有编码配置", resultText);
                    break;

                case 7: { // 获取视频流地址
                    String token = getTokenInput();
                    if (token.isEmpty()) {
                        resultItem = new ResultItem("获取视频流地址", "请输入 Profile Token");
                        break;
                    }
                    response = OnvifUtil.getStreamUri(
                            currentServiceUrl, token, currentUsername, currentPassword);
                    String rtspUrl = OnvifUtil.parseStreamUri(response);
                    String processedUrl = OnvifUtil.processRtspUrl(
                            rtspUrl, currentUsername, currentPassword);
                    resultItem = new ResultItem("获取视频流地址",
                            "原始RTSP: " + (rtspUrl != null ? rtspUrl : "null") + "\n\n" +
                            "处理后RTSP: " + (processedUrl != null ? processedUrl : "null"));
                    break;
                }

                case 8: // 获取视频源配置
                    response = OnvifUtil.getVideoSources(currentServiceUrl, currentUsername, currentPassword);
                    resultText = response == null ? "请求失败，设备可能不支持此命令"
                            : parseVideoSourceConfigurations(response);
                    resultItem = new ResultItem("获取视频源配置", resultText);
                    break;

                case COMMAND_REBOOT: // 摄像头重启
                    response = OnvifUtil.setRestart(
                            currentServiceUrl, currentUsername, currentPassword);
                    boolean rebootSuccess = OnvifUtil.isSystemRebootSuccess(response);
                    String rebootFault = null;
                    if (response != null && !response.trim().isEmpty()) {
                        rebootFault = OnvifUtil.parseSoapFault(response);
                    }
                    if (rebootSuccess) {
                        resultText = "重启请求已被设备接受，设备将暂时断开连接。";
                    } else if (rebootFault != null) {
                        resultText = "重启失败：" + rebootFault;
                    } else {
                        resultText = "重启失败：设备未响应或要求登录认证。";
                    }
                    resultItem = new ResultItem("摄像头重启（需登录）", resultText);
                    break;

                default:
                    resultItem = new ResultItem("未知命令", "不支持的命令");
                    break;
            }
        } catch (Exception e) {
            resultItem = new ResultItem(commands[commandIndex], "执行失败: " + e.getMessage());
        }

        // 更新UI
        final ResultItem finalResult = resultItem;
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            showLoading(false);
            if (finalResult != null) {
                addResult(finalResult);
            }
        });
    }

    /**
     * 获取Token输入
     */
    private String getTokenInput() {
        if (tokenEditText.getText() == null) return "";
        return tokenEditText.getText().toString().trim();
    }

    /**
     * 免登录重启确认。
     * 标准 ONVIF 要求管理员权限，这里仅尝试不带凭据发送；是否成功取决于摄像头配置。
     */
    private void showAnonymousRebootConfirm() {
        String ip = ipEditText.getText() == null ? "" : ipEditText.getText().toString().trim();
        if (ip.isEmpty()) {
            showToast("请先输入 IP 或 IP:端口");
            return;
        }
        final String serviceUrl = buildDeviceServiceUrl(ip);

        new MaterialAlertDialogBuilder(this)
                .setTitle("免登录尝试重启")
                .setMessage("将不发送用户名和密码，直接请求设备重启。\n\n"
                        + "大多数摄像头会拒绝匿名访问（401/NotAuthorized），"
                        + "只有主动关闭 ONVIF 认证的设备才可能成功。\n\n继续吗？")
                .setPositiveButton("发送", (dialog, which) -> executeAnonymousReboot(serviceUrl))
                .setNegativeButton("取消", null)
                .show();
    }

    private void executeAnonymousReboot(String serviceUrl) {
        showLoading(true);
        executorService.execute(() -> {
            String resultText;
            try {
                String response = OnvifUtil.systemRebootAnonymous(serviceUrl);
                boolean success = OnvifUtil.isSystemRebootSuccess(response);
                if (success) {
                    resultText = "免登录重启请求已被设备接受，设备将暂时断开连接。";
                } else if (response == null) {
                    resultText = "设备拒绝匿名访问或未响应，需要登录认证。";
                } else {
                    String fault = response.trim().isEmpty() ? null
                            : OnvifUtil.parseSoapFault(response);
                    resultText = fault != null ? "重启失败：" + fault
                            : "设备未确认重启请求。";
                }
            } catch (Exception e) {
                resultText = "免登录重启失败：" + e.getMessage();
            }

            final String finalResultText = resultText;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                showLoading(false);
                addResult(new ResultItem("免登录尝试重启", finalResultText));
            });
        });
    }

    // ==================== 解析方法 ====================

    /**
     * 解析服务列表
     */
    private String parseServices(String response) {
        if (response == null) return "";

        StringBuilder sb = new StringBuilder();
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<(?:[\\w.-]+:)?Service[^>]*>(.*?)</(?:[\\w.-]+:)?Service>",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = pattern.matcher(response);

            int index = 1;
            while (matcher.find()) {
                String serviceXml = matcher.group(1);
                String namespace = extractXmlTag(serviceXml, "Namespace");
                String xaddr = extractXmlTag(serviceXml, "XAddr");
                if ((namespace == null || namespace.isEmpty())
                        && (xaddr == null || xaddr.isEmpty())) {
                    continue;
                }
                sb.append("服务").append(index++).append(":\n");
                sb.append("  Namespace: ").append(namespace == null ? "--" : namespace).append("\n");
                sb.append("  Address: ").append(xaddr == null ? "--" : xaddr).append("\n\n");
            }
        } catch (Exception e) {
            sb.append("解析失败: ").append(e.getMessage());
        }

        return sb.length() > 0 ? sb.toString() : "无服务信息";
    }

    private String extractXmlTag(String xml, String tag) {
        if (xml == null || tag == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "<(?:[\\w.-]+:)?" + tag + "[^>]*>(.*?)</(?:[\\w.-]+:)?" + tag + ">",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 解析设备能力列表
     */
    private String parseCapabilities(String response) {
        if (response == null) return "";

        StringBuilder sb = new StringBuilder();
        try {
            String[] keys = {"Media", "PTZ", "Imaging", "Events", "Device"};
            String[] labels = {"媒体", "云台", "图像", "事件", "设备"};
            for (int i = 0; i < keys.length; i++) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "<(?:[\\w.-]+:)?" + keys[i] + "[^>]*>(.*?)</(?:[\\w.-]+:)?" + keys[i] + ">",
                        java.util.regex.Pattern.DOTALL).matcher(response);
                if (matcher.find()) {
                    String xaddr = extractXmlTag(matcher.group(1), "XAddr");
                    sb.append(labels[i]).append(": 支持");
                    if (xaddr != null && !xaddr.isEmpty()) {
                        sb.append("  ").append(xaddr);
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("解析失败: ").append(e.getMessage());
        }

        return sb.length() > 0 ? sb.toString() : "无能力信息";
    }

    /**
     * 解析视频编码器配置选项
     */
    private String parseVideoEncoderConfigurationOptions(String response) {
        if (response == null) return "";

        StringBuilder sb = new StringBuilder();
        try {
            // 提取支持的编码格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<tt:Encoding>(.*?)</tt:Encoding>");
            java.util.regex.Matcher matcher = pattern.matcher(response);

            sb.append("支持的编码格式:\n");
            while (matcher.find()) {
                sb.append("  - ").append(matcher.group(1)).append("\n");
            }

            // 提取支持的分辨率
            pattern = java.util.regex.Pattern.compile(
                    "<tt:Resolution>.*?<tt:Width>(.*?)</tt:Width>.*?<tt:Height>(.*?)</tt:Height>",
                    java.util.regex.Pattern.DOTALL);
            matcher = pattern.matcher(response);

            sb.append("\n支持的分辨率:\n");
            while (matcher.find()) {
                sb.append("  - ").append(matcher.group(1)).append("x").append(matcher.group(2)).append("\n");
            }
        } catch (Exception e) {
            sb.append("解析失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 解析视频编码器配置（一个或多个 Configuration）
     */
    private String parseVideoEncoderConfiguration(String response) {
        if (response == null) return "";

        StringBuilder sb = new StringBuilder();
        try {
            java.util.regex.Matcher blockMatcher = java.util.regex.Pattern.compile(
                    "<(?:[\\w.-]+:)?VideoEncoderConfiguration\\b[^>]*token=\"([^\"]*)\"[^>]*>(.*?)</(?:[\\w.-]+:)?VideoEncoderConfiguration>",
                    java.util.regex.Pattern.DOTALL).matcher(response);

            int index = 1;
            while (blockMatcher.find()) {
                String token = blockMatcher.group(1);
                String content = blockMatcher.group(2);
                sb.append("配置 ").append(index++);
                if (token != null && !token.isEmpty()) {
                    sb.append("  Token: ").append(token);
                }
                sb.append("\n");

                String encoding = extractXmlTag(content, "Encoding");
                if (encoding != null) {
                    sb.append("  编码格式: ").append(encoding).append("\n");
                }

                java.util.regex.Matcher resolutionMatcher = java.util.regex.Pattern.compile(
                        "<(?:[\\w.-]+:)?Width>(\\d+)</(?:[\\w.-]+:)?Width>.*?" +
                        "<(?:[\\w.-]+:)?Height>(\\d+)</(?:[\\w.-]+:)?Height>",
                        java.util.regex.Pattern.DOTALL).matcher(content);
                if (resolutionMatcher.find()) {
                    sb.append("  分辨率: ").append(resolutionMatcher.group(1))
                            .append("x").append(resolutionMatcher.group(2)).append("\n");
                }

                String frameRate = extractXmlTag(content, "FrameRateLimit");
                if (frameRate != null) {
                    sb.append("  帧率: ").append(frameRate).append(" fps\n");
                }
                String bitrate = extractXmlTag(content, "BitrateLimit");
                if (bitrate != null) {
                    sb.append("  码率: ").append(bitrate).append(" kbps\n");
                }
                sb.append("\n");
            }

            // 兼容不支持 VideoEncoderConfiguration 包裹的简化响应
            if (sb.length() == 0) {
                String encoding = extractXmlTag(response, "Encoding");
                if (encoding != null) {
                    sb.append("编码格式: ").append(encoding).append("\n");
                }
                java.util.regex.Matcher resolutionMatcher = java.util.regex.Pattern.compile(
                        "<(?:[\\w.-]+:)?Width>(\\d+)</(?:[\\w.-]+:)?Width>.*?" +
                        "<(?:[\\w.-]+:)?Height>(\\d+)</(?:[\\w.-]+:)?Height>",
                        java.util.regex.Pattern.DOTALL).matcher(response);
                if (resolutionMatcher.find()) {
                    sb.append("分辨率: ").append(resolutionMatcher.group(1))
                            .append("x").append(resolutionMatcher.group(2)).append("\n");
                }
                String frameRate = extractXmlTag(response, "FrameRateLimit");
                if (frameRate != null) {
                    sb.append("帧率: ").append(frameRate).append(" fps\n");
                }
                String bitrate = extractXmlTag(response, "BitrateLimit");
                if (bitrate != null) {
                    sb.append("码率: ").append(bitrate).append(" kbps\n");
                }
            }
        } catch (Exception e) {
            sb.append("解析失败: ").append(e.getMessage());
        }

        return sb.length() > 0 ? sb.toString() : "无编码配置信息";
    }

    /**
     * 解析视频源
     */
    private String parseVideoSourceConfigurations(String response) {
        if (response == null) return "";
        List<String[]> configs = OnvifUtil.parseVideoSourceConfigurations(response);
        if (configs.isEmpty()) {
            return "无视频源配置信息";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < configs.size(); i++) {
            String[] config = configs.get(i);
            sb.append("配置 ").append(i + 1).append(":\n");
            sb.append("  Token: ").append(config[0]).append("\n");
            sb.append("  名称: ").append(config[1] == null || config[1].isEmpty() ? "--" : config[1]).append("\n");
            sb.append("  源 Token: ").append(config[2] == null || config[2].isEmpty() ? "--" : config[2]).append("\n");
            sb.append("  区域: ").append(config[3] == null || config[3].isEmpty() ? "--" : config[3]).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 弹窗方法 ====================

    // ==================== 辅助方法 ====================

    /**
     * 添加结果到列表
     */
    private void addResult(ResultItem item) {
        resultList.add(item);
        resultAdapter.notifyItemInserted(resultList.size() - 1);
        resultRecyclerView.scrollToPosition(resultList.size() - 1);
    }

    /**
     * 显示/隐藏加载动画
     */
    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        executeButton.setEnabled(!show);
        connectButton.setEnabled(!show);
        anonymousRebootButton.setEnabled(!show);
    }

    /**
     * 显示Toast提示
     */
    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    // ==================== 数据类 ====================

    /**
     * 结果项数据类
     */
    public static class ResultItem {
        private String command;
        private String result;

        public ResultItem(String command, String result) {
            this.command = command;
            this.result = result;
        }

        public String getCommand() {
            return command;
        }

        public String getResult() {
            return result;
        }
    }

    /**
     * 结果适配器
     */
    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.ResultViewHolder> {

        @NonNull
        @Override
        public ResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 使用MaterialCardView作为结果项
            MaterialCardView cardView = new MaterialCardView(OnvifConsoleActivity.this);
            cardView.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            cardView.setCardElevation(0);
            cardView.setRadius(dp(16));
            cardView.setStrokeWidth(0);
            ThemeManager.setupResultCard(cardView, OnvifConsoleActivity.this);

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(16), dp(6), dp(16), dp(6));
            cardView.setLayoutParams(params);

            // 创建内容布局
            android.widget.LinearLayout layout = new android.widget.LinearLayout(OnvifConsoleActivity.this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(dp(16), dp(16), dp(16), dp(16));

            TextView commandText = new TextView(OnvifConsoleActivity.this);
            commandText.setId(View.generateViewId());
            commandText.setTextSize(14);
            commandText.setTextColor(ThemeManager.getAccessibleThemeColor(OnvifConsoleActivity.this));
            commandText.setTypeface(null, android.graphics.Typeface.BOLD);
            commandText.setPadding(0, 0, 0, dp(8));

            TextView resultText = new TextView(OnvifConsoleActivity.this);
            resultText.setId(View.generateViewId());
            resultText.setTextSize(12);
            resultText.setTextColor(ThemeManager.getOnSurfaceColor(OnvifConsoleActivity.this));
            resultText.setTextIsSelectable(true);
            resultText.setLineSpacing(0, 1.15f);

            layout.addView(commandText);
            layout.addView(resultText);
            cardView.addView(layout);

            return new ResultViewHolder(cardView, commandText, resultText);
        }

        @Override
        public void onBindViewHolder(@NonNull ResultViewHolder holder, int position) {
            ResultItem item = resultList.get(position);
            holder.commandText.setText(item.getCommand());
            holder.resultText.setText(item.getResult());
        }

        @Override
        public int getItemCount() {
            return resultList.size();
        }

        class ResultViewHolder extends RecyclerView.ViewHolder {
            TextView commandText;
            TextView resultText;

            ResultViewHolder(@NonNull View itemView, TextView commandText, TextView resultText) {
                super(itemView);
                this.commandText = commandText;
                this.resultText = resultText;
            }
        }
    }
}
