package com.networktool.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网聊天管理器
 * 
 * 使用UDP广播发现设备，TCP直连传输消息
 * - UDP广播端口: 8888（发现好友）
 * - TCP服务端口: 9000（默认，可配置）
 * - UDP组播端口: 9001（房间聊天）
 */
public class LanChatManager {
    
    private static final String TAG = "LanChatManager";

    private final java.util.Map<Integer, java.net.MulticastSocket> multicastSockets = new java.util.concurrent.ConcurrentHashMap<>();
    // 端口→房间码映射，用于组播消息隔离
    private final java.util.Map<Integer, String> multicastPortRoomCodes = new java.util.concurrent.ConcurrentHashMap<>();
    // 端口→组播地址映射，用于离开组播组
    private final java.util.Map<Integer, String> multicastPortAddresses = new java.util.concurrent.ConcurrentHashMap<>();

    // 端口配置
    private int tcpServerPort = -1;  // 初始化时通过协商确定
    public static final int UDP_BROADCAST_PORT = 19001;  // 保留UDP广播端口（兼容老旧设备发现）
    public static final int UDP_MULTICAST_NEGOTIATION_PORT = 19000;  // 公共组播协商端口（所有设备先加入此通道）
    public static final int UDP_MULTICAST_DISCOVERY_PORT = 19103;  // 房间发现组播端口（公共端口）
    
    // 随机端口范围（避开系统保留端口）
    private static final int MIN_RANDOM_PORT = 49152;
    private static final int MAX_RANDOM_PORT = 60999;
    private static final int PORT_NEGOTIATION_TIMEOUT = 3000;  // 端口协商超时（毫秒）
    
    // 组播配置
    private static final String MULTICAST_ADDRESS = "239.255.0.1";  // 公共发现组播地址
    private static final int ROOM_MULTICAST_PORT = 19105;  // 房间组播固定端口

    // 广播间隔（毫秒）
    private static final long BROADCAST_INTERVAL = 3000;
    private static final String FILE_SAVE_DIR = "网络工具箱/files";
    
    // 用户配置
    private String userName;
    private final String deviceId = java.util.UUID.randomUUID().toString();  // 设备唯一ID，用于自过滤
    private long myUid;  // 用户唯一标识（逻辑身份），持久化
    private String currentRoomCode;  // 当前房间码（用于房间聊天）
    private boolean discoverable = true;  // 是否可被发现

    // 房间组播序列号（用于NACK丢包恢复）
    private final java.util.concurrent.atomic.AtomicInteger roomSeqCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    // 房间消息缓存（seq → ChatMessage），用于NACK重传
    private final java.util.Map<Integer, ChatMessage> roomMessageCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 房间接收端：记录每个房间最后收到的seq（用于检测丢包间隙）
    private final java.util.Map<String, Integer> roomLastReceivedSeq = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 房间成员管理（roomCode -> ConcurrentHashMap<uid, LanPeer>）
    private final java.util.Map<String, java.util.concurrent.ConcurrentHashMap<Long, LanPeer>> roomMembers = new java.util.concurrent.ConcurrentHashMap<>();
    // 房间封禁列表（roomCode -> Set<uid>）
    private final java.util.Map<String, java.util.Set<Long>> roomBanned = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 线程管理
    private ExecutorService executorService;
    private Handler mainHandler;
    private java.util.concurrent.ScheduledExecutorService scheduledExecutor;
    private java.util.concurrent.ScheduledFuture<?> roomHeartbeatFuture;
    
    // Socket相关
    private WifiManager.MulticastLock multicastLock;  // WiFi多播锁，确保能接收广播/组播包
    private MulticastSocket negotiationSocket;  // 用于端口协商的组播socket（公共通道）
    private ServerSocket tcpServerSocket;
    private boolean isRunning = false;
    
    // 端口协商状态
    private boolean portConfirmed = false;      // 端口是否已确认
    private int negotiatedPort = -1;            // 协商确定的端口
    private final java.util.Map<String, Integer> remoteClaimedPorts = new java.util.concurrent.ConcurrentHashMap<>();  // deviceId→已声明的端口
    
    // 数据库引用（用于好友操作）
    private Context appContext;
    
    // 好友列表
    private final CopyOnWriteArrayList<LanPeer> peers = new CopyOnWriteArrayList<>();
    
    // 回调 - 支持多个监听器
    private final CopyOnWriteArrayList<OnPeerChangeListener> peerChangeListeners = new CopyOnWriteArrayList<>();

    // 全局房间邀请监听器（用于非聊天页面接收邀请通知）
    public interface RoomInviteListener {
        void onRoomInviteReceived(String inviterName, String roomCode, String roomName);
    }
    private volatile RoomInviteListener roomInviteListener;
    
    // Gson
    private final Gson gson = new Gson();
    
    // 消息去重缓存 - 存储已处理消息的 key（时间戳 + 发送者 IP+ 端口 + 内容哈希），避免 UDP 和 TCP 重复传递
    private final Set<String> processedMessageCache = Collections.synchronizedSet(new LinkedHashSet<>(100));
    
    public Gson getGson() { return gson; }
    
    // 单例
    private static LanChatManager instance;
    
    public static synchronized LanChatManager getInstance() {
        if (instance == null) {
            instance = new LanChatManager();
        }
        return instance;
    }
    
    private LanChatManager() {
        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 初始化并开始服务
     * 流程：先加入公共组播协商组 → 协商随机端口 → 绑定端口 → 启动各服务
     */
    public void start(Context context, String userName, int tcpPort) {
        if (isRunning) {
            this.userName = userName;
            Log.d(TAG, "LanChatManager already running, updated config (port unchanged: " + tcpServerPort + ")");
            return;
        }

        try {
            this.appContext = context.getApplicationContext();
            this.userName = userName;
            this.currentRoomCode = "default";
            this.portConfirmed = false;
            this.negotiatedPort = -1;
            this.remoteClaimedPorts.clear();
            isRunning = true;

            // 初始化调度线程池（用于心跳等定时任务）
            if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
                scheduledExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            }

            // 加载或生成持久化 UID
            this.myUid = loadOrCreateUid();

            // 获取WiFi多播锁
            acquireMulticastLock();

            // 1. 先加入公共组播协商组 (224.0.5.88:19000)
            startNegotiationListener();
            Log.d(TAG, "LanChatManager: joined negotiation group");

            // 2. 通过组播协商随机端口
            startPortNegotiation();
            Log.d(TAG, "LanChatManager: port negotiation complete, port=" + negotiatedPort);
            DebugLogger.logDiscovery("LanChat", "端口协商完成", "端口: " + negotiatedPort);

            // 3. 绑定协商好的端口
            bindTcpPort(negotiatedPort);

            Log.d(TAG, "LanChatManager starting, user: " + userName + ", port: " + tcpServerPort);
            DebugLogger.logDiscovery("LanChat", "服务启动", "用户: " + userName + ", 端口: " + tcpServerPort);

            // 4. 通过组播发送在线宣告（替代UDP广播）
            startMulticastPresenceSender();

            // 首次启动时发送快速发现脉冲，加速好友识别
            executorService.execute(() -> {
                for (int i = 0; i < 5 && isRunning; i++) {
                    if (portConfirmed && tcpServerPort > 0) {
                        sendMulticastPresenceMessage();
                    }
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            });

            // 5. 启动TCP accept 循环
            startTcpAcceptLoop();

            // 6. 启动UDP组播（用于房间消息）
            startMulticast();

            // 7. 启动组播房间发现监听
            startMulticastDiscovery();

            // 8. 启动文件传输服务（端口 = 聊天端口 + 1）
            LanChatFileManager.getInstance().start(tcpServerPort);
            Log.d(TAG, "File transfer server started on port " + (tcpServerPort + 1));

            // 9. 定期刷新任务
            startPeriodicRefresh();

            Log.d(TAG, "LanChatManager started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start LanChatManager", e);
            DebugLogger.logDiscovery("LanChat", "服务启动失败", e.getMessage());
            isRunning = false;
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Failed to start LanChatManager", e);
        }
    }

    /**
     * 同步绑定TCP端口，支持端口冲突时自动切换备用端口
     * 改进的端口分配策略，支持在同一台设备上运行多个实例
     */
    private void bindTcpPort(int preferredPort) throws IOException {
        // 生成一系列可能的端口，从preferredPort开始，尝试最多100个连续端口
        int maxAttempts = 100;
        for (int i = 0; i < maxAttempts; i++) {
            int port = preferredPort + i;
            try {
                tcpServerSocket = new ServerSocket(port);
                tcpServerSocket.setReuseAddress(true);
                tcpServerPort = port;
                Log.d(TAG, "TCP server bound to port " + port);
                DebugLogger.logDiscovery("LanChat", "TCP端口绑定成功", "端口: " + port);
                // 保存实际绑定的端口到SharedPreferences，确保下次启动使用同一端口
                if (appContext != null) {
                    SharedPreferences lanChatPrefs = appContext.getSharedPreferences("lan_chat", Context.MODE_PRIVATE);
                    lanChatPrefs.edit().putInt("tcp_port", port).apply();
                }
                return;
            } catch (IOException e) {
                Log.w(TAG, "Port " + port + " is occupied, trying next: " + e.getMessage());
            }
        }
        throw new IOException("Failed to bind TCP server on any port after " + maxAttempts + " attempts");
    }

    /**
     * 加载或生成持久化 UID
     * UID 是用户唯一标识（逻辑身份），首次生成后持久化，重启不变
     */
    private long loadOrCreateUid() {
        if (appContext == null) return System.currentTimeMillis() & 0xFFFFFFFFL;
        SharedPreferences prefs = appContext.getSharedPreferences("lan_chat", Context.MODE_PRIVATE);
        long saved = prefs.getLong("user_uid", 0);
        if (saved == 0) {
            saved = System.currentTimeMillis() & 0xFFFFFFFFL;
            prefs.edit().putLong("user_uid", saved).apply();
            Log.d(TAG, "Generated new UID: " + saved);
        }
        return saved;
    }

    /**
     * 获取当前用户的 UID
     */
    public long getMyUid() { return myUid; }

    public void stop() {
        isRunning = false;
        portConfirmed = false;
        remoteClaimedPorts.clear();
        // 释放WiFi多播锁
        releaseMulticastLock();
        
        // 关闭协商组播socket
        if (negotiationSocket != null && !negotiationSocket.isClosed()) {
            try { negotiationSocket.leaveGroup(InetAddress.getByName(MULTICAST_ADDRESS)); } catch (Exception ignored) {}
            negotiationSocket.close();
        }
        
        // 关闭TCP服务器
        if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
            try {
                tcpServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing TCP server: " + e.getMessage());
            }
        }
        
        // 停止文件传输服务
        LanChatFileManager.getInstance().stop();
        
        // 关闭所有房间组播socket
        for (java.net.MulticastSocket socket : multicastSockets.values()) {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
        multicastSockets.clear();
        multicastPortRoomCodes.clear();
        multicastPortAddresses.clear();
        roomMessageCache.clear();
        roomLastReceivedSeq.clear();

        // 停止心跳
        stopRoomHeartbeat();
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow();
        }
        
        // 关闭所有TCP连接
        for (LanPeer peer : peers) {
            peer.disconnect();
        }
        peers.clear();
        
        Log.d(TAG, "LanChatManager stopped");
    }
    
    /**
     * 随机选取一个端口（避开系统保留端口范围）
     */
    private int pickRandomPort() {
        java.util.Random rnd = new java.util.Random();
        return MIN_RANDOM_PORT + rnd.nextInt(MAX_RANDOM_PORT - MIN_RANDOM_PORT + 1);
    }

