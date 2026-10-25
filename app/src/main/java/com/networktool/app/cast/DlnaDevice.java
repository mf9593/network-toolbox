package com.networktool.app.cast;

/**
 * 一个 DLNA / UPnP 媒体渲染设备（电视、电视盒子等）。
 */
public class DlnaDevice {

    /** 设备名称，如「客厅电视」 */
    public String friendlyName;
    /** 型号 */
    public String modelName;
    /** 唯一标识 */
    public String udn;
    /** 设备描述 XML 地址 */
    public String location;
    /** AVTransport 服务控制地址（投屏用这个） */
    public String controlUrl;
    /** 设备 IP，用于界面展示 */
    public String ip;

    public boolean isReady() {
        return controlUrl != null && !controlUrl.isEmpty();
    }

    public String getDisplayName() {
        if (friendlyName != null && !friendlyName.trim().isEmpty()) {
            return friendlyName;
        }
        return ip == null ? "未知设备" : ip;
    }

    public String getSubTitle() {
        StringBuilder builder = new StringBuilder();
        if (modelName != null && !modelName.isEmpty()) {
            builder.append(modelName);
        }
        if (ip != null && !ip.isEmpty()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(ip);
        }
        return builder.length() == 0 ? "DLNA 设备" : builder.toString();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
