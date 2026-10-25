package com.networktool.app.fragment;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.NetworkToolApplication;
import com.networktool.app.R;
import com.networktool.app.activity.MainActivity;
import com.networktool.app.activity.settings.PrivacyPolicyActivity;
import com.networktool.app.activity.settings.UserAgreementActivity;
import com.networktool.app.activity.tool.DnsActivity;
import com.networktool.app.activity.tool.MacActivity;
import com.networktool.app.activity.tool.PingActivity;
import com.networktool.app.activity.tool.PortScanActivity;
import com.networktool.app.activity.tool.SpeedTestActivity;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.UrlOpenHelper;
import com.networktool.app.view.SignalStrengthView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 网络概览页
 *
 * 展示当前网络、关键指标、信号曲线和网络详情。
 */
public class InfoFragment extends Fragment {

    private SwipeRefreshLayout refreshLayout;
    private MaterialCardView wifiCard;
    private MaterialCardView metricsCard;
    private MaterialCardView signalCard;
    private MaterialCardView networkInfoCard;
    private MaterialCardView quickToolsCard;
    private MaterialCardView batteryCard;
    private TextView batteryPercent;
    private TextView batteryVoltage;
    private TextView batteryPower;
    private TextView batteryTemperature;
    private View heroContent;

    private ImageView wifiLogo;
    private TextView wifiTitle;
    private TextView wifiIp;
    private TextView wifiSignal;
    private TextView wifiAction;
    private TextView signalCurrent;
    private TextView gatewayValue;
    private TextView dnsValue;
    private TextView frequencyValue;
    private TextView linkSpeedValue;

    private LinearLayout detailContainer;
    private SignalStrengthView signalChart;

    private View metricGateway;
    private View metricDns;
    private View metricFrequency;
    private View metricSpeed;
    private ImageView quickPingIcon;
    private ImageView quickDnsIcon;
    private ImageView quickSpeedIcon;
    private ImageView quickPortIcon;
    private ImageView quickMacIcon;
    private TextView quickPingText;
    private TextView quickDnsText;
    private TextView quickSpeedText;
    private TextView quickPortText;
    private TextView quickMacText;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastNetworkType = "";
    private String currentGateway;
    private boolean isWifiNetwork = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable delayedRefresh = () -> {
        if (getActivity() != null && isAdded()) {
            refreshInfo();
        }
    };

    /** 电池硬件信息需要持续刷新（充电功率/电压/温度会随充电过程变化） */
    private final Runnable batteryTimer = new Runnable() {
        @Override
        public void run() {
            updateBatteryInfo();
            mainHandler.postDelayed(this, 2000);
        }
    };
    private BroadcastReceiver batteryReceiver;