    /**
     * 启动组播协商监听器 — 加入公共组播组 239.255.0.1:19000
     * 所有设备共用此通道进行端口协商和在线宣告
     */
    private void startNegotiationListener() {
        executorService.execute(() -> {
            try {
                InetAddress groupAddr = InetAddress.getByName(MULTICAST_ADDRESS);
                negotiationSocket = new MulticastSocket(UDP_MULTICAST_NEGOTIATION_PORT);
                negotiationSocket.setReuseAddress(true);
                negotiationSocket.joinGroup(groupAddr);
                negotiationSocket.setSoTimeout(1000);
                byte[] buffer = new byte[65535];
                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        negotiationSocket.receive(packet);

                        String data = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        String senderIp = packet.getAddress().getHostAddress();

                        BroadcastMessage msg = gson.fromJson(data, BroadcastMessage.class);
                        if (msg == null) continue;

                        // 跳过自己发送的消息
                        if (msg.getDeviceId() != null && msg.getDeviceId().equals(deviceId)) continue;

                        handleNegotiationMessage(msg, senderIp);
                    } catch (java.net.SocketTimeoutException e) {
                        // 正常超时
                    } catch (Exception e) {
                        if (!isRunning) break;
                        Log.e(TAG, "Negotiation listener error: " + e.getMessage());
                    }
                }
                negotiationSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start negotiation listener: " + e.getMessage());
                DebugLogger.logDiscovery("LanChat", "协商监听启动失败", e.getMessage());
            }
        });
    }

    /**
     * 处理组播协商消息
     * 消息类型: port_claim（声明端口）, port_confirm（确认端口）, presence（在线宣告）
     */
    private void handleNegotiationMessage(BroadcastMessage msg, String senderIp) {
        if (msg == null) return;
        String type = msg.getType();
        String remoteDeviceId = msg.getDeviceId();

        if ("port_claim".equals(type)) {
            int claimedPort = msg.getSenderPort();
            // 记录对方声明的端口
            if (remoteDeviceId != null && claimedPort > 0) {
                remoteClaimedPorts.put(remoteDeviceId, claimedPort);

                // 冲突检测
                if (portConfirmed && claimedPort == tcpServerPort) {
                    Log.w(TAG, "Port conflict with " + remoteDeviceId + " on port " + claimedPort);
                    if (deviceId.compareTo(remoteDeviceId) > 0) {
                        executorService.execute(this::renegotiatePort);
                    }
                }
            }

            // 如果本机端口已确认，给对方发送确认
            if (portConfirmed && tcpServerPort > 0) {
                sendPortConfirm(tcpServerPort);
            }
        } else if ("port_confirm".equals(type)) {
            int confirmedPort = msg.getSenderPort();
            if (remoteDeviceId != null && confirmedPort > 0) {
                remoteClaimedPorts.put(remoteDeviceId, confirmedPort);
            }
        } else if ("presence".equals(type)) {
            // 在线宣告消息，直接通过现有广播处理逻辑走
            handleBroadcastMessage(msg, senderIp);
        }
    }

    /**
     * 通过组播发送端口声明
     */
    private void sendPortClaim(int port) {
        try {
            if (negotiationSocket == null || negotiationSocket.isClosed()) return;
            BroadcastMessage msg = new BroadcastMessage();
            msg.setType("port_claim");
            msg.setSender(userName);
            msg.setDeviceId(deviceId);
            msg.setSenderPort(port);
            msg.setTimestamp(System.currentTimeMillis());

            String json = gson.toJson(msg);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            InetAddress groupAddr = InetAddress.getByName(MULTICAST_ADDRESS);
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddr, UDP_MULTICAST_NEGOTIATION_PORT);
            negotiationSocket.send(packet);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send PORT_CLAIM: " + e.getMessage());
        }
    }

    /**
     * 通过组播发送端口确认
     */
    private void sendPortConfirm(int port) {
        try {
            if (negotiationSocket == null || negotiationSocket.isClosed()) return;
            BroadcastMessage msg = new BroadcastMessage();
            msg.setType("port_confirm");
            msg.setSender(userName);
            msg.setDeviceId(deviceId);
            msg.setSenderPort(port);
            msg.setTimestamp(System.currentTimeMillis());

            String json = gson.toJson(msg);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            InetAddress groupAddr = InetAddress.getByName(MULTICAST_ADDRESS);
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddr, UDP_MULTICAST_NEGOTIATION_PORT);
            negotiationSocket.send(packet);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send PORT_CONFIRM: " + e.getMessage());
        }
    }

    /**
     * 启动端口协商流程
     * 1. 选取随机端口
     * 2. 发送 PORT_CLAIM
     * 3. 等待 PORT_NEGOTIATION_TIMEOUT 检测冲突
     * 4. 无冲突则发送 PORT_CONFIRM 并确认
     * 5. 有冲突则根据 deviceId 大小决定让步还是坚持
     */
    private void startPortNegotiation() {
        final int MAX_RETRIES = 20;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            int candidatePort = pickRandomPort();

            // 先快速检查本机端口是否可用
            try (java.net.ServerSocket testSocket = new java.net.ServerSocket(candidatePort)) {
                testSocket.close();
            } catch (IOException e) {
                continue;
            }

            // 发送端口声明
            sendPortClaim(candidatePort);

            // 等待检测冲突
            try {
                Thread.sleep(PORT_NEGOTIATION_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            // 检查是否有其他设备声明了相同端口
            boolean conflict = false;
            for (java.util.Map.Entry<String, Integer> entry : remoteClaimedPorts.entrySet()) {
                if (entry.getValue() == candidatePort && !entry.getKey().equals(deviceId)) {
                    if (entry.getKey().compareTo(deviceId) < 0) {
                        conflict = true;
                        break;
                    }
                }
            }

            if (!conflict) {
                portConfirmed = true;
                negotiatedPort = candidatePort;
                tcpServerPort = candidatePort;
                sendPortConfirm(candidatePort);

                if (appContext != null) {
                    appContext.getSharedPreferences("lan_chat", Context.MODE_PRIVATE)
                        .edit().putInt("tcp_port", candidatePort).apply();
                }
                Log.d(TAG, "端口协商完成: " + candidatePort);
                DebugLogger.logDiscovery("LanChat", "端口协商成功", "端口: " + candidatePort);
                return;
            }

            remoteClaimedPorts.clear();
        }

        Log.w(TAG, "端口协商失败，使用回退端口");
        negotiatedPort = pickRandomPort();
        tcpServerPort = negotiatedPort;
        portConfirmed = true;
    }

    /**
     * 检测到冲突后重新协商端口
     */
    private void renegotiatePort() {
        portConfirmed = false;
        negotiatedPort = -1;
        startPortNegotiation();

        // 重新绑定TCP端口
        if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
            try { tcpServerSocket.close(); } catch (IOException ignored) {}
        }
        try {
            bindTcpPort(negotiatedPort);
        } catch (IOException e) {
            Log.e(TAG, "Failed to rebind TCP port after renegotiation: " + e.getMessage());
        }
    }

    /**
     * 通过组播周期性发送在线宣告（替代UDP广播的 sendBroadcast）
     */
    private void startMulticastPresenceSender() {
        executorService.execute(() -> {
            while (isRunning) {
                if (portConfirmed && tcpServerPort > 0) {
                    sendMulticastPresenceMessage();
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * 通过协商组播发送在线宣告消息
     */
    private void sendMulticastPresenceMessage() {
        try {
            if (negotiationSocket == null || negotiationSocket.isClosed()) return;

            BroadcastMessage msg = new BroadcastMessage();
            msg.setType("presence");
            msg.setUid(myUid);
            msg.setPeerType("friend");
            msg.setSender(userName);
            msg.setDeviceId(deviceId);
            msg.setSenderIp(NetworkUtils.getLocalIpAddress());
            msg.setSenderPort(tcpServerPort);
            msg.setBackupPort(tcpServerPort);
            msg.setDiscoverable(discoverable);
            msg.setTimestamp(System.currentTimeMillis());

            String json = gson.toJson(msg);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            InetAddress groupAddr = InetAddress.getByName(MULTICAST_ADDRESS);
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddr, UDP_MULTICAST_NEGOTIATION_PORT);
            negotiationSocket.send(packet);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send multicast presence: " + e.getMessage());
        }
    }

    /**
     * 获取WiFi多播锁
     * Android WiFi驱动默认过滤广播/组播包，必须获取MulticastLock才能接收
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("LanChat");
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire MulticastLock: " + e.getMessage());
        }
    }

    /**
     * 释放WiFi多播锁
     */
    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release MulticastLock: " + e.getMessage());
        }
    }
    
    /**
     * 处理自动添加好友
     */
    private void handleAutoFriendAdd(String peerIp, String peerName, long peerUid) {
        // 如果是回环地址，使用本地网络IP，避免数据库存错IP
        String saveIp = peerIp;
        if (isLoopbackAddress(peerIp)) {
            String localIp = NetworkUtils.getLocalIpAddress();
            if (localIp != null) {
                saveIp = localIp;
            }
        }

        // 查找对应的peer（uid优先匹配）
        LanPeer peer = null;
        if (peerUid != 0) {
            for (LanPeer p : peers) {
                if (p.getUid() == peerUid) {
                    peer = p;
                    break;
                }
            }
        }
        
        // uid未命中，用IP匹配
        if (peer == null) {
            for (LanPeer p : peers) {
                if (p.getIp().equals(saveIp)) {
                    peer = p;
                    break;
                }
            }
        }

        if (peer != null) {
            peer.setFriend(true);
        }

        // 保存到数据库
        if (appContext != null) {
            LanChatDatabase db = LanChatDatabase.getInstance(appContext);
            long dbUid = peer != null ? peer.getUid() : peerUid;
            // 即使已经是好友，也更新信息
            db.addFriend(saveIp, dbUid, peerName, null, peer != null ? peer.getTcpPort() : 0);
            Log.d(TAG, "Auto-added friend to DB: " + peerName + " @ " + saveIp + " uid=" + dbUid);
            DebugLogger.logDiscovery("LanChat", "自动添加好友", peerName + " @ " + saveIp);
        }

        // 通知监听器
        for (OnPeerChangeListener listener : peerChangeListeners) {
            mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
        }
    }
    
    /**
     * 刷新发现（重新发送广播）
     */
    public void refreshDiscovery() {
        executorService.execute(() -> {
            try {
                // 通过组播协商通道发送在线宣告
                sendMulticastPresenceMessage();

                // 同时检查所有peer的状态
                for (LanPeer peer : peers) {
                    if (peer.isFriend() && !peer.isOnline()) {
                        connectToPeer(peer);
                    }
                }
                
                // 通过房间组播发送在线状态
                sendMulticastPresence();
                
                // 重新发送公开房间的创建广播
                for (LanPeer peer : peers) {
                    if (peer.isRoom() && peer.isDiscoverable()) {
                        sendRoomCreateBroadcast(peer);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending refresh broadcast: " + e.getMessage());
            }
        });
    }
    
    /**
     * 发送房间创建广播
     */
    private void sendRoomCreateBroadcast(LanPeer roomPeer) {
        executorService.execute(() -> {
            try {
                BroadcastMessage broadcastMsg = new BroadcastMessage();
                broadcastMsg.setType("room_create");
                broadcastMsg.setUid(myUid);
                broadcastMsg.setPeerType("room");
                broadcastMsg.setSender(roomPeer.getUserName());
                broadcastMsg.setDeviceId(deviceId);
                broadcastMsg.setSenderIp(roomPeer.getIp());
                broadcastMsg.setSenderPort(roomPeer.getTcpPort());
                broadcastMsg.setDiscoverable(roomPeer.isDiscoverable());
                broadcastMsg.setRoomCode(roomPeer.getRoomCode());
                broadcastMsg.setRoomName(roomPeer.getRoomName());
                broadcastMsg.setRoomIp(roomPeer.getOwnerIp());
                broadcastMsg.setRoomPort(roomPeer.getTcpPort());
                broadcastMsg.setTimestamp(System.currentTimeMillis());

                String json = gson.toJson(broadcastMsg);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                // 通过组播发送房间创建消息（公共发现端口）
                try {
                    InetAddress multicastAddr = InetAddress.getByName(MULTICAST_ADDRESS);
                    MulticastSocket multicastSendSocket = new MulticastSocket();
                    try {
                        DatagramPacket multicastPacket = new DatagramPacket(data, data.length, multicastAddr, UDP_MULTICAST_DISCOVERY_PORT);
                        multicastSendSocket.send(multicastPacket);
                    } finally {
                        multicastSendSocket.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send room create multicast: " + e.getMessage());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending room create broadcast: " + e.getMessage());
            }
        });
    }

    /**
     * 发送组播在线状态消息
     * 用于通知所有监听组播地址的用户本机在线
     */
    private void sendMulticastPresence() {
        // 根据房间码计算组播地址和端口
        int roomPort = calculateMulticastPort(currentRoomCode);
        String roomAddr = calculateRoomMulticastAddress(currentRoomCode);
        
        executorService.execute(() -> {
            MulticastSocket sendSocket = null;
            try {
                sendSocket = new MulticastSocket();
                if (sendSocket.isClosed()) {
                    Log.e(TAG, "Multicast send socket closed");
                    return;
                }

                // 创建在线状态消息
                ChatMessage presenceMessage = new ChatMessage();
                presenceMessage.setType("presence");
                presenceMessage.setUid(myUid);
                presenceMessage.setPeerType("friend"); // 标记为好友类型
                presenceMessage.setSender(userName);
                presenceMessage.setDeviceId(deviceId);
                presenceMessage.setSenderIp(NetworkUtils.getLocalIpAddress());
                presenceMessage.setSenderPort(tcpServerPort);
                presenceMessage.setBackupPort(tcpServerPort);
                presenceMessage.setDiscoverable(discoverable);
                presenceMessage.setTimestamp(System.currentTimeMillis());
                
                String json = gson.toJson(presenceMessage);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                InetAddress multicastAddr = InetAddress.getByName(roomAddr);
                DatagramPacket packet = new DatagramPacket(data, data.length, multicastAddr, roomPort);
                sendSocket.send(packet);
            } catch (Exception e) {
                Log.e(TAG, "Error sending multicast presence: " + e.getMessage());
            } finally {
                if (sendSocket != null) sendSocket.close();
            }
        });
    }
    
    /**
     * 启动定期刷新任务
     */
    private void startPeriodicRefresh() {
        executorService.execute(() -> {
            try {
                while (isRunning) {
                    Thread.sleep(30000); // 每30秒执行一次

                    try {
                        // 1. 通过组播协商通道发送在线宣告
                        sendMulticastPresenceMessage();
                        // 通过房间组播发送在线状态
                        sendMulticastPresence();
                        // 重新发送公开房间的创建广播
                        for (LanPeer peer : peers) {
                            if (peer.isRoom() && peer.isDiscoverable()) {
                                sendRoomCreateBroadcast(peer);
                            }
                        }

                        // 2. 超时检测：清理不活跃的房间成员
                        boolean membersChanged = false;
                        for (String roomCode : roomMembers.keySet()) {
                            java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.get(roomCode);
                            if (members == null) continue;
                            long now = System.currentTimeMillis();
                            for (java.util.Map.Entry<Long, LanPeer> entry : members.entrySet()) {
                                LanPeer member = entry.getValue();
                                if (member.getUid() == myUid) continue; // 不检查自己
                                long elapsed = now - member.getLastSeen();
                                if (elapsed > 60000) {
                                    // 超过60秒，移除该成员
                                    members.remove(entry.getKey());
                                    Log.d(TAG, "Removed stale member " + member.getUserName() + " from room " + roomCode);
                                    membersChanged = true;
                                } else if (elapsed > 30000) {
                                    // 超过30秒，标记离线
                                    if (member.isOnline()) {
                                        member.setOnline(false);
                                        membersChanged = true;
                                    }
                                }
                            }
                        }
                        if (membersChanged) {
                            for (OnPeerChangeListener listener : peerChangeListeners) {
                                mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in periodic broadcast: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Error in periodic refresh: " + e.getMessage());
            }
        });
    }
    
    /**
     * 发送房间消息（UDP组播）
     * 根据房间码计算组播端口，实现房间隔离
     */
    public void sendRoomMessage(ChatMessage message) {
        if (currentRoomCode == null || currentRoomCode.isEmpty()) {
            Log.w(TAG, "Room code not set, cannot send room message");
            return;
        }

        // 确保消息有 uid、sender、发送方IP端口和 timestamp
        if (message.getUid() == 0) {
            message.setUid(myUid);
        }
        if (message.getSender() == null || message.getSender().isEmpty()) {
            message.setSender(userName);
        }
        if (message.getDeviceId() == null || message.getDeviceId().isEmpty()) {
            message.setDeviceId(deviceId);
        }
        if (message.getSenderIp() == null || message.getSenderIp().isEmpty()) {
            String localIp = NetworkUtils.getLocalIpAddress();
            message.setSenderIp(localIp);
        }
        if (message.getSenderPort() <= 0) {
            message.setSenderPort(tcpServerPort);
        }
        if (message.getBackupPort() <= 0) {
            message.setBackupPort(tcpServerPort);
        }
        message.setDiscoverable(discoverable);
        if (message.getRoomCode() == null || message.getRoomCode().isEmpty()) {
            message.setRoomCode(currentRoomCode);
        }
        if (message.getTimestamp() == 0) {
            message.setTimestamp(System.currentTimeMillis());
        }

        // 设置序列号（用于NACK丢包恢复）
        int seq = roomSeqCounter.incrementAndGet();
        message.setSeq(seq);
        // 缓存消息用于重传（最多保留100条）
        roomMessageCache.put(seq, message);
        if (roomMessageCache.size() > 100) {
            // 移除最旧的条目
            int oldest = seq - 100;
            for (int i = oldest; i < seq; i++) {
                roomMessageCache.remove(i);
            }
        }

        // 根据房间码计算组播地址和端口
        int roomPort = calculateMulticastPort(currentRoomCode);
        String roomAddr = calculateRoomMulticastAddress(currentRoomCode);
        InetAddress roomMulticastAddress;

        try {
            roomMulticastAddress = InetAddress.getByName(roomAddr);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get multicast address", e);
            return;
        }

        final InetAddress finalMulticastAddress = roomMulticastAddress;
        final int finalPort = roomPort;
        final ChatMessage finalMessage = message;

        executorService.execute(() -> {
            MulticastSocket sendSocket = null;
            try {
                sendSocket = new MulticastSocket();
                if (sendSocket.isClosed()) {
                    Log.e(TAG, "Multicast send socket closed");
                    return;
                }

                String json = gson.toJson(finalMessage);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                DatagramPacket packet = new DatagramPacket(data, data.length, finalMulticastAddress, finalPort);
                sendSocket.send(packet);
            } catch (Exception e) {
                Log.e(TAG, "Error sending room message: " + e.getMessage());
            } finally {
                if (sendSocket != null) sendSocket.close();
            }
        });
    }

    /**
     * 通过UDP广播发送消息（用于房间创建等需要全局通知的场景）
     * 将ChatMessage转为BroadcastMessage格式发送
     */
    public void sendBroadcastMessage(ChatMessage chatMsg) {
        executorService.execute(() -> {
            try {
                BroadcastMessage broadcastMsg = new BroadcastMessage();
                broadcastMsg.setType(chatMsg.getType());
                broadcastMsg.setPeerType("room"); // 标记为房间类型
                broadcastMsg.setSender(chatMsg.getSender());
                broadcastMsg.setDeviceId(deviceId);
                broadcastMsg.setSenderIp(chatMsg.getSenderIp());
                broadcastMsg.setSenderPort(chatMsg.getSenderPort());
                broadcastMsg.setContent(chatMsg.getContent());
                broadcastMsg.setDiscoverable(chatMsg.isDiscoverable());
                broadcastMsg.setRoomCode(chatMsg.getRoomCode());
                broadcastMsg.setRoomName(chatMsg.getRoomName());
                broadcastMsg.setRoomIp(chatMsg.getRoomIp());
                broadcastMsg.setRoomPort(chatMsg.getRoomPort());
                broadcastMsg.setRoomOwner(chatMsg.isRoomOwner());
                broadcastMsg.setTimestamp(chatMsg.getTimestamp());

                String json = gson.toJson(broadcastMsg);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                if ("room_create".equals(chatMsg.getType())) {
                    MulticastSocket multicastSendSocket = null;
                    try {
                        InetAddress multicastAddr = InetAddress.getByName(MULTICAST_ADDRESS);
                        multicastSendSocket = new MulticastSocket();
                        DatagramPacket multicastPacket = new DatagramPacket(data, data.length, multicastAddr, UDP_MULTICAST_DISCOVERY_PORT);
                        multicastSendSocket.send(multicastPacket);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send room create multicast: " + e.getMessage(), e);
                    } finally {
                        if (multicastSendSocket != null) multicastSendSocket.close();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending broadcast message: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 根据房间码计算组播端口
     * 使用更好的哈希算法减少冲突，范围19003-19102
     */
    /**
     * 计算房间组播地址：239.255.{high}.{low}
     * 房间码 hash 成4位 hex，高2位→第3字节，低2位→第4字节
     */
    private String calculateRoomMulticastAddress(String roomCode) {
        try {
            int hash = roomCode.hashCode();
            // 取绝对值并转为4位hex
            String hex = String.format("%04x", Math.abs(hash) & 0xFFFF);
            // 高2位→第3字节，低2位→第4字节
            int high = Integer.parseInt(hex.substring(0, 2), 16);
            int low = Integer.parseInt(hex.substring(2, 4), 16);
            return "239.255." + high + "." + low;
        } catch (Exception e) {
            // fallback: 使用默认地址
            return "239.255.0.1";
        }
    }

    /**
     * 计算房间组播端口（固定19105）
     */
    private int calculateMulticastPort(String roomCode) {
        return ROOM_MULTICAST_PORT;
    }
    
    /**
     * 发送NACK请求，请求重传丢失的组播消息
     * @param roomCode 房间码
     * @param fromSeq 起始seq（包含）
     * @param toSeq 结束seq（包含）
     */
    private void sendNackRequest(String roomCode, int fromSeq, int toSeq) {
        executorService.execute(() -> {
            try {
                String roomAddr = calculateRoomMulticastAddress(roomCode);
                int port = calculateMulticastPort(roomCode);
                
                // 构建NACK消息
                ChatMessage nackMsg = new ChatMessage();
                nackMsg.setType("nack");
                nackMsg.setUid(myUid);
                nackMsg.setSender(userName);
                nackMsg.setDeviceId(deviceId);
                nackMsg.setRoomCode(roomCode);
                nackMsg.setSeq(fromSeq);  // 请求的起始seq
                nackMsg.setContent(toSeq + "");  // 结束seq放在content中
                nackMsg.setTimestamp(System.currentTimeMillis());
                
                String json = gson.toJson(nackMsg);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                
                InetAddress multicastAddr = InetAddress.getByName(roomAddr);
                DatagramPacket packet = new DatagramPacket(data, data.length, multicastAddr, port);
                
                MulticastSocket sendSocket = new MulticastSocket();
                try {
                    sendSocket.send(packet);
                    Log.d(TAG, "Sent NACK request for seq " + fromSeq + "-" + toSeq + " in room " + roomCode);
                } finally {
                    sendSocket.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending NACK request: " + e.getMessage());
            }
        });
    }
    
    /**
     * 处理NACK请求，重传指定seq范围的消息
     * @param nackMsg NACK消息
     */
    private void handleNackRequest(ChatMessage nackMsg) {
        if (nackMsg.getDeviceId() != null && nackMsg.getDeviceId().equals(deviceId)) {
            return;  // 忽略自己发的NACK
        }
        
        int fromSeq = nackMsg.getSeq();
        int toSeq;
        try {
            toSeq = Integer.parseInt(nackMsg.getContent());
        } catch (NumberFormatException e) {
            return;
        }
        
        String roomCode = nackMsg.getRoomCode();
        Log.d(TAG, "Received NACK request for seq " + fromSeq + "-" + toSeq + " in room " + roomCode);
        
        // 重传指定范围的消息
        executorService.execute(() -> {
            try {
                String roomAddr = calculateRoomMulticastAddress(roomCode);
                int port = calculateMulticastPort(roomCode);
                InetAddress multicastAddr = InetAddress.getByName(roomAddr);
                
                for (int seq = fromSeq; seq <= toSeq; seq++) {
                    ChatMessage cachedMsg = roomMessageCache.get(seq);
                    if (cachedMsg != null) {
                        String json = gson.toJson(cachedMsg);
                        byte[] data = json.getBytes(StandardCharsets.UTF_8);
                        DatagramPacket packet = new DatagramPacket(data, data.length, multicastAddr, port);
                        
                        MulticastSocket sendSocket = new MulticastSocket();
                        try {
                            sendSocket.send(packet);
                            Log.d(TAG, "Retransmitted message seq=" + seq);
                        } finally {
                            sendSocket.close();
                        }
                        
                        // 重传间隔，避免网络拥塞
                        Thread.sleep(50);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling NACK request: " + e.getMessage());
            }
        });
    }
    
    /**
     * 发送连接请求广播
     * 当点击好友但TCP连接未建立时，发送特殊广播通知对方本机TCP端口
     * 对方收到后会主动TCP连接本机
     */
      
    /**
     * 发送连接请求广播给指定peer
     * 对方收到后会主动TCP连接本机
     */
    public void sendConnectRequest(LanPeer targetPeer) {
        if (targetPeer == null || targetPeer.getIp() == null) {
            Log.e(TAG, "sendConnectRequest: invalid target peer");
            return;
        }

        executorService.execute(() -> {
            try {
                BroadcastMessage message = new BroadcastMessage();
                message.setType("connect_request");
                message.setUid(myUid);
                message.setSender(userName);
                message.setSenderPort(tcpServerPort);
                message.setTimestamp(System.currentTimeMillis());
                message.setTargetIp(targetPeer.getIp());

                String json = gson.toJson(message);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                // 通过组播发送连接请求
                try {
                    InetAddress multicastAddr = InetAddress.getByName(MULTICAST_ADDRESS);
                    MulticastSocket multicastSendSocket = new MulticastSocket();
                    try {
                        DatagramPacket multicastPacket = new DatagramPacket(data, data.length, multicastAddr, UDP_MULTICAST_DISCOVERY_PORT);
                        multicastSendSocket.send(multicastPacket);
                    } finally {
                        multicastSendSocket.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send connect request multicast: " + e.getMessage());
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending connect request: " + e.getMessage());
            }
        });
    }

    /**
     * 发送UDP广播
     * 兼容WiFi和有线网络，支持多种广播地址
     */

    /**
     * 处理接收到的广播消息
     *
     * 优化后的逻辑：
     * 1. 默认情况下，所有发现的设备都显示为在线设备
     * 2. 如果双方都可被发现（discoverable=true），则自动成为好友
     * 3. 手动添加好友时，直接添加，无需验证码
     * 4. 如果用户开启不公开选项（discoverable=false），则不会被其他人发现
     * 5. 支持同一IP多实例：通过用户名和端口区分不同实例
     */
    private void handleBroadcastMessage(BroadcastMessage message, String senderIp) {
        if (message.getDeviceId() != null && message.getDeviceId().equals(deviceId)) {
            return;
        }

        String localIp = NetworkUtils.getLocalIpAddress();

        // 处理连接请求广播：对方想与本机建立TCP连接
        if ("connect_request".equals(message.getType())) {
            // 只有当targetIp匹配本机IP时才响应
            if (message.getTargetIp() != null && localIp != null && localIp.equals(message.getTargetIp())) {
                DebugLogger.logDiscovery("LanChat", "收到连接请求", message.getSender() + " @ " + senderIp + " 端口:" + message.getSenderPort());
                Log.d(TAG, "Received connect_request from " + senderIp + " port " + message.getSenderPort());

                // 查找或创建peer（uid优先，再IP+用户名+端口）
                LanPeer targetPeer = null;
                long reqUid = message.getUid();
                
                // 优先uid匹配
                if (reqUid != 0) {
                    for (LanPeer peer : peers) {
                        if (peer.getUid() == reqUid) {
                            targetPeer = peer;
                            break;
                        }
                    }
                }
                
                // uid未命中，用IP+用户名+端口匹配
                if (targetPeer == null) {
                    for (LanPeer peer : peers) {
                        if (peer.getIp().equals(senderIp) &&
                            peer.getUserName() != null &&
                            peer.getUserName().equals(message.getSender()) &&
                            peer.getTcpPort() == message.getSenderPort()) {
                            targetPeer = peer;
                            break;
                        }
                    }
                }

                if (targetPeer == null) {
                    targetPeer = new LanPeer();
                    targetPeer.setUid(reqUid);
                    String displayName = message.getSender();
                    if (displayName == null || displayName.isEmpty()) {
                        displayName = "用户@" + senderIp;
                    }
                    targetPeer.setUserName(displayName);
                    targetPeer.setIp(senderIp);
                    targetPeer.setTcpPort(message.getSenderPort());
                    targetPeer.setOnline(false);
                    targetPeer.setLastSeen(System.currentTimeMillis());
                    targetPeer.setFriend(true);
                    peers.add(targetPeer);
                } else {
                    // 更新TCP端口（对方可能换了端口）
                    targetPeer.setTcpPort(message.getSenderPort());
                    targetPeer.setUserName(message.getSender());
                    targetPeer.setLastSeen(System.currentTimeMillis());
                }

                // 主动TCP连接对方
                if (!targetPeer.isOnline() || targetPeer.getWriter() == null) {
                    connectToPeer(targetPeer);
                }

                // 通知监听器更新好友列表
                for (OnPeerChangeListener listener : peerChangeListeners) {
                    mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                }
            }
            return;  // connect_request类型不继续走普通发现流程
        }

        // 处理房间创建广播：将创建者的房间添加到公开房间列表
        if ("room_create".equals(message.getType())) {
            // 只有公开房间才添加到列表
            if (message.isDiscoverable()) {
                handleRoomCreateBroadcast(message, senderIp);
            }
            return;
        }

        // 处理中继广播
        if (message.getType().startsWith("relay_")) {
            // 跳过从回环地址发来的中继消息（防止环路）
            if (isLoopbackAddress(senderIp)) {
                Log.d(TAG, "Ignoring relay from loopback: " + senderIp);
                return;
            }

            // 提取原始发送者信息
            String originalSender = message.getOriginalSender();
            if (originalSender == null || originalSender.isEmpty()) {
                originalSender = senderIp;
            }

            // 更新或创建peer（uid优先，再IP+用户名+端口查找）
            LanPeer peer = null;
            long relayUid = message.getUid();
            
            // 优先uid匹配
            if (relayUid != 0) {
                for (LanPeer p : peers) {
                    if (p.getUid() == relayUid) {
                        peer = p;
                        break;
                    }
                }
            }
            
            // uid未命中，用IP+用户名+端口匹配
            if (peer == null) {
                for (LanPeer p : peers) {
                    if (p.getIp().equals(originalSender) &&
                        p.getUserName() != null &&
                        p.getUserName().equals(message.getSender()) &&
                        p.getTcpPort() == message.getSenderPort()) {
                        peer = p;
                        break;
                    }
                }
            }

            boolean isNewPeer = (peer == null);
            if (isNewPeer) {
                peer = new LanPeer();
                peer.setIp(originalSender);
                peer.setTcpPort(message.getSenderPort());
                peer.setLastSeen(System.currentTimeMillis());
                peer.setOnline(true);
                peers.add(peer);
                Log.d(TAG, "New peer discovered via relay: " + originalSender + " port " + message.getSenderPort());
            } else {
                // 更新现有peer信息
                peer.setTcpPort(message.getSenderPort());
                peer.setOnline(true);
            }

            // 设置用户名（如果为空或为默认值）
            if (peer.getUserName() == null || peer.getUserName().startsWith("用户@")) {
                String displayName = message.getSender();
                if (displayName == null || displayName.isEmpty()) {
                    displayName = "用户@" + originalSender;
                }
                peer.setUserName(displayName);
            }

            // 设置可被发现状态
            peer.setDiscoverable(message.isDiscoverable());

            // 通知监听器更新（在更新lastSeen之前判断，确保首次消息能触发刷新）
            boolean shouldNotify = isNewPeer || peer.getLastSeen() == 0 ||
                    System.currentTimeMillis() - peer.getLastSeen() > 5000;
            peer.setLastSeen(System.currentTimeMillis());

            if (shouldNotify) {
                for (OnPeerChangeListener listener : peerChangeListeners) {
                    mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                }
            }

            return;
        }

        DebugLogger.logDiscovery("LanChat", "发现设备", message.getSender() + " @ " + senderIp + " TCP端口:" + message.getSenderPort());

        // 检查是否已存在（uid优先匹配，再IP+用户名+端口+peerType查找）
        LanPeer existingPeer = null;
        String msgPeerType = message.getPeerType();
        long remoteUid = message.getUid();
        
        // 优先用uid匹配（同设备多实例时IP相同，uid是唯一身份）
        if (remoteUid != 0) {
            for (LanPeer peer : peers) {
                if (peer.getUid() == remoteUid) {
                    existingPeer = peer;
                    break;
                }
            }
        }
        
        // uid未命中时，用IP+用户名+端口匹配
        if (existingPeer == null) {
            for (LanPeer peer : peers) {
                if (peer.getIp().equals(senderIp) &&
                    peer.getUserName() != null &&
                    peer.getUserName().equals(message.getSender()) &&
                    peer.getTcpPort() == message.getSenderPort()) {
                    // 检查peer类型是否匹配
                    if ("room".equals(msgPeerType) && peer.isRoom()) {
                        existingPeer = peer;
                        break;
                    } else if ("friend".equals(msgPeerType) && peer.isFriend()) {
                        existingPeer = peer;
                        break;
                    } else if (msgPeerType == null) {
                        // 兼容旧消息（无peerType字段），按原有逻辑匹配
                        existingPeer = peer;
                        break;
                    }
                }
            }
        }
        
        // 简化回环处理：同设备多开时通过 deviceId 精确匹配，避免 IP 混淆
        if (existingPeer == null && isLoopbackAddress(senderIp)) {
            for (LanPeer peer : peers) {
                if (peer.getUserName() != null &&
                    peer.getUserName().equals(message.getSender()) &&
                    peer.getTcpPort() == message.getSenderPort()) {
                    existingPeer = peer;
                    break;
                }
            }
        }

        // 改名查找：同一IP+port但用户名变了
        if (existingPeer == null) {
            for (LanPeer peer : peers) {
                if (peer.getIp().equals(senderIp) && peer.getTcpPort() == message.getSenderPort()) {
                    existingPeer = peer;
                    break;
                }
            }
        }

        // 补充查找：同一IP+用户名但端口变了（解决好友重启后端口变化的问题）
        if (existingPeer == null) {
            for (LanPeer peer : peers) {
                if (peer.getIp().equals(senderIp) &&
                    peer.getUserName() != null &&
                    peer.getUserName().equals(message.getSender())) {
                    existingPeer = peer;
                    break;
                }
            }
        }

        // 判断是否为自动好友
        boolean isAutoFriend = false;

        // 情况1：双方都可被发现，自动成为好友
        if (discoverable && message.isDiscoverable()) {
            isAutoFriend = true;
        }

        if (existingPeer != null) {
            // 更新最后看到的时间和 TCP 端口
            existingPeer.setLastSeen(System.currentTimeMillis());
            existingPeer.setUserName(message.getSender());  // 更新用户名
            existingPeer.setTcpPort(message.getSenderPort());    // 更新 TCP 端口

            // 注意：这里不设置 online=true，只有 TCP 连接成功才表示真正在线
            // 收到广播只表示设备存在，但不一定能建立 TCP 连接

            // 如果是自动好友，更新状态
            if (isAutoFriend) {
                existingPeer.setFriend(true);
                // 保存到数据库
                if (appContext != null) {
                    LanChatDatabase db = LanChatDatabase.getInstance(appContext);
                    db.addFriend(senderIp, message.getUid(), message.getSender(), null, message.getSenderPort());
                    Log.d(TAG, "Updated auto-added friend: " + message.getSender() + " @ " + senderIp + " uid=" + message.getUid());
                }
            }

            // 如果当前不在线，尝试重新连接
            if (!existingPeer.isOnline()) {
                connectToPeer(existingPeer);
            }
        } else {
            // 新设备 — 跳过回环地址，同设备多开通过各自不同端口区分
            if (senderIp != null && isLoopbackAddress(senderIp)) {
                Log.d(TAG, "Skipping new peer from loopback: " + senderIp);
                for (OnPeerChangeListener listener : peerChangeListeners) {
                    mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                }
                return;
            }

            LanPeer newPeer = new LanPeer();
            // 处理空用户名情况
            String displayName = message.getSender();
            if (displayName == null || displayName.isEmpty()) {
                displayName = "用户@" + senderIp;
            }
            newPeer.setUserName(displayName);
            newPeer.setIp(senderIp);
            newPeer.setTcpPort(message.getSenderPort());
            newPeer.setOnline(false); // 等待 TCP 连接成功才设置在线
            newPeer.setLastSeen(System.currentTimeMillis());
            // 根据peerType设置节点类型
            if ("room".equals(msgPeerType)) {
                newPeer.setFriend(false);
                newPeer.setRoom(true);
            } else {
                newPeer.setFriend(isAutoFriend);
                newPeer.setRoom(false);
            }
            newPeer.setUid(message.getUid());
            newPeer.setDiscoverable(message.isDiscoverable()); // 设置可被发现状态
            peers.add(newPeer);

            // 主动建立TCP连接（尝试连接，但不阻塞）
            connectToPeer(newPeer);

            // 如果是自动好友，保存到数据库
            if (isAutoFriend && appContext != null) {
                LanChatDatabase db = LanChatDatabase.getInstance(appContext);
                db.addFriend(senderIp, message.getUid(), displayName, null, message.getSenderPort());
                Log.d(TAG, "Auto-added friend: " + displayName + " @ " + senderIp + " uid=" + message.getUid());
            }

            Log.d(TAG, "Discovered peer: " + displayName + " at " + senderIp);
        }

        // 通知所有监听器更新
        for (OnPeerChangeListener listener : peerChangeListeners) {
            mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
        }

    }

    /**
     * 解析广播消息
     */
    private BroadcastMessage parseBroadcastMessage(String data) {
        try {
            return gson.fromJson(data, BroadcastMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 处理文件接收（FILE_REQUEST + FILE_DATA + FILE_END 协议）
     * @param reader TCP输入流
     * @param peer 发送方
     * @param line 当前行（已包含 FILE_REQUEST: 前缀）
     * @return 文件传输结束后读到的下一行，若EOF则返回null
     */
    private String handleFileReceive(BufferedReader reader, LanPeer peer, String line) {
        String[] parts = line.substring(13).split(":");
        if (parts.length < 2) return null;

        final String fileName = parts[0];
        final long fileSize = Long.parseLong(parts[1]);
        final StringBuilder fileDataBuilder = new StringBuilder();

        try {
            String dataLine;
            while (isRunning && (dataLine = reader.readLine()) != null) {
                if (dataLine.startsWith("FILE_DATA:")) {
                    fileDataBuilder.append(dataLine.substring(10));
                } else if (dataLine.equals("FILE_END:")) {
                    break;
                } else {
                    line = dataLine;
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading file data: " + e.getMessage());
            return null;
        }

        if (fileDataBuilder.length() > 0) {
            try {
                String fileDataBase64 = fileDataBuilder.toString();
                byte[] fileBytes = android.util.Base64.decode(fileDataBase64, android.util.Base64.DEFAULT);
                File saveDir = new File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        FILE_SAVE_DIR);
                if (!saveDir.exists()) saveDir.mkdirs();
                File saveFile = new File(saveDir, System.currentTimeMillis() + "_" + fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(saveFile);
                fos.write(fileBytes);
                fos.close();

                String savedFilePath = saveFile.getAbsolutePath();
                Log.d(TAG, "File received and saved: " + savedFilePath);

                String lowerFileName = fileName.toLowerCase();
                if (lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg") ||
                    lowerFileName.endsWith(".png") || lowerFileName.endsWith(".gif") ||
                    lowerFileName.endsWith(".bmp") || lowerFileName.endsWith(".webp")) {
                    ChatMessage imageMsg = new ChatMessage();
                    imageMsg.setType("image");
                    imageMsg.setContent("[图片]");
                    imageMsg.setFileName(fileName);
                    imageMsg.setFileSize(fileBytes.length);
                    imageMsg.setFileData(fileDataBase64);
                    imageMsg.setFilePath(savedFilePath);
                    imageMsg.setSender(peer.getUserName());
                    imageMsg.setSenderIp(peer.getIp());
                    imageMsg.setSenderPort(peer.getTcpPort());
                    imageMsg.setTimestamp(System.currentTimeMillis());
                    for (OnPeerChangeListener listener : peerChangeListeners) {
                        listener.onMessageReceived(peer, imageMsg);
                    }
                }

                for (OnPeerChangeListener listener : peerChangeListeners) {
                    listener.onFileRequestReceived(peer, fileName, fileSize, savedFilePath);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving received file: " + e.getMessage());
            }
        }
        return line;
    }
    
    /**
     * 启动UDP组播监听（用于房间消息）
     * 只监听当前房间码对应的端口，避免创建100个socket导致资源耗尽闪退
     */
    private void startMulticast() {
        // 不再监听所有100个端口，改为只监听当前房间码对应的端口
        // 房间码变化时重新监听（见 setRoomCode 方法）
        startMulticastForRoom(currentRoomCode);
    }

    /**
     * 为指定房间码启动组播监听
     */
    private void startMulticastForRoom(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            Log.w(TAG, "Room code not set, skip multicast listener");
            return;
        }

        int port = calculateMulticastPort(roomCode);
        
        // 检查是否已启动此端口的监听
        if (multicastSockets.containsKey(port)) {
            Log.d(TAG, "Multicast listener already running on port " + port);
            multicastPortRoomCodes.put(port, roomCode);
            return;
        }

        // 记录端口→房间码映射
        multicastPortRoomCodes.put(port, roomCode);

        // 计算房间组播地址
        String roomMulticastAddr = calculateRoomMulticastAddress(roomCode);
        executorService.execute(() -> listenMulticastPort(port, roomMulticastAddr));
    }

    /**
     * 启动组播房间发现监听（公共端口）
     * 所有设备都监听此端口，用于发现公开房间
     */
    private void startMulticastDiscovery() {
        executorService.execute(() -> listenMulticastPort(UDP_MULTICAST_DISCOVERY_PORT, MULTICAST_ADDRESS));
    }

    /**
     * 停止指定端口的组播监听
     */
    private void stopMulticastListener(int port) {
        java.net.MulticastSocket socket = multicastSockets.remove(port);
        multicastPortRoomCodes.remove(port);
        String addr = multicastPortAddresses.remove(port);
        if (socket != null && !socket.isClosed()) {
            try {
                // 显式离开组播组
                if (addr != null) {
                    socket.leaveGroup(java.net.InetAddress.getByName(addr));
                }
                socket.close();
                Log.d(TAG, "Stopped multicast listener on port " + port);
            } catch (Exception e) {
                Log.d(TAG, "Error closing multicast socket on port " + port + ": " + e.getMessage());
            }
        }
    }

    /**
     * UDP组播监听线程 - 监听指定端口接收房间消息
     */
    private void listenMulticastPort(int port, String multicastAddr) {
        try {
            InetAddress multicastAddress = InetAddress.getByName(multicastAddr);
            // 创建组播socket并加入组
            java.net.MulticastSocket socket = new java.net.MulticastSocket(port);
            multicastSockets.put(port, socket);
            multicastPortAddresses.put(port, multicastAddr);
            socket.joinGroup(multicastAddress);
            socket.setSoTimeout(1000);  // 设置超时，避免阻塞
            
            // 组播消息去重：记录最近处理过的消息ID，防止网络层重复投递
            final java.util.LinkedHashMap<String, Long> recentMessages = new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                    return size() > 50; // 最多保留50条记录
                }
            };

            byte[] buffer = new byte[65535];
            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String data = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    String senderIp = packet.getAddress().getHostAddress();

                    ChatMessage message = gson.fromJson(data, ChatMessage.class);
                    if (message != null) {
                        // 设置发送方IP（从UDP包中获取）
                        if (message.getSenderIp() == null || message.getSenderIp().isEmpty()) {
                            message.setSenderIp(senderIp);
                        }

                        // 组播消息去重：基于 sender+timestamp+content 生成唯一ID
                        String msgId = (message.getSender() != null ? message.getSender() : "") +
                                "_" + message.getTimestamp() +
                                "_" + (message.getContent() != null ? message.getContent().hashCode() : 0);
                        long now = System.currentTimeMillis();
                        if (recentMessages.containsKey(msgId) && (now - recentMessages.get(msgId) < 5000)) {
                            continue;
                        }
                        recentMessages.put(msgId, now);

                        // 处理在线状态消息
                        if ("presence".equals(message.getType())) {
                            handlePresenceMessage(message);
                            continue;
                        }

                        // 处理房间创建消息 - 将创建者的房间添加到公开房间列表
                        if ("room_create".equals(message.getType())) {
                            handleRoomCreateMessage(message);
                            continue;
                        }

                        // 处理NACK请求（丢包恢复）
                        if ("nack".equals(message.getType())) {
                            handleNackRequest(message);
                            continue;
                        }

                        // 处理成员列表请求（新成员加入时请求最新列表）
                        if ("member_list_request".equals(message.getType())) {
                            String requestRoomCode = message.getRoomCode();
                            if (requestRoomCode != null && requestRoomCode.equals(currentRoomCode)) {
                                // 先将请求者添加到成员列表（防止其被遗漏）
                                long senderUid = message.getUid();
                                boolean isSelf = message.getDeviceId() != null && message.getDeviceId().equals(deviceId);
                                if (senderUid != 0 && !isSelf) {
                                    LanPeer senderPeer = new LanPeer();
                                    senderPeer.setUid(senderUid);
                                    senderPeer.setUserName(message.getSender());
                                    senderPeer.setIp(message.getSenderIp());
                                    senderPeer.setTcpPort(message.getSenderPort());
                                    senderPeer.setOnline(true);
                                    senderPeer.setLastSeen(System.currentTimeMillis());
                                    addRoomMember(requestRoomCode, senderUid, senderPeer);
                                }

                                // 去中心化：任何已在房间中的成员都可以响应（不再仅限房主）
                                java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.get(requestRoomCode);
                                if (members != null && members.containsKey(myUid)) {
                                    String localIp = NetworkUtils.getLocalIpAddress();
                                    ChatMessage responseMsg = new ChatMessage();
                                    responseMsg.setType("member_list_response");
                                    responseMsg.setRoomCode(requestRoomCode);
                                    responseMsg.setRoomMembers(gson.toJson(getRoomMemberList(requestRoomCode)));
                                    responseMsg.setSender(userName);
                                    responseMsg.setSenderIp(localIp);
                                    responseMsg.setSenderPort(tcpServerPort);
                                    responseMsg.setUid(myUid);
                                    responseMsg.setDeviceId(deviceId);
                                    responseMsg.setTimestamp(System.currentTimeMillis());
                                    sendRoomMessage(responseMsg);
                                    Log.d(TAG, "Responded to member_list_request for room " + requestRoomCode);
                                }
                            }
                            continue;
                        }

                        // 处理成员列表响应
                        if ("member_list_response".equals(message.getType())) {
                            String responseRoomCode = message.getRoomCode();
                            if (responseRoomCode != null && responseRoomCode.equals(currentRoomCode)) {
                                // 同步房主IP
                                if (message.getRoomOwnerIp() != null && !message.getRoomOwnerIp().isEmpty()) {
                                    setRoomOwnerIp(responseRoomCode, message.getRoomOwnerIp());
                                }
                                // 同步成员列表
                                if (message.getRoomMembers() != null && !message.getRoomMembers().isEmpty()) {
                                    syncRoomMembers(responseRoomCode, message.getRoomMembers());
                                    Log.d(TAG, "Synced member list from response for room " + responseRoomCode);
                                }
                                // 通知监听器更新UI（成员列表变化）
                                for (OnPeerChangeListener listener : peerChangeListeners) {
                                    mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                                }
                            }
                            continue;
                        }

                        // 处理房间心跳：合并成员列表 + 更新lastSeen
                        if ("room_heartbeat".equals(message.getType())) {
                            String hbRoomCode = message.getRoomCode();
                            boolean isSelfHeartbeat = message.getDeviceId() != null && message.getDeviceId().equals(deviceId);
                            if (hbRoomCode != null && hbRoomCode.equals(currentRoomCode)
                                    && !isSelfHeartbeat) {
                                // 1. 将发送者添加到成员列表
                                long senderUid = message.getUid();
                                if (senderUid != 0) {
                                    LanPeer senderPeer = new LanPeer();
                                    senderPeer.setUid(senderUid);
                                    senderPeer.setUserName(message.getSender());
                                    senderPeer.setIp(message.getSenderIp());
                                    senderPeer.setTcpPort(message.getSenderPort());
                                    senderPeer.setOnline(true);
                                    senderPeer.setLastSeen(System.currentTimeMillis());
                                    addRoomMember(hbRoomCode, senderUid, senderPeer);
                                }
                                // 2. 合并对方的成员列表（取并集）
                                if (message.getRoomMembers() != null && !message.getRoomMembers().isEmpty()) {
                                    mergeRoomMembers(hbRoomCode, message.getRoomMembers());
                                }
                            }
                            continue;
                        }

                        // 处理房间离开通知：移除离开的成员
                        if ("room_leave".equals(message.getType())) {
                            String leaveRoomCode = message.getRoomCode();
                            if (leaveRoomCode != null && leaveRoomCode.equals(currentRoomCode)) {
                                long leaveUid = message.getUid();
                                if (leaveUid != 0) {
                                    removeRoomMember(leaveRoomCode, leaveUid);
                                    Log.d(TAG, "Member left room: " + leaveUid + " from " + message.getSender());
                                    // 通知监听器更新UI
                                    for (OnPeerChangeListener listener : peerChangeListeners) {
                                        mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                                    }
                                }
                            }
                            continue;
                        }

                        // 跳过协议消息（不显示在聊天界面）
                        if ("delivery_status".equals(message.getType()) ||
                            "handshake".equals(message.getType()) ||
                            "handshake_response".equals(message.getType()) ||
                            "friend_response".equals(message.getType())) {
                            continue;
                        }

                        // 跳过自己发送的消息（通过deviceId判断）
                        if (message.getDeviceId() != null && message.getDeviceId().equals(deviceId)) {
                            continue;
                        }

                        // 房间消息隔离：检查消息的roomCode是否匹配当前监听端口对应的房间
                        // 公共发现端口（19103）不检查roomCode，因为它是用于房间发现的
                        if (port != UDP_MULTICAST_DISCOVERY_PORT) {
                            String expectedRoomCode = multicastPortRoomCodes.get(port);
                            String msgRoomCode = message.getRoomCode();
                            if (expectedRoomCode != null && msgRoomCode != null && !msgRoomCode.isEmpty()
                                && !expectedRoomCode.equals(msgRoomCode)) {
                                continue;
                            }
                            
                            // NACK丢包检测：检查seq是否连续
                            int msgSeq = message.getSeq();
                            if (msgSeq > 0 && expectedRoomCode != null) {
                                // 序号是「每个发送方各自递增」的，必须按发送方分开记录，
                                // 否则多设备时必然误判丢包，触发 NACK 重传风暴 + 大量重复消息
                                String seqOwner = (message.getDeviceId() != null && !message.getDeviceId().isEmpty())
                                        ? message.getDeviceId() : ("uid" + message.getUid());
                                String lastSeqKey = expectedRoomCode + "_" + seqOwner + "_lastSeq";
                                Integer lastSeqObj = roomLastReceivedSeq.get(lastSeqKey);
                                int lastSeq = lastSeqObj != null ? lastSeqObj : 0;

                                // 间隙过大说明对方重启/序号重置，直接接受新基准，不要 NACK
                                if (lastSeq > 0 && msgSeq > lastSeq + 1 && msgSeq - lastSeq <= 50) {
                                    Log.d(TAG, "Seq gap detected: expected " + (lastSeq + 1) + " got " + msgSeq + ", sending NACK");
                                    sendNackRequest(expectedRoomCode, lastSeq + 1, msgSeq - 1);
                                }

                                // 更新最后收到的seq
                                if (msgSeq > lastSeq || msgSeq - lastSeq > 50) {
                                    roomLastReceivedSeq.put(lastSeqKey, msgSeq);
                                }
                            }

                            // 房间成员追踪：收到消息时添加发送者到成员列表
                            if (expectedRoomCode != null && msgRoomCode != null && !msgRoomCode.isEmpty()) {
                                long senderUid = message.getUid();
                                boolean isSelfMsg = message.getDeviceId() != null && message.getDeviceId().equals(deviceId);
                                if (senderUid != 0 && !isSelfMsg) {
                                    LanPeer senderPeer = new LanPeer();
                                    senderPeer.setUid(senderUid);
                                    senderPeer.setUserName(message.getSender());
                                    senderPeer.setIp(message.getSenderIp());
                                    senderPeer.setTcpPort(message.getSenderPort());
                                    senderPeer.setOnline(true);
                                    senderPeer.setLastSeen(System.currentTimeMillis());
                                    addRoomMember(expectedRoomCode, senderUid, senderPeer);
                                }

                                // 同步房主IP
                                if (message.getRoomOwnerIp() != null && !message.getRoomOwnerIp().isEmpty()) {
                                    setRoomOwnerIp(expectedRoomCode, message.getRoomOwnerIp());
                                }

                                // 同步房间成员列表（房主发送的完整列表）
                                if (message.getRoomMembers() != null && !message.getRoomMembers().isEmpty()) {
                                    syncRoomMembers(expectedRoomCode, message.getRoomMembers());
                                }
                            }

                            // 处理踢人消息
                            if ("room_kick".equals(message.getType())) {
                                String targetUserStr = message.getTargetUser();
                                if (targetUserStr != null) {
                                    try {
                                        long targetUid = Long.parseLong(targetUserStr);
                                        // 如果是踢自己，退出房间
                                        if (targetUid == myUid) {
                                            Log.d(TAG, "Kicked from room " + expectedRoomCode);
                                            // 通知监听器（由Activity处理退出逻辑）
                                            for (OnPeerChangeListener listener : peerChangeListeners) {
                                                mainHandler.post(() -> listener.onMessageReceived(null, message));
                                            }
                                        } else {
                                            // 从成员列表移除被踢者
                                            removeRoomMember(expectedRoomCode, targetUid);
                                        }
                                    } catch (NumberFormatException e) {
                                        Log.e(TAG, "Invalid target UID in kick message");
                                    }
                                }
                                continue;
                            }
                        }

                        // 通知所有监听器
                        for (OnPeerChangeListener listener : peerChangeListeners) {
                            mainHandler.post(() -> listener.onMessageReceived(null, message));
                        }

                        // 如果没有监听器（APP在后台），显示通知
                        if (peerChangeListeners.isEmpty() && appContext != null
                            && "text".equals(message.getType())
                            && message.getSender() != null && !message.getSender().equals(userName)) {
                            String msgRoomCode = message.getRoomCode();
                            if (msgRoomCode != null && !msgRoomCode.isEmpty()) {
                                LanChatNotificationManager notificationMgr = LanChatNotificationManager.getInstance(appContext);
                                String roomName = getRoomName(msgRoomCode);
                                notificationMgr.showMessageNotification(
                                    message.getSender(), message.getSenderIp(), message.getContent(),
                                    true, msgRoomCode, roomName);
                            }
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 超时是正常的，继续循环
                    continue;
                } catch (SocketException e) {
                    if (!isRunning) break;
                    Log.d(TAG, "UDP multicast listener stopped on port " + port + ": " + e.getMessage());
                    DebugLogger.logDiscovery("LanChat", "UDP组播监听停止", "端口: " + port + ", 错误: " + e.getMessage());
                    break;
                } catch (com.google.gson.JsonSyntaxException | IllegalStateException e) {
                    // 非JSON数据（其他设备发送的垃圾数据），静默忽略
                } catch (Exception e) {
                    Log.e(TAG, "UDP multicast listen error on port " + port + ": " + e.getMessage());
                    DebugLogger.logDiscovery("LanChat", "UDP组播监听错误", "端口: " + port + ", 错误: " + e.getMessage());
                }
            }

            socket.close();
            // 清理映射：只有映射仍指向本 socket 时才移除。
            // 否则旧监听线程退出时会删掉「重新启动的新监听」的映射，
            // 导致房间码校验（expectedRoomCode）变 null，房间隔离与成员同步全部失效。
            if (multicastSockets.get(port) == socket) {
                multicastSockets.remove(port);
                multicastPortRoomCodes.remove(port);
                multicastPortAddresses.remove(port);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start UDP multicast listener on port " + port + ": " + e.getMessage());
            DebugLogger.logDiscovery("LanChat", "UDP组播监听启动失败", "端口: " + port + ", 错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理接收到的在线状态消息
     */
    private void handlePresenceMessage(ChatMessage message) {
        String senderIp = message.getSenderIp();
        int senderPort = message.getSenderPort();
        if (senderIp == null || senderIp.isEmpty()) {
            return;
        }

        // 跳过自己发送的消息（通过deviceId判断）
        if (message.getDeviceId() != null && message.getDeviceId().equals(deviceId)) {
            return;
        }

        // 查找或创建peer（uid优先，再IP+用户名+端口查找）
        LanPeer peer = null;
        long presenceUid = message.getUid();
        
        // 优先uid匹配
        if (presenceUid != 0) {
            for (LanPeer p : peers) {
                if (p.getUid() == presenceUid) {
                    peer = p;
                    break;
                }
            }
        }
        
        // uid未命中，用IP+用户名+端口匹配
        if (peer == null) {
            for (LanPeer p : peers) {
                if (p.getIp().equals(senderIp) &&
                    p.getUserName() != null &&
                    p.getUserName().equals(message.getSender()) &&
                    p.getTcpPort() == senderPort) {
                    peer = p;
                    break;
                }
            }
        }

        boolean isNewPeer = (peer == null);
        if (isNewPeer) {
            peer = new LanPeer();
            peer.setUid(presenceUid);
            peer.setIp(senderIp);
            peer.setTcpPort(senderPort);
            peer.setUserName(message.getSender());
            peer.setLastSeen(System.currentTimeMillis());
            peer.setOnline(false); // 仅标记为存在，TCP连接成功后才算真正在线
            // 如果双方都可被发现，自动成为好友
            if (discoverable && message.getSender() != null && !message.getSender().isEmpty()) {
                peer.setFriend(true);
            }
            peers.add(peer);
            Log.d(TAG, "New peer discovered via multicast: " + senderIp + " port: " + senderPort);
        } else {
            // 更新现有peer信息
            // 如果现有peer UID为0而消息有有效UID，补上UID（数据库加载时可能丢失）
            if (peer.getUid() == 0 && presenceUid != 0) {
                peer.setUid(presenceUid);
            }
            peer.setTcpPort(senderPort);
            peer.setUserName(message.getSender());
            peer.setLastSeen(System.currentTimeMillis());
            // 不设置 online=true，只有 TCP 连接成功才表示真正在线
            // 收到组播只表示设备存在，但不一定能建立 TCP 连接
        }

        // 通知监听器更新
        if (isNewPeer || System.currentTimeMillis() - peer.getLastSeen() > 5000) {
            for (OnPeerChangeListener listener : peerChangeListeners) {
                mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
            }
        }
    }

    /**
     * 启动TCP accept 循环（异步）

    /**
     * 处理房间创建消息 - 将创建者的房间添加到公开房间列表
     */
    private void handleRoomCreateMessage(ChatMessage message) {
        String senderIp = message.getSenderIp();
        int senderPort = message.getSenderPort();
        if (senderIp == null || senderIp.isEmpty()) {
            return;
        }

        // 过滤回环地址
        if (isLoopbackAddress(senderIp)) {
            Log.d(TAG, "Ignoring room create from loopback: " + senderIp);
            return;
        }

        // 跳过自己发送的消息（通过deviceId判断）
        if (message.getDeviceId() != null && message.getDeviceId().equals(deviceId)) {
            return;
        }

        // 查找是否已存在该房间（优先通过房间码查找，再通过 IP+端口查找）
        LanPeer roomPeer = null;
        String roomCode = message.getRoomCode();
        if (roomCode != null && !roomCode.isEmpty()) {
            for (LanPeer p : peers) {
                if (p.isRoom() && roomCode.equals(p.getRoomCode())) {
                    roomPeer = p;
                    break;
                }
            }
        }
        if (roomPeer == null) {
            for (LanPeer p : peers) {
                if (p.getIp().equals(senderIp) && p.getTcpPort() == senderPort) {
                    roomPeer = p;
                    break;
                }
            }
        }

        boolean isNewRoom = (roomPeer == null);
        if (isNewRoom) {
            // 创建新的房间 peer
            roomPeer = new LanPeer();
            roomPeer.setIp(senderIp);
            roomPeer.setTcpPort(senderPort);
            roomPeer.setUserName(message.getSender());
            roomPeer.setRoom(true); // 标记为房间
            roomPeer.setLastSeen(System.currentTimeMillis());
            roomPeer.setOnline(true);
            // 从消息中读取discoverable标志，公开房间可被发现，私有房间不可被发现
            boolean isDiscoverable = message.isDiscoverable();
            roomPeer.setDiscoverable(isDiscoverable);
            roomPeer.setFriend(false); // 是房间不是好友
            // 设置房间号
            roomPeer.setRoomCode(message.getRoomCode());
            // 设置房间名称
            if (message.getRoomName() != null && !message.getRoomName().isEmpty()) {
                roomPeer.setRoomName(message.getRoomName());
            }
            // 设置房主IP（与handleRoomCreateBroadcast保持一致）
            roomPeer.setOwnerIp(senderIp);
            peers.add(roomPeer);
            Log.d(TAG, "New " + (isDiscoverable ? "public" : "private") + " room discovered: " + senderIp + " port: " + senderPort + " host: " + message.getSender());
        } else {
            // 更新现有房间信息
            roomPeer.setUserName(message.getSender());
            roomPeer.setLastSeen(System.currentTimeMillis());
            roomPeer.setOnline(true);
            // 更新房间号
            roomPeer.setRoomCode(message.getRoomCode());
            // 更新房间名称
            if (message.getRoomName() != null && !message.getRoomName().isEmpty()) {
                roomPeer.setRoomName(message.getRoomName());
            }
        }

        // 无论是否为新房间，都通知监听器更新列表
        for (OnPeerChangeListener listener : peerChangeListeners) {
            mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
        }
    }

    /**
     * 处理通过UDP广播收到的房间创建消息
     */
    private void handleRoomCreateBroadcast(BroadcastMessage message, String senderIp) {
        // 过滤回环地址
        if (isLoopbackAddress(senderIp)) {
            Log.d(TAG, "Ignoring room create broadcast from loopback: " + senderIp);
            return;
        }

        // 跳过自己发送的广播（通过deviceId判断）
        if (message.getDeviceId() != null && message.getDeviceId().equals(deviceId)) {
            Log.d(TAG, "Ignoring room create broadcast from self (deviceId match)");
            return;
        }

        // 查找是否已存在该房间（优先通过房间码查找，再通过 IP+端口查找）
        LanPeer roomPeer = null;
        String roomCode = message.getRoomCode();
        if (roomCode != null && !roomCode.isEmpty()) {
            for (LanPeer p : peers) {
                if (p.isRoom() && roomCode.equals(p.getRoomCode())) {
                    roomPeer = p;
                    break;
                }
            }
        }
        if (roomPeer == null) {
            for (LanPeer p : peers) {
                if (p.getIp().equals(senderIp) && p.getTcpPort() == message.getSenderPort() && p.isRoom()) {
                    roomPeer = p;
                    break;
                }
            }
        }

        boolean isNewRoom = (roomPeer == null);
        if (isNewRoom) {
            roomPeer = new LanPeer();
            roomPeer.setIp(senderIp);
            roomPeer.setTcpPort(message.getSenderPort());
            roomPeer.setUserName(message.getSender());
            roomPeer.setRoom(true);
            roomPeer.setLastSeen(System.currentTimeMillis());
            roomPeer.setOnline(true);
            roomPeer.setDiscoverable(message.isDiscoverable());
            roomPeer.setFriend(false);
            // 保存房间号，用于点击时加入房间
            if (message.getRoomCode() != null && !message.getRoomCode().isEmpty()) {
                roomPeer.setRoomCode(message.getRoomCode());
            }
            // 保存房间名称，用于显示
            if (message.getRoomName() != null && !message.getRoomName().isEmpty()) {
                roomPeer.setRoomName(message.getRoomName());
            }
            // 保存房主IP，任何人进入房间都可以看到房主IP
            roomPeer.setOwnerIp(senderIp);
            peers.add(roomPeer);
            Log.d(TAG, "New room discovered via broadcast: " + senderIp + " port: " + message.getSenderPort());
        } else {
            roomPeer.setUserName(message.getSender());
            roomPeer.setLastSeen(System.currentTimeMillis());
            roomPeer.setOnline(true);
            roomPeer.setDiscoverable(message.isDiscoverable());
            // 更新房间号
            if (message.getRoomCode() != null && !message.getRoomCode().isEmpty()) {
                roomPeer.setRoomCode(message.getRoomCode());
            }
            // 更新房间名称
            if (message.getRoomName() != null && !message.getRoomName().isEmpty()) {
                roomPeer.setRoomName(message.getRoomName());
            }
            // 更新房主IP
            roomPeer.setOwnerIp(senderIp);
        }

        // 无论是否为新房间，都通知监听器更新列表
        for (OnPeerChangeListener listener : peerChangeListeners) {
            mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
        }
    }


    private void startTcpAcceptLoop() {
        executorService.execute(() -> {
            while (isRunning && tcpServerSocket != null && !tcpServerSocket.isClosed()) {
                try {
                    Socket clientSocket = tcpServerSocket.accept();
                    String clientIp = clientSocket.getInetAddress().getHostAddress();
                    Log.d(TAG, "TCP connection from: " + clientIp);
                    handleTcpClient(clientSocket, clientIp);
                } catch (SocketException e) {
                    if (!isRunning) break;
                } catch (IOException e) {
                    Log.e(TAG, "TCP accept error: " + e.getMessage());
                }
            }
            Log.d(TAG, "TCP accept loop ended");
        });
    }
    
    /**
     * 处理TCP客户端连接
     */
    private void handleTcpClient(Socket socket, String clientIp) {
        executorService.execute(() -> {
            LanPeer peer = null;
            PrintWriter currentWriter = null; // 在try外声明，供catch块使用
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(
                        socket.getOutputStream(), true);

                // 读取第一行握手消息，获取用户名
                String firstLine = reader.readLine();
                if (firstLine == null) {
                    socket.close();
                    return;
                }

                // 解析握手消息
                ChatMessage handshakeMsg = gson.fromJson(firstLine, ChatMessage.class);
                String clientUsername = null;
                int clientTcpPort = 0;
                long clientUid = 0;

                if (handshakeMsg != null && "handshake".equals(handshakeMsg.getType())) {
                    clientUsername = handshakeMsg.getSender();
                    clientTcpPort = handshakeMsg.getSenderPort();
                    clientUid = handshakeMsg.getUid();
                }

                // 判断是否为回环地址（同一设备上的连接）
                boolean isLoopback = isLoopbackAddress(clientIp);

                // 优先用uid查找peer（同设备多实例时IP相同，uid是唯一身份）
                if (clientUid != 0) {
                    for (LanPeer p : peers) {
                        if (p.getUid() == clientUid) {
                            peer = p;
                            break;
                        }
                    }
                }

                // uid未命中，用IP+用户名+端口查找
                if (peer == null) {
                    for (LanPeer p : peers) {
                        if (p.getIp().equals(clientIp) &&
                            p.getUserName() != null &&
                            p.getUserName().equals(clientUsername) &&
                            p.getTcpPort() == clientTcpPort) {
                            peer = p;
                            break;
                        }
                    }
                }

                // 如果没找到，且是回环地址，尝试用用户名+端口查找（忽略IP）
                // 解决同一设备多实例时，TCP连接走回环导致peer查找失败的问题
                if (peer == null && isLoopback) {
                    for (LanPeer p : peers) {
                        if (p.getUserName() != null &&
                            p.getUserName().equals(clientUsername) &&
                            p.getTcpPort() == clientTcpPort) {
                            peer = p;
                            Log.d(TAG, "Found peer by username+port (loopback): " + clientUsername + " port " + clientTcpPort);
                            break;
                        }
                    }
                }

                if (peer == null) {
                    peer = new LanPeer();
                    // 如果是回环地址，使用本地网络IP，避免创建IP为127.0.0.1的重复peer
                    if (isLoopback) {
                        String localIp = NetworkUtils.getLocalIpAddress();
                        peer.setIp(localIp != null ? localIp : clientIp);
                    } else {
                        peer.setIp(clientIp);
                    }
                    peer.setUserName(clientUsername != null ? clientUsername : "用户@" + clientIp);
                    peer.setTcpPort(clientTcpPort);  // 端口将从handshake消息中获取
                    if (clientUid != 0) peer.setUid(clientUid);
                    peer.setOnline(true);

                    Log.d(TAG, "TCP connection established to " + peer.getIp() + ":" + peer.getTcpPort());
                    peer.setLastSeen(System.currentTimeMillis());
                    peer.setFriend(true);
                    peers.add(peer);
                    Log.d(TAG, "Created new peer for TCP client: " + clientIp + " username: " + clientUsername + " port: " + clientTcpPort);
                } else {
                    // 更新peer信息（回环连接时不覆盖IP，保持原有的网络IP）
                    if (!isLoopback) {
                        peer.setIp(clientIp);
                    }
                    peer.setUserName(clientUsername != null ? clientUsername : "用户@" + clientIp);
                    peer.setTcpPort(clientTcpPort);
                    if (clientUid != 0) peer.setUid(clientUid);
                    peer.setLastSeen(System.currentTimeMillis());
                    Log.d(TAG, "Updated peer for TCP client: " + clientIp + " username: " + clientUsername + " port: " + clientTcpPort + " uid=" + clientUid);
                }

                boolean skipStaleSocketCleanup = false;
                // 互连竞态处理：双方同时发起TCP连接时，用IP+端口比较规则打破对称性
                // 比较优先级：先比IP，IP相等时比TCP端口
                // 值较小的一方保留入站连接（替换出站），值较大的一方保留出站连接（关闭入站）
                // 这样双方最终使用同一个TCP连接，不会互相关闭导致连接全部断开
                if (peer.getSocket() != null && !peer.getSocket().isClosed() && peer.isOnline()) {
                    String localIp = NetworkUtils.getLocalIpAddress();
                    String peerIpForCompare = isLoopback ? localIp : clientIp;
                    int localPort = tcpServerPort;
                    int peerPort = clientTcpPort;
                    
                    // 比较规则：先比IP，IP相等时比端口
                    boolean shouldKeepInbound;
                    if (localIp != null && peerIpForCompare != null && !localIp.equals(peerIpForCompare)) {
                        shouldKeepInbound = localIp.compareTo(peerIpForCompare) < 0;
                    } else {
                        // IP相等（同设备多开），用端口比较
                        shouldKeepInbound = localPort < peerPort;
                    }
                    
                    if (shouldKeepInbound) {
                        // 本机值较小：保留入站连接，替换出站连接
                        Log.d(TAG, "Mutual connect race: keeping inbound, replacing outbound from " + clientIp + " (local=" + localIp + ":" + localPort + " vs peer=" + peerIpForCompare + ":" + peerPort + ")");
                        DebugLogger.logDiscovery("LanChat", "互连竞态处理", "保留入站连接 " + clientIp + " (本机=" + localIp + ":" + localPort + " < 对方=" + peerIpForCompare + ":" + peerPort + ")");
                        
                        // 不关闭旧的出站连接！关闭会导致对方的startPeerListener检测到断开，
                        // 对方重新发起连接，形成无限循环。
                        // 让startPeerListener自然退出：peer.getSocket()被替换后，
                        // startPeerListener检查peer.getSocket()!=currentSocket，不清理peer状态
                        skipStaleSocketCleanup = true;
                    } else {
                        // 本机值较大或相等：保留出站连接，关闭入站连接
                        Log.d(TAG, "Mutual connect race: keeping outbound, closing inbound from " + clientIp + " (local=" + localIp + ":" + localPort + " vs peer=" + peerIpForCompare + ":" + peerPort + ")");
                        DebugLogger.logDiscovery("LanChat", "互连竞态处理", "保留出站连接 " + clientIp + " (本机=" + localIp + ":" + localPort + " >= 对方=" + peerIpForCompare + ":" + peerPort + ")");
                        try { socket.close(); } catch (IOException ignored) {}
                        return;
                    }
                }

                // 关闭旧连接（如果存在但已失效），但互连竞态保留入站连接时跳过
                if (!skipStaleSocketCleanup && peer.getSocket() != null && !peer.getSocket().isClosed()) {
                    Log.d(TAG, "Closing stale socket for " + clientIp);
                    try { 
                        peer.getSocket().close(); 
                    } catch (IOException ignored) {}
                    // 清理peer状态，确保一致性
                    peer.setSocket(null);
                    peer.setWriter(null);
                    peer.setOnline(false);
                }

                // 设置peer的socket和writer
                peer.setSocket(socket);
                peer.setWriter(writer);
                peer.setOnline(true);
                synchronized (peer) {
                    // 入站连接由本线程负责读，登记后不会再挂第二个读线程
                    peer.setReaderSocket(socket);
                }
                currentWriter = writer; // 捕获当前writer，用于后续判断

                // 处理握手响应
                if (handshakeMsg != null && "handshake".equals(handshakeMsg.getType())) {
                    // 从握手消息中提取对方的uid
                    long remoteUid = handshakeMsg.getUid();
                    if (remoteUid != 0) {
                        peer.setUid(remoteUid);
                        Log.d(TAG, "Received remote uid=" + remoteUid + " from " + clientIp);
                    }

                    ChatMessage response = new ChatMessage();
                    response.setType("handshake_response");

                    // 验证逻辑：如果可被发现，则自动接受
                    boolean isAutoAccept = discoverable;  // 如果可被发现，自动接受

                    if (isAutoAccept) {
                        response.setSender(userName);
                        response.setContent("success");
                        writer.println(new Gson().toJson(response));
                        writer.flush();
                        Log.d(TAG, "Handshake successful with " + clientIp + " uid=" + remoteUid);

                        // 添加好友
                        handleAutoFriendAdd(clientIp, handshakeMsg.getSender(), remoteUid);
                    } else {
                        response.setSender(userName);
                        response.setContent("not_discoverable");
                        writer.println(new Gson().toJson(response));
                        writer.flush();
                        Log.d(TAG, "Handshake failed - not discoverable from " + clientIp);
                    }
                }

                // 读取消息（统一处理新旧协议）
                String line;
                while (isRunning) {
                    try {
                        line = reader.readLine();
                        if (line == null) break;
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    // 处理新协议文本消息
                    if (line.startsWith("MSG:")) {
                        String messageContent = line.substring(4);
                        ChatMessage message = new ChatMessage();
                        message.setType("text");
                        message.setContent(messageContent);
                        message.setSender(peer.getUserName());
                        message.setTimestamp(System.currentTimeMillis());

                        // 通知监听器
                        // 去重检查：避免 UDP 和 TCP 重复传递同一条消息
                        message.setSenderIp(peer.getIp());
                        message.setSenderPort(peer.getTcpPort());
                        if (!isNewMessage(message)) {
                            Log.d(TAG, "Duplicate message detected, skipping: " + message.getContent());
                            continue;
                        }

                        for (OnPeerChangeListener listener : peerChangeListeners) {
                            listener.onMessageReceived(peer, message);
                        }
                        // 保存消息到数据库，确保从通知进入聊天窗口时消息已持久化
                        if (appContext != null) {
                            try {
                                LanChatDatabase db = LanChatDatabase.getInstance(appContext);
                                db.saveMessage(peer.getIp(), peer.getUserName(), message);
                                message.setSavedToDb(true); // 标记已保存
                            } catch (Exception e) {
                                Log.e(TAG, "Error saving message to database: " + e.getMessage());
                            }
                        }
                        // 显示通知，确保不在聊天窗口时也能收到
                        if (appContext != null) {
                            LanChatNotificationManager notificationMgr = LanChatNotificationManager.getInstance(appContext);
                            notificationMgr.showMessageNotification(
                                    message.getSender() != null ? message.getSender() : peer.getUserName(),
                                    peer.getIp(),
                                    message.getContent());
                        }
                        continue;
                    }
                    // 处理新协议文件传输请求
                    else if (line.startsWith("FILE_REQUEST:")) {
                        line = handleFileReceive(reader, peer, line);
                        if (line == null) break;
                        continue;
                    }
                    // 处理踢人通知（KICKED）
                    else if (line.startsWith("{") && line.contains("\"type\":\"KICKED\"")) {
                        try {
                            ChatMessage kickedMsg = gson.fromJson(line, ChatMessage.class);
                            if (kickedMsg != null && "KICKED".equals(kickedMsg.getType())) {
                                String kickedRoomCode = kickedMsg.getRoomCode();
                                Log.d(TAG, "Received KICKED notification for room: " + kickedRoomCode);
                                // 通知监听器（由Activity处理退出逻辑）
                                for (OnPeerChangeListener listener : peerChangeListeners) {
                                    listener.onMessageReceived(peer, kickedMsg);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing KICKED message: " + e.getMessage());
                        }
                        continue;
                    }

                    // 处理旧版JSON消息（解析失败只跳过这一行）
                    ChatMessage chatMsg;
                    try {
                        chatMsg = gson.fromJson(line, ChatMessage.class);
                    } catch (Exception parseError) {
                        Log.w(TAG, "跳过无法解析的消息行: " + parseError.getMessage());
                        continue;
                    }
                    if (chatMsg != null) {
                        // 处理握手消息（已经处理过了，跳过）
                        if ("handshake".equals(chatMsg.getType())) {
                            continue;
                        }

                        // 处理握手响应消息（对方回复handshake_response），不作为聊天消息处理
                        if ("handshake_response".equals(chatMsg.getType())) {
                            Log.d(TAG, "Received handshake_response from " + clientIp + ": " + chatMsg.getContent());
                            // 握手响应仅用于确认连接成功，不传递给聊天界面
                            continue;
                        }

                        // 处理好友请求响应
                        if ("friend_response".equals(chatMsg.getType())) {
                            String status = chatMsg.getContent();
                            if ("auto_accepted".equals(status)) {
                                // 对方自动接受了好友请求
                                handleAutoFriendAdd(clientIp, chatMsg.getSender(), chatMsg.getUid());
                            }
                            continue;
                        }

                        // 处理送达确认消息
                        if ("delivery_status".equals(chatMsg.getType())) {
                            Log.d(TAG, "Received delivery_status for message: " + chatMsg.getMessageId());
                            for (OnPeerChangeListener listener : peerChangeListeners) {
                                final LanPeer finalPeer = peer;
                                final ChatMessage finalMsg = chatMsg;
                                mainHandler.post(() -> listener.onMessageReceived(finalPeer, finalMsg));
                            }
                            continue;
                        }

                        // 处理房间邀请消息（通知Activity弹窗确认，不自动加入）
                        if ("room_invite".equals(chatMsg.getType())) {
                            String invitedRoomCode = chatMsg.getRoomCode();
                            String inviterName = chatMsg.getSender();
                            String invitedRoomName = chatMsg.getRoomName();
                            if (invitedRoomCode != null && !invitedRoomCode.isEmpty()) {
                                Log.d(TAG, "Received room invite to room " + invitedRoomCode + " from " + inviterName);
                                // 始终通知全局邀请监听器（弹窗确认）
                                if (roomInviteListener != null) {
                                    final String rn = invitedRoomName;
                                    mainHandler.post(() -> roomInviteListener.onRoomInviteReceived(inviterName, invitedRoomCode, rn));
                                }
                            }
                            // 不continue，让消息继续传递到peerChangeListeners
                        }

                        // 保留发送方的原始sender、IP端口和timestamp
                        if (chatMsg.getSender() == null || chatMsg.getSender().isEmpty()) {
                            chatMsg.setSender(userName);
                        }
                        // 注意：这里我们不覆盖发送方的IP和端口，而是保留原始值
                        // chatMsg.setSenderIp(...) 和 chatMsg.setSenderPort(...) 不应该在这里设置
                        if (chatMsg.getTimestamp() == 0) {
                            chatMsg.setTimestamp(System.currentTimeMillis());
                        }

                        // 通知所有监听器
                        final ChatMessage finalMsg = chatMsg;
                        final LanPeer finalPeer = peer;
                        if (finalPeer != null) {
                            for (OnPeerChangeListener listener : peerChangeListeners) {
                                mainHandler.post(() ->
                                        listener.onMessageReceived(finalPeer, finalMsg));
                            }
                            // 保存消息到数据库，确保从通知进入聊天窗口时消息已持久化
                            if (appContext != null && ("text".equals(finalMsg.getType()) || "wifi_backup".equals(finalMsg.getType()))) {
                                try {
                                    LanChatDatabase db = LanChatDatabase.getInstance(appContext);
                                    db.saveMessage(finalPeer.getIp(), finalPeer.getUserName(), finalMsg);
                                    finalMsg.setSavedToDb(true); // 标记已保存，防止addReceivedMessage重复保存
                                } catch (Exception e) {
                                    Log.e(TAG, "Error saving message to database: " + e.getMessage());
                                }
                            }
                            // 在LanChatManager层面直接显示通知，确保无论用户在哪个界面都能收到
                            if (appContext != null && "text".equals(finalMsg.getType())) {
                                LanChatNotificationManager notificationMgr = LanChatNotificationManager.getInstance(appContext);
                                notificationMgr.showMessageNotification(
                                        finalMsg.getSender() != null ? finalMsg.getSender() : finalPeer.getUserName(),
                                        finalPeer.getIp(),
                                        finalMsg.getContent());
                            }
                            // 入站连接这一侧同样要回执，否则主动方永远停在「发送中」
                            if ("text".equals(finalMsg.getType())
                                    && finalMsg.getMessageId() != null
                                    && !finalMsg.getMessageId().isEmpty()) {
                                sendDeliveryStatus(finalPeer, finalMsg.getMessageId());
                            }
                        }
                    }
                }

                // while 循环正常退出（line == null），表示客户端关闭了连接
                Log.d(TAG, "TCP client reader reached EOF: " + clientIp);
                if (peer != null) {
                    synchronized (peer) {
                        if (peer.getReaderSocket() == socket) peer.setReaderSocket(null);
                    }
                    synchronized (peer) {
                        if (peer.getWriter() == currentWriter) {
                            Log.d(TAG, "Cleaning up peer state due to EOF: " + peer.getUserName());
                            peer.setOnline(false);
                            peer.setSocket(null);
                            peer.setWriter(null);
                        }
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "TCP client disconnected: " + clientIp);
                // 标记peer离线（仅当writer未被新连接更新时）
                if (peer != null) {
                    synchronized (peer) {
                        if (peer.getWriter() == currentWriter) {
                            peer.setOnline(false);
                            peer.setSocket(null);
                            peer.setWriter(null);
                        }
                    }
                }
            } finally {
                if (peer != null) {
                    synchronized (peer) {
                        if (peer.getReaderSocket() == socket) peer.setReaderSocket(null);
                    }
                }
                try { socket.close(); } catch (IOException ignored) {}
            }
        });
    }

    /**
     * 判断是不是「本机这个实例」发出的消息（回声过滤）。
     *
     * 只按 deviceId + 本机服务端口判断：昵称可能重名（默认昵称都是「用户xxx」），
     * 用昵称判自环会让同名设备之间整条通道的消息被丢掉 —— 这正是「只能单向聊天」的主因之一。
     */
    private boolean isSelfMessage(ChatMessage message) {
        if (message == null) return false;
        String id = message.getDeviceId();
        if (id == null || id.isEmpty() || deviceId == null || !id.equals(deviceId)) return false;
        int port = message.getSenderPort();
        // 同设备多开时端口不同，端口相同才算自己发出去的回声
        return port <= 0 || port == tcpServerPort;
    }

    /**
     * 主动连接到其他设备
     */
    public void connectToPeer(LanPeer peer) {
        // 参数校验（同步执行，避免竞态条件）
        if (peer == null || peer.getIp() == null || peer.getIp().isEmpty()) {
            Log.e(TAG, "connectToPeer: invalid peer or IP");
            return;
        }
        if (peer.getTcpPort() <= 0) {
            Log.e(TAG, "connectToPeer: invalid TCP port " + peer.getTcpPort() + " for peer " + peer.getIp());
            return;
        }
        // 连接去重：同步检查，防止多个线程同时发起连接
        synchronized (peer) {
            if (peer.isConnecting()) {
                Log.d(TAG, "connectToPeer: already connecting to " + peer.getIp() + ", skipping");
                return;
            }
            if (peer.getSocket() != null && !peer.getSocket().isClosed() && peer.isOnline()) {
                Log.d(TAG, "connectToPeer: already connected to " + peer.getIp() + ", skipping");
                return;
            }
            peer.setConnecting(true);
        }

        // 跨peer去重：同设备多开时，peer列表中可能有多个peer指向同一设备（不同IP）
        // 如果已有同设备的在线peer，不需要再建立新连接
        // 优先用uid判断（如果两个peer的uid相同，说明是同一设备）
        synchronized (peers) {
            for (LanPeer p : peers) {
                if (p != peer) {
                    // uid相同 → 同一设备
                    if (peer.getUid() != 0 && p.getUid() == peer.getUid() &&
                        p.isOnline() && p.getSocket() != null && !p.getSocket().isClosed()) {
                        synchronized (peer) {
                            peer.setConnecting(false);
                        }
                        return;
                    }
                    // uid未命中时，用username+port匹配
                    if (p.getUserName() != null &&
                        p.getUserName().equals(peer.getUserName()) &&
                        p.getTcpPort() == peer.getTcpPort() &&
                        p.isOnline() && p.getSocket() != null && !p.getSocket().isClosed()) {
                        synchronized (peer) {
                            peer.setConnecting(false);
                        }
                        return;
                    }
                }
            }
        }

        executorService.execute(() -> {
            // 重试机制：最多尝试3次连接
            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // 检查本地网络状态
                    String localIp = NetworkUtils.getLocalIpAddress();
                    if (localIp == null || localIp.isEmpty()) {
                        Log.e(TAG, "Local IP address not available");
                        DebugLogger.logDiscovery("LanChat", "TCP连接失败", "本地IP地址不可用");
                        break;
                    }

                    // 创建Socket并设置连接超时（InetAddress.isReachable在Android上不可靠，直接用socket.connect超时判断）
                    Socket socket = new Socket();
                    socket.connect(new java.net.InetSocketAddress(peer.getIp(), peer.getTcpPort()), 3000); // 3秒连接超时
                    socket.setSoTimeout(5000); // 5秒读取超时
                    socket.setKeepAlive(true); // 半开连接至少能被内核探测出来

                    PrintWriter writer = new PrintWriter(
                            socket.getOutputStream(), true);

                    synchronized (peer) {
                        // 再次检查，防止在建立连接期间handleTcpClient已设置了连接
                        if (peer.getSocket() != null && !peer.getSocket().isClosed() && peer.isOnline()) {
                            // 互连竞态处理：与handleTcpClient对称
                            // 比较优先级：先比IP，IP相等时比TCP端口
                            // 值较大的一方保留出站连接（替换入站），值较小的一方保留入站连接（关闭出站）
                            localIp = NetworkUtils.getLocalIpAddress();
                            int localPort = tcpServerPort;
                            int peerPort = peer.getTcpPort();
                            
                            boolean shouldKeepOutbound;
                            if (localIp != null && peer.getIp() != null && !localIp.equals(peer.getIp())) {
                                shouldKeepOutbound = localIp.compareTo(peer.getIp()) >= 0;
                            } else {
                                // IP相等（同设备多开），用端口比较
                                shouldKeepOutbound = localPort >= peerPort;
                            }
                            
                            if (shouldKeepOutbound) {
                                // 本机值较大：保留出站连接，替换入站连接
                                Log.d(TAG, "connectToPeer: keeping outbound, replacing inbound (local=" + localIp + ":" + localPort + " >= peer=" + peer.getIp() + ":" + peerPort + ")");
                                // 不关闭旧的入站连接！关闭会导致对方的handleTcpClient检测到断开，
                                // 对方重新发起连接，形成无限循环。
                                // 让handleTcpClient自然退出：peer.getSocket()被替换后，
                                // handleTcpClient检查peer.getWriter()!=currentWriter，不清理peer状态
                            } else {
                                // 本机值较小：保留入站连接，关闭出站连接
                                Log.d(TAG, "connectToPeer: keeping inbound, closing outbound (local=" + localIp + ":" + localPort + " < peer=" + peer.getIp() + ":" + peerPort + ")");
                                try { socket.close(); } catch (IOException ignored) {}
                                peer.setConnecting(false);
                                return;
                            }
                        }
                        peer.setSocket(socket);
                        peer.setWriter(writer);
                        peer.setOnline(true);
                        peer.setConnecting(false);
                    }

                    Log.d(TAG, "TCP connection established to " + peer.getIp() + ":" + peer.getTcpPort());

                    // 发送握手消息（包含本机UID和TCP端口，让对方能回连）
                    ChatMessage handshake = new ChatMessage();
                    handshake.setType("handshake");
                    handshake.setUid(myUid);
                    handshake.setSender(userName);
                    handshake.setSenderPort(tcpServerPort);
                    handshake.setContent("");
                    peer.sendMessage(handshake);

                    Log.d(TAG, "Handshake sent to " + peer.getIp() + " uid=" + myUid);

                    // 开始监听该peer的消息（传本次握手用的 socket，避免读到被别人替换的连接）
                    startPeerListener(peer, socket);

                    Log.d(TAG, "Connected to peer: " + peer.getUserName() + " (attempt " + attempt + ")");
                    DebugLogger.logDiscovery("LanChat", "TCP连接成功", peer.getUserName() + " @ " + peer.getIp() + ":" + peer.getTcpPort());
                    return; // 成功连接，退出重试循环
                } catch (IOException e) {
                    Log.e(TAG, "Error connecting to peer (attempt " + attempt + "): " + e.getMessage());
                    DebugLogger.logDiscovery("LanChat", "TCP连接失败(尝试" + attempt + ")", peer.getUserName() + " @ " + peer.getIp() + ":" + peer.getTcpPort() + " - " + e.getMessage());
                    peer.setOnline(false);

                    // 如果不是最后一次尝试，等待一段时间再重试（指数退避）
                    if (attempt < maxRetries) {
                        try {
                            long delay = (long) Math.pow(2, attempt) * 1000; // 2^attempt 秒延迟
                            // 最大延迟5秒
                            delay = Math.min(delay, 5000);
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            synchronized (peer) {
                peer.setConnecting(false);
                peer.setOnline(false);
            }
            Log.e(TAG, "Failed to connect to peer after " + maxRetries + " attempts: " + peer.getUserName() + " @ " + peer.getIp() + ":" + peer.getTcpPort());
            DebugLogger.logDiscovery("LanChat", "TCP连接彻底失败", peer.getUserName() + " @ " + peer.getIp() + ":" + peer.getTcpPort() + " (重试" + maxRetries + "次)");
        });
    }
    
    /**
     * 监听peer消息
     */
    private void startPeerListener(LanPeer peer) {
        startPeerListener(peer, peer.getSocket());
    }

    /**
     * 监听指定 socket 上的消息。
     *
     * 必须显式传入本次握手所用的 socket：peer.socket 可能被「互连竞态」的另一条连接替换，
     * 如果这里再去读 peer.getSocket()，就会出现两个线程读同一个 InputStream，
     * JSON 行被随机撕成两半、双方解析失败并丢弃 —— 表现就是「一边能收、另一边收不到」。
     */
    private void startPeerListener(LanPeer peer, Socket boundSocket) {
        if (peer == null || boundSocket == null || boundSocket.isClosed()) return;
        synchronized (peer) {
            if (peer.getReaderSocket() == boundSocket) {
                Log.d(TAG, "startPeerListener: 该 socket 已有读线程，跳过");
                return;
            }
            peer.setReaderSocket(boundSocket);
        }
        executorService.execute(() -> {
            Socket currentSocket = boundSocket; // 固定本次监听的 socket
            try {
                Socket socket = boundSocket;
                if (socket == null || socket.isClosed()) return;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                String line;
                while (isRunning) {
                    try {
                        line = reader.readLine();
                        if (line == null) break;
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    // 处理新协议文本消息
                    if (line.startsWith("MSG:")) {
                        String messageContent = line.substring(4);
                        ChatMessage message = new ChatMessage();
                        message.setType("text");
                        message.setContent(messageContent);
                        message.setSender(peer.getUserName());
                        message.setTimestamp(System.currentTimeMillis());

                        // 通知监听器
                        for (OnPeerChangeListener listener : peerChangeListeners) {
                            listener.onMessageReceived(peer, message);
                        }
                        // 保存消息到数据库，确保从通知进入聊天窗口时消息已持久化
                        if (appContext != null) {
                            try {
                                LanChatDatabase db = LanChatDatabase.getInstance(appContext);
                                db.saveMessage(peer.getIp(), peer.getUserName(), message);
                                message.setSavedToDb(true); // 标记已保存
                            } catch (Exception e) {
                                Log.e(TAG, "Error saving message to database: " + e.getMessage());
                            }
                        }
                        // 显示通知，确保不在聊天窗口时也能收到
                        if (appContext != null) {
                            LanChatNotificationManager notificationMgr = LanChatNotificationManager.getInstance(appContext);
                            notificationMgr.showMessageNotification(
                                    message.getSender() != null ? message.getSender() : peer.getUserName(),
                                    peer.getIp(),
                                    message.getContent());
                        }
                        continue;
                    }
                    // 处理新协议文件传输请求
                    else if (line.startsWith("FILE_REQUEST:")) {
                        line = handleFileReceive(reader, peer, line);
                        if (line == null) break;
                        continue;
                    }
                    
                    // 处理旧版JSON消息（解析失败只跳过这一行，绝不能让接收线程直接死掉）
                    ChatMessage chatMsg;
                    try {
                        chatMsg = gson.fromJson(line, ChatMessage.class);
                    } catch (Exception parseError) {
                        Log.w(TAG, "跳过无法解析的消息行: " + parseError.getMessage());
                        continue;
                    }
                    if (chatMsg != null) {
                        // 过滤握手响应消息，不作为聊天消息处理
                        if ("handshake_response".equals(chatMsg.getType())) {
                            Log.d(TAG, "Received handshake_response from peer " + peer.getUserName() + ": " + chatMsg.getContent());
                            continue;
                        }

                        // 过滤好友请求响应消息
                        if ("friend_response".equals(chatMsg.getType())) {
                            Log.d(TAG, "Received friend_response from peer " + peer.getUserName() + ": " + chatMsg.getContent());
                            continue;
                        }

                        // 处理送达确认消息
                        if ("delivery_status".equals(chatMsg.getType())) {
                            Log.d(TAG, "Received delivery_status for message: " + chatMsg.getMessageId());
                            for (OnPeerChangeListener listener : peerChangeListeners) {
                                mainHandler.post(() -> listener.onMessageReceived(peer, chatMsg));
                            }
                            continue;
                        }

                        // 处理房间邀请消息
                        if ("room_invite".equals(chatMsg.getType())) {
                            String invitedRoomCode = chatMsg.getRoomCode();
                            String inviterName = chatMsg.getSender();
                            String invitedRoomName = chatMsg.getRoomName();
                            if (invitedRoomCode != null && !invitedRoomCode.isEmpty()) {
                                Log.d(TAG, "Received room invite to room " + invitedRoomCode + " from " + inviterName);
                                if (roomInviteListener != null) {
                                    final String rn = invitedRoomName;
                                    mainHandler.post(() -> roomInviteListener.onRoomInviteReceived(inviterName, invitedRoomCode, rn));
                                }
                            }
                            // 不continue，让消息继续传递到peerChangeListeners
                        }

                        // 保留发送方的原始时间戳、IP和端口
                        if (chatMsg.getSender() == null || chatMsg.getSender().isEmpty()) {
                            chatMsg.setSender(userName);
                        }
                        // 注意：这里我们不覆盖发送方的IP和端口，而是保留原始值
                        // chatMsg.setSenderIp(...) 和 chatMsg.setSenderPort(...) 不应该在这里设置
                        if (chatMsg.getTimestamp() == 0) {
                            chatMsg.setTimestamp(System.currentTimeMillis());
                        }

                        if (!peerChangeListeners.isEmpty()) {
                            final ChatMessage finalMsg = chatMsg;
                            // 跳过自己发送的消息（避免重复显示）
                            if (isSelfMessage(chatMsg)) {
                                continue;
                            }

                            for (OnPeerChangeListener listener : peerChangeListeners) {
                                mainHandler.post(() ->
                                        listener.onMessageReceived(peer, finalMsg));
                            }
                        }

                        // 保存消息到数据库，确保从通知进入聊天窗口时消息已持久化
                        if (appContext != null && "text".equals(chatMsg.getType())) {
                            if (isSelfMessage(chatMsg)) {
                                continue;
                            }
                            try {
                                LanChatDatabase db = LanChatDatabase.getInstance(appContext);
                                db.saveMessage(peer.getIp(), peer.getUserName(), chatMsg);
                            } catch (Exception e) {
                                Log.e(TAG, "Error saving message to database: " + e.getMessage());
                            }
                        }

                        // 在LanChatManager层面直接显示通知，确保无论用户在哪个界面都能收到
                        // 通知逻辑独立于peerChangeListeners，即使没有监听器也要显示通知
                        if (appContext != null && "text".equals(chatMsg.getType())) {
                            if (isSelfMessage(chatMsg)) {
                                continue;
                            }
                            LanChatNotificationManager notificationMgr = LanChatNotificationManager.getInstance(appContext);
                            notificationMgr.showMessageNotification(
                                    chatMsg.getSender() != null ? chatMsg.getSender() : peer.getUserName(),
                                    peer.getIp(),
                                    chatMsg.getContent());
                        }

                        // 发送送达确认：如果消息有messageId，回复delivery_status
                        if ("text".equals(chatMsg.getType()) && chatMsg.getMessageId() != null && !chatMsg.getMessageId().isEmpty()) {
                            sendDeliveryStatus(peer, chatMsg.getMessageId());
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Peer listener closed: " + peer.getUserName() + " - " + e.getMessage());
                synchronized (peer) {
                    // 仅当socket未被新连接更新时才清理
                    if (peer.getSocket() == currentSocket) {
                        Log.d(TAG, "Cleaning up peer state: " + peer.getUserName());
                        peer.setOnline(false);
                        peer.setSocket(null);
                        peer.setWriter(null);
                    } else {
                        Log.d(TAG, "Peer socket updated, skipping cleanup: " + peer.getUserName());
                    }
                }
            } finally {
                synchronized (peer) {
                    if (peer.getReaderSocket() == currentSocket) {
                        peer.setReaderSocket(null);
                    }
                }
            }
        });
    }
    
    /**
     * 发送送达确认给发送者
     */
    private void sendDeliveryStatus(LanPeer peer, String messageId) {
        if (peer == null || peer.getWriter() == null) return;
        try {
            ChatMessage statusMsg = new ChatMessage();
            statusMsg.setType("delivery_status");
            statusMsg.setMessageId(messageId);
            statusMsg.setContent("delivered");
            statusMsg.setSender(userName);
            statusMsg.setTimestamp(System.currentTimeMillis());
            
            String json = gson.toJson(statusMsg);
            peer.getWriter().println(json);
            Log.d(TAG, "Delivery status sent for message: " + messageId);
        } catch (Exception e) {
            Log.e(TAG, "Error sending delivery status: " + e.getMessage());
        }
    }

    /**
     * 发送消息给指定peer
     */
    public boolean sendMessage(LanPeer peer, ChatMessage message) {
        // 确保消息有 uid、sender、发送方IP端口和 timestamp
        if (message.getUid() == 0) {
            message.setUid(myUid);
        }
        if (message.getSender() == null || message.getSender().isEmpty()) {
            message.setSender(userName);
        }
        if (message.getDeviceId() == null || message.getDeviceId().isEmpty()) {
            message.setDeviceId(deviceId);
        }
        if (message.getSenderIp() == null || message.getSenderIp().isEmpty()) {
            String localIp = NetworkUtils.getLocalIpAddress();
            message.setSenderIp(localIp);
        }
        if (message.getSenderPort() <= 0) {
            message.setSenderPort(tcpServerPort);
        }
        if (message.getBackupPort() <= 0) {
            message.setBackupPort(tcpServerPort);
        }
        message.setDiscoverable(discoverable);
        if (message.getTimestamp() == 0) {
            message.setTimestamp(System.currentTimeMillis());
        }

        if (peer != null) {
            // 检查连接状态，如果未连接则尝试连接
            if (!peer.isOnline() || peer.getWriter() == null || peer.getSocket() == null || peer.getSocket().isClosed()) {
                // 触发连接尝试（connectToPeer 内部有重试机制，最多重试3次）
                if (!peer.isConnecting()) {
                    connectToPeer(peer);
                }

                // 轮询等待连接建立，最多等待 20 秒（匹配 connectToPeer 的3次重试总时间）
                int maxWaitTime = 20000;
                int waitInterval = 200;
                int waitedTime = 0;

                while (waitedTime < maxWaitTime) {
                    if (peer.isOnline() && peer.getWriter() != null &&
                        peer.getSocket() != null && !peer.getSocket().isClosed()) {
                        break;
                    }
                    try {
                        Thread.sleep(waitInterval);
                        waitedTime += waitInterval;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            }

            // 再次检查连接状态（更严格的检查）
            if (!peer.isOnline() || peer.getWriter() == null || peer.getSocket() == null || peer.getSocket().isClosed()) {
                // 同设备多开时，peer列表中可能有多个peer指向同一设备（不同IP）
                // 尝试查找同设备的其他在线peer（uid优先）
                LanPeer alternativePeer = null;
                synchronized (peers) {
                    for (LanPeer p : peers) {
                        if (p != peer) {
                            // uid相同 → 同一设备
                            if (peer.getUid() != 0 && p.getUid() == peer.getUid() &&
                                p.isOnline() && p.getWriter() != null &&
                                p.getSocket() != null && !p.getSocket().isClosed()) {
                                alternativePeer = p;
                                break;
                            }
                            // uid未命中，用username+port匹配
                            if (p.getUserName() != null &&
                                p.getUserName().equals(peer.getUserName()) &&
                                p.getTcpPort() == peer.getTcpPort() &&
                                p.isOnline() && p.getWriter() != null &&
                                p.getSocket() != null && !p.getSocket().isClosed()) {
                                alternativePeer = p;
                                break;
                            }
                        }
                    }
                }
                if (alternativePeer != null) {
                    return alternativePeer.sendMessage(message);
                }
                return false;
            }

            return peer.sendMessage(message);
        } else {
            return false;
        }
    }
    
    /**
     * 发送文件给指定peer
     */
    public boolean sendFile(LanPeer peer, File file) {
        if (peer == null || file == null || !file.exists()) {
            return false;
        }

        executorService.execute(() -> {
            try {
                long fileSize = file.length();
                if (fileSize > 50 * 1024 * 1024) {
                    Log.e(TAG, "File too large: " + fileSize + " bytes");
                    return;
                }

                boolean requestSuccess = peer.sendFileRequest(file.getName(), fileSize);
                if (!requestSuccess) {
                    Log.e(TAG, "Failed to send file request to peer: " + peer.getUserName());
                    return;
                }

                Thread.sleep(500);

                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] readBuffer = new byte[3072];
                int bytesRead;

                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

                    while ((bytesRead = fis.read(readBuffer)) != -1) {
                        baos.write(readBuffer, 0, bytesRead);

                        // 每积累约6KB原始数据就编码并发送一次（避免内存溢出）
                        if (baos.size() >= 6144) {
                            byte[] chunkBytes = baos.toByteArray();
                            baos.reset();
                            String encodedChunk = android.util.Base64.encodeToString(chunkBytes, android.util.Base64.NO_WRAP);

                            int sendChunkSize = 8000;
                            for (int i = 0; i < encodedChunk.length(); i += sendChunkSize) {
                                int end = Math.min(i + sendChunkSize, encodedChunk.length());
                                String chunk = encodedChunk.substring(i, end);
                                boolean dataSuccess = peer.sendRawLine("FILE_DATA:" + chunk);
                                if (!dataSuccess) {
                                    Log.e(TAG, "Failed to send file data chunk to peer: " + peer.getUserName());
                                    return;
                                }
                            }
                        }
                    }

                    // 发送剩余数据
                    if (baos.size() > 0) {
                        byte[] remainingBytes = baos.toByteArray();
                        String remainingEncoded = android.util.Base64.encodeToString(remainingBytes, android.util.Base64.NO_WRAP);
                        int sendChunkSize = 8000;
                        for (int i = 0; i < remainingEncoded.length(); i += sendChunkSize) {
                            int end = Math.min(i + sendChunkSize, remainingEncoded.length());
                            String chunk = remainingEncoded.substring(i, end);
                            boolean dataSuccess = peer.sendRawLine("FILE_DATA:" + chunk);
                            if (!dataSuccess) {
                                Log.e(TAG, "Failed to send remaining file data to peer: " + peer.getUserName());
                                return;
                            }
                        }
                    }

                    boolean endSuccess = peer.sendRawLine("FILE_END:");
                    if (!endSuccess) {
                        Log.e(TAG, "Failed to send file end marker to peer: " + peer.getUserName());
                        return;
                    }

                    Log.d(TAG, "File sent successfully to peer: " + peer.getUserName());
                } finally {
                    fis.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending file to peer: " + e.getMessage());
            }
        });

        return true;
    }

    /**
     * 判断IP地址是否为回环地址
     * 用于处理同一设备多实例场景下的TCP连接识别
     */
    private boolean isLoopbackAddress(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }

    
    // ==================== Getters/Setters ====================
    
    public List<LanPeer> getPublicRooms() {
        List<LanPeer> publicRooms = new ArrayList<>();
        for (LanPeer peer : peers) {
            // 公开房间条件：
            // 1. 在线或最近在线（30秒内）
            // 2. 可被发现
            // 3. 是房间（isRoom=true）
            long currentTime = System.currentTimeMillis();
            boolean isRecentlyActive = (currentTime - peer.getLastSeen()) < 30000; // 30秒内活跃
            if ((peer.isOnline() || isRecentlyActive) && peer.isDiscoverable() &&
                peer.isRoom()) {
                publicRooms.add(peer);
            }
        }
        return publicRooms;
    }
    
    public List<LanPeer> getPeers() {
        return new ArrayList<>(peers);
    }

    /**
     * 清空所有 peer（断开所有连接并清空列表）
     */
    public void clearPeers() {
        synchronized (peers) {
            for (LanPeer peer : peers) {
                peer.disconnect();
            }
            peers.clear();
        }
        // 通知监听器更新
        for (OnPeerChangeListener listener : peerChangeListeners) {
            mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>()));
        }
    }

    /**
     * 将外部创建的 peer 加入 peers 列表并尝试连接
     * 用于手动添加好友的场景
     */
    /**
     * 添加 peer 并尝试连接，如果已存在则返回已有的 peer
     * @return 已存在或新添加的 peer 对象
     */
    public LanPeer addAndConnectPeer(LanPeer peer) {
        // 优先用uid查找（如果传入的peer有uid）
        if (peer.getUid() != 0) {
            for (LanPeer p : peers) {
                if (p.getUid() == peer.getUid()) {
                    // 已存在，尝试连接并返回已有对象
                    if (!p.isOnline() || p.getWriter() == null) {
                        connectToPeer(p);
                    }
                    return p;
                }
            }
        }
        
        // uid未命中，用IP+用户名+端口查找
        for (LanPeer p : peers) {
            if (p.getIp() != null && p.getIp().equals(peer.getIp()) &&
                p.getUserName() != null && p.getUserName().equals(peer.getUserName()) &&
                p.getTcpPort() == peer.getTcpPort()) {
                // 已存在，尝试连接并返回已有对象
                if (!p.isOnline() || p.getWriter() == null) {
                    connectToPeer(p);
                }
                return p;
            }
        }
        // 不存在，添加并连接
        peer.setFriend(true);
        peers.add(peer);
        connectToPeer(peer);
        return peer;
    }
    
    /**
     * 添加房间到 peers 列表
     * 用于创建房间的场景
     */
    public void addRoom(LanPeer room) {
        // 检查是否已存在
        for (LanPeer p : peers) {
            if (p.getIp() != null && p.getIp().equals(room.getIp()) &&
                p.isRoom() &&
                p.getTcpPort() == room.getTcpPort()) {
                // 更新现有房间信息
                p.setUserName(room.getUserName());
                p.setDiscoverable(room.isDiscoverable());
                p.setOnline(room.isOnline());
                p.setLastSeen(room.getLastSeen());
                p.setRoomName(room.getRoomName());
                p.setOwnerIp(room.getOwnerIp());
                p.setRoomCode(room.getRoomCode());
                return;
            }
        }
        // 添加新房间
        peers.add(room);

        // 通知监听器更新
        for (OnPeerChangeListener listener : peerChangeListeners) {
            mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
        }
    }

    /**
     * 从peers列表中移除房间
     */
    public void removeRoom(LanPeer roomToRemove) {
        LanPeer roomToRemoveFromList = null;

        // 查找要移除的房间
        for (LanPeer p : peers) {
            if (p.getIp() != null && p.getIp().equals(roomToRemove.getIp()) &&
                p.isRoom() &&
                p.getTcpPort() == roomToRemove.getTcpPort()) {
                roomToRemoveFromList = p;
                break;
            }
        }
        
        // 如果找到了，从列表中移除
        if (roomToRemoveFromList != null) {
            peers.remove(roomToRemoveFromList);
            
            // 通知监听器更新
            for (OnPeerChangeListener listener : peerChangeListeners) {
                mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
            }
        }
    }

    // ==================== 房间成员管理 ====================

    /**
     * 添加成员到房间
     */
    public void addRoomMember(String roomCode, long uid, LanPeer peer) {
        if (roomCode == null || roomCode.isEmpty()) return;
        
        // 检查是否被封禁
        java.util.Set<Long> banned = roomBanned.get(roomCode);
        if (banned != null && banned.contains(uid)) {
            Log.d(TAG, "User " + uid + " is banned from room " + roomCode);
            return;
        }
        
        java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.computeIfAbsent(
            roomCode, k -> new java.util.concurrent.ConcurrentHashMap<>());
        members.put(uid, peer);
        Log.d(TAG, "Added member " + uid + " to room " + roomCode);

        // 同时添加到peers列表，使其显示在好友列表中
        if (uid != 0 && peer.getIp() != null) {
            String localIp = NetworkUtils.getLocalIpAddress();
            // 不添加自己
            if (!peer.getIp().equals(localIp)) {
                boolean found = false;
                for (LanPeer p : peers) {
                    if (p.getUid() == uid && uid != 0) {
                        // 更新在线状态
                        p.setOnline(true);
                        p.setLastSeen(System.currentTimeMillis());
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    peer.setFriend(true);
                    peer.setOnline(true);
                    peer.setRoomCode(roomCode);
                    peers.add(peer);
                    // 通知监听器
                    for (OnPeerChangeListener listener : peerChangeListeners) {
                        mainHandler.post(() -> listener.onPeerListChanged(new ArrayList<>(peers)));
                    }
                }
            }
        }
    }

    /**
     * 从房间移除成员
     */
    public void removeRoomMember(String roomCode, long uid) {
        java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.get(roomCode);
        if (members != null) {
            members.remove(uid);
            Log.d(TAG, "Removed member " + uid + " from room " + roomCode);
        }
    }

    /**
     * 获取房间成员列表
     */
    public List<LanPeer> getRoomMembers(String roomCode) {
        java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.get(roomCode);
        if (members == null) return new ArrayList<>();
        return new ArrayList<>(members.values());
    }

    /**
     * 获取房间成员简要信息列表（用于组播传输）
     */
    public List<java.util.Map<String, Object>> getRoomMemberList(String roomCode) {
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.get(roomCode);
        if (members != null) {
            for (java.util.Map.Entry<Long, LanPeer> entry : members.entrySet()) {
                java.util.Map<String, Object> info = new java.util.HashMap<>();
                info.put("uid", entry.getKey());
                info.put("name", entry.getValue().getUserName());
                info.put("ip", entry.getValue().getIp());
                info.put("port", entry.getValue().getTcpPort());
                list.add(info);
            }
        }
        return list;
    }

    /**
     * 设置/更新房间成员列表（从组播消息中同步）
     */
    public void syncRoomMembers(String roomCode, String membersJson) {
        if (roomCode == null || membersJson == null || membersJson.isEmpty()) return;
        try {
            java.util.List<java.util.Map<String, Object>> memberList =
                gson.fromJson(membersJson, new com.google.gson.reflect.TypeToken<java.util.List<java.util.Map<String, Object>>>(){}.getType());
            if (memberList == null) return;

            java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members =
                roomMembers.computeIfAbsent(roomCode, k -> new java.util.concurrent.ConcurrentHashMap<>());
            for (java.util.Map<String, Object> info : memberList) {
                long uid = ((Number) info.get("uid")).longValue();
                String name = (String) info.get("name");
                String ip = (String) info.get("ip");
                int port = info.get("port") != null ? ((Number) info.get("port")).intValue() : 0;

                LanPeer existing = members.get(uid);
                if (existing != null) {
                    existing.setIp(ip);
                    existing.setTcpPort(port);
                    existing.setUserName(name);
                } else {
                    LanPeer peer = new LanPeer();
                    peer.setUid(uid);
                    peer.setUserName(name);
                    peer.setIp(ip);
                    peer.setTcpPort(port);
                    peer.setOnline(true);
                    peer.setFriend(true);
                    members.put(uid, peer);
                }
            }
            Log.d(TAG, "Synced " + memberList.size() + " members for room " + roomCode);
        } catch (Exception e) {
            Log.e(TAG, "Error syncing room members: " + e.getMessage());
        }
    }

    /**
     * 合并房间成员列表（心跳用）：取并集，保留lastSeen更新的那个
     */
    private void mergeRoomMembers(String roomCode, String membersJson) {
        if (roomCode == null || membersJson == null || membersJson.isEmpty()) return;
        try {
            java.util.List<java.util.Map<String, Object>> memberList =
                gson.fromJson(membersJson, new com.google.gson.reflect.TypeToken<java.util.List<java.util.Map<String, Object>>>(){}.getType());
            if (memberList == null) return;

            java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members =
                roomMembers.computeIfAbsent(roomCode, k -> new java.util.concurrent.ConcurrentHashMap<>());
            for (java.util.Map<String, Object> info : memberList) {
                long uid = ((Number) info.get("uid")).longValue();
                String name = (String) info.get("name");
                String ip = (String) info.get("ip");
                int port = info.get("port") != null ? ((Number) info.get("port")).intValue() : 0;

                LanPeer existing = members.get(uid);
                if (existing != null) {
                    // 更新已有成员：保留lastSeen更新的那个IP/port
                    existing.setUserName(name);
                    existing.setOnline(true);
                    // 如果对方的port比本地大（说明信息更新），更新IP/port
                    if (port > 0) {
                        existing.setIp(ip);
                        existing.setTcpPort(port);
                    }
                } else {
                    LanPeer peer = new LanPeer();
                    peer.setUid(uid);
                    peer.setUserName(name);
                    peer.setIp(ip);
                    peer.setTcpPort(port);
                    peer.setOnline(true);
                    peer.setLastSeen(System.currentTimeMillis());
                    peer.setFriend(true);
                    members.put(uid, peer);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error merging room members: " + e.getMessage());
        }
    }

    // 房主IP追踪（每个房间）— 保留用于显示，不再影响功能
    private final java.util.concurrent.ConcurrentHashMap<String, String> roomOwnerIps = new java.util.concurrent.ConcurrentHashMap<>();

    public void setRoomOwnerIp(String roomCode, String ownerIp) {
        if (roomCode != null && ownerIp != null) {
            roomOwnerIps.put(roomCode, ownerIp);
        }
    }

    public String getRoomOwnerIp(String roomCode) {
        return roomOwnerIps.get(roomCode);
    }

    /**
     * 发送成员列表请求（加入房间时调用）
     */
    private void sendMemberListRequest(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) return;
        try {
            ChatMessage requestMsg = new ChatMessage();
            requestMsg.setType("member_list_request");
            requestMsg.setRoomCode(roomCode);
            requestMsg.setUid(myUid);
            requestMsg.setSender(userName);
            requestMsg.setSenderIp(NetworkUtils.getLocalIpAddress());
            requestMsg.setSenderPort(tcpServerPort);
            requestMsg.setDeviceId(deviceId);
            requestMsg.setTimestamp(System.currentTimeMillis());
            sendRoomMessage(requestMsg);
            Log.d(TAG, "Sent member_list_request for room " + roomCode);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send member_list_request: " + e.getMessage());
        }
    }

    /**
     * 封禁用户（从房间移除并禁止加入）
     */
    public void banRoomMember(String roomCode, long uid) {
        roomBanned.computeIfAbsent(roomCode, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(uid);
        removeRoomMember(roomCode, uid);
        Log.d(TAG, "Banned user " + uid + " from room " + roomCode);
    }

    /**
     * 踢出房间成员（通过TCP信令通知被踢者，通过UDP广播通知房间其他人）
     */
    public void kickRoomMember(String roomCode, long targetUid, long myUid) {
        if (roomCode == null || roomCode.isEmpty()) {
            Log.w(TAG, "Room code is null, cannot kick user");
            return;
        }

        // 从成员列表移除并加入封禁列表
        banRoomMember(roomCode, targetUid);

        // 获取被踢者的peer信息
        LanPeer targetPeer = null;
        java.util.concurrent.ConcurrentHashMap<Long, LanPeer> members = roomMembers.get(roomCode);
        if (members != null) {
            targetPeer = members.get(targetUid);
        }

        // 1. 通过TCP通知被踢者
        if (targetPeer != null) {
            try {
                java.net.Socket socket = new java.net.Socket(targetPeer.getIp(), targetPeer.getTcpPort());
                java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
                out.println("{\"type\":\"KICKED\",\"roomCode\":\"" + roomCode + "\",\"uid\":" + myUid + "}");
                out.flush();
                socket.close();
                Log.d(TAG, "Sent KICKED message to user " + targetUid);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send KICKED message: " + e.getMessage());
            }
        }

        // 2. 通过UDP组播广播踢人消息
        ChatMessage kickMsg = new ChatMessage();
        kickMsg.setType("room_kick");
        kickMsg.setContent("用户 " + targetUid + " 已被踢出房间");
        kickMsg.setSender(userName);
        kickMsg.setUid(myUid);
        kickMsg.setSenderIp(NetworkUtils.getLocalIpAddress());
        kickMsg.setSenderPort(tcpServerPort);
        kickMsg.setTargetUser(String.valueOf(targetUid));
        kickMsg.setRoomCode(roomCode);
        kickMsg.setTimestamp(System.currentTimeMillis());
        sendRoomMessage(kickMsg);

        Log.d(TAG, "Kicked user " + targetUid + " from room " + roomCode);
    }

    /**
     * 获取用户名
     */
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getDeviceId() { return deviceId; }

    public String getRoomCode() { return currentRoomCode; }

    /**
     * 根据房间号获取房间名称
     */
    public String getRoomName(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) return null;
        for (LanPeer peer : peers) {
            if (peer.isRoom() && roomCode.equals(peer.getRoomCode())) {
                return peer.getRoomName();
            }
        }
        return null;
    }

    public void setRoomCode(String roomCode) {
        String oldRoomCode = this.currentRoomCode;
        this.currentRoomCode = roomCode;

        // 房间码变化时，停止旧端口监听，启动新端口监听
        if (isRunning && (oldRoomCode == null || !oldRoomCode.equals(roomCode))) {
            // 停止旧房间对应的组播监听
            if (oldRoomCode != null && !oldRoomCode.isEmpty()) {
                int oldPort = calculateMulticastPort(oldRoomCode);
                stopMulticastListener(oldPort);
            }
            // 启动新房间对应的组播监听
            if (roomCode != null && !roomCode.isEmpty()) {
                startMulticastForRoom(roomCode);
                // 将自己添加到房间成员列表
                LanPeer selfPeer = new LanPeer();
                selfPeer.setUid(myUid);
                selfPeer.setUserName(userName);
                selfPeer.setIp(NetworkUtils.getLocalIpAddress());
                selfPeer.setTcpPort(tcpServerPort);
                selfPeer.setOnline(true);
                addRoomMember(roomCode, myUid, selfPeer);
                // 启动心跳广播
                startRoomHeartbeat(roomCode);
                // 发送成员列表请求，获取最新成员列表
                sendMemberListRequest(roomCode);
            }
        }
    }

    /**
     * 离开房间：发送离开通知 + 清理本地状态
     */
    public void leaveRoom(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) return;

        // 发送离开通知
        try {
            ChatMessage leaveMsg = new ChatMessage();
            leaveMsg.setType("room_leave");
            leaveMsg.setRoomCode(roomCode);
            leaveMsg.setUid(myUid);
            leaveMsg.setSender(userName);
            leaveMsg.setSenderIp(NetworkUtils.getLocalIpAddress());
            leaveMsg.setSenderPort(tcpServerPort);
            leaveMsg.setDeviceId(deviceId);
            leaveMsg.setTimestamp(System.currentTimeMillis());
            sendRoomMessage(leaveMsg);
        } catch (Exception e) {
            Log.e(TAG, "Error sending room_leave: " + e.getMessage());
        }

        // 从本地成员列表移除自己
        removeRoomMember(roomCode, myUid);

        // 停止心跳
        stopRoomHeartbeat();

        // 停止组播监听
        int port = calculateMulticastPort(roomCode);
        stopMulticastListener(port);

        Log.d(TAG, "Left room " + roomCode);
    }

    /**
     * 启动房间心跳：每5秒广播成员列表
     */
    private void startRoomHeartbeat(String roomCode) {
        stopRoomHeartbeat(); // 先停旧的
        if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
            scheduledExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        }
        roomHeartbeatFuture = scheduledExecutor.scheduleAtFixedRate(() -> {
            if (!isRunning || roomCode == null || !roomCode.equals(currentRoomCode)) return;
            try {
                ChatMessage heartbeat = new ChatMessage();
                heartbeat.setType("room_heartbeat");
                heartbeat.setRoomCode(roomCode);
                heartbeat.setRoomMembers(gson.toJson(getRoomMemberList(roomCode)));
                heartbeat.setUid(myUid);
                heartbeat.setSender(userName);
                heartbeat.setSenderIp(NetworkUtils.getLocalIpAddress());
                heartbeat.setSenderPort(tcpServerPort);
                heartbeat.setDeviceId(deviceId);
                heartbeat.setTimestamp(System.currentTimeMillis());
                sendRoomMessage(heartbeat);
            } catch (Exception e) {
                Log.e(TAG, "Error sending room heartbeat: " + e.getMessage());
            }
        }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
        Log.d(TAG, "Started room heartbeat for room " + roomCode);
    }

    /**
     * 停止房间心跳
     */
    private void stopRoomHeartbeat() {
        if (roomHeartbeatFuture != null && !roomHeartbeatFuture.isDone()) {
            roomHeartbeatFuture.cancel(false);
            roomHeartbeatFuture = null;
        }
    }

    public boolean isRunning() { return isRunning; }

    public boolean isDiscoverable() { return discoverable; }
    public void setDiscoverable(boolean discoverable) {
        this.discoverable = discoverable;
        // 更新所有 peers 的 discoverable 状态
        for (LanPeer peer : peers) {
            peer.setDiscoverable(discoverable);
        }
    }
    
    public int getTcpServerPort() { return tcpServerPort; }
    
    /**
     * 添加监听器
     */
    public void addPeerChangeListener(OnPeerChangeListener listener) {
        if (listener != null && !peerChangeListeners.contains(listener)) {
            peerChangeListeners.add(listener);
        }
    }
    
    /**
     * 移除监听器
     */
    public void removePeerChangeListener(OnPeerChangeListener listener) {
        peerChangeListeners.remove(listener);
    }

    /**
     * 设置全局房间邀请监听器
     */
    public void setRoomInviteListener(RoomInviteListener listener) {
        this.roomInviteListener = listener;
    }
    
    /**
     * 广播消息模型 - 用于UDP广播/组播发现
     * 统一JSON格式，所有字段可选，按消息类型使用
     */
    public static class BroadcastMessage {
        @SerializedName("type")
        private String type;           // 消息类型: presence, connect_request, room_create, chat_invite, manual_add_notify

        @SerializedName("uid")
        private long uid;              // 用户唯一标识（逻辑身份）

        @SerializedName("peer_type")
        private String peerType;       // 节点类型: friend(好友), room(房间), 用于区分同设备的不同角色

        @SerializedName("sender")
        private String sender;         // 发送者名字

        @SerializedName("device_id")
        private String deviceId;       // 设备唯一ID，用于自过滤

        @SerializedName("sender_ip")
        private String senderIp;       // 发送者IP

        @SerializedName("sender_port")
        private int senderPort;        // 发送者TCP端口

        @SerializedName("backup_port")
        private int backupPort;        // 备用端口（用于验证/加好友回连）

        @SerializedName("discoverable")
        private boolean discoverable = true;  // 是否可被发现

        @SerializedName("is_room_owner")
        private boolean isRoomOwner;   // 是否为房主

        @SerializedName("room_name")
        private String roomName;       // 房间名字

        @SerializedName("room_ip")
        private String roomIp;         // 房间IP

        @SerializedName("room_port")
        private int roomPort;          // 房间端口

        @SerializedName("room_code")
        private String roomCode;       // 房间号（公开房间自动添加到好友列表）

        @SerializedName("content")
        private String content;        // 消息内容

        @SerializedName("timestamp")
        private long timestamp;

        @SerializedName("target_ip")
        private String targetIp;       // 连接请求的目标IP

        @SerializedName("original_sender")
        private String originalSender; // 中继广播的原始发送者

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public long getUid() { return uid; }
        public void setUid(long uid) { this.uid = uid; }

        public String getPeerType() { return peerType; }
        public void setPeerType(String peerType) { this.peerType = peerType; }

        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

        public String getSenderIp() { return senderIp; }
        public void setSenderIp(String senderIp) { this.senderIp = senderIp; }

        public int getSenderPort() { return senderPort; }
        public void setSenderPort(int senderPort) { this.senderPort = senderPort; }

        public int getBackupPort() { return backupPort; }
        public void setBackupPort(int backupPort) { this.backupPort = backupPort; }

        public boolean isDiscoverable() { return discoverable; }
        public void setDiscoverable(boolean discoverable) { this.discoverable = discoverable; }

        public boolean isRoomOwner() { return isRoomOwner; }
        public void setRoomOwner(boolean roomOwner) { isRoomOwner = roomOwner; }

        public String getRoomName() { return roomName; }
        public void setRoomName(String roomName) { this.roomName = roomName; }

        public String getRoomIp() { return roomIp; }
        public void setRoomIp(String roomIp) { this.roomIp = roomIp; }

        public int getRoomPort() { return roomPort; }
        public void setRoomPort(int roomPort) { this.roomPort = roomPort; }

        public String getRoomCode() { return roomCode; }
        public void setRoomCode(String roomCode) { this.roomCode = roomCode; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        // 添加缺失的字段定义
        @SerializedName("message_id")
        private String messageId;       // 消息唯一ID，用于送达确认
        
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public String getTargetIp() { return targetIp; }
        public void setTargetIp(String targetIp) { this.targetIp = targetIp; }

        public String getOriginalSender() { return originalSender; }
        public void setOriginalSender(String originalSender) { this.originalSender = originalSender; }
    }
    
    /**
     * 聊天消息模型 - 用于TCP聊天和组播消息
     * 统一JSON格式，所有字段可选，按消息类型使用
     */
    public static class ChatMessage {
        // 消息范围枚举：好友私聊 vs 房间群聊
        public enum MsgScope {
            PRIVATE,  // 好友私聊（TCP单播）
            ROOM      // 房间群聊（UDP组播）
        }

        @SerializedName("type")  // text, image, file, emoji, presence, room_create, room_invite, handshake
        private String type;

        @SerializedName("scope")
        private MsgScope scope;     // 消息范围：PRIVATE 或 ROOM

        @SerializedName("uid")
        private long uid;              // 用户唯一标识（逻辑身份）

        @SerializedName("peer_type")
        private String peerType;       // 节点类型: friend(好友), room(房间)

        @SerializedName("sender")
        private String sender;

        @SerializedName("device_id")
        private String deviceId;       // 设备唯一ID，用于自过滤

        @SerializedName("sender_ip")
        private String senderIp;

        @SerializedName("sender_port")
        private int senderPort;

        @SerializedName("backup_port")
        private int backupPort;        // 备用端口

        @SerializedName("discoverable")
        private boolean discoverable = true;  // 是否可被发现

        @SerializedName("is_room_owner")
        private boolean isRoomOwner;   // 是否为房主

        @SerializedName("room_name")
        private String roomName;       // 房间名字

        @SerializedName("room_ip")
        private String roomIp;         // 房间IP

        @SerializedName("room_port")
        private int roomPort;          // 房间端口

        @SerializedName("room_code")
        private String roomCode;       // 房间号

        @SerializedName("content")
        private String content;

        // transient: 标记是否已由handleTcpClient保存到数据库，防止addReceivedMessage重复保存
        private transient boolean savedToDb = false;

        @SerializedName("message_id")
        private String messageId;      // 消息唯一ID，用于送达确认

        @SerializedName("timestamp")
        private long timestamp;

        // 序列号（用于NACK丢包恢复）
        @SerializedName("seq")
        private int seq;               // 组播消息序列号

        // 文件相关
        @SerializedName("file_name")
        private String fileName;

        @SerializedName("file_size")
        private long fileSize;

        @SerializedName("file_data")
        private String fileData;  // Base64编码

        @SerializedName("file_path")
        private String filePath;  // 文件本地路径

        // 目标用户（用于定向消息，如房主移交）
        @SerializedName("target_user")
        private String targetUser;

        @SerializedName("target_ip")
        private String targetIp;

        @SerializedName("target_port")
        private int targetPort;

        // 房间成员信息（由房主在组播消息中携带）
        @SerializedName("room_owner_ip")
        private String roomOwnerIp;     // 房主IP

        @SerializedName("room_members")
        private String roomMembers;     // JSON格式的房间成员列表

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public MsgScope getScope() { return scope; }
        public void setScope(MsgScope scope) { this.scope = scope; }

        public long getUid() { return uid; }
        public void setUid(long uid) { this.uid = uid; }

        public String getPeerType() { return peerType; }
        public void setPeerType(String peerType) { this.peerType = peerType; }

        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

        public String getSenderIp() { return senderIp; }
        public void setSenderIp(String senderIp) { this.senderIp = senderIp; }

        public int getSenderPort() { return senderPort; }
        public void setSenderPort(int senderPort) { this.senderPort = senderPort; }

        public int getBackupPort() { return backupPort; }
        public void setBackupPort(int backupPort) { this.backupPort = backupPort; }

        public boolean isDiscoverable() { return discoverable; }
        public void setDiscoverable(boolean discoverable) { this.discoverable = discoverable; }

        public boolean isRoomOwner() { return isRoomOwner; }
        public void setRoomOwner(boolean roomOwner) { this.isRoomOwner = roomOwner; }

        public String getRoomName() { return roomName; }
        public void setRoomName(String roomName) { this.roomName = roomName; }

        public String getRoomIp() { return roomIp; }
        public void setRoomIp(String roomIp) { this.roomIp = roomIp; }

        public int getRoomPort() { return roomPort; }
        public void setRoomPort(int roomPort) { this.roomPort = roomPort; }

        public String getRoomCode() { return roomCode; }
        public void setRoomCode(String roomCode) { this.roomCode = roomCode; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public boolean isSavedToDb() { return savedToDb; }
        public void setSavedToDb(boolean savedToDb) { this.savedToDb = savedToDb; }

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }

        public String getFileData() { return fileData; }
        public void setFileData(String fileData) { this.fileData = fileData; }

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }

        public String getTargetUser() { return targetUser; }
        public void setTargetUser(String targetUser) { this.targetUser = targetUser; }

        public String getTargetIp() { return targetIp; }
        public void setTargetIp(String targetIp) { this.targetIp = targetIp; }

        public int getTargetPort() { return targetPort; }
        public void setTargetPort(int targetPort) { this.targetPort = targetPort; }

        public String getRoomOwnerIp() { return roomOwnerIp; }
        public void setRoomOwnerIp(String roomOwnerIp) { this.roomOwnerIp = roomOwnerIp; }

        public String getRoomMembers() { return roomMembers; }
        public void setRoomMembers(String roomMembers) { this.roomMembers = roomMembers; }
    }
    
    /**
     * 好友/对等设备模型
     */
    public static class LanPeer {
        private long uid;           // 用户唯一标识（逻辑身份），持久化
        private String userName;
        private String ip;
        private int tcpPort;
        private boolean online;
        private boolean connecting;  // 正在连接中，防止并发连接
        private long lastSeen;
        private boolean isFriend;  // 是否已添加为好友
        private boolean isRoom;    // 是否为房间
        private String roomCode;   // 房间号（用于加入房间）
        private String roomName;   // 房间名称（用于显示）
        private String ownerIp;    // 房主IP地址（仅房间有效）

        // TCP连接相关
        private Socket socket;
        private PrintWriter writer;

        /**
         * 连接到该节点（内部方法）
         */
        private boolean connect() {
            if (ip == null || ip.isEmpty() || tcpPort <= 0) {
                Log.e(TAG, "connect: invalid peer IP or port");
                return false;
            }
            try {
                String localIp = NetworkUtils.getLocalIpAddress();
                if (localIp == null || localIp.isEmpty()) {
                    Log.e(TAG, "Local IP address not available");
                    return false;
                }
                
                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(ip, tcpPort), 3000);
                socket.setSoTimeout(5000);
                socket.setKeepAlive(true);
                writer = new PrintWriter(socket.getOutputStream(), true);
                online = true;
                Log.d(TAG, "Connected to " + ip + ":" + tcpPort);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "connect failed to " + ip + ":" + tcpPort + " - " + e.getMessage());
                socket = null;
                writer = null;
                return false;
            }
        }

        /**
         * 发送文件传输请求
         */
        public boolean sendFileRequest(String fileName, long fileSize) {
            if (socket == null || socket.isClosed()) {
                if (!connect()) return false;
            }
            
            try {
                if (writer == null) {
                    writer = new PrintWriter(socket.getOutputStream(), true);
                }
                writer.println("FILE_REQUEST:" + fileName + ":" + fileSize);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "文件请求失败: " + e.getMessage());
                disconnect();
                return false;
            }
        }

        /**
         * 发送原始文本行（用于文件数据传输等）
         */
        public boolean sendRawLine(String line) {
            if (socket == null || socket.isClosed()) {
                if (!connect()) return false;
            }
            try {
                if (writer == null) {
                    writer = new PrintWriter(socket.getOutputStream(), true);
                }
                writer.println(line);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "发送原始行失败: " + e.getMessage());
                return false;
            }
        }

        public long getUid() { return uid; }
        public void setUid(long uid) { this.uid = uid; }

        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }

        public int getTcpPort() { return tcpPort; }
        public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }

        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }

        public boolean isConnecting() { return connecting; }
        public void setConnecting(boolean connecting) { this.connecting = connecting; }

        public long getLastSeen() { return lastSeen; }
        public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
        
        public boolean isFriend() { return isFriend; }
        public void setFriend(boolean friend) { isFriend = friend; }
        public boolean isRoom() { return isRoom; }
        public void setRoom(boolean room) { isRoom = room; }
        public String getRoomCode() { return roomCode; }
        public void setRoomCode(String roomCode) { this.roomCode = roomCode; }
        public String getRoomName() { return roomName; }
        public void setRoomName(String roomName) { this.roomName = roomName; }
        public String getOwnerIp() { return ownerIp; }
        public void setOwnerIp(String ownerIp) { this.ownerIp = ownerIp; }

        private boolean discoverable = true;  // 是否可被发现
        
        public boolean isDiscoverable() { return discoverable; }
        public void setDiscoverable(boolean discoverable) { this.discoverable = discoverable; }

        public Socket getSocket() { return socket; }
        public void setSocket(Socket socket) { this.socket = socket; }

        // 当前挂在该 peer 上负责读的 socket：保证一条连接只有一个读线程
        private volatile Socket readerSocket;

        public Socket getReaderSocket() { return readerSocket; }
        public void setReaderSocket(Socket readerSocket) { this.readerSocket = readerSocket; }
        
        public PrintWriter getWriter() { return writer; }
        public void setWriter(PrintWriter writer) { this.writer = writer; }
        
        /**
         * 发送消息
         */
        public synchronized boolean sendMessage(ChatMessage message) {
            // 更严格的连接状态检查
            if (writer != null && socket != null && !socket.isClosed() && online) {
                try {
                    String json = new Gson().toJson(message);
                    writer.println(json);
                    writer.flush();
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "发送消息失败: " + e.getMessage());
                    // 发送失败时清理连接状态
                    disconnect();
                    return false;
                }
            }
            return false;
        }
        
        /**
         * 断开连接
         */
        public void disconnect() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // 忽略
            }
            socket = null;
            writer = null;
            online = false;
        }
        
        /**
         * 获取显示名称
         */
        public String getDisplayName() {
            if (userName != null && !userName.isEmpty()) {
                return userName;
            }
            return ip != null ? ip : "未知设备";
        }
        
        /**
         * 获取显示状态
         */
        public String getStatusText() {
            String status;
            if (online) {
                status = "在线";
            } else {
                long ago = System.currentTimeMillis() - lastSeen;
                if (ago < 60000) {
                    status = "刚刚";
                } else if (ago < 3600000) {
                    status = (ago / 60000) + "分钟前";
                } else {
                    status = "离线";
                }
            }
            
            // 陌生人标记
            if (!isFriend) {
                status = "陌生人 · " + status;
            }
            return status;
        }
    }
    

    /**
     * 检查消息是否已处理过（用于去重）
     * @return true 表示是新消息，false 表示是重复消息
     */
    private boolean isNewMessage(ChatMessage message) {
        if (message == null) return false;
        
        // 生成消息唯一 key：时间戳 + 发送者 IP+ 端口 + 内容哈希
        String key = message.getTimestamp() + "_" + 
                     message.getSenderIp() + "_" + 
                     message.getSenderPort() + "_" + 
                     (message.getContent() != null ? message.getContent().hashCode() : "null");
        
        synchronized (processedMessageCache) {
            if (processedMessageCache.contains(key)) {
                return false; // 重复消息
            }
            processedMessageCache.add(key);
            return true; // 新消息
        }
    }
    /**
     * 好友列表变化监听器
     */
    public interface OnPeerChangeListener {
        void onPeerListChanged(List<LanPeer> peers);
        void onMessageReceived(LanPeer peer, ChatMessage message);
        void onFileRequestReceived(LanPeer peer, String fileName, long fileSize, String filePath);
    }
}
