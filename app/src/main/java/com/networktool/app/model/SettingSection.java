package com.networktool.app.model;

import java.util.List;

public class SettingSection {
    private String title;
    private List<SettingItem> items;

    public SettingSection(String title, List<SettingItem> items) {
        this.title = title;
        this.items = items;
    }

    public String getTitle() { return title; }
    public List<SettingItem> getItems() { return items; }
}
