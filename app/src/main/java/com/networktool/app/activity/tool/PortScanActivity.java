package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.networktool.app.R;
import com.networktool.app.adapter.PortScanAdapter;
import com.networktool.app.model.PortScanResult;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端口扫描工具Activity
 * 
 * 功能说明：
 * - 扫描指定IP地址的指定端口
 * - 显示端口开放状态
 * - 支持点击结果打开对应服务
 */
public class PortScanActivity extends AppCompatActivity {

    private static final String TAG = "PortScanActivity";
    private static final String DEFAULT_AUTO_PORTS = "21,22,23,53,80,443,554,8000,8080,8443";
    
    private MaterialToolbar toolbar;
    private EditText ipEditText;
    private EditText portEditText;
    private MaterialButton scanButton;
    private GridView gridView;
    private TextView tipTextView;
    private LinearLayout progressLayout;
    private LinearProgressIndicator progressBar;
    private TextView progressText;

    private PortScanAdapter adapter;
    private List<PortScanResult> results;
    private ExecutorService executorService;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_port_scan);

        // 设置沉浸式状态栏
        setupStatusBar();

        initViews();
        setupToolbar();
        setupListeners();

        results = new ArrayList<>();
        adapter = new PortScanAdapter(this, results);
        gridView.setAdapter(adapter);

        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());

        // 从设备详情进入时自动填入目标 IP 和常用端口
        String presetIp = getIntent().getStringExtra("ip");
        if (presetIp != null && !presetIp.trim().isEmpty()) {
            ipEditText.setText(presetIp.trim());
        }
        boolean autoStart = getIntent().getBooleanExtra("auto_start", false);
        if (autoStart) {
            String presetPorts = getIntent().getStringExtra("ports");
            portEditText.setText(presetPorts == null || presetPorts.trim().isEmpty()
                    ? DEFAULT_AUTO_PORTS : presetPorts.trim());
            if (savedInstanceState == null) {
                ipEditText.post(this::startPortScan);
            }
        }
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

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        ipEditText = findViewById(R.id.ipEditText);
        portEditText = findViewById(R.id.portEditText);
        scanButton = findViewById(R.id.scanButton);
        gridView = findViewById(R.id.gridView);
        tipTextView = findViewById(R.id.tipTextView);
        progressLayout = findViewById(R.id.progressLayout);
        progressBar = findViewById(R.id.progressBar);
        progressText = findViewById(R.id.progressText);

        ThemeManager.setupResultCard(findViewById(R.id.formCard), this);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("端口扫描");
        }
        
        // 应用主题色到工具栏
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupListeners() {
        scanButton.setOnClickListener(v -> startPortScan());
        
        // 应用主题色
        applyThemeColors();
    }
    
    /**
     * 应用主题色到控件
     */
    private void applyThemeColors() {
        // 扫描按钮使用主题色
        ThemeManager.applyPrimaryButton(scanButton);

        // 提示文字使用可读的主题强调色
        tipTextView.setTextColor(ThemeManager.getAccessibleThemeColor(this));
    }

    private void startPortScan() {
        String ip = ipEditText.getText().toString().trim();
        String portRange = portEditText.getText().toString().trim();

        if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(portRange)) {
            Toast.makeText(this, "请输入IP地址和端口", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!NetworkUtils.isValidIp(ip)) {
            Toast.makeText(this, "IP地址不符合规范", Toast.LENGTH_SHORT).show();
            return;
        }

        results.clear();
        adapter.notifyDataSetChanged();

        // Parse port ranges (support multiple ranges like "1-10,80-88,100")
        List<int[]> portRanges = new ArrayList<>();
        String[] rangeParts = portRange.split(",");
        for (String rangePart : rangeParts) {
            rangePart = rangePart.trim();
            try {
                if (rangePart.contains("-")) {
                    String[] parts = rangePart.split("-");
                    int start = Integer.parseInt(parts[0].trim());
                    int end = Integer.parseInt(parts[1].trim());
                    if (start < 1 || end > 65535 || start > end) {
                        throw new NumberFormatException("Invalid port range: " + rangePart);
                    }
                    portRanges.add(new int[]{start, end});
                } else {
                    int port = Integer.parseInt(rangePart);
                    if (port < 1 || port > 65535) {
                        throw new NumberFormatException("Invalid port: " + rangePart);
                    }
                    portRanges.add(new int[]{port, port});
                }
            } catch (NumberFormatException e) {
                AppErrorLogger.logError(TAG, "端口格式解析错误: " + e.getMessage(), e);
                Toast.makeText(this, "端口格式错误: " + rangePart, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        tipTextView.setText("开始扫描 " + ip + "，端口范围: " + portRange);
        scanButton.setEnabled(false);
        progressLayout.setVisibility(View.VISIBLE);

        // Count total ports
        int totalPorts = 0;
        for (int[] range : portRanges) {
            totalPorts += (range[1] - range[0] + 1);
        }

        progressBar.setMax(totalPorts);
        progressBar.setProgress(0);
        progressText.setText("扫描进度: 0/" + totalPorts);

        final int totalPortsFinal = totalPorts;
        final AtomicInteger completedPorts = new AtomicInteger(0);

        // Scan all ports in all ranges
        for (int[] range : portRanges) {
            int startPort = range[0];
            int endPort = range[1];
            for (int port = startPort; port <= endPort; port++) {
                final int currentPort = port;
                executorService.execute(() -> {
                    scanPort(ip, currentPort);

                    // Update progress
                    int completed = completedPorts.incrementAndGet();
                    mainHandler.post(() -> {
                        progressBar.setProgress(completed);
                        progressText.setText("扫描进度: " + completed + "/" + totalPortsFinal);
                        if (completed == totalPortsFinal) {
                            // All ports scanned
                            progressLayout.setVisibility(View.GONE);
                            tipTextView.setText("扫描完成，共发现 " + results.size() + " 个开放端口");
                            scanButton.setEnabled(true);
                        }
                    });
                });
            }
        }
    }

    private void scanPort(String ip, int port) {
        try {
            Socket socket = new Socket();
            // 增加超时时间到500ms，更稳定
            socket.connect(new InetSocketAddress(ip, port), 500);
            socket.close();

            // Port is open
            mainHandler.post(() -> {
                PortScanResult result = new PortScanResult();
                result.setIp(ip);
                result.setPort(port);
                result.setUrl("http://" + ip + ":" + port);
                results.add(result);
                adapter.notifyDataSetChanged();
                tipTextView.setText("检测到开放端口: " + port);
            });
        } catch (Exception e) {
            // Port is closed or filtered，静默处理
        }
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
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
