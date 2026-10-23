package com.networktool.app.activity.tool;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 路由器跃点跟踪 (Traceroute) Activity
 *
 * 通过逐步增加 TTL 的 ping 实现 traceroute：
 * - TTL 超时 → 中间路由器返回 ICMP Time Exceeded，从中提取路由器 IP
 * - 到达目标 → ping 成功返回，提取目标 IP 并结束
 * - 无响应 → 显示 * * *
 */
public class TracerouteActivity extends AppCompatActivity {

    private static final String TAG = "TracerouteActivity";

    private MaterialToolbar toolbar;
    private MaterialCardView formCard;
    private MaterialCardView resultCard;
    private EditText ipEditText;
    private EditText maxHopsEditText;
    private EditText timeoutEditText;
    private MaterialButton startButton;
    private TextView resultTextView;
    private ProgressBar progressBar;

    private ExecutorService executorService;
    private Handler mainHandler;
    private StringBuilder traceResult;
    private volatile boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_traceroute);

        setupStatusBar();
        initViews();
        setupToolbar();
        setupListeners();

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        traceResult = new StringBuilder();
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
        formCard = findViewById(R.id.formCard);
        resultCard = findViewById(R.id.resultCard);
        ipEditText = findViewById(R.id.ipEditText);
        maxHopsEditText = findViewById(R.id.maxHopsEditText);
        timeoutEditText = findViewById(R.id.timeoutEditText);
        startButton = findViewById(R.id.startButton);
        resultTextView = findViewById(R.id.resultTextView);
        progressBar = findViewById(R.id.progressBar);

        int surfaceColor = ThemeManager.getSurfaceColor();
        formCard.setCardBackgroundColor(surfaceColor);
        resultCard.setCardBackgroundColor(surfaceColor);
    }

    private void setupToolbar() {
        toolbar.setNavigationOnClickListener(v -> finish());

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupListeners() {
        startButton.setOnClickListener(v -> {
            if (isRunning) {
                stopTraceroute();
            } else {
                startTraceroute();
            }
        });

        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(startButton);
    }

    private void startTraceroute() {
        String host = ipEditText.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            Toast.makeText(this, "请输入目标IP地址或域名", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!NetworkUtils.isValidIp(host) && !NetworkUtils.isValidUrl(host)) {
            Toast.makeText(this, "IP地址或域名不符合规范", Toast.LENGTH_SHORT).show();
            return;
        }

        String maxHopsStr = maxHopsEditText.getText().toString().trim();
        String timeoutStr = timeoutEditText.getText().toString().trim();

        int maxHops = TextUtils.isEmpty(maxHopsStr) ? 30 : Integer.parseInt(maxHopsStr);
        int timeout = TextUtils.isEmpty(timeoutStr) ? 3 : Integer.parseInt(timeoutStr);

        maxHops = Math.max(1, Math.min(maxHops, 64));
        timeout = Math.max(1, Math.min(timeout, 10));

        traceResult.setLength(0);
        resultTextView.setText("正在跟踪到 " + host + " 的路由...\n（最大 " + maxHops + " 跳，超时 " + timeout + "s）\n\n");
        isRunning = true;
        startButton.setText("停止");
        progressBar.setVisibility(View.VISIBLE);

        final int finalMaxHops = maxHops;
        final int finalTimeout = timeout;

        executorService.execute(() -> {
            try {
                for (int ttl = 1; ttl <= finalMaxHops && isRunning; ttl++) {
                    final int currentTtl = ttl;
                    // 使用 ping -t 设置 TTL，-c 1 只发一个包，-W 设置超时
                    String command = String.format("ping -c 1 -t %d -W %d %s", ttl, finalTimeout, host);

                    Process process = Runtime.getRuntime().exec(command);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                    // stderr 也可能包含有用信息（某些设备把 TTL exceeded 输出到 stderr）
                    while ((line = errorReader.readLine()) != null) {
                        output.append(line).append("\n");
                    }

                    process.waitFor();
                    reader.close();
                    errorReader.close();
                    process.destroy();

                    String outputStr = output.toString();
                    HopResult result = parseHopResult(outputStr, ttl);

                    mainHandler.post(() -> {
                        traceResult.append(result.displayLine).append("\n");
                        resultTextView.setText(traceResult.toString());
                    });

                    if (result.reachedDestination) {
                        mainHandler.post(() -> {
                            traceResult.append("\n路由跟踪完成！已到达目标主机。\n");
                            resultTextView.setText(traceResult.toString());
                            isRunning = false;
                            startButton.setText("开始跟踪");
                            progressBar.setVisibility(View.GONE);
                        });
                        return;
                    }

                    Thread.sleep(50);
                }

                mainHandler.post(() -> {
                    if (isRunning) {
                        traceResult.append("\n路由跟踪完成（达到最大跳数限制）。\n");
                        resultTextView.setText(traceResult.toString());
                    }
                    isRunning = false;
                    startButton.setText("开始跟踪");
                    progressBar.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "路由跟踪失败: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    traceResult.append("Error: ").append(e.getMessage()).append("\n");
                    resultTextView.setText(traceResult.toString());
                    isRunning = false;
                    startButton.setText("开始跟踪");
                    progressBar.setVisibility(View.GONE);
                });
            }
        });
    }

    /**
     * 解析每一跳的结果
     */
    private HopResult parseHopResult(String pingOutput, int ttl) {
        HopResult result = new HopResult();
        result.ttl = ttl;

        // 判断是否到达目标：有 "bytes from" 或 "1 received" 表示成功到达
        boolean reachedDestination = pingOutput.contains("bytes from")
                && !pingOutput.contains("Time to live exceeded")
                && !pingOutput.contains("time to live exceeded")
                && !pingOutput.contains("TTL to live exceeded");
        
        // 也检查统计行
        if (pingOutput.contains("1 received") || pingOutput.contains("1 packets received")) {
            reachedDestination = true;
        }

        if (reachedDestination) {
            // 到达目标，提取目标 IP
            result.ip = extractDestinationIp(pingOutput);
            result.time = extractTime(pingOutput);
            result.reachedDestination = true;
            result.displayLine = formatHopLine(ttl, result.ip, result.time);
        } else if (pingOutput.contains("Time to live exceeded")
                || pingOutput.contains("time to live exceeded")
                || pingOutput.contains("TTL to live exceeded")) {
            // TTL 超时，提取中间路由器 IP
            result.ip = extractTtlExceededIp(pingOutput);
            result.time = extractTime(pingOutput);
            result.displayLine = formatHopLine(ttl, result.ip, result.time);
        } else {
            // 无响应
            result.ip = null;
            result.displayLine = formatHopLine(ttl, null, null);
        }

        return result;
    }

    /**
     * 从 TTL exceeded 消息中提取中间路由器 IP
     * 格式: "From 192.168.1.1 icmp_seq=1 Time to live exceeded"
     */
    private String extractTtlExceededIp(String output) {
        // 匹配 "From x.x.x.x" 格式（TTL exceeded 消息）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "[Ff]rom\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)");
        java.util.regex.Matcher m = p.matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 从成功 ping 响应中提取目标 IP
     * 格式: "64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=10.0ms"
     */
    private String extractDestinationIp(String output) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "bytes from\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)");
        java.util.regex.Matcher m = p.matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        // 备用：匹配括号中的 IP
        p = java.util.regex.Pattern.compile("\\((\\d+\\.\\d+\\.\\d+\\.\\d+)\\)");
        m = p.matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 提取延迟时间
     */
    private String extractTime(String output) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "time[=<](\\d+\\.?\\d*)\\s*ms");
        java.util.regex.Matcher m = p.matcher(output);
        if (m.find()) {
            return m.group(1) + " ms";
        }
        return null;
    }

    /**
     * 格式化每一跳的显示行
     */
    private String formatHopLine(int ttl, String ip, String time) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%2d  ", ttl));

        if (ip != null && !ip.isEmpty()) {
            sb.append(ip);
            if (time != null && !time.isEmpty()) {
                sb.append("  (").append(time).append(")");
            }
        } else {
            sb.append("* * *");
        }

        return sb.toString();
    }

    private void stopTraceroute() {
        isRunning = false;
        startButton.setText("开始跟踪");
        progressBar.setVisibility(View.GONE);
        traceResult.append("\n[用户手动停止]\n");
        resultTextView.setText(traceResult.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    /**
     * 每一跳的解析结果
     */
    private static class HopResult {
        int ttl;
        String ip;
        String time;
        boolean reachedDestination;
        String displayLine;
    }
}
