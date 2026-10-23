package com.networktool.app.model;

/**
 * 工具项数据模型
 *
 * 既可表示工具，也可表示工具分组标题。
 */
public class ToolItem {

    private String title;
    private String description;
    private int iconResId;
    private OnToolClickListener clickListener;
    private boolean header;

    public interface OnToolClickListener {
        void onToolClick(ToolItem tool);
    }

    public ToolItem(String title, int iconResId, OnToolClickListener clickListener) {
        this(title, iconResId, "", clickListener);
    }

    public ToolItem(String title, int iconResId, String description, OnToolClickListener clickListener) {
        this.title = title;
        this.iconResId = iconResId;
        this.description = description;
        this.clickListener = clickListener;
        this.header = false;
    }

    /**
     * 创建分组标题
     */
    public static ToolItem header(String title) {
        ToolItem item = new ToolItem(title, 0, null);
        item.header = true;
        return item;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getIconResId() {
        return iconResId;
    }

    public void setIconResId(int iconResId) {
        this.iconResId = iconResId;
    }

    public OnToolClickListener getClickListener() {
        return clickListener;
    }

    public void setClickListener(OnToolClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public boolean isHeader() {
        return header;
    }
}
