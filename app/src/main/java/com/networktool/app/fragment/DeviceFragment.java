package com.networktool.app.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager.BadTokenException;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.networktool.app.R;
import com.networktool.app.activity.settings.RuntimeLogActivity;
import com.networktool.app.activity.tool.MacActivity;
import com.networktool.app.activity.tool.OnvifActivity;
import com.networktool.app.activity.tool.SadpActivity;
import com.networktool.app.activity.tool.PingActivity;
import com.networktool.app.activity.tool.PortScanActivity;
import com.networktool.app.adapter.DeviceAdapter;
import com.networktool.app.adapter.ScanOptionAdapter;
import com.networktool.app.model.DeviceInfo;
import com.networktool.app.util.LanScanManager;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.BottomSheetHelper;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.RuntimeLogStore;
import com.networktool.app.util.ScanSpeedConfig;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.TitlePriorityConfig;
import com.networktool.app.util.UpstreamNetworkDetector;
import com.networktool.app.util.UrlOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网设备页
 *
 * 负责设备发现、扫描状态、IP 版本/协议筛选和设备详情。
 */
public class DeviceFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView deviceSummary;
    private TextView deviceSubtitle;
    private MaterialButton scanButton;
    private MaterialButton filterButton;
    private MaterialButton sortButton;
    private MaterialButton settingsButton;
    private MaterialButton ipVersionButton;
    /** 排序/筛选/IP 版本那一行横向容器 */
    private HorizontalScrollView filterBar;
    private LinearProgressIndicator scanProgress;
    private SwipeRefreshLayout refreshLayout;
    private View emptyView;
    private TextView emptyText;
    private TextView emptyHint;
    private ImageView emptyIcon;
    private MaterialButton emptyAction;

    private DeviceAdapter adapter;
    private final List<DeviceInfo> deviceList = new ArrayList<>();
    private final List<DeviceInfo> filteredDeviceList = new ArrayList<>();

    private int ipVersionFilter = 0; // 0 = 全部（默认显示所有 IP 版本）
    private String protocolFilter = null;
    /** 排序方式：按 IP（默认）或按名称 */
    private static final String SORT_IP = "ip";
    private static final String SORT_NAME = "name";
    private String sortBy = SORT_IP;
    /** 「本机设备」行的标题来源标签，优先级最高（见 TitlePriorityConfig） */
    private static final String LOCAL_DEVICE_SOURCE = TitlePriorityConfig.LOCAL_LABEL;
    private boolean isScanning = false;
    private String scanStatus = "";
    private String scanLocalIp;
    private String scanRangeLabel = "";

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private LanScanManager scanManager;
    private int scanGeneration = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * 上级网段探测结果缓存：key 为本机 /24 前缀，value 为上级网关 IP，
     * 空字符串表示该网段探测过、没有上级内网（避免每次扫描都白等几秒）。
     */
    private static final Map<String, String> UPSTREAM_CACHE = new HashMap<>();
    /** 最近一次扫描到的上级网段网关，供首页过滤设备列表用 */
    private static String lastUpstreamGateway = null;

    public static String getLastUpstreamGateway() {
        return lastUpstreamGateway;
    }
    /** 是否已把进程默认网络切到 WiFi/以太网（内网无外网时必须切，否则探测包会走移动数据） */
    private boolean lanNetworkBound = false;
    /** 绑定时保存的 Application Context，onDestroyView 之后仍能用来解绑 */
    private Context appContextForNetwork;
    private final List<String[]> routerDomains = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        initManagers();
        loadSortPreference();
        setupRecyclerView();
        setupFilterChips();
        setupListeners();
        updateSortButton();
        updateFilterButton();
        applyThemeColors();
        registerNetworkCallback();
        startScan();
    }

    /** 读取持久化的排序方式（默认按 IP） */
    private void loadSortPreference() {
        try {
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            String saved = prefs.getString("device_sort_by", SORT_IP);
            sortBy = SORT_NAME.equals(saved) ? SORT_NAME : SORT_IP;
        } catch (Exception e) {
            sortBy = SORT_IP;
        }
    }

    /** 保存排序方式，一次设置永久生效 */
    private void saveSortPreference() {
        try {
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            prefs.edit().putString("device_sort_by", sortBy).apply();
        } catch (Exception ignored) {
        }
    }

    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recyclerView);
        deviceSummary = view.findViewById(R.id.deviceSummary);
        deviceSubtitle = view.findViewById(R.id.deviceSubtitle);
        scanButton = view.findViewById(R.id.scanButton);
        filterButton = view.findViewById(R.id.filterButton);
        sortButton = view.findViewById(R.id.sortButton);
        settingsButton = view.findViewById(R.id.settingsButton);
        ipVersionButton = view.findViewById(R.id.ipVersionButton);
        filterBar = view.findViewById(R.id.filterBar);
        scanProgress = view.findViewById(R.id.scanProgress);
        refreshLayout = view.findViewById(R.id.refreshLayout);
        emptyView = view.findViewById(R.id.emptyView);
        emptyText = view.findViewById(R.id.emptyText);
        emptyHint = view.findViewById(R.id.emptyHint);
        emptyIcon = view.findViewById(R.id.emptyIcon);
        emptyAction = view.findViewById(R.id.emptyAction);
        scanProgress.setVisibility(View.GONE);
    }

    private void initManagers() {
        connectivityManager = (ConnectivityManager) requireContext()
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        loadRouterList();
    }

    private void setupRecyclerView() {
        adapter = new DeviceAdapter(filteredDeviceList, new DeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onDeviceClick(DeviceInfo device) {
                showDeviceDetails(device);
            }

            @Override
            public void onDeviceLongClick(DeviceInfo device, String protocolInfo) {
                showDeviceDetails(device);
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
    }

    /** IP 版本筛选也走底部弹窗，和「排序 / 协议」用同一套交互 */
    private void setupFilterChips() {
        if (ipVersionButton == null) return;
        ipVersionButton.setOnClickListener(v -> showIpVersionDialog());
    }

    private void showIpVersionDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_scan_filter, null);
        dialog.setContentView(view);

        TextView title = view.findViewById(R.id.filterSheetTitle);
        if (title != null) title.setText("网络协议");
        TextView subtitle = view.findViewById(R.id.filterSheetSubtitle);
        if (subtitle != null) subtitle.setText("只显示某一类地址，默认两类都显示");

        RecyclerView list = view.findViewById(R.id.filterOptionList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new ScanOptionAdapter(buildIpVersionOptions(),
                ipVersionKey(ipVersionFilter), option -> {
                    ipVersionFilter = ipVersionFromKey(option.key);
                    updateIpVersionButton();
                    applyFilter();
                    dialog.dismiss();
                }));
        BottomSheetHelper.capHeight(dialog, 0.8f);
        dialog.show();
    }

    private List<ScanOptionAdapter.Option> buildIpVersionOptions() {
        List<ScanOptionAdapter.Option> options = new ArrayList<>();
        options.add(new ScanOptionAdapter.Option(null, "全部", "IPv4 和 IPv6 设备都显示"));
        options.add(new ScanOptionAdapter.Option("ipv4", "IPv4", "只显示使用 IPv4 地址的设备"));
        options.add(new ScanOptionAdapter.Option("ipv6", "IPv6", "只显示使用 IPv6 地址的设备"));
        return options;
    }

    private static String ipVersionKey(int version) {
        if (version == DeviceInfo.IPV4) return "ipv4";
        if (version == DeviceInfo.IPV6) return "ipv6";
        return null;
    }

    private static int ipVersionFromKey(String key) {
        if ("ipv4".equals(key)) return DeviceInfo.IPV4;
        if ("ipv6".equals(key)) return DeviceInfo.IPV6;
        return 0;
    }

    private void setupListeners() {
        scanButton.setOnClickListener(v -> {
            if (isScanning) {
                stopScan();
            } else {
                restartScan();
            }
        });
        filterButton.setOnClickListener(v -> showProtocolFilterDialog());
        sortButton.setOnClickListener(v -> showSortDialog());
        settingsButton.setOnClickListener(v -> showScanSettingsDialog());
        refreshLayout.setOnRefreshListener(this::restartScan);
        emptyAction.setOnClickListener(v -> {
            if (!NetworkUtils.isWifiConnected(requireContext()) && !isEthernetConnected()) {
                openNetworkSettings();
            } else if (protocolFilter != null && !deviceList.isEmpty()) {
                protocolFilter = null;
                updateFilterButton();
                applyFilter();
            } else if (!deviceList.isEmpty()) {
                ipVersionFilter = 0;
                updateIpVersionButton();
                applyFilter();
            } else {
                restartScan();
            }
        });
    }

    private void applyThemeColors() {
        if (!isAdded()) return;
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(requireContext());

        scanButton.setTextColor(accentColor);
        scanButton.setIconTint(ColorStateList.valueOf(accentColor));
        settingsButton.setIconTint(ColorStateList.valueOf(onSurfaceVariant));
        emptyAction.setTextColor(accentColor);
        emptyIcon.setColorFilter(onSurfaceVariant);

        // 排序/筛选胶囊：默认「柔和底 + 主题色文字」，筛选生效时变「实底 + 反色文字」。
        // 颜色全部来自 ThemeManager，切主题/深浅色时按钮自动跟着变。
        updateSortButton();
        updateFilterButton();

        updateIpVersionButton();
        refreshLayout.setColorSchemeColors(accentColor);
        refreshLayout.setProgressBackgroundColorSchemeColor(
                ThemeManager.getSurfaceColor(requireContext()));
        if (scanProgress != null) {
            scanProgress.setIndicatorColor(accentColor);
            scanProgress.setTrackColor(ThemeManager.getChipSurfaceColor(requireContext()));
        }
    }

    /**
     * 给「状态胶囊」上色。
     *
     * <p>{@code active=false}：柔和主题底 + 描边 + 主题色文字（表示「这是当前值」而不是操作按钮）；
     * {@code active=true}：主题色实底 + 反色文字（表示这一项筛选正在生效）。
     * 三种颜色都走 {@link ThemeManager}，不在布局里写死，切换主题变体自动跟随。</p>
     */
    private void styleStatePill(MaterialButton button, boolean active) {
        if (button == null || !isAdded()) return;
        Context context = requireContext();
        int accent = ThemeManager.getAccessibleThemeColor(context);
        int background = active ? accent : ThemeManager.getBadgeSurfaceColor(context);
        int foreground = active ? ThemeManager.getContrastColor(accent)
                : ThemeManager.getBadgeTextColor(context);

        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(foreground);
        button.setIconTint(ColorStateList.valueOf(foreground));
        button.setRippleColor(ColorStateList.valueOf(active
                ? ThemeManager.getContrastColor(accent) : accent));
        button.setStrokeWidth(active ? 0 : dpToPx(1));
        button.setStrokeColor(ColorStateList.valueOf(ThemeManager.getBadgeStrokeColor(context)));
    }

    /** 排序胶囊显示当前排序方式，点开是排序底部弹窗 */
    private void updateSortButton() {
        if (sortButton == null) return;
        boolean byName = SORT_NAME.equals(sortBy);
        String label = byName ? "按名称" : "按 IP";
        sortButton.setText(label);
        sortButton.setContentDescription("排序方式：" + label);
        styleStatePill(sortButton, false);
    }

    /** IP 版本胶囊：显示当前值，选中具体版本时变实底 */
    private void updateIpVersionButton() {
        if (ipVersionButton == null) return;
        String label;
        if (ipVersionFilter == DeviceInfo.IPV4) {
            label = "IPv4";
        } else if (ipVersionFilter == DeviceInfo.IPV6) {
            label = "IPv6";
        } else {
            label = "网络协议";
        }
        ipVersionButton.setText(label);
        ipVersionButton.setContentDescription("网络协议：" + label);
        styleStatePill(ipVersionButton, ipVersionFilter != 0);
    }

    /** 排序方式底部弹窗：按 IP / 按名称，选择后持久化保存 */
    private void showSortDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_scan_sort, null);
        dialog.setContentView(view);

        List<ScanOptionAdapter.Option> options = new ArrayList<>();
        options.add(new ScanOptionAdapter.Option(SORT_IP, "按 IP 排序",
                "同网段内地址从小到大，网关和常用设备集中在前面"));
        options.add(new ScanOptionAdapter.Option(SORT_NAME, "按名称排序",
                "按设备名或型号排序，方便按名字找特定设备"));

        RecyclerView list = view.findViewById(R.id.sortOptionList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new ScanOptionAdapter(options, sortBy, option -> {
            sortBy = option.key;
            saveSortPreference();
            updateSortButton();
            applyFilter();
            dialog.dismiss();
            showToast(SORT_NAME.equals(sortBy) ? "已按名称排序" : "已按 IP 排序");
        }));
        BottomSheetHelper.capHeight(dialog, 0.8f);
        dialog.show();
    }

    private void showProtocolFilterDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_scan_filter, null);
        dialog.setContentView(view);

        RecyclerView list = view.findViewById(R.id.filterOptionList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new ScanOptionAdapter(buildProtocolOptions(), protocolFilter, option -> {
            protocolFilter = option.key;
            updateFilterButton();
            applyFilter();
            dialog.dismiss();
        }));
        // 14 个协议在矮屏上放不下：限制最大高度，内容在内部滚动，
        // 滑到底再往上拖不会把弹窗拖掉
        BottomSheetHelper.capHeight(dialog, 0.8f);
        dialog.show();
    }

    /**
     * 协议筛选项。key 必须和 {@code DeviceInfo#getProtocolNames()} 里的名字完全一致，
     * 否则筛出来会是空列表。
     */
    private List<ScanOptionAdapter.Option> buildProtocolOptions() {
        List<ScanOptionAdapter.Option> options = new ArrayList<>();
        options.add(new ScanOptionAdapter.Option(null, "全部协议", "不做过滤，所有发现结果都显示"));
        options.add(new ScanOptionAdapter.Option("ARP表", "ARP 表", "同网段内所有在线设备，覆盖最全"));
        options.add(new ScanOptionAdapter.Option("mDNS", "mDNS", "苹果设备、打印机、投屏等局域网服务"));
        options.add(new ScanOptionAdapter.Option("NetBIOS", "NetBIOS", "Windows 电脑的共享与名称解析"));
        options.add(new ScanOptionAdapter.Option("SSDP", "SSDP", "DLNA、智能电视、智能音箱"));
        options.add(new ScanOptionAdapter.Option("路由器", "路由器", "网关、AP 等网络设备"));
        options.add(new ScanOptionAdapter.Option("Ping探测", "Ping 探测", "能响应 ICMP 的设备"));
        options.add(new ScanOptionAdapter.Option("HTTP", "HTTP", "开放了 Web 管理页面的设备"));
        options.add(new ScanOptionAdapter.Option("UDP广播", "UDP 广播", "通用局域网广播探测"));
        options.add(new ScanOptionAdapter.Option("IPv6", "IPv6", "通过 IPv6 邻居发现扫到的设备"));
        options.add(new ScanOptionAdapter.Option("端口探测", "端口探测", "常见服务端口扫描命中的设备"));
        options.add(new ScanOptionAdapter.Option("SADP", "SADP", "海康威视摄像头与录像机"));
        options.add(new ScanOptionAdapter.Option("ONVIF", "ONVIF", "支持 ONVIF 的网络摄像头"));
        options.add(new ScanOptionAdapter.Option("WSD", "WSD", "支持 WS-Discovery 的打印机与扫描仪"));
        return options;
    }

    private void updateFilterButton() {
        if (filterButton == null) return;
        String label = protocolFilter == null ? "全部协议" : protocolFilter;
        filterButton.setText(label);
        filterButton.setContentDescription(protocolFilter == null
                ? "筛选协议：全部" : "筛选协议：" + protocolFilter);
        // 有筛选时按钮变实底，一眼看出列表被过滤过
        styleStatePill(filterButton, protocolFilter != null);
    }

    private void restartScan() {
        scanGeneration++;
        if (scanManager != null) {
            scanManager.stopScan();
        }
        unbindLanNetwork();
        isScanning = false;
        scanStatus = "";
        deviceList.clear();
        filteredDeviceList.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        refreshLayout.setRefreshing(false);
        startScan();
    }

    private void startScan() {
        boolean wired = isEthernetConnected();
        if (!NetworkUtils.isWifiConnected(requireContext()) && !wired) {
            isScanning = false;
            scanStatus = "设备扫描仅支持 WiFi 和有线网络";
            scanRangeLabel = "";
            updateScanUi();
            updateEmptyState();
            return;
        }

        isScanning = true;
        scanStatus = "正在检测相邻网段…";
        updateScanUi();

        // 内网没有外网时系统默认网络仍是移动数据，不切网络的话
        // UDP 广播/TCP 探测会从移动数据网卡发出，扫不到任何内网设备。
        bindLanNetwork();

        // 有线优先：即使系统当前活动网络是移动数据，只要以太网已连接，
        // 也使用以太网网段的网关和本机地址进行扫描。
        if (wired) {
            scanLocalIp = NetworkUtils.getEthernetLocalIpv4Address(requireContext());
        } else {
            // WiFi 已连接时优先使用 WiFi 网段，避免移动数据作为活动网络时扫错范围
            scanLocalIp = NetworkUtils.getWifiLocalIpv4Address(requireContext());
        }
        if (scanLocalIp == null) {
            scanLocalIp = NetworkUtils.getActiveLocalIpv4Address(requireContext());
        }
        scanRangeLabel = buildScanRangeLabel(scanLocalIp);

        String gateway = wired
                ? NetworkUtils.getEthernetGateway(requireContext())
                : NetworkUtils.getWifiGateway(requireContext());
        if (gateway == null && scanLocalIp != null && !scanLocalIp.isEmpty()) {
            // 没有默认路由/DHCP 网关时，按本机 IP 推断同网段网关
            gateway = inferGatewayFromLocalIp(scanLocalIp);
        }

        if (gateway != null && !"0.0.0.0".equals(gateway)) {
            addGatewayDevice(gateway);
            searchRouterBackend();
            addLocalDevice();
            detectUpstreamAndScan(gateway);
        } else if (scanLocalIp != null && !scanLocalIp.isEmpty()
                && !"0.0.0.0".equals(scanLocalIp)) {
            String inferredGateway = inferGatewayFromLocalIp(scanLocalIp);
            addLocalDevice();
            detectUpstreamAndScan(inferredGateway);
        } else {
            isScanning = false;
            scanStatus = "未连接到网络";
            unbindLanNetwork();
            updateScanUi();
            updateEmptyState();
        }
    }

    /**
     * 先探测上级网段（可路由到、但广播发现不了的上一层内网），再开始扫描。
     *
     * 探测结果按本机网段缓存在内存里，同一网段重复扫描不会每次都多花几秒。
     */
    private void detectUpstreamAndScan(String gateway) {
        final String localIpForDetect = scanLocalIp;
        final String gatewayForScan = gateway;
        final int detectGeneration = scanGeneration;

        String cached = UPSTREAM_CACHE.get(NetworkUtils.subnetPrefix24(localIpForDetect));
        if (cached != null) {
            startLanScan(gatewayForScan, cached.isEmpty() ? null : cached);
            return;
        }

        new Thread(() -> {
            String upstream = null;
            try {
                upstream = UpstreamNetworkDetector.detect(localIpForDetect);
            } catch (Throwable t) {
                AppErrorLogger.catchThrowable("DeviceFragment", "上级网段探测失败", t);
            }
            final String result = upstream;
            mainHandler.post(() -> {
                if (!isAdded() || detectGeneration != scanGeneration || !isScanning) return;
                String prefix = NetworkUtils.subnetPrefix24(localIpForDetect);
                if (prefix != null) UPSTREAM_CACHE.put(prefix, result == null ? "" : result);
                scanStatus = "正在发现局域网设备…";
                updateScanUi();
                startLanScan(gatewayForScan, result);
            });
        }, "upstream-detect").start();
    }

    /**
     * 扫描前把进程默认网络切到 WiFi/以太网。
     * 局域网没有外网时（如摄像头/内网），系统的默认网络仍是移动数据，
     * 未绑定网络的 Socket 会把广播发到移动数据网卡，导致扫不到设备。
     */
    private void bindLanNetwork() {
        if (lanNetworkBound) return;
        Context context = getContext();
        if (context == null) return;
        appContextForNetwork = context.getApplicationContext();
        lanNetworkBound = NetworkUtils.acquireLanNetwork(context);
        AppErrorLogger.logDebug("DeviceFragment", "局域网扫描绑定网络: " + lanNetworkBound);
    }

    /** 扫描结束后恢复系统默认网络，避免影响其他网络请求 */
    private void unbindLanNetwork() {
        if (!lanNetworkBound) return;
        lanNetworkBound = false;
        Context context = appContextForNetwork != null ? appContextForNetwork : getContext();
        if (context == null) return;
        NetworkUtils.releaseLanNetwork(context);
    }

    private String buildScanRangeLabel(String ip) {
        if (ip == null || !ip.contains(".")) return "";
        int lastDot = ip.lastIndexOf(".");
        if (lastDot <= 0) return "";
        return ip.substring(0, lastDot + 1) + "0/24";
    }

    private void stopScan() {
        scanGeneration++;
        if (scanManager != null) {
            scanManager.stopScan();
        }
        isScanning = false;
        scanStatus = "扫描已停止";
        unbindLanNetwork();
        updateScanUi();
        updateEmptyState();
        showToast("已停止扫描");
    }

    private String getNetworkTypeLabel() {
        if (isEthernetConnected()) return "有线";
        if (NetworkUtils.isWifiConnected(requireContext())) return "WiFi";
        if (NetworkUtils.isMobileDataConnected(requireContext())) return "移动数据";
        if (NetworkUtils.isNetworkConnected(requireContext())) return "局域网";
        return "未连接";
    }

    private boolean isEthernetConnected() {
        if (connectivityManager == null) return false;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        // Android 5/6 旧接口兜底
        try {
            NetworkInfo info = connectivityManager.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && info.getType() == ConnectivityManager.TYPE_ETHERNET;
        } catch (Exception e) {
            return false;
        }
    }

    private void startLanScan(String gateway, String upstreamGateway) {
        try {
            final int generation = ++scanGeneration;
            scanManager = new LanScanManager(requireContext(), gateway, scanLocalIp,
                    new LanScanManager.OnScanListener() {
                @Override
                public void onDeviceFound(DeviceInfo device) {
                    if (!isAdded() || device == null || generation != scanGeneration) return;
                    for (int i = 0; i < deviceList.size(); i++) {
                        DeviceInfo existing = deviceList.get(i);
                        if (existing.getIp() == null || !existing.getIp().equals(device.getIp())) {
                            continue;
                        }
                        // IP 已存在（多半是 addGatewayDevice / addLocalDevice 提前塞的占位行）。
                        // 占位行没有 titleSource，说明它的名字只是开场凑数，
                        // 这里必须用扫描器的真实结果替换掉，否则真实标题永远进不来。
                        if (existing.getTitleSource() == null) {
                            deviceList.set(i, device);
                            applyFilter();
                        }
                        return;
                    }
                    deviceList.add(device);
                    applyFilter();
                }

                @Override
                public void onDeviceUpdated(DeviceInfo device) {
                    if (!isAdded() || device == null || generation != scanGeneration) return;
                    for (int i = 0; i < deviceList.size(); i++) {
                        DeviceInfo existing = deviceList.get(i);
                        if (existing.getIp() == null
                                || !existing.getIp().equals(device.getIp())) {
                            continue;
                        }
                        // 本机这一行是页面自己放的：标题用设备名、标记 localDevice。
                        // 扫描器送来的对象没有这些信息，直接替换会把标题和标记冲掉，
                        // 所以这里保留本机的标题/标记，只把扫描补到的信息（MAC、端口、协议）并进来。
                        if (existing.isLocalDevice()) {
                            device.setLocalDevice(true);
                            if (existing.getTitle() != null && !existing.getTitle().isEmpty()) {
                                device.setTitle(existing.getTitle());
                            }
                            device.setTitleSource(LOCAL_DEVICE_SOURCE);
                            for (Integer port : existing.getPorts()) {
                                device.addPort(port);
                            }
                        }
                        deviceList.set(i, device);
                        break;
                    }
                    applyFilter();
                }

                @Override
                public void onProgress(String status, int progress, int total) {
                    if (!isAdded() || generation != scanGeneration) return;
                    scanStatus = status == null ? "正在扫描…" : status;
                    // 仅在扫描进行中才显示进度条，避免扫描结束后被晚到的回调重新显示
                    if (scanProgress != null && isScanning) {
                        scanProgress.setVisibility(View.VISIBLE);
                        if (total > 0) {
                            scanProgress.setIndeterminate(false);
                            scanProgress.setMax(total);
                            scanProgress.setProgressCompat(progress, true);
                        } else {
                            scanProgress.setIndeterminate(true);
                        }
                    }
                    updateSummary();
                }

                @Override
                public void onScanComplete(int deviceCount) {
                    if (!isAdded() || generation != scanGeneration) return;
                    isScanning = false;
                    scanStatus = "扫描完成";
                    unbindLanNetwork();
                    refreshLayout.setRefreshing(false);
                    updateScanUi();
                    applyFilter();
                    showToast("局域网设备扫描完成");
                }

                @Override
                public void onError(String error) {
                    if (!isAdded() || generation != scanGeneration) return;
                    isScanning = false;
                    scanStatus = error == null ? "扫描失败" : error;
                    unbindLanNetwork();
                    refreshLayout.setRefreshing(false);
                    updateScanUi();
                    updateEmptyState();
                    showToast(scanStatus);
                }
            });
            if (upstreamGateway != null) {
                int extraHosts = scanManager.addExtraSubnet(upstreamGateway);
                if (extraHosts > 0) {
                    lastUpstreamGateway = upstreamGateway;
                    scanRangeLabel = buildScanRangeLabel(scanLocalIp)
                            + " + " + buildScanRangeLabel(upstreamGateway) + " 上级网段";
                    AppErrorLogger.logDebug("DeviceFragment",
                            "追加上级网段 " + upstreamGateway + "，新增主机 " + extraHosts);
                }
            }
            scanManager.startScan();
        } catch (Exception e) {
            isScanning = false;
            scanStatus = "扫描启动失败";
            updateScanUi();
            updateEmptyState();
        }
    }

    private String inferGatewayFromLocalIp(String localIp) {
        try {
            String[] parts = localIp.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".1";
            }
        } catch (Exception ignored) {
        }
        return "192.168.1.1";
    }

    /**
     * 先放一个网关占位设备，保证列表一打开就有网关这一行。
     *
     * <p>注意这里刻意<b>不</b>设置 titleSource：占位名只是开场凑数，
     * 一旦设成「路由器」（优先级表里最高）就会永远盖住扫描器拿到的真实标题，
     * 导致 192.168.2.1 一直显示「路由器网关」而不是「中国电信智能网关」。
     * 保持 titleSource 为 null，扫描器发现同一 IP 时才能真正覆盖它。</p>
     */
    private void addGatewayDevice(String gateway) {
        DeviceInfo device = new DeviceInfo();
        device.setIp(gateway);
        device.setTitle("路由器网关");
        device.setIconType(DeviceInfo.ICON_ROUTER);
        device.addProtocolName("路由器", device.getTitle());
        deviceList.add(device);
        applyFilter();
    }

    /**
     * 往列表里放「本机设备」这一行。
     *
     * <p>本机固定排在列表最前面（用户最先关心的就是自己这台），
     * 标题直接用设备自身的名称（系统「设备名称」或机型），不再拼 "(localhost)"。</p>
     */
    private void addLocalDevice() {
        String localIp = scanLocalIp;
        if (localIp == null || localIp.isEmpty()) {
            localIp = NetworkUtils.getActiveLocalIpv4Address(requireContext());
        }
        if (localIp == null || localIp.isEmpty()) return;
        for (DeviceInfo existing : deviceList) {
            if (localIp.equals(existing.getIp())) return;
        }
        DeviceInfo device = new DeviceInfo();
        device.setIp(localIp);
        device.setTitle(NetworkUtils.getLocalDeviceName(requireContext()));
        // 图标跟着真实设备类型走：手机用手机图标，别再一律套「电脑」
        int localType = DeviceInfo.ICON_COMPUTER;
        if ("手机".equals(NetworkUtils.getLocalDeviceTypeLabel(requireContext()))) {
            localType = DeviceInfo.ICON_PHONE;
        }
        device.setIconType(localType);
        device.setLocalDevice(true);
        // 标题来源设成「本机」，优先级表里最高，扫描器拿到别的名字也盖不掉
        device.setTitleSource(LOCAL_DEVICE_SOURCE);
        device.addProtocolName(LOCAL_DEVICE_SOURCE, device.getTitle());
        deviceList.add(device);
        applyFilter();
    }

    private void searchRouterBackend() {
        new Thread(() -> {
            int tried = 0;
            int matched = 0;
            for (String[] router : routerDomains) {
                if (getActivity() == null) break;
                tried++;
                try {
                    java.net.InetAddress address = java.net.InetAddress.getByName(router[1]);
                    String ip = address.getHostAddress();
                    if (!isPrivateIP(ip)) continue;
                    matched++;
                    final String finalIp = ip;
                    final String routerLink = router[1];
                    final String brand = router[0];
                    // 记入运行报文：设备详情 →「路由器」协议标签可核对
                    RuntimeLogStore.record(RuntimeLogStore.P_ROUTER, ip, "域名命中",
                            brand + " | " + routerLink + " → " + ip);
                    if (getActivity() == null) break;
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        for (DeviceInfo device : deviceList) {
                            if (finalIp.equals(device.getIp())) {
                                if (device.getDescription() == null || device.getDescription().isEmpty()) {
                                    device.setDescription("http://" + routerLink);
                                }
                                device.addProtocolName("路由器", brand + " - " + routerLink);
                                // 路由器品牌按用户配置的优先级覆盖旧标题（默认高于 NetBIOS）
                                int routerRank = TitlePriorityConfig.rank(requireContext(), "路由器");
                                int currentRank = device.getTitleSource() == null
                                        ? Integer.MAX_VALUE
                                        : TitlePriorityConfig.rank(requireContext(), device.getTitleSource());
                                if (routerRank <= currentRank) {
                                    device.setTitle(brand);
                                    device.setTitleSource("路由器");
                                    device.setIconType(DeviceInfo.ICON_ROUTER);
                                }
                                applyFilter();
                                return;
                            }
                        }
                        DeviceInfo device = new DeviceInfo();
                        device.setIp(finalIp);
                        device.setTitle(brand);
                        device.setTitleSource("路由器");
                        device.setDescription("http://" + routerLink);
                        device.setIconType(DeviceInfo.ICON_ROUTER);
                        device.addProtocolName("路由器", brand + " - " + routerLink);
                        deviceList.add(device);
                        applyFilter();
                    });
                } catch (Exception ignored) {
                }
            }
            RuntimeLogStore.record(RuntimeLogStore.P_ROUTER, "", "扫描完成",
                    "尝试 " + tried + " 个路由器域名，命中 " + matched + " 个");
        }).start();
    }

    private boolean isPrivateIP(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        return ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    private void loadRouterList() {
        try (InputStream is = requireContext().getAssets().open("routerList.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject jsonObject = new JSONObject(sb.toString());
            JSONArray array = jsonObject.getJSONArray("routers");
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                routerDomains.add(new String[]{obj.getString("brand"), obj.getString("link")});
            }
        } catch (Exception e) {
            if (routerDomains.isEmpty()) {
                routerDomains.add(new String[]{"TP-Link", "tplogin.cn"});
                routerDomains.add(new String[]{"小米路由", "miwifi.com"});
            }
        }
    }

    private void applyFilter() {
        if (adapter == null) return;
        filteredDeviceList.clear();
        for (DeviceInfo device : deviceList) {
            if (matchesFilter(device)) {
                filteredDeviceList.add(device);
            }
        }
        filteredDeviceList.sort(this::compareDevices);
        adapter.notifyDataSetChanged();
        updateSummary();
        updateEmptyState();
        // 把设备页「当前展示的列表」同步给主页快照，
        // 主页的标题、顺序、数量才会和设备页完全一致
        try {
            com.networktool.app.util.LanScanManager.syncCachedDevices(
                    requireContext(), new ArrayList<>(filteredDeviceList));
        } catch (Exception ignored) {
        }
    }

    private boolean matchesFilter(DeviceInfo device) {
        if (device == null) return false;
        if (ipVersionFilter != 0 && device.getIpVersion() != ipVersionFilter) {
            return false;
        }
        return protocolFilter == null || device.getProtocolNames().containsKey(protocolFilter);
    }

    private void updateScanUi() {
        if (!isAdded()) return;
        scanButton.setText(isScanning ? "停止" : "扫描");
        scanButton.setIconResource(isScanning ? R.drawable.ic_pause : R.drawable.ic_scan);
        scanProgress.setVisibility(isScanning ? View.VISIBLE : View.GONE);
        if (isScanning) {
            scanProgress.setIndeterminate(true);
        }
        updateSummary();
    }

    private void updateSummary() {
        if (deviceSummary == null) return;
        if (isScanning) {
            deviceSummary.setText("正在扫描");
            String status = scanStatus == null || scanStatus.isEmpty()
                    ? "正在发现局域网设备…" : scanStatus;
            deviceSubtitle.setText(scanRangeLabel.isEmpty()
                    ? status : status + " · " + scanRangeLabel);
            return;
        }
        int count = filteredDeviceList.size();
        if (count > 0) {
            deviceSummary.setText(count + " 台设备");
        } else {
            deviceSummary.setText(deviceList.isEmpty() ? "未发现设备" : "0 台设备");
        }
        StringBuilder subtitle = new StringBuilder();
        subtitle.append("共发现 ").append(deviceList.size()).append(" 台");
        subtitle.append(" · ").append(getNetworkTypeLabel());
        if (!scanRangeLabel.isEmpty()) subtitle.append(" · ").append(scanRangeLabel);
        if (ipVersionFilter == DeviceInfo.IPV4) subtitle.append(" · IPv4");
        else if (ipVersionFilter == DeviceInfo.IPV6) subtitle.append(" · IPv6");
        if (protocolFilter != null) subtitle.append(" · ").append(protocolFilter);
        if (scanStatus != null && !scanStatus.isEmpty()) {
            subtitle.append(" · ").append(scanStatus);
        }
        deviceSubtitle.setText(subtitle.toString());
    }

    private void updateEmptyState() {
        if (emptyView == null) return;
        boolean empty = !isScanning && filteredDeviceList.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;

        boolean noNetwork = !NetworkUtils.isWifiConnected(requireContext())
                && !isEthernetConnected();
        if (noNetwork) {
            emptyText.setText("设备扫描仅支持 WiFi 和有线网络");
            emptyHint.setText("请连接 WiFi 或插入有线网络");
            emptyAction.setText("打开网络设置");
        } else if (protocolFilter != null && !deviceList.isEmpty()) {
            emptyText.setText("没有符合协议的设备");
            emptyHint.setText("尝试切换或清除协议筛选");
            emptyAction.setText("清除筛选");
        } else if (deviceList.isEmpty()) {
            emptyText.setText("未发现局域网设备");
            emptyHint.setText("确认手机与设备在同一局域网");
            emptyAction.setText("重新扫描");
        } else {
            emptyText.setText("没有符合筛选条件的设备");
            emptyHint.setText("尝试切换 IP 版本筛选");
            emptyAction.setText("查看全部");
        }
    }

    private void showDeviceDetails(DeviceInfo device) {
        if (device == null || !isAdded()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottomsheet_device_details, null);
        dialog.setContentView(view);

        TextView title = view.findViewById(R.id.detailTitle);
        TextView ip = view.findViewById(R.id.detailIp);
        TextView mac = view.findViewById(R.id.detailMac);
        TextView description = view.findViewById(R.id.detailDescription);
        TextView protocols = view.findViewById(R.id.detailProtocols);
        com.google.android.material.chip.ChipGroup protocolChips =
                view.findViewById(R.id.detailProtocolChips);
        MaterialButton openHttp = view.findViewById(R.id.openHttpButton);
        MaterialButton onvifDebug = view.findViewById(R.id.onvifDebugButton);
        MaterialButton sadpDebug = view.findViewById(R.id.sadpDebugButton);
        MaterialButton ping = view.findViewById(R.id.pingButton);
        MaterialButton portScan = view.findViewById(R.id.portScanButton);
        MaterialButton copyIp = view.findViewById(R.id.copyIpButton);
        MaterialButton macQuery = view.findViewById(R.id.macQueryButton);
        MaterialButton share = view.findViewById(R.id.shareButton);

        String deviceTitle = device.getTitle();
        title.setText(deviceTitle == null || deviceTitle.isEmpty() ? "未知设备" : deviceTitle);
        ip.setText(device.getIp() == null ? "--" : device.getIp());
        String deviceMac = device.getMacAddress();
        mac.setText(deviceMac == null || deviceMac.isEmpty()
                ? "MAC: 未获取（可尝试重新扫描）" : "MAC: " + deviceMac);
        String desc = device.getDescription();
        description.setText(desc == null || desc.isEmpty() ? "未获取到更多设备描述" : desc);
        String protocolText = joinProtocols(device);
        protocols.setText(protocolText.isEmpty() ? "未知" : protocolText);
        // 生成可点击的协议标签，点击后跳转运行日志查看该协议原始报文
        populateProtocolChips(protocolChips, device);
        // 同一设备被多种协议发现时，把所有名称都列出来（点击可复制）
        populateNameList(view.findViewById(R.id.detailNameList), device);

        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        openHttp.setBackgroundTintList(ColorStateList.valueOf(themeColor));
        openHttp.setTextColor(contrastColor);
        openHttp.setIconTint(ColorStateList.valueOf(contrastColor));
        // ping / portScan 是 TonalButton 样式（淡主题底 + 主题色文字），
        // XML 默认背景取静态 colorSecondaryContainer 不跟动态主题色，统一走 applyTonalButton 重刷
        ThemeManager.applyTonalButton(ping);
        ThemeManager.applyTonalButton(portScan);
        copyIp.setTextColor(accentColor);
        copyIp.setIconTint(ColorStateList.valueOf(accentColor));
        mac.setTextColor(accentColor);
        mac.setClickable(true);
        mac.setFocusable(true);
        macQuery.setTextColor(accentColor);
        macQuery.setIconTint(ColorStateList.valueOf(accentColor));
        share.setTextColor(accentColor);
        share.setIconTint(ColorStateList.valueOf(accentColor));

        // 协议调试入口：按设备实际被哪个协议发现来决定弹哪个入口。
        // ONVIF 和 SADP 是两个独立协议，互斥显示——海康 SADP 设备不该出现「ONVIF 调试」
        boolean onvifFound = isOnvifDevice(device);
        boolean sadpFound = isSadpDevice(device);
        if (onvifFound) {
            onvifDebug.setVisibility(View.VISIBLE);
            // 背景跟随主题色，与「打开 HTTP 页面」按钮一致
            onvifDebug.setBackgroundTintList(ColorStateList.valueOf(themeColor));
            onvifDebug.setTextColor(contrastColor);
            onvifDebug.setIconTint(ColorStateList.valueOf(contrastColor));
            onvifDebug.setOnClickListener(v -> {
                openOnvifDebug(device);
                dialog.dismiss();
            });
        } else {
            onvifDebug.setVisibility(View.GONE);
        }
        if (sadpFound && !onvifFound) {
            sadpDebug.setVisibility(View.VISIBLE);
            sadpDebug.setBackgroundTintList(ColorStateList.valueOf(themeColor));
            sadpDebug.setTextColor(contrastColor);
            sadpDebug.setIconTint(ColorStateList.valueOf(contrastColor));
            sadpDebug.setOnClickListener(v -> {
                openSadpDebug(device);
                dialog.dismiss();
            });
        } else {
            sadpDebug.setVisibility(View.GONE);
        }

        openHttp.setOnClickListener(v -> {
            openHttp(device);
            dialog.dismiss();
        });
        ping.setOnClickListener(v -> {
            String targetIp = device.getIp();
            if (targetIp == null || targetIp.trim().isEmpty()) {
                showToast("设备没有可用的 IP 地址");
                return;
            }
            try {
                Intent intent = new Intent(requireContext(), PingActivity.class);
                intent.putExtra("host", targetIp.trim());
                intent.putExtra("auto_start", true);
                startActivity(intent);
            } catch (Exception e) {
                showToast("无法打开 Ping 工具");
            }
            dialog.dismiss();
        });
        portScan.setOnClickListener(v -> {
            String targetIp = device.getIp();
            if (targetIp == null || targetIp.trim().isEmpty()) {
                showToast("设备没有可用的 IP 地址");
                return;
            }
            try {
                Intent intent = new Intent(requireContext(), PortScanActivity.class);
                intent.putExtra("ip", targetIp.trim());
                intent.putExtra("auto_start", true);
                startActivity(intent);
            } catch (Exception e) {
                showToast("无法打开端口扫描");
            }
            dialog.dismiss();
        });
        copyIp.setOnClickListener(v -> copyText(device.getIp(), "IP 地址"));
        View.OnClickListener macQueryListener = v -> {
            if (device.getMacAddress() == null || device.getMacAddress().isEmpty()) {
                showToast("未获取到该设备的 MAC 地址");
                return;
            }
            try {
                Intent intent = new Intent(requireContext(), MacActivity.class);
                intent.putExtra("mac", device.getMacAddress());
                intent.putExtra("auto_query", true);
                startActivity(intent);
            } catch (Exception e) {
                showToast("无法打开 MAC 查询");
            }
            dialog.dismiss();
        };
        macQuery.setOnClickListener(macQueryListener);
        mac.setOnClickListener(macQueryListener);
        share.setOnClickListener(v -> shareDevice(device));

        dialog.show();
    }

    private String joinProtocols(DeviceInfo device) {
        StringBuilder builder = new StringBuilder();
        for (String protocol : device.getProtocolNames().keySet()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(protocol);
        }
        return builder.toString();
    }

    /**
     * 判断设备是否真的被 ONVIF 协议发现。
     *
     * <p>只看协议名，不再看 iconType：以前 SADP 设备借用了 ONVIF 图标，
     * 结果海康 SADP 设备会错误地冒出「ONVIF 调试」入口。图标只负责显示，
     * 协议名才代表这个设备真的支持哪个协议。</p>
     */
    private boolean isOnvifDevice(DeviceInfo device) {
        if (device == null) return false;
        return hasProtocol(device, "ONVIF");
    }

    /** 判断设备是否被 SADP（海康私有发现协议）发现 */
    private boolean isSadpDevice(DeviceInfo device) {
        if (device == null) return false;
        return hasProtocol(device, "SADP");
    }

    /**
     * 设备协议名里是否含指定协议（忽略大小写，容忍 "ONVIF 调试" 这类带后缀的标签）
     */
    private boolean hasProtocol(DeviceInfo device, String protocol) {
        String target = protocol.toUpperCase(java.util.Locale.ROOT);
        for (String name : device.getProtocolNames().keySet()) {
            if (name != null && name.toUpperCase(java.util.Locale.ROOT).contains(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 跳转 SADP 调试页（海康私有发现协议）。
     *
     * <p>SADP 页面暂无预填参数，直接进入让其自己扫描；这里只负责把用户
     * 引导到正确的工具页，不再错误地跳去 ONVIF。</p>
     */
    private void openSadpDebug(DeviceInfo device) {
        try {
            Intent intent = new Intent(requireContext(), SadpActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开 SADP 调试");
        }
    }

    /**
     * 跳转 ONVIF 调试页，并把已发现的设备带过去直接展示，
     * 避免在调试页重复扫描。
     */
    private void openOnvifDebug(DeviceInfo device) {
        String ip = device.getIp();
        if (ip == null || ip.trim().isEmpty()) {
            showToast("设备没有可用的 IP 地址");
            return;
        }
        try {
            Intent intent = new Intent(requireContext(), OnvifActivity.class);
            intent.putExtra(OnvifActivity.EXTRA_PREFILL_IP, ip.trim());
            intent.putExtra(OnvifActivity.EXTRA_PREFILL_PORT, 80);
            intent.putExtra(OnvifActivity.EXTRA_PREFILL_NAME, device.getTitle());
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开 ONVIF 调试");
        }
    }

    /**
     * 为设备详情生成可点击的协议标签，点击后打开「运行日志」并按该协议过滤，
     * 方便核对原始报文（例如标题截取是否正确）。
     */
    private void populateProtocolChips(
            com.google.android.material.chip.ChipGroup group, DeviceInfo device) {
        if (group == null || device == null) return;
        group.removeAllViews();
        java.util.Set<String> protocols = device.getProtocolNames().keySet();
        if (protocols.isEmpty()) return;
        android.content.Context context = requireContext();
        // 和列表里的分类/来源标签用同一套配色（浅色底 + 同色系描边 + 主题文字色），
        // 换主题色或切深色模式时一起跟随。
        int chipSurface = ThemeManager.getBadgeSurfaceColor(context);
        int chipStroke = ThemeManager.getBadgeStrokeColor(context);
        int chipText = ThemeManager.getBadgeTextColor(context);
        for (String protocol : protocols) {
            com.google.android.material.chip.Chip chip =
                    new com.google.android.material.chip.Chip(context);
            chip.setText(protocol);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setTextColor(chipText);
            // 背景必须显式设置：Chip 默认背景取自 colorSecondaryContainer，
            // 而 Theme.NetworkTool 没定义这个属性，会落到 Material 默认的淡紫色，
            // 和当前主题色对不上（表现为「弹窗里协议卡片背景没跟随主题」）。
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(chipSurface));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(chipStroke));
            chip.setChipStrokeWidth(dpToPx(1));
            chip.setCloseIconVisible(false);
            chip.setOnClickListener(v -> openProtocolLogs(protocol));
            group.addView(chip);
        }
    }

    /** 打开运行日志页并按协议过滤 */
    private void openProtocolLogs(String protocol) {
        try {
            Intent intent = new Intent(requireContext(), RuntimeLogActivity.class);
            intent.putExtra(RuntimeLogActivity.EXTRA_PROTOCOL, protocol);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开运行日志");
        }
    }

    /**
     * 展示所有协议识别到的名称。
     * 同一设备常被多种协议发现（路由器/NetBIOS/mDNS/HTTP…），
     * 名称不一定相同，这里全部列出；当前标题高亮，点击任意名称可复制。
     */
    private void populateNameList(LinearLayout container, DeviceInfo device) {
        if (container == null || device == null) return;
        container.removeAllViews();
        Map<String, String> names = device.getProtocolNames();
        if (names == null || names.isEmpty()) return;

        int onSurface = ThemeManager.getOnSurfaceColor(requireContext());
        int variant = ThemeManager.getOnSurfaceVariantColor(requireContext());
        int accent = ThemeManager.getAccessibleThemeColor(requireContext());
        String currentTitle = device.getTitle();
        String titleSource = device.getTitleSource();

        for (Map.Entry<String, String> entry : names.entrySet()) {
            String protocol = entry.getKey();
            String name = entry.getValue();
            if (name == null || name.isEmpty()) continue;

            boolean isCurrent = (titleSource != null && titleSource.equals(protocol))
                    || (currentTitle != null && currentTitle.equals(name));

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpToPx(5), 0, dpToPx(5));

            TextView protocolView = new TextView(requireContext());
            protocolView.setText(protocol);
            protocolView.setTextSize(12);
            protocolView.setTextColor(variant);
            row.addView(protocolView, new LinearLayout.LayoutParams(
                    dpToPx(76), LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView nameView = new TextView(requireContext());
            nameView.setText(isCurrent ? name + "（当前标题）" : name);
            nameView.setTextSize(14);
            nameView.setTextColor(isCurrent ? accent : onSurface);
            nameView.setMaxLines(2);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(nameView, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            android.util.TypedValue tv = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setOnClickListener(v -> copyText(name, "名称"));
            container.addView(row);
        }
    }

    private void openHttp(DeviceInfo device) {
        String ip = device.getIp();
        if (ip == null || ip.isEmpty()) {
            showToast("设备没有可用 IP");
            return;
        }
        String url = ip.contains(":") ? "http://[" + ip + "]" : "http://" + ip;
        try {
            // 按用户设置的跳转方式打开（内置浏览器或默认浏览器）
            UrlOpenHelper.open(requireContext(), url, device.getTitle());
        } catch (Exception e) {
            showToast("无法打开设备页面");
        }
    }

    private void shareDevice(DeviceInfo device) {
        StringBuilder text = new StringBuilder();
        text.append("设备：").append(device.getTitle() == null ? "未知设备" : device.getTitle());
        text.append("\nIP：").append(device.getIp());
        String protocols = joinProtocols(device);
        if (!protocols.isEmpty()) {
            text.append("\n协议：").append(protocols);
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
        try {
            startActivity(Intent.createChooser(intent, "分享设备信息"));
        } catch (Exception e) {
            showToast("无法分享");
        }
    }

    private void copyText(String text, String label) {
        if (text == null || text.isEmpty()) return;
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

    private void showScanSettingsDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_scan_settings, null);
        dialog.setContentView(dialogView);

        SharedPreferences prefs = requireContext().getSharedPreferences(
                "app_settings", Context.MODE_PRIVATE);

        // 速度/模式跟排序、筛选弹窗用同一套选项行：点一下立刻有勾，
        // 但要等点「保存」才真正写进配置，避免误触就改掉扫描参数
        RecyclerView speedList = dialogView.findViewById(R.id.speedList);
        speedList.setLayoutManager(new LinearLayoutManager(requireContext()));
        ScanOptionAdapter speedAdapter = new ScanOptionAdapter(
                buildSpeedOptions(),
                prefs.getString("scan_speed", ScanSpeedConfig.SPEED_STANDARD),
                option -> {
                });
        speedList.setAdapter(speedAdapter);

        RecyclerView modeList = dialogView.findViewById(R.id.modeList);
        modeList.setLayoutManager(new LinearLayoutManager(requireContext()));
        ScanOptionAdapter modeAdapter = new ScanOptionAdapter(
                buildModeOptions(),
                prefs.getString("scan_mode", LanScanManager.MODE_ACTIVE),
                option -> {
                });
        modeList.setAdapter(modeAdapter);

        // 标题来源优先级：越靠上越优先
        LinearLayout priorityList = dialogView.findViewById(R.id.priorityList);
        List<String> priorityOrder = TitlePriorityConfig.getOrder(requireContext());
        renderPriorityList(priorityList, priorityOrder);

        MaterialButton resetPriority = dialogView.findViewById(R.id.btnResetPriority);
        if (resetPriority != null) {
            resetPriority.setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));
            resetPriority.setOnClickListener(v -> {
                priorityOrder.clear();
                priorityOrder.addAll(TitlePriorityConfig.getDefaultOrder());
                renderPriorityList(priorityList, priorityOrder);
            });
        }

        dialogView.findViewById(R.id.btnSave).setOnClickListener(v -> {
            String newSpeed = speedAdapter.getSelectedKey();
            if (newSpeed == null) newSpeed = ScanSpeedConfig.SPEED_STANDARD;
            String newMode = modeAdapter.getSelectedKey();
            if (newMode == null) newMode = LanScanManager.MODE_ACTIVE;
            prefs.edit()
                    .putString("scan_speed", newSpeed)
                    .putString("scan_mode", newMode)
                    .apply();
            TitlePriorityConfig.saveOrder(requireContext(), priorityOrder);
            dialog.dismiss();
            showToast("设置已保存，重新扫描后生效");
        });

        MaterialButton btnSave = dialogView.findViewById(R.id.btnSave);
        int themeColor = ThemeManager.getThemeColor();
        btnSave.setBackgroundTintList(ColorStateList.valueOf(themeColor));
        btnSave.setTextColor(ThemeManager.getContrastColor(themeColor));
        // 标题固定、内容区自己滚，弹窗可拖到接近全屏；
        // 滚到底再往上拖只会继续滚内容，不会把弹窗关掉
        BottomSheetHelper.makeExpandable(dialog, 16);
        dialog.show();
    }

    /** 扫描速度的可选项。key 必须和 {@link ScanSpeedConfig} 里的常量一致。 */
    private List<ScanOptionAdapter.Option> buildSpeedOptions() {
        List<ScanOptionAdapter.Option> options = new ArrayList<>();
        options.add(new ScanOptionAdapter.Option(ScanSpeedConfig.SPEED_FAST,
                "快速", "高并发扫描，几秒扫完常见网段，日常用这个"));
        options.add(new ScanOptionAdapter.Option(ScanSpeedConfig.SPEED_STANDARD,
                "标准", "速度与路由器负载平衡，推荐"));
        options.add(new ScanOptionAdapter.Option(ScanSpeedConfig.SPEED_SLOW,
                "慢速", "低并发长超时，适合老旧路由器和弱信号环境"));
        return options;
    }

    /** 扫描模式的可选项。key 必须和 {@link LanScanManager} 里的 MODE_* 常量一致。 */
    private List<ScanOptionAdapter.Option> buildModeOptions() {
        List<ScanOptionAdapter.Option> options = new ArrayList<>();
        options.add(new ScanOptionAdapter.Option(LanScanManager.MODE_ACTIVE,
                "主动扫描", "Ping + HTTP + 多播 + NetBIOS，发现率最高"));
        options.add(new ScanOptionAdapter.Option(LanScanManager.MODE_QUIET,
                "安静扫描", "不做 HTTP / NetBIOS 探测，发现率中等"));
        options.add(new ScanOptionAdapter.Option(LanScanManager.MODE_PASSIVE,
                "被动扫描", "只监听多播和邻居表，最省电，发现率最低"));
        return options;
    }

    /**
     * 渲染标题优先级列表：序号 + 协议名 + 上移/下移。
     * 第一项用主题色强调，表示当前最优先的标题来源。
     */
    private void renderPriorityList(LinearLayout container, List<String> order) {
        if (container == null || order == null) return;
        container.removeAllViews();
        int onSurface = ThemeManager.getOnSurfaceColor(requireContext());
        int variant = ThemeManager.getOnSurfaceVariantColor(requireContext());
        int accent = ThemeManager.getAccessibleThemeColor(requireContext());
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (int i = 0; i < order.size(); i++) {
            final int index = i;
            View row = inflater.inflate(R.layout.item_priority_row, container, false);

            TextView indexView = row.findViewById(R.id.priorityIndex);
            TextView nameView = row.findViewById(R.id.priorityName);
            ImageButton up = row.findViewById(R.id.priorityUp);
            ImageButton down = row.findViewById(R.id.priorityDown);

            indexView.setText(String.valueOf(i + 1));
            indexView.setTextColor(i == 0 ? accent : variant);
            nameView.setText(order.get(i));
            nameView.setTextColor(i == 0 ? accent : onSurface);

            if (i > 0) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
                params.topMargin = dpToPx(2);
                row.setLayoutParams(params);
            }

            tintArrow(up, i > 0, accent, variant);
            up.setOnClickListener(v -> {
                if (index > 0) {
                    java.util.Collections.swap(order, index, index - 1);
                    renderPriorityList(container, order);
                }
            });

            tintArrow(down, i < order.size() - 1, accent, variant);
            down.setOnClickListener(v -> {
                if (index < order.size() - 1) {
                    java.util.Collections.swap(order, index, index + 1);
                    renderPriorityList(container, order);
                }
            });

            container.addView(row);
        }
    }

    /** 优先级列表里的上移/下移箭头：可用时主题色，不可用时淡出 */
    private void tintArrow(ImageButton arrow, boolean enabled, int accent, int variant) {
        if (arrow == null) return;
        arrow.setEnabled(enabled);
        arrow.setAlpha(enabled ? 1f : 0.35f);
        arrow.setImageTintList(ColorStateList.valueOf(enabled ? accent : variant));
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder().build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (isAdded() && !isScanning) restartScan();
                        });
                    }
                }

                @Override
                public void onLost(@NonNull Network network) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (scanManager != null) scanManager.stopScan();
                            isScanning = false;
                            scanStatus = "网络已断开";
                            unbindLanNetwork();
                            updateScanUi();
                            updateEmptyState();
                        });
                    }
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
        }
    }

    /**
     * 设备列表排序：按用户选择的方式（IP 或 名称）。
     * 同名/同 IP 时再用发现优先级和 IP 版本兜底，保证顺序稳定。
     */
    private int compareDevices(DeviceInfo a, DeviceInfo b) {
        if (SORT_NAME.equals(sortBy)) {
            String na = a.getTitle() == null ? "" : a.getTitle().trim();
            String nb = b.getTitle() == null ? "" : b.getTitle().trim();
            int cmp = na.compareToIgnoreCase(nb);
            if (cmp != 0) return cmp;
        } else {
            // 按 IP 排序：先分 IPv4/IPv6，再按 IP 数值比较
            int vA = a.getIpVersion();
            int vB = b.getIpVersion();
            if (vA != vB) return Integer.compare(vA, vB);
            int cmp = NetworkUtils.compareIp(a.getIp(), b.getIp());
            if (cmp != 0) return cmp;
        }
        // 兜底：按发现优先级（数字小的靠前，如 mDNS 在 ONVIF 之前）
        int pA = a.getDiscoveryPriority();
        int pB = b.getDiscoveryPriority();
        if (pA != pB) return Integer.compare(pA, pB);
        return NetworkUtils.compareIp(a.getIp(), b.getIp());
    }

    private void openNetworkSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                showToast("无法打开网络设置");
            }
        }
    }

    private void showToast(String text) {
        try {
            if (isAdded()) {
                Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
            }
        } catch (BadTokenException | IllegalStateException ignored) {
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onResume() {
        super.onResume();
        applyThemeColors();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateSummary();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        scanGeneration++;
        if (scanManager != null) {
            scanManager.stopScan();
        }
        unbindLanNetwork();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }
}
