package com.networktool.app.util;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

/**
 * WiFi 连接器
 *
 * Android 10+ 使用 WifiNetworkSuggestion —— 把网络交给系统真正连接，
 * 可正常上网、获取 IP、成为默认网络（状态栏显示已连接 WiFi）。
 * 不使用 WifiNetworkSpecifier：它只建立「临时/点对点」网络，系统会提示
 * 「临时 WLAN 网络」，且无公网路由、不成为默认网络。
 *
 * 首次连接系统会弹通知/弹窗，需用户点「连接」确认；之后系统自动连接。
 * Android 9 及以下使用 WifiManager 旧 API。
 */
public class WifiConnector {

    public static final int SECURITY_OPEN = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_WPA2 = 2;
    public static final int SECURITY_WPA3 = 3;
    public static final int SECURITY_OWE = 4;
    public static final int SECURITY_UNKNOWN = 5;

    public interface Callback {
        void onConnecting(String ssid);

        void onConnected(String ssid);

        void onFailed(String ssid, String message);
    }

    private static WifiConnector instance;

    private final Context appContext;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ConnectivityManager.NetworkCallback activeCallback;
    private BroadcastReceiver postConnectionReceiver;
    private Runnable connectionPollRunnable;
    private Callback callback;
    private String connectingSsid;
    private boolean connecting;
    private boolean connected;

    /** Suggestion 连接由系统驱动、非即时，等待时间放宽到 30 秒 */
    private static final long CONNECT_TIMEOUT_MS = 30000;

    private final Runnable timeoutRunnable = () -> {
        if (connecting) {
            fail("连接超时，可能是密码错误或系统未弹出确认。请到系统 WiFi 设置中手动连接，"
                    + "或检查「Wi-Fi 控制」权限是否已开启");
        }
    };

