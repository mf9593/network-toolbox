package com.networktool.app.activity.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * IP扫描Activity
 * 按照图片设计：网格视图显示IP在线/离线状态
 */
public class IpScanActivity extends AppCompatActivity {

    private static final String TAG = "IpScanActivity";
    
    private MaterialToolbar toolbar;
    private com.google.android.material.textfield.TextInputEditText startIpEditText;
    private com.google.android.material.textfield.TextInputEditText endIpEditText;
    private MaterialButton scanButton;
    private TextView tipTextView;
    private TextView onlineCountText;
    private TextView saveButton;
    private LinearLayout gridContainer;

    private ExecutorService executorService;

    /** 点击 IP 时探测的常见后台 / 摄像头端口 */
    private static final int[] COMMON_PORTS = {
            80, 443, 554, 8000, 8080, 8081, 8443, 8888, 9000, 37777, 34567
    };
    /** 单个端口的连接超时 */
    private static final int PORT_SCAN_TIMEOUT_MS = 400;
    
    // 扫描状态
    private AtomicInteger totalIps = new AtomicInteger(0);
    private AtomicInteger scannedIps = new AtomicInteger(0);
    private AtomicInteger onlineCount = new AtomicInteger(0);
    private Map<String, Boolean> scanResults = new HashMap<>();
    private String currentPrefix = "";
    private int currentSegment = 0;

