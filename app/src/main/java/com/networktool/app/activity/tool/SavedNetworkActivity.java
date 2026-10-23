package com.networktool.app.activity.tool;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.adapter.SavedNetworkAdapter;
import com.networktool.app.model.SavedNetworkModel;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 已保存网络Activity
 *
 * 显示已保存的WiFi网络列表，支持查看详情和删除网络
 */
public class SavedNetworkActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private TextView countText;

    private WifiManager wifiManager;
    private SavedNetworkAdapter adapter;
    private List<SavedNetworkModel> networkList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_network);

        setupStatusBar();
        setupToolbar();

        initManagers();
        initViews();
        loadData();
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    /**
     * 设置工具栏主题
     */
    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initManagers() {
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        countText = findViewById(R.id.countText);
        countText.setTextColor(ThemeManager.getAccessibleThemeColor(this));

        adapter = new SavedNetworkAdapter(networkList,
                this::onNetworkClick,
                this::onNetworkLongClick);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void loadData() {
        // Android 10+ 需要位置权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 100);
                return;
            }
        }
        
        doLoadData();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doLoadData();
            } else {
                Toast.makeText(this, "需要位置权限才能读取WiFi配置", Toast.LENGTH_LONG).show();
                updateViewVisibility();
            }
        }
    }
    
    private void doLoadData() {
        networkList.clear();

        // 获取当前连接的WiFi信息
        WifiInfo currentInfo = wifiManager.getConnectionInfo();
        String currentSsid = null;
        if (currentInfo != null) {
            currentSsid = currentInfo.getSSID();
            if (currentSsid != null && currentSsid.startsWith("\"") && currentSsid.endsWith("\"")) {
                currentSsid = currentSsid.substring(1, currentSsid.length() - 1);
            }
        }

        // 尝试获取已配置的网络列表
        boolean loadedFromConfig = false;
        try {
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            if (configuredNetworks != null && !configuredNetworks.isEmpty()) {
                loadedFromConfig = true;
                for (WifiConfiguration config : configuredNetworks) {
                    SavedNetworkModel model = new SavedNetworkModel();
                    
                    // 解析SSID
                    String ssid = config.SSID;
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    model.setSsid(ssid != null ? ssid : "未知网络");
                    model.setNetworkId(config.networkId);
                    model.setPriority(config.priority);
                    model.setSecurity(getSecurityType(config));

                    // 检查是否是当前连接的网络
                    if (currentSsid != null && currentSsid.equals(model.getSsid())) {
                        model.setCurrentNetwork(true);
                    }

                    networkList.add(model);
                }
            }
        } catch (SecurityException e) {
            // 权限不足
        } catch (Exception e) {
            // 其他异常
        }

        // 如果无法获取配置列表，显示当前连接的网络
        if (!loadedFromConfig || networkList.isEmpty()) {
            if (currentSsid != null && !"<unknown ssid>".equals(currentSsid)) {
                SavedNetworkModel model = new SavedNetworkModel();
                model.setSsid(currentSsid);
                model.setNetworkId(-1);
                model.setSecurity("未知");
                model.setCurrentNetwork(true);
                networkList.add(model);
            }
        }

        adapter.updateData(networkList);
        updateViewVisibility();
    }

    /**
     * 获取WiFi安全类型
     */
    private String getSecurityType(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return "WPA-PSK";
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) {
            return "WPA-EAP";
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            return "IEEE8021X";
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
            if (config.wepKeys[0] != null) {
                return "WEP";
            }
            return "开放";
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WAPI_PSK)) {
            return "WAPI-PSK";
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WAPI_CERT)) {
            return "WAPI-CERT";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 的其他安全类型
            try {
                if (config.allowedKeyManagement.get(4)) { // OWE
                    return "OWE";
                }
                if (config.allowedKeyManagement.get(5)) { // SAE
                    return "WPA3-SAE";
                }
                if (config.allowedKeyManagement.get(6)) { // SUITE_B_192
                    return "WPA3-Enterprise";
                }
            } catch (Exception e) {
                // 忽略
            }
        }
        return "未知";
    }

    private void updateViewVisibility() {
        if (networkList.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            countText.setText("共 0 个已保存网络");
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            countText.setText("共 " + networkList.size() + " 个已保存网络");
        }
    }

    private void onNetworkClick(SavedNetworkModel network) {
        showNetworkDetails(network);
    }

    private void onNetworkLongClick(SavedNetworkModel network) {
        if (network.getNetworkId() != -1) {
            showNetworkOptions(network);
        }
    }

    private void showNetworkDetails(SavedNetworkModel network) {
        StringBuilder info = new StringBuilder();
        info.append("SSID: ").append(network.getSsid()).append("\n");
        info.append("安全类型: ").append(network.getSecurity()).append("\n");
        info.append("网络ID: ").append(network.getNetworkId()).append("\n");
        info.append("优先级: ").append(network.getPriority()).append("\n");
        info.append("状态: ").append(network.isCurrentNetwork() ? "当前连接" : "已保存");

        new MaterialAlertDialogBuilder(this)
                .setTitle("网络详情")
                .setMessage(info.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    private void showNetworkOptions(SavedNetworkModel network) {
        String[] options = {"查看详情", "忘记此网络"};

        new MaterialAlertDialogBuilder(this)
                .setTitle(network.getSsid())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showNetworkDetails(network);
                            break;
                        case 1:
                            forgetNetwork(network);
                            break;
                    }
                })
                .show();
    }

    private void forgetNetwork(SavedNetworkModel network) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("忘记网络")
                .setMessage("确定要忘记网络 \"" + network.getSsid() + "\" 吗？")
                .setPositiveButton("忘记", (dialog, which) -> {
                    try {
                        boolean result = wifiManager.removeNetwork(network.getNetworkId());
                        if (result) {
                            wifiManager.saveConfiguration();
                            Toast.makeText(this, "已忘记网络 " + network.getSsid(), Toast.LENGTH_SHORT).show();
                            loadData();
                        } else {
                            Toast.makeText(this, "忘记网络失败", Toast.LENGTH_SHORT).show();
                        }
                    } catch (SecurityException e) {
                        Toast.makeText(this, "没有权限忘记网络", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
