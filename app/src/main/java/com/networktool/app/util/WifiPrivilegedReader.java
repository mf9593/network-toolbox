package com.networktool.app.util;

import android.content.AttributionSource;
import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.annotation.SuppressLint;

import com.networktool.app.model.SavedNetworkModel;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 读取「已保存的 WiFi 网络」列表，<b>带明文密码</b>。
 *
 * <p>实现移植自 Shizuku-Next 的 {@code WifiPasswords.kt}：把系统服务 {@code IWifiManager} 用
 * {@link ShizukuBinderWrapper} 包一层再调用，服务的调用方就成了 shell(uid 2000)。
 * Android 11 起 shell 被赋予了「读取已保存网络」所需的权限，于是
 * {@code getPrivilegedConfiguredNetworks} 会把密钥原样返回，而不像公开接口那样返回 {@code "*"}。</p>
 *
 * <p>接口本身不在 SDK 里，所以按名字反射查找 {@code IWifiManager$Stub}、按名字查找方法，
 * 并用 {@link HiddenApiBypass} 绕过隐藏 API 限制；方法签名有两种形态——Android 13 起多了一个
 * 携带 attribution source 的 {@code Bundle}，早些版本只有两个参数，按设备声明的形状依次尝试。</p>
 *
 * <p>只做读取与删除，不添加、不连接：读走 {@code getPrivilegedConfiguredNetworks}，
 * 删除走 {@code IWifiManager.forget}，两者都在同一条 shell 身份的 binder 通道上。</p>
 */
public final class WifiPrivilegedReader {

    /** 系统服务接口的隐藏类名 */
    private static final String IWIFI_MANAGER_STUB = "android.net.wifi.IWifiManager$Stub";

    /** 读已保存网络（含密码）的隐藏方法名 */
    private static final String PRIVILEGED_NETWORKS = "getPrivilegedConfiguredNetworks";

    /** Android 13+ 传入的 attribution key，拼写沿用 AOSP */
    private static final String ATTRIBUTION_KEY = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE";

    /** 权限是借 shell 的，所以包名、attribution tag 都要写成 shell 的 */
    private static final String SHELL_PACKAGE = "com.android.shell";

    /** 读取结果的几种状态，页面据此给出不同提示 */
    public enum Status {
        /** 读取成功 */
        NETWORKS,
        /** 没有 Shizuku 服务，没有可以借用的 shell 身份 */
        NO_SERVER,
        /** Android 8 及以下，隐藏接口根本够不着 */
        UNSUPPORTED,
        /** 有 Shizuku，但调用没成功 */
        FAILED
    }

    /** 一次读取的结果：状态 + 网络列表 + 失败原因 */
    public static final class Result {
        public final Status status;
        public final List<SavedNetworkModel> networks;
        public final String error;

        public Result(Status status, List<SavedNetworkModel> networks, String error) {
            this.status = status;
            this.networks = networks == null ? new ArrayList<>() : networks;
            this.error = error;
        }

        public boolean isOk() {
            return status == Status.NETWORKS;
        }
    }

    private WifiPrivilegedReader() {
    }

