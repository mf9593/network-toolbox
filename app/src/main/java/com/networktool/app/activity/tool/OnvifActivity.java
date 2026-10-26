package com.networktool.app.activity.tool;

import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import android.graphics.PorterDuff;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import com.networktool.app.R;
import com.networktool.app.player.RtspPlayer;
import com.networktool.app.player.RtspPlayerView;
import com.networktool.app.adapter.OnvifDeviceAdapter;
import com.networktool.app.model.OnvifDevice;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.OnvifUtil;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.activity.tool.WebViewActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.Credentials;

/**
 * Onvif调试Activity - 包含设备发现和RTSP预览
 * 
 * 提供Onvif摄像头设备发现和控制功能：
 * - WS-Discovery设备发现
 * - 设备信息获取
 * - RTSP流地址获取
 * - 设备认证
 * - 手动输入RTSP地址播放
 */
public class OnvifActivity extends AppCompatActivity {
    private static final String TAG = "OnvifActivity";

    /** 从设备页跳转过来时预填的设备信息（无需重新扫描） */
    public static final String EXTRA_PREFILL_IP = "prefill_ip";
    public static final String EXTRA_PREFILL_PORT = "prefill_port";
    public static final String EXTRA_PREFILL_NAME = "prefill_name";

    // Tab相关
    private TabLayout tabLayout;
    private LinearLayout onvifLayout;
    private LinearLayout rtspInputLayout;
    
    // RTSP播放器相关
    private TextInputEditText rtspUrlInput;
    private TextInputEditText deviceNameInput;
    private Button rtspPlayButton;
    private Button rtspStopButton;
    private FrameLayout rtspPlayerContainer;
    private FrameLayout videoViewContainer;
    private View rtspTopBar;
    private View rtspBottomBar;
    private ImageView rtspBackButton;
    private ImageView rtspFullscreenBtn;
    private ImageView rtspPlayPauseBtn;
    private SeekBar rtspSeekBar;
    private TextView rtspStatusText;
    private TextView rtspResolutionText;
    private ProgressBar rtspLoadingProgress;
    private TextView rtspStatusOverlay;
    private RadioGroup transportProtocolGroup;
    
    // 当前选择的传输协议
    private String currentTransportProtocol = "TCP";
    
    // 播放控制状态
    private boolean isControlsVisible = false;
    private boolean isPlaying = false;
    private boolean isFullscreen = false;
    private android.os.Handler hideHandler = new android.os.Handler(Looper.getMainLooper());
    private Runnable hideRunnable = this::hideControls;
    private static final int HIDE_DELAY = 5000;
    
    // 视频尺寸
    private int videoWidth = 0;
    private int videoHeight = 0;
    
    // RtspPlayer
    private RtspPlayer nodePlayer;
    private RtspPlayerView videoView;
    
    // 保留 appendLog 方法用于调试，但不显示 UI
    
    // Onvif相关
    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private Button scanButton;
    private Button addManualButton;
    private Button clearButton;
    private ProgressBar scanProgressBar;
    private TextView scanStatusText;
    private TextView deviceCountText;

