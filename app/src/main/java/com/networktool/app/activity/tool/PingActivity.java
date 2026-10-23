package com.networktool.app.activity.tool;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.NetworkToolApplication;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ping工具Activity
 *
 * 功能说明：
 * - 测试网络连通性
 * - 支持自定义目标地址、间隔、数据包大小和次数
 * - 网关动态更新，切换网络自动更新
 * - 历史记录卡片，不重复，点击重新ping，可清除
 */
public class PingActivity extends AppCompatActivity {

    private static final String TAG = "PingActivity";
    private static final String PREFS_NAME = "ping_history";
    private static final String KEY_HISTORY = "history_hosts";

    private MaterialToolbar toolbar;
    private EditText ipEditText;
    private EditText intervalEditText;
    private EditText packetSizeEditText;
    private EditText countEditText;
    private ChipGroup chipGroup;
    private ChipGroup historyChipGroup;
    private LinearLayout historyLayout;
    private MaterialButton clearHistoryButton;
    private TextView resultTextView;
    private MaterialButton connectButton;

    private ExecutorService executorService;
    private Handler mainHandler;
    private StringBuilder pingResult;
    private Process currentProcess;
    private volatile boolean isPinging = false;

    private SharedPreferences historyPrefs;
    private LinkedHashSet<String> historyHosts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ping);

        setupStatusBar();

        historyPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadHistory();

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        pingResult = new StringBuilder();

        initViews();
        setupToolbar();
        setupChips();
        setupHistoryChips();
        setupListeners();

        // 从设备列表进入时自动填入目标并开始 Ping
        String presetHost = getIntent().getStringExtra("host");
        if (presetHost == null || presetHost.trim().isEmpty()) {
            presetHost = getIntent().getStringExtra("ip");
        }
        if (presetHost != null && !presetHost.trim().isEmpty()) {
            ipEditText.setText(presetHost.trim());
            if (savedInstanceState == null
                    && getIntent().getBooleanExtra("auto_start", false)) {
                String finalHost = presetHost.trim();
                ipEditText.post(() -> {
                    ipEditText.setText(finalHost);
                    performPing();
                });
            }
        }
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
        toolbar = findViewById(R.id.toolbar);
        ipEditText = findViewById(R.id.ipEditText);
        intervalEditText = findViewById(R.id.intervalEditText);
        packetSizeEditText = findViewById(R.id.packetSizeEditText);
        countEditText = findViewById(R.id.countEditText);
        chipGroup = findViewById(R.id.chipGroup);
        historyChipGroup = findViewById(R.id.historyChipGroup);
        historyLayout = findViewById(R.id.historyLayout);
        clearHistoryButton = findViewById(R.id.clearHistoryButton);
        resultTextView = findViewById(R.id.resultTextView);
        connectButton = findViewById(R.id.connectButton);

        ThemeManager.setupResultCard(findViewById(R.id.pingInputCard), this);
        ThemeManager.setupResultCard(findViewById(R.id.quickSelectCard), this);
        ThemeManager.setupResultCard(findViewById(R.id.resultCard), this);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Ping 工具");
        }

        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupChips() {
        chipGroup.removeAllViews();
        int themeColor = ThemeManager.getThemeColor();

        // 优先从缓存获取，同时尝试动态更新
        String gateway = (String) NetworkToolApplication.getInstance().getSharedData("Gateway");
        if (gateway == null || gateway.equals("0.0.0.0")) {
            gateway = NetworkUtils.getGateway(this);
        }
        // 异步更新网关缓存
        refreshGatewayAsync();

        if (gateway != null && !gateway.equals("0.0.0.0")) {
            addChip(chipGroup, "网关", gateway, themeColor);
        }
        addChip(chipGroup, "百度", "www.baidu.com", themeColor);
        addChip(chipGroup, "必应", "www.bing.com", themeColor);
    }

    /**
     * 异步刷新网关缓存
     */
    private void refreshGatewayAsync() {
        executorService.execute(() -> {
            String gw = NetworkUtils.getGateway(PingActivity.this);
            if (gw != null && !gw.equals("0.0.0.0")) {
                NetworkToolApplication.getInstance().putSharedData("Gateway", gw);
            }
        });
    }

    private void addChip(ChipGroup group, String label, String host, int themeColor) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setTextColor(ThemeManager.getOnSurfaceColor(this));
        chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                ThemeManager.getChipSurfaceColor(this)));
        chip.setChipStrokeWidth(1);
        chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(themeColor));
        chip.setOnClickListener(v -> {
            ipEditText.setText(host);
            performPing();
        });
        group.addView(chip);
    }

    /**
     * 加载历史记录
     */
    private void loadHistory() {
        Set<String> saved = historyPrefs.getStringSet(KEY_HISTORY, new LinkedHashSet<>());
        historyHosts = new LinkedHashSet<>(saved);
    }

    /**
     * 保存历史记录
     */
    private void saveHistory() {
        historyPrefs.edit().putStringSet(KEY_HISTORY, new LinkedHashSet<>(historyHosts)).apply();
    }

    /**
     * 添加历史记录（不重复，最新的在前面）
     */
    private void addHistory(String host) {
        // 移除旧的（如果存在），然后添加到末尾
        historyHosts.remove(host);
        historyHosts.add(host);
        // 限制最多20条
        if (historyHosts.size() > 20) {
            String first = historyHosts.iterator().next();
            historyHosts.remove(first);
        }
        saveHistory();
        setupHistoryChips();
    }

    /**
     * 设置历史记录芯片
     */
    private void setupHistoryChips() {
        historyChipGroup.removeAllViews();
        int themeColor = ThemeManager.getThemeColor();

        if (historyHosts.isEmpty()) {
            historyLayout.setVisibility(View.GONE);
        } else {
            historyLayout.setVisibility(View.VISIBLE);
            // 反转顺序，最新的在前面
            List<String> reversed = new ArrayList<>(historyHosts);
            java.util.Collections.reverse(reversed);
            for (String host : reversed) {
                addChip(historyChipGroup, host, host, themeColor);
            }
        }
    }

    private void setupListeners() {
        connectButton.setOnClickListener(v -> {
            if (isPinging) {
                if (currentProcess != null) {
                    currentProcess.destroy();
                    currentProcess = null;
                }
                isPinging = false;
                connectButton.setText("开始 Ping");
                pingResult.append("\n[用户手动停止Ping]\n");
                resultTextView.setText(pingResult.toString());
            } else {
                performPing();
            }
        });

        clearHistoryButton.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("清除历史记录")
                    .setMessage("确定要清除所有Ping历史记录吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        historyHosts.clear();
                        saveHistory();
                        setupHistoryChips();
                        Toast.makeText(this, "历史记录已清除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        applyThemeColors();
    }

    private void applyThemeColors() {
        ThemeManager.applyPrimaryButton(connectButton);
        clearHistoryButton.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        resultTextView.setTextColor(ThemeManager.getOnSurfaceColor(this));
    }

    private void performPing() {
        String host = ipEditText.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            Toast.makeText(this, "请输入IP地址或域名", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!NetworkUtils.isValidIp(host) && !NetworkUtils.isValidUrl(host)) {
            Toast.makeText(this, "IP地址或域名不符合规范", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isPinging && currentProcess != null) {
            currentProcess.destroy();
            currentProcess = null;
            isPinging = false;
            resultTextView.append("\n[已中断上一次Ping]\n\n");
        }

        String intervalStr = intervalEditText.getText().toString().trim();
        String packetSizeStr = packetSizeEditText.getText().toString().trim();
        String countStr = countEditText.getText().toString().trim();

        int interval = TextUtils.isEmpty(intervalStr) ? 1 : Integer.parseInt(intervalStr);
        int packetSize = TextUtils.isEmpty(packetSizeStr) ? 56 : Integer.parseInt(packetSizeStr);
        int count = TextUtils.isEmpty(countStr) ? 10 : Integer.parseInt(countStr);

        final int finalCount = count < 1 ? 1 : count;
        final int finalInterval = interval;
        final int finalPacketSize = packetSize;

        pingResult.setLength(0);
        resultTextView.setText("正在Ping " + host + "...\n");
        isPinging = true;
        connectButton.setText("停止");

        // 添加到历史记录
        addHistory(host);

        executorService.execute(() -> {
            try {
                String command = String.format("ping -i %d -s %d -c %d -W 3 %s", finalInterval, finalPacketSize, finalCount, host);
                Process process = Runtime.getRuntime().exec(command);
                currentProcess = process;
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    final String output = line;
                    mainHandler.post(() -> {
                        pingResult.append(output).append("\n");
                        resultTextView.setText(pingResult.toString());
                    });
                }

                if (currentProcess != process) {
                    reader.close();
                    return;
                }
                int exitCode = process.waitFor();
                reader.close();
                currentProcess = null;

                final String finishMsg;
                if (exitCode == 0) {
                    finishMsg = "\nPing完成";
                } else {
                    finishMsg = "\nPing结束，退出码: " + exitCode;
                }

                mainHandler.post(() -> {
                    pingResult.append(finishMsg).append("\n");
                    resultTextView.setText(pingResult.toString());
                    isPinging = false;
                    connectButton.setText("开始 Ping");
                });

            } catch (java.io.InterruptedIOException e) {
                // 用户手动停止Ping导致的正常中断，不做错误日志
                mainHandler.post(() -> {
                    isPinging = false;
                    currentProcess = null;
                    connectButton.setText("开始 Ping");
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "Ping执行失败: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    pingResult.append("Error: ").append(e.getMessage()).append("\n");
                    resultTextView.setText(pingResult.toString());
                    isPinging = false;
                    currentProcess = null;
                    connectButton.setText("开始 Ping");
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyThemeColors();
        // 每次恢复时动态更新网关
        setupChips();
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
