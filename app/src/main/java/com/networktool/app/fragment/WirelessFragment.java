package com.networktool.app.fragment;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.networktool.app.R;
import com.networktool.app.activity.settings.ScanActivity;
import com.networktool.app.activity.tool.MacActivity;
import com.networktool.app.adapter.WifiAdapter;
import com.networktool.app.model.WifiInfoModel;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiConnector;
import com.networktool.app.util.WifiDataManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WiFi 页
 *
 * 负责真实扫描、权限申请、当前连接、频段/标准筛选和网络详情。
 */
public class WirelessFragment extends Fragment implements WifiConnector.Callback {

    private static final int REQ_WIFI_PERMISSION = 100;
    private static final long MIN_SCAN_INTERVAL = 10_000L;

    private SwipeRefreshLayout refreshLayout;
    private RecyclerView recyclerView;
    private WifiAdapter adapter;
    private final List<WifiInfoModel> wifiList = new ArrayList<>();
    private final List<WifiInfoModel> fullWifiList = new ArrayList<>();

    private View currentWifiCard;
    private ImageView currentSignalIcon;
    private TextView currentSsid;
    private TextView currentIp;
    private TextView currentMeta;
    private MaterialButton openWifiSettingsButton;
    private MaterialButton scanEntryButton;
    private MaterialButton scanButton;
    private MaterialButton frequencyFilterButton;
    private MaterialButton standardFilterButton;
    private LinearProgressIndicator scanProgress;
    private TextView wifiSummary;
    private View emptyView;
    private TextView emptyText;
    private TextView emptyHint;
    private MaterialButton emptyAction;
    private ImageView emptyIcon;

    private WifiManager wifiManager;
    private WifiDataManager wifiDataManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String frequencyFilter = "全部";
    private String standardFilter = "全部";
    private boolean scanning = false;
    private boolean permissionRequested = false;
    private WifiInfoModel pendingConnectWifi;
    private long lastScanTime = 0L;
    private boolean receiverRegistered = false;

