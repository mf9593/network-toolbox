package com.networktool.app.model;

/**
 * 设置项数据模型
 *
 * 用于表示设置列表中的每个设置项目，包含：
 * - 设置标题
 * - 左侧图标资源 ID
 * - 右侧图标资源 ID
 * - 点击事件监听器
 */
public class SettingItem {
    /** 设置标题 */
    private String title;
    /** 设置副标题/当前值 */
    private String subtitle = "";
    /** 左侧图标资源 ID */
    private int iconResId;
    /** 右侧图标资源 ID */
    private int endIconResId;
    /** 点击事件监听器 */
    private OnSettingClickListener clickListener;
    /** 是否为开关类型 */
    private boolean isSwitch;
    /** 开关状态 */
    private boolean isChecked;
    /** 开关状态改变监听器 */
    private OnSwitchChangeListener switchChangeListener;

    /**
     * 设置点击事件监听接口
     */
    public interface OnSettingClickListener {
        /**
         * 设置项被点击时调用
         * @param setting 被点击的设置项
         */
        void onSettingClick(SettingItem setting);
    }

    /**
     * 开关状态改变监听接口
     */
    public interface OnSwitchChangeListener {
        /**
         * 开关状态改变时调用
         * @param setting 设置项
         * @param isChecked 新的开关状态
         */
        void onSwitchChanged(SettingItem setting, boolean isChecked);
    }

    /**
     * 创建设置项（普通类型）
     * @param title 设置标题
     * @param iconResId 左侧图标资源 ID
     * @param endIconResId 右侧图标资源 ID
     * @param clickListener 点击事件监听器
     */
    public SettingItem(String title, int iconResId, int endIconResId, OnSettingClickListener clickListener) {
        this(title, "", iconResId, endIconResId, clickListener);
    }

    /**
     * 创建设置项（带副标题）
     */
    public SettingItem(String title, String subtitle, int iconResId, int endIconResId,
                       OnSettingClickListener clickListener) {
        this.title = title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.iconResId = iconResId;
        this.endIconResId = endIconResId;
        this.clickListener = clickListener;
        this.isSwitch = false;
        this.isChecked = false;
    }

    /**
     * 创建设置项（开关类型）
     * @param title 设置标题
     * @param iconResId 左侧图标资源 ID
     * @param isChecked 初始开关状态
     * @param switchChangeListener 开关状态改变监听器
     */
    public SettingItem(String title, int iconResId, boolean isChecked, OnSwitchChangeListener switchChangeListener) {
        this.title = title;
        this.iconResId = iconResId;
        this.isChecked = isChecked;
        this.switchChangeListener = switchChangeListener;
        this.isSwitch = true;
    }

    /**
     * 获取设置标题
     * @return 设置标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置设置标题
     * @param title 设置标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 获取副标题/当前值
     */
    public String getSubtitle() {
        return subtitle;
    }

    /**
     * 设置副标题/当前值
     */
    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle == null ? "" : subtitle;
    }

    /**
     * 获取左侧图标资源 ID
     * @return 图标资源 ID
     */
    public int getIconResId() {
        return iconResId;
    }

    /**
     * 设置左侧图标资源 ID
     * @param iconResId 图标资源 ID
     */
    public void setIconResId(int iconResId) {
        this.iconResId = iconResId;
    }

    /**
     * 获取右侧图标资源 ID
     * @return 图标资源 ID
     */
    public int getEndIconResId() {
        return endIconResId;
    }

    /**
     * 设置右侧图标资源 ID
     * @param endIconResId 图标资源 ID
     */
    public void setEndIconResId(int endIconResId) {
        this.endIconResId = endIconResId;
    }

    /**
     * 获取点击事件监听器
     * @return 点击事件监听器
     */
    public OnSettingClickListener getClickListener() {
        return clickListener;
    }

    /**
     * 设置点击事件监听器
     * @param clickListener 点击事件监听器
     */
    public void setClickListener(OnSettingClickListener clickListener) {
        this.clickListener = clickListener;
    }

    /**
     * 是否为开关类型
     * @return true 表示开关类型，false 表示普通类型
     */
    public boolean isSwitch() {
        return isSwitch;
    }

    /**
     * 设置是否为开关类型
     * @param isSwitch 是否为开关类型
     */
    public void setSwitch(boolean isSwitch) {
        this.isSwitch = isSwitch;
    }

    /**
     * 获取开关状态
     * @return 开关状态
     */
    public boolean isChecked() {
        return isChecked;
    }

    /**
     * 设置开关状态
     * @param isChecked 开关状态
     */
    public void setChecked(boolean isChecked) {
        this.isChecked = isChecked;
    }

    /**
     * 获取开关状态改变监听器
     * @return 开关状态改变监听器
     */
    public OnSwitchChangeListener getSwitchChangeListener() {
        return switchChangeListener;
    }

    /**
     * 设置开关状态改变监听器
     * @param switchChangeListener 开关状态改变监听器
     */
    public void setSwitchChangeListener(OnSwitchChangeListener switchChangeListener) {
        this.switchChangeListener = switchChangeListener;
    }
}
