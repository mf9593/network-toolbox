package com.networktool.app.model;

/**
 * 已保存WiFi网络模型
 */
public class SavedNetworkModel {
    private String ssid;
    private String bssid;
    private int networkId;
    private int priority;
    private String security;
    private String preSharedKey;
    private boolean isCurrentNetwork;

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        // 移除SSID的引号
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            this.ssid = ssid.substring(1, ssid.length() - 1);
        } else {
            this.ssid = ssid;
        }
    }

    public String getBssid() {
        return bssid;
    }

    public void setBssid(String bssid) {
        this.bssid = bssid;
    }

    public int getNetworkId() {
        return networkId;
    }

    public void setNetworkId(int networkId) {
        this.networkId = networkId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getSecurity() {
        return security;
    }

    public void setSecurity(String security) {
        this.security = security;
    }

    public String getPreSharedKey() {
        return preSharedKey;
    }

    public void setPreSharedKey(String preSharedKey) {
        this.preSharedKey = preSharedKey;
    }

    public boolean isCurrentNetwork() {
        return isCurrentNetwork;
    }

    public void setCurrentNetwork(boolean currentNetwork) {
        isCurrentNetwork = currentNetwork;
    }
}
