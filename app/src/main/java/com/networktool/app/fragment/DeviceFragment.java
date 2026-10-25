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
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager.BadTokenException;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.networktool.app.R;
import com.networktool.app.activity.settings.RuntimeLogActivity;
import com.networktool.app.activity.tool.MacActivity;
import com.networktool.app.activity.tool.OnvifActivity;
import com.networktool.app.activity.tool.PingActivity;
import com.networktool.app.activity.tool.PortScanActivity;
import com.networktool.app.adapter.DeviceAdapter;
import com.networktool.app.model.DeviceInfo;
import com.networktool.app.util.LanScanManager;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ScanSpeedConfig;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.UrlOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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
    private ChipGroup ipVersionGroup;
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
    private boolean isScanning = false;
    private String scanStatus = "";
    private String scanLocalIp;
    private String scanRangeLabel = "";

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private LanScanManager scanManager;
    private int scanGeneration = 0;
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
        ipVersionGroup = view.findViewById(R.id.ipVersionGroup);
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

    private void setupFilterChips() {
        ipVersionGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipIpv4) {
                ipVersionFilter = DeviceInfo.IPV4;
            } else if (id == R.id.chipIpv6) {
                ipVersionFilter = DeviceInfo.IPV6;
            } else {
                ipVersionFilter = 0;
            }
            applyFilter();
        });
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
                if (ipVersionGroup != null) {
                    ipVersionGroup.check(R.id.chipAll);
                }
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
        filterButton.setIconTint(ColorStateList.valueOf(onSurfaceVariant));
        sortButton.setIconTint(ColorStateList.valueOf(onSurfaceVariant));
        settingsButton.setIconTint(ColorStateList.valueOf(onSurfaceVariant));
        emptyAction.setTextColor(accentColor);
        emptyIcon.setColorFilter(onSurfaceVariant);

        styleChipGroup(ipVersionGroup, requireContext());
        refreshLayout.setColorSchemeColors(accentColor);
        refreshLayout.setProgressBackgroundColorSchemeColor(
                ThemeManager.getSurfaceColor(requireContext()));
        if (scanProgress != null) {
            scanProgress.setIndicatorColor(accentColor);
            scanProgress.setTrackColor(ThemeManager.getChipSurfaceColor(requireContext()));
        }
    }

    private void styleChipGroup(ChipGroup group, Context context) {
        if (group == null) return;
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        int uncheckedBackground = ThemeManager.getChipSurfaceColor(context);
        int uncheckedText = ThemeManager.getOnSurfaceVariantColor(context);
        int outline = ThemeManager.getOutlineVariantColor(context);

        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        ColorStateList background = new ColorStateList(states,
                new int[]{themeColor, uncheckedBackground});
        ColorStateList text = new ColorStateList(states,
                new int[]{contrastColor, uncheckedText});
        ColorStateList stroke = new ColorStateList(states,
                new int[]{themeColor, outline});

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Chip) {
                Chip chip = (Chip) child;
                chip.setChipBackgroundColor(background);
                chip.setTextColor(text);
                chip.setChipStrokeColor(stroke);
                chip.setChipStrokeWidth(dpToPx(1));
                chip.setCheckedIconTint(ColorStateList.valueOf(contrastColor));
            }
        }
    }

    /** 排序方式弹窗：按 IP / 按名称，选择后持久化保存 */
    private void showSortDialog() {
        String[] options = {"按 IP 排序", "按名称排序"};
        int checked = SORT_NAME.equals(sortBy) ? 1 : 0;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("排序方式")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    sortBy = which == 1 ? SORT_NAME : SORT_IP;
                    saveSortPreference();
                    applyFilter();
                    dialog.dismiss();
                    showToast(which == 1 ? "已按名称排序" : "已按 IP 排序");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showProtocolFilterDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_scan_filter, null);
        dialog.setContentView(view);

        ChipGroup group = view.findViewById(R.id.protocolChipGroup);
        MaterialButton clearButton = view.findViewById(R.id.clearFilterButton);

        group.check(chipIdForProtocol(protocolFilter));
        styleChipGroup(group, requireContext());

        group.setOnCheckedStateChangeListener((chipGroup, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            protocolFilter = protocolForChip(checkedIds.get(0));
            updateFilterButton();
            applyFilter();
            dialog.dismiss();
        });
        clearButton.setOnClickListener(v -> {
            protocolFilter = null;
            updateFilterButton();
            applyFilter();
            dialog.dismiss();
        });
        dialog.show();
    }

    private int chipIdForProtocol(String protocol) {
        if (protocol == null) return R.id.protoAll;
        switch (protocol) {
            case "ONVIF":
                return R.id.protoOnvif;
            case "SADP":
                return R.id.protoSadp;
            case "路由器":
                return R.id.protoRouter;
            case "SSDP":
                return R.id.protoSsdp;
            case "mDNS":
                return R.id.protoMdns;
            case "NetBIOS":
                return R.id.protoNetbios;
            case "ARP表":
                return R.id.protoArp;
            case "UDP广播":
                return R.id.protoUdp;
            case "HTTP":
                return R.id.protoHttp;
            case "Ping探测":
                return R.id.protoPing;
            case "IPv6":
                return R.id.protoIpv6;
            case "端口探测":
                return R.id.protoService;
            default:
                return R.id.protoAll;
        }
    }

    private String protocolForChip(int chipId) {
        if (chipId == R.id.protoOnvif) return "ONVIF";
        if (chipId == R.id.protoSadp) return "SADP";
        if (chipId == R.id.protoRouter) return "路由器";
        if (chipId == R.id.protoSsdp) return "SSDP";
        if (chipId == R.id.protoMdns) return "mDNS";
        if (chipId == R.id.protoNetbios) return "NetBIOS";
        if (chipId == R.id.protoArp) return "ARP表";
        if (chipId == R.id.protoUdp) return "UDP广播";
        if (chipId == R.id.protoHttp) return "HTTP";
        if (chipId == R.id.protoPing) return "Ping探测";
        if (chipId == R.id.protoIpv6) return "IPv6";
        if (chipId == R.id.protoService) return "端口探测";
        return null;
    }

    private void updateFilterButton() {
        filterButton.setContentDescription(protocolFilter == null
                ? "筛选协议" : "筛选协议：" + protocolFilter);
    }

    private void restartScan() {
        scanGeneration++;
        if (scanManager != null) {
            scanManager.stopScan();
        }
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
        scanStatus = "正在发现局域网设备…";
        updateScanUi();

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
            searchRouterBackend(gateway);
            addLocalDevice();
            startLanScan(gateway);
        } else if (scanLocalIp != null && !scanLocalIp.isEmpty()
                && !"0.0.0.0".equals(scanLocalIp)) {
            String inferredGateway = inferGatewayFromLocalIp(scanLocalIp);
            addLocalDevice();
            startLanScan(inferredGateway);
        } else {
            isScanning = false;
            scanStatus = "未连接到网络";
            updateScanUi();
            updateEmptyState();
        }
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

    private void startLanScan(String gateway) {
        try {
            final int generation = ++scanGeneration;
            scanManager = new LanScanManager(requireContext(), gateway, scanLocalIp,
                    new LanScanManager.OnScanListener() {
                @Override
                public void onDeviceFound(DeviceInfo device) {
                    if (!isAdded() || device == null || generation != scanGeneration) return;
                    for (DeviceInfo existing : deviceList) {
                        if (existing.getIp() != null && existing.getIp().equals(device.getIp())) {
                            return;
                        }
                    }
                    deviceList.add(device);
                    applyFilter();
                }

                @Override
                public void onDeviceUpdated(DeviceInfo device) {
                    if (!isAdded() || device == null || generation != scanGeneration) return;
                    for (int i = 0; i < deviceList.size(); i++) {
                        if (deviceList.get(i).getIp() != null
                                && deviceList.get(i).getIp().equals(device.getIp())) {
                            deviceList.set(i, device);
                            break;
                        }
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
                    refreshLayout.setRefreshing(false);
                    updateScanUi();
                    updateEmptyState();
                    showToast(scanStatus);
                }
            });
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

    private void addGatewayDevice(String gateway) {
        DeviceInfo device = new DeviceInfo();
        device.setIp(gateway);
        device.setTitle("路由器网关");
        device.setIconType(DeviceInfo.ICON_ROUTER);
        device.addProtocolName("路由器", device.getTitle());
        deviceList.add(device);
        applyFilter();
    }

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
        device.setTitle("本机设备 (" + getLocalHostname() + ")");
        device.setIconType(DeviceInfo.ICON_COMPUTER);
        device.addProtocolName("ARP表", device.getTitle());
        deviceList.add(device);
        applyFilter();
    }

    private String getLocalHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private void searchRouterBackend(String currentGateway) {
        new Thread(() -> {
            for (String[] router : routerDomains) {
                try {
                    java.net.InetAddress address = java.net.InetAddress.getByName(router[1]);
                    String ip = address.getHostAddress();
                    if (isPrivateIP(ip) && !ip.equals(currentGateway)) {
                        final String finalIp = ip;
                        final String routerLink = router[1];
                        final String brand = router[0];
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            for (DeviceInfo device : deviceList) {
                                if (finalIp.equals(device.getIp())) {
                                    if (device.getDescription() == null || device.getDescription().isEmpty()) {
                                        device.setDescription("http://" + routerLink);
                                    }
                                    device.addProtocolName("路由器", brand + " - " + routerLink);
                                    applyFilter();
                                    return;
                                }
                            }
                            DeviceInfo device = new DeviceInfo();
                            device.setIp(finalIp);
                            device.setTitle("【路由器】" + brand);
                            device.setDescription("http://" + routerLink);
                            device.setIconType(DeviceInfo.ICON_ROUTER);
                            device.addProtocolName("路由器", brand + " - " + routerLink);
                            deviceList.add(device);
                            applyFilter();
                        });
                    }
                } catch (Exception ignored) {
                }
            }
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

        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        openHttp.setBackgroundTintList(ColorStateList.valueOf(themeColor));
        openHttp.setTextColor(contrastColor);
        openHttp.setIconTint(ColorStateList.valueOf(contrastColor));
        ping.setTextColor(accentColor);
        ping.setIconTint(ColorStateList.valueOf(accentColor));
        portScan.setTextColor(accentColor);
        portScan.setIconTint(ColorStateList.valueOf(accentColor));
        copyIp.setTextColor(accentColor);
        copyIp.setIconTint(ColorStateList.valueOf(accentColor));
        mac.setTextColor(accentColor);
        mac.setClickable(true);
        mac.setFocusable(true);
        macQuery.setTextColor(accentColor);
        macQuery.setIconTint(ColorStateList.valueOf(accentColor));
        share.setTextColor(accentColor);
        share.setIconTint(ColorStateList.valueOf(accentColor));

        // ONVIF 调试入口：仅当设备含 ONVIF 协议时显示，点击直接带设备跳转（无需重扫）
        if (isOnvifDevice(device)) {
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

    /** 判断设备是否被 ONVIF 协议发现（图标或协议名任一匹配） */
    private boolean isOnvifDevice(DeviceInfo device) {
        if (device == null) return false;
        if (device.getIconType() == DeviceInfo.ICON_ONVIF) return true;
        for (String p : device.getProtocolNames().keySet()) {
            if (p != null && p.toUpperCase().contains("ONVIF")) return true;
        }
        return false;
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
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        for (String protocol : protocols) {
            com.google.android.material.chip.Chip chip =
                    new com.google.android.material.chip.Chip(requireContext());
            chip.setText(protocol);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setTextColor(accentColor);
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(accentColor));
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

        RadioGroup speedGroup = dialogView.findViewById(R.id.speedGroup);
        RadioGroup modeGroup = dialogView.findViewById(R.id.modeGroup);

        SharedPreferences prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String currentSpeed = prefs.getString("scan_speed", ScanSpeedConfig.SPEED_STANDARD);
        String currentMode = prefs.getString("scan_mode", LanScanManager.MODE_ACTIVE);

        switch (currentSpeed) {
            case ScanSpeedConfig.SPEED_FAST:
                speedGroup.check(R.id.speedFast);
                break;
            case ScanSpeedConfig.SPEED_SLOW:
                speedGroup.check(R.id.speedSlow);
                break;
            default:
                speedGroup.check(R.id.speedStandard);
                break;
        }
        switch (currentMode) {
            case LanScanManager.MODE_PASSIVE:
                modeGroup.check(R.id.modePassive);
                break;
            case LanScanManager.MODE_QUIET:
                modeGroup.check(R.id.modeQuiet);
                break;
            default:
                modeGroup.check(R.id.modeActive);
                break;
        }

        applyRadioTheme(speedGroup);
        applyRadioTheme(modeGroup);

        dialogView.findViewById(R.id.btnSave).setOnClickListener(v -> {
            int speedId = speedGroup.getCheckedRadioButtonId();
            String newSpeed = ScanSpeedConfig.SPEED_STANDARD;
            if (speedId == R.id.speedFast) newSpeed = ScanSpeedConfig.SPEED_FAST;
            else if (speedId == R.id.speedSlow) newSpeed = ScanSpeedConfig.SPEED_SLOW;
            prefs.edit().putString("scan_speed", newSpeed).apply();

            int modeId = modeGroup.getCheckedRadioButtonId();
            String newMode = LanScanManager.MODE_ACTIVE;
            if (modeId == R.id.modePassive) newMode = LanScanManager.MODE_PASSIVE;
            else if (modeId == R.id.modeQuiet) newMode = LanScanManager.MODE_QUIET;
            prefs.edit().putString("scan_mode", newMode).apply();

            dialog.dismiss();
            showToast("设置已保存，刷新后生效");
        });

        MaterialButton btnSave = dialogView.findViewById(R.id.btnSave);
        int themeColor = ThemeManager.getThemeColor();
        btnSave.setBackgroundTintList(ColorStateList.valueOf(themeColor));
        btnSave.setTextColor(ThemeManager.getContrastColor(themeColor));
        dialog.show();
    }

    private void applyRadioTheme(RadioGroup group) {
        if (group == null) return;
        int themeColor = ThemeManager.getThemeColor();
        int unchecked = ThemeManager.getOnSurfaceVariantColor(requireContext());
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        ColorStateList tint = new ColorStateList(states, new int[]{themeColor, unchecked});
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton) {
                ((RadioButton) child).setButtonTintList(tint);
            }
        }
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
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }
}
