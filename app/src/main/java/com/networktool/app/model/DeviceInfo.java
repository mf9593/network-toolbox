package com.networktool.app.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 局域网设备信息数据模型
 * 
 * 用于表示局域网扫描发现的设备信息，包括：
 * - 设备标题和IP地址
 * - 设备图标类型（用于区分路由器、电脑、摄像头等设备类型）
 * - 设备ID、型号和描述信息
 */
public class DeviceInfo {
    /** 图标类型常量：未知设备 */
    public static final int ICON_UNKNOWN = 0;
    /** 图标类型常量：路由器 */
    public static final int ICON_ROUTER = 1;
    /** 图标类型常量：电脑 */
    public static final int ICON_COMPUTER = 2;
    /** 图标类型常量：摄像头 */
    public static final int ICON_CAMERA = 3;
    /** 图标类型常量：手机 */
    public static final int ICON_PHONE = 4;
    /** 图标类型常量：打印机 */
    public static final int ICON_PRINTER = 5;
    /** 图标类型常量：DLNA设备 */
    public static final int ICON_DLNA = 6;
    /** 图标类型常量：NVR录像机 */
    public static final int ICON_NVR = 7;
    /** 图标类型常量：Ping探测设备 */
    public static final int ICON_PING = 8;
    /** 图标类型常量：HTTP探测设备 */
    public static final int ICON_HTTP = 9;
    /** 图标类型常量：SSDP/UPnP设备 */
    public static final int ICON_SSDP = 10;
    /** 图标类型常量：ONVIF设备 */
    public static final int ICON_ONVIF = 11;
    /** 图标类型常量：ARP设备 */
    public static final int ICON_ARP = 12;

    /** IP版本常量：IPv4 */
    public static final int IPV4 = 4;
    /** IP版本常量：IPv6 */
    public static final int IPV6 = 6;

    /** 设备显示标题 */
    private String title;
    /** 设备IP地址 */
    private String ip;
    /** 设备 MAC 地址（ARP/NDP 表或 SADP 获取） */
    private String macAddress;
    /** IP版本（IPV4 或 IPV6） */
    private int ipVersion = IPV4;
    /** 设备图标类型 */
    private int iconType = ICON_UNKNOWN;
    /** 设备唯一标识 */
    private String id;
    /** 设备型号 */
    private String model;
    /** 设备描述信息 */
    private String description;
    /** 各协议发现的名称（协议名→名称） */
    private Map<String, String> protocolNames = new LinkedHashMap<>();
    /** 发现的常用端口（如 HTTP 80、RTSP 554），用于列表展示 */
    private java.util.Set<Integer> ports = new java.util.LinkedHashSet<>();
    /** 发现优先级（数字越小越优先，决定列表排序；默认取最低优先级） */
    private int discoveryPriority = Integer.MAX_VALUE;

    /**
     * 获取设备显示标题
     * @return 设备标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置设备显示标题
     * @param title 设备标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 获取设备IP地址
     * @return IP地址
     */
    public String getIp() {
        return ip;
    }

    /**
     * 设置设备IP地址（自动识别IP版本）
     * @param ip IP地址
     */
    public void setIp(String ip) {
        this.ip = ip;
        if (ip != null && ip.contains(":")) {
            this.ipVersion = IPV6;
        } else {
            this.ipVersion = IPV4;
        }
    }

    /**
     * 获取设备 MAC 地址
     */
    public String getMacAddress() {
        return macAddress;
    }

    /**
     * 设置设备 MAC 地址
     */
    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    /**
     * 获取IP版本
     * @return IPV4 或 IPV6
     */
    public int getIpVersion() {
        return ipVersion;
    }

    /**
     * 设置IP版本
     * @param ipVersion IPV4 或 IPV6
     */
    public void setIpVersion(int ipVersion) {
        this.ipVersion = ipVersion;
    }

    /**
     * 获取设备图标类型
     * @return 图标类型（使用ICON_*常量）
     */
    public int getIconType() {
        return iconType;
    }

    /**
     * 设置设备图标类型
     * @param iconType 图标类型（使用ICON_*常量）
     */
    public void setIconType(int iconType) {
        this.iconType = iconType;
    }

    /**
     * 获取设备唯一标识
     * @return 设备ID
     */
    public String getId() {
        return id;
    }

    /**
     * 设置设备唯一标识
     * @param id 设备ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * 获取设备型号
     * @return 设备型号
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置设备型号
     * @param model 设备型号
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 获取设备描述信息
     * @return 描述信息
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置设备描述信息
     * @param description 描述信息
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 添加协议发现的名称
     * @param protocol 协议名称
     * @param name 发现的名称
     */
    public void addProtocolName(String protocol, String name) {
        protocolNames.put(protocol, name);
    }

    /**
     * 获取所有协议发现的名称
     * @return 协议名→名称映射
     */
    public Map<String, String> getProtocolNames() {
        return protocolNames;
    }

    /**
     * 获取发现的常用端口（升序）
     */
    public java.util.List<Integer> getPorts() {
        java.util.List<Integer> list = new java.util.ArrayList<>(ports);
        java.util.Collections.sort(list);
        return list;
    }

    /**
     * 记录一个发现的端口（去重）
     */
    public void addPort(int port) {
        if (port > 0 && port <= 65535) {
            ports.add(port);
        }
    }

    /**
     * 获取发现优先级（数字越小越靠前）
     */
    public int getDiscoveryPriority() {
        return discoveryPriority;
    }

    /**
     * 设置发现优先级；仅当新的优先级更高（数字更小）时生效，
     * 避免后到的低优先级协议覆盖高优先级排序。
     * @param priority 优先级数字
     */
    public void setDiscoveryPriority(int priority) {
        if (priority < this.discoveryPriority) {
            this.discoveryPriority = priority;
        }
    }
}