    // 网格视图
    private List<LinearLayout> gridRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ip_scan);

        // 设置沉浸式状态栏
        setupStatusBar();

        initViews();
        setupToolbar();
        applyThemeColors();
        setupListeners();

        executorService = Executors.newCachedThreadPool();
        
        // 默认填充网关IP
        fillDefaultIp();
    }

    /**
     * 填充默认IP地址
     */
    private void fillDefaultIp() {
        try {
            String gateway = getGateway();
            if (gateway != null && !gateway.equals("0.0.0.0")) {
                startIpEditText.setText(gateway);
                // 结束IP为同网段.255
                String prefix = gateway.substring(0, gateway.lastIndexOf("."));
                endIpEditText.setText(prefix + ".255");
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 获取网关地址
     */
    private String getGateway() {
        // 使用NetworkUtils统一获取网关（支持WiFi和有线网络）
        String gateway = NetworkUtils.getGateway(this);
        if (gateway != null && !gateway.equals("0.0.0.0")) {
            return gateway;
        }

        // 备用方法：获取本地IP推断网关
        String localIp = NetworkUtils.getLocalIpAddress();
        if (localIp != null) {
            try {
                int lastDot = localIp.lastIndexOf(".");
                if (lastDot > 0) {
                    return localIp.substring(0, lastDot + 1) + "1";
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        startIpEditText = findViewById(R.id.startIpEditText);
        endIpEditText = findViewById(R.id.endIpEditText);
        scanButton = findViewById(R.id.scanButton);
        onlineCountText = findViewById(R.id.onlineCountText);
        saveButton = findViewById(R.id.saveButton);
        gridContainer = findViewById(R.id.gridContainer);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("IP扫描");
        }
        
        // 应用主题色到工具栏
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
    
    /**
     * 应用主题色到控件
     */
    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(scanButton);
    }

    private void setupListeners() {
        scanButton.setOnClickListener(v -> startIpScan());
        saveButton.setOnClickListener(v -> saveResults());
    }

    /**
     * 保存扫描结果
     */
    private void saveResults() {
        if (scanResults.isEmpty()) {
            Toast.makeText(this, "没有可保存的数据", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File filesDir = getExternalFilesDir(null);
            if (filesDir == null) {
                filesDir = getFilesDir();
            }
            File saveFile = new File(filesDir, "IP扫描结果.json");
            
            JSONArray jsonArray = new JSONArray();
            for (Map.Entry<String, Boolean> entry : scanResults.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("ip", entry.getKey());
                obj.put("online", entry.getValue());
                jsonArray.put(obj);
            }
            
            try (FileWriter writer = new FileWriter(saveFile)) {
                writer.write(jsonArray.toString(2));
            }
            
            Toast.makeText(this, "已保存到: " + saveFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            AppErrorLogger.logError(TAG, "保存扫描结果失败: " + e.getMessage(), e);
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startIpScan() {
        String startIp = startIpEditText.getText().toString().trim();
        String endIp = endIpEditText.getText().toString().trim();

        if (TextUtils.isEmpty(startIp) || TextUtils.isEmpty(endIp)) {
            Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // 清空之前的结果
        scanResults.clear();
        onlineCount.set(0);
        scannedIps.set(0);
        gridContainer.removeAllViews();
        gridRows.clear();

        // 解析IP地址
        String[] startParts = startIp.split("\\.");
        String[] endParts = endIp.split("\\.");

        if (startParts.length != 4 || endParts.length != 4) {
            Toast.makeText(this, "IP地址格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int startSeg = Integer.parseInt(startParts[2]);
            int endSeg = Integer.parseInt(endParts[2]);
            String basePrefix = startParts[0] + "." + startParts[1] + ".";

            // 计算总IP数
            int totalSubnets = endSeg - startSeg + 1;
            totalIps.set(totalSubnets * 255);

            // 为每个网段创建网格视图
            for (int seg = startSeg; seg <= endSeg; seg++) {
                final String segPrefix = basePrefix + seg;
                createGridForSegment(segPrefix, seg);
            }

            onlineCountText.setText("在线总数：0");

            // 开始扫描
            for (int seg = startSeg; seg <= endSeg; seg++) {
                final String segPrefix = basePrefix + seg;
                for (int host = 1; host <= 255; host++) {
                    final String ip = segPrefix + "." + host;
                    executorService.execute(() -> scanIp(ip, segPrefix));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "IP地址格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 为网段创建网格视图
     */
    private void createGridForSegment(String prefix, int segment) {
        // 网段标题
        TextView titleView = new TextView(this);
        titleView.setText("IP地址：" + prefix + ".X");
        titleView.setTextSize(14);
        titleView.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        titleView.setPadding(0, 16, 0, 8);
        gridContainer.addView(titleView);

        // 在线数显示
        TextView onlineTextView = new TextView(this);
        onlineTextView.setText("在线数：0");
        onlineTextView.setTextSize(12);
        onlineTextView.setTextColor(getResources().getColor(android.R.color.darker_gray, getTheme()));
        onlineTextView.setGravity(Gravity.END);
        onlineTextView.setPadding(0, 0, 0, 8);
        gridContainer.addView(onlineTextView);

        // 创建网格（每行10个）
        int row = 0;
        LinearLayout currentRow = new LinearLayout(this);
        currentRow.setOrientation(LinearLayout.HORIZONTAL);
        gridContainer.addView(currentRow);
        gridRows.add(currentRow);

        for (int i = 0; i <= 255; i++) {
            if (i > 0 && i % 10 == 0) {
                // 新行
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                gridContainer.addView(currentRow);
                gridRows.add(currentRow);
            }

            // 创建单元格
            TextView cell = createIpCell(i, prefix, i == 0);
            
            // 设置标签，方便后续更新
            String tag = prefix + "." + i;
            cell.setTag(tag);
            
            // 存储网格单元引用
            cell.setTag(R.id.ipCellText, onlineTextView);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 120);
            params.weight = 1;
            params.setMargins(2, 2, 2, 2);
            cell.setLayoutParams(params);
            
            currentRow.addView(cell);
        }
    }

    /**
     * 创建IP网格单元格
     */
    private TextView createIpCell(int number, String prefix, boolean isHeader) {
        TextView cell = (TextView) LayoutInflater.from(this).inflate(R.layout.item_ip_grid, null);
        cell.setText(String.valueOf(number));
        cell.setGravity(Gravity.CENTER);
        cell.setTextSize(14);
        
        // 设置默认背景色
        cell.setBackgroundColor(getResources().getColor(android.R.color.darker_gray, getTheme()));
        cell.setTextColor(getResources().getColor(android.R.color.white, getTheme()));
        
        // 点击事件：在线 IP 扫描常见后台/摄像头端口
        cell.setOnClickListener(v -> onIpCellClick(prefix + "." + number));

        return cell;
    }

    /**
     * 点击扫描网格中的 IP。
     * 在线设备会探测常见后台 / 摄像头端口，把开放端口和用途显示在弹窗里，
     * 用户可以直接复制 IP 或打开网页。
     */
    private void onIpCellClick(String ip) {
        Boolean online = scanResults.get(ip);
        boolean isOnline = online != null && online;
        if (!isOnline) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("IP: " + ip)
                    .setMessage("状态：离线\n没有探测到该地址的在线设备。")
                    .setPositiveButton("复制IP", (dialog, which) -> copyIp(ip))
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }

        Toast.makeText(this, "正在扫描 " + ip + " 的常见端口…", Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            final java.util.List<Integer> openPorts = scanCommonPorts(ip);
            runOnUiThread(() -> showPortResult(ip, openPorts));
        });
    }

    /** 并行探测常见后台 / 摄像头端口，返回开放的端口（升序） */
    private java.util.List<Integer> scanCommonPorts(String ip) {
        final java.util.List<Integer> openPorts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
        for (final int port : COMMON_PORTS) {
            tasks.add(executorService.submit(() -> {
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress(ip, port), PORT_SCAN_TIMEOUT_MS);
                    openPorts.add(port);
                } catch (Exception ignored) {
                }
            }));
        }
        for (java.util.concurrent.Future<?> task : tasks) {
            try {
                task.get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        java.util.Collections.sort(openPorts);
        return openPorts;
    }

    /** 端口用途说明 */
    private static String portLabel(int port) {
        switch (port) {
            case 80:
                return "HTTP 后台（大概率有网页管理界面）";
            case 443:
                return "HTTPS 后台";
            case 554:
                return "RTSP 视频流（摄像头）";
            case 8000:
                return "常见后台 / 摄像头服务（海康等）";
            case 8080:
                return "HTTP 备用后台";
            case 8081:
                return "HTTP 备用后台";
            case 8443:
                return "HTTPS 备用后台";
            case 8888:
                return "常见后台 / 摄像头 Web";
            case 9000:
                return "常见后台服务";
            case 37777:
                return "大华设备服务";
            case 34567:
                return "雄迈 / 通用摄像头";
            default:
                return "开放端口";
        }
    }

    /** 显示端口探测结果：无论有没有开放端口都弹窗，保证还能复制 IP / 打开网页 */
    private void showPortResult(String ip, java.util.List<Integer> ports) {
        if (isFinishing() || isDestroyed()) return;
        StringBuilder message = new StringBuilder();
        if (ports == null || ports.isEmpty()) {
            message.append("未发现常见后台端口。\n")
                    .append("该 IP 可能没有网页管理界面，或服务端口不在常见范围内。");
        } else {
            message.append("开放端口：\n");
            for (int port : ports) {
                message.append(port).append("  ").append(portLabel(port)).append("\n");
            }
            message.append("\n提示：80 / 8080 等 Web 端口开放，大概率可以打开网页后台。");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("IP: " + ip)
                .setMessage(message.toString())
                .setPositiveButton("打开网页", (dialog, which) -> openWebPage(bestWebUrl(ip, ports)))
                .setNeutralButton("复制IP", (dialog, which) -> copyIp(ip))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 根据开放端口挑一个最可能打开后台的地址 */
    private String bestWebUrl(String ip, java.util.List<Integer> ports) {
        if (ports != null) {
            if (ports.contains(8443)) return "https://" + ip + ":8443";
            if (ports.contains(443)) return "https://" + ip;
            if (ports.contains(80)) return "http://" + ip;
            if (ports.contains(8080)) return "http://" + ip + ":8080";
            if (ports.contains(8081)) return "http://" + ip + ":8081";
            if (ports.contains(8000)) return "http://" + ip + ":8000";
            if (ports.contains(8888)) return "http://" + ip + ":8888";
            if (ports.contains(9000)) return "http://" + ip + ":9000";
        }
        return "http://" + ip;
    }

    private void openWebPage(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开网页", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyIp(String ip) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("IP", ip));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    /**
     * 扫描单个IP
     */
    private void scanIp(String ip, String prefix) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            boolean isOnline = address.isReachable(100); // 100ms超时
            
            runOnUiThread(() -> {
                scanResults.put(ip, isOnline);
                
                if (isOnline) {
                    onlineCount.incrementAndGet();
                    onlineCountText.setText("在线总数：" + onlineCount.get());
                    
                    // 更新网格颜色为高亮（在线）
                    updateGridCell(ip, prefix, true);
                } else {
                    // 离线，保持默认颜色
                    updateGridCell(ip, prefix, false);
                }
                
                // 更新进度
                int scanned = scannedIps.incrementAndGet();
                int total = totalIps.get();
                
                if (scanned >= total) {
                    onlineCountText.setText("扫描完成！在线总数：" + onlineCount.get());
                    Toast.makeText(IpScanActivity.this, 
                        "扫描完成！发现 " + onlineCount.get() + " 台在线设备", 
                        Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                scanResults.put(ip, false);
                
                int scanned = scannedIps.incrementAndGet();
                int total = totalIps.get();
                
                if (scanned >= total) {
                    onlineCountText.setText("扫描完成！在线总数：" + onlineCount.get());
                }
            });
        }
    }

    /**
     * 更新网格单元格颜色
     */
    private void updateGridCell(String ip, String prefix, boolean isOnline) {
        // 查找对应的单元格
        for (int i = 0; i < gridContainer.getChildCount(); i++) {
            View child = gridContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View cell = row.getChildAt(j);
                    if (cell instanceof TextView) {
                        TextView tv = (TextView) cell;
                        String tag = (String) tv.getTag();
                        if (ip.equals(tag)) {
                            if (isOnline) {
                                // 在线 - 使用主题色高亮
                                int themeColor = ThemeManager.getThemeColor();
                                tv.setBackgroundColor(themeColor);
                                tv.setTextColor(ThemeManager.getContrastColor(themeColor));
                            } else {
                                // 离线 - 灰色
                                tv.setBackgroundColor(getResources().getColor(
                                    android.R.color.darker_gray, getTheme()));
                                tv.setTextColor(getResources().getColor(
                                    android.R.color.white, getTheme()));
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_ip_scan, menu);
        MenuItem help = menu.findItem(R.id.action_help);
        if (help != null && help.getIcon() != null) {
            help.getIcon().setTint(ThemeManager.getOnSurfaceColor(this));
        }
        return true;
    }

    /** 标题栏帮助：说明扫描范围与点击行为 */
    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("IP 扫描说明")
                .setMessage("· 起始/结束 IP 段：按第 3 段网段扫描，"
                        + "例如 192.168.1.1 ~ 192.168.2.254 会扫描 192.168.1.x 和 192.168.2.x，"
                        + "第 4 段固定扫描 1~255。\n\n"
                        + "· 扫描结果：高亮为在线，灰色为离线或未扫描。\n\n"
                        + "· 点击在线 IP：探测常见后台 / 摄像头端口"
                        + "（80、443、554、8000、8080、8081、8443、8888、9000、37777、34567），"
                        + "并在弹窗里列出开放的端口和用途。\n\n"
                        + "· 80 / 8080 等 Web 端口开放，大概率有网页后台，可点「打开网页」直接访问；"
                        + "554 开放一般是 RTSP 摄像头。\n\n"
                        + "· 保存：把本次扫描结果导出为 JSON 文件。")
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
