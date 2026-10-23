package com.networktool.app.activity.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.player.RtspPlayer;
import com.networktool.app.player.RtspPlayerView;
import com.networktool.app.adapter.SadpDeviceAdapter;
import com.networktool.app.model.SadpDevice;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.DebugLogger;
import com.networktool.app.util.ThemeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Credentials;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * SADP调试Activity
 * 
 * 海康威视设备搜索和管理工具
 * 使用SADP协议扫描局域网内的海康设备
 * 支持获取RTSP预览视频、修改视频配置、重启设备、同步时间等功能
 * 
 * 注意: RTSP预览功能已移至ONVIF调试页面
 */
public class SadpActivity extends AppCompatActivity {

    private static final String TAG = "SadpActivity";

    // Tab相关
    private TabLayout tabLayout;
    private LinearLayout deviceTabContent;
    private LinearLayout rtspPreviewTabContent;

    // UI组件
    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private View emptyView;
    private MaterialButton scanButton;
    private ProgressBar scanProgressBar;
    private TextView scanStatusText;
    private TextView deviceCountText;

    // RTSP预览相关
    private TextInputEditText rtspUrlInput;
    private TextInputEditText deviceNameInput;
    private RadioGroup transportProtocolGroup;
    private MaterialButton rtspPlayButton;
    private MaterialButton rtspStopButton;
    private FrameLayout rtspPlayerContainer;
    private FrameLayout videoViewContainer;
    private ProgressBar rtspLoadingProgress;
    private TextView rtspStatusText;

    // 播放器
    private RtspPlayer nodePlayer;
    private String currentTransportProtocol = "TCP";
    private boolean isPlaying = false;

    // 适配器和数据
    private SadpDeviceAdapter adapter;
    private List<SadpDevice> deviceList = new ArrayList<>();

    // 线程管理
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isScanning = false;
    
