package com.networktool.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.ThemeManager;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用Application类
 * 
 * 功能说明：
 * - 提供全局Application实例
 * - 管理SharedPreferences数据存储
 * - 管理WiFi数据缓存
 * - 注册全局异常捕获处理器
 */
public class NetworkToolApplication extends Application {
    /** 全局Application实例 */
    private static NetworkToolApplication instance;
    /** SharedPreferences实例 */
    private SharedPreferences sharedPreferences;
    /** WiFi数据缓存 */
    private Map<String, Object> wifiData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sharedPreferences = getSharedPreferences("NetworkToolPrefs", Context.MODE_PRIVATE);
        loadWifiData();

        // 应用用户保存的深色模式设置
        ThemeManager.applyThemeMode();

        // 页面显示时把布局里残留的默认强调色换成用户选的主题色
        registerThemeTinter();
        
        // 仅注册全局崩溃捕获，不再记录网络/调试日志
        registerGlobalExceptionHandler();

        // 自动启动局域网聊天服务（如果允许被发现）
        startLanChatIfEnabled();
    }


    /** 页面每次显示时，让布局中的强调色跟随主题色（切换主题色后无需重启应用） */
    private void registerThemeTinter() {
        registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(android.app.Activity activity,
                                                  android.os.Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(android.app.Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(android.app.Activity activity) {
                        if (activity == null) return;
                        android.view.View decor = activity.getWindow() == null
                                ? null : activity.getWindow().getDecorView();
                        if (decor == null) return;
                        decor.post(() -> com.networktool.app.util.ThemeAccentTinter
                                .apply(activity.getWindow().getDecorView()));
                    }

                    @Override
                    public void onActivityPaused(android.app.Activity activity) {
                    }

                    @Override
                    public void onActivityStopped(android.app.Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(android.app.Activity activity,
                                                            android.os.Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(android.app.Activity activity) {
                    }
                });
    }

    /**
     * 如果用户开启了"允许被好友发现"，自动启动局域网聊天服务
     * 异步执行，不阻塞主线程启动
     */
    private void startLanChatIfEnabled() {
        new Thread(() -> {
            try {
                SharedPreferences lanChatPrefs = getSharedPreferences("lan_chat", Context.MODE_PRIVATE);
                boolean discoverable = lanChatPrefs.getBoolean("discoverable", true);

                if (discoverable) {
                    String userName = lanChatPrefs.getString("user_name", "");

                    if (userName.isEmpty()) {
                        userName = "用户" + (int)(Math.random() * 1000);
                        lanChatPrefs.edit().putString("user_name", userName).apply();
                    }

                    LanChatManager.getInstance().start(NetworkToolApplication.this, userName, 0);
                }
            } catch (Exception e) {
                android.util.Log.e("NetworkToolApp", "Failed to start LanChat", e);
            }
        }, "LanChat-Init").start();
    }
    
    /**
     * 注册全局未捕获异常处理器
     * 所有未处理的异常都会被记录到日志中
     */
    private void registerGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(
                new AppErrorLogger.GlobalExceptionHandler(this));
    }

    /**
     * 获取全局Application实例
     * @return Application实例
     */
    public static NetworkToolApplication getInstance() {
        return instance;
    }

    /**
     * 获取SharedPreferences实例
     * @return SharedPreferences实例
     */
    public SharedPreferences getSharedPreferences() {
        return sharedPreferences;
    }

    /**
     * 存储数据到SharedPreferences
     * @param key 键名
     * @param value 值（支持String、Integer、Boolean、Long）
     */
    public void putSharedData(String key, Object value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value == null) {
            editor.remove(key);
        }
        editor.apply();
    }

    /**
     * 从SharedPreferences获取数据
     * @param key 键名
     * @return 存储的数据对象
     */
    public Object getSharedData(String key) {
        return sharedPreferences.getAll().get(key);
    }

    /**
     * 从SharedPreferences获取字符串数据
     * @param key 键名
     * @param defaultValue 默认值
     * @return 字符串值，如果不存在返回默认值
     */
    public String getSharedData(String key, String defaultValue) {
        return sharedPreferences.getString(key, defaultValue);
    }

    /**
     * 加载WiFi数据
     */
    private void loadWifiData() {
        String json = sharedPreferences.getString("wifiData", null);
        if (json != null) {
            Gson gson = new Gson();
            Type type = new TypeToken<Map<String, Object>>(){}.getType();
            wifiData = gson.fromJson(json, type);
        } else {
            wifiData = new HashMap<>();
        }
    }

    /**
     * 保存WiFi数据到SharedPreferences
     */
    public void saveWifiData() {
        Gson gson = new Gson();
        String json = gson.toJson(wifiData);
        sharedPreferences.edit().putString("wifiData", json).apply();
    }

    /**
     * 获取WiFi数据
     * @return WiFi数据Map
     */
    public Map<String, Object> getWifiData() {
        return wifiData;
    }

    /**
     * 设置WiFi数据并保存
     * @param data WiFi数据Map
     */
    public void setWifiData(Map<String, Object> data) {
        this.wifiData = data;
        saveWifiData();
    }
}
