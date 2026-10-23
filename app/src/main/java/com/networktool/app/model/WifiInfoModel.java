package com.networktool.app.model;

/**
 * WiFi信息数据模型
 * 
 * 存储WiFi网络的详细信息，包括：
 * - 网络标识信息（SSID、BSSID）
 * - 信号强度（level）
 * - 频率和频道信息（frequency）
 * - 安全能力（capabilities）
 * - WiFi标准和频道宽度（standard、channelWidth）
 */
public class WifiInfoModel {
    /** WiFi网络名称 */
    private String ssid;
    /** WiFi网络MAC地址 */
    private String bssid;
    /** 信号强度（dBm） */
    private int level;
    /** 频率（MHz） */
    private int frequency;
    /** 安全能力描述 */
    private String capabilities;
    /** WiFi标准（如802.11ac） */
    private String standard;
    /** 频道宽度（如80MHz） */
    private String channelWidth;

    /**
     * 获取WiFi网络名称
     * @return SSID
     */
    public String getSsid() {
        return ssid;
    }

    /**
     * 设置WiFi网络名称
     * @param ssid SSID
     */
    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    /**
     * 获取WiFi网络MAC地址
     * @return BSSID
     */
    public String getBssid() {
        return bssid;
    }

    /**
     * 设置WiFi网络MAC地址
     * @param bssid BSSID
     */
    public void setBssid(String bssid) {
        this.bssid = bssid;
    }

    /**
     * 获取信号强度
     * @return 信号强度（dBm）
     */
    public int getLevel() {
        return level;
    }

    /**
     * 设置信号强度
     * @param level 信号强度（dBm）
     */
    public void setLevel(int level) {
        this.level = level;
    }

    /**
     * 获取频率
     * @return 频率（MHz）
     */
    public int getFrequency() {
        return frequency;
    }

    /**
     * 设置频率
     * @param frequency 频率（MHz）
     */
    public void setFrequency(int frequency) {
        this.frequency = frequency;
    }

    /**
     * 获取安全能力描述
     * @return 安全能力
     */
    public String getCapabilities() {
        return capabilities;
    }

    /**
     * 设置安全能力描述
     * @param capabilities 安全能力
     */
    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * 获取WiFi标准
     * @return WiFi标准（如802.11ac）
     */
    public String getStandard() {
        return standard;
    }

    /**
     * 设置WiFi标准
     * @param standard WiFi标准
     */
    public void setStandard(String standard) {
        this.standard = standard;
    }

    /**
     * 获取频道宽度
     * @return 频道宽度（如80MHz）
     */
    public String getChannelWidth() {
        return channelWidth;
    }

    /**
     * 设置频道宽度
     * @param channelWidth 频道宽度
     */
    public void setChannelWidth(String channelWidth) {
        this.channelWidth = channelWidth;
    }

    /**
     * 根据频率(MHz)判断所属频段
     * @return "2.4GHz"、"5GHz"、"6GHz" 或 "其他"
     */
    public String getFrequencyBand() {
        if (frequency >= 2400 && frequency < 2500) return "2.4GHz";
        if (frequency >= 5000 && frequency < 5900) return "5GHz";
        if (frequency >= 5900 && frequency < 7125) return "6GHz";
        return "其他";
    }
}
