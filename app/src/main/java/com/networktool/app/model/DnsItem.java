package com.networktool.app.model;

/**
 * DNS服务器信息数据模型
 * 
 * 用于表示DNS服务器的信息，包括：
 * - 服务器IP地址
 * - 服务器名称
 * - 延迟时间
 */
public class DnsItem {
    /** DNS服务器IP地址 */
    private String ip;
    /** DNS服务器名称 */
    private String name;
    /** 连接延迟（毫秒） */
    private String delay;

    /**
     * 创建DNS服务器信息
     * @param ip 服务器IP地址
     * @param name 服务器名称
     * @param delay 连接延迟
     */
    public DnsItem(String ip, String name, String delay) {
        this.ip = ip;
        this.name = name;
        this.delay = delay;
    }

    /**
     * 获取服务器IP地址
     * @return IP地址
     */
    public String getIp() {
        return ip;
    }

    /**
     * 设置服务器IP地址
     * @param ip IP地址
     */
    public void setIp(String ip) {
        this.ip = ip;
    }

    /**
     * 获取服务器名称
     * @return 服务器名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置服务器名称
     * @param name 服务器名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取连接延迟
     * @return 延迟时间
     */
    public String getDelay() {
        return delay;
    }

    /**
     * 设置连接延迟
     * @param delay 延迟时间
     */
    public void setDelay(String delay) {
        this.delay = delay;
    }
}