    private final Runnable scanTimeout = () -> {
        if (scanning) {
            scanning = false;
            updateListFromResults();
            updateScanUi();
            updateEmptyState();
        }
    };

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                mainHandler.removeCallbacks(scanTimeout);
                scanning = false;
                updateListFromResults();
                updateScanUi();
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wireless, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        initManagers();
        setupRecyclerView();
        setupFilters();
        setupListeners();
        applyThemeColors();
        registerScanReceiver();
    }

    private void initViews(View view) {
        refreshLayout = view.findViewById(R.id.refreshLayout);
        recyclerView = view.findViewById(R.id.recyclerView);
        currentWifiCard = view.findViewById(R.id.currentWifiCard);
        // 标题栏已不使用信号图标（保留字段做空值保护）
        currentSignalIcon = null;
        // 主标题固定为「无线列表」，连接状态统一放在副标题
        currentSsid = null;
        currentIp = view.findViewById(R.id.currentIp);
        // 标题栏只保留两行，网段/标准等细节不再单独占一行
        currentMeta = null;
        openWifiSettingsButton = view.findViewById(R.id.openWifiSettingsButton);
        scanEntryButton = view.findViewById(R.id.scanEntryButton);
        scanButton = view.findViewById(R.id.scanButton);
        // 频段筛选已改为标题栏下方的 chip，不再有独立按钮
        frequencyFilterButton = null;
        standardFilterButton = view.findViewById(R.id.standardFilterButton);
        scanProgress = view.findViewById(R.id.scanProgress);
        wifiSummary = view.findViewById(R.id.wifiSummary);
        emptyView = view.findViewById(R.id.emptyView);
        emptyText = view.findViewById(R.id.emptyText);
        emptyHint = view.findViewById(R.id.emptyHint);
        emptyAction = view.findViewById(R.id.emptyAction);
        emptyIcon = view.findViewById(R.id.emptyIcon);
        scanProgress.setVisibility(View.GONE);
    }

    private void initManagers() {
        try {
            wifiManager = (WifiManager) requireContext()
                    .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiDataManager = WifiDataManager.getInstance(requireContext());
        } catch (Exception e) {
            wifiManager = null;
        }
    }

    private void setupRecyclerView() {
        adapter = new WifiAdapter(wifiList, this::onWifiClick, this::onWifiLongClick, wifiDataManager);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
    }

    private void setupFilters() {
        // 频段筛选改成标题栏下方的 chip（和设备页 IP 版本一致）
        com.google.android.material.chip.ChipGroup bandGroup =
                getView() == null ? null : getView().findViewById(R.id.bandChipGroup);
        if (bandGroup != null) {
            ThemeManager.styleFilterChips(requireContext(), bandGroup);
            bandGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds == null || checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                if (id == R.id.chipBand24) {
                    frequencyFilter = "2.4GHz";
                } else if (id == R.id.chipBand5) {
                    frequencyFilter = "5GHz";
                } else if (id == R.id.chipBand6) {
                    frequencyFilter = "6GHz";
                } else {
                    frequencyFilter = "全部";
                }
                applyFilter();
            });
        }
        if (standardFilterButton != null) {
            standardFilterButton.setOnClickListener(v -> showFilterPopup(false));
        }
        updateFilterButtons();
    }

    private void showFilterPopup(boolean frequency) {
        MaterialButton anchor = frequency ? frequencyFilterButton : standardFilterButton;
        if (anchor == null) return;

        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        menu.setGroupCheckable(0, true, true);

        String[] labels;
        String[] values;
        if (frequency) {
            labels = new String[]{"全部频段", "2.4 GHz", "5 GHz", "6 GHz"};
            values = new String[]{"全部", "2.4GHz", "5GHz", "6GHz"};
        } else {
            labels = new String[]{"全部标准", "Wi-Fi 7", "Wi-Fi 6", "Wi-Fi 5", "Wi-Fi 4"};
            values = new String[]{"全部", "Wi-Fi 7", "Wi-Fi 6", "Wi-Fi 5", "Wi-Fi 4"};
        }
        String current = frequency ? frequencyFilter : standardFilter;
        for (int i = 0; i < labels.length; i++) {
            MenuItem item = menu.add(0, i, i, labels[i]);
            item.setCheckable(true);
            item.setChecked(values[i].equals(current));
        }

        popup.setOnMenuItemClickListener(item -> {
            String selected = values[item.getItemId()];
            if (frequency) {
                frequencyFilter = selected;
            } else {
                standardFilter = selected;
            }
            updateFilterButtons();
            applyFilter();
            return true;
        });
        popup.show();
    }

    private void updateFilterButtons() {
        if (!isAdded()) return;
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int inactiveColor = ThemeManager.getOnSurfaceVariantColor(requireContext());
        if (frequencyFilterButton != null) {
            int color = "全部".equals(frequencyFilter) ? inactiveColor : accentColor;
            frequencyFilterButton.setIconTint(ColorStateList.valueOf(color));
            frequencyFilterButton.setTextColor(color);
            frequencyFilterButton.setContentDescription("频段筛选：" + frequencyDisplayName());
        }
        if (scanEntryButton != null) {
            scanEntryButton.setIconTint(ColorStateList.valueOf(accentColor));
            scanEntryButton.setTextColor(accentColor);
        }
        if (scanButton != null) {
            scanButton.setIconTint(ColorStateList.valueOf(accentColor));
            scanButton.setTextColor(accentColor);
        }
        if (openWifiSettingsButton != null) {
            openWifiSettingsButton.setIconTint(ColorStateList.valueOf(accentColor));
            openWifiSettingsButton.setTextColor(accentColor);
        }
        if (standardFilterButton != null) {
            int color = "全部".equals(standardFilter) ? inactiveColor : accentColor;
            standardFilterButton.setIconTint(ColorStateList.valueOf(color));
            standardFilterButton.setTextColor(color);
            standardFilterButton.setContentDescription("标准筛选：" + standardFilter);
        }
    }

    private String frequencyDisplayName() {
        if ("2.4GHz".equals(frequencyFilter)) return "2.4G";
        if ("5GHz".equals(frequencyFilter)) return "5G";
        if ("6GHz".equals(frequencyFilter)) return "6G";
        return "全部";
    }

    private boolean hasActiveFilter() {
        return !"全部".equals(frequencyFilter) || !"全部".equals(standardFilter);
    }

    private void setupListeners() {
        refreshLayout.setOnRefreshListener(() -> scanWifi(true));
        scanButton.setOnClickListener(v -> scanWifi(true));
        currentWifiCard.setOnClickListener(v -> openWifiSettings());
        openWifiSettingsButton.setOnClickListener(v -> openWifiSettings());
        scanEntryButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(requireContext(), ScanActivity.class));
            } catch (Exception e) {
                showToast("无法打开扫一扫");
            }
        });
        emptyAction.setOnClickListener(v -> {
            if (hasActiveFilter() && !fullWifiList.isEmpty()) {
                frequencyFilter = "全部";
                standardFilter = "全部";
                updateFilterButtons();
                applyFilter();
            } else if (!hasWifiPermission()) {
                if (permissionRequested) {
                    openAppSettings();
                } else {
                    requestWifiPermissions();
                }
            } else if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                openWifiSettings();
            } else if (!WifiConnector.getInstance(requireContext()).isLocationEnabled()) {
                openLocationSettings();
            } else {
                scanWifi(true);
            }
        });
    }

    private void applyThemeColors() {
        if (!isAdded()) return;
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int surfaceColor = ThemeManager.getSurfaceColor(requireContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(requireContext());

        if (currentSignalIcon != null) currentSignalIcon.setColorFilter(accentColor);

        openWifiSettingsButton.setTextColor(accentColor);
        scanEntryButton.setIconTint(ColorStateList.valueOf(accentColor));
        scanButton.setTextColor(accentColor);
        scanButton.setIconTint(ColorStateList.valueOf(accentColor));
        emptyAction.setTextColor(accentColor);
        emptyIcon.setColorFilter(onSurfaceVariant);
        updateFilterButtons();

        refreshLayout.setColorSchemeColors(accentColor);
        refreshLayout.setProgressBackgroundColorSchemeColor(surfaceColor);
        scanProgress.setIndicatorColor(accentColor);
    }

    private void checkPermissionsAndScan() {
        if (!hasWifiPermission()) {
            fullWifiList.clear();
            applyFilter();
            if (!permissionRequested) {
                requestWifiPermissions();
            } else {
                updateEmptyState();
            }
            return;
        }
        boolean shouldScan = lastScanTime == 0
                || System.currentTimeMillis() - lastScanTime > MIN_SCAN_INTERVAL;
        updateListFromResults();
        if (shouldScan && wifiManager != null && wifiManager.isWifiEnabled()) {
            scanWifi(false);
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            // 扫描需要位置权限，连接附近 WiFi 需要附近设备权限，两者都要检查
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestWifiPermissions() {
        permissionRequested = true;
        try {
            requestPermissions(requiredPermissions(), REQ_WIFI_PERMISSION);
        } catch (Exception e) {
            updateEmptyState();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WIFI_PERMISSION) {
            if (hasWifiPermission()) {
                scanWifi(false);
                WifiInfoModel target = pendingConnectWifi;
                pendingConnectWifi = null;
                if (target != null) {
                    if (WifiConnector.getInstance(requireContext()).isLocationEnabled()) {
                        showConnectDialog(target);
                    } else {
                        showLocationDialog(target);
                    }
                }
            } else {
                pendingConnectWifi = null;
                fullWifiList.clear();
                applyFilter();
                updateEmptyState();
                showToast("需要附近设备权限和位置信息权限才能连接 WiFi");
            }
        }
    }

    private void registerScanReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            ContextCompat.registerReceiver(requireContext(), scanReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterScanReceiver() {
        if (!receiverRegistered) return;
        try {
            requireContext().unregisterReceiver(scanReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private void scanWifi(boolean manual) {
        if (!isAdded()) return;
        if (refreshLayout != null) {
            refreshLayout.setRefreshing(false);
        }
        if (!hasWifiPermission()) {
            updateEmptyState();
            return;
        }
        if (wifiManager == null) {
            showToast("当前设备不支持 WiFi 扫描");
            updateEmptyState();
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            showToast("请先开启 WiFi");
            updateEmptyState();
            return;
        }
        if (!WifiConnector.getInstance(requireContext()).isLocationEnabled()) {
            if (manual) {
                showToast("请先开启系统“位置信息”开关");
            }
            updateEmptyState();
            return;
        }
        if (scanning) return;

        scanning = true;
        updateScanUi();
        boolean started = false;
        try {
            started = wifiManager.startScan();
        } catch (SecurityException e) {
            showToast("没有 WiFi 扫描权限");
        } catch (Exception ignored) {
        }

        if (!started) {
            scanning = false;
            mainHandler.removeCallbacks(scanTimeout);
            updateScanUi();
            updateEmptyState();
            if (manual) {
                showToast("扫描请求过于频繁，请稍后重试");
            }
        } else {
            mainHandler.removeCallbacks(scanTimeout);
            mainHandler.postDelayed(scanTimeout, 12000);
            if (manual) {
                showToast("正在扫描附近 WiFi…");
            }
        }
    }

    private void updateListFromResults() {
        if (!isAdded() || wifiManager == null) return;
        if (!wifiManager.isWifiEnabled()) {
            fullWifiList.clear();
            applyFilter();
            updateEmptyState();
            return;
        }
        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            updateEmptyState();
            return;
        } catch (Exception ignored) {
        }

        fullWifiList.clear();
        if (results != null) {
            for (ScanResult result : results) {
                if (result == null) continue;
                WifiInfoModel wifi = new WifiInfoModel();
                String ssid = result.SSID;
                if (ssid == null || ssid.isEmpty()) {
                    ssid = "隐藏 SSID";
                }
                wifi.setSsid(ssid);
                wifi.setBssid(result.BSSID);
                wifi.setLevel(result.level);
                wifi.setFrequency(result.frequency);
                wifi.setCapabilities(result.capabilities);
                wifi.setStandard(getWifiStandard(result));
                wifi.setChannelWidth(getChannelWidth(result));
                fullWifiList.add(wifi);
            }
            Collections.sort(fullWifiList, (a, b) -> Integer.compare(b.getLevel(), a.getLevel()));
        }
        lastScanTime = System.currentTimeMillis();
        applyFilter();
        updateCurrentConnection();
        if (adapter != null) {
            adapter.updateWifiData();
        }
    }

    private void applyFilter() {
        if (adapter == null) return;
        wifiList.clear();
        for (WifiInfoModel wifi : fullWifiList) {
            if (matchesFrequency(wifi) && matchesStandard(wifi)) {
                wifiList.add(wifi);
            }
        }
        adapter.updateData(wifiList);
        updateSummary();
        updateEmptyState();
    }

    private boolean matchesFrequency(WifiInfoModel wifi) {
        return "全部".equals(frequencyFilter) || frequencyFilter.equals(wifi.getFrequencyBand());
    }

    private boolean matchesStandard(WifiInfoModel wifi) {
        if ("全部".equals(standardFilter)) return true;
        String standard = wifi.getStandard();
        return standard != null && standardFilter.equals(standard);
    }

    private void updateScanUi() {
        if (!isAdded()) return;
        // 图标按钮只切状态，不显示文字（文字会把 44dp 按钮撑开）
        scanButton.setEnabled(!scanning);
        scanButton.setContentDescription(scanning ? "正在扫描" : "重新扫描");
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
        scanProgress.setIndeterminate(true);
        updateSummary();
    }

    private void updateSummary() {
        if (!isAdded() || wifiSummary == null) return;
        if (scanning) {
            updateTitle();
            wifiSummary.setVisibility(View.VISIBLE);
            wifiSummary.setText("正在扫描附近 WiFi…");
            return;
        }
        // 数量 + 刷新时间都在主标题上，副标题只留筛选状态
        updateTitle();

        StringBuilder text = new StringBuilder();
        if (!"全部".equals(frequencyFilter)) {
            text.append(frequencyDisplayName());
        }
        if (!"全部".equals(standardFilter)) {
            if (text.length() > 0) text.append(" · ");
            text.append(standardFilter);
        }
        if (text.length() == 0) {
            wifiSummary.setVisibility(View.GONE);
        } else {
            wifiSummary.setVisibility(View.VISIBLE);
            wifiSummary.setText(text.toString());
        }
    }

    /** 主标题带上网络数量与刷新时间，例如「无线列表 · 12 个网络 · 19:48」 */
    private void updateTitle() {
        if (!isAdded() || getView() == null) return;
        TextView title = getView().findViewById(R.id.wifiTitleText);
        if (title == null) return;
        int count = fullWifiList.isEmpty() ? wifiList.size() : fullWifiList.size();
        String base = count <= 0 ? "无线列表" : "无线列表 · " + count + " 个网络";

        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder(base);
        int start = sb.length();
        if (scanning) {
            sb.append(" · 扫描中…");
        } else if (lastScanTime > 0) {
            sb.append(" · ").append(new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(lastScanTime)));
        }
        if (sb.length() > start) {
            // 时间和「扫描中」用更小更淡的样式，不抢主标题
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.6f), start, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(
                            ThemeManager.getOnSurfaceVariantColor(requireContext())),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(sb);
    }

    private void updateEmptyState() {
        if (!isAdded() || emptyView == null) return;
        boolean empty = !scanning && wifiList.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;

        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(requireContext());
        emptyIcon.setColorFilter(onSurfaceVariant);

        if (!hasWifiPermission()) {
            emptyIcon.setImageResource(R.drawable.ic_lock);
            boolean nearbyMissing = Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED;
            boolean locationMissing = ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
            if (nearbyMissing && locationMissing) {
                emptyText.setText("需要附近设备和位置权限");
                emptyHint.setText("Android 13+ 连接 WiFi 需要“附近设备”，扫描需要位置信息");
            } else if (nearbyMissing) {
                emptyText.setText("需要附近设备权限");
                emptyHint.setText("请允许应用访问附近的 WiFi 设备");
            } else {
                emptyText.setText("需要位置信息权限");
                emptyHint.setText("请允许精确位置信息后重新扫描");
            }
            emptyAction.setText("去授权");
        } else if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            emptyIcon.setImageResource(R.drawable.ic_wifi_off);
            emptyText.setText("WiFi 已关闭");
            emptyHint.setText("请在系统设置中开启 WiFi 后重试");
            emptyAction.setText("打开设置");
        } else if (!WifiConnector.getInstance(requireContext()).isLocationEnabled()) {
            emptyIcon.setImageResource(R.drawable.ic_lock);
            emptyText.setText("系统定位未开启");
            emptyHint.setText("请开启“位置信息”和“WiFi 扫描”后重试");
            emptyAction.setText("去开启");
        } else if (hasActiveFilter() && !fullWifiList.isEmpty()) {
            emptyIcon.setImageResource(R.drawable.ic_filter);
            emptyText.setText("没有符合筛选条件的网络");
            emptyHint.setText("尝试切换频段或标准");
            emptyAction.setText("清除筛选");
        } else {
            emptyIcon.setImageResource(R.drawable.ic_empty);
            emptyText.setText("未发现 WiFi 网络");
            emptyHint.setText("请靠近路由器或稍后重新扫描");
            emptyAction.setText("重新扫描");
        }
    }

    private void updateCurrentConnection() {
        if (!isAdded()) return;
        int themeColor = ThemeManager.getAccessibleThemeColor(requireContext());
        if (wifiManager == null) {
            currentIp.setText("WiFi 不可用 · 当前设备不支持 WiFi 管理");
            if (currentMeta != null) currentMeta.setText("--");
            setCurrentSignalIcon(R.drawable.ic_wifi_off,
                    ThemeManager.getOnSurfaceVariantColor(requireContext()), "WiFi 不可用");
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            currentIp.setText("WiFi 已关闭");
            if (currentMeta != null) currentMeta.setText("开启后可扫描附近网络");
            setCurrentSignalIcon(R.drawable.ic_wifi_off,
                    ThemeManager.getOnSurfaceVariantColor(requireContext()), "WiFi 已关闭");
            return;
        }

        WifiInfo info = null;
        try {
            info = wifiManager.getConnectionInfo();
        } catch (Exception ignored) {
        }
        if (info == null) {
            showDisconnected(themeColor);
            return;
        }

        String rawSsid;
        try {
            rawSsid = info.getSSID();
        } catch (Exception e) {
            rawSsid = null;
        }
        String ssid = sanitizeSsid(rawSsid);
        if ("未知网络".equals(ssid)) {
            showDisconnected(themeColor);
            return;
        }

        String ip = intToIp(info.getIpAddress());
        if (ip == null) {
            ip = NetworkUtils.getLocalIpAddress();
        }
        if (ip == null || ip.isEmpty()) ip = "--";

        int rssi = info.getRssi();
        int frequency = info.getFrequency();
        String standard = getConnectionStandard(info);
        String band = getFrequencyBand(frequency);

        currentIp.setText("已连接 " + ssid + " · " + ip);
        if (currentMeta != null) currentMeta.setText(band + " · " + standard + " · " + rssi + " dBm");
        setCurrentSignalIcon(signalIconRes(rssi), themeColor, "当前 WiFi 信号 " + rssi + " dBm");
    }

    private void showDisconnected(int themeColor) {
        currentIp.setText("未连接 WiFi");
        if (currentMeta != null) currentMeta.setText("扫描附近网络或连接已知网络");
        setCurrentSignalIcon(R.drawable.ic_signal_wifi_outline_0, themeColor, "WiFi 已开启，未连接网络");
    }

    /** 标题栏图标已在布局中移除，这里做空值保护 */
    private void setCurrentSignalIcon(int resId, int color, String description) {
        if (currentSignalIcon == null) return;
        currentSignalIcon.setImageResource(resId);
        currentSignalIcon.setColorFilter(color);
        currentSignalIcon.setContentDescription(description);
    }

    private String getWifiStandard(ScanResult result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return wifiStandardName(result.getWifiStandard());
            } catch (Exception ignored) {
            }
        }
        String caps = result.capabilities;
        if (caps != null) {
            if (caps.contains("11be")) return "Wi-Fi 7";
            if (caps.contains("11ax")) return "Wi-Fi 6";
            if (caps.contains("11ac")) return "Wi-Fi 5";
            if (caps.contains("11n")) return "Wi-Fi 4";
        }
        return "Wi-Fi 4";
    }

    private String getConnectionStandard(WifiInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return wifiStandardName(info.getWifiStandard());
            } catch (Exception ignored) {
            }
        }
        return "Wi-Fi";
    }

    private String wifiStandardName(int standard) {
        switch (standard) {
            case 1:
            case 3:
                return "Wi-Fi 3";
            case 2:
                return "Wi-Fi 1";
            case 4:
                return "Wi-Fi 4";
            case 5:
                return "Wi-Fi 5";
            case 6:
                return "Wi-Fi 6";
            case 7:
                return "802.11ad";
            case 8:
                return "Wi-Fi 7";
            default:
                return "Wi-Fi";
        }
    }

    private String getChannelWidth(ScanResult result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                switch (result.channelWidth) {
                    case ScanResult.CHANNEL_WIDTH_20MHZ:
                        return "20 MHz";
                    case ScanResult.CHANNEL_WIDTH_40MHZ:
                        return "40 MHz";
                    case ScanResult.CHANNEL_WIDTH_80MHZ:
                        return "80 MHz";
                    case ScanResult.CHANNEL_WIDTH_160MHZ:
                        return "160 MHz";
                    case 5: // ScanResult.CHANNEL_WIDTH_320MHZ (API 33+)
                        return "320 MHz";
                    default:
                        return "带宽未知";
                }
            } catch (Exception ignored) {
            }
        }
        return "带宽未知";
    }

    private int signalIconRes(int rssi) {
        if (rssi >= -50) return R.drawable.ic_signal_wifi_outline_4;
        if (rssi >= -70) return R.drawable.ic_signal_wifi_outline_3;
        if (rssi >= -85) return R.drawable.ic_signal_wifi_outline_2;
        if (rssi >= -100) return R.drawable.ic_signal_wifi_outline_1;
        return R.drawable.ic_signal_wifi_outline_0;
    }

    private String getFrequencyBand(int frequency) {
        if (frequency >= 2400 && frequency < 2500) return "2.4 GHz";
        if (frequency >= 5000 && frequency < 5900) return "5 GHz";
        if (frequency >= 5900 && frequency < 7125) return "6 GHz";
        return "未知频段";
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

    private String intToIp(int ip) {
        if (ip == 0) return null;
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private String formatFrequency(int frequency) {
        if (frequency <= 0) return "--";
        return String.format(Locale.CHINA, "%.1f GHz (%d MHz)", frequency / 1000.0, frequency);
    }

    private void onWifiClick(WifiInfoModel wifi) {
        if (wifi == null || !isAdded()) return;
        String ssid = wifi.getSsid();
        if (isConnectedTo(ssid)) {
            showToast("已连接到 " + ssid);
            return;
        }
        if (!hasWifiPermission()) {
            pendingConnectWifi = wifi;
            showToast("请先授予“附近设备”和“位置信息”权限");
            requestWifiPermissions();
            return;
        }
        if (!WifiConnector.getInstance(requireContext()).isLocationEnabled()) {
            showLocationDialog(wifi);
            return;
        }
        showConnectDialog(wifi);
    }

    private void showLocationDialog(WifiInfoModel wifi) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("需要开启系统定位")
                .setMessage("WiFi 扫描和部分机型的连接功能依赖系统“位置信息”开关。\n\n"
                        + "部分手机还需要在定位设置中开启“WiFi 扫描”。开启后返回重试即可。")
                .setPositiveButton("去开启", (dialog, which) -> openLocationSettings())
                .setNeutralButton("仍然尝试", (dialog, which) -> showConnectDialog(wifi))
                .setNegativeButton("取消", null)
                .show();
    }

    private void onWifiLongClick(WifiInfoModel wifi) {
        showWifiDetails(wifi);
    }

    /**
     * 点击网络：输入密码并调用系统 API 连接
     */
    private void showConnectDialog(WifiInfoModel wifi) {
        if (wifi == null || !isAdded()) return;
        String ssid = wifi.getSsid();
        if (ssid == null || ssid.isEmpty() || "隐藏 SSID".equals(ssid)) {
            showToast("隐藏网络请使用系统 WiFi 设置连接");
            openWifiSettings();
            return;
        }

        int securityType = WifiConnector.detectSecurityType(wifi.getCapabilities());
        if (!WifiConnector.isSupported(securityType)) {
            if (securityType == WifiConnector.SECURITY_WEP
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                showToast("Android 10+ 不支持应用内连接 WEP 网络，请使用系统设置");
            } else {
                showToast("该网络加密方式暂不支持应用内连接，请使用系统设置");
            }
            openWifiSettings();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottomsheet_wifi_connect, null);
        dialog.setContentView(view);

        TextView ssidView = view.findViewById(R.id.connectSsid);
        TextView securityView = view.findViewById(R.id.connectSecurity);
        TextInputLayout passwordLayout = view.findViewById(R.id.passwordLayout);
        TextInputEditText passwordEdit = view.findViewById(R.id.passwordEdit);
        TextView savedPasswordHint = view.findViewById(R.id.savedPasswordHint);
        TextView connectHint = view.findViewById(R.id.connectHint);
        MaterialButton connectButton = view.findViewById(R.id.connectButton);
        MaterialButton cancelButton = view.findViewById(R.id.cancelButton);

        ssidView.setText(ssid);
        securityView.setText(WifiConnector.securityLabel(securityType));

        boolean needsPassword = WifiConnector.requiresPassword(securityType);
        passwordLayout.setVisibility(needsPassword ? View.VISIBLE : View.GONE);
        if (needsPassword) {
            List<String> savedPasswords = getSavedPasswords(ssid);
            if (!savedPasswords.isEmpty()) {
                passwordEdit.setText(savedPasswords.get(0));
                savedPasswordHint.setVisibility(View.VISIBLE);
                savedPasswordHint.setText(savedPasswords.size() == 1
                        ? "已填入已保存密码，可直接修改"
                        : "已填入 1 个已保存密码，可修改或使用其他密码");
            }
            connectHint.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "连接时 Android 会弹出系统确认窗口"
                    : "将调用系统 WiFi API 发起连接");
        } else {
            connectHint.setText("开放网络，无需密码");
        }

        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        connectButton.setBackgroundTintList(ColorStateList.valueOf(themeColor));
        connectButton.setTextColor(contrastColor);
        cancelButton.setTextColor(accentColor);

        connectButton.setOnClickListener(v -> {
            String password = passwordEdit.getText() == null
                    ? "" : passwordEdit.getText().toString();
            if (needsPassword) {
                String error = WifiConnector.validatePassword(securityType, password);
                if (error != null) {
                    passwordLayout.setError(error);
                    return;
                }
            }
            passwordLayout.setError(null);
            hideKeyboard(view);
            dialog.dismiss();

            WifiConnector.getInstance(requireContext()).setCallback(WirelessFragment.this);
            WifiConnector.getInstance(requireContext()).connect(
                    ssid, wifi.getCapabilities(), password, WirelessFragment.this);
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private List<String> getSavedPasswords(String ssid) {
        List<String> result = new ArrayList<>();
        if (wifiDataManager == null || ssid == null) return result;
        try {
            Map<String, List<String>> allData = wifiDataManager.getAllWifiData();
            if (allData != null) {
                List<String> passwords = allData.get(ssid);
                if (passwords != null) {
                    result.addAll(passwords);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private boolean isConnectedTo(String ssid) {
        if (wifiManager == null || ssid == null) return false;
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return false;
            String current = sanitizeSsid(info.getSSID());
            return ssid.equals(current);
        } catch (Exception e) {
            return false;
        }
    }

    private void hideKeyboard(View view) {
        try {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && view != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onConnecting(String ssid) {
        if (!isAdded()) return;
        showToast("正在连接 " + ssid + "…");
    }

    @Override
    public void onConnected(String ssid) {
        if (!isAdded()) return;
        showToast("已连接到 " + ssid);
        mainHandler.postDelayed(() -> {
            if (isAdded()) {
                updateCurrentConnection();
                updateListFromResults();
            }
        }, 1000);
    }

    @Override
    public void onFailed(String ssid, String message) {
        if (!isAdded()) return;
        String reason = message == null ? "连接失败" : message;
        if (reason.contains("权限") || reason.contains("定位")) {
            // 底部弹窗展示失败原因与权限状态
            showWifiFailSheet(reason);
        } else {
            showToast(reason);
        }
        updateCurrentConnection();
    }

    /** 连接失败底部弹窗：展示原因、权限诊断，并提供跳转入口 */
    private void showWifiFailSheet(String reason) {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
            View view = LayoutInflater.from(requireContext())
                    .inflate(R.layout.bottomsheet_wifi_fail, null);
            dialog.setContentView(view);

            TextView reasonText = view.findViewById(R.id.failReason);
            TextView diagnostics = view.findViewById(R.id.failDiagnostics);
            MaterialButton openPermission = view.findViewById(R.id.openPermissionButton);
            MaterialButton openWifiSettings = view.findViewById(R.id.openWifiSettingsButton);
            MaterialButton closeButton = view.findViewById(R.id.closeButton);

            reasonText.setText(reason);
            diagnostics.setText("当前权限状态：\n" + permissionDiagnostics());

            openPermission.setOnClickListener(v -> {
                dialog.dismiss();
                openAppSettings();
            });
            openWifiSettings.setOnClickListener(v -> {
                dialog.dismiss();
                openWifiSettings();
            });
            closeButton.setOnClickListener(v -> dialog.dismiss());

            dialog.show();
        } catch (Exception e) {
            // 兜底：底部弹窗失败时退回 Toast
            showToast(reason);
        }
    }

    private String permissionDiagnostics() {
        StringBuilder builder = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 33) {
            builder.append("附近设备：")
                    .append(ContextCompat.checkSelfPermission(requireContext(),
                            Manifest.permission.NEARBY_WIFI_DEVICES)
                            == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权")
                    .append('\n');
        }
        builder.append("位置信息：")
                .append(ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权")
                .append('\n');
        builder.append("系统定位开关：")
                .append(WifiConnector.getInstance(requireContext()).isLocationEnabled()
                        ? "已开启" : "未开启")
                .append('\n')
                .append("网络状态权限：")
                .append(ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.CHANGE_NETWORK_STATE)
                        == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权");
        return builder.toString();
    }

    private void showWifiDetails(WifiInfoModel wifi) {
        if (wifi == null || !isAdded()) return;
        showWifiDetailsDialog(wifi, getSavedPasswords(wifi.getSsid()));
    }

    private void showWifiDetailsDialog(WifiInfoModel wifi, List<String> passwords) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottomsheet_wifi_details, null);
        dialog.setContentView(view);

        TextView ssidText = view.findViewById(R.id.ssidText);
        TextView bssidText = view.findViewById(R.id.bssidText);
        TextView levelText = view.findViewById(R.id.levelText);
        TextView frequencyText = view.findViewById(R.id.frequencyText);
        TextView standardText = view.findViewById(R.id.standardText);
        TextView capabilitiesText = view.findViewById(R.id.capabilitiesText);
        View passwordSection = view.findViewById(R.id.passwordSection);
        TextView passwordInfoText = view.findViewById(R.id.passwordInfoText);
        View passwordScroll = view.findViewById(R.id.passwordScroll);
        LinearLayout passwordListContainer = view.findViewById(R.id.passwordListContainer);
        MaterialButton copyPasswordButton = view.findViewById(R.id.copyPasswordButton);
        MaterialButton openSettings = view.findViewById(R.id.openWifiSettingsButton);
        MaterialButton queryBssidButton = view.findViewById(R.id.queryBssidButton);

        ssidText.setText(wifi.getSsid() == null ? "隐藏 SSID" : wifi.getSsid());
        bssidText.setText("BSSID " + (wifi.getBssid() == null ? "--" : wifi.getBssid()));
        levelText.setText("信号强度 " + wifi.getLevel() + " dBm");
        frequencyText.setText("频率 " + formatFrequency(wifi.getFrequency()));
        standardText.setText("标准 " + (wifi.getStandard() == null ? "--" : wifi.getStandard()));
        capabilitiesText.setText("加密 " + (wifi.getCapabilities() == null
                ? "--" : wifi.getCapabilities()));

        passwordSection.setVisibility(View.VISIBLE);
        passwordListContainer.removeAllViews();
        if (passwords == null || passwords.isEmpty()) {
            passwordInfoText.setText("未保存该 WiFi 的密码");
            copyPasswordButton.setVisibility(View.GONE);
            passwordScroll.setVisibility(View.GONE);
        } else {
            passwordInfoText.setText(passwords.size() == 1
                    ? "已保存 1 个密码" : "已保存 " + passwords.size() + " 个密码");
            copyPasswordButton.setVisibility(View.VISIBLE);
            copyPasswordButton.setText("全部复制");
            copyPasswordButton.setOnClickListener(v -> {
                copyToClipboard(android.text.TextUtils.join("\n", passwords),
                        wifi.getSsid() + " 的 WiFi 密码");
                showToast("已复制全部密码");
            });

            LayoutInflater inflater = LayoutInflater.from(requireContext());
            float density = getResources().getDisplayMetrics().density;
            int rowHeight = Math.round(48 * density);
            int maxHeight = Math.round(220 * density);
            passwordScroll.getLayoutParams().height = Math.min(
                    rowHeight * passwords.size(), maxHeight);
            passwordScroll.setVisibility(View.VISIBLE);

            for (String password : passwords) {
                View row = inflater.inflate(R.layout.item_saved_password,
                        passwordListContainer, false);
                TextView passwordText = row.findViewById(R.id.passwordText);
                MaterialButton copyButton = row.findViewById(R.id.copyPasswordButton);
                passwordText.setText(password == null || password.isEmpty()
                        ? "空密码" : password);
                copyButton.setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));
                copyButton.setOnClickListener(v -> {
                    copyToClipboard(password == null ? "" : password,
                            wifi.getSsid() + " 的 WiFi 密码");
                    showToast("已复制密码");
                });
                passwordListContainer.addView(row);
            }
        }

        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        openSettings.setBackgroundTintList(ColorStateList.valueOf(themeColor));
        openSettings.setTextColor(contrastColor);
        openSettings.setIconTint(ColorStateList.valueOf(contrastColor));
        copyPasswordButton.setTextColor(accentColor);
        queryBssidButton.setTextColor(accentColor);
        queryBssidButton.setIconTint(ColorStateList.valueOf(accentColor));
        bssidText.setTextColor(accentColor);
        bssidText.setClickable(true);
        bssidText.setFocusable(true);

        View.OnClickListener macQueryListener = v -> {
            openMacQuery(wifi.getBssid());
            dialog.dismiss();
        };
        queryBssidButton.setOnClickListener(macQueryListener);
        bssidText.setOnClickListener(macQueryListener);

        openSettings.setOnClickListener(v -> {
            openWifiSettings();
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * 从 WiFi BSSID / 设备 MAC 跳转到厂商查询，并自动开始查询。
     */
    private void openMacQuery(String mac) {
        if (mac == null || mac.trim().isEmpty() || "--".equals(mac.trim())) {
            showToast("当前网络没有可用的 MAC 地址");
            return;
        }
        try {
            Intent intent = new Intent(requireContext(), MacActivity.class);
            intent.putExtra("mac", mac.trim());
            intent.putExtra("auto_query", true);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开 MAC 查询");
        }
    }

    private void copyToClipboard(String text, String label) {
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            }
        } catch (Exception ignored) {
        }
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            showToast("无法打开 WiFi 设置");
        }
    }

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception e) {
            showToast("无法打开定位设置");
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开应用设置");
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

    @Override
    public void onResume() {
        super.onResume();
        applyThemeColors();
        updateCurrentConnection();
        checkPermissionsAndScan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unregisterScanReceiver();
        mainHandler.removeCallbacks(scanTimeout);
        Context context = getContext();
        if (context != null) {
            WifiConnector.getInstance(context).setCallback(null);
        }
    }
}
