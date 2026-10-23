package com.networktool.app.model;

/**
 * WiFi数据模型
 * 
 * 存储WiFi的SSID和密码信息
 */
public class WifiDataItem {
    private String ssid;
    private String password;
    private long timestamp;
    private int passwordCount; // 该 SSID 的密码总数

    public WifiDataItem() {
        this.timestamp = System.currentTimeMillis();
    }

    public WifiDataItem(String ssid, String password) {
        this.ssid = ssid;
        this.password = password;
        this.timestamp = System.currentTimeMillis();
    }

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 获取加密类型描述
     */
    public String getSecurityType() {
        if (password == null || password.isEmpty()
                || "null".equals(password) || "NONE".equals(password)
                || "空密码".equals(password)) {
            return "开放网络";
        } else if (password.length() == 5 || password.length() == 13) {
            return "WEP";
        } else {
            return "WPA/WPA2";
        }
    }

    /**
     * 是否有密码
     */
    public boolean hasPassword() {
        return password != null && !password.isEmpty()
                && !"null".equals(password) && !"NONE".equals(password)
                && !"空密码".equals(password);
    }

    /**
     * 获取该 SSID 的密码总数
     */
    public int getPasswordCount() {
        return passwordCount;
    }

    /**
     * 设置该 SSID 的密码总数
     */
    public void setPasswordCount(int passwordCount) {
        this.passwordCount = passwordCount;
    }

    /**
     * 是否有多个密码
     */
    public boolean hasMultiplePasswords() {
        return passwordCount > 1;
    }
}
