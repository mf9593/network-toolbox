package com.networktool.app.model;

/**
 * 设备信息项模型
 */
public class DeviceInfoItem {
    private String name;
    private String value;

    public DeviceInfoItem(String name, String value) {
        this.name = name;
        this.value = value != null ? value : "N/A";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