    /** 读取已保存网络列表（含明文密码） */
    public static Result read() {
        if (!ShizukuHelper.isRunning()) {
            return new Result(Status.NO_SERVER, null, null);
        }
        // Android 8 及以下没有隐藏 API 绕过手段，够不到这个接口
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return new Result(Status.UNSUPPORTED, null, null);
        }
        try {
            return new Result(Status.NETWORKS, networks(), null);
        } catch (Throwable t) {
            String detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            return new Result(Status.FAILED, null, detail);
        }
    }

    private static List<SavedNetworkModel> networks() throws Exception {
        Object service = service();
        Bundle extras = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? attributionExtras() : null;

        List<WifiConfiguration> configs = configuredNetworks(service, extras);

        // 同一个 SSID 可能存了不止一份配置：临时副本、重复添加的副本。
        // 屏幕是给人看密码的，同一行重复出现没有意义——保留带密码的那一份，
        // 免得一个空白副本把真实密码藏在它后面。
        Map<String, SavedNetworkModel> bySsid = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (WifiConfiguration config : configs) {
            SavedNetworkModel model = toModel(config);
            if (model == null) continue;
            SavedNetworkModel old = bySsid.get(model.getSsid());
            boolean oldHasKey = old != null && !isEmpty(old.getPreSharedKey());
            boolean newHasKey = !isEmpty(model.getPreSharedKey());
            if (old == null || (newHasKey && !oldHasKey)) {
                bySsid.put(model.getSsid(), model);
            }
        }
        return new ArrayList<>(bySsid.values());
    }

    /** 拿到「以 shell 视角」的 WiFi 系统服务 */
    private static Object service() throws Exception {
        Object binder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("wifi"));
        Class<?> stub = Class.forName(IWIFI_MANAGER_STUB);
        Object service = HiddenApiBypass.invoke(stub, null, "asInterface", binder);
        if (service == null) {
            throw new IllegalStateException("拿不到 WiFi 服务绑定器");
        }
        return service;
    }

    /** Android 13+ 需要携带 attribution source，声明这个的进程必须就是 shell 自己 */
    @SuppressLint("NewApi")
    private static Bundle attributionExtras() {
        AttributionSource source = new AttributionSource.Builder(Shizuku.getUid())
                .setPackageName(SHELL_PACKAGE)
                .setAttributionTag(SHELL_PACKAGE)
                .build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(ATTRIBUTION_KEY, source);
        return bundle;
    }

    /**
     * 调用 {@code getPrivilegedConfiguredNetworks}，两种方法签名依次试。
     *
     * <p>先问设备声明了哪种形状、再调用，是因为按名字调用不会告诉我们它要几个参数；
     * 形状不匹配的 {@code NoSuchMethodException} 是「换一种形状」的信号，
     * 而被服务拒绝是另一种结果，两者要能区分开。</p>
     */
    private static List<WifiConfiguration> configuredNetworks(Object service, Bundle extras)
            throws Exception {
        Class<?>[][] shapes = {
                {String.class, String.class, Bundle.class},
                {String.class, String.class}
        };

        for (Class<?>[] params : shapes) {
            if (!hasMethod(service.getClass(), PRIVILEGED_NETWORKS, params)) continue;

            Object[] args = params.length == 3
                    ? new Object[]{user(), SHELL_PACKAGE, extras != null ? extras : new Bundle()}
                    : new Object[]{user(), SHELL_PACKAGE};

            Object slice = HiddenApiBypass.invoke(
                    service.getClass(), service, PRIVILEGED_NETWORKS, args);
            if (slice == null) return new ArrayList<>();
            return sliceList(slice);
        }
        return new ArrayList<>();
    }

    /**
     * 忘记（删除）一个已保存的网络，跟读取走同一条 shell 身份的 binder 通道。
     *
     * <p>IWifiManager.forget 的签名各版本不一样：Android 13 起是
     * {@code forget(int, IActionListener)}，更早的版本还多两个参数（IBinder 与回调标识）。
     * 监听器传 null 即可，系统本来就允许不带回调的调用。</p>
     *
     * @return true 表示按设备声明的签名调用成功；找不到对应签名时返回 false
     */
    public static boolean forget(int networkId) throws Exception {
        Object service = service();
        Class<?> listenerClass = Class.forName("android.net.wifi.IActionListener");

        Class<?>[][] shapes = {
                {int.class, listenerClass},
                {int.class, android.os.IBinder.class, listenerClass, int.class}
        };

        for (Class<?>[] params : shapes) {
            if (!hasMethod(service.getClass(), "forget", params)) continue;
            Object[] args = params.length == 2
                    ? new Object[]{networkId, null}
                    : new Object[]{networkId, null, null, 0};
            HiddenApiBypass.invoke(service.getClass(), service, "forget", args);
            return true;
        }
        return false;
    }

    /**
     * AIDL 返回的是 {@code ParceledListSlice}，真正的列表在它基类的 {@code getList()} 上。
     *
     * <p>基类同样是隐藏类，所以也不写死，沿继承链往上找声明了 {@code getList} 的那一层。</p>
     */
    private static List<WifiConfiguration> sliceList(Object slice) throws Exception {
        for (Class<?> type = slice.getClass(); type != null; type = type.getSuperclass()) {
            if (hasMethod(type, "getList")) {
                Object list = HiddenApiBypass.invoke(type, slice, "getList");
                if (list instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<WifiConfiguration> result = (List<WifiConfiguration>) list;
                    return result;
                }
            }
        }
        return new ArrayList<>();
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... params) {
        try {
            HiddenApiBypass.getDeclaredMethod(type, name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 调用是作为谁发起的：Shizuku 的 uid 决定这个名字 */
    private static String user() {
        int uid = Shizuku.getUid();
        if (uid == 0) return "root";
        if (uid == 1000) return "system";
        return "shell";
    }

    private static SavedNetworkModel toModel(WifiConfiguration config) {
        String ssid = config.SSID;
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        // 没有名字的配置没法展示（每个被系统遗忘名字的网络都会有这么一条）
        if (ssid == null || ssid.isEmpty()) return null;

        SavedNetworkModel model = new SavedNetworkModel();
        model.setSsid(ssid);
        model.setNetworkId(config.networkId);
        model.setPriority(config.priority);
        model.setSecurity(security(config));
        model.setPreSharedKey(password(config));
        return model;
    }

    /**
     * 密码，按配置自己保存的形式取出。
     *
     * <p>PSK 是一个值，WEP 是四个且最多只有一个非空；两者都带着输入时的引号，
     * 引号是这个字段自身的语法，不属于密码，复制出去带引号就没法粘贴进任何地方。</p>
     */
    private static String password(WifiConfiguration config) {
        String psk = unquote(config.preSharedKey);
        if (!isEmpty(psk)) return psk;

        if (config.wepKeys != null) {
            for (String key : config.wepKeys) {
                String value = unquote(key);
                if (!isEmpty(value)) return value;
            }
        }
        return "";
    }

    /** 加密类型，按它允许的密钥管理方式推断 */
    private static String security(WifiConfiguration config) {
        BitSet km = config.allowedKeyManagement;
        if (km == null) return "开放";

        if (km.get(WifiConfiguration.KeyMgmt.SAE)) return "WPA3";
        if (km.get(WifiConfiguration.KeyMgmt.OWE)) return "OWE";
        if (km.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || km.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
                || km.get(WifiConfiguration.KeyMgmt.WPA_EAP)) {
            return "WPA2";
        }
        if (config.wepKeys != null) {
            for (String key : config.wepKeys) {
                if (!isEmpty(unquote(key))) return "WEP";
            }
        }
        return "开放";
    }

    private static String unquote(String value) {
        if (value == null) return "";
        return value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2
                ? value.substring(1, value.length() - 1) : value;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