    private final List<Integer> rssiHistory = new ArrayList<>();
    private final Runnable rssiTimer = new Runnable() {
        @Override
        public void run() {
            collectRssi();
            mainHandler.postDelayed(this, 3000);
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        initManagers();
        setupListeners();
        applyThemeColors();
        refreshInfo();
        registerNetworkCallback();
        checkUserAgreement();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyThemeColors();
        refreshInfo();
        collectRssi();
        mainHandler.post(rssiTimer);
        // 电池信息：2 秒轮询 + 系统电池广播双重驱动，避免电量/充电功率停在初次进入时的数值
        mainHandler.removeCallbacks(batteryTimer);
        mainHandler.post(batteryTimer);
        registerBatteryReceiver();
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(rssiTimer);
        mainHandler.removeCallbacks(batteryTimer);
        unregisterBatteryReceiver();
    }

    /** 注册电池变化广播：充电插拔、电量变化时立刻刷新，不用等轮询 */
    private void registerBatteryReceiver() {
        if (batteryReceiver != null || !isAdded()) return;
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    updateBatteryInfo();
                }
            }
        };
        try {
            ContextCompat.registerReceiver(requireContext(), batteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            batteryReceiver = null;
        }
    }

    private void unregisterBatteryReceiver() {
        if (batteryReceiver == null) return;
        try {
            requireContext().unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        batteryReceiver = null;
    }

    private void initViews(View view) {
        refreshLayout = view.findViewById(R.id.refreshLayout);
        wifiCard = view.findViewById(R.id.wifiCard);
        metricsCard = view.findViewById(R.id.metricsCard);
        signalCard = view.findViewById(R.id.signalCard);
        networkInfoCard = view.findViewById(R.id.networkInfoCard);
        quickToolsCard = view.findViewById(R.id.quickToolsCard);
        batteryCard = view.findViewById(R.id.batteryCard);
        batteryPercent = view.findViewById(R.id.batteryPercent);
        batteryVoltage = view.findViewById(R.id.batteryVoltage);
        batteryPower = view.findViewById(R.id.batteryPower);
        batteryTemperature = view.findViewById(R.id.batteryTemperature);
        heroContent = view.findViewById(R.id.heroContent);

        wifiLogo = view.findViewById(R.id.wifiLogo);
        wifiTitle = view.findViewById(R.id.wifiTitle);
        wifiIp = view.findViewById(R.id.wifiIp);
        wifiSignal = view.findViewById(R.id.wifiSignal);
        wifiAction = view.findViewById(R.id.wifiAction);
        signalCurrent = view.findViewById(R.id.signalCurrent);
        gatewayValue = view.findViewById(R.id.gatewayValue);
        dnsValue = view.findViewById(R.id.dnsValue);
        frequencyValue = view.findViewById(R.id.frequencyValue);
        linkSpeedValue = view.findViewById(R.id.linkSpeedValue);

        detailContainer = view.findViewById(R.id.detailContainer);
        signalChart = view.findViewById(R.id.signalChart);

        metricGateway = view.findViewById(R.id.metricGateway);
        metricDns = view.findViewById(R.id.metricDns);
        metricFrequency = view.findViewById(R.id.metricFrequency);
        metricSpeed = view.findViewById(R.id.metricSpeed);
        quickPingIcon = view.findViewById(R.id.quickPingIcon);
        quickDnsIcon = view.findViewById(R.id.quickDnsIcon);
        quickSpeedIcon = view.findViewById(R.id.quickSpeedIcon);
        quickPortIcon = view.findViewById(R.id.quickPortIcon);
        quickMacIcon = view.findViewById(R.id.quickMacIcon);
        quickPingText = view.findViewById(R.id.quickPingText);
        quickDnsText = view.findViewById(R.id.quickDnsText);
        quickSpeedText = view.findViewById(R.id.quickSpeedText);
        quickPortText = view.findViewById(R.id.quickPortText);
        quickMacText = view.findViewById(R.id.quickMacText);
    }

    private void initManagers() {
        Context context = requireContext().getApplicationContext();
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private void setupListeners() {
        refreshLayout.setOnRefreshListener(() -> {
            refreshInfo();
            collectRssi();
            refreshLayout.setRefreshing(false);
            showToast("网络信息已刷新");
        });

        wifiCard.setOnClickListener(v -> openRouter());
        wifiAction.setOnClickListener(v -> openRouter());

        metricGateway.setOnClickListener(v -> copyText(textOf(gatewayValue), "网关"));
        metricDns.setOnClickListener(v -> copyText(textOf(dnsValue), "DNS"));
        metricFrequency.setOnClickListener(v -> copyText(textOf(frequencyValue), "频率"));
        metricSpeed.setOnClickListener(v -> copyText(textOf(linkSpeedValue), "协商速率"));

        view(R.id.quickPing, PingActivity.class);
        view(R.id.quickDns, DnsActivity.class);
        view(R.id.quickSpeed, SpeedTestActivity.class);
        view(R.id.quickPort, PortScanActivity.class);
        view(R.id.quickMac, MacActivity.class);
    }

    private void view(int id, Class<?> target) {
        View view = getView();
        if (view == null) return;
        View button = view.findViewById(id);
        if (button != null) {
            button.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(requireContext(), target));
                } catch (Exception e) {
                    showToast("无法打开该工具");
                }
            });
        }
    }

    private void applyThemeColors() {
        if (!isAdded()) return;
        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        int surfaceColor = ThemeManager.getSurfaceColor(requireContext());
        int onSurfaceColor = ThemeManager.getOnSurfaceColor(requireContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(requireContext());

        wifiCard.setCardBackgroundColor(themeColor);
        try {
            wifiCard.setClipToOutline(true);
        } catch (Exception ignored) {
        }

        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{themeColor, ThemeManager.darkenColor(themeColor, 0.78f)});
        gradient.setCornerRadius(dp(20));
        heroContent.setBackground(gradient);

        wifiTitle.setTextColor(contrastColor);
        wifiIp.setTextColor(withAlpha(contrastColor, 0xD9));
        wifiSignal.setTextColor(contrastColor);
        wifiAction.setTextColor(withAlpha(contrastColor, 0xC0));
        wifiLogo.setColorFilter(contrastColor);

        metricsCard.setCardBackgroundColor(surfaceColor);
        signalCard.setCardBackgroundColor(surfaceColor);
        networkInfoCard.setCardBackgroundColor(surfaceColor);
        quickToolsCard.setCardBackgroundColor(surfaceColor);
        if (batteryCard != null) {
            batteryCard.setCardBackgroundColor(surfaceColor);
        }

        gatewayValue.setTextColor(onSurfaceColor);
        dnsValue.setTextColor(onSurfaceColor);
        frequencyValue.setTextColor(onSurfaceColor);
        linkSpeedValue.setTextColor(onSurfaceColor);
        signalCurrent.setTextColor(onSurfaceVariant);
        if (signalChart != null) {
            signalChart.updateThemeColors();
        }

        quickPingIcon.setColorFilter(accentColor);
        quickDnsIcon.setColorFilter(accentColor);
        quickSpeedIcon.setColorFilter(accentColor);
        quickPortIcon.setColorFilter(accentColor);
        quickMacIcon.setColorFilter(accentColor);
        quickPingText.setTextColor(onSurfaceVariant);
        quickDnsText.setTextColor(onSurfaceVariant);
        quickSpeedText.setTextColor(onSurfaceVariant);
        quickPortText.setTextColor(onSurfaceVariant);
        quickMacText.setTextColor(onSurfaceVariant);

        refreshLayout.setColorSchemeColors(accentColor);
        refreshLayout.setProgressBackgroundColorSchemeColor(surfaceColor);
    }

    /**
     * 读取并显示电池硬件信息：电量百分比、电压、充电功率、温度。
     *
     * - 电量通过 BatteryManager 的 BATTERY_PROPERTY_CAPACITY 获取
     * - 电压/温度/充电电流通过注册 ACTION_BATTERY_CHANGED 广播获取
     *   （这些字段不在 BatteryManager 属性里，只能从 sticky intent 读）
     * - 充电功率 = 电压 × 充电电流（仅充电时有意义，放电时显示"—"）
     */
    private void updateBatteryInfo() {
        if (!isAdded() || batteryPercent == null) return;
        try {
            Context context = requireContext();
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    context.getSystemService(Context.BATTERY_SERVICE);

            // 电量百分比
            int percent = -1;
            if (bm != null) {
                percent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
            batteryPercent.setText(percent >= 0 ? percent + "%" : "--");

            // 电压、温度、充电电流、状态：从 sticky 广播读取
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent battery = context.registerReceiver(null, filter);
            if (battery == null) return;

            int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1); // mV
            if (voltage <= 0) {
                // 部分机型/模拟器广播里没有电压，回退到 sysfs
                voltage = readBatteryVoltageMv();
            }
            int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1); // 0.1°C
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            // 是否在充电：状态位 + 是否插着电源，两者任一成立即视为充电
            // （有些机型放电状态位不准，只靠 EXTRA_STATUS 会误报「未充电」）
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            // 充电电流：CURRENT_NOW → CURRENT_AVERAGE → sysfs 三级回退
            // （不少机型 CURRENT_NOW 直接返回 0，导致充电功率一直显示 "--"）
            int current = Integer.MIN_VALUE;
            if (bm != null) {
                current = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (current == 0 || current == Integer.MIN_VALUE) {
                    int average = bm.getIntProperty(
                            android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                    if (average != 0 && average != Integer.MIN_VALUE) current = average;
                }
            }
            if (current == 0 || current == Integer.MIN_VALUE) {
                int sysfs = readBatteryCurrentUa();
                if (sysfs != 0) current = sysfs;
            }

            // 电压：手机电池正常在 3.0~4.5V，小于 2.5V 的读数视为无效（模拟器/个别机型会给垃圾值）
            boolean voltageValid = voltage >= 2500 && voltage <= 5000;
            if (voltageValid) {
                batteryVoltage.setText(String.format(Locale.US, "%.2fV", voltage / 1000f));
            } else {
                batteryVoltage.setText("--");
            }

            // 温度
            if (temp > 0) {
                batteryTemperature.setText(String.format(Locale.US, "%.1f°C", temp / 10f));
            } else {
                batteryTemperature.setText("--");
            }

            // 充电功率：仅充电状态下计算（电流取绝对值）
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
                    || plugged != 0;
            if (charging && voltageValid && current != Integer.MIN_VALUE && current != 0) {
                double amps = Math.abs(current) / 1_000_000.0;
                double watts = (voltage / 1000.0) * amps;
                batteryPower.setText(String.format(Locale.US, "%.1fW", watts));
            } else if (!charging) {
                batteryPower.setText("未充电");
            } else {
                // 充电中但读不到电流：至少说明在充电，而不是显示无意义的 "--"
                batteryPower.setText("充电中");
            }
            if (com.networktool.app.BuildConfig.DEBUG) {
                android.util.Log.d("InfoFragment", "硬件信息刷新: 电量=" + batteryPercent.getText()
                        + " 电压=" + batteryVoltage.getText()
                        + " 功率=" + batteryPower.getText()
                        + " 温度=" + batteryTemperature.getText()
                        + " 状态=" + status + " 电流=" + current + "µA");
            }
        } catch (Exception e) {
            // 读取失败不影响页面其它内容
            if (batteryPercent != null) batteryPercent.setText("--");
        }
    }

    /**
     * 从 sysfs 读电池电压并换算成 mV。
     * sysfs 里通常是 µV（如 4300000），少数机型直接给 mV（如 4300）。
     */
    private int readBatteryVoltageMv() {
        String[] paths = {
                "/sys/class/power_supply/battery/voltage_now",
                "/sys/class/power_supply/Battery/voltage_now",
                "/sys/class/power_supply/bms/voltage_now"
        };
        for (String path : paths) {
            java.io.BufferedReader reader = null;
            try {
                reader = new java.io.BufferedReader(new java.io.FileReader(path));
                String line = reader.readLine();
                if (line != null) {
                    int value = Integer.parseInt(line.trim());
                    if (value > 0) {
                        return value > 100000 ? value / 1000 : value;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 从 sysfs 读充电电流（µA）。Android 没有公开 API 的机型只能走这条路。
     * 不同机型路径不同，逐个尝试，读到非 0 值就用。
     */
    private int readBatteryCurrentUa() {
        String[] paths = {
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/Battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/usb/current_now"
        };
        for (String path : paths) {
            java.io.BufferedReader reader = null;
            try {
                reader = new java.io.BufferedReader(new java.io.FileReader(path));
                String line = reader.readLine();
                if (line != null) {
                    int value = Integer.parseInt(line.trim());
                    if (value != 0) {
                        // 规范单位是 µA；个别机型/内核直接写 mA，数值小于 1000 时按 mA 处理
                        return Math.abs(value) < 1000 ? value * 1000 : value;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return 0;
    }

    private void refreshInfo() {
        updateBatteryInfo();
        if (!isAdded() || connectivityManager == null) {
            updateNoNetworkInfo();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                if (info == null || !info.isConnected()) {
                    updateNoNetworkInfo();
                    return;
                }
                if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                    updateWifiInfo();
                } else if (info.getType() == ConnectivityManager.TYPE_ETHERNET) {
                    updateEthernetInfo();
                } else if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                    updateMobileInfo();
                } else {
                    updateOtherNetworkInfo();
                }
                return;
            }

            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                updateNoNetworkInfo();
                return;
            }
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (caps == null) {
                updateNoNetworkInfo();
                return;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                updateWifiInfo();
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                updateEthernetInfo();
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                updateMobileInfo();
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                updateOtherNetworkInfo();
            } else {
                updateNoNetworkInfo();
            }
        } catch (Exception e) {
            updateNoNetworkInfo();
        }
    }

    private void updateWifiInfo() {
        if (!isAdded()) return;
        if (wifiManager == null) {
            updateNoNetworkInfo();
            return;
        }
        WifiInfo wifiInfo;
        try {
            wifiInfo = wifiManager.getConnectionInfo();
        } catch (Exception e) {
            wifiInfo = null;
        }
        if (wifiInfo == null) {
            updateNoNetworkInfo();
            return;
        }

        isWifiNetwork = true;
        String rawSsid;
        String bssid;
        try {
            rawSsid = wifiInfo.getSSID();
            bssid = wifiInfo.getBSSID();
        } catch (Exception e) {
            rawSsid = null;
            bssid = null;
        }
        String ssid = sanitizeSsid(rawSsid);
        if (bssid == null || bssid.isEmpty()) bssid = "--";
        int rssi = wifiInfo.getRssi();
        int frequency = wifiInfo.getFrequency();
        int linkSpeed = wifiInfo.getLinkSpeed();

        String ipv4 = intToIp(wifiInfo.getIpAddress());
        if (ipv4 == null || "0.0.0.0".equals(ipv4)) {
            ipv4 = NetworkUtils.getActiveLocalIpv4Address(requireContext());
        }
        if (ipv4 == null || ipv4.isEmpty()) ipv4 = "--";

        String ipv6 = getIpv6Address();
        if (ipv6 == null || ipv6.isEmpty()) ipv6 = "--";

        // 有线优先取「以太网网络」的网关，避免系统活动网络是 WiFi 时显示错网关
        String gateway = NetworkUtils.getEthernetGateway(requireContext());
        if (gateway == null || gateway.isEmpty()) {
            gateway = NetworkUtils.getGateway(requireContext());
        }
        String dns1 = NetworkUtils.getDns1(requireContext());
        String dns2 = NetworkUtils.getDns2(requireContext());
        String standard = getWifiStandard(wifiInfo);

        boolean unknownSsid = "未知网络".equals(ssid);
        currentGateway = gateway;
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        wifiTitle.setTextColor(contrastColor);
        wifiTitle.setText(unknownSsid ? "WiFi 已连接" : ssid);
        wifiIp.setText(unknownSsid
                ? ipv4 + " · 网络名称需要附近设备权限"
                : ipv4 + " · " + standard);
        wifiSignal.setText(rssi + " dBm");
        wifiSignal.setTextColor(contrastColor);

        if (unknownSsid) {
            wifiAction.setVisibility(View.VISIBLE);
            wifiAction.setText("前往无线页授权查看网络名称  ›");
            wifiAction.setOnClickListener(v -> openWifiPage());
            wifiCard.setOnClickListener(v -> openWifiPage());
        } else {
            wifiAction.setOnClickListener(v -> openRouter());
            wifiCard.setOnClickListener(v -> openRouter());
            if (gateway != null && !gateway.isEmpty() && !"0.0.0.0".equals(gateway)) {
                wifiAction.setVisibility(View.VISIBLE);
                wifiAction.setText("路由器后台 · " + gateway + "  ›");
            } else {
                wifiAction.setVisibility(View.GONE);
            }
        }

        updateSignalIcon(rssi);
        signalCard.setVisibility(View.VISIBLE);
        signalCurrent.setText(rssi + " dBm");
        collectRssi();

        setMetricValues(gateway, dns1, frequency, linkSpeed);
        buildDetails(new String[][]{
                {"WiFi 名称", unknownSsid ? "需要授权查看" : ssid},
                {"本机 IPv4", ipv4},
                {"本机 IPv6", ipv6},
                {"网关", safe(gateway)},
                {"路由器 MAC", bssid},
                {"DNS 1", safe(dns1)},
                {"DNS 2", safe(dns2)},
                {"技术标准", standard},
                {"无线频率", formatFrequency(frequency)},
                {"链路速度", linkSpeed > 0 ? linkSpeed + " Mbps" : "--"},
                {"信号强度", rssi + " dBm"}
        });
    }

    private void updateEthernetInfo() {
        isWifiNetwork = false;
        rssiHistory.clear();
        if (signalChart != null) signalChart.setData(rssiHistory);

        String ipv4 = getEthernetIpv4Address();
        if (ipv4 == null) ipv4 = "--";
        String ipv6 = getIpv6Address();
        if (ipv6 == null) ipv6 = "--";
        String gateway = NetworkUtils.getGateway(requireContext());
        String dns1 = NetworkUtils.getDns1(requireContext());
        String dns2 = NetworkUtils.getDns2(requireContext());

        currentGateway = gateway;
        wifiLogo.setImageResource(R.drawable.ic_computer);
        wifiTitle.setText("有线网络");
        wifiIp.setText(ipv4 + " · 以太网");
        wifiSignal.setText("有线");
        signalCard.setVisibility(View.GONE);
        if (gateway != null && !"0.0.0.0".equals(gateway)) {
            wifiAction.setVisibility(View.VISIBLE);
            wifiAction.setText("路由器后台 · " + gateway + "  ›");
            wifiAction.setOnClickListener(v -> openRouter());
            wifiCard.setOnClickListener(v -> openRouter());
        } else {
            wifiAction.setVisibility(View.GONE);
            wifiCard.setOnClickListener(null);
        }

        setMetricValues(gateway, dns1, 0, 0);
        buildDetails(new String[][]{
                {"网络类型", "以太网（有线）"},
                {"本机 IPv4", ipv4},
                {"本机 IPv6", ipv6},
                {"网关", safe(gateway)},
                {"DNS 1", safe(dns1)},
                {"DNS 2", safe(dns2)}
        });
    }

    private void updateMobileInfo() {
        isWifiNetwork = false;
        rssiHistory.clear();
        if (signalChart != null) signalChart.setData(rssiHistory);

        String ipv4 = getMobileIpv4Address();
        if (ipv4 == null) ipv4 = "--";
        String ipv6 = getIpv6Address();
        if (ipv6 == null) ipv6 = "--";
        String gateway = NetworkUtils.getGateway(requireContext());
        String dns1 = NetworkUtils.getDns1(requireContext());
        String dns2 = NetworkUtils.getDns2(requireContext());

        currentGateway = null;
        wifiLogo.setImageResource(R.drawable.ic_import_export);
        wifiTitle.setText("移动数据");
        wifiIp.setText(ipv4 + " · 蜂窝网络");
        wifiSignal.setText("移动");
        signalCard.setVisibility(View.GONE);
        wifiAction.setVisibility(View.GONE);
        wifiCard.setOnClickListener(null);

        setMetricValues(gateway, dns1, 0, 0);
        buildDetails(new String[][]{
                {"网络类型", "移动数据"},
                {"本机 IPv4", ipv4},
                {"本机 IPv6", ipv6},
                {"网关", safe(gateway)},
                {"DNS 1", safe(dns1)},
                {"DNS 2", safe(dns2)}
        });
    }

    private void updateOtherNetworkInfo() {
        isWifiNetwork = false;
        rssiHistory.clear();
        if (signalChart != null) signalChart.setData(rssiHistory);

        String ipv4 = getEthernetIpv4Address();
        if (ipv4 == null) ipv4 = "--";
        String ipv6 = getIpv6Address();
        if (ipv6 == null) ipv6 = "--";

        currentGateway = null;
        wifiLogo.setImageResource(R.drawable.ic_import_export);
        wifiTitle.setText("其他网络");
        wifiIp.setText(ipv4);
        wifiSignal.setText("已连接");
        signalCard.setVisibility(View.GONE);
        wifiAction.setVisibility(View.GONE);
        wifiCard.setOnClickListener(null);

        setMetricValues(null, null, 0, 0);
        buildDetails(new String[][]{
                {"本机 IPv4", ipv4},
                {"本机 IPv6", ipv6}
        });
    }

    private void updateNoNetworkInfo() {
        isWifiNetwork = false;
        currentGateway = null;
        rssiHistory.clear();
        if (signalChart != null) signalChart.setData(rssiHistory);

        wifiLogo.setImageResource(R.drawable.ic_signal_wifi_outline_0);
        wifiTitle.setText("未连接网络");
        wifiIp.setText("请检查 Wi-Fi 或移动数据");
        wifiSignal.setText("无信号");
        signalCard.setVisibility(View.GONE);
        wifiAction.setVisibility(View.GONE);
        wifiCard.setOnClickListener(null);

        setMetricValues(null, null, 0, 0);
        buildDetails(new String[][]{
                {"网络状态", "当前没有可用的网络连接"}
        });
    }

    private void setMetricValues(String gateway, String dns, int frequency, int linkSpeed) {
        gatewayValue.setText(safe(gateway));
        dnsValue.setText(safe(dns));
        frequencyValue.setText(frequency > 0 ? getFrequencyBand(frequency) : "--");
        linkSpeedValue.setText(linkSpeed > 0 ? linkSpeed + " Mbps" : "--");
    }

    private void buildDetails(String[][] rows) {
        if (detailContainer == null || !isAdded()) return;
        detailContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int onSurface = ThemeManager.getOnSurfaceColor(requireContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(requireContext());
        int dividerColor = ThemeManager.getOutlineVariantColor(requireContext());

        for (int i = 0; i < rows.length; i++) {
            String label = rows[i][0];
            String value = safe(rows[i][1]);

            View row = inflater.inflate(R.layout.item_info_detail_row, detailContainer, false);
            TextView labelView = row.findViewById(R.id.labelText);
            TextView valueView = row.findViewById(R.id.valueText);
            ImageView copyIcon = row.findViewById(R.id.copyIcon);

            labelView.setText(label);
            labelView.setTextColor(onSurfaceVariant);
            valueView.setText(value);
            valueView.setTextColor(onSurface);
            copyIcon.setColorFilter(onSurfaceVariant);

            boolean copyable = !"--".equals(value) && !value.isEmpty()
                    && !value.startsWith("需要授权");
            copyIcon.setVisibility(copyable ? View.VISIBLE : View.INVISIBLE);
            if (copyable) {
                row.setOnClickListener(v -> copyText(value, label));
            } else {
                row.setClickable(false);
                row.setBackground(null);
            }
            detailContainer.addView(row);

            if (i < rows.length - 1) {
                View divider = new View(requireContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                lp.leftMargin = dpToPx(96);
                divider.setLayoutParams(lp);
                divider.setBackgroundColor(dividerColor);
                detailContainer.addView(divider);
            }
        }
    }

    private void collectRssi() {
        if (!isWifiNetwork || wifiManager == null || signalChart == null) return;
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return;
            int rssi = info.getRssi();
            if (rssi <= -120) return;
            rssiHistory.add(rssi);
            while (rssiHistory.size() > 20) {
                rssiHistory.remove(0);
            }
            signalChart.setData(rssiHistory);
            if (signalCurrent != null) {
                signalCurrent.setText(rssi + " dBm");
            }
        } catch (Exception ignored) {
        }
    }

    private void updateSignalIcon(int rssi) {
        if (rssi >= -50) {
            wifiLogo.setImageResource(R.drawable.ic_signal_wifi_outline_4);
        } else if (rssi >= -70) {
            wifiLogo.setImageResource(R.drawable.ic_signal_wifi_outline_3);
        } else if (rssi >= -85) {
            wifiLogo.setImageResource(R.drawable.ic_signal_wifi_outline_2);
        } else if (rssi >= -100) {
            wifiLogo.setImageResource(R.drawable.ic_signal_wifi_outline_1);
        } else {
            wifiLogo.setImageResource(R.drawable.ic_signal_wifi_outline_0);
        }
    }

    private String sanitizeSsid(String ssid) {
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) {
            return "未知网络";
        }
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid.isEmpty() ? "未知网络" : ssid;
    }

    private String getWifiStandard(WifiInfo wifiInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return wifiStandardName(wifiInfo.getWifiStandard());
            } catch (Exception ignored) {
            }
        }
        return "Wi-Fi";
    }

    private String wifiStandardName(int standard) {
        switch (standard) {
            case 1:
                return "Wi-Fi 1 (802.11a)";
            case 2:
                return "Wi-Fi 1 (802.11b)";
            case 3:
                return "Wi-Fi 3 (802.11g)";
            case 4:
                return "Wi-Fi 4 (802.11n)";
            case 5:
                return "Wi-Fi 5 (802.11ac)";
            case 6:
                return "Wi-Fi 6 (802.11ax)";
            case 7:
                return "802.11ad";
            case 8:
                return "Wi-Fi 7 (802.11be)";
            default:
                return "Wi-Fi";
        }
    }

    private String getFrequencyBand(int frequency) {
        if (frequency >= 2400 && frequency < 2500) return "2.4 GHz";
        if (frequency >= 5000 && frequency < 5900) return "5 GHz";
        if (frequency >= 5900 && frequency < 7125) return "6 GHz";
        return "--";
    }

    private String formatFrequency(int frequency) {
        if (frequency <= 0) return "--";
        return String.format(Locale.CHINA, "%.1f GHz (%d MHz)", frequency / 1000.0, frequency);
    }

    private String intToIp(int ip) {
        if (ip == 0) return null;
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private String getEthernetIpv4Address() {
        // 先按「带以太网传输的网络」精确取地址：之前先取活动网络，
        // 有线只连局域网、活动网络是 WiFi 时会把 WiFi 地址误显示成有线 IP
        String ethernetIp = NetworkUtils.getEthernetLocalIpv4Address(requireContext());
        if (ethernetIp != null && !ethernetIp.isEmpty()) {
            return ethernetIp;
        }
        String activeIp = NetworkUtils.getActiveLocalIpv4Address(requireContext());
        if (activeIp != null && !activeIp.isEmpty()) {
            return activeIp;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (name != null && (name.startsWith("eth") || name.startsWith("enp")
                        || name.startsWith("en0"))) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return getNonWifiIpv4Address();
    }

    private String getNonWifiIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (name != null && !name.startsWith("wlan") && !name.startsWith("wifi")) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    private String getIpv6Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String host = address.getHostAddress();
                    if (!address.isLoopbackAddress() && host != null && host.contains(":")) {
                        if (!address.isLinkLocalAddress()) {
                            return host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getMobileIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (name != null && (name.startsWith("rmnet") || name.startsWith("ppp")
                        || name.startsWith("tun") || name.startsWith("ccmni"))) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return getGeneralIpv4Address();
    }

    private String getGeneralIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (name == null || name.startsWith("wlan") || name.startsWith("eth")
                        || name.startsWith("ap")) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder().build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                private String detectNetworkType() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                        if (info == null || !info.isConnected()) return "无网络";
                        if (info.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
                        if (info.getType() == ConnectivityManager.TYPE_ETHERNET) return "有线";
                        if (info.getType() == ConnectivityManager.TYPE_MOBILE) return "移动数据";
                        return "其他";
                    }
                    Network active = connectivityManager.getActiveNetwork();
                    if (active == null) return "无网络";
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
                    if (caps == null) return "无网络";
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线";
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动数据";
                    return "其他";
                }

                private void scheduleRefresh(boolean showToast) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        mainHandler.removeCallbacks(delayedRefresh);
                        mainHandler.postDelayed(delayedRefresh, 2500);
                        if (showToast) {
                            String current = detectNetworkType();
                            if (!lastNetworkType.isEmpty() && !current.equals(lastNetworkType)) {
                                showToast("已切换到 " + current + " 网络");
                            }
                            lastNetworkType = current;
                        }
                    });
                }

                @Override
                public void onAvailable(@NonNull Network network) {
                    scheduleRefresh(true);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    mainHandler.removeCallbacks(delayedRefresh);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (isAdded()) {
                                lastNetworkType = "";
                                updateNoNetworkInfo();
                            }
                        });
                    }
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                 @NonNull NetworkCapabilities caps) {
                    scheduleRefresh(false);
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void openRouter() {
        if (currentGateway == null || currentGateway.isEmpty() || "0.0.0.0".equals(currentGateway)) {
            showToast("当前网络没有可用的网关地址");
            return;
        }
        openBrowser("http://" + currentGateway);
    }

    private void openWifiPage() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).selectPage(2);
        }
    }

    private void copyText(String text, String label) {
        if (text == null || text.isEmpty() || "--".equals(text)) {
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
                showToast("已复制" + label);
            }
        } catch (Exception e) {
            showToast("复制失败");
        }
    }

    private void openBrowser(String url) {
        try {
            // 按用户设置的跳转方式打开（内置浏览器或默认浏览器）
            UrlOpenHelper.open(requireContext(), url, "网页");
        } catch (Exception e) {
            showToast("无法打开浏览器");
        }
    }

    private void showToast(String message) {
        try {
            if (isAdded()) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private String textOf(TextView view) {
        return view == null ? "--" : view.getText().toString();
    }

    private String safe(String value) {
        return (value == null || value.isEmpty() || "null".equals(value)) ? "--" : value;
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void checkUserAgreement() {
        Integer agreementLength = (Integer) NetworkToolApplication.getInstance().getSharedData("用户协议");
        String prompt = getPromptText();

        if (agreementLength == null || agreementLength != prompt.length()) {
            showUserAgreementDialog(prompt);
        }
    }

    private String getPromptText() {
        return "使用须知：\n"
                + "网络工具箱（以下简称\"本应用\"）\n"
                + "本应用提供的功能建议专业人士使用。\n"
                + "本应用不收集任何用户信息，数据仅在本地处理。\n"
                + "运行所需权限由用户自行开启或关闭。\n"
                + "使用前请阅读《隐私政策》和《用户协议》。";
    }

    private void showUserAgreementDialog(String prompt) {
        SpannableStringBuilder builder = new SpannableStringBuilder(prompt);

        int privacyIndex = prompt.indexOf("隐私政策");
        if (privacyIndex >= 0) {
            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    startActivity(new Intent(requireContext(), PrivacyPolicyActivity.class));
                }
            }, privacyIndex, privacyIndex + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ForegroundColorSpan(ThemeManager.getThemeColor()),
                    privacyIndex, privacyIndex + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        int agreementIndex = prompt.indexOf("用户协议");
        if (agreementIndex >= 0) {
            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    startActivity(new Intent(requireContext(), UserAgreementActivity.class));
                }
            }, agreementIndex, agreementIndex + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ForegroundColorSpan(ThemeManager.getThemeColor()),
                    agreementIndex, agreementIndex + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("欢迎使用网络工具箱")
                .setMessage(builder)
                .setPositiveButton("同意协议", (dialog, which) ->
                        NetworkToolApplication.getInstance().putSharedData("用户协议", prompt.length()))
                .setNegativeButton("拒绝协议", (dialog, which) -> {
                    requireActivity().finishAffinity();
                    System.exit(0);
                })
                .setCancelable(false);

        androidx.appcompat.app.AlertDialog dialog = dialogBuilder.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));

        TextView messageTextView = dialog.findViewById(android.R.id.message);
        if (messageTextView != null) {
            messageTextView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(rssiTimer);
        mainHandler.removeCallbacks(batteryTimer);
        mainHandler.removeCallbacks(delayedRefresh);
        unregisterBatteryReceiver();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
    }
}
