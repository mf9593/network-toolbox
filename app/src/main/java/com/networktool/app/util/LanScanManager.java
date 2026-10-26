package com.networktool.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.networktool.app.util.ScanSpeedConfig;
import com.networktool.app.model.DeviceInfo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 局域网设备扫描管理器（优化版 - 防闪退）
 * 
 * 优化策略：
 * 1. 使用单一线程池，所有任务排队执行
 * 2. 发现服务分批启动，避免同时创建大量Socket
 * 3. 所有Socket操作都有完善的异常处理
 * 4. 使用 try-with-resources 自动关闭资源
 */
public class LanScanManager {
    
    private static final String TAG = "LanScanManager";

    // 扫描模式
    public static final String MODE_ACTIVE = "active";
    public static final String MODE_PASSIVE = "passive";
    public static final String MODE_QUIET = "quiet";

    // 扫描速度配置（秒）
    public static final long DISCOVERY_TIME_FAST = 3;
    public static final long DISCOVERY_TIME_STANDARD = 5;
    public static final long DISCOVERY_TIME_SLOW = 10;
    
    // 设备名称与排序优先级（数字越小优先级越高，越靠前显示）
    // mDNS > NetBIOS > 路由器 > SADP > SSDP > ARP/UDP广播 > HTTP/Ping > ONVIF
    // 说明：mDNS 由设备自身广播、名称最准确，故置顶；
    //      ONVIF 通常返回「【ONVIF】摄像头」这类占位标题，故置底，仅作兜底。
    public static final int PRIORITY_MDNS = 1;
    public static final int PRIORITY_NETBIOS = 2;
    public static final int PRIORITY_ROUTER = 3;
    public static final int PRIORITY_SADP = 4;
    public static final int PRIORITY_SSDP = 5;
    public static final int PRIORITY_ARP = 6;
    public static final int PRIORITY_UDP = 7;
    public static final int PRIORITY_HTTP = 8;
    public static final int PRIORITY_PING = 9;
    public static final int PRIORITY_IPV6 = 10;
    public static final int PRIORITY_SERVICE = 11;
    public static final int PRIORITY_ONVIF = 12;

    private static final java.util.Map<Integer, String> PROTOCOL_LABELS = new java.util.HashMap<>();
    static {
        PROTOCOL_LABELS.put(PRIORITY_ONVIF, "ONVIF");
        PROTOCOL_LABELS.put(PRIORITY_SADP, "SADP");
        PROTOCOL_LABELS.put(PRIORITY_ROUTER, "路由器");
        PROTOCOL_LABELS.put(PRIORITY_SSDP, "SSDP");
        PROTOCOL_LABELS.put(PRIORITY_MDNS, "mDNS");
        PROTOCOL_LABELS.put(PRIORITY_NETBIOS, "NetBIOS");
        PROTOCOL_LABELS.put(PRIORITY_ARP, "ARP表");
        PROTOCOL_LABELS.put(PRIORITY_UDP, "UDP广播");
        PROTOCOL_LABELS.put(PRIORITY_HTTP, "HTTP");
        PROTOCOL_LABELS.put(PRIORITY_PING, "Ping探测");
        PROTOCOL_LABELS.put(PRIORITY_IPV6, "IPv6");
        PROTOCOL_LABELS.put(PRIORITY_SERVICE, "端口探测");
    }
    
    // 上下文和配置
    private final Context context;
    private final String gateway;
    private final String subnetPrefix;
    /** 本次扫描的目标主机地址（按真实子网掩码推导，/16~/23 的网段也能覆盖到） */
    private final List<String> targetHosts = new ArrayList<>();
    private String scanSpeed;
    private String scanMode;
    private long discoveryTime;
    
    // 线程管理 - 使用多线程池，提高扫描效率
    private ExecutorService executorService;
    private ExecutorService pingExecutor;      // Ping专用线程池
    private ExecutorService httpExecutor;      // HTTP专用线程池
    private Handler mainHandler;
    private volatile boolean isScanning = false;
    
    // 设备列表和名称缓存
    private final List<DeviceInfo> deviceList = new ArrayList<>();

    /** 最近一次扫描到的设备快照，供主页「局域网设备」直接展示，避免重复扫描 */
    private static volatile List<DeviceInfo> cachedDevices = new ArrayList<>();

    private static final String PREF_SCAN_CACHE = "lan_scan_cache";
    /** 上一次落盘的快照指纹，避免每次刷新都写盘 */
    private static String lastSnapshotSignature;
    private static final String KEY_SCAN_CACHE = "devices";

    /**
     * 取上次扫描到的设备快照（可能为空：从未扫描过）
     */
    public static List<DeviceInfo> getCachedDevices() {
        return new ArrayList<>(cachedDevices);
    }

    /**
     * 设备页把「当前展示的列表」同步给主页快照。
     * 设备页会对标题做本地识别（路由器网关、本机设备、路由器品牌等），
     * 主页直接读快照才能和设备页保持一致；标题或描述变化时顺带落盘。
     */
    public static void syncCachedDevices(Context context, List<DeviceInfo> devices) {
        if (devices == null) return;
        List<DeviceInfo> snapshot = new ArrayList<>(devices);
        StringBuilder signature = new StringBuilder();
        for (DeviceInfo device : snapshot) {
            signature.append(device.getIp()).append('#')
                    .append(device.getTitle()).append('#')
                    .append(device.getDescription()).append(';');
        }
        cachedDevices = snapshot;
        if (signature.toString().equals(lastSnapshotSignature)) {
            return;
        }
        lastSnapshotSignature = signature.toString();
        persistDevices(context, snapshot);
    }

    /** 把扫描结果落到 SharedPreferences，进程重启后主页仍能显示上次扫描结果 */
    private void persistCachedDevices() {
        persistDevices(context, deviceList);
    }

    private static void persistDevices(Context context, List<DeviceInfo> devices) {
        if (context == null || devices == null) return;
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            for (DeviceInfo device : devices) {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("ip", device.getIp());
                item.put("title", device.getTitle());
                item.put("desc", device.getDescription());
                item.put("icon", device.getIconType());
                array.put(item);
            }
            context.getSharedPreferences(PREF_SCAN_CACHE, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SCAN_CACHE, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 读取上次扫描结果的持久化快照 */
    public static List<DeviceInfo> loadPersistedDevices(Context context) {
        List<DeviceInfo> list = new ArrayList<>();
        if (context == null) return list;
        try {
            String json = context.getSharedPreferences(PREF_SCAN_CACHE, Context.MODE_PRIVATE)
                    .getString(KEY_SCAN_CACHE, null);
            if (json == null || json.isEmpty()) return list;
            org.json.JSONArray array = new org.json.JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.getJSONObject(i);
                DeviceInfo device = new DeviceInfo();
                device.setIp(item.optString("ip"));
                device.setTitle(item.optString("title"));
                device.setDescription(item.optString("desc"));
                device.setIconType(item.optInt("icon"));
                list.add(device);
            }
        } catch (Exception ignored) {
        }
        return list;
    }
    private final Map<String, String> deviceNames = new HashMap<>();
    private final Map<String, Integer> devicePriorities = new HashMap<>();
    private final Map<String, Boolean> scannedIps = new HashMap<>();
    
    // 路由器列表
    private final List<RouterInfo> routerList = new ArrayList<>();
    
    /**
     * 路由器信息
     */
    public static class RouterInfo {
        public String brand;
        public String link;
        
        public RouterInfo(String brand, String link) {
            this.brand = brand;
            this.link = link;
        }
    }
    
    // 回调
    private OnScanListener listener;
    
    public interface OnScanListener {
        void onDeviceFound(DeviceInfo device);
        void onDeviceUpdated(DeviceInfo device);
        void onProgress(String status, int progress, int total);
        void onScanComplete(int deviceCount);
        void onError(String error);
    }
    
    public LanScanManager(Context context, String gateway, OnScanListener listener) {
        this(context, gateway, null, listener);
    }

    /**
     * @param localIp 本机 IPv4 地址。优先用本机地址推导 /24 网段，
     *                避免网关与设备不在同一 /24 时扫错网段。
     */
    public LanScanManager(Context context, String gateway, String localIp,
                          OnScanListener listener) {
        this.context = context.getApplicationContext(); // 使用Application Context防止内存泄漏
        this.gateway = gateway;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // 优先使用本机 IPv4 推导子网，网关地址仅作为兜底
        String addressForSubnet = isValidIpv4(localIp) ? localIp : gateway;
        if (isValidIpv4(addressForSubnet)) {
            int lastDot = addressForSubnet.lastIndexOf(".");
            this.subnetPrefix = addressForSubnet.substring(0, lastDot + 1);
        } else {
            this.subnetPrefix = "192.168.1.";
        }

        buildTargetHosts(isValidIpv4(localIp) ? localIp : gateway);

        loadScanSpeed();
        loadScanMode();
        loadRouterList();
    }

    /**
     * 按本机 IP 的真实子网掩码生成扫描目标。
     *
     * <p>以前固定扫 xxx.xxx.xxx.1~255，遇到 /23、/22 这类网段（例如 192.168.0.0/23）
     * 就会漏掉另一半地址段里的设备，有线网络尤其明显。现在优先按掩码枚举主机，
     * 主机数超过上限（1024）时退回只扫本机所在 /24，避免扫描过久。</p>
     */
    private void buildTargetHosts(String localIp) {
        targetHosts.clear();
        int prefixLength = 24;
        long ipValue = ipToLong(localIp);
        if (ipValue > 0) {
            prefixLength = prefixLengthForIp(localIp);
        }
        int hostBits = 32 - prefixLength;
        long hostCount = hostBits >= 0 ? (1L << hostBits) - 2 : 254;
        if (hostBits < 0 || hostCount <= 0 || hostCount > 1024) {
            // 掩码过大（如 /16）或拿不到掩码：只扫本机所在 /24
            String prefix = isValidIpv4(localIp)
                    ? localIp.substring(0, localIp.lastIndexOf('.') + 1)
                    : subnetPrefix;
            for (int i = 1; i <= 254; i++) {
                targetHosts.add(prefix + i);
            }
            return;
        }
        long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << hostBits) & 0xFFFFFFFFL;
        long network = ipValue & mask;
        for (long i = 1; i <= hostCount; i++) {
            targetHosts.add(longToIp(network + i));
        }
    }