    // HTTP客户端
    private OkHttpClient okHttpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sadp);

        setupStatusBar();

        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化OkHttpClient
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        initViews();
        setupToolbar();
        setupRecyclerView();
        setupTabLayout();
        initRtspPlayer();
    }

    private void setupStatusBar() {
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("SADP调试");
        }
        
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        tabLayout = findViewById(R.id.tabLayout);
        deviceTabContent = findViewById(R.id.deviceTabContent);
        rtspPreviewTabContent = findViewById(R.id.rtspPreviewTabContent);
        
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        scanButton = findViewById(R.id.scanButton);
        scanProgressBar = findViewById(R.id.scanProgressBar);
        scanStatusText = findViewById(R.id.scanStatusText);
        deviceCountText = findViewById(R.id.deviceCountText);

        // RTSP相关
        rtspUrlInput = findViewById(R.id.rtspUrlInput);
        deviceNameInput = findViewById(R.id.deviceNameInput);
        transportProtocolGroup = findViewById(R.id.transportProtocolGroup);
        rtspPlayButton = findViewById(R.id.rtspPlayButton);
        rtspStopButton = findViewById(R.id.rtspStopButton);
        rtspPlayerContainer = findViewById(R.id.rtspPlayerContainer);
        videoViewContainer = findViewById(R.id.videoViewContainer);
        rtspLoadingProgress = findViewById(R.id.rtspLoadingProgress);
        rtspStatusText = findViewById(R.id.rtspStatusText);

        ThemeManager.setupResultCard(findViewById(R.id.infoCard), this);
        ThemeManager.setupResultCard(findViewById(R.id.scanCard), this);
        ThemeManager.applyPrimaryButton(scanButton);
        ThemeManager.applyPrimaryButton(rtspPlayButton);
        ThemeManager.applyPrimaryButton(rtspStopButton);
        scanStatusText.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));

        scanButton.setOnClickListener(v -> startScan());
        rtspPlayButton.setOnClickListener(v -> startRtspPlayback());
        rtspStopButton.setOnClickListener(v -> stopRtspPlayback());
        
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
        });
    }

    private void setupTabLayout() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    deviceTabContent.setVisibility(View.VISIBLE);
                    rtspPreviewTabContent.setVisibility(View.GONE);
                } else {
                    deviceTabContent.setVisibility(View.GONE);
                    rtspPreviewTabContent.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void initRtspPlayer() {
        // 播放器初始化在开始播放时进行
    }

    private void setupRecyclerView() {
        adapter = new SadpDeviceAdapter(new SadpDeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onDeviceClick(SadpDevice device) {
                showDeviceDetails(device);
            }

            @Override
            public void onDeviceMenu(SadpDevice device) {
                showDeviceMenuDialog(device);
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    /**
     * 开始扫描SADP设备
     * 
     * 使用海康威视SADP协议，通过UDP多播搜索设备
     * 关键点：
     * 1. 使用临时 UDP Socket 发送 SADP 多播探测，接收设备的单播响应
     * 2. 探测消息发送到 239.255.255.250:37020
     * 3. 解析XML响应中的DeviceSN等字段
     */
    private void startScan() {
        if (isScanning) return;

        isScanning = true;
        scanButton.setEnabled(false);
        scanProgressBar.setVisibility(View.VISIBLE);
        scanStatusText.setText("正在扫描局域网内的海康设备...");
        deviceList.clear();
        adapter.setDevices(deviceList);

        executorService.execute(() -> {
            DatagramSocket socket = null;
            try {
                // 使用临时 UDP 端口发送多播探测，设备会以单播响应回来源端口
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(500);
                InetAddress multicastGroup = InetAddress.getByName("239.255.255.250");
                
                // 构建SADP探测消息（XML格式）
                String xmlMessage = 
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                    "<Probe>\r\n" +
                    "<Uuid>" + java.util.UUID.randomUUID().toString() + "</Uuid>\r\n" +
                    "<Types>inquiry</Types>\r\n" +
                    "</Probe>";
                
                byte[] probeData = xmlMessage.getBytes(StandardCharsets.UTF_8);
                
                // 发送到多播地址
                DatagramPacket sendPacket = new DatagramPacket(probeData, probeData.length, multicastGroup, 37020);
                socket.send(sendPacket);
                Log.d(TAG, "SADP探测已发送");

                // 等待500ms后再次发送
                Thread.sleep(500);
                socket.send(sendPacket);
                Log.d(TAG, "SADP探测已重发");
                
                // 接收响应
                int deviceCount = 0;
                long startTime = System.currentTimeMillis();
                long scanTimeout = 5000; // 5秒超时
                
                // 用于去重的序列号集合
                java.util.Set<String> seenSerialNumbers = new java.util.HashSet<>();
                
                byte[] receiveBuffer = new byte[8192];
                
                while ((System.currentTimeMillis() - startTime) < scanTimeout && deviceCount < 100) {
                    try {
                        DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        socket.receive(receivePacket);
                        
                        String responseStr = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                        String senderIp = receivePacket.getAddress().getHostAddress();
                        
                        // 只处理私有IP
                        if (!isPrivateIP(senderIp)) {
                            continue;
                        }
                        
                        // 检查是否是SADP响应（包含DeviceSN）
                        if (!responseStr.contains("DeviceSN")) {
                            continue;
                        }
                        
                        Log.d(TAG, "收到SADP响应，来自: " + senderIp);
                        
                        // 解析XML字段
                        String serialNumber = extractXmlValue(responseStr, "DeviceSN");
                        if (serialNumber == null || serialNumber.isEmpty()) {
                            continue;
                        }
                        
                        // 去重
                        if (seenSerialNumbers.contains(serialNumber)) {
                            continue;
                        }
                        seenSerialNumbers.add(serialNumber);
                        
                        // 构建设备对象
                        SadpDevice device = new SadpDevice();
                        device.setSerialNumber(serialNumber);
                        device.setIpAddress(senderIp);
                        device.setModel(extractXmlValue(responseStr, "DeviceModel"));
                        device.setMacAddress(extractXmlValue(responseStr, "MacAddress"));
                        
                        // 解析端口
                        String portStr = extractXmlValue(responseStr, "DevicePort");
                        try {
                            device.setPort(Integer.parseInt(portStr));
                        } catch (NumberFormatException e) {
                            device.setPort(80);
                        }
                        
                        device.setOnline(true);
                        
                        final SadpDevice finalDevice = device;
                        mainHandler.post(() -> {
                            adapter.addDevice(finalDevice);
                            deviceCountText.setText(adapter.getItemCount() + " 个设备");
                        });
                        deviceCount++;
                        
                        Log.d(TAG, "发现设备: IP=" + senderIp + ", 序列号=" + serialNumber);
                        
                    } catch (java.net.SocketTimeoutException e) {
                        // 超时，继续循环
                    }
                }

                final int finalCount = deviceCount;
                mainHandler.post(() -> {
                    if (finalCount == 0) {
                        scanStatusText.setText("未发现海康设备");
                        emptyView.setVisibility(View.VISIBLE);
                        recyclerView.setVisibility(View.GONE);
                    } else {
                        scanStatusText.setText("发现 " + finalCount + " 个海康设备");
                        emptyView.setVisibility(View.GONE);
                        recyclerView.setVisibility(View.VISIBLE);
                        Toast.makeText(SadpActivity.this, "发现 " + finalCount + " 个设备", Toast.LENGTH_SHORT).show();
                    }
                    scanProgressBar.setVisibility(View.GONE);
                    scanButton.setEnabled(true);
                    isScanning = false;
                });

            } catch (Exception e) {
                Log.e(TAG, "SADP扫描异常: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    scanStatusText.setText("扫描失败: " + e.getMessage());
                    scanProgressBar.setVisibility(View.GONE);
                    scanButton.setEnabled(true);
                    isScanning = false;
                    Toast.makeText(SadpActivity.this, "扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        });
    }
    
    /**
     * 判断是否是私有IP
     */
    private boolean isPrivateIP(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        return ip.startsWith("10.") ||
               ip.startsWith("192.168.") ||
               ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    /**
     * 从XML响应中提取值
     */
    private String extractXmlValue(String content, String tagName) {
        if (content == null || tagName == null) return "";
        
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";
        
        int startIndex = content.indexOf(startTag);
        if (startIndex == -1) return "";
        
        startIndex += startTag.length();
        int endIndex = content.indexOf(endTag, startIndex);
        if (endIndex == -1) return "";
        
        return content.substring(startIndex, endIndex).trim();
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * 显示设备详情
     */
    private void showDeviceDetails(SadpDevice device) {
        StringBuilder info = new StringBuilder();
        info.append("设备型号: ").append(device.getModel() != null ? device.getModel() : "未知").append("\n");
        info.append("IP地址: ").append(device.getIpAddress()).append("\n");
        info.append("端口: ").append(device.getPort()).append("\n");
        info.append("序列号: ").append(device.getSerialNumber() != null ? device.getSerialNumber() : "未知").append("\n");
        info.append("MAC地址: ").append(device.getMacAddress() != null ? device.getMacAddress() : "未知").append("\n");
        info.append("固件版本: ").append(device.getFirmwareVersion() != null ? device.getFirmwareVersion() : "未知").append("\n");
        info.append("通道数: ").append(device.getChannelCount()).append("\n");
        info.append("认证状态: ").append(device.isAuthenticated() ? "已认证" : "未认证");

        if (device.getRtspUrl() != null) {
            info.append("\n\nRTSP地址:\n").append(device.getRtspUrl());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("设备详情")
                .setMessage(info.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * 显示设备功能菜单
     */
    private void showDeviceMenuDialog(SadpDevice device) {
        String[] menuItems = {
            "获取设备信息",
            "获取RTSP预览视频",
            "修改视频配置",
            "重启设备",
            "同步时间",
            "修改设备密码",
            "复制IP地址"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("设备功能菜单 - " + device.getDisplayName())
                .setItems(menuItems, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            getDeviceInfo(device);
                            break;
                        case 1:
                            getRtspUrl(device);
                            break;
                        case 2:
                            showVideoConfigDialog(device);
                            break;
                        case 3:
                            showRestartDialog(device);
                            break;
                        case 4:
                            showSyncTimeDialog(device);
                            break;
                        case 5:
                            showChangePasswordDialog(device);
                            break;
                        case 6:
                            copyToClipboard(device.getIpAddress(), "IP地址");
                            Toast.makeText(this, "已复制IP地址", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 获取设备信息
     */
    private void getDeviceInfo(SadpDevice device) {
        if (!device.isAuthenticated()) {
            showAuthDialog(device, () -> getDeviceInfo(device));
            return;
        }

        scanStatusText.setText("正在获取设备信息...");
        
        executorService.execute(() -> {
            StringBuilder info = new StringBuilder();
            
            try {
                // 获取设备基本信息
                String deviceInfoUrl = "http://" + device.getIpAddress() + "/ISAPI/System/deviceInfo";
                String response = sendHttpGet(deviceInfoUrl, device.getUsername(), device.getPassword());
                
                if (response != null) {
                    info.append("=== 设备基本信息 ===\n");
                    info.append("设备型号: ").append(extractXmlValue(response, "model")).append("\n");
                    info.append("序列号: ").append(extractXmlValue(response, "serialNumber")).append("\n");
                    info.append("固件版本: ").append(extractXmlValue(response, "firmwareVersion")).append("\n");
                    info.append("MAC地址: ").append(extractXmlValue(response, "macAddress")).append("\n");
                }
                
                // 获取网络配置
                String networkUrl = "http://" + device.getIpAddress() + "/ISAPI/System/Network/interfaces/1";
                response = sendHttpGet(networkUrl, device.getUsername(), device.getPassword());
                
                if (response != null) {
                    String ipAddress = extractXmlValue(response, "ipAddress");
                    String subnetMask = extractXmlValue(response, "subnetMask");
                    String defaultGateway = extractXmlValue(response, "defaultGateway");
                    
                    info.append("\n=== 网络配置 ===\n");
                    info.append("IP地址: ").append(ipAddress).append("\n");
                    info.append("子网掩码: ").append(subnetMask).append("\n");
                    info.append("默认网关: ").append(defaultGateway).append("\n");
                    
                    // 更新设备对象的网络配置
                    if (ipAddress != null && !ipAddress.isEmpty()) {
                        device.setIpAddress(ipAddress);
                    }
                    if (subnetMask != null && !subnetMask.isEmpty()) {
                        device.setSubnetMask(subnetMask);
                    }
                    if (defaultGateway != null && !defaultGateway.isEmpty()) {
                        device.setGateway(defaultGateway);
                    }
                }
                
                // 获取视频通道
                String channelsUrl = "http://" + device.getIpAddress() + "/ISAPI/Streaming/channels";
                response = sendHttpGet(channelsUrl, device.getUsername(), device.getPassword());
                
                if (response != null) {
                    info.append("\n=== 视频通道 ===\n");
                    // 统计通道数量
                    int count = 0;
                    int pos = 0;
                    while ((pos = response.indexOf("<id>", pos)) != -1) {
                        count++;
                        pos += 4;
                    }
                    info.append("通道数量: ").append(count > 0 ? count : "未知").append("\n");
                }
                
                final String finalInfo = info.length() > 0 ? info.toString() : "获取设备信息失败";
                mainHandler.post(() -> {
                    scanStatusText.setText("设备信息获取成功");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("设备详情 - " + device.getDisplayName())
                            .setMessage(finalInfo)
                            .setPositiveButton("确定", null)
                            .show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("获取失败: " + e.getMessage());
                    Toast.makeText(this, "获取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示修改密码对话框
     */
    private void showChangePasswordDialog(SadpDevice device) {
        if (!device.isAuthenticated()) {
            showAuthDialog(device, () -> showChangePasswordDialog(device));
            return;
        }

        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_change_password, null);
        
        EditText oldPasswordEdit = dialogView.findViewById(R.id.oldPasswordEdit);
        EditText newPasswordEdit = dialogView.findViewById(R.id.newPasswordEdit);
        EditText confirmPasswordEdit = dialogView.findViewById(R.id.confirmPasswordEdit);

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改设备密码")
                .setView(dialogView)
                .setPositiveButton("确定", (dialog, which) -> {
                    String oldPassword = oldPasswordEdit.getText().toString().trim();
                    String newPassword = newPasswordEdit.getText().toString().trim();
                    String confirmPassword = confirmPasswordEdit.getText().toString().trim();
                    
                    if (oldPassword.isEmpty() || newPassword.isEmpty()) {
                        Toast.makeText(this, "请填写完整", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    if (!newPassword.equals(confirmPassword)) {
                        Toast.makeText(this, "两次输入的新密码不一致", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    changePassword(device, oldPassword, newPassword);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 修改设备密码
     */
    private void changePassword(SadpDevice device, String oldPassword, String newPassword) {
        scanStatusText.setText("正在修改密码...");
        
        executorService.execute(() -> {
            try {
                // 海康ISAPI修改密码
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<User xmlns=\"http://www.hikvision.com/2011/IPCD\" version=\"2.0\">\n" +
                        "  <id>1</id>\n" +
                        "  <userName>" + escapeXml(device.getUsername()) + "</userName>\n" +
                        "  <password>" + escapeXml(newPassword) + "</password>\n" +
                        "  <oldPassword>" + escapeXml(oldPassword) + "</oldPassword>\n" +
                        "</User>";
                
                String url = "http://" + device.getIpAddress() + "/ISAPI/Security/users/1";
                String response = sendHttpPut(url, device.getUsername(), oldPassword, xml);
                
                mainHandler.post(() -> {
                    if (response != null) {
                        device.setPassword(newPassword);
                        device.setAuthenticated(true);
                        scanStatusText.setText("密码修改成功");
                        Toast.makeText(this, "密码已修改", Toast.LENGTH_SHORT).show();
                    } else {
                        scanStatusText.setText("密码修改失败");
                        Toast.makeText(this, "密码修改失败，请检查旧密码", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     */
    private void getRtspUrl(SadpDevice device) {
        if (!device.isAuthenticated()) {
            showAuthDialog(device, () -> getRtspUrl(device));
            return;
        }

        scanStatusText.setText("正在获取RTSP地址...");
        
        executorService.execute(() -> {
            try {
                // 使用海康ISAPI协议获取RTSP地址
                String url = "http://" + device.getIpAddress() + "/ISAPI/Streaming/Channels/101";
                String response = sendHttpGet(url, device.getUsername(), device.getPassword());
                
                if (response != null) {
                    // 解析RTSP地址
                    String rtspUrl = extractRtspUrl(response, device);
                    final String finalUrl = rtspUrl != null ? rtspUrl : 
                            "rtsp://" + device.getUsername() + ":" + device.getPassword() + 
                            "@" + device.getIpAddress() + ":554/Streaming/Channels/101";
                    
                    mainHandler.post(() -> {
                        device.setRtspUrl(finalUrl);
                        scanStatusText.setText("RTSP地址获取成功");
                        showRtspUrlDialog(device, finalUrl);
                    });
                } else {
                    // 使用默认RTSP格式
                    final String defaultUrl = "rtsp://" + device.getUsername() + ":" + 
                            device.getPassword() + "@" + device.getIpAddress() + ":554/Streaming/Channels/101";
                    
                    mainHandler.post(() -> {
                        device.setRtspUrl(defaultUrl);
                        scanStatusText.setText("使用默认RTSP格式");
                        showRtspUrlDialog(device, defaultUrl);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("获取失败: " + e.getMessage());
                    Toast.makeText(this, "获取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String extractRtspUrl(String response, SadpDevice device) {
        // 从ISAPI响应中提取RTSP URL
        int start = response.indexOf("<TransportURL>");
        if (start == -1) return null;
        start += "<TransportURL>".length();
        int end = response.indexOf("</TransportURL>", start);
        if (end == -1) return null;
        
        String url = response.substring(start, end).trim();
        // 替换为实际的用户名密码
        url = url.replace("${username}", device.getUsername());
        url = url.replace("${password}", device.getPassword());
        return url;
    }

    private void showRtspUrlDialog(SadpDevice device, String rtspUrl) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_rtsp_url, null);
        
        TextView urlText = dialogView.findViewById(R.id.urlText);
        urlText.setText(rtspUrl);
        urlText.setTextIsSelectable(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle("RTSP流地址")
                .setView(dialogView)
                .setPositiveButton("预览", (dialog, which) -> {
                    // 直接进入 RTSP 播放器，并传入设备信息
                    try {
                        Intent intent = new Intent(this, RtspPlayerActivity.class);
                        intent.putExtra("main_stream_url", rtspUrl);
                        intent.putExtra("sub_stream_url", "");
                        intent.putExtra("device_name", device.getDisplayName());
                        intent.putExtra("channel_name", "通道1");
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "打开预览失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("复制", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("RTSP URL", rtspUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 显示认证对话框
     */
    private void showAuthDialog(SadpDevice device, Runnable onAuthSuccess) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_onvif_auth, null);
        
        EditText usernameEdit = dialogView.findViewById(R.id.usernameEdit);
        EditText passwordEdit = dialogView.findViewById(R.id.passwordEdit);
        
        // 默认使用admin
        usernameEdit.setText("admin");

        new MaterialAlertDialogBuilder(this)
                .setTitle("设备认证")
                .setMessage("请输入海康设备的用户名和密码")
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
                    validateCredentials(device, onAuthSuccess);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 先用 ISAPI deviceInfo 验证凭据，避免错误凭据被标记为已认证。
     */
    private void validateCredentials(SadpDevice device, Runnable onAuthSuccess) {
        scanStatusText.setText("正在验证设备凭据...");
        executorService.execute(() -> {
            String response = sendHttpGet(
                    "http://" + device.getIpAddress() + "/ISAPI/System/deviceInfo",
                    device.getUsername(), device.getPassword());
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (response != null) {
                    device.setAuthenticated(true);
                    scanStatusText.setText("设备认证成功");
                    Toast.makeText(this, "认证成功", Toast.LENGTH_SHORT).show();
                    if (onAuthSuccess != null) {
                        onAuthSuccess.run();
                    }
                } else {
                    device.setAuthenticated(false);
                    scanStatusText.setText("认证失败，请检查用户名和密码");
                    Toast.makeText(this, "认证失败，请检查用户名和密码", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * 显示修改视频配置对话框
     */
    private void showVideoConfigDialog(SadpDevice device) {
        if (!device.isAuthenticated()) {
            showAuthDialog(device, () -> showVideoConfigDialog(device));
            return;
        }

        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_video_config, null);
        
        Spinner streamSpinner = dialogView.findViewById(R.id.streamSpinner);
        RadioGroup encodingGroup = dialogView.findViewById(R.id.encodingGroup);
        Spinner resolutionSpinner = dialogView.findViewById(R.id.resolutionSpinner);
        
        // 填充码流选项
        String[] streamOptions = {"主码流", "子码流"};
        android.widget.ArrayAdapter<String> streamAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, streamOptions);
        streamSpinner.setAdapter(streamAdapter);
        
        // 填充分辨率选项
        String[] resolutions = {"1920x1080", "1280x720", "640x480", "320x240"};
        android.widget.ArrayAdapter<String> resAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, resolutions);
        resolutionSpinner.setAdapter(resAdapter);

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改视频配置")
                .setView(dialogView)
                .setPositiveButton("应用", (dialog, which) -> {
                    String encoding = "H264";
                    int checkedId = encodingGroup.getCheckedRadioButtonId();
                    if (checkedId != -1) {
                        RadioButton rb = dialogView.findViewById(checkedId);
                        if (rb != null) {
                            encoding = rb.getText().toString();
                        }
                    }
                    
                    String resolution = (String) resolutionSpinner.getSelectedItem();
                    int streamIndex = streamSpinner.getSelectedItemPosition();
                    applyVideoConfig(device, encoding, resolution, streamIndex);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 应用视频配置（使用海康ISAPI标准格式）
     */
    private void applyVideoConfig(SadpDevice device, String encoding, String resolution, int streamIndex) {
        scanStatusText.setText("正在应用视频配置...");
        
        executorService.execute(() -> {
            try {
                String[] parts = resolution.split("x");
                int width = Integer.parseInt(parts[0]);
                int height = Integer.parseInt(parts[1]);
                String channelId = streamIndex == 1 ? "102" : "101";
                
                // 获取当前编码配置
                String getUrl = "http://" + device.getIpAddress() + "/ISAPI/Streaming/Channels/" + channelId;
                String currentConfig = sendHttpGet(getUrl, device.getUsername(), device.getPassword());
                
                // 构建符合海康ISAPI标准的XML
                String xml;
                if (currentConfig != null && currentConfig.contains("<VideoEncoderConfiguration>")) {
                    // 如果获取到当前配置，基于当前配置修改
                    xml = updateVideoEncoderConfig(currentConfig, encoding, width, height);
                } else {
                    // 使用标准配置格式
                    xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                            "<VideoEncoderConfiguration xmlns=\"http://www.hikvision.com/2011/IPCD\" version=\"2.0\">\n" +
                            "  <id>1</id>\n" +
                            "  <enabled>true</enabled>\n" +
                            "  <VideoEncoderConfiguration>\n" +
                            "    <VideoCodecType>" + ("H265".equalsIgnoreCase(encoding) ? "H.265" : "H.264") + "</VideoCodecType>\n" +
                            "    <videoResolutionWidth>" + width + "</videoResolutionWidth>\n" +
                            "    <videoResolutionHeight>" + height + "</videoResolutionHeight>\n" +
                            "    <videoFrameRateType>fullFrameRate</videoFrameRateType>\n" +
                            "    <videoQualityLevel>4</videoQualityLevel>\n" +
                            "    <bitrateLimit>4096</bitrateLimit>\n" +
                            "    <maxFrameRate>25</maxFrameRate>\n" +
                            "    <keyFrameInterval>50</keyFrameInterval>\n" +
                            "    <snapShotImageQuality>2</snapShotImageQuality>\n" +
                            "  </VideoEncoderConfiguration>\n" +
                            "</VideoEncoderConfiguration>";
                }
                
                String response = sendHttpPut(getUrl, device.getUsername(), device.getPassword(), xml);
                
                mainHandler.post(() -> {
                    if (response != null) {
                        scanStatusText.setText("配置应用成功");
                        Toast.makeText(this, "配置已应用: " + encoding + " " + resolution, Toast.LENGTH_SHORT).show();
                    } else {
                        scanStatusText.setText("配置应用失败");
                        Toast.makeText(this, "配置应用失败，请检查网络连接", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * 更新视频编码器配置（保留其他设置）
     */
    private String updateVideoEncoderConfig(String originalXml, String encoding, int width, int height) {
        // 替换编码类型
        String updated = originalXml.replaceAll("<VideoCodecType>[^<]+</VideoCodecType>", 
                "<VideoCodecType>" + ("H265".equalsIgnoreCase(encoding) ? "H.265" : "H.264") + "</VideoCodecType>");
        // 替换宽度
        updated = updated.replaceAll("<videoResolutionWidth>[^<]+</videoResolutionWidth>", 
                "<videoResolutionWidth>" + width + "</videoResolutionWidth>");
        // 替换高度
        updated = updated.replaceAll("<videoResolutionHeight>[^<]+</videoResolutionHeight>", 
                "<videoResolutionHeight>" + height + "</videoResolutionHeight>");
        return updated;
    }

    /**
     * 显示重启设备对话框
     */
    private void showRestartDialog(SadpDevice device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("重启设备")
                .setMessage("确定要重启此设备吗？\n\n设备将在重启过程中暂时断开连接。")
                .setPositiveButton("重启", (dialog, which) -> {
                    executeRestart(device);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 执行重启设备（使用海康ISAPI标准格式）
     */
    private void executeRestart(SadpDevice device) {
        if (!device.isAuthenticated()) {
            showAuthDialog(device, () -> executeRestart(device));
            return;
        }

        scanStatusText.setText("正在重启设备...");
        
        executorService.execute(() -> {
            try {
                // 使用海康标准重启XML
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<reboot xmlns=\"http://www.hikvision.com/2011/IPCD\" version=\"2.0\">\n" +
                        "</reboot>";
                
                String url = "http://" + device.getIpAddress() + "/ISAPI/System/reboot";
                String response = sendHttpPut(url, device.getUsername(), device.getPassword(), xml);
                
                mainHandler.post(() -> {
                    if (response != null) {
                        scanStatusText.setText("设备重启命令已发送");
                        Toast.makeText(this, "设备正在重启，请稍候", Toast.LENGTH_LONG).show();
                    } else {
                        scanStatusText.setText("重启失败，请检查认证信息或设备支持");
                        Toast.makeText(this, "重启请求失败", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("重启失败: " + e.getMessage());
                    Toast.makeText(this, "重启失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 显示同步时间对话框
     */
    private void showSyncTimeDialog(SadpDevice device) {
        if (!device.isAuthenticated()) {
            showAuthDialog(device, () -> showSyncTimeDialog(device));
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("同步时间")
                .setMessage("确定要将设备时间与当前系统时间同步吗？")
                .setPositiveButton("同步", (dialog, which) -> {
                    syncDeviceTime(device);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 同步设备时间（使用海康ISAPI标准格式）
     */
    private void syncDeviceTime(SadpDevice device) {
        scanStatusText.setText("正在同步时间...");
        
        executorService.execute(() -> {
            try {
                // 获取当前时间
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+08:00");
                String currentTime = sdf.format(new java.util.Date());
                
                // 构建符合海康ISAPI标准的时间设置请求
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<TimeZone xmlns=\"http://www.hikvision.com/2011/IPCD\" version=\"2.0\">\n" +
                        "  <Time>" + currentTime + "</Time>\n" +
                        "  <timeZone>8</timeZone>\n" +
                        "</TimeZone>";
                
                String url = "http://" + device.getIpAddress() + "/ISAPI/System/time";
                String response = sendHttpPut(url, device.getUsername(), device.getPassword(), xml);
                
                mainHandler.post(() -> {
                    if (response != null) {
                        scanStatusText.setText("时间同步成功");
                        Toast.makeText(this, "设备时间已同步", Toast.LENGTH_SHORT).show();
                    } else {
                        scanStatusText.setText("时间同步失败");
                        Toast.makeText(this, "时间同步失败，请检查认证信息或网络连接", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanStatusText.setText("错误: " + e.getMessage());
                    Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 发送HTTP GET请求
     */
    private String sendHttpGet(String url, String username, String password) {
        return sendHttpRequest("GET", url, username, password, null);
    }

    /**
     * 发送HTTP PUT请求
     */
    private String sendHttpPut(String url, String username, String password, String body) {
        return sendHttpRequest("PUT", url, username, password, body);
    }

    /**
     * 发送HTTP POST请求
     */
    private String sendHttpPost(String url, String username, String password, String body) {
        return sendHttpRequest("POST", url, username, password, body);
    }

    /**
     * 统一 HTTP 请求入口：先 Basic，401 时再按 Digest 挑战重试。
     */
    private String sendHttpRequest(String method, String url, String username,
                                   String password, String body) {
        try {
            Request first = buildHttpRequest(method, url, body,
                    Credentials.basic(username, password));
            try (Response response = okHttpClient.newCall(first).execute()) {
                if (response.isSuccessful()) {
                    return response.body() != null ? response.body().string() : "";
                }
                if (response.code() != 401) {
                    return null;
                }
                String challenge = null;
                for (String value : response.headers("WWW-Authenticate")) {
                    if (value != null && value.contains("Digest")) {
                        challenge = value;
                        break;
                    }
                }
                String digestAuth = buildDigestAuthorization(
                        challenge, method, url, username, password);
                if (digestAuth == null) {
                    return null;
                }
                Request retry = buildHttpRequest(method, url, body, digestAuth);
                try (Response digestResponse = okHttpClient.newCall(retry).execute()) {
                    if (digestResponse.isSuccessful()) {
                        return digestResponse.body() != null
                                ? digestResponse.body().string() : "";
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP请求失败: " + e.getMessage(), e);
        }
        return null;
    }

    private Request buildHttpRequest(String method, String url, String body, String authHeader) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader);
        if ("GET".equals(method)) {
            builder.get();
        } else {
            RequestBody requestBody = RequestBody.create(
                    body == null ? "" : body,
                    okhttp3.MediaType.parse("application/xml; charset=utf-8"));
            builder.method(method, requestBody);
        }
        return builder.build();
    }

    /**
     * 解析 Digest 挑战并生成 Authorization 头。
     */
    private String buildDigestAuthorization(String challenge, String method,
                                             String url, String username, String password) {
        try {
            if (challenge == null) return null;
            int digestIndex = challenge.indexOf("Digest");
            if (digestIndex < 0) return null;
            String digestChallenge = challenge.substring(digestIndex + "Digest".length()).trim();
            String realm = digestParam(digestChallenge, "realm");
            String nonce = digestParam(digestChallenge, "nonce");
            String opaque = digestParam(digestChallenge, "opaque");
            String qopValue = digestParam(digestChallenge, "qop");
            if (realm == null || nonce == null) {
                return null;
            }

            java.net.URI uri = java.net.URI.create(url);
            String digestUri = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                digestUri += "?" + uri.getRawQuery();
            }

            String qop = null;
            if (qopValue != null) {
                for (String item : qopValue.split(",")) {
                    if ("auth".equalsIgnoreCase(item.trim())) {
                        qop = "auth";
                        break;
                    }
                }
            }

            String nc = "00000001";
            String cnonce = java.util.UUID.randomUUID().toString().replace("-", "");
            String ha1 = md5(username + ":" + realm + ":" + password);
            String ha2 = md5(method + ":" + digestUri);
            String response;
            if (qop != null) {
                response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
            } else {
                response = md5(ha1 + ":" + nonce + ":" + ha2);
            }

            StringBuilder header = new StringBuilder("Digest ");
            header.append("username=\"").append(escapeQuoted(username)).append("\", ");
            header.append("realm=\"").append(escapeQuoted(realm)).append("\", ");
            header.append("nonce=\"").append(escapeQuoted(nonce)).append("\", ");
            header.append("uri=\"").append(escapeQuoted(digestUri)).append("\", ");
            header.append("response=\"").append(response).append("\", ");
            header.append("algorithm=MD5");
            if (qop != null) {
                header.append(", qop=").append(qop);
                header.append(", nc=").append(nc);
                header.append(", cnonce=\"").append(cnonce).append("\"");
            }
            if (opaque != null) {
                header.append(", opaque=\"").append(escapeQuoted(opaque)).append("\"");
            }
            return header.toString();
        } catch (Exception e) {
            Log.w(TAG, "生成Digest认证失败: " + e.getMessage());
            return null;
        }
    }

    private String digestParam(String header, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)(?:^|,\\s*)" + java.util.regex.Pattern.quote(key)
                        + "\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(header);
        if (matcher.find()) {
            return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }
        return null;
    }

    private String md5(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String escapeQuoted(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
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
        stopRtspPlayback();
        if (executorService != null) {
            executorService.shutdownNow();
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ==================== RTSP播放相关 ====================

    /**
     * 开始RTSP播放
     */
    private void startRtspPlayback() {
        String rtspUrl = rtspUrlInput.getText() != null ? rtspUrlInput.getText().toString().trim() : "";
        if (rtspUrl.isEmpty()) {
            Toast.makeText(this, "请输入RTSP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "开始播放: " + rtspUrl + ", 协议: " + currentTransportProtocol);

        // 显示播放器容器
        rtspPlayerContainer.setVisibility(View.VISIBLE);
        rtspLoadingProgress.setVisibility(View.VISIBLE);
        rtspStatusText.setText("正在连接...");

        // 如果已有播放器,先停止
        if (nodePlayer != null) {
            stopRtspPlayback();
        }

        try {
            // 创建RtspPlayerView
            RtspPlayerView videoView = new RtspPlayerView(this);
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
            Log.i(TAG, "播放器已启动 (" + currentTransportProtocol + ")");

        } catch (Exception e) {
            String errorMsg = "播放器初始化失败: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            rtspStatusText.setText(errorMsg);
            rtspLoadingProgress.setVisibility(View.GONE);
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
                Log.i(TAG, "播放器: 连接中");
                break;
            case 1101: // 已连接
                rtspStatusText.setText("已连接到服务器");
                Log.i(TAG, "播放器: 已连接");
                break;
            case 1104: // 开始播放
                rtspStatusText.setText("正在播放");
                isPlaying = true;
                rtspLoadingProgress.setVisibility(View.GONE);
                Log.i(TAG, "播放器: 开始播放");
                break;
            case 1004: // 停止播放
                rtspStatusText.setText("已停止播放");
                isPlaying = false;
                Log.i(TAG, "播放器: 停止播放");
                break;
            case 1000: // 错误
                String errorMsg = "播放错误: " + msg;
                Log.e(TAG, errorMsg);
                rtspStatusText.setText(errorMsg);
                isPlaying = false;
                rtspLoadingProgress.setVisibility(View.GONE);
                Log.e(TAG, errorMsg);
                break;
            default:
                Log.d(TAG, "未知事件: " + event);
                break;
        }
    }

    /**
     * 停止RTSP播放
     */
    private void stopRtspPlayback() {
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

        isPlaying = false;
        rtspPlayerContainer.setVisibility(View.GONE);
        rtspLoadingProgress.setVisibility(View.GONE);
        rtspStatusText.setText("准备播放");
    }
}
