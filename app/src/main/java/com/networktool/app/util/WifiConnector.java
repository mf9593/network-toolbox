package com.networktool.app.util;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
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
 * Android 10+ 使用系统 WifiNetworkSpecifier + ConnectivityManager.requestNetwork，
 * 会弹出系统连接确认窗口；Android 9 及以下使用 WifiManager 旧 API。
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
    private Callback callback;
    private String connectingSsid;
    private boolean connecting;
    private boolean connected;

    private final Runnable timeoutRunnable = () -> {
        if (connecting) {
            fail("连接超时，请检查密码或稍后重试");
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private void connectModern(String ssid, int type, String password) {
        try {
            WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (type == SECURITY_WPA3) {
                builder.setWpa3Passphrase(password);
            } else if (type == SECURITY_WPA2) {
                builder.setWpa2Passphrase(password);
            }

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(builder.build())
                    .build();

            activeCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (connected) return;
                    mainHandler.removeCallbacks(timeoutRunnable);
                    connecting = false;
                    connected = true;
                    Callback cb = callback;
                    if (cb != null) {
                        mainHandler.post(() -> cb.onConnected(ssid));
                    }
                }

                @Override
                public void onUnavailable() {
                    mainHandler.post(() -> fail("连接失败或已取消，请检查密码"));
                }

                @Override
                public void onLost(Network network) {
                    if (connecting) {
                        mainHandler.post(() -> fail("连接失败，网络不可用"));
                    } else if (connected) {
                        connected = false;
                        releaseActiveRequest();
                    }
                }
            };

            if (connectivityManager == null) {
                fail("当前设备不支持网络连接管理");
                return;
            }
            connectivityManager.requestNetwork(request, activeCallback);
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.postDelayed(timeoutRunnable, 60000);
        } catch (SecurityException e) {
            fail("缺少 WiFi 连接权限：" + safeMessage(e)
                    + "。请检查“附近设备/位置信息”权限和定位开关");
        } catch (Exception e) {
            fail("发起连接失败：" + safeMessage(e));
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
        if (activeCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(activeCallback);
            } catch (Exception ignored) {
            }
        }
        activeCallback = null;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