    /** 找到本机地址对应的子网掩码前缀长度（拿不到就按 /24） */
    private static int prefixLengthForIp(String localIp) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (java.net.InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    java.net.InetAddress inetAddress = address.getAddress();
                    if (inetAddress instanceof java.net.Inet4Address
                            && localIp.equals(inetAddress.getHostAddress())) {
                        int prefix = address.getNetworkPrefixLength();
                        if (prefix > 0 && prefix <= 32) return prefix;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 24;
    }

    private long ipToLong(String ip) {
        if (!isValidIpv4(ip)) return -1;
        String[] parts = ip.split("\\.");
        long value = 0;
        for (String part : parts) {
            value = (value << 8) | (Integer.parseInt(part) & 0xFF);
        }
        return value;
    }

    private String longToIp(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    private boolean isValidIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 加载扫描速度配置
     */
    private void loadScanSpeed() {
        scanSpeed = ScanSpeedConfig.getScanSpeed(context);
        switch (scanSpeed) {
            case ScanSpeedConfig.SPEED_FAST:
                discoveryTime = DISCOVERY_TIME_FAST;
                break;
            case ScanSpeedConfig.SPEED_SLOW:
                discoveryTime = DISCOVERY_TIME_SLOW;
                break;
            default:
                discoveryTime = DISCOVERY_TIME_STANDARD;
                break;
        }
    }
    
    /**
     * 加载扫描模式配置
     */
    private void loadScanMode() {
        String mode = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("scan_mode", MODE_ACTIVE);
        if (mode == null || (!mode.equals(MODE_ACTIVE) && !mode.equals(MODE_PASSIVE) && !mode.equals(MODE_QUIET))) {
            scanMode = MODE_ACTIVE;
        } else {
            scanMode = mode;
        }
    }

    /**
     * 加载路由器列表
     */
    private void loadRouterList() {
        try {
            android.content.res.AssetManager assetManager = context.getAssets();
            java.io.InputStream is = assetManager.open("routerList.json");
            java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            is.close();
            
            org.json.JSONObject jsonObject = new org.json.JSONObject(sb.toString());
            org.json.JSONArray array = jsonObject.getJSONArray("routers");
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.getJSONObject(i);
                routerList.add(new RouterInfo(
                    obj.getString("brand"),
                    obj.getString("link")
                ));
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "加载路由器列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 开始扫描
     */
    public void startScan() {
        if (isScanning) {
            stopScan();
        }

        loadScanSpeed();
        loadScanMode();
        
        // 清理旧数据
        deviceList.clear();
        deviceNames.clear();
        devicePriorities.clear();
        scannedIps.clear();
        
        // 创建多个线程池，提高并发能力
        int pingThreads = ScanSpeedConfig.getPingConcurrency(scanSpeed);
        int httpThreads = ScanSpeedConfig.getHttpConcurrency(scanSpeed);
        
        // 主线程池：用于发现服务
        executorService = Executors.newCachedThreadPool();
        // Ping专用线程池：控制Ping并发数
        pingExecutor = Executors.newFixedThreadPool(pingThreads);
        // HTTP专用线程池：控制HTTP并发数
        httpExecutor = Executors.newFixedThreadPool(httpThreads);
        
        isScanning = true;
        
        reportProgress("正在启动扫描服务...", 0, 255);
        
        // 在子线程中执行扫描
        executorService.execute(() -> {
            try {
                // 第一步：分批启动发现服务
                startDiscoveryServicesInBatches();
                
                // 第二步：IP 扫描（使用多线程）
                if (isScanning) {
                    startIpScanMultiThread();
                }

                // Ping 扫描会促发 ARP 解析，结束时再读一次以补全设备 MAC
                if (isScanning) {
                    scanArpTable();
                }
                if (isScanning) {
                    scanNdpTable();
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "扫描异常: " + e.getMessage(), e);
                reportError("扫描异常: " + e.getMessage());
            } finally {
                // 确保完成扫描
                if (isScanning) {
                    finishScan();
                }
            }
        });
    }
    
    /**
     * 分批启动发现服务，避免同时创建大量Socket
     * 根据扫描模式（被动/主动/安静）决定发现策略
     */
    private void startDiscoveryServicesInBatches() {
        long batchDelay = getBatchDelay();

        // === 所有模式通用：ARP表 + NDP表（简单读取，无网络IO） ===
        if (isScanning) {
            reportProgress("扫描ARP表...", 0, 255);
            safeRun(() -> scanArpTable());
            sleep(batchDelay);
        }
        if (isScanning) {
            safeRun(() -> scanNdpTable());
            sleep(batchDelay);
        }

        // === SSDP 多播发现（IPv4 + IPv6） ===
        if (isScanning) {
            reportProgress("SSDP搜索...", 0, 255);
            safeRun(() -> searchSsdpDevices());
            safeRun(() -> searchSsdpDevicesV6());
            sleep(batchDelay);
        }

        // === mDNS 多播发现（IPv4 + IPv6） ===
        if (isScanning) {
            reportProgress("mDNS搜索...", 0, 255);
            safeRun(() -> searchMdnsDevices());
            safeRun(() -> searchMdnsDevicesV6());
            sleep(batchDelay);
        }

        if (scanMode.equals(MODE_PASSIVE)) {
            // 被动模式：仅靠表和多播监听，不做任何主动探测
            return;
        }

        // === 以下为主动扫描（ACTIVE / QUIET 模式） ===

        // ONVIF + SADP 搜索（主动多播探测）
        if (isScanning) {
            reportProgress("ONVIF搜索...", 0, 255);
            safeRun(() -> searchOnvifDevices());
            safeRun(() -> searchOnvifDevicesV6());
            sleep(batchDelay);
        }
        if (isScanning) {
            reportProgress("SADP搜索...", 0, 255);
            safeRun(() -> searchSadpDevices());
            sleep(batchDelay);
        }

        // UDP 广播发现
        if (isScanning) {
            reportProgress("UDP发现...", 0, 255);
            safeRun(() -> searchUdpDevices());
            sleep(batchDelay);
        }

        // 路由器后台扫描（安静模式跳过）
        if (isScanning && !routerList.isEmpty() && !scanMode.equals(MODE_QUIET)) {
            reportProgress("扫描路由器后台...", 0, routerList.size());
            safeRun(() -> scanRouterBackends());
            sleep(batchDelay);
        }

        // NetBIOS 搜索（安静模式跳过）
        if (isScanning && !scanMode.equals(MODE_QUIET)) {
            reportProgress("NetBIOS搜索...", 0, 255);
            safeRun(() -> searchNetBiosDevices());
            sleep(batchDelay);
        }
    }

    /**
     * 获取分批启动延迟
     */
    private long getBatchDelay() {
        switch (scanSpeed) {
            case ScanSpeedConfig.SPEED_FAST:
                return 200;  // 快速：200ms
            case ScanSpeedConfig.SPEED_SLOW:
                return 500;  // 慢速：500ms
            default:
                return 300;  // 标准：300ms
        }
    }
    
    /**
     * 安全执行任务
     */
    private void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            DebugLogger.logDiscovery("safeRun", "异常", e.getClass().getSimpleName() + ": " + e.getMessage());
            android.util.Log.w(TAG, "任务执行异常: " + e.getMessage());
        }
    }
    
    /**
     * 安全延迟
     */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 开始 IP 扫描（多线程版本）
     * 根据扫描模式决定策略：
     * - 被动模式：跳过IP扫描
     * - 安静模式：仅Ping探测，不HTTP获取标题
     * - 主动模式：Ping + HTTP 完整扫描（含IPv6）
     */
    private void startIpScanMultiThread() {
        if (scanMode.equals(MODE_PASSIVE)) return;

        boolean doHttp = !scanMode.equals(MODE_QUIET);

        // IPv4 Ping 扫描
        reportProgress("正在扫描设备...", 0, 255);
        
        final AtomicInteger progress = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(255);
        
        for (String host : targetHosts) {
            if (!isScanning) {
                for (int j = 0; j < latch.getCount(); j++) {
                    latch.countDown();
                }
                break;
            }

            final String ip = host;
            
            pingExecutor.execute(() -> {
                try {
                    if (!isScanning) return;
                    
                    boolean alreadyFound;
                    synchronized (scannedIps) {
                        alreadyFound = scannedIps.containsKey(ip);
                    }
                    
                    if (!alreadyFound && pingHost(ip)) {
                        addDeviceWithPriority(ip, "局域网设备", DeviceInfo.ICON_PING, PRIORITY_PING);
                        if (doHttp) {
                            fetchDeviceTitleMultiThread(ip);
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "IP扫描异常: " + e.getMessage());
                } finally {
                    int current = progress.incrementAndGet();
                    reportProgress("正在扫描 " + current + "/255", current, 255);
                    latch.countDown();
                }
            });
        }
        
        try {
            boolean finished = latch.await(30, TimeUnit.SECONDS);
            if (!finished) {
                android.util.Log.w(TAG, "IP扫描超时，已完成 " + (255 - latch.getCount()) + "/255");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // IPv6 Ping 扫描（所有非被动模式都执行）
        if (!isScanning) return;
        scanIpv6Neighbors();
    }
    
    /**
     * 获取设备标题（每IP串行尝试常见端口，避免洪泛httpExecutor）
     */
    private void fetchDeviceTitleMultiThread(String ip) {
        final int[] httpPorts = {80, 8080, 443};
        httpExecutor.execute(() -> {
            for (int port : httpPorts) {
                if (!isScanning) return;
                String[] result = httpFetchTitle(ip, port);
                if (result != null) {
                    String title = result[0];
                    String label = (title != null && !title.isEmpty())
                            ? title + " on port " + port
                            : "HTTP服务 (端口 " + port + ")";
                    // 记录发现该标题所用的 HTTP 端口，供列表展示
                    addDeviceWithPriority(ip, label, null,
                            DeviceInfo.ICON_HTTP, PRIORITY_HTTP, null, port);
                    break;
                }
            }
            if (isScanning) {
                probeCommonServices(ip);
            }
        });
    }

    /**
     * 常见局域网服务端口探测，补充 HTTP/Ping 识别不到的电脑、摄像头、
     * 打印机和 NAS。
     */
    private void probeCommonServices(String ip) {
        int[] ports = {22, 445, 554, 631, 3389, 5000};
        int[] icons = {
                DeviceInfo.ICON_COMPUTER,
                DeviceInfo.ICON_COMPUTER,
                DeviceInfo.ICON_CAMERA,
                DeviceInfo.ICON_PRINTER,
                DeviceInfo.ICON_COMPUTER,
                DeviceInfo.ICON_UNKNOWN
        };
        String[] labels = {
                "SSH 服务", "SMB 共享", "RTSP 摄像头",
                "网络打印机", "远程桌面", "NAS 管理"
        };
        for (int i = 0; i < ports.length; i++) {
            if (!isScanning) return;
            if (!isPortOpen(ip, ports[i], 250)) continue;

            String label = labels[i];
            int icon = icons[i];
            // 554 端口开放不等于就是摄像头：发一条 RTSP OPTIONS 确认对面真的是 RTSP 服务，
            // 不是的话只标「554 端口开放」，避免把随便开着 554 的设备误判成摄像头
            if (ports[i] == 554 && !isRtspServer(ip, ports[i])) {
                label = "554 端口开放（未见 RTSP 响应）";
                icon = DeviceInfo.ICON_UNKNOWN;
            }
            // 记录发现的开放端口，供列表展示
            addDeviceWithPriority(ip, label, null, icon, PRIORITY_SERVICE, null, ports[i]);
        }
    }

    /**
     * 用 RTSP OPTIONS 请求确认目标是不是真正的 RTSP 服务（摄像头 / NVR / 流媒体）。
     * 只要首行以 RTSP/1.0 开头（含 401 需要认证的情况）就认定是 RTSP。
     */
    private boolean isRtspServer(String ip, int port) {
        java.net.Socket socket = null;
        try {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(ip, port), 400);
            socket.setSoTimeout(800);
            String request = "OPTIONS rtsp://" + ip + ":" + port + "/ RTSP/1.0\r\n"
                    + "CSeq: 1\r\n"
                    + "User-Agent: NetworkToolbox\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[64];
            int read = socket.getInputStream().read(buffer);
            if (read <= 0) return false;
            String head = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
            return head.startsWith("RTSP/1.0") || head.startsWith("RTSP/2.0");
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * HTTP 获取网页标题（同步，超时1秒）
     *
     * @return String[2] {title, server} 或 null（端口不可达）
     */
    private String[] httpFetchTitle(String ip, int port) {
        OkHttpClient client = null;
        try {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true);
            // 局域网 HTTPS 使用宽松 SSL（自签名证书常见）
            if (port == 443) {
                try {
                    javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                        }
                    };
                    javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                    sslContext.init(null, trustAll, new java.security.SecureRandom());
                    builder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0]);
                    builder.hostnameVerifier((hostname, session) -> true);
                } catch (Exception ignored) {}
            }
            client = builder.build();
            Request request = new Request.Builder()
                    .url((port == 443 ? "https" : "http") + "://" + ip + ":" + port)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.body() == null) return null;
                String content = response.body().string();
                Document doc = Jsoup.parse(content);
                String server = response.header("Server");
                // 标题：<title> 为空时依次退到 og:site_name / h1 / 厂商特征 / Server
                String title = extractHttpPageTitle(doc, content, server);
                // 记录原始 HTML 与解析出的标题，便于核对标题截取是否正确
                StringBuilder raw = new StringBuilder();
                raw.append("HTTP ").append(response.code()).append(" ")
                   .append(response.message()).append("\n");
                if (server != null) raw.append("Server: ").append(server).append("\n");
                raw.append("解析出的标题: [").append(title == null ? "" : title).append("]\n");
                raw.append("响应体(前2000字符):\n")
                   .append(content.length() > 2000 ? content.substring(0, 2000) : content);
                DebugLogger.logBroadcast("HTTP", ip + ":" + port, raw.toString());
                if (title != null && !title.isEmpty()) {
                    return new String[]{title, server};
                }
                return new String[]{"网页管理界面", server};
            }
        } catch (Exception e) {
            // 端口不可达或非HTTP
        } finally {
            if (client != null) {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            }
        }
        return null;
    }
    
    /**
     * 判断主机是否在线
     * 使用系统 ping 命令（ICMP），不需要 root，比 TCP 端口探测更可靠
     */
    private boolean pingHost(String ip) {
        try {
            Process process = Runtime.getRuntime().exec("ping -c 1 -W 1 " + ip);
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * IPv6 Ping 探测
     */
    private boolean ping6Host(String ip) {
        try {
            Process process = Runtime.getRuntime().exec("ping6 -c 1 -W 1 " + ip);
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            // ping6 可能不存在，尝试 ping -6
            try {
                Process process = Runtime.getRuntime().exec("ping -6 -c 1 -W 1 " + ip);
                int exitCode = process.waitFor();
                return exitCode == 0;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /**
     * IPv6 全节点组播探测（ff02::1），用于触发 NDP 邻居表更新。
     * 需要绑定到有 IPv6 地址的 WiFi/以太网接口。
     */
    private void pingIpv6AllNodes() {
        String iface = findIpv6InterfaceName();
        if (iface == null) return;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "ping6", "-I", iface, "-c", "1", "-W", "1", "ff02::1"
            });
            process.waitFor();
        } catch (Exception e) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{
                        "ping", "-6", "-I", iface, "-c", "1", "-W", "1", "ff02::1"
                });
                process.waitFor();
            } catch (Exception ignored) {
            }
        }
    }

    private String findIpv6InterfaceName() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                String name = networkInterface.getName().toLowerCase(Locale.US);
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || name.startsWith("rmnet") || name.startsWith("pdp")
                        || name.startsWith("ccmni")) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.getAddress().length == 16 && !address.isLoopbackAddress()) {
                        return networkInterface.getName();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isUsableIpv6(String address) {
        if (address == null || !address.contains(":")) return false;
        String lower = address.toLowerCase(Locale.US);
        return !lower.equals("::") && !lower.equals("::1")
                && !lower.startsWith("ff");
    }

    /**
     * 扫描 IPv6 邻居（从 NDP 表获取列表并 Ping 验证存活）
     */
    private void scanIpv6Neighbors() {
        try {
            // 先主动触发 IPv6 邻居解析：向链路所有节点发送一次探测
            pingIpv6AllNodes();

            // 从 NDP 表读取 IPv6 邻居地址（包括 fe80、ULA 和全球地址）
            List<String> ipv6Candidates = new ArrayList<>();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader("/proc/net/ndisc"));
                String line;
                reader.readLine();
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1 && isUsableIpv6(parts[0])) {
                        ipv6Candidates.add(parts[0]);
                    }
                }
            } catch (Exception e) {
                // 尝试 ip -6 neigh
                try {
                    Process process = Runtime.getRuntime().exec("ip -6 neigh show");
                    BufferedReader ipReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String ipLine;
                    while ((ipLine = ipReader.readLine()) != null) {
                        String[] ipParts = ipLine.trim().split("\\s+");
                        if (ipParts.length >= 1 && isUsableIpv6(ipParts[0])) {
                            ipv6Candidates.add(ipParts[0]);
                        }
                    }
                    ipReader.close();
                } catch (Exception ignored) {}
            } finally {
                if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            }

            // Ping 验证每个邻居
            for (String ipv6 : ipv6Candidates) {
                if (!isScanning) break;
                if (ping6Host(ipv6)) {
                    addDeviceWithPriority(ipv6, "IPv6设备", DeviceInfo.ICON_PING, PRIORITY_IPV6);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "IPv6邻居扫描失败: " + e.getMessage());
        }
    }
    
    // ==================== 高优先级发现服务 ====================
    
    /**
     * ARP 表扫描
     */
    private void scanArpTable() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            reader.readLine(); // 跳过标题
            
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0];
                    String mac = parts[3].toUpperCase();
                    
                    if (!mac.equals("00:00:00:00:00:00")) {
                        String vendor = getVendorFromMac(mac);
                        String title = vendor.isEmpty() ? "局域网设备" : vendor + " 设备";
                        addDeviceWithPriority(ip, title, DeviceInfo.ICON_ARP,
                                PRIORITY_ARP, mac);
                    }
                }
            }
        } catch (Exception e) {
            // /proc/net/arp 在部分 Android 版本不可读，尝试 ip neigh 命令
            try {
                Process process = Runtime.getRuntime().exec("ip neigh show");
                BufferedReader ipReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String ipLine;
                while ((ipLine = ipReader.readLine()) != null) {
                    String[] parts = ipLine.trim().split("\\s+");
                    if (parts.length < 5) continue;
                    String ip = parts[0];
                    String mac = "";
                    for (int k = 1; k < parts.length - 1; k++) {
                        if ("lladdr".equals(parts[k])) {
                            mac = parts[k + 1].toUpperCase();
                            break;
                        }
                    }
                    if (mac.isEmpty() || mac.equals("00:00:00:00:00:00")) continue;
                    String vendor = getVendorFromMac(mac);
                    String title = vendor.isEmpty() ? "局域网设备" : vendor + " 设备";
                    addDeviceWithPriority(ip, title, DeviceInfo.ICON_ARP,
                            PRIORITY_ARP, mac);
                }
                ipReader.close();
            } catch (Exception e2) {
                android.util.Log.w(TAG, "ip neigh 读取失败: " + e2.getMessage());
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception e) {}
            }
        }
    }
    
    /**
     * IPv6 NDP 邻居表扫描（类似 ARP 表，读取内核邻居缓存）
     * 文件格式: /proc/net/ndisc 或 /proc/net/ipv6_neigh
     * /proc/net/ndisc 每行: IPV6地址 dev mac flags
     */
    private void scanNdpTable() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/ndisc"));
            String line;
            reader.readLine(); // 跳过标题

            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                // 格式: fe80::xxx dev wlan0 lladdr xx:xx:xx:xx:xx:xx router STALE
                if (parts.length >= 3 && isUsableIpv6(parts[0])) {
                    String ipv6 = parts[0];
                    String mac = "";
                    for (int k = 1; k < parts.length - 1; k++) {
                        if ("lladdr".equals(parts[k])) {
                            mac = parts[k + 1].toUpperCase(Locale.US);
                            break;
                        }
                    }

                    if (!mac.equals("00:00:00:00:00:00") && !mac.equals("(INCOMPLETE)")) {
                        String vendor = getVendorFromMac(mac);
                        String title = vendor.isEmpty() ? "IPv6局域网设备" : vendor + " 设备";
                        addDeviceWithPriority(ipv6, title, DeviceInfo.ICON_ARP,
                                PRIORITY_IPV6, mac);
                    }
                }
            }
        } catch (Exception e) {
            // /proc/net/ndisc 可能不存在，尝试 ip -6 neigh
            try {
                Process process = Runtime.getRuntime().exec("ip -6 neigh show");
                BufferedReader ipReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String ipLine;
                while ((ipLine = ipReader.readLine()) != null) {
                    String[] ipParts = ipLine.trim().split("\\s+");
                    // 格式: fe80::xxx dev wlan0 lladdr xx:xx:xx:xx:xx:xx REACHABLE
                    // 或: fe80::xxx lladdr xx:xx:xx:xx:xx:xx dev wlan0 REACHABLE
                    if (ipParts.length >= 3 && isUsableIpv6(ipParts[0])) {
                        String ipv6 = ipParts[0];
                        String mac = "";
                        for (int k = 1; k < ipParts.length - 1; k++) {
                            if ("lladdr".equals(ipParts[k]) && k + 1 < ipParts.length) {
                                mac = ipParts[k + 1].toUpperCase();
                                break;
                            }
                        }

                        String vendor = getVendorFromMac(mac);
                        String title = vendor.isEmpty() ? "IPv6局域网设备" : vendor + " 设备";
                        addDeviceWithPriority(ipv6, title, DeviceInfo.ICON_ARP,
                                PRIORITY_IPV6, mac);
                    }
                }
                ipReader.close();
            } catch (Exception e2) {
                android.util.Log.w(TAG, "NDP表扫描失败: " + e2.getMessage());
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception e) {}
            }
        }
    }

    private String getVendorFromMac(String mac) {
        if (mac == null || mac.length() < 8) return "";
        
        String prefix = mac.substring(0, 8).toUpperCase();
        
        Map<String, String> vendors = new HashMap<>();
        vendors.put("00:1A:2B", "华为");
        vendors.put("00:23:8B", "小米");
        vendors.put("00:1B:44", "TP-Link");
        vendors.put("00:1D:0F", "D-Link");
        vendors.put("00:17:31", "Apple");
        vendors.put("8C:79:F5", "Samsung");
        vendors.put("B0:6E:BF", "Dell");
        vendors.put("00:18:F8", "Netgear");
        vendors.put("00:26:F2", "Microsoft");
        
        return vendors.getOrDefault(prefix, "");
    }
    
        /**
     * ONVIF 设备搜索（双协议 + 支持有线网络）
     * 发送天视通专用 Probe（空类型）和标准 ONVIF Probe（tds:Device）两种协议
     * 使用 DatagramSocket 广播方式，兼容 WiFi 和有线网络
     */
    private void searchOnvifDevices() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.setSoTimeout(1500);

            // 天视通专用 Probe（空类型）
            String probeSkysight =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" \n" +
                "             xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" \n" +
                "             xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">\n" +
                "  <s:Header>\n" +
                "    <a:MessageID>uuid:scan-" + System.currentTimeMillis() + "-skysight</a:MessageID>\n" +
                "    <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>\n" +
                "    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>\n" +
                "  </s:Header>\n" +
                "  <s:Body>\n" +
                "    <d:Probe>\n" +
                "      <d:Scopes></d:Scopes>\n" +
                "      <d:Types></d:Types>\n" +
                "    </d:Probe>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>\n";

            // 标准 ONVIF Probe（带 tds:Device 类型）
            String probeStandard =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" \n" +
                "             xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" \n" +
                "             xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">\n" +
                "  <s:Header>\n" +
                "    <a:MessageID>uuid:scan-" + System.currentTimeMillis() + "-std</a:MessageID>\n" +
                "    <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>\n" +
                "    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>\n" +
                "  </s:Header>\n" +
                "  <s:Body>\n" +
                "    <d:Probe>\n" +
                "      <d:Types>tds:Device</d:Types>\n" +
                "    </d:Probe>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>\n";

            InetAddress group = InetAddress.getByName("239.255.255.250");

            // 发送天视通专用 Probe
            byte[] buffer1 = probeSkysight.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet1 = new DatagramPacket(buffer1, buffer1.length, group, 3702);
            socket.send(packet1);

            // 发送标准 ONVIF Probe
            byte[] buffer2 = probeStandard.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet2 = new DatagramPacket(buffer2, buffer2.length, group, 3702);
            socket.send(packet2);

            // 接收所有响应
            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String responseStr = new String(response.getData(), 0, response.getLength());
                    String senderIp = response.getAddress().getHostAddress();
                    DebugLogger.logDiscovery("ONVIF", "收到响应", senderIp + " | " + responseStr.substring(0, Math.min(300, responseStr.length())));
                    DebugLogger.logBroadcast("ONVIF", senderIp, responseStr);

                    // 检查响应中是否包含 ONVIF 设备标识
                    if (responseStr.contains("NetworkVideo") || responseStr.contains("XAddrs")) {
                        String title = extractOnvifTitle(responseStr);
                        if (title == null) title = "【ONVIF】摄像头";
                        // 从 XAddrs 提取 ONVIF 服务端口
                        Integer onvifPort = extractOnvifPort(responseStr);
                        addDeviceWithPriority(senderIp, title, null,
                            DeviceInfo.ICON_ONVIF, PRIORITY_ONVIF, null, onvifPort);
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            DebugLogger.logDiscovery("ONVIF", "搜索失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * ONVIF IPv6 多播发现
     * 发送WS-Discovery Probe到 ff02::c:3702
     */
    private void searchOnvifDevicesV6() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setReuseAddress(true);
            socket.setTimeToLive(2);
            socket.setSoTimeout(1500);

            InetAddress group = InetAddress.getByName("ff02::c");
            socket.joinGroup(group);

            String probeSkysight =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" \n" +
                "             xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" \n" +
                "             xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">\n" +
                "  <s:Header>\n" +
                "    <a:MessageID>uuid:scan-" + System.currentTimeMillis() + "-v6-skysight</a:MessageID>\n" +
                "    <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>\n" +
                "    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>\n" +
                "  </s:Header>\n" +
                "  <s:Body>\n" +
                "    <d:Probe>\n" +
                "      <d:Scopes></d:Scopes>\n" +
                "      <d:Types></d:Types>\n" +
                "    </d:Probe>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>\n";

            String probeStandard =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" \n" +
                "             xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" \n" +
                "             xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">\n" +
                "  <s:Header>\n" +
                "    <a:MessageID>uuid:scan-" + System.currentTimeMillis() + "-v6-std</a:MessageID>\n" +
                "    <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>\n" +
                "    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>\n" +
                "  </s:Header>\n" +
                "  <s:Body>\n" +
                "    <d:Probe>\n" +
                "      <d:Types>tds:Device</d:Types>\n" +
                "    </d:Probe>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>\n";

            byte[] buffer1 = probeSkysight.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet1 = new DatagramPacket(buffer1, buffer1.length, group, 3702);
            socket.send(packet1);

            byte[] buffer2 = probeStandard.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet2 = new DatagramPacket(buffer2, buffer2.length, group, 3702);
            socket.send(packet2);

            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String responseStr = new String(response.getData(), 0, response.getLength());
                    String senderIp = response.getAddress().getHostAddress();
                    DebugLogger.logDiscovery("ONVIF_IPv6", "收到响应", senderIp + " | " + responseStr.substring(0, Math.min(300, responseStr.length())));
                    DebugLogger.logBroadcast("ONVIF_IPv6", senderIp, responseStr);

                    if (responseStr.contains("NetworkVideo") || responseStr.contains("XAddrs")) {
                        String title = extractOnvifTitle(responseStr);
                        if (title == null) title = "【ONVIF】摄像头(IPv6)";
                        Integer onvifPort = extractOnvifPort(responseStr);
                        addDeviceWithPriority(senderIp, title, null,
                            DeviceInfo.ICON_ONVIF, PRIORITY_ONVIF, null, onvifPort);
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            DebugLogger.logDiscovery("ONVIF_IPv6", "搜索失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.leaveGroup(InetAddress.getByName("ff02::c")); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * SADP 设备搜索
     */
    private void searchSadpDevices() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(1500);

            String message =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<Probe>\n" +
                "<Uuid>SADP-SCAN-" + System.currentTimeMillis() + "</Uuid>\n" +
                "<Types>inquiry</Types>\n" +
                "</Probe>";

            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 37020);
            socket.send(packet);

            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String responseStr = new String(response.getData(), 0, response.getLength());
                    String senderIp = response.getAddress().getHostAddress();
                    DebugLogger.logDiscovery("SADP", "收到响应", senderIp + " | " + responseStr.substring(0, Math.min(300, responseStr.length())));
                    DebugLogger.logBroadcast("SADP", senderIp, responseStr);

                    String serial = extractXmlValue(responseStr, "DeviceSN");
                    String model = extractXmlValue(responseStr, "DeviceModel");
                    String title = "【SADP】";
                    if (model != null && !model.isEmpty()) {
                        title += model;
                    } else {
                        title += "海康设备";
                    }
                    if (serial != null && !serial.isEmpty()) {
                        title += " (" + serial + ")";
                    }
                    addDeviceWithPriority(senderIp, title,
                            DeviceInfo.ICON_ONVIF, PRIORITY_SADP);
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            DebugLogger.logDiscovery("SADP", "搜索失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * 从网页里提取设备标题。
     *
     * <p>不少路由/录像机/NAS 的首页 {@code <title></title>} 是空的（标题由 JS 动态写入），
     * 直接取 doc.title() 会得到空串，设备就变成「HTTP Server」。这里按可靠度依次回退：
     * {@code <title>} → {@code og:site_name} / {@code application-name} 等 meta →
     * 首个 {@code <h1>} → 页面特征串识别厂商型号 → Server 头产品名。</p>
     */
    private String extractHttpPageTitle(Document doc, String content, String server) {
        if (doc != null) {
            String title = doc.title();
            if (title != null && !title.trim().isEmpty()) return title.trim();

            String[] metaSelectors = {
                    "meta[property=og:site_name]", "meta[name=og:site_name]",
                    "meta[name=application-name]", "meta[name=apple-mobile-web-app-title]",
                    "meta[property=og:title]", "meta[name=twitter:title]"
            };
            for (String selector : metaSelectors) {
                try {
                    Element meta = doc.selectFirst(selector);
                    if (meta != null) {
                        String value = meta.attr("content");
                        if (value != null && !value.trim().isEmpty()) return value.trim();
                    }
                } catch (Exception ignored) {
                }
            }

            try {
                Element h1 = doc.selectFirst("h1");
                if (h1 != null) {
                    String text = h1.text();
                    if (text != null && !text.trim().isEmpty() && text.trim().length() <= 40) {
                        return text.trim();
                    }
                }
                Element logoAlt = doc.selectFirst("img[alt]");
                if (logoAlt != null) {
                    String alt = logoAlt.attr("alt");
                    if (alt != null && !alt.trim().isEmpty() && alt.trim().length() <= 30) {
                        return alt.trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String brand = detectWebUiBrand(content);
        if (brand != null) return brand;

        // Server 头兜底：取产品名（"Boa/0.94.14rc21" → Boa）
        if (server != null && !server.trim().isEmpty()) {
            String product = server.split("[ /]")[0].trim();
            if (!product.isEmpty()) return "网页管理界面 (" + product + ")";
        }
        return "网页管理界面";
    }

    /** 用页面特征串判断 Web 管理界面属于哪家设备（命中就返回可读名称） */
    private String detectWebUiBrand(String content) {
        if (content == null || content.isEmpty()) return null;
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        String[][] signatures = {
                {"hikvision", "海康威视设备"},
                {"dahua", "大华设备"},
                {"seetong", "视洞(Seetong)设备"},
                {"xmeye", "雄迈(XMEye)设备"},
                {"tplink", "TP-LINK 设备"},
                {"tplogin", "TP-LINK 设备"},
                {"miwifi", "小米路由"},
                {"huawei", "华为设备"},
                {"ruijie", "锐捷设备"},
                {"hik", "海康威视设备"},
                {"uniview", "宇视设备"},
                {"nvr", "网络录像机(NVR)"},
                {"ipc", "网络摄像机(IPC)"},
                {"synology", "群晖 NAS"},
                {"qnap", "QNAP NAS"},
                {"openwrt", "OpenWrt 路由"},
                {"luci", "OpenWrt 路由"},
                {"asus", "华硕路由"},
                {"netgear", "网件设备"},
                {"vstarcam", "威视达康设备"},
                {"v380", "V380 摄像头"},
        };
        for (String[] signature : signatures) {
            if (lower.contains(signature[0])) return signature[1];
        }
        return null;
    }

    /**
     * 从 UPnP 的 SERVER 头里取「产品名」。
     *
     * <p>UPnP 规范格式为 {@code OS/版本 UPnP/版本 产品/版本}，例如
     * {@code vxWorks/5.5 UPnP/1.0 TL-WDR5660千兆版/5.0} 应得到
     * {@code TL-WDR5660千兆版}。旧实现直接按 "/" 切分取最后一段，
     * 结果只拿到版本号 "5.0"。</p>
     */
    private static String parseUpnpServerName(String server) {
        if (server == null) return "SSDP设备";
        String text = server.trim();
        if (text.isEmpty()) return "SSDP设备";

        String candidate = null;

        // ① 优先取 "UPnP/版本" 之后的产品段
        int upnpIndex = text.toUpperCase(java.util.Locale.ROOT).indexOf("UPNP/");
        if (upnpIndex >= 0) {
            String after = text.substring(upnpIndex + 5).trim();
            int space = after.indexOf(' ');
            if (space >= 0) {
                candidate = after.substring(space + 1).trim();
            }
        }
        // ② 没有 UPnP 段时退回整串
        if (candidate == null || candidate.isEmpty()) {
            candidate = text;
        }

        // 去掉产品名尾巴上的 "/版本"
        int slash = candidate.indexOf('/');
        if (slash > 0) {
            candidate = candidate.substring(0, slash).trim();
        }
        // 去掉修饰性符号（部分是 "Linux, UPnP/1.0, Private Upnp SDK" 这种写法）
        candidate = candidate.replaceAll("^[,\\s]+", "").replaceAll("[,\\s]+$", "").trim();

        // 只剩纯版本号 / 纯 upnp 字样就没意义，退回整串 Server
        if (candidate.isEmpty()
                || candidate.matches("^[0-9.]+$")
                || "upnp".equalsIgnoreCase(candidate)) {
            return text;
        }
        return candidate;
    }

    /**
     * NetBIOS 设备搜索（并行扫描，超时控制）
     */
    private void searchNetBiosDevices() {
        int scanRange = getNetBiosScanRange();
        int concurrency = 20;  // 最多20个并发
        final Semaphore semaphore = new Semaphore(concurrency);
        final AtomicInteger progress = new AtomicInteger(0);
        // 目标按真实子网生成，最多取前 scanRange 个，避免大网段扫太久
        List<String> netBiosTargets = new ArrayList<>(targetHosts);
        if (netBiosTargets.size() > scanRange) {
            netBiosTargets = new ArrayList<>(netBiosTargets.subList(0, scanRange));
        }
        final int netBiosTotal = netBiosTargets.size();
        final CountDownLatch latch = new CountDownLatch(netBiosTotal);

        for (String host : netBiosTargets) {
            if (!isScanning) {
                // 如果扫描被停止，释放所有剩余的latch
                for (int j = 0; j < latch.getCount(); j++) {
                    latch.countDown();
                }
                break;
            }
            
            final String ip = host;

            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 中断时释放所有latch
                for (int j = 0; j < latch.getCount(); j++) {
                    latch.countDown();
                }
                break;
            }
            
            executorService.execute(() -> {
                try {
                    if (isScanning) {
                        String netbiosName = getNetBiosName(ip);
                        if (netbiosName != null && !netbiosName.isEmpty()) {
                            addDeviceWithPriority(ip, netbiosName,
                                DeviceInfo.ICON_COMPUTER, PRIORITY_NETBIOS);
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "NetBIOS扫描异常: " + e.getMessage());
                } finally {
                    semaphore.release();
                    progress.incrementAndGet();
                    latch.countDown();
                }
            });
        }
        
        // 等待所有任务完成，最多等待30秒
        try {
            boolean finished = latch.await(30, TimeUnit.SECONDS);
            if (!finished) {
                android.util.Log.w(TAG, "NetBIOS扫描超时，已完成 " + (scanRange - latch.getCount()) + "/" + scanRange);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取NetBIOS扫描范围
     */
    private int getNetBiosScanRange() {
        switch (scanSpeed) {
            case ScanSpeedConfig.SPEED_FAST:
                return 255;  // 快速：全部IP
            case ScanSpeedConfig.SPEED_SLOW:
                return 100;  // 慢速：前100个
            default:
                return 255;  // 标准：全部IP
        }
    }

    private String getNetBiosName(String ip) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(400);
            
            byte[] query = new byte[] {
                (byte)0x80, (byte)0x94, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x20, 0x43, 0x4B, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x00, 0x00, 0x21,
                0x00, 0x01
            };
            
            InetAddress address = InetAddress.getByName(ip);
            DatagramPacket packet = new DatagramPacket(query, query.length, address, 137);
            socket.send(packet);
            
            byte[] buffer = new byte[512];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            DebugLogger.logDiscovery("NetBIOS", "收到响应", response.getAddress().getHostAddress() + " | 长度=" + response.getLength());
            
            return parseNetBiosName(buffer, response.getLength());
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }
    
    private String parseNetBiosName(byte[] buffer, int length) {
        String name = parseNbstatComputerName(buffer, length);
        if (name == null || name.isEmpty()) {
            DebugLogger.logDiscovery("NetBIOS", "解析失败",
                    "报文长度=" + length + "，未找到 NBSTAT 计算机名");
            return null;
        }
        DebugLogger.logDiscovery("NetBIOS", "解析名称", name);
        return name;
    }

    /**
     * 解析 NBSTAT（节点状态应答）里的计算机名。
     *
     * <p>应答结构：12 字节头 → 问题区 → 若干资源记录（Answer）。
     * NBSTAT 记录的 RDATA 是「1 字节名称数量 + N×(15 字节名称 + 1 字节后缀 + 2 字节标志）」。
     * 旧实现按固定偏移 57/73 硬读，压缩指针或问题区长度不同的报文就会读空，
     * 于是日志里有「收到响应」却没有标题。</p>
     */
    private String parseNbstatComputerName(byte[] buffer, int length) {
        if (buffer == null || length < 12) return null;
        try {
            int qdCount = readUShort(buffer, 4, length);
            int anCount = readUShort(buffer, 6, length);
            int offset = 12;

            // 跳过问题区
            for (int i = 0; i < qdCount && offset < length; i++) {
                offset = skipDnsName(buffer, offset, length);
                offset += 4; // QTYPE + QCLASS
            }

            String best = null;
            int bestScore = -1;
            for (int i = 0; i < anCount && offset + 10 <= length; i++) {
                offset = skipDnsName(buffer, offset, length);
                if (offset + 10 > length) break;
                int type = readUShort(buffer, offset, length);
                int rdLength = readUShort(buffer, offset + 8, length);
                int rdataStart = offset + 10;
                if (rdLength <= 0 || rdataStart + rdLength > length) break;

                if (type == 0x0021) { // NBSTAT
                    int nameCount = buffer[rdataStart] & 0xFF;
                    int pos = rdataStart + 1;
                    for (int n = 0; n < nameCount && pos + 18 <= rdataStart + rdLength; n++, pos += 18) {
                        String name = decodeNetBiosName(buffer, pos);
                        if (name == null || name.isEmpty()) continue;
                        int suffix = buffer[pos + 15] & 0xFF;
                        int flags = readUShort(buffer, pos + 16, length);
                        boolean group = (flags & 0x8000) != 0;
                        int score;
                        if (group) {
                            score = 0;              // 工作组/域，仅在别无选择时用
                        } else if (suffix == 0x00) {
                            score = 3;              // 工作站名，最准
                        } else if (suffix == 0x20) {
                            score = 2;              // 文件服务名
                        } else {
                            score = 1;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = name;
                        }
                    }
                }
                offset = rdataStart + rdLength;
            }

            if (best == null) return null;
            // 只拿到工作组名时不当作标题
            if (isWorkgroupName(best) && bestScore <= 0) return null;
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取无符号 16 位整数 */
    private int readUShort(byte[] buffer, int offset, int length) {
        if (offset < 0 || offset + 1 >= length) return 0;
        return ((buffer[offset] & 0xFF) << 8) | (buffer[offset + 1] & 0xFF);
    }

    /** 跳过 DNS 名字（含压缩指针），返回名字之后的偏移 */
    private int skipDnsName(byte[] buffer, int offset, int length) {
        while (offset < length) {
            int len = buffer[offset] & 0xFF;
            if (len == 0) {
                offset++;
                break;
            }
            if ((len & 0xC0) == 0xC0) { // 压缩指针
                offset += 2;
                break;
            }
            offset += len + 1;
        }
        return offset;
    }

    /** 15 字节 NetBIOS 名称：ASCII、右补空格或 \0 */
    private String decodeNetBiosName(byte[] buffer, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            int value = buffer[offset + i] & 0xFF;
            if (value == 0) break;
            if (value < 32 || value >= 127) continue;
            sb.append((char) value);
        }
        String name = sb.toString().trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * 判断是否是常见的工作组名
     */
    private boolean isWorkgroupName(String name) {
        if (name == null || name.isEmpty()) return false;
        
        String[] workgroupNames = {
            "WORKGROUP", "WORKGROUP", "MSHOME", "HOME", "DOMAIN",
            "WORK", "HOME", "LAN", "NETWORK", "LOCAL"
        };
        
        for (String workgroup : workgroupNames) {
            if (name.equalsIgnoreCase(workgroup)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * SSDP 设备搜索
     */
    private void searchSsdpDevices() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setTimeToLive(2);
            socket.setSoTimeout(1500);
            
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.joinGroup(group);
            
            String discoverMessage = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n" +
                    "\r\n";
            
            byte[] buffer = discoverMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 1900);
            socket.send(packet);
            
            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String responseStr = new String(response.getData(), 0, response.getLength());
                    String senderIp = response.getAddress().getHostAddress();
                    String location = extractHeaderValue(responseStr, "LOCATION");
                    String deviceIp = location != null ? extractHostFromLocation(location) : null;
                    String displayIp = deviceIp != null ? deviceIp : senderIp;
                    DebugLogger.logDiscovery("SSDP", "收到响应", displayIp + " | " + responseStr.substring(0, Math.min(400, responseStr.length())).replace("\r\n", " | "));
                    DebugLogger.logBroadcast("SSDP", displayIp, responseStr);

                    String server = extractHeaderValue(responseStr, "Server");
                    String name = server != null ? server : "SSDP设备";
                    Integer ssdpPort = null;
                    String upnpSerial = null;
                    String upnpMac = null;

                    // 抓取 UPnP 设备描述，取友好名/型号/序列号/MAC/端口
                    String[] desc = fetchUpnpDescription(location);
                    if (desc != null) {
                        String friendly = desc[0];
                        String model = desc[1];
                        String manufacturer = desc[2];
                        upnpSerial = desc[3];
                        upnpMac = desc[4];
                        try { ssdpPort = Integer.parseInt(desc[5]); } catch (Exception ignored) {}

                        StringBuilder sb = new StringBuilder();
                        if (friendly != null) sb.append(friendly);
                        if (model != null && (friendly == null || !friendly.contains(model))) {
                            if (sb.length() > 0) sb.append(" · ");
                            sb.append(model);
                        }
                        if (sb.length() > 0) {
                            name = sb.toString();
                        } else if (manufacturer != null) {
                            name = manufacturer;
                        }
                    }

                    if (name.equals(server) || name.equals("SSDP设备")) {
                        // 描述抓取失败时退回用 Server 头分类
                        if (responseStr.contains("Windows")) {
                            name = "【电脑】Windows设备";
                        } else if (responseStr.contains("DLNA")) {
                            name = "【投屏】DLNA设备";
                        } else if (server != null) {
                            name = parseUpnpServerName(server);
                        }
                    }

                    // 从 LOCATION 提取端口作为 HTTP 端口
                    if (ssdpPort == null) {
                        try {
                            int p = location != null ? new java.net.URL(location).getPort() : -1;
                            ssdpPort = p > 0 ? p : 80;
                        } catch (Exception ignored) {}
                    }

                    addDeviceWithPriority(deviceIp != null ? deviceIp : senderIp, name,
                        null, DeviceInfo.ICON_SSDP, PRIORITY_SSDP, upnpMac, ssdpPort);

                    // 序列号写入描述，方便查看详情
                    final String serialFinal = upnpSerial;
                    if (serialFinal != null) {
                        mainHandler.post(() -> {
                            for (DeviceInfo d : deviceList) {
                                if (d.getIp() != null && d.getIp().equals(displayIp)) {
                                    String old = d.getDescription();
                                    String serialLine = "序列号: " + serialFinal;
                                    if (old == null || old.isEmpty()) {
                                        d.setDescription(serialLine);
                                    } else if (!old.contains(serialFinal)) {
                                        d.setDescription(old + "\n" + serialLine);
                                    }
                                    break;
                                }
                            }
                        });
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            
            socket.leaveGroup(group);
        } catch (Exception e) {
            DebugLogger.logDiscovery("SSDP", "搜索失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }
    
    /**
     * SSDP IPv6 多播发现
     * 发送 M-SEARCH 到 ff02::c:1900（IPv6 SSDP 链路本地多播）
     */
    private void searchSsdpDevicesV6() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setTimeToLive(2);
            socket.setSoTimeout(1500);

            InetAddress group = InetAddress.getByName("ff02::c");
            socket.joinGroup(group);

            String discoverMessage = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: [ff02::c]:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n" +
                    "\r\n";

            byte[] buffer = discoverMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 1900);
            socket.send(packet);

            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String senderIp = response.getAddress().getHostAddress();
                    String responseStr = new String(response.getData(), 0, response.getLength());
                    String location = extractHeaderValue(responseStr, "LOCATION");
                    String deviceIp = location != null ? extractHostFromLocation(location) : null;
                    String displayIp = deviceIp != null ? deviceIp : senderIp;
                    DebugLogger.logDiscovery("SSDP_IPv6", "收到响应", displayIp + " | " + responseStr.substring(0, Math.min(300, responseStr.length())).replace("\r\n", " | "));
                    DebugLogger.logBroadcast("SSDP_IPv6", displayIp, responseStr);

                    String server = extractHeaderValue(responseStr, "Server");
                    String name = server != null ? server : "SSDP设备(IPv6)";

                    if (server != null && server.contains("Windows")) {
                        name = "【电脑】Windows设备(IPv6)";
                    } else if (server != null && server.contains("DLNA")) {
                        name = "【投屏】DLNA设备(IPv6)";
                    } else if (server != null) {
                        name = parseUpnpServerName(server);
                    }

                    addDeviceWithPriority(deviceIp != null ? deviceIp : senderIp, name, DeviceInfo.ICON_SSDP, PRIORITY_SSDP);
                } catch (SocketTimeoutException e) {
                    break;
                }
            }

            socket.leaveGroup(group);
        } catch (Exception e) {
            DebugLogger.logDiscovery("SSDP_IPv6", "搜索失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * mDNS IPv6 多播发现
     * 发送DNS-SD查询到 ff02::fb:5353，设备会响应自己的主机名
     */
    private void searchMdnsDevicesV6() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setTimeToLive(2);
            socket.setSoTimeout(1500);
            InetAddress group = InetAddress.getByName("ff02::fb");
            socket.joinGroup(group);

            byte[] query = buildMdnsQuery("_services._dns-sd._udp.local", 12);
            DatagramPacket packet = new DatagramPacket(query, query.length, group, 5353);
            socket.send(packet);

            byte[] queryHttp = buildMdnsQuery("_http._tcp.local", 12);
            socket.send(new DatagramPacket(queryHttp, queryHttp.length, group, 5353));
            byte[] queryDevice = buildMdnsQuery("_device-info._tcp.local", 12);
            socket.send(new DatagramPacket(queryDevice, queryDevice.length, group, 5353));
            byte[] queryWorkstation = buildMdnsQuery("_workstation._tcp.local", 12);
            socket.send(new DatagramPacket(queryWorkstation, queryWorkstation.length, group, 5353));

            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String senderIp = response.getAddress().getHostAddress();
                    String hostname = parseMdnsHostname(receiveBuffer, response.getLength());
                    DebugLogger.logDiscovery("mDNS_IPv6", "收到响应", senderIp + " | 主机名=" + (hostname != null ? hostname : "null"));
                    DebugLogger.logBroadcast("mDNS_IPv6", senderIp,
                            "主机名=" + (hostname != null ? hostname : "(无)") + "\n原始报文(长度="
                                    + response.getLength() + "):\n"
                                    + RuntimeLogStore.bytesToDisplay(receiveBuffer, response.getLength()));
                    if (hostname != null) {
                        addDeviceWithPriority(senderIp, hostname + "(IPv6)",
                            DeviceInfo.ICON_COMPUTER, PRIORITY_MDNS);
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            DebugLogger.logDiscovery("mDNS_IPv6", "搜索失败", e.getMessage());
            android.util.Log.w(TAG, "mDNS IPv6搜索失败: " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.leaveGroup(InetAddress.getByName("ff02::fb")); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * mDNS 多播发现（Apple Bonjour / IoT设备）
     * 发送DNS-SD查询到224.0.0.251:5353，设备会响应自己的主机名
     */
    private void searchMdnsDevices() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setTimeToLive(2);
            socket.setSoTimeout(1500);
            InetAddress group = InetAddress.getByName("224.0.0.251");

            // 构建DNS查询：_services._dns-sd._udp.local PTR
            byte[] query = buildMdnsQuery("_services._dns-sd._udp.local", 12); // PTR
            DatagramPacket packet = new DatagramPacket(query, query.length, group, 5353);
            socket.send(packet);

            // 额外查 _http._tcp.local 和 _device-info._tcp.local
            byte[] queryHttp = buildMdnsQuery("_http._tcp.local", 12);
            socket.send(new DatagramPacket(queryHttp, queryHttp.length, group, 5353));
            byte[] queryDevice = buildMdnsQuery("_device-info._tcp.local", 12);
            socket.send(new DatagramPacket(queryDevice, queryDevice.length, group, 5353));
            byte[] queryWorkstation = buildMdnsQuery("_workstation._tcp.local", 12);
            socket.send(new DatagramPacket(queryWorkstation, queryWorkstation.length, group, 5353));

            byte[] receiveBuffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String senderIp = response.getAddress().getHostAddress();

                    String hostname = parseMdnsHostname(receiveBuffer, response.getLength());
                    Integer mdnsPort = parseMdnsSrvPort(receiveBuffer, response.getLength());
                    DebugLogger.logDiscovery("mDNS", "收到响应", senderIp + " | 主机名=" + (hostname != null ? hostname : "null")
                            + (mdnsPort != null ? " | 端口=" + mdnsPort : ""));
                    // 记录原始 mDNS 报文（十六进制+ASCII），供运行日志核对解析结果
                    DebugLogger.logBroadcast("mDNS", senderIp,
                            "主机名=" + (hostname != null ? hostname : "(无)")
                                    + (mdnsPort != null ? " | 服务端口=" + mdnsPort : "")
                                    + "\n原始报文(长度=" + response.getLength() + "):\n"
                                    + RuntimeLogStore.bytesToDisplay(receiveBuffer, response.getLength()));
                    if (hostname != null) {
                        addDeviceWithPriority(senderIp, hostname, null,
                            DeviceInfo.ICON_COMPUTER, PRIORITY_MDNS, null, mdnsPort);
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            DebugLogger.logDiscovery("mDNS", "搜索失败", e.getMessage());
            android.util.Log.w(TAG, "mDNS搜索失败: " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.leaveGroup(InetAddress.getByName("224.0.0.251")); } catch (Exception e) {}
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * 构建mDNS DNS查询包
     */
    private byte[] buildMdnsQuery(String name, int qtype) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            // DNS Header (12 bytes)
            os.write(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            // Encode QNAME
            for (String label : name.split("\\.")) {
                os.write((byte) label.length());
                os.write(label.getBytes(StandardCharsets.UTF_8));
            }
            os.write(0x00); // terminator
            // QTYPE (2 bytes), QCLASS (2 bytes) with unicast-response bit
            os.write(new byte[]{(byte)(qtype >> 8), (byte)(qtype & 0xFF), 0x00, 0x01});
        } catch (IOException e) {
            return new byte[0];
        }
        return os.toByteArray();
    }

    /**
     * 从mDNS响应中提取主机名
     */
    /**
     * 从 mDNS 响应中解析 SRV 记录的端口号（type=33）。
     *
     * mDNS 服务实例（如 _http._tcp.local）通过 SRV 记录通告监听端口。
     * 这里遍历所有 answer，找到 SRV 记录后取出端口（RDATA 中第 5-6 字节）。
     *
     * @return 端口号；未找到 SRV 记录时返回 null
     */
    private Integer parseMdnsSrvPort(byte[] data, int length) {
        if (length < 12) return null;
        try {
            int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            if (ancount == 0) return null;

            // 跳过 header + question section
            int offset = 12;
            for (int q = 0; q < qdcount && offset < length; q++) {
                while (offset < length && data[offset] != 0) {
                    offset += (data[offset] & 0xFF) + 1;
                }
                offset += 1 + 4; // 终止符 + QTYPE(2) + QCLASS(2)
            }

            for (int a = 0; a < ancount && offset < length; a++) {
                // NAME（可能是压缩指针）
                if ((data[offset] & 0xC0) == 0xC0) {
                    offset += 2;
                } else {
                    while (offset < length && data[offset] != 0) {
                        offset += (data[offset] & 0xFF) + 1;
                    }
                    offset++;
                }
                if (offset + 10 > length) break;
                int type = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 8; // TYPE(2) + CLASS(2) + TTL(4)
                int rdlength = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;

                if (type == 33 && rdlength >= 6 && offset + rdlength <= length) {
                    // SRV RDATA: Priority(2) Weight(2) Port(2) Target(...)
                    int port = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);
                    if (port > 0 && port <= 65535) return port;
                }
                offset += rdlength;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String parseMdnsHostname(byte[] data, int length) {
        if (length < 12) return null;
        try {
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            if (ancount == 0) return null;

            // 跳过header(12) + question section
            int offset = 12;
            while (offset < length && data[offset] != 0) {
                offset += (data[offset] & 0xFF) + 1;
            }
            offset += 5; // 跳过终止符+QTYPE+QCLASS

            // 解析第一个answer的NAME
            int nameOffset;
            if ((data[offset] & 0xC0) == 0xC0) {
                nameOffset = ((data[offset] & 0x3F) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
            } else {
                nameOffset = offset;
                while (offset < length && data[offset] != 0) {
                    offset += (data[offset] & 0xFF) + 1;
                }
                offset++; // terminator
            }
            offset += 8; // TYPE(2) + CLASS(2) + TTL(4)
            int rdlength = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            offset += 2;

            // 从NAME（或RDATA中的PTR target）提取第一个标签作为主机名
            int hostStart;
            if ((data[nameOffset] & 0xC0) == 0xC0 && nameOffset + 1 < length) {
                hostStart = ((data[nameOffset] & 0x3F) << 8) | (data[nameOffset + 1] & 0xFF);
            } else {
                hostStart = nameOffset + 1;
            }
            StringBuilder sb = new StringBuilder();
            while (hostStart < length && data[hostStart] != 0) {
                int labelLen = data[hostStart] & 0xFF;
                hostStart++;
                for (int i = 0; i < labelLen && hostStart < length; i++) {
                    char c = (char) data[hostStart++];
                    if (c >= 32 && c < 127) sb.append(c);
                }
                if (data[hostStart] != 0) sb.append(".");
            }
            String result = sb.toString().trim();
            return result.isEmpty() || result.contains("_services") || result.contains("_dns-sd") ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * UDP 广播发现
     */
    private void searchUdpDevices() {
        int[] probePorts = {80, 8080};
        
        for (int port : probePorts) {
            if (!isScanning) break;
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(500);
                
                String message = "HELLO";
                byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcast, port);
                socket.send(packet);
                
                byte[] receiveBuffer = new byte[2048];
                try {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);
                    String senderIp = response.getAddress().getHostAddress();
                    DebugLogger.logDiscovery("UDP广播", "收到响应", senderIp + " | 端口=" + port);
                    DebugLogger.logBroadcast("UDP广播", senderIp, "端口=" + port);
                    
                    addDeviceWithPriority(senderIp, "网络设备", 
                        DeviceInfo.ICON_UNKNOWN, PRIORITY_UDP);
                } catch (SocketTimeoutException e) {
                    // 超时
                }
            } catch (Exception e) {
                // 忽略
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception e) {}
                }
            }
        }
    }
    
    /**
     * 扫描路由器后台地址
     * 各路由器试自己的hostname（路由器本地DNS拦截），网关单独做HTTP标题匹配
     */
    private void scanRouterBackends() {
        if (gateway == null || routerList.isEmpty() || !isScanning) return;
        
        int total = routerList.size();
        int concurrency = Math.min(10, total);
        final Semaphore semaphore = new Semaphore(concurrency);
        final AtomicInteger progress = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(total);
        
        for (RouterInfo router : routerList) {
            if (!isScanning) {
                for (int i = 0; i < latch.getCount(); i++) latch.countDown();
                break;
            }
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            executorService.execute(() -> {
                try {
                    if (isScanning) tryRouterConnect(router);
                } catch (Exception e) {
                    android.util.Log.d(TAG, "路由器扫描失败: " + router.link);
                } finally {
                    semaphore.release();
                    int current = progress.incrementAndGet();
                    reportProgress("扫描路由器后台 " + current + "/" + total, current, total);
                    latch.countDown();
                }
            });
        }
        try {
            boolean finished = latch.await(30, TimeUnit.SECONDS);
            if (!finished) {
                android.util.Log.w(TAG, "路由器后台扫描超时，已完成 " + (total - latch.getCount()) + "/" + total);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 最后单独扫网关，取HTTP标题匹配品牌
        if (isScanning && gateway != null) {
            httpExecutor.execute(() -> {
                if (!isScanning) return;
                // 如果网关已被hostname方式添加，不再重复加
                synchronized (scannedIps) {
                    if (scannedIps.containsKey(gateway)) return;
                }
                
                String[] result = httpFetchTitle(gateway, 80);
                if (result == null && isScanning) result = httpFetchTitle(gateway, 443);
                if (result != null && isScanning) {
                    String title = result[0];
                    if (title == null || title.isEmpty()) title = "HTTP服务 (网关)";
                    String matchedBrand = null;
                    for (RouterInfo router : routerList) {
                        if (title.toLowerCase().contains(router.brand.replace("路由", "").toLowerCase())) {
                            matchedBrand = router.brand;
                            break;
                        }
                    }
                    addDeviceWithPriority(gateway, matchedBrand != null ? matchedBrand : title, DeviceInfo.ICON_UNKNOWN, PRIORITY_ROUTER);
                }
            });
        }
    }
    
    /**
     * 尝试通过主机名连接路由器（路由器本地DNS拦截，无需外网）
     */
    private void tryRouterConnect(RouterInfo router) {
        int[] ports = {80, 443};
        for (int port : ports) {
            if (!isScanning) return;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(router.link, port), 500);
                String ip = socket.getInetAddress().getHostAddress();
                if (ip != null && isPrivateIP(ip)) {
                    addDeviceWithPriority(ip, router.brand, router.link, DeviceInfo.ICON_UNKNOWN, PRIORITY_ROUTER);
                    String finalIp = ip;
                    String finalUrl = router.link;
                    httpExecutor.execute(() -> {
                        String[] res = httpFetchTitle(finalIp, port);
                        if (res != null) {
                            String title = res[0];
                            if (title == null || title.isEmpty()) title = "HTTP服务";
                            addDeviceWithPriority(finalIp, title, finalUrl, DeviceInfo.ICON_HTTP, PRIORITY_HTTP);
                        }
                    });
                    return;
                }
            } catch (Exception e) {
            }
        }
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 添加设备（带优先级控制）
     */
    private void addDeviceWithPriority(String ip, String title, int iconType, int priority) {
        addDeviceWithPriority(ip, title, null, iconType, priority, null, null);
    }

    private void addDeviceWithPriority(String ip, String title, int iconType,
                                       int priority, String mac) {
        addDeviceWithPriority(ip, title, null, iconType, priority, mac, null);
    }

    private void addDeviceWithPriority(String ip, String title, String description, int iconType, int priority) {
        addDeviceWithPriority(ip, title, description, iconType, priority, null, null);
    }

    private void addDeviceWithPriority(String ip, String title, String description,
                                       int iconType, int priority, String mac) {
        addDeviceWithPriority(ip, title, description, iconType, priority, mac, null);
    }

    /**
     * 带端口的设备登记：记录标题、MAC、描述与发现的服务端口。
     *
     * @param port 发现的服务端口（如 HTTP 80、RTSP 554）；可为 null
     */
    private void addDeviceWithPriority(String ip, String title, String description,
                                       int iconType, int priority, String mac, Integer port) {
        if (ip == null || ip.isEmpty()) return;
        
        final String finalIp = ip;
        final String finalTitle = title != null ? title : "未知设备";
        final String finalDesc = description;
        final int finalIconType = iconType;
        final int finalPriority = priority;
        final String finalMac = mac;
        final Integer finalPort = port;
        final String protocolLabel = PROTOCOL_LABELS.getOrDefault(priority, "未知协议");
        
        mainHandler.post(() -> {
            // 检查是否已存在
            for (DeviceInfo device : deviceList) {
                if (device.getIp().equals(finalIp)) {
                    device.addProtocolName(protocolLabel, finalTitle);
                    device.setDiscoveryPriority(finalPriority);
                    if (finalMac != null && !finalMac.isEmpty()) {
                        device.setMacAddress(finalMac);
                    }
                    if (finalPort != null) {
                        device.addPort(finalPort);
                    }
                    Integer currentPriority = devicePriorities.get(finalIp);
                    boolean betterName = currentPriority == null || finalPriority <= currentPriority
                            || (device.getIconType() == DeviceInfo.ICON_PING
                            && finalIconType != DeviceInfo.ICON_PING);
                    if (betterName) {
                        device.setTitle(finalTitle);
                        device.setIconType(finalIconType);
                        if (finalDesc != null) device.setDescription(finalDesc);
                        devicePriorities.put(finalIp, finalPriority);
                        deviceNames.put(finalIp, finalTitle);
                        
                        if (listener != null) {
                            listener.onDeviceUpdated(device);
                        }
                    }
                    cachedDevices = new ArrayList<>(deviceList);
                    return;
                }
            }
            
            // 添加新设备
            DeviceInfo device = new DeviceInfo();
            device.setIp(finalIp);
            device.setTitle(finalTitle);
            device.setIconType(finalIconType);
            device.setDiscoveryPriority(finalPriority);
            if (finalDesc != null) device.setDescription(finalDesc);
            if (finalMac != null && !finalMac.isEmpty()) {
                device.setMacAddress(finalMac);
            }
            if (finalPort != null) {
                device.addPort(finalPort);
            }
            device.addProtocolName(protocolLabel, finalTitle);
            
            deviceList.add(device);
            devicePriorities.put(finalIp, finalPriority);
            deviceNames.put(finalIp, finalTitle);
            scannedIps.put(finalIp, true);
            
            cachedDevices = new ArrayList<>(deviceList);
            if (listener != null) {
                listener.onDeviceFound(device);
            }
        });
    }
    
    private boolean isPrivateIP(String ipStr) {
        if (ipStr == null || ipStr.isEmpty()) return false;
        // IPv4 私有地址
        if (ipStr.startsWith("10.") ||
            ipStr.startsWith("192.168.") ||
            ipStr.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) return true;
        // IPv6 链路本地地址 (fe80::/10)
        if (ipStr.toLowerCase(java.util.Locale.US).startsWith("fe80")) return true;
        return false;
    }
    
    private String extractHeaderValue(String response, String headerName) {
        if (response == null || headerName == null) return null;
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase() + ":")) {
                int colonIndex = line.indexOf(":");
                if (colonIndex != -1 && colonIndex + 1 < line.length()) {
                    return line.substring(colonIndex + 1).trim();
                }
            }
        }
        return null;
    }

    private String extractHostFromLocation(String location) {
        if (location == null) return null;
        try {
            return new java.net.URL(location).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 LOCATION 指向的 UPnP 设备描述 XML 中提取友好名、型号、序列号、MAC 与端口。
     *
     * SSDP 的 M-SEARCH 响应本身只有 Server/ST 头，设备真名在描述文档里，
     * 因此需要再请求一次 LOCATION。解析失败时各字段为 null，不影响主流程。
     */
    private String[] fetchUpnpDescription(String location) {
        if (location == null || location.isEmpty()) return null;
        OkHttpClient client = null;
        try {
            client = new OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
            Request request = new Request.Builder().url(location).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.body() == null) return null;
                String xml = response.body().string();
                if (xml == null || xml.isEmpty()) return null;

                String friendly = extractXmlValueAnyCase(xml, "friendlyName");
                String model = extractXmlValueAnyCase(xml, "modelName");
                String manufacturer = extractXmlValueAnyCase(xml, "manufacturer");
                String serial = extractXmlValueAnyCase(xml, "serialNumber");

                // MAC 可能在 serialNumber / UDN / 专门的 MACAddress 元素里
                String mac = extractMacFromText(serial);
                if (mac == null) mac = extractMacFromText(extractXmlValueAnyCase(xml, "UDN"));
                if (mac == null) mac = extractMacFromText(extractXmlValueAnyCase(xml, "MACAddress"));
                if (mac == null) mac = extractMacFromText(serial);

                int port = 80;
                try {
                    int p = new java.net.URL(location).getPort();
                    if (p > 0) port = p;
                } catch (Exception ignored) {}

                return new String[]{friendly, model, manufacturer, serial, mac, String.valueOf(port)};
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (client != null) {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            }
        }
    }

    /** 大小写无关地提取 XML 标签内容 */
    private String extractXmlValueAnyCase(String content, String tag) {
        if (content == null || tag == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<\\s*" + java.util.regex.Pattern.quote(tag) + "[^>]*>(.*?)</\\s*"
                        + java.util.regex.Pattern.quote(tag) + "\\s*>",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(content);
        if (m.find()) {
            String v = m.group(1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    /** 从任意文本中提取 MAC 地址（支持冒号/连字符/12位十六进制） */
    private String extractMacFromText(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)([0-9A-F]{2}(?:[:-][0-9A-F]{2}){5})")
                .matcher(text);
        if (m.find()) return m.group(1).toUpperCase(java.util.Locale.US);
        // 尝试 12 位连续十六进制（排除已是 UUID 的情况）
        m = java.util.regex.Pattern.compile("(?i)\\b([0-9A-F]{12})\\b").matcher(text);
        if (m.find()) {
            String h = m.group(1).toUpperCase(java.util.Locale.US);
            return h.replaceAll("(..)(?!$)", "$1:");
        }
        return null;
    }

    private String extractXmlValue(String content, String tagName) {
        if (content == null || tagName == null) return null;
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";
        int startIndex = content.indexOf(startTag);
        if (startIndex == -1) return null;
        startIndex += startTag.length();
        int endIndex = content.indexOf(endTag, startIndex);
        if (endIndex == -1) return null;
        return content.substring(startIndex, endIndex).trim();
    }

    /**
     * 从 ONVIF WS-Discovery 响应的 Scopes 中提取设备标题。
     *
     * Scopes 形如：
     *   onvif://www.onvif.org/name/ONVIF_IPNC
     *   onvif://www.onvif.org/Hardware/DM36X_IPNC
     *   onvif://www.onvif.org/name/HD%20IPC   （需 URL 解码）
     * 其中 name/hardware 的大小写在不同厂商间不统一，这里做大小写无关匹配。
     *
     * @return 形如 "ONVIF_IPNC (DM36X_IPNC)" 的标题；提取不到时返回 null
     */
    private String extractOnvifTitle(String responseStr) {
        if (responseStr == null) return null;
        // 定位 <...Scopes ...> 与对应的 </...Scopes>
        int openTag = responseStr.indexOf("Scopes");
        if (openTag == -1) return null;
        int tagEnd = responseStr.indexOf('>', openTag);
        if (tagEnd == -1) return null;
        int contentStart = tagEnd + 1;
        int closeTag = responseStr.indexOf("</", contentStart);
        if (closeTag == -1) return null;

        String scopes = responseStr.substring(contentStart, closeTag);
        String name = null;
        String hardware = null;
        for (String token : scopes.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(java.util.Locale.US);
            int nameIdx = lower.indexOf("/name/");
            int hwIdx = lower.indexOf("/hardware/");
            if (nameIdx >= 0 && name == null) {
                name = token.substring(nameIdx + 6);
            } else if (hwIdx >= 0 && hardware == null) {
                hardware = token.substring(hwIdx + 10);
            }
        }
        name = decodeScopeValue(name);
        hardware = decodeScopeValue(hardware);

        if (name == null && hardware == null) return null;
        if (name == null) return hardware;
        if (hardware == null || hardware.equalsIgnoreCase(name)) return name;
        return name + " (" + hardware + ")";
    }

    /** URL 解码 scope 值（如 HD%20IPC → HD IPC） */
    private String decodeScopeValue(String v) {
        if (v == null || v.isEmpty()) return null;
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            decoded = v;
        }
        decoded = decoded.trim();
        return decoded.isEmpty() ? null : decoded;
    }

    /**
     * 从 ONVIF 响应的 XAddrs 中提取服务端口。
     *
     * XAddrs 形如 http://192.168.1.10:80/onvif/device_service，
     * 端口缺省为 80。
     *
     * @return 服务端口；解析失败返回 null
     */
    private Integer extractOnvifPort(String responseStr) {
        if (responseStr == null) return null;
        int idx = responseStr.indexOf("XAddrs");
        if (idx == -1) return null;
        int tagEnd = responseStr.indexOf('>', idx);
        if (tagEnd == -1) return null;
        int contentEnd = responseStr.indexOf('<', tagEnd + 1);
        if (contentEnd == -1) return null;
        String xaddrs = responseStr.substring(tagEnd + 1, contentEnd).trim();
        if (xaddrs.isEmpty()) return null;
        // 可能有多个地址，取第一个
        String first = xaddrs.split("\\s+")[0];
        try {
            java.net.URI uri = new java.net.URI(first);
            int p = uri.getPort();
            return p > 0 ? p : 80;
        } catch (Exception e) {
            return 80;
        }
    }
    
    private void reportProgress(String status, int progress, int total) {
        if (listener != null) {
            mainHandler.post(() -> listener.onProgress(status, progress, total));
        }
    }
    
    private void reportError(String error) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(error));
        }
    }
    
    private void finishScan() {
        isScanning = false;
        
        // 扫描完成后按IP地址排序（IPv4在前，IPv6在后，各自排序）
        deviceList.sort((a, b) -> {
            int vA = a.getIpVersion();
            int vB = b.getIpVersion();
            if (vA != vB) return Integer.compare(vA, vB);
            return NetworkUtils.compareIp(a.getIp(), b.getIp());
        });
        
        cachedDevices = new ArrayList<>(deviceList);
        persistCachedDevices();
        if (listener != null) {
            mainHandler.post(() -> listener.onScanComplete(deviceList.size()));
        }
    }
    
    public List<DeviceInfo> getDeviceList() {
        return new ArrayList<>(deviceList);
    }
    
    public void stopScan() {
        isScanning = false;
        
        // 关闭所有线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        if (pingExecutor != null && !pingExecutor.isShutdown()) {
            pingExecutor.shutdownNow();
        }
        if (httpExecutor != null && !httpExecutor.isShutdown()) {
            httpExecutor.shutdownNow();
        }
    }
}
