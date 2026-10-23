package com.networktool.app.model;

/**
 * IP扫描结果模型
 * 用于显示IP扫描结果，包括在线/离线状态
 */
public class PortScanResult {
    private String ip;
    private int port;
    private String url;
    private boolean isOnline; // 是否在线

    public PortScanResult() {
    }

    public PortScanResult(String ip, boolean isOnline) {
        this.ip = ip;
        this.isOnline = isOnline;
        this.url = "http://" + ip;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isOnline() {
        return isOnline;
    }

    public void setOnline(boolean online) {
        isOnline = online;
    }

    public String getDisplayText() {
        if (port > 0) {
            return "端口：" + port;
        }
        return isOnline ? "在线" : "离线";
    }
}