    private OnvifDeviceAdapter adapter;
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isScanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_debug);

        // 设置沉浸式状态栏
        setupStatusBar();

        // 初始化工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setupToolbar();

        // 初始化线程池（每次创建时初始化）
        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());

        initTabs();
        initViews();
        setupRecyclerView();
        initRtspPlayer();
        handlePrefillDevice();
    }

    /**
     * 处理从设备扫描页跳转过来的预填设备。
     *
     * 直接把该设备加入列表并展示，无需重新扫描，
     * 用户可立即进行连接、取流等进一步操作。
     */
    private void handlePrefillDevice() {
        String ip = getIntent().getStringExtra(EXTRA_PREFILL_IP);
        if (ip == null || ip.trim().isEmpty()) return;
        int port = getIntent().getIntExtra(EXTRA_PREFILL_PORT, 80);
        String name = getIntent().getStringExtra(EXTRA_PREFILL_NAME);

        OnvifDevice device = new OnvifDevice();
        device.setIpAddress(ip.trim());
        device.setPort(port > 0 ? port : 80);
        device.setName(name == null || name.trim().isEmpty() ? ip.trim() : name.trim());
        device.setUuid("prefill-" + ip.trim());

        adapter.addDevice(device);
        updateDeviceCount();
        emptyView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        scanStatusText.setText("已加载设备 " + ip.trim() + "，可直接连接调试");
    }

    /**
     * 初始化Tab切换 - Onvif设备、RTSP预览
     */
    private void initTabs() {
        tabLayout = findViewById(R.id.tabLayout);
        onvifLayout = findViewById(R.id.deviceTabContent);
        rtspInputLayout = findViewById(R.id.rtspTabContent);

        // 默认显示Onvif设备页
        onvifLayout.setVisibility(View.VISIBLE);
        rtspInputLayout.setVisibility(View.GONE);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                switch (position) {
                    case 0: // Onvif设备
                        onvifLayout.setVisibility(View.VISIBLE);
                        rtspInputLayout.setVisibility(View.GONE);
                        Log.i(TAG, "切换到Onvif设备页面");
                        break;
                    case 1: // RTSP预览
                        onvifLayout.setVisibility(View.GONE);
                        rtspInputLayout.setVisibility(View.VISIBLE);
                        Log.i(TAG, "切换到RTSP预览页面");
                        break;
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
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
            getSupportActionBar().setTitle("RTSP/Onvif调试");
        }
        
        // 应用主题色到工具栏
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        // Onvif相关
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        // 概览卡 / 操作卡沿用主页卡片底色，避免和其余页面有色差
        ThemeManager.setupResultCard(findViewById(R.id.infoCard), this);
        ThemeManager.setupResultCard(findViewById(R.id.scanCard), this);
        // 主操作按钮跟随主题色（扫描 / 播放）
        for (int id : new int[]{R.id.scanButton, R.id.rtspPlayButton}) {
            View button = findViewById(id);
            if (button instanceof com.google.android.material.button.MaterialButton) {
                ThemeManager.applyPrimaryButton(
                        (com.google.android.material.button.MaterialButton) button);
            }
        }
        scanButton = findViewById(R.id.scanButton);
        addManualButton = findViewById(R.id.addManualButton);
        clearButton = findViewById(R.id.clearButton);
        scanProgressBar = findViewById(R.id.scanProgressBar);
        scanStatusText = findViewById(R.id.scanStatusText);
        deviceCountText = findViewById(R.id.deviceCountText);

        scanButton.setOnClickListener(v -> startScan());
        addManualButton.setOnClickListener(v -> showAddManualDialog());
        clearButton.setOnClickListener(v -> clearDevices());
        
        // RTSP相关
        rtspUrlInput = findViewById(R.id.rtspUrlInput);
        deviceNameInput = findViewById(R.id.deviceNameInput);
        rtspPlayButton = findViewById(R.id.rtspPlayButton);
        rtspStopButton = findViewById(R.id.rtspStopButton);
        rtspPlayerContainer = findViewById(R.id.rtspPlayerContainer);
        videoViewContainer = findViewById(R.id.videoViewContainer);
        rtspTopBar = findViewById(R.id.rtspTopBar);
        rtspBottomBar = findViewById(R.id.rtspBottomBar);
        rtspBackButton = findViewById(R.id.rtspBackButton);
        rtspFullscreenBtn = findViewById(R.id.rtspFullscreenBtn);
        rtspPlayPauseBtn = findViewById(R.id.rtspPlayPauseBtn);
        rtspSeekBar = findViewById(R.id.rtspSeekBar);
        rtspStatusText = findViewById(R.id.rtspStatusText);
        rtspResolutionText = findViewById(R.id.rtspResolutionText);
        rtspLoadingProgress = findViewById(R.id.rtspLoadingProgress);
        rtspStatusOverlay = findViewById(R.id.rtspStatusOverlay);
        transportProtocolGroup = findViewById(R.id.transportProtocolGroup);
        
        rtspPlayButton.setOnClickListener(v -> startRtspPlayback());
        rtspStopButton.setOnClickListener(v -> stopRtspPlayback());
        
        // 播放器控制
        rtspPlayerContainer.setOnClickListener(v -> toggleControls());
        rtspBackButton.setOnClickListener(v -> stopRtspPlayback());
        rtspPlayPauseBtn.setOnClickListener(v -> togglePlayPause());
        rtspFullscreenBtn.setOnClickListener(v -> toggleFullscreen());
        
        // 传输协议选择
        transportProtocolGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.protocolTcp) {
                currentTransportProtocol = "TCP";
            } else if (checkedId == R.id.protocolUdp) {
                currentTransportProtocol = "UDP";
            } else if (checkedId == R.id.protocolUdpMulticast) {
                currentTransportProtocol = "UDP_MULTICAST";
            } else if (checkedId == R.id.protocolHttp) {
                currentTransportProtocol = "HTTP";
            }
            appendLog("传输协议切换为: " + currentTransportProtocol, "INFO");
        });
    }

    private void setupRecyclerView() {
        adapter = new OnvifDeviceAdapter(new OnvifDeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onDeviceClick(OnvifDevice device) {
                showDeviceDetails(device);
            }

            @Override
            public void onDeviceConnect(OnvifDevice device) {
                showAuthDialog(device);
            }

            @Override
            public void onDeviceStream(OnvifDevice device) {
                getStreamUri(device);
            }

            @Override
            public void onDeviceMenu(OnvifDevice device) {
                showDeviceMenuDialog(device);
            }

            @Override
            public void onDeviceLongClick(OnvifDevice device) {
                showDeviceDetails(device);
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    /**
     * 比较两个IP地址（按数值大小）
     */
    private int compareIpAddresses(String ip1, String ip2) {
        if (ip1 == null && ip2 == null) return 0;
        if (ip1 == null) return -1;
        if (ip2 == null) return 1;

        try {
            String[] parts1 = ip1.split("\\.");
            String[] parts2 = ip2.split("\\.");

            for (int i = 0; i < 4; i++) {
                int num1 = Integer.parseInt(parts1[i]);
                int num2 = Integer.parseInt(parts2[i]);
                if (num1 != num2) {
                    return Integer.compare(num1, num2);
                }
            }
            return 0;
        } catch (Exception e) {
            // 如果解析失败，按字符串比较
            return ip1.compareTo(ip2);
        }
    }

    /**
     * 开始扫描Onvif设备
     *
     * 使用WS-Discovery协议发现局域网内的ONVIF摄像头设备。
     * 扫描过程在后台线程执行，不会阻塞UI。
     * 扫描完成后自动按IP地址排序并更新列表显示。
     *
     * 线程安全说明：
     * - 使用CachedThreadPool，可自动创建和回收线程
     * - 扫描任务在独立线程执行，不阻塞主线程
     * - Activity销毁时立即终止线程池，防止内存泄漏
     * - 所有UI更新都通过runOnUiThreadSafe检查Activity状态
     */
    private void startScan() {
        // 防止重复扫描
        if (isScanning) return;

        // 确保executorService已初始化且未关闭
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newCachedThreadPool();
        }

        isScanning = true;
        scanButton.setEnabled(false);
        scanProgressBar.setVisibility(View.VISIBLE);
        scanStatusText.setText("正在扫描局域网内的Onvif设备...");

        // 在后台线程执行扫描（使用线程池，不阻塞UI）
        executorService.execute(() -> {
            try {
                // 调用OnvifUtil发现设备（此调用会阻塞最多5秒）
                List<OnvifDevice> discoveredDevices = OnvifUtil.discoverDevices(OnvifActivity.this);

                // 安全检查：确保设备列表不为null
                final List<OnvifDevice> devices = (discoveredDevices != null) ? discoveredDevices : new ArrayList<>();

                // 按IP地址排序（添加异常保护）
                try {
                    Collections.sort(devices, new Comparator<OnvifDevice>() {
                        @Override
                        public int compare(OnvifDevice d1, OnvifDevice d2) {
                            return compareIpAddresses(d1.getIpAddress(), d2.getIpAddress());
                        }
                    });
                } catch (Exception sortEx) {
                    // 排序失败不影响继续显示
                    AppErrorLogger.logError(TAG, "设备排序失败: " + sortEx.getMessage(), sortEx);
                }

                // Activity状态检查后再更新UI
                runOnUiThreadSafe(() -> {
                    adapter.setDevices(devices);
                    updateDeviceCount();

                    if (devices.isEmpty()) {
                        scanStatusText.setText("未发现Onvif设备");
                        emptyView.setVisibility(View.VISIBLE);
                        recyclerView.setVisibility(View.GONE);
                    } else {
                        scanStatusText.setText("发现 " + devices.size() + " 个Onvif设备");
                        emptyView.setVisibility(View.GONE);
                        recyclerView.setVisibility(View.VISIBLE);
                        Toast.makeText(OnvifActivity.this, "发现 " + devices.size() + " 个设备", Toast.LENGTH_SHORT).show();
                    }

                    scanProgressBar.setVisibility(View.GONE);
                    scanButton.setEnabled(true);
                    isScanning = false;
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "扫描异常: " + e.getMessage(), e);
                runOnUiThreadSafe(() -> {
                    scanStatusText.setText("扫描失败: " + e.getMessage());
                    scanProgressBar.setVisibility(View.GONE);
                    scanButton.setEnabled(true);
                    isScanning = false;
                    Toast.makeText(OnvifActivity.this, "扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 安全地在UI线程执行，检查Activity状态
     */
    private void runOnUiThreadSafe(Runnable action) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mainHandler.post(action);
    }

    /**
     * 显示手动添加设备对话框
     */
    private void showAddManualDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_onvif_device, null);

        EditText ipEdit = dialogView.findViewById(R.id.ipEdit);
        EditText portEdit = dialogView.findViewById(R.id.portEdit);
        EditText nameEdit = dialogView.findViewById(R.id.nameEdit);

        portEdit.setText("80");

        new MaterialAlertDialogBuilder(this)
                .setTitle("手动添加设备")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String ip = ipEdit.getText().toString().trim();
                    String portStr = portEdit.getText().toString().trim();
                    String name = nameEdit.getText().toString().trim();

                    if (ip.isEmpty()) {
                        Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port = 80;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        port = 80;
                    }

                    OnvifDevice device = new OnvifDevice();
                    device.setIpAddress(ip);
                    device.setPort(port);
                    device.setName(name.isEmpty() ? ip : name);
                    device.setUuid("manual-" + System.currentTimeMillis());

                    adapter.addDevice(device);
                    updateDeviceCount();
                    emptyView.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);

                    Toast.makeText(this, "设备已添加", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 清空设备列表
     */
    private void clearDevices() {
        adapter.clearDevices();
        updateDeviceCount();
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        scanStatusText.setText("点击\"扫描设备\"开始搜索局域网内的Onvif摄像头");
    }

    /**
     * 更新设备计数
     */
    private void updateDeviceCount() {
        deviceCountText.setText(adapter.getItemCount() + " 个设备");
    }

    /**
     * 显示设备详情
     *
     * 显示设备的完整信息，包括：
     * - 基本网络信息（名称、IP、端口）
     * - 设备硬件信息（制造商、型号、固件版本）
     * - 认证状态
     * - RTSP流地址（如果已获取）
     *
     * @param device 设备对象
     */
    private void showDeviceDetails(OnvifDevice device) {
        StringBuilder info = new StringBuilder();
        info.append("名称: ").append(device.getDisplayName()).append("\n");
        info.append("IP地址: ").append(device.getIpAddress()).append("\n");
        info.append("端口: ").append(device.getPort()).append("\n\n");

        if (device.getManufacturer() != null) {
            info.append("制造商: ").append(device.getManufacturer()).append("\n");
        }
        if (device.getModel() != null) {
            info.append("型号: ").append(device.getModel()).append("\n");
        }
        if (device.getFirmwareVersion() != null) {
            info.append("固件版本: ").append(device.getFirmwareVersion()).append("\n");
        }
        if (device.getSerialNumber() != null) {
            info.append("序列号: ").append(device.getSerialNumber()).append("\n");
        }
        if (device.getHardware() != null) {
            info.append("硬件: ").append(device.getHardware()).append("\n");
        }
        if (device.getLocation() != null) {
            info.append("位置: ").append(device.getLocation()).append("\n");
        }
        if (device.getRtspUrl() != null) {
            info.append("\nRTSP地址:\n").append(device.getRtspUrl()).append("\n");
        }

        info.append("\n认证状态: ").append(device.isAuthenticated() ? "已认证" : "未认证");

        new MaterialAlertDialogBuilder(this)
                .setTitle("设备详情")
                .setMessage(info.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * 显示认证对话框
     *
     * 弹出对话框让用户输入ONVIF设备的用户名和密码。
     * 提供"使用默认用户"复选框，勾选后自动填入admin/123456。
     * 认证成功后会更新设备状态并识别设备品牌。
     *
     * @param device 需要认证的设备对象
     */
    private void showAuthDialog(OnvifDevice device) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_onvif_auth, null);

        EditText usernameEdit = dialogView.findViewById(R.id.usernameEdit);
        EditText passwordEdit = dialogView.findViewById(R.id.passwordEdit);
        androidx.appcompat.widget.AppCompatCheckBox defaultUserCheckBox = dialogView.findViewById(R.id.defaultUserCheckBox);

        // 如果设备已有凭据，预填
        if (device.getUsername() != null) {
            usernameEdit.setText(device.getUsername());
        }
        if (device.getPassword() != null) {
            passwordEdit.setText(device.getPassword());
        }

        // 监听复选框变化
        defaultUserCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                usernameEdit.setText("admin");
                passwordEdit.setText("123456");
                usernameEdit.setEnabled(false);
                passwordEdit.setEnabled(false);
            } else {
                usernameEdit.setText("");
                passwordEdit.setText("");
                usernameEdit.setEnabled(true);
                passwordEdit.setEnabled(true);
            }
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("设备认证")
                .setMessage("请输入Onvif设备的用户名和密码")
                .setView(dialogView)
                .setPositiveButton("连接", (dialog, which) -> {
                    String username = usernameEdit.getText().toString().trim();
                    String password = passwordEdit.getText().toString();

                    if (username.isEmpty() || password.isEmpty()) {
                        Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    device.setUsername(username);
                    device.setPassword(password);

                    authenticateDevice(device);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 认证设备并获取信息
     *
     * 通过ONVIF协议获取设备详细信息，识别设备品牌。
     * 认证过程在后台线程执行，避免阻塞UI。
     *
     * @param device 需要认证的设备对象
     */
    private void authenticateDevice(OnvifDevice device) {
        scanStatusText.setText("正在连接设备...");

        executorService.execute(() -> {
            try {
                // 获取设备信息
                String response = OnvifUtil.getDeviceInformation(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (response != null) {
                    // 解析设备信息并填充到设备对象
                    OnvifUtil.parseDeviceInformation(response, device);
                    device.setAuthenticated(true);

                    // 识别设备品牌
                    String brand = OnvifUtil.detectBrand(device.getManufacturer(), device.getModel());
                    device.setBrand(brand);

                    // Activity状态检查
                    if (isFinishing() || isDestroyed()) return;

                    mainHandler.post(() -> {
                        adapter.updateDevice(device);
                        scanStatusText.setText("设备认证成功 - " + brand);
                        Toast.makeText(OnvifActivity.this, "认证成功 - " + brand, Toast.LENGTH_SHORT).show();
                    });
                } else {
                    if (isFinishing() || isDestroyed()) return;

                    mainHandler.post(() -> {
                        scanStatusText.setText("认证失败，请检查用户名和密码");
                        Toast.makeText(OnvifActivity.this, "认证失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "认证异常: " + e.getMessage(), e);
                if (isFinishing() || isDestroyed()) return;

                mainHandler.post(() -> {
                    scanStatusText.setText("连接失败: " + e.getMessage());
                    Toast.makeText(OnvifActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示修改通道名称对话框
     *
     * 通过ONVIF标准协议修改摄像头的通道名称：
     * 1. 先调用GetVideoSourceConfigurations获取当前视频源配置
     * 2. 如果设备不支持视频源配置，则尝试GetUserFriendlyName获取设备友好名称
     * 3. 显示对话框让用户输入新名称
     * 4. 调用SetVideoSourceConfigurations写入，失败则回退到SetUserFriendlyName
     *
     * @param device 设备对象（必须已认证）
     */
    private void showChannelNameDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        scanStatusText.setText("正在获取通道配置...");

        executorService.execute(() -> {
            try {
                // 步骤1: 尝试获取VideoSourceConfigurations
                String response = OnvifUtil.getVideoSourceConfigurations(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                boolean useVideoSource = false;
                String configToken = null;
                String currentName = null;
                String sourceToken = null;
                String bounds = null;

                if (response != null) {
                    List<String[]> configs = OnvifUtil.parseVideoSourceConfigurations(response);
                    if (!configs.isEmpty()) {
                        String[] firstConfig = configs.get(0);
                        configToken = firstConfig[0];
                        currentName = firstConfig[1];
                        sourceToken = firstConfig[2];
                        bounds = firstConfig[3];
                        useVideoSource = true;
                    }
                }

                // 步骤2: 如果VideoSourceConfigurations不可用，尝试GetUserFriendlyName
                if (!useVideoSource) {
                    String friendlyResponse = OnvifUtil.getUserFriendlyName(
                            device.getServiceUrl(), device.getUsername(), device.getPassword());
                    if (friendlyResponse != null) {
                        String friendlyName = OnvifUtil.parseUserFriendlyName(friendlyResponse);
                        if (friendlyName != null && !friendlyName.isEmpty()) {
                            currentName = friendlyName;
                        }
                    }
                }

                if (currentName == null || currentName.isEmpty()) {
                    currentName = "通道1";
                }

                if (isFinishing() || isDestroyed()) return;

                final String finalCurrentName = currentName;
                final String finalConfigToken = configToken;
                final String finalSourceToken = sourceToken;
                final String finalBounds = bounds;
                final boolean finalUseVideoSource = useVideoSource;

                mainHandler.post(() -> {
                    scanStatusText.setText("");

                    // 创建输入布局
                    LinearLayout layout = new LinearLayout(OnvifActivity.this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(40, 20, 40, 10);

                    final EditText input = new EditText(OnvifActivity.this);
                    input.setHint("请输入新的通道名称");
                    input.setText(finalCurrentName);
                    input.setSelection(finalCurrentName.length());
                    input.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));

                    int themeColor = ThemeManager.getThemeColor();
                    input.getBackground().mutate().setColorFilter(themeColor, PorterDuff.Mode.SRC_ATOP);

                    layout.addView(input);

                    new MaterialAlertDialogBuilder(OnvifActivity.this)
                            .setTitle("修改通道名")
                            .setView(layout)
                            .setPositiveButton("确定", (dialog, which) -> {
                                String newName = input.getText().toString().trim();
                                if (newName.isEmpty()) {
                                    Toast.makeText(OnvifActivity.this, "名称不能为空", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                // 通过ONVIF协议修改摄像头通道名称
                                applyChannelName(device, finalConfigToken, newName,
                                        finalSourceToken, finalBounds, finalUseVideoSource);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });

            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "获取通道配置异常: " + e.getMessage(), e);
                if (isFinishing() || isDestroyed()) return;
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(OnvifActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 通过ONVIF协议将新通道名称写入摄像头
     *
     * 优先使用SetVideoSourceConfigurations（修改视频源名称），
     * 如果失败则回退到SetUserFriendlyName（修改设备友好名称）。
     */
    private void applyChannelName(OnvifDevice device, String configToken, String newName,
                                   String sourceToken, String bounds, boolean useVideoSource) {
        scanStatusText.setText("正在修改通道名称...");

        executorService.execute(() -> {
            try {
                String result = null;

                // 优先使用SetVideoSourceConfigurations
                if (useVideoSource && configToken != null) {
                    result = OnvifUtil.setVideoSourceConfigurations(
                            device.getServiceUrl(), device.getUsername(), device.getPassword(),
                            configToken, newName, sourceToken, bounds);
                }

                // 如果SetVideoSourceConfigurations失败，尝试SetUserFriendlyName
                if (result == null) {
                    result = OnvifUtil.setUserFriendlyName(
                            device.getServiceUrl(), device.getUsername(), device.getPassword(),
                            newName);
                }

                if (isFinishing() || isDestroyed()) return;

                final String finalResult = result;
                mainHandler.post(() -> {
                    if (finalResult != null) {
                        // 成功：更新本地设备名称并刷新
                        device.setName(newName);
                        adapter.notifyDataSetChanged();
                        scanStatusText.setText("通道名称已修改为: " + newName);
                        Toast.makeText(OnvifActivity.this, "通道名已修改为: " + newName, Toast.LENGTH_SHORT).show();
                    } else {
                        scanStatusText.setText("修改通道名称失败");
                        Toast.makeText(OnvifActivity.this, "修改通道名称失败，设备可能不支持此操作", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "修改通道名称异常: " + e.getMessage(), e);
                if (isFinishing() || isDestroyed()) return;
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(OnvifActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示OSD修改对话框
     * 支持修改OSD文字内容和显示位置
     */
    private void showOsdDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示加载提示
        androidx.appcompat.app.AlertDialog loadingDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("正在获取OSD配置...")
                .setView(new android.widget.ProgressBar(this))
                .setCancelable(false)
                .create();
        loadingDialog.show();

        executorService.execute(() -> {
            try {
                // 获取OSD配置
                String osdResponse = OnvifUtil.getOSDs(device.getServiceUrl(), device.getUsername(), device.getPassword());
                String videoSourceResponse = OnvifUtil.getVideoSourceConfigurations(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (osdResponse == null || videoSourceResponse == null) {
                    mainHandler.post(() -> {
                        loadingDialog.dismiss();
                        Toast.makeText(this, "获取OSD配置失败，设备可能不支持OSD", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 解析VideoSourceConfiguration获取token
                java.util.List<String[]> videoSources = OnvifUtil.parseVideoSourceConfigurations(videoSourceResponse);
                String videoSourceToken = null;
                if (!videoSources.isEmpty()) {
                    videoSourceToken = videoSources.get(0)[0]; // token
                }

                // 解析OSD配置
                String osdToken = null;
                String currentText = "";
                float currentX = -0.8f;
                float currentY = -0.8f;

                try {
                    // 简单XML解析获取OSD信息
                    org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder()
                            .parse(new org.xml.sax.InputSource(new java.io.StringReader(osdResponse)));
                    org.w3c.dom.NodeList osdNodes = doc.getElementsByTagNameNS("*", "OSD");
                    if (osdNodes.getLength() > 0) {
                        org.w3c.dom.Element osdElem = (org.w3c.dom.Element) osdNodes.item(0);
                        osdToken = osdElem.getAttribute("token");

                        // 获取文字内容
                        org.w3c.dom.NodeList textNodes = osdElem.getElementsByTagNameNS("*", "PlainText");
                        if (textNodes.getLength() > 0) {
                            currentText = textNodes.item(0).getTextContent();
                        }

                        // 获取位置
                        org.w3c.dom.NodeList posNodes = osdElem.getElementsByTagNameNS("*", "Pos");
                        if (posNodes.getLength() > 0) {
                            org.w3c.dom.Element posElem = (org.w3c.dom.Element) posNodes.item(0);
                            String xStr = posElem.getAttribute("x");
                            String yStr = posElem.getAttribute("y");
                            if (xStr != null && !xStr.isEmpty()) currentX = Float.parseFloat(xStr);
                            if (yStr != null && !yStr.isEmpty()) currentY = Float.parseFloat(yStr);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析OSD响应失败: " + e.getMessage());
                }

                final String finalOsdToken = osdToken;
                final String finalVideoSourceToken = videoSourceToken;
                final String finalCurrentText = currentText;
                final float finalCurrentX = currentX;
                final float finalCurrentY = currentY;

                mainHandler.post(() -> {
                    loadingDialog.dismiss();
                    buildOsdDialog(device, finalOsdToken, finalVideoSourceToken,
                            finalCurrentText, finalCurrentX, finalCurrentY);
                });

            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "获取OSD配置异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, "获取OSD配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 构建OSD修改对话框UI
     */
    private void buildOsdDialog(OnvifDevice device, String osdToken, String videoSourceToken,
                                 String currentText, float currentX, float currentY) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);

        // 文字输入
        TextView textLabel = new TextView(this);
        textLabel.setText("OSD文字内容");
        textLabel.setTextSize(14);
        textLabel.setTextColor(getColor(android.R.color.tab_indicator_text));
        layout.addView(textLabel);

        android.widget.EditText textInput = new android.widget.EditText(this);
        textInput.setText(currentText);
        textInput.setHint("输入OSD显示文字");
        textInput.setSingleLine(true);
        textInput.setPadding(16, 12, 16, 12);
        layout.addView(textInput);

        // 位置X
        TextView xLabel = new TextView(this);
        xLabel.setText("水平位置 (-1.0左 ~ 1.0右)");
        xLabel.setTextSize(14);
        xLabel.setPadding(0, 16, 0, 0);
        xLabel.setTextColor(getColor(android.R.color.tab_indicator_text));
        layout.addView(xLabel);

        android.widget.EditText xInput = new android.widget.EditText(this);
        xInput.setText(String.format("%.1f", currentX));
        xInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        xInput.setSingleLine(true);
        xInput.setPadding(16, 12, 16, 12);
        layout.addView(xInput);

        // 位置Y
        TextView yLabel = new TextView(this);
        yLabel.setText("垂直位置 (-1.0下 ~ 1.0上)");
        yLabel.setTextSize(14);
        yLabel.setPadding(0, 16, 0, 0);
        yLabel.setTextColor(getColor(android.R.color.tab_indicator_text));
        layout.addView(yLabel);

        android.widget.EditText yInput = new android.widget.EditText(this);
        yInput.setText(String.format("%.1f", currentY));
        yInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        yInput.setSingleLine(true);
        yInput.setPadding(16, 12, 16, 12);
        layout.addView(yInput);

        // 提示信息
        TextView hintText = new TextView(this);
        hintText.setText("提示：若设备无OSD配置，将自动创建新的OSD");
        hintText.setTextSize(12);
        hintText.setPadding(0, 16, 0, 0);
        hintText.setTextColor(getColor(android.R.color.tab_indicator_text));
        layout.addView(hintText);

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改OSD - " + device.getDisplayName())
                .setView(layout)
                .setPositiveButton("应用", (dialog, which) -> {
                    String newText = textInput.getText().toString().trim();
                    float newX, newY;
                    try {
                        newX = Float.parseFloat(xInput.getText().toString().trim());
                        newY = Float.parseFloat(yInput.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的位置数值", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (newText.isEmpty()) {
                        Toast.makeText(this, "请输入OSD文字内容", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    applyOsdConfig(device, osdToken, videoSourceToken, newText, newX, newY);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 应用OSD配置到设备
     */
    private void applyOsdConfig(OnvifDevice device, String osdToken, String videoSourceToken,
                                 String text, float x, float y) {
        executorService.execute(() -> {
            try {
                String result;
                if (osdToken != null && !osdToken.isEmpty()) {
                    // 已有OSD，更新配置
                    result = OnvifUtil.setOSD(device.getServiceUrl(), device.getUsername(), device.getPassword(),
                            osdToken, videoSourceToken, text, x, y, null, "white");
                } else {
                    // 无OSD，创建新的
                    result = OnvifUtil.createOSD(device.getServiceUrl(), device.getUsername(), device.getPassword(),
                            videoSourceToken, text, x, y);
                }

                if (result != null && !result.contains("Fault")) {
                    mainHandler.post(() -> Toast.makeText(this, "OSD修改成功", Toast.LENGTH_SHORT).show());
                } else {
                    mainHandler.post(() -> Toast.makeText(this, "OSD修改失败，设备可能不支持此操作", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "应用OSD配置失败: " + e.getMessage(), e);
                mainHandler.post(() -> Toast.makeText(this, "OSD修改失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 显示RTSP预览对话框（选择主码流或子码流）
     *
     * 在预览视频流前，让用户选择：
     * - 主码流：高清画质，带宽占用较大
     * - 子码流：流畅画质，带宽占用较小
     *
     * @param device 设备对象（必须已认证）
     */
    private void showRtspPreviewDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] streamOptions = {"主码流 (高清)", "子码流 (流畅)"};
        
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择视频流")
                .setItems(streamOptions, (dialog, which) -> {
                    boolean useMainStream = (which == 0);
                    getStreamUriForPreview(device, useMainStream);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 获取RTSP流地址用于预览
     *
     * 获取设备的RTSP视频流地址，并跳转到RtspPlayerActivity播放。
     * 支持主码流和子码流切换。
     *
     * 实现流程：
     * 1. 调用GetProfiles获取所有Profile Token
     * 2. 主码流使用第一个 Token，子码流优先使用第二个 Token
     * 3. 处理RTSP URL（HTML解码、密码替换、添加默认凭据）
     * 4. 跳转到预览Activity
     *
     * @param device 设备对象
     * @param useMainStream true使用主码流，false使用子码流
     */
    private void getStreamUriForPreview(OnvifDevice device, boolean useMainStream) {
        scanStatusText.setText("正在获取" + (useMainStream ? "主码流" : "子码流") + "...");

        executorService.execute(() -> {
            try {
                // 步骤1: 获取Profiles
                String profilesResponse = OnvifUtil.getProfiles(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (profilesResponse == null) {
                    if (isFinishing() || isDestroyed()) return;
                    mainHandler.post(() -> {
                        scanStatusText.setText("获取Profiles失败");
                        Toast.makeText(OnvifActivity.this, "获取Profiles失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 步骤2: 解析Profile Token
                List<String> profileTokens = OnvifUtil.parseProfileTokens(profilesResponse);
                String profileToken;

                if (profileTokens.isEmpty()) {
                    // 如果没有解析到token，使用默认值
                    profileToken = "Profile_1";
                    Log.w(TAG, "未解析到Profile Token，使用默认值: " + profileToken);
                } else if (useMainStream || profileTokens.size() == 1) {
                    // 主码流使用第一个 Token；设备只有一个码流时也使用它
                    profileToken = profileTokens.get(0);
                    Log.d(TAG, "使用主码流 Profile Token: " + profileToken);
                } else {
                    // 子码流通常对应第二个 Profile
                    profileToken = profileTokens.get(1);
                    Log.d(TAG, "使用子码流 Profile Token: " + profileToken);
                }

                // 步骤3: 获取流URI
                String streamResponse = OnvifUtil.getStreamUri(
                        device.getServiceUrl(), profileToken, device.getUsername(), device.getPassword());

                if (streamResponse == null) {
                    if (isFinishing() || isDestroyed()) return;
                    mainHandler.post(() -> {
                        scanStatusText.setText("获取RTSP地址失败");
                        Toast.makeText(OnvifActivity.this, "获取RTSP地址失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 步骤4: 解析RTSP地址
                String rtspUrl = OnvifUtil.parseStreamUri(streamResponse);

                if (rtspUrl == null || rtspUrl.isEmpty()) {
                    if (isFinishing() || isDestroyed()) return;
                    mainHandler.post(() -> {
                        scanStatusText.setText("无法解析RTSP地址");
                        Toast.makeText(OnvifActivity.this, "无法解析RTSP地址", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 步骤5: 处理RTSP URL
                final String processedUrl = OnvifUtil.processRtspUrl(
                        rtspUrl, device.getUsername(), device.getPassword());

                if (isFinishing() || isDestroyed()) return;

                // 步骤6: 跳转到预览Activity
                mainHandler.post(() -> {
                    scanStatusText.setText("视频流获取成功");
                    try {
                        Intent intent = new Intent(OnvifActivity.this, RtspPlayerActivity.class);
                        // 传递处理后的RTSP地址
                        // 注意：当前只获取了一个流地址，传递到main_stream_url
                        // 如果需要主/子码流切换，需要分别获取两个Profile
                        intent.putExtra("main_stream_url", processedUrl);
                        // sub_stream_url 暂时为空，可以在后续实现中获取
                        intent.putExtra("sub_stream_url", "");
                        intent.putExtra("device_name", device.getDisplayName());
                        // 使用设备名称作为通道名，如果未设置则默认"通道1"
                        String chName = device.getName();
                        if (chName == null || chName.isEmpty()) {
                            chName = "通道1";
                        }
                        intent.putExtra("channel_name", chName);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "打开预览Activity失败: " + e.getMessage(), e);
                        Toast.makeText(OnvifActivity.this, "打开预览失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "获取RTSP流异常: " + e.getMessage(), e);
                if (isFinishing() || isDestroyed()) return;

                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(OnvifActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示设备功能菜单对话框
     *
     * 显示设备的可用功能列表，包括：
     * - 访问后台、修改OSD、预览视频流
     * - 获取/修改视频配置、修改网络配置
     * - 重启摄像头、免登录尝试重启、同步时间
     *
     * 如果设备已识别品牌，会在菜单顶部显示品牌信息。
     *
     * @param device 设备对象
     */
    private void showDeviceMenuDialog(OnvifDevice device) {
        // 构建菜单项
        java.util.ArrayList<String> menuItems = new java.util.ArrayList<>();
        
        // 检查是否有品牌信息
        boolean hasBrand = device.getBrand() != null && !device.getBrand().equals("未知品牌");
        
        // 添加品牌信息（如果已识别）
        if (hasBrand) {
            menuItems.add("品牌: " + device.getBrand());
        }
        
        menuItems.add("访问后台");
        menuItems.add("修改OSD");
        menuItems.add("预览视频流 (主/子码流)");
        menuItems.add("获取视频配置");
        menuItems.add("修改视频配置");
        menuItems.add("修改网络配置");
        menuItems.add("重启摄像头（需登录）");
        menuItems.add("免登录尝试重启");
        menuItems.add("同步时间");

        String[] menuArray = menuItems.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle("设备功能菜单 - " + device.getDisplayName())
                .setItems(menuArray, (dialog, which) -> {
                    // 根据点击的位置执行对应操作
                    if (hasBrand && which == 0) {
                        // 点击的是品牌信息项
                        Toast.makeText(this, "设备品牌: " + device.getBrand(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 计算实际功能索引（减去品牌项）
                    int actionIndex = hasBrand ? which - 1 : which;
                    
                    switch (actionIndex) {
                        case 0:
                            openDeviceWebBackend(device);
                            break;
                        case 1:
                            showOsdDialog(device);
                            break;
                        case 2:
                            showRtspPreviewDialog(device);
                            break;
                        case 3:
                            showGetVideoConfigDialogNew(device);
                            break;
                        case 4:
                            showVideoConfigDialog(device);
                            break;
                        case 5:
                            showNetworkConfigDialog(device);
                            break;
                        case 6:
                            showRestartDialog(device);
                            break;
                        case 7:
                            showAnonymousRestartDialog(device);
                            break;
                        case 8:
                            showSyncTimeDialog(device);
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 打开设备 Web 后台管理页面
     * 使用内置浏览器（WebViewActivity）访问设备的 HTTP 界面
     *
     * @param device 设备对象
     */
    private void openDeviceWebBackend(OnvifDevice device) {
        String url = "http://" + device.getIpAddress() + ":" + device.getPort();
        String title = device.getDisplayName() + " - 后台管理";

        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_URL, url);
        intent.putExtra(WebViewActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    /**
     * 构建SOAP Envelope（不含WS-Security头）
     * 认证通过HTTP Basic/Digest头处理
     */
    private String buildSoapEnvelope(String soapBody, String username, String password) {
        StringBuilder envelope = new StringBuilder();
        envelope.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        envelope.append("<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"\n");
        envelope.append("    xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"\n");
        envelope.append("    xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"\n");
        envelope.append("    xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n");
        // 注意: soapBody已包含<soap:Body>标签，直接追加
        envelope.append(soapBody).append("\n");
        envelope.append("</soap:Envelope>");

        return envelope.toString();
    }
    
    /**
     * 发送SOAP请求
     */
    private String sendSoapRequest(String serviceUrl, String envelope, String username, String password) {
        try {
            // 创建OkHttpClient实例（可以复用）
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .hostnameVerifier((hostname, session) -> true) // 忽略证书验证
                    .build();

            // 构造请求体
            RequestBody body = RequestBody.create(envelope, MediaType.parse("application/soap+xml; charset=utf-8"));

            // 构造请求
            Request request = new Request.Builder()
                    .url(serviceUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/soap+xml; charset=utf-8")
                    .build();

            // 添加认证头（如果提供了用户名和密码）
            if (username != null && password != null) {
                // 尝试Basic认证
                String credential = Credentials.basic(username, password);
                request = request.newBuilder()
                        .addHeader("Authorization", credential)
                        .build();
            }

            // 发送请求
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                return response.body().string();
            } else {
                Log.e(TAG, "SOAP请求失败，状态码: " + response.code());
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "发送SOAP请求失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * XML转义
     */
    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    /**
     * 显示获取视频配置对话框 - 显示当前设备的分辨率和编码格式信息
     *
     * @param device 设备对象
     */
    private void showGetVideoConfigDialogNew(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        scanStatusText.setText("正在获取视频配置...");

        executorService.execute(() -> {
            try {
                // 获取Profiles
                String profilesResponse = OnvifUtil.getProfiles(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (profilesResponse == null || profilesResponse.isEmpty()) {
                    mainHandler.post(() -> {
                        scanStatusText.setText("获取视频配置失败");
                        Toast.makeText(this, "获取视频配置失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 解析Profiles
                java.util.List<ProfileInfo> profiles = parseProfiles(profilesResponse);

                if (profiles.isEmpty()) {
                    mainHandler.post(() -> {
                        scanStatusText.setText("未找到视频配置");
                        Toast.makeText(this, "未找到视频配置", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 构建显示信息
                StringBuilder info = new StringBuilder();
                info.append("视频配置信息:\n\n");

                for (ProfileInfo profile : profiles) {
                    String streamType = "未知";
                    String nameLower = profile.name != null ? profile.name.toLowerCase() : "";
                    String tokenLower = profile.token != null ? profile.token.toLowerCase() : "";

                    if (nameLower.contains("sub") || tokenLower.contains("sub") ||
                        nameLower.contains("ext") || tokenLower.contains("ext")) {
                        streamType = "子码流";
                    } else {
                        streamType = "主码流";
                    }

                    info.append("【").append(streamType).append("】\n");
                    info.append("  Token: ").append(profile.token).append("\n");
                    info.append("  名称: ").append(profile.name != null ? profile.name : "N/A").append("\n");
                    info.append("  编码: ").append(profile.encoding != null && !profile.encoding.isEmpty() ? profile.encoding : "N/A").append("\n");
                    info.append("  分辨率: ").append(profile.resolution != null && !profile.resolution.isEmpty() ? profile.resolution : "N/A").append("\n\n");
                }

                final String displayInfo = info.toString();

                mainHandler.post(() -> {
                    scanStatusText.setText("视频配置获取成功");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("视频配置 - " + device.getDisplayName())
                            .setMessage(displayInfo)
                            .setPositiveButton("确定", null)
                            .show();
                });

            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "获取视频配置异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "获取视频配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * 显示修改视频配置对话框（天视通协议）
     *
     * 自动获取主码流和子码流的token，用户选择码流、编码格式和分辨率后应用配置。
     *
     * @param device 设备对象
     */
    private void showVideoConfigDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 创建主布局
            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            android.widget.LinearLayout mainLayout = new android.widget.LinearLayout(this);
            mainLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            mainLayout.setPadding(32, 24, 32, 24);
            scrollView.addView(mainLayout);

            // 加载提示
            TextView loadingText = new TextView(this);
            loadingText.setText("正在获取配置信息...");
            loadingText.setTextSize(14);
            loadingText.setTextColor(getColor(android.R.color.darker_gray));
            mainLayout.addView(loadingText);

            // 显示对话框
            androidx.appcompat.app.AlertDialog[] alertDialog = new androidx.appcompat.app.AlertDialog[1];

            MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
                    .setTitle("修改视频配置")
                    .setView(scrollView)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("应用配置", (dialog, which) -> {
                        // 这个会被自定义点击事件覆盖
                    });

            alertDialog[0] = dialogBuilder.create();
            alertDialog[0].show();

            // 获取Profiles和分辨率选项
            fetchVideoConfigOptions(device, loadingText, mainLayout, alertDialog);
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "打开视频配置对话框失败: " + e.getMessage(), e);
            Toast.makeText(this, "打开视频配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 获取视频配置选项（Profiles和分辨率）
     */
    private void fetchVideoConfigOptions(OnvifDevice device, TextView loadingText,
                                          android.widget.LinearLayout mainLayout,
                                          androidx.appcompat.app.AlertDialog[] alertDialogRef) {
        executorService.execute(() -> {
            try {
                AppErrorLogger.logDebug(TAG, "开始获取视频配置选项");

                // 步骤1: 获取Profiles
                String profilesResponse = OnvifUtil.getProfiles(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (profilesResponse == null || profilesResponse.isEmpty()) {
                    AppErrorLogger.logError(TAG, "获取Profiles响应为空");
                    mainHandler.post(() -> {
                        loadingText.setText("获取Profiles失败: 响应为空");
                        loadingText.setTextColor(getColor(android.R.color.holo_red_dark));
                    });
                    return;
                }

                AppErrorLogger.logDebug(TAG, "Profiles响应长度: " + profilesResponse.length());

                // 解析Profiles
                java.util.List<ProfileInfo> profiles = parseProfiles(profilesResponse);

                AppErrorLogger.logDebug(TAG, "解析到 " + profiles.size() + " 个 Profiles");

                if (profiles.isEmpty()) {
                    AppErrorLogger.logError(TAG, "未找到Profile，响应内容: " + profilesResponse.substring(0, Math.min(500, profilesResponse.length())));
                    mainHandler.post(() -> {
                        loadingText.setText("未找到Profile，请确认设备支持ONVIF Media服务");
                        loadingText.setTextColor(getColor(android.R.color.holo_red_dark));
                    });
                    return;
                }

                // 分离主码流和子码流
                java.util.List<ProfileInfo> mainProfiles = new java.util.ArrayList<>();
                java.util.List<ProfileInfo> subProfiles = new java.util.ArrayList<>();

                for (ProfileInfo profile : profiles) {
                    String name = profile.name.toLowerCase();
                    String token = profile.token.toLowerCase();
                    if (name.contains("sub") || token.contains("sub") || name.contains("ext") || token.contains("ext")) {
                        subProfiles.add(profile);
                    } else {
                        mainProfiles.add(profile);
                    }
                }

                // 保存设备信息供后续使用
                final java.util.List<ProfileInfo> finalMainProfiles = mainProfiles;
                final java.util.List<ProfileInfo> finalSubProfiles = subProfiles;

                mainHandler.post(() -> {
                    loadingText.setVisibility(View.GONE);
                    buildVideoConfigDialog(device, mainLayout, alertDialogRef,
                                          finalMainProfiles, finalSubProfiles);
                });

            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "获取视频配置选项失败: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    loadingText.setText("获取配置失败: " + e.getMessage());
                    loadingText.setTextColor(getColor(android.R.color.holo_red_dark));
                });
            }
        });
    }
    
    /**
     * Profile信息类
     */
    private static class ProfileInfo {
        String token;
        String name;
        String encoding;
        String resolution;
        String videoEncoderToken;

        ProfileInfo(String token, String name, String encoding, String resolution, String videoEncoderToken) {
            this.token = token;
            this.name = name;
            this.encoding = encoding;
            this.resolution = resolution;
            this.videoEncoderToken = videoEncoderToken;
        }
    }
    
    /**
     * 获取XML节点文本内容
     */
    private String getXmlNodeText(org.w3c.dom.Element parent, String tagName) {
        org.w3c.dom.NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName("tt:" + tagName);
        }
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return "";
    }

    /**
     * 解析Profiles响应
     *
     * 支持多种ONVIF设备品牌，包括天视通、海康、大华等
     * 命名空间可能是 trt:Profiles 或 tt:Profiles
     */
    private java.util.List<ProfileInfo> parseProfiles(String response) {
        java.util.List<ProfileInfo> profiles = new java.util.ArrayList<>();
        try {
            // 使用 XML DOM 解析
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(response)));

            // 尝试多种标签名查找Profiles
            org.w3c.dom.NodeList profileNodes = doc.getElementsByTagNameNS("*", "Profiles");

            if (profileNodes.getLength() == 0) {
                // 回退到带命名空间前缀的查找
                profileNodes = doc.getElementsByTagName("trt:Profiles");
            }
            if (profileNodes.getLength() == 0) {
                profileNodes = doc.getElementsByTagName("tt:Profiles");
            }
            if (profileNodes.getLength() == 0) {
                // 尝试不带命名空间的查找
                profileNodes = doc.getElementsByTagName("Profiles");
            }

            AppErrorLogger.logDebug(TAG, "找到 " + profileNodes.getLength() + " 个 Profiles 节点");

            for (int i = 0; i < profileNodes.getLength(); i++) {
                try {
                    org.w3c.dom.Element profile = (org.w3c.dom.Element) profileNodes.item(i);
                    String token = profile.getAttribute("token");
                    String name = profile.getAttribute("Name");

                    // 如果name为空，尝试获取子元素Name
                    if (name == null || name.isEmpty()) {
                        name = getXmlNodeText(profile, "Name");
                    }

                    String encoding = "";
                    String resolution = "";
                    String videoEncoderToken = "";

                    // 获取VideoEncoderConfiguration
                    org.w3c.dom.NodeList vecList = profile.getElementsByTagNameNS("*", "VideoEncoderConfiguration");
                    if (vecList.getLength() == 0) {
                        vecList = profile.getElementsByTagName("tt:VideoEncoderConfiguration");
                    }
                    if (vecList.getLength() == 0) {
                        vecList = profile.getElementsByTagName("VideoEncoderConfiguration");
                    }

                    if (vecList.getLength() > 0) {
                        org.w3c.dom.Element vec = (org.w3c.dom.Element) vecList.item(0);
                        // 获取VideoEncoderConfiguration token（SetVideoEncoderConfiguration需要此token，不是Profile token）
                        videoEncoderToken = vec.getAttribute("token");
                        if (videoEncoderToken == null) videoEncoderToken = "";
                        encoding = getXmlNodeText(vec, "Encoding");

                        // 尝试读取非标准编码字段（部分厂商使用自定义标签）
                        if (encoding.isEmpty() || encoding.equalsIgnoreCase("H264")) {
                            String codecType = getXmlNodeText(vec, "VideoCodecType");
                            if (!codecType.isEmpty()) {
                                // H.265 或 H.265+ 的非标准标识
                                if (codecType.contains("265") || codecType.contains("HEVC")) {
                                    encoding = "H265";
                                    // 检查是否有 + 标识（H265+ 智能编码）
                                    if (codecType.contains("+")) {
                                        encoding = "H265+";
                                    }
                                } else if (codecType.contains("264")) {
                                    encoding = "H264";
                                }
                            }
                        }

                        // HEVC 即 H265
                        if (encoding.equalsIgnoreCase("HEVC")) {
                            encoding = "H265";
                        }

                        // 尝试从 Profile 名称推断编码（天视通等部分厂商）
                        if (encoding.isEmpty() || encoding.equalsIgnoreCase("H264")) {
                            String profileNameLower = (name != null ? name.toLowerCase() : "");
                            String profileTokenLower = (token != null ? token.toLowerCase() : "");
                            if (profileNameLower.contains("h265") || profileTokenLower.contains("h265")) {
                                encoding = "H265";
                            }
                            if (profileNameLower.contains("h265+") || profileTokenLower.contains("h265+")) {
                                encoding = "H265+";
                            }
                        }

                        org.w3c.dom.NodeList resList = vec.getElementsByTagNameNS("*", "Resolution");
                        if (resList.getLength() == 0) {
                            resList = vec.getElementsByTagName("tt:Resolution");
                        }
                        if (resList.getLength() == 0) {
                            resList = vec.getElementsByTagName("Resolution");
                        }

                        if (resList.getLength() > 0) {
                            org.w3c.dom.Element res = (org.w3c.dom.Element) resList.item(0);
                            String width = getXmlNodeText(res, "Width");
                            String height = getXmlNodeText(res, "Height");
                            if (!width.isEmpty() && !height.isEmpty()) {
                                resolution = width + "x" + height;
                            }
                        }
                    }

                    AppErrorLogger.logDebug(TAG, "解析到Profile: token=" + token + ", name=" + name +
                                          ", encoding=" + encoding + ", resolution=" + resolution);
                    profiles.add(new ProfileInfo(token, name, encoding, resolution, videoEncoderToken));
                } catch (Exception e) {
                    AppErrorLogger.logWarning(TAG, "解析单个Profile节点失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "解析Profiles失败: " + e.getMessage(), e);
        }
        return profiles;
    }
    
    /**
     * 构建视频配置对话框内容
     */
    private void buildVideoConfigDialog(OnvifDevice device, android.widget.LinearLayout mainLayout,
                                        androidx.appcompat.app.AlertDialog[] alertDialogRef,
                                        java.util.List<ProfileInfo> mainProfiles,
                                        java.util.List<ProfileInfo> subProfiles) {
        mainLayout.removeAllViews();
        mainLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        
        // 码流选择
        TextView streamLabel = new TextView(this);
        streamLabel.setText("码流类型:");
        streamLabel.setTextSize(14);
        streamLabel.setTextColor(getColor(android.R.color.darker_gray));
        mainLayout.addView(streamLabel);
        
        Spinner streamSpinner = new Spinner(this);
        String[] streamOptions = {"主码流", "子码流"};
        android.widget.ArrayAdapter<String> streamAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, streamOptions);
        streamAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        streamSpinner.setAdapter(streamAdapter);
        mainLayout.addView(streamSpinner);
        
        // 编码格式选择（单选）
        TextView encodingLabel = new TextView(this);
        encodingLabel.setText("编码格式:");
        encodingLabel.setTextSize(14);
        encodingLabel.setPadding(0, 16, 0, 8);
        mainLayout.addView(encodingLabel);
        
        RadioGroup encodingGroup = new RadioGroup(this);
        encodingGroup.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int h264Id = View.generateViewId();
        int h265Id = View.generateViewId();
        
        RadioButton h264Btn = new RadioButton(this);
        h264Btn.setText("H264");
        h264Btn.setId(h264Id);
        h264Btn.setChecked(true);
        
        RadioButton h265Btn = new RadioButton(this);
        h265Btn.setText("H265");
        h265Btn.setId(h265Id);
        
        encodingGroup.addView(h264Btn);
        encodingGroup.addView(h265Btn);
        mainLayout.addView(encodingGroup);
        
        // 分辨率选择
        TextView resolutionLabel = new TextView(this);
        resolutionLabel.setText("分辨率:");
        resolutionLabel.setTextSize(14);
        resolutionLabel.setPadding(0, 16, 0, 8);
        mainLayout.addView(resolutionLabel);
        
        Spinner resolutionSpinner = new Spinner(this);
        mainLayout.addView(resolutionSpinner);
        
        // 当前配置显示
        TextView currentConfigText = new TextView(this);
        currentConfigText.setTextSize(12);
        currentConfigText.setTextColor(getColor(android.R.color.darker_gray));
        currentConfigText.setPadding(0, 16, 0, 0);
        mainLayout.addView(currentConfigText);
        
        // 保存控件引用用于后续更新
        final android.widget.Spinner[] resSpinnerRef = new android.widget.Spinner[1];
        resSpinnerRef[0] = resolutionSpinner;
        final android.widget.Spinner[] streamSpinnerRef = new android.widget.Spinner[1];
        streamSpinnerRef[0] = streamSpinner;
        final RadioGroup[] encodingGroupRef = new RadioGroup[1];
        encodingGroupRef[0] = encodingGroup;
        final TextView[] currentConfigRef = new TextView[1];
        currentConfigRef[0] = currentConfigText;
        final int[] h264IdRef = new int[1];
        h264IdRef[0] = h264Id;
        final int[] h265IdRef = new int[1];
        h265IdRef[0] = h265Id;
        
        // 编码格式切换监听
        encodingGroup.setOnCheckedChangeListener((group, checkedId) -> {
            try {
                // 获取当前编码格式
                String encoding = "未知";
                if (checkedId == h264Id) {
                    encoding = "H264";
                } else if (checkedId == h265Id) {
                    encoding = "H265";
                }
                
                // 获取当前分辨率
                String resolution = "未知";
                int resPos = resolutionSpinner.getSelectedItemPosition();
                if (resPos >= 0 && resPos < resolutionSpinner.getCount()) {
                    resolution = (String) resolutionSpinner.getItemAtPosition(resPos);
                }
                
                // 获取当前码流
                int streamPos = streamSpinner.getSelectedItemPosition();
                java.util.List<ProfileInfo> selectedProfiles = (streamPos == 0) ? mainProfiles : subProfiles;
                String token = "未知";
                if (!selectedProfiles.isEmpty()) {
                    token = selectedProfiles.get(0).token;
                }
                
                // 更新文本
                currentConfigText.setText("当前: Token=" + token + 
                                        ", 分辨率=" + resolution +
                                        ", 编码=" + encoding);
            } catch (Exception e) {
                // 忽略
            }
        });
        
        // 更新分辨率选项
        updateResolutionOptions(device, mainProfiles, subProfiles, streamSpinner, 
                               resolutionSpinner, currentConfigText, resSpinnerRef, encodingGroup, h264Id, h265Id);
        
        // 设置应用配置按钮
        if (alertDialogRef[0] != null) {
            alertDialogRef[0].setOnShowListener(dialog -> {
                androidx.appcompat.app.AlertDialog alertDialog = (androidx.appcompat.app.AlertDialog) alertDialogRef[0];
                alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            try {
                                int streamPos = streamSpinner.getSelectedItemPosition();
                                java.util.List<ProfileInfo> selectedProfiles = (streamPos == 0) ? mainProfiles : subProfiles;
                                
                                if (selectedProfiles.isEmpty()) {
                                    Toast.makeText(this, "未找到对应的码流", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                
                                ProfileInfo selectedProfile = selectedProfiles.get(0);
                                
                                // 获取编码格式
                                String encoding = "H264";
                                int encodingCheckedId = encodingGroup.getCheckedRadioButtonId();
                                if (encodingCheckedId != -1) {
                                    RadioButton rb = mainLayout.findViewById(encodingCheckedId);
                                    if (rb != null) {
                                        encoding = rb.getText().toString().toUpperCase();
                                    }
                                }
                                
                                // 获取分辨率
                                String resolution = selectedProfile.resolution;
                                int resPos = resolutionSpinner.getSelectedItemPosition();
                                if (resPos >= 0 && resPos < resolutionSpinner.getCount()) {
                                    resolution = (String) resolutionSpinner.getItemAtPosition(resPos);
                                }
                                
                                // 使用VideoEncoderConfiguration token（ONVIF规范要求使用配置token而非Profile token）
                                String videoEncoderToken = selectedProfile.videoEncoderToken;
                                if (videoEncoderToken == null || videoEncoderToken.isEmpty()) {
                                    // 回退到Profile token（部分设备两者相同）
                                    videoEncoderToken = selectedProfile.token;
                                }
                                applyVideoConfig(device, videoEncoderToken, encoding, resolution);
                                alertDialog.dismiss();
                            } catch (Exception e) {
                                AppErrorLogger.logError(TAG, "应用视频配置时发生错误: " + e.getMessage(), e);
                                Toast.makeText(this, "应用配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                ((androidx.appcompat.app.AlertDialog) alertDialogRef[0]).dismiss();
                            }
                        });
            });
        }
    }
    
    /**
     * 更新分辨率选项
     */
    private void updateResolutionOptions(OnvifDevice device,
                                         java.util.List<ProfileInfo> mainProfiles,
                                         java.util.List<ProfileInfo> subProfiles,
                                         Spinner streamSpinner,
                                         Spinner resolutionSpinner,
                                         TextView currentConfigText,
                                         android.widget.Spinner[] resSpinnerRef,
                                         RadioGroup encodingGroup,
                                         int h264Id,
                                         int h265Id) {
        // 创建监听器
        android.widget.AdapterView.OnItemSelectedListener listener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    java.util.List<ProfileInfo> selectedProfiles = (position == 0) ? mainProfiles : subProfiles;
                    
                    java.util.Set<String> resolutions = new java.util.HashSet<>();
                    for (ProfileInfo p : selectedProfiles) {
                        if (!p.resolution.isEmpty()) {
                            resolutions.add(p.resolution);
                        }
                    }
                    
                    // 添加常见分辨率（包括3MP和4MP）
                    resolutions.add("2560x1440");  // 4MP
                    resolutions.add("2304x1296");  // 3MP
                    resolutions.add("2048x1536");  // 3MP (4:3)
                    resolutions.add("1920x1080");
                    resolutions.add("1280x720");
                    resolutions.add("640x480");
                    resolutions.add("320x240");
                    
                    java.util.List<String> resList = new java.util.ArrayList<>(resolutions);
                    java.util.Collections.sort(resList, (a, b) -> {
                        int wA = Integer.parseInt(a.split("x")[0]);
                        int wB = Integer.parseInt(b.split("x")[0]);
                        return Integer.compare(wB, wA); // 降序
                    });
                    
                    android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                            OnvifActivity.this, android.R.layout.simple_spinner_item, resList);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    resolutionSpinner.setAdapter(adapter);
                    
                    // 更新当前配置显示（包含编码和分辨率）
                    updateCurrentConfigText(currentConfigText, streamSpinner, resolutionSpinner, 
                                          encodingGroup, h264Id, h265Id, mainProfiles, subProfiles);
                } catch (Exception e) {
                    AppErrorLogger.logError(TAG, "更新分辨率选项失败: " + e.getMessage(), e);
                }
            }
            
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        
        streamSpinner.setOnItemSelectedListener(listener);
        
        // 触发初始加载 - 手动调用一次更新
        updateCurrentConfigText(currentConfigText, streamSpinner, resolutionSpinner, 
                              encodingGroup, h264Id, h265Id, mainProfiles, subProfiles);
        
        // 添加分辨率选择监听，更新文本
        resolutionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    updateCurrentConfigText(currentConfigText, streamSpinner, resolutionSpinner, 
                                          encodingGroup, h264Id, h265Id, mainProfiles, subProfiles);
                } catch (Exception e) {
                    // 忽略
                }
            }
            
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }
    
    /**
     * 更新当前配置文本
     */
    private void updateCurrentConfigText(TextView currentConfigText,
                                        Spinner streamSpinner,
                                        Spinner resolutionSpinner,
                                        RadioGroup encodingGroup,
                                        int h264Id,
                                        int h265Id,
                                        java.util.List<ProfileInfo> mainProfiles,
                                        java.util.List<ProfileInfo> subProfiles) {
        try {
            // 获取当前编码格式
            String encoding = "未知";
            int checkedId = encodingGroup.getCheckedRadioButtonId();
            if (checkedId == h264Id) {
                encoding = "H264";
            } else if (checkedId == h265Id) {
                encoding = "H265";
            }
            
            // 获取当前分辨率
            String resolution = "未知";
            int resPos = resolutionSpinner.getSelectedItemPosition();
            if (resPos >= 0 && resPos < resolutionSpinner.getCount()) {
                resolution = (String) resolutionSpinner.getItemAtPosition(resPos);
            }
            
            // 获取当前码流
            int streamPos = streamSpinner.getSelectedItemPosition();
            java.util.List<ProfileInfo> selectedProfiles = (streamPos == 0) ? mainProfiles : subProfiles;
            String token = "未知";
            if (!selectedProfiles.isEmpty()) {
                token = selectedProfiles.get(0).token;
            }
            
            // 更新文本
            currentConfigText.setText("当前: Token=" + token + 
                                    ", 分辨率=" + resolution +
                                    ", 编码=" + encoding);
        } catch (Exception e) {
            // 忽略
        }
    }
    
    /**
     * 应用视频配置到设备（ONVIF标准协议）
     *
     * @param device 设备对象
     * @param configToken VideoEncoderConfiguration Token（不是Profile token）
     * @param encoding 编码格式（H264/H265）
     * @param resolution 分辨率（如1920x1080）
     */
    private void applyVideoConfig(OnvifDevice device, String configToken, String encoding, String resolution) {
        AppErrorLogger.logDebug(TAG, "应用视频配置 - 配置Token: " + configToken +
                               ", 编码: " + encoding + ", 分辨率: " + resolution);

        new MaterialAlertDialogBuilder(this)
                .setTitle("应用配置")
                .setMessage("确定要应用以下配置吗？\n\n" +
                        "配置Token: " + configToken + "\n" +
                        "编码: " + encoding + "\n" +
                        "分辨率: " + resolution)
                .setPositiveButton("确定", (dialog, which) -> {
                    AppErrorLogger.logDebug(TAG, "用户确认应用视频配置");
                    scanStatusText.setText("正在应用视频配置...");

                    executorService.execute(() -> {
                        try {
                            AppErrorLogger.logDebug(TAG, "开始调用OnvifUtil.setVideoEncoderConfiguration");

                            // 注意：视频配置必须发送到 media_service，不是 device_service！
                            // 解析分辨率
                            int width = 1920, height = 1080;
                            if (resolution != null && resolution.contains("x")) {
                                String[] parts = resolution.split("x");
                                try {
                                    width = Integer.parseInt(parts[0].trim());
                                    height = Integer.parseInt(parts[1].trim());
                                } catch (Exception ignored) {}
                            }
                            
                            // 调用正确的9参数重载方法
                            String response = OnvifUtil.setVideoEncoderConfiguration(
                                    device.getMediaServiceUrl(),
                                    device.getUsername(),
                                    device.getPassword(),
                                    configToken,  // 配置Token
                                    width,        // 宽度
                                    height,       // 高度
                                    4096,         // 码率
                                    25,           // 帧率
                                    encoding);    // 编码格式

                            AppErrorLogger.logDebug(TAG, "OnvifUtil.setVideoEncoderConfiguration返回: " +
                                   (response == null ? "null" : "长度=" + response.length()));

                            if (response != null && !response.isEmpty()) {
                                // 检查是否包含 SOAP Fault 错误
                                String error = OnvifUtil.parseSoapFault(response);
                                if (error != null && !error.isEmpty()) {
                                    AppErrorLogger.logWarning(TAG, "视频配置应用失败，设备返回错误: " + error);
                                    mainHandler.post(() -> {
                                        scanStatusText.setText("应用视频配置失败");
                                        new MaterialAlertDialogBuilder(this)
                                                .setTitle("配置失败")
                                                .setMessage("设备返回错误:\n\n" + error + "\n\n可能原因:\n1. 设备不支持修改此配置\n2. 用户名密码权限不足\n3. 配置Token不正确")
                                                .setPositiveButton("确定", null)
                                                .show();
                                    });
                                } else {
                                    AppErrorLogger.logDebug(TAG, "视频配置应用成功");
                                    mainHandler.post(() -> {
                                        scanStatusText.setText("视频配置应用成功");
                                        new MaterialAlertDialogBuilder(this)
                                                .setTitle("配置结果")
                                                .setMessage("编码: " + encoding + "\n分辨率: " + resolution +
                                                           "\n配置Token: " + configToken + "\n\n配置已成功应用，可能需要几秒生效。")
                                                .setPositiveButton("确定", null)
                                                .show();
                                    });
                                }
                            } else {
                                AppErrorLogger.logWarning(TAG, "视频配置应用失败，响应为空");
                                mainHandler.post(() -> {
                                    scanStatusText.setText("应用配置失败（响应为空）");
                                    Toast.makeText(this, "应用配置失败，设备可能不支持此操作", Toast.LENGTH_LONG).show();
                                });
                            }
                        } catch (Exception e) {
                            AppErrorLogger.logError(TAG, "应用视频配置异常: " + e.getMessage(), e);
                            mainHandler.post(() -> {
                                scanStatusText.setText("错误: " + e.getMessage());
                                Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }


    /**
     * 显示重启摄像头对话框
     *
     * 弹出确认对话框，警告用户设备将暂时断开连接。
     *
     * @param device 设备对象
     */
    private void showRestartDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备；如摄像头允许匿名访问，可使用“免登录尝试重启”",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("重启摄像头")
                .setMessage("确定要重启此设备吗？\n\n设备将在重启过程中暂时断开连接。")
                .setPositiveButton("重启", (dialog, which) -> {
                    executeRestart(device);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 免登录重启：仅发送不带凭据的 ONVIF SystemReboot 请求。
     * 标准 ONVIF 默认要求认证，只有显式允许匿名访问的摄像头才可能接受。
     */
    private void showAnonymousRestartDialog(OnvifDevice device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("免登录尝试重启")
                .setMessage("将不携带用户名和密码，直接向设备发送 ONVIF 重启请求。\n\n"
                        + "注意：大多数摄像头会拒绝匿名请求（401/NotAuthorized），"
                        + "只有主动关闭 ONVIF 认证的设备才可能成功。\n\n确定继续吗？")
                .setPositiveButton("发送", (dialog, which) -> executeAnonymousRestart(device))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示同步时间对话框
     *
     * 先获取设备当前时间显示给用户，确认后执行时间同步。
     *
     * @param device 设备对象
     */
    private void showSyncTimeDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        // 先获取设备当前时间
        scanStatusText.setText("正在获取设备时间...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.getSystemDateAndTime(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (response == null) {
                    mainHandler.post(() -> {
                        scanStatusText.setText("获取设备时间失败");
                        Toast.makeText(this, "获取设备时间失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                final String currentTimeInfo = OnvifUtil.parseSystemDateAndTime(response);

                if (isFinishing() || isDestroyed()) return;

                mainHandler.post(() -> {
                    scanStatusText.setText("设备时间获取成功");
                    // 显示当前设备时间，并询问是否同步
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("同步时间")
                            .setMessage("设备当前时间:\n" + currentTimeInfo +
                                    "\n\n确定要将设备时间与当前系统时间同步吗？")
                            .setPositiveButton("同步", (dialog, which) -> {
                                executeSyncTime(device);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "获取设备时间异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 执行时间同步 - 将设备时间设置为当前系统时间
     *
     * @param device 设备对象
     */
    private void executeSyncTime(OnvifDevice device) {
        scanStatusText.setText("正在同步时间...");

        executorService.execute(() -> {
            try {
                // 获取当前系统UTC时间
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                String utcTime = sdf.format(new java.util.Date());

                String response = OnvifUtil.setSystemDateAndTime(
                        device.getServiceUrl(), device.getUsername(), device.getPassword(), utcTime);

                if (response != null) {
                    String error = OnvifUtil.parseSoapFault(response);
                    if (error != null) {
                        final String errorMsg = error;
                        mainHandler.post(() -> {
                            scanStatusText.setText("时间同步失败");
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("同步失败")
                                    .setMessage("时间同步失败:\n\n" + errorMsg)
                                    .setPositiveButton("确定", null)
                                    .show();
                        });
                    } else {
                        mainHandler.post(() -> {
                            scanStatusText.setText("时间同步成功");
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("同步成功")
                                    .setMessage("设备时间已同步为:\n" + utcTime)
                                    .setPositiveButton("确定", null)
                                    .show();
                        });
                    }
                } else {
                    mainHandler.post(() -> {
                        scanStatusText.setText("时间同步失败（响应为空）");
                        Toast.makeText(this, "时间同步失败，设备可能不支持此操作", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "时间同步异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示修改网络配置对话框
     *
     * @param device 设备对象
     */
    private void showNetworkConfigDialog(OnvifDevice device) {
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_onvif_network_config, null);

        EditText ipEdit = dialogView.findViewById(R.id.ipEdit);
        EditText subnetMaskEdit = dialogView.findViewById(R.id.subnetMaskEdit);
        EditText gatewayEdit = dialogView.findViewById(R.id.gatewayEdit);
        EditText dnsEdit = dialogView.findViewById(R.id.dnsEdit);
        androidx.appcompat.widget.AppCompatCheckBox dhcpCheckBox = dialogView.findViewById(R.id.dhcpCheckBox);

        // 预填当前设备IP
        ipEdit.setText(device.getIpAddress());

        // DHCP开关监听
        dhcpCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ipEdit.setEnabled(!isChecked);
            subnetMaskEdit.setEnabled(!isChecked);
            gatewayEdit.setEnabled(!isChecked);
            dnsEdit.setEnabled(!isChecked);
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改网络配置")
                .setView(dialogView)
                .setPositiveButton("应用配置", (dialog, which) -> {
                    boolean useDhcp = dhcpCheckBox.isChecked();
                    String ip = ipEdit.getText().toString().trim();
                    String subnetMask = subnetMaskEdit.getText().toString().trim();
                    String gateway = gatewayEdit.getText().toString().trim();
                    String dns = dnsEdit.getText().toString().trim();

                    if (!useDhcp && (ip.isEmpty() || subnetMask.isEmpty() || gateway.isEmpty())) {
                        Toast.makeText(this, "请填写完整的静态IP信息", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    applyNetworkConfig(device, useDhcp, ip, subnetMask, gateway, dns);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 应用网络配置
     */
    private void applyNetworkConfig(OnvifDevice device, boolean useDhcp, String ip, String subnetMask, String gateway, String dns) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("应用网络配置")
                .setMessage("确定要应用以下网络配置吗？\n\n" +
                        "DHCP: " + (useDhcp ? "开启" : "关闭") + "\n" +
                        (useDhcp ? "" : "IP地址: " + ip + "\n" +
                        "子网掩码: " + subnetMask + "\n" +
                        "网关: " + gateway + "\n" +
                        "DNS: " + (dns.isEmpty() ? "自动" : dns)) +
                        "\n\n注意：应用配置后设备将重启，IP地址可能会变化，需要重新扫描设备。")
                .setPositiveButton("确定", (dialog, which) -> {
                    scanStatusText.setText("正在应用网络配置...");

                    executorService.execute(() -> {
                        try {
                            // 调用OnvifUtil设置网络配置
                            String response = OnvifUtil.setNetworkConfiguration(
                                    device.getServiceUrl(),
                                    device.getUsername(),
                                    device.getPassword(),
                                    useDhcp,
                                    ip,
                                    subnetMask,
                                    gateway,
                                    dns);

                            if (response != null && !response.isEmpty()) {
                                // 检查是否包含错误
                                String error = OnvifUtil.parseSoapFault(response);
                                if (error != null && !error.isEmpty()) {
                                    mainHandler.post(() -> {
                                        scanStatusText.setText("应用网络配置失败: " + error);
                                        new MaterialAlertDialogBuilder(this)
                                                .setTitle("配置失败")
                                                .setMessage("设备返回错误:\n\n" + error + "\n\n可能原因:\n1. 设备不支持网络配置修改\n2. 用户名密码权限不足\n3. InterfaceToken 不匹配")
                                                .setPositiveButton("确定", null)
                                                .show();
                                    });
                                } else {
                                    mainHandler.post(() -> {
                                        scanStatusText.setText("网络配置应用成功，设备正在重启");
                                        new MaterialAlertDialogBuilder(this)
                                                .setTitle("配置成功")
                                                .setMessage("网络配置已成功应用，设备正在重启。\n\n请等待设备重启完成后重新扫描。")
                                                .setPositiveButton("确定", null)
                                                .show();
                                    });
                                }
                            } else {
                                mainHandler.post(() -> {
                                    // 网络配置变更时，设备可能重启网络接口导致响应丢失，但不代表配置失败
                                    scanStatusText.setText("配置已发送，设备正在重启应用...");
                                    new MaterialAlertDialogBuilder(this)
                                            .setTitle("配置已发送")
                                            .setMessage("网络配置已发送至设备，设备可能正在重启应用配置。\n\n请等待30-60秒后重新扫描设备。\n\n注意：如果IP地址已更改，请使用新IP重新扫描。")
                                            .setPositiveButton("确定", null)
                                            .show();
                                });
                            }
                        } catch (Exception e) {
                            AppErrorLogger.logError(TAG, "应用网络配置异常: " + e.getMessage(), e);
                            mainHandler.post(() -> {
                                // 网络配置变更时连接断开是预期行为
                                scanStatusText.setText("配置已发送，设备正在重启应用...");
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle("配置已发送")
                                        .setMessage("网络配置已发送至设备。\n\n由于修改网络配置会导致设备重启，连接已断开。\n请等待30-60秒后重新扫描设备。\n\n注意：如果IP地址已更改，请使用新IP重新扫描。")
                                        .setPositiveButton("确定", null)
                                        .show();
                            });
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 获取视频编码器配置
     *
     * 获取指定Profile Token的视频编码配置，包括编码格式、
     * 分辨率、帧率、码率等参数。
     *
     * @param device 设备对象
     * @param profileToken Profile Token
     */
    private void getVideoEncoderConfiguration(OnvifDevice device, String profileToken) {
        scanStatusText.setText("正在获取视频配置...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.getVideoEncoderConfiguration(
                        device.getServiceUrl(), profileToken, device.getUsername(), device.getPassword());

                if (response != null) {
                    mainHandler.post(() -> {
                        scanStatusText.setText("视频配置获取成功");
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("视频编码器配置")
                                .setMessage(response)
                                .setPositiveButton("确定", null)
                                .show();
                    });
                } else {
                    mainHandler.post(() -> {
                        scanStatusText.setText("视频配置获取失败");
                        Toast.makeText(this, "视频配置获取失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 获取图像设置
     *
     * 获取设备的图像参数设置，包括亮度、饱和度、对比度、
     * 锐度和白平衡模式等。
     *
     * @param device 设备对象
     */
    private void getImagingSettings(OnvifDevice device) {
        scanStatusText.setText("正在获取图像设置...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.getImagingSettings(
                        device.getServiceUrl(), "VideoSourceToken", device.getUsername(), device.getPassword());

                if (response != null) {
                    String parsedResult = OnvifUtil.parseImagingSettings(response);
                    
                    mainHandler.post(() -> {
                        scanStatusText.setText("图像设置获取成功");
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("图像设置")
                                .setMessage(parsedResult)
                                .setPositiveButton("确定", null)
                                .show();
                    });
                } else {
                    mainHandler.post(() -> {
                        scanStatusText.setText("图像设置获取失败");
                        Toast.makeText(this, "图像设置获取失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 获取视频源
     *
     * 获取设备的视频源信息，包括帧率和分辨率等。
     *
     * @param device 设备对象
     */
    private void getVideoSources(OnvifDevice device) {
        scanStatusText.setText("正在获取视频源...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.getVideoSources(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (response != null) {
                    mainHandler.post(() -> {
                        scanStatusText.setText("视频源获取成功");
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("视频源")
                                .setMessage(response)
                                .setPositiveButton("确定", null)
                                .show();
                    });
                } else {
                    mainHandler.post(() -> {
                        scanStatusText.setText("视频源获取失败");
                        Toast.makeText(this, "视频源获取失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 获取灯光状态
     *
     * 获取设备灯光的状态，包括是否启用、模式和强度。
     *
     * @param device 设备对象
     */
    private void getLighting(OnvifDevice device) {
        scanStatusText.setText("正在获取灯光状态...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.getLighting(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (response != null) {
                    String parsedResult = OnvifUtil.parseLighting(response);
                    
                    mainHandler.post(() -> {
                        scanStatusText.setText("灯光状态获取成功");
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("灯光状态")
                                .setMessage(parsedResult)
                                .setPositiveButton("确定", null)
                                .show();
                    });
                } else {
                    mainHandler.post(() -> {
                        scanStatusText.setText("灯光状态获取失败");
                        Toast.makeText(this, "灯光状态获取失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 执行重启设备命令
     *
     * 通过ONVIF SystemReboot命令重启设备。
     * 设备在重启过程中会暂时断开连接。
     *
     * @param device 设备对象
     */
    private void executeRestart(OnvifDevice device) {
        scanStatusText.setText("正在重启设备...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.setRestart(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());
                boolean success = OnvifUtil.isSystemRebootSuccess(response);
                String fault = null;
                if (response != null && !response.trim().isEmpty()) {
                    fault = OnvifUtil.parseSoapFault(response);
                }
                final boolean finalSuccess = success;
                final String finalFault = fault;

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (finalSuccess) {
                        scanStatusText.setText("设备重启命令已发送");
                        Toast.makeText(this, "设备正在重启，请稍后重新扫描", Toast.LENGTH_LONG).show();
                    } else {
                        String message = finalFault != null ? finalFault
                                : "设备未响应或认证失败";
                        scanStatusText.setText("重启失败: " + message);
                        Toast.makeText(this, "重启失败: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "重启设备异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    scanStatusText.setText("重启失败: " + e.getMessage());
                    Toast.makeText(this, "重启失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 发送不带认证信息的重启请求。
     */
    private void executeAnonymousRestart(OnvifDevice device) {
        scanStatusText.setText("正在发送免登录重启请求...");

        executorService.execute(() -> {
            try {
                String response = OnvifUtil.systemRebootAnonymous(device.getServiceUrl());
                boolean success = OnvifUtil.isSystemRebootSuccess(response);
                String fault = null;
                if (response != null && !response.trim().isEmpty()) {
                    fault = OnvifUtil.parseSoapFault(response);
                }
                final boolean finalSuccess = success;
                final String finalFault = fault;

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (finalSuccess) {
                        scanStatusText.setText("免登录重启请求已发送");
                        Toast.makeText(this, "设备正在重启，请稍后重新扫描", Toast.LENGTH_LONG).show();
                    } else {
                        String message = finalFault != null ? finalFault
                                : "设备拒绝匿名访问或未响应";
                        scanStatusText.setText("免登录重启失败: " + message);
                        Toast.makeText(this,
                                "免登录重启失败，设备要求登录认证", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "免登录重启异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    scanStatusText.setText("免登录重启失败: " + e.getMessage());
                    Toast.makeText(this, "免登录重启失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 获取RTSP流地址
     *
     * 获取设备的RTSP视频流地址并显示在对话框中。
     * 用户可以预览、复制或分享RTSP地址。
     *
     * 实现流程：
     * 1. 检查设备认证状态
     * 2. 调用GetProfiles获取Profile Token
     * 3. 调用GetStreamUri获取RTSP地址
     * 4. 处理RTSP URL
     * 5. 显示对话框
     *
     * @param device 设备对象
     */
    private void getStreamUri(OnvifDevice device) {
        // 如果设备未认证，先弹出认证对话框
        if (!device.isAuthenticated()) {
            Toast.makeText(this, "请先认证设备", Toast.LENGTH_SHORT).show();
            showAuthDialog(device);
            return;
        }

        scanStatusText.setText("正在获取视频流地址...");

        executorService.execute(() -> {
            try {
                // 步骤1: 获取Profiles
                String profilesResponse = OnvifUtil.getProfiles(
                        device.getServiceUrl(), device.getUsername(), device.getPassword());

                if (profilesResponse == null) {
                    if (isFinishing() || isDestroyed()) return;
                    mainHandler.post(() -> {
                        scanStatusText.setText("获取Profiles失败");
                        Toast.makeText(OnvifActivity.this, "获取Profiles失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 步骤2: 解析Profile Token
                List<String> profileTokens = OnvifUtil.parseProfileTokens(profilesResponse);
                String profileToken;

                if (profileTokens.isEmpty()) {
                    profileToken = "Profile_1";
                    Log.w(TAG, "未解析到Profile Token，使用默认值: " + profileToken);
                } else {
                    profileToken = profileTokens.get(0);
                    Log.d(TAG, "使用Profile Token: " + profileToken);
                }

                // 步骤3: 获取流URI
                String streamResponse = OnvifUtil.getStreamUri(
                        device.getServiceUrl(), profileToken, device.getUsername(), device.getPassword());

                if (streamResponse == null) {
                    if (isFinishing() || isDestroyed()) return;
                    mainHandler.post(() -> {
                        scanStatusText.setText("获取RTSP地址失败");
                        Toast.makeText(OnvifActivity.this, "获取RTSP地址失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 步骤4: 解析并处理RTSP地址
                String parsedRtspUrl = OnvifUtil.parseStreamUri(streamResponse);

                if (parsedRtspUrl == null || parsedRtspUrl.isEmpty()) {
                    if (isFinishing() || isDestroyed()) return;
                    mainHandler.post(() -> {
                        scanStatusText.setText("无法解析RTSP地址");
                        Toast.makeText(OnvifActivity.this, "无法解析RTSP地址", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 处理RTSP URL
                final String rtspUrl = OnvifUtil.processRtspUrl(
                        parsedRtspUrl, device.getUsername(), device.getPassword());
                device.setRtspUrl(rtspUrl);

                // 步骤5: 显示对话框
                if (isFinishing() || isDestroyed()) return;

                mainHandler.post(() -> {
                    adapter.updateDevice(device);
                    scanStatusText.setText("RTSP地址获取成功");
                    showRtspUrlDialog(device, rtspUrl);
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "获取RTSP流异常: " + e.getMessage(), e);
                if (isFinishing() || isDestroyed()) return;

                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(OnvifActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示RTSP地址对话框
     *
     * 显示获取到的RTSP流地址，提供三个操作：
     * - 预览：跳转到RtspPlayerActivity播放视频
     * - 复制：复制RTSP地址到剪贴板
     * - 关闭：关闭对话框
     *
     * @param device 设备对象
     * @param rtspUrl RTSP流地址
     */
    private void showRtspUrlDialog(OnvifDevice device, String rtspUrl) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_rtsp_url, null);

        TextView urlText = dialogView.findViewById(R.id.urlText);
        urlText.setText(rtspUrl);
        urlText.setTextIsSelectable(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle("RTSP流地址")
                .setView(dialogView)
                .setPositiveButton("预览", (dialog, which) -> {
                    // 跳转到RtspPlayerActivity使用RtspPlayer播放RTSP流
                    try {
                        Intent intent = new Intent(this, RtspPlayerActivity.class);
                        intent.putExtra("main_stream_url", rtspUrl);
                        intent.putExtra("device_name", device.getDisplayName());
                        // 传递通道名称
                        String chName = device.getName();
                        if (chName == null || chName.isEmpty()) {
                            chName = "通道1";
                        }
                        intent.putExtra("channel_name", chName);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "打开预览失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText(
                            "RTSP URL", rtspUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 初始化RTSP播放器
     */
    private void initRtspPlayer() {
        // 播放器初始化在开始播放时进行
    }

    /**
     * 开始RTSP播放
     */
    private void startRtspPlayback() {
        String rtspUrl = rtspUrlInput.getText().toString().trim();
        if (rtspUrl.isEmpty()) {
            Toast.makeText(this, "请输入RTSP地址", Toast.LENGTH_SHORT).show();
            appendLog("播放失败: RTSP地址为空", "ERROR");
            return;
        }
        
        appendLog("开始播放: " + rtspUrl, "INFO");
        appendLog("使用传输协议: " + currentTransportProtocol, "INFO");
        
        // 显示播放器容器
        rtspPlayerContainer.setVisibility(View.VISIBLE);
        rtspLoadingProgress.setVisibility(View.VISIBLE);
        rtspStatusText.setText("正在连接...");
        
        // 显示控制栏
        showControls();
        
        // 如果已有播放器，先停止
        if (nodePlayer != null) {
            stopRtspPlayback();
        }
        
        try {
            // 创建RtspPlayerView
            videoView = new RtspPlayerView(this);
            videoView.setRenderType(RtspPlayerView.RenderType.SURFACEVIEW);
            videoView.setUIViewContentMode(RtspPlayerView.UIViewContentMode.ScaleToFill);
            
            // 清除旧视图并添加新视图
            videoViewContainer.removeAllViews();
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            );
            videoViewContainer.addView(videoView, 0, layoutParams);
            
            // 创建RtspPlayer实例
            nodePlayer = new RtspPlayer(this);
            nodePlayer.setPlayerView(videoView);
            
            // 设置传输协议
            setTransportProtocol(nodePlayer);
            
            nodePlayer.setInputUrl(rtspUrl);
            nodePlayer.setVideoEnable(true);
            nodePlayer.setBufferTime(500);
            nodePlayer.setMaxBufferTime(3000);
            nodePlayer.setVolume(100);
            
            // 设置事件回调
            nodePlayer.setDelegate(new RtspPlayer.Delegate() {
                @Override
                public void onEventCallback(RtspPlayer player, int event, String msg) {
                    runOnUiThread(() -> handleRtspPlayerEvent(event, msg));
                }
            });
            
            // 开始播放
            nodePlayer.start();
            isPlaying = true;
            appendLog("播放器已启动 (" + currentTransportProtocol + ")", "INFO");
            
        } catch (Exception e) {
            String errorMsg = "播放器初始化失败: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            rtspStatusText.setText(errorMsg);
            rtspLoadingProgress.setVisibility(View.GONE);
            appendLog(errorMsg, "ERROR");
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 设置传输协议
     */
    private void setTransportProtocol(RtspPlayer player) {
        switch (currentTransportProtocol) {
            case "TCP":
                player.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_TCP);
                break;
            case "UDP":
                player.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_UDP);
                break;
            case "UDP_MULTICAST":
                player.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_UDP_MULTICAST);
                break;
            case "HTTP":
                player.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_HTTP);
                break;
            default:
                player.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_TCP);
                break;
        }
    }

    /**
     * 处理RTSP播放器事件
     */
    private void handleRtspPlayerEvent(int event, String msg) {
        switch (event) {
            case 1001: // 连接中
                rtspStatusText.setText("正在连接...");
                showStatusOverlay("连接中...");
                appendLog("播放器: 连接中", "INFO");
                break;
            case 1101: // 已连接
                rtspStatusText.setText("已连接到服务器");
                appendLog("播放器: 已连接", "INFO");
                break;
            case 1104: // 开始播放
                rtspStatusText.setText("正在播放");
                isPlaying = true;
                updatePlayPauseIcon();
                rtspLoadingProgress.setVisibility(View.GONE);
                hideStatusOverlay();
                appendLog("播放器: 开始播放", "INFO");
                // 更新分辨率显示
                if (videoWidth > 0 && videoHeight > 0) {
                    rtspResolutionText.setText(videoWidth + "x" + videoHeight);
                }
                break;
            case 1004: // 停止播放
                rtspStatusText.setText("已停止播放");
                isPlaying = false;
                updatePlayPauseIcon();
                appendLog("播放器: 停止播放", "INFO");
                break;
            case 1000: // 错误
                String errorMsg = "播放错误: " + msg;
                Log.e(TAG, errorMsg);
                rtspStatusText.setText(errorMsg);
                isPlaying = false;
                updatePlayPauseIcon();
                rtspLoadingProgress.setVisibility(View.GONE);
                appendLog(errorMsg, "ERROR");
                break;
            default:
                Log.d(TAG, "未知事件: " + event);
                break;
        }
    }

    /**
     * 切换播放/暂停
     */
    private void togglePlayPause() {
        if (nodePlayer == null) return;
        
        try {
            if (isPlaying) {
                nodePlayer.pause();
                isPlaying = false;
                showStatusOverlay("已暂停");
                appendLog("播放器: 暂停", "INFO");
            } else {
                nodePlayer.start();
                isPlaying = true;
                showStatusOverlay("继续播放");
                appendLog("播放器: 继续播放", "INFO");
            }
            updatePlayPauseIcon();
            resetHideTimer();
        } catch (Exception e) {
            Log.e(TAG, "切换播放状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新播放/暂停图标
     */
    private void updatePlayPauseIcon() {
        if (rtspPlayPauseBtn != null) {
            if (isPlaying) {
                rtspPlayPauseBtn.setImageResource(R.drawable.ic_pause);
            } else {
                rtspPlayPauseBtn.setImageResource(R.drawable.ic_play);
            }
        }
    }

    /**
     * 切换全屏（Onvif预览不需要全屏功能）
     */
    private void toggleFullscreen() {
        Toast.makeText(this, "Onvif预览不支持全屏，请使用工具RTSP预览", Toast.LENGTH_SHORT).show();
    }

    /**
     * 显示/隐藏控制栏
     */
    private void toggleControls() {
        if (isControlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    /**
     * 显示控制栏
     */
    private void showControls() {
        isControlsVisible = true;
        if (rtspTopBar != null) {
            rtspTopBar.setVisibility(View.VISIBLE);
        }
        if (rtspBottomBar != null) {
            rtspBottomBar.setVisibility(View.VISIBLE);
        }
        resetHideTimer();
    }

    /**
     * 隐藏控制栏
     */
    private void hideControls() {
        isControlsVisible = false;
        if (rtspTopBar != null) {
            rtspTopBar.setVisibility(View.GONE);
        }
        if (rtspBottomBar != null) {
            rtspBottomBar.setVisibility(View.GONE);
        }
        hideHandler.removeCallbacks(hideRunnable);
    }

    /**
     * 重置隐藏定时器
     */
    private void resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.postDelayed(hideRunnable, HIDE_DELAY);
    }

    /**
     * 显示状态覆盖层
     */
    private void showStatusOverlay(String message) {
        if (rtspStatusOverlay != null) {
            rtspStatusOverlay.setText(message);
            rtspStatusOverlay.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 隐藏状态覆盖层
     */
    private void hideStatusOverlay() {
        if (rtspStatusOverlay != null) {
            rtspStatusOverlay.setVisibility(View.GONE);
        }
    }

    /**
     * 停止RTSP播放
     */
    private void stopRtspPlayback() {
        // 写入logcat，不依赖UI
        Log.i(TAG, "停止播放");
        
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
                nodePlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败: " + e.getMessage(), e);
            }
            nodePlayer = null;
        }
        
        if (videoView != null && videoViewContainer != null) {
            try {
                videoViewContainer.removeView(videoView);
            } catch (Exception e) {
                // 视图可能已经detach
            }
            videoView = null;
        }
        
        isPlaying = false;
        isControlsVisible = false;
        isFullscreen = false;
        hideHandler.removeCallbacksAndMessages(null);
        
        // 安全隐藏视图（在onDestroy中可能为null）
        try {
            if (rtspPlayerContainer != null) rtspPlayerContainer.setVisibility(View.GONE);
            if (rtspLoadingProgress != null) rtspLoadingProgress.setVisibility(View.GONE);
            if (rtspTopBar != null) rtspTopBar.setVisibility(View.GONE);
            if (rtspBottomBar != null) rtspBottomBar.setVisibility(View.GONE);
            if (rtspStatusText != null) rtspStatusText.setText("准备播放");
        } catch (Exception e) {
            Log.w(TAG, "隐藏视图异常: " + e.getMessage());
        }
    }

    /**
     * 仅写入 logcat，不落盘、不展示 UI 日志
     */
    private void appendLog(String message, String level) {
        // 写入logcat
        switch (level) {
            case "ERROR":
                Log.e(TAG, message);
                break;
            case "WARN":
                Log.w(TAG, message);
                break;
            default:
                Log.i(TAG, message);
                break;
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

    @Override
    protected void onPause() {
        super.onPause();
        // ExoPlayer 支持多实例，无需特殊处理
        // 避免与随后打开的 RtspPlayerActivity 争用同一个 native 实例。
        if (nodePlayer != null) {
            stopRtspPlayback();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止RTSP播放
        stopRtspPlayback();
        hideHandler.removeCallbacksAndMessages(null);
        // 立即关闭线程池，取消所有后台任务
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

}
