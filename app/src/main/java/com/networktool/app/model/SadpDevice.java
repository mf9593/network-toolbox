package com.networktool.app.model;

/**
 * SADP设备数据模型
 * 
 * 存储海康威视SADP协议发现的设备信息
 */
public class SadpDevice {
    /* 设备序列号 */
    private String serialNumber;
    
    /* 设备型号 */
    private String model;
    
    /* 设备IP地址 */
    private String ipAddress;
    
    /* 设备MAC地址 */
    private String macAddress;
    
    /* 设备端口 */
    private int port;
    
    /* 设备固件版本 */
    private String firmwareVersion;
    
    /* 子网掩码 */
    private String subnetMask;
    
    /* 默认网关 */
    private String gateway;
    
    /* 设备通道数 */
    private int channelCount;
    
    /* 设备是否在线 */
    private boolean online;
    
    /* 认证用户名 */
    private String username;
    
    /* 认证密码 */
    private String password;
    
    /* 是否已认证 */
    private boolean authenticated;
    
    /* RTSP流地址 */
    private String rtspUrl;

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public String getSubnetMask() {
        return subnetMask;
    }

    public void setSubnetMask(String subnetMask) {
        this.subnetMask = subnetMask;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = channelCount;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getDisplayName() {
        if (model != null && !model.isEmpty()) {
            return model;
        }
        if (serialNumber != null && !serialNumber.isEmpty()) {
            return "海康设备 (" + serialNumber + ")";
        }
        if (ipAddress != null && !ipAddress.isEmpty()) {
            return ipAddress;
        }
        return "未知设备";
    }
}