    private WifiConnector(Context context) {
        appContext = context.getApplicationContext();
        wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public static synchronized WifiConnector getInstance(Context context) {
        if (instance == null) {
            instance = new WifiConnector(context);
        }
        return instance;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 根据 ScanResult.capabilities 判断加密类型
     */
    public static int detectSecurityType(String capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return SECURITY_UNKNOWN;
        }
        String caps = capabilities.toUpperCase(Locale.ROOT);
        if (caps.contains("EAP")) {
            // WPA2/WPA3-Enterprise 需要账号认证，不在应用内处理
            return SECURITY_UNKNOWN;
        }
        if (caps.contains("OWE")) {
            return SECURITY_OWE;
        }
        if (caps.contains("SAE")) {
            return SECURITY_WPA3;
        }
        if (caps.contains("WEP")) {
            return SECURITY_WEP;
        }
        if (caps.contains("WPA") || caps.contains("PSK")) {
            return SECURITY_WPA2;
        }
        if (caps.contains("ESS") || caps.contains("OPEN")) {
            return SECURITY_OPEN;
        }
        return SECURITY_UNKNOWN;
    }

    public static String securityLabel(int type) {
        switch (type) {
            case SECURITY_OPEN:
                return "开放网络（无需密码）";
            case SECURITY_WEP:
                return "WEP 加密";
            case SECURITY_WPA2:
                return "WPA/WPA2 加密";
            case SECURITY_WPA3:
                return "WPA3 加密";
            case SECURITY_OWE:
                return "OWE 增强开放网络";
            default:
                return "未知加密方式";
        }
    }

    public static boolean requiresPassword(int type) {
        return type == SECURITY_WEP || type == SECURITY_WPA2 || type == SECURITY_WPA3;
    }

    public static boolean isSupported(int type) {
        if (type == SECURITY_OPEN || type == SECURITY_WPA2) {
            return true;
        }
        if (type == SECURITY_WPA3) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        }
        if (type == SECURITY_WEP) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
        }
        return false;
    }

    /**
     * 校验密码格式，返回 null 表示通过
     */
    public static String validatePassword(int type, String password) {
        if (!requiresPassword(type)) {
            return null;
        }
        if (password == null) {
            password = "";
        }
        int length = password.length();
        if (type == SECURITY_WEP) {
            boolean ascii = length == 5 || length == 13;
            boolean hex = (length == 10 || length == 26) && isHex(password);
            return (ascii || hex) ? null : "WEP 密码需为 5/13 位字符或 10/26 位十六进制";
        }
        boolean ascii = length >= 8 && length <= 63;
        boolean hex = length == 64 && isHex(password);
        return (ascii || hex) ? null : "WiFi 密码需为 8-63 位字符或 64 位十六进制";
    }

    private static boolean isHex(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean upper = c >= 'A' && c <= 'F';
            boolean lower = c >= 'a' && c <= 'f';
            if (!digit && !upper && !lower) return false;
        }
        return true;
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(appContext, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 返回缺失的运行时权限说明。Android 13+ 连接附近 WiFi 需要
     * “附近设备”权限，同时本应用扫描 WiFi 仍需要位置权限。
     */
    private String missingPermissionMessage() {
        if (!hasPermission(Manifest.permission.CHANGE_WIFI_STATE)
                || !hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
            return "缺少 WiFi 状态权限，请重新安装或在设置中检查应用权限";
        }
        if (!hasPermission(Manifest.permission.CHANGE_NETWORK_STATE)) {
            return "缺少 CHANGE_NETWORK_STATE 权限，请安装最新版本 APK";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            return "缺少“附近设备”权限，请在设置中允许访问附近 WiFi";
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "缺少“位置信息”权限，请在权限管理中允许位置信息";
        }
        return null;
    }

    /**
     * 系统定位开关是否开启。部分 Android 版本即使已授权，
     * 关闭定位开关后 Wi-Fi 扫描/连接仍会失败。
     */
    public boolean isLocationEnabled() {
        try {
            LocationManager manager = (LocationManager) appContext
                    .getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 发起连接。Android 10+ 会弹出系统确认窗口。
     */
    public void connect(String ssid, String capabilities, String password, Callback callback) {
        this.callback = callback;
        this.connectingSsid = ssid;
        this.connecting = true;
        this.connected = false;
        releaseActiveRequest();

        int type = detectSecurityType(capabilities);
        if (!isSupported(type)) {
            if (type == SECURITY_WEP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fail("Android 10+ 不支持应用内连接 WEP 网络，请使用系统 WiFi 设置");
            } else {
                fail("该网络加密方式暂不支持应用内连接，请使用系统 WiFi 设置");
            }
            return;
        }
        if (wifiManager == null) {
            fail("当前设备不支持 WiFi 管理");
            return;
        }

        String permissionError = missingPermissionMessage();
        if (permissionError != null) {
            fail(permissionError);
            return;
        }

        if (callback != null) {
            callback.onConnecting(ssid);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ssid, type, password);
        } else {
            connectLegacy(ssid, type, password);
        }
    }

    /**
     * Android 10+ 连接：使用 WifiNetworkSuggestion。
     *
     * 说明：不能用 WifiNetworkSpecifier——它只会建立一个「临时/点对点」本地网络，系统会提示
     * 「临时 WLAN 网络」，且默认不带互联网路由、拿不到路由器分配的 IP、也不会成为默认网络
     *（导致仍在用移动数据、状态栏不显示已连接）。
     *
     * WifiNetworkSuggestion 把网络交给系统真正连接：可正常上网、获取 IP、成为默认网络，
     * 状态栏会显示已连接 WiFi。首次连接系统会弹通知/弹窗，需用户点「连接」确认；
     * 之后系统会在该网络在范围时自动连接。
     *
     * 连接成功的判定：监听默认网络回调，确认默认网络是 WiFi 且 SSID 匹配。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private void connectModern(String ssid, int type, String password) {
        try {
            if (wifiManager == null) {
                fail("当前设备不支持 WiFi 管理");
                return;
            }
            if (connectivityManager == null) {
                fail("当前设备不支持网络连接管理");
                return;
            }

            // ① 添加建议（先删后加，处理 DUPLICATE）
            int status = persistSuggestion(ssid, type, password);
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                fail("系统拒绝添加 WiFi 建议（状态码 " + status + "）。"
                        + "部分机型需在「设置→应用→特殊权限→Wi-Fi 控制」中允许本应用");
                return;
            }

            // ② 若当前已连上目标 SSID（如系统已自动连接），直接判定成功
            if (isCurrentlyConnectedTo(ssid)) {
                connecting = false;
                connected = true;
                mainHandler.removeCallbacks(timeoutRunnable);
                notifyConnected(ssid);
                return;
            }

            // ③ POST_CONNECTION：系统连到建议网络时的广播，作为快速确认
            registerPostConnectionReceiver(ssid);

            // ④ 轮询 getConnectionInfo().getSSID() 判定真连上。
            //    不能用 registerDefaultNetworkCallback——Suggestion 连的 WiFi 未通过互联网
            //    校验前不会成为默认网络，回调不触发会导致误报超时。
            final long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
            connectionPollRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!connecting) return;
                    if (isCurrentlyConnectedTo(ssid)) {
                        connecting = false;
                        connected = true;
                        notifyConnected(ssid);
                        return;
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        fail("连接超时。系统已保存该网络，可能需要你在通知栏点「连接」确认，"
                                + "或到系统 WiFi 设置中手动连接。若一直失败，请检查"
                                + "「设置→应用→特殊权限→Wi-Fi 控制」是否允许本应用");
                        return;
                    }
                    mainHandler.postDelayed(this, 1000);
                }
            };
            mainHandler.postDelayed(connectionPollRunnable, 1000);
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS);
        } catch (SecurityException e) {
            fail("缺少 WiFi 连接权限：" + safeMessage(e)
                    + "。请检查「附近设备/位置信息」权限和定位开关");
        } catch (Exception e) {
            fail("发起连接失败：" + safeMessage(e));
        }
    }

    /**
     * 读取当前 WiFi 连接的 SSID，判断是否已连到目标网络。
     * 这是最可靠的「是否真连上」信号，比默认网络回调准确。
     */
    @SuppressLint("MissingPermission")
    private boolean isCurrentlyConnectedTo(String ssid) {
        if (wifiManager == null || ssid == null) return false;
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return false;
            String current = info.getSSID();
            if (current != null && current.startsWith("\"") && current.endsWith("\"")
                    && current.length() >= 2) {
                current = current.substring(1, current.length() - 1);
            }
            return ssid.equals(current);
        } catch (Exception e) {
            return false;
        }
    }

    /** 连接成功统一回调 */
    private void notifyConnected(String ssid) {
        mainHandler.removeCallbacks(timeoutRunnable);
        if (connectionPollRunnable != null) {
            mainHandler.removeCallbacks(connectionPollRunnable);
            connectionPollRunnable = null;
        }
        Callback cb = callback;
        if (cb != null) {
            mainHandler.post(() -> cb.onConnected(ssid));
        }
    }

    /**
     * 把网络以 WifiNetworkSuggestion 形式「存底」，交给系统记住并择机自动连接。
     * 改过密码需先 remove 再 add，否则返回 DUPLICATE 不会更新。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private int persistSuggestion(String ssid, int type, String password) {
        try {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsAppInteractionRequired(false);
            if (type == SECURITY_WPA3) {
                builder.setWpa3Passphrase(password);
            } else if (type == SECURITY_WPA2) {
                builder.setWpa2Passphrase(password);
            }
            WifiNetworkSuggestion suggestion = builder.build();

            // 先移除该 App 的全部旧建议，避免叠加/过期配置
            List<WifiNetworkSuggestion> existing = wifiManager.getNetworkSuggestions();
            if (existing != null && !existing.isEmpty()) {
                wifiManager.removeNetworkSuggestions(existing);
            }

            java.util.List<WifiNetworkSuggestion> single =
                    java.util.Collections.singletonList(suggestion);
            int status = wifiManager.addNetworkSuggestions(single);
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                wifiManager.removeNetworkSuggestions(single);
                status = wifiManager.addNetworkSuggestions(single);
            }
            return status;
        } catch (SecurityException se) {
            // 权限被收回（用户在系统里拒绝过），返回非 0 表示失败
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 监听系统「已连到建议网络」的广播，作为连接成功的二次确认。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private void registerPostConnectionReceiver(String ssid) {
        try {
            if (postConnectionReceiver != null) return;
            final String target = ssid;
            postConnectionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    WifiInfo info = intent.getParcelableExtra(
                            WifiManager.EXTRA_WIFI_INFO);
                    String connectedSsid = info != null ? info.getSSID() : null;
                    if (connectedSsid != null && connectedSsid.startsWith("\"")
                            && connectedSsid.endsWith("\"") && connectedSsid.length() >= 2) {
                        connectedSsid = connectedSsid.substring(1, connectedSsid.length() - 1);
                    }
                    if (target.equals(connectedSsid)) {
                        Callback cb = callback;
                        if (cb != null) {
                            mainHandler.post(() -> cb.onConnected(target));
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter(
                    WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
            appContext.registerReceiver(postConnectionReceiver, filter);
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void connectLegacy(String ssid, int type, String password) {
        new Thread(() -> {
            try {
                WifiConfiguration config = buildLegacyConfig(ssid, type, password);
                int networkId = findConfiguredNetwork(ssid);
                if (networkId != -1) {
                    config.networkId = networkId;
                    wifiManager.updateNetwork(config);
                } else {
                    networkId = wifiManager.addNetwork(config);
                }
                if (networkId == -1) {
                    fail("无法添加 WiFi 配置，请检查权限或手动连接");
                    return;
                }

                wifiManager.disconnect();
                boolean enabled = wifiManager.enableNetwork(networkId, true);
                wifiManager.reconnect();
                if (!enabled) {
                    fail("系统拒绝连接该网络，请手动连接");
                    return;
                }

                mainHandler.postDelayed(() -> checkLegacyConnection(ssid), 8000);
            } catch (SecurityException e) {
                fail("缺少 WiFi 连接权限，请手动连接");
            } catch (Exception e) {
                fail("连接失败：" + safeMessage(e));
            }
        }, "Wifi-Connect").start();
    }

    @SuppressWarnings("deprecation")
    private WifiConfiguration buildLegacyConfig(String ssid, int type, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        config.hiddenSSID = false;

        if (type == SECURITY_OPEN) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else if (type == SECURITY_WEP) {
            boolean hexKey = (password.length() == 10 || password.length() == 26)
                    && isHex(password);
            config.wepKeys[0] = hexKey ? password : ("\"" + password + "\"");
            config.wepTxKeyIndex = 0;
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        } else {
            boolean hexKey = password.length() == 64 && isHex(password);
            config.preSharedKey = hexKey ? password : ("\"" + password + "\"");
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }
        return config;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private int findConfiguredNetwork(String ssid) {
        try {
            List<WifiConfiguration> configurations = wifiManager.getConfiguredNetworks();
            if (configurations == null) return -1;
            String quoted = "\"" + ssid + "\"";
            for (WifiConfiguration configuration : configurations) {
                if (configuration != null && quoted.equals(configuration.SSID)) {
                    return configuration.networkId;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private void checkLegacyConnection(String ssid) {
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            String current = info == null ? null : info.getSSID();
            if (current != null && current.startsWith("\"") && current.endsWith("\"")
                    && current.length() >= 2) {
                current = current.substring(1, current.length() - 1);
            }
            if (ssid != null && ssid.equals(current)) {
                connecting = false;
                connected = true;
                Callback cb = callback;
                if (cb != null) {
                    mainHandler.post(() -> cb.onConnected(ssid));
                }
            } else {
                fail("连接超时，请检查密码或使用系统 WiFi 设置");
            }
        } catch (Exception e) {
            fail("无法确认连接状态，请使用系统 WiFi 设置");
        }
    }

    private void fail(String message) {
        String ssid = connectingSsid;
        connecting = false;
        connected = false;
        releaseActiveRequest();
        Callback cb = callback;
        if (cb != null) {
            mainHandler.post(() -> cb.onFailed(ssid, message));
        }
    }

    private void releaseActiveRequest() {
        mainHandler.removeCallbacks(timeoutRunnable);
        if (connectionPollRunnable != null) {
            mainHandler.removeCallbacks(connectionPollRunnable);
            connectionPollRunnable = null;
        }
        if (activeCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(activeCallback);
            } catch (Exception ignored) {
            }
        }
        activeCallback = null;

        // 解绑进程网络，避免断开后 App 流量仍被钉在已失效的 WiFi 上
        if (connectivityManager != null) {
            try {
                connectivityManager.bindProcessToNetwork(null);
            } catch (Exception ignored) {
            }
        }

        // 注销建议连接广播
        if (postConnectionReceiver != null) {
            try {
                appContext.unregisterReceiver(postConnectionReceiver);
            } catch (Exception ignored) {
            }
            postConnectionReceiver = null;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
