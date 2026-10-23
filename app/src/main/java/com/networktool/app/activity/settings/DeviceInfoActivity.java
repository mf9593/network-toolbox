package com.networktool.app.activity.settings;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.networktool.app.R;
import com.networktool.app.adapter.DeviceInfoAdapter;
import com.networktool.app.model.DeviceInfoItem;
import com.networktool.app.util.ThemeManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 设备信息Activity
 * 
 * 显示完整的设备信息列表
 */
public class DeviceInfoActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private DeviceInfoAdapter adapter;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_info);

        // 设置工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("设备信息");
        }

        // 应用主题色到工具栏
        applyToolbarTheme();

        setupStatusBar();

        initViews();
        loadDeviceInfo();
    }

    /**
     * 应用主题色到工具栏
     */
    private void applyToolbarTheme() {
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupStatusBar() {
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadDeviceInfo() {
        List<DeviceInfoItem> infoList = new ArrayList<>();

        // 基本信息
        infoList.add(new DeviceInfoItem("手机型号", Build.MODEL));
        infoList.add(new DeviceInfoItem("安卓版本", Build.VERSION.RELEASE + "-" + Build.VERSION.CODENAME));
        infoList.add(new DeviceInfoItem("设备用户名", Build.USER));
        infoList.add(new DeviceInfoItem("系统源代码控制值", Build.VERSION.INCREMENTAL));
        
        // 产品信息
        infoList.add(new DeviceInfoItem("产品型号", Build.PRODUCT));
        infoList.add(new DeviceInfoItem("版本显示", Build.DISPLAY));
        infoList.add(new DeviceInfoItem("主板", Build.BOARD));
        infoList.add(new DeviceInfoItem("编译时间", formatBuildTime(Build.TIME)));
        
        // 设备信息
        infoList.add(new DeviceInfoItem("设备引导程序版本", Build.BOOTLOADER));
        infoList.add(new DeviceInfoItem("设备版本类型", Build.TYPE));
        infoList.add(new DeviceInfoItem("系统定制商", Build.BRAND));
        infoList.add(new DeviceInfoItem("设备参数", Build.DEVICE));
        
        // 更多系统信息
        infoList.add(new DeviceInfoItem("开发代号", Build.VERSION.CODENAME));
        infoList.add(new DeviceInfoItem("SDK版本号", String.valueOf(Build.VERSION.SDK_INT)));
        infoList.add(new DeviceInfoItem("CPU指令集", Build.SUPPORTED_ABIS[0]));
        infoList.add(new DeviceInfoItem("硬件类型", Build.HARDWARE));
        
        // 网络相关
        infoList.add(new DeviceInfoItem("无线电固件版本", Build.RADIO));
        infoList.add(new DeviceInfoItem("设备标签", Build.TAGS));
        infoList.add(new DeviceInfoItem("主机地址", Build.HOST));
        infoList.add(new DeviceInfoItem("生产ID", Build.ID));
        
        // 制造商信息
        infoList.add(new DeviceInfoItem("ROM制造商", Build.MANUFACTURER));
        infoList.add(new DeviceInfoItem("安卓ID", getAndroidId()));
        infoList.add(new DeviceInfoItem("设备指纹", Build.FINGERPRINT));
        infoList.add(new DeviceInfoItem("安全更新程序级别", Build.VERSION.SECURITY_PATCH));
        
        // Root和调试状态
        infoList.add(new DeviceInfoItem("Root状态", isDeviceRooted() ? "已root" : "未root"));
        infoList.add(new DeviceInfoItem("调试模式状态", isAdbEnabled() ? "已激活" : "未激活"));
        
        // Shizuku状态
        infoList.add(new DeviceInfoItem("Shizuku状态", isShizukuAvailable() ? "已激活" : "未激活"));
        
        // WiFi状态
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        infoList.add(new DeviceInfoItem("WiFi状态", getWifiStateString(wifiManager.getWifiState())));
        infoList.add(new DeviceInfoItem("WiFi是否激活", wifiManager.isWifiEnabled() ? "是" : "否"));
        infoList.add(new DeviceInfoItem("接入点SSID", getCurrentSsid(wifiManager)));
        infoList.add(new DeviceInfoItem("Mac地址", getMacAddress(wifiManager)));
        
        // 电池信息
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            infoList.add(new DeviceInfoItem("当前电量状态", getBatteryStatus(batteryIntent)));
            infoList.add(new DeviceInfoItem("电池健康值", getBatteryHealth(batteryIntent)));
            infoList.add(new DeviceInfoItem("电源类型", getPowerType(batteryIntent)));
            infoList.add(new DeviceInfoItem("电池技术类型", batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)));
            infoList.add(new DeviceInfoItem("电池温度", getBatteryTemperature(batteryIntent)));
            infoList.add(new DeviceInfoItem("电池剩余电量", getBatteryLevel(batteryIntent)));
        }

        adapter = new DeviceInfoAdapter(infoList);
        recyclerView.setAdapter(adapter);
    }

    private String formatBuildTime(long time) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(time));
    }

    private String getAndroidId() {
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    private boolean isDeviceRooted() {
        String[] paths = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdbEnabled() {
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.ADB_ENABLED, 0) > 0;
    }

    private boolean isShizukuAvailable() {
        return new File("/data/local/tmp/shizuku/shizuku.json").exists();
    }

    private String getWifiStateString(int state) {
        switch (state) {
            case WifiManager.WIFI_STATE_DISABLING:
                return "正在关闭";
            case WifiManager.WIFI_STATE_DISABLED:
                return "已关闭";
            case WifiManager.WIFI_STATE_ENABLING:
                return "正在开启";
            case WifiManager.WIFI_STATE_ENABLED:
                return "已开启";
            case WifiManager.WIFI_STATE_UNKNOWN:
            default:
                return "未知";
        }
    }

    private String getCurrentSsid(WifiManager wifiManager) {
        if (wifiManager.getConnectionInfo() != null) {
            String ssid = wifiManager.getConnectionInfo().getSSID();
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        }
        return "N/A";
    }

    private String getMacAddress(WifiManager wifiManager) {
        if (wifiManager.getConnectionInfo() != null) {
            return wifiManager.getConnectionInfo().getMacAddress();
        }
        return "N/A";
    }

    private String getBatteryStatus(Intent intent) {
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "正在充电";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "已充满";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "未充电";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "未充电";
            case BatteryManager.BATTERY_STATUS_UNKNOWN:
                return "未知状态";
            default:
                return "获取充电状态失败";
        }
    }

    private String getBatteryHealth(Intent intent) {
        int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "没电";
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "健康";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "过热";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "过冷";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "过电压";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "未知错误";
            case BatteryManager.BATTERY_HEALTH_UNKNOWN:
            default:
                return "未知";
        }
    }

    private String getPowerType(Intent intent) {
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "交流电源";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "无线充电";
            default:
                return "--";
        }
    }

    private String getBatteryTemperature(Intent intent) {
        int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        return (temperature / 10.0) + " 摄氏度";
    }

    private String getBatteryLevel(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        return level + "%";
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
