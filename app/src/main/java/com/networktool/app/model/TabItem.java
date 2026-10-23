package com.networktool.app.model;

import android.graphics.Bitmap;

/**
 * 浏览器标签页数据模型
 * 
 * 用于管理多标签页浏览器中的每个标签页的状态和信息
 */
public class TabItem {
    
    /** 标签页唯一ID */
    private String tabId;
    
    /** 当前URL */
    private String url;
    
    /** 页面标题 */
    private String title;
    
    /** 页面favicon */
    private Bitmap favicon;
    
    /** 是否正在加载 */
    private boolean isLoading;
    
    /** 加载进度 0-100 */
    private int progress;
    
    /** 创建时间 */
    private long createTime;
    
    /** 最后访问时间 */
    private long lastAccessTime;
    
    public TabItem() {
        this.tabId = String.valueOf(System.currentTimeMillis());
        this.createTime = System.currentTimeMillis();
        this.lastAccessTime = System.currentTimeMillis();
    }
    
    public TabItem(String url, String title) {
        this();
        this.url = url;
        this.title = title;
    }
    
    public String getTabId() {
        return tabId;
    }
    
    public void setTabId(String tabId) {
        this.tabId = tabId;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public Bitmap getFavicon() {
        return favicon;
    }
    
    public void setFavicon(Bitmap favicon) {
        this.favicon = favicon;
    }
    
    public boolean isLoading() {
        return isLoading;
    }
    
    public void setLoading(boolean loading) {
        isLoading = loading;
    }
    
    public int getProgress() {
        return progress;
    }
    
    public void setProgress(int progress) {
        this.progress = progress;
    }
    
    public long getCreateTime() {
        return createTime;
    }
    
    public long getLastAccessTime() {
        return lastAccessTime;
    }
    
    public void setLastAccessTime(long lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }
    
    /**
     * 获取显示标题
     * 如果没有标题则显示URL
     */
    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) {
            return title;
        }
        if (url != null && !url.isEmpty()) {
            // 简化URL显示
            String displayUrl = url.replace("http://", "").replace("https://", "");
            if (displayUrl.endsWith("/")) {
                displayUrl = displayUrl.substring(0, displayUrl.length() - 1);
            }
            return displayUrl;
        }
        return "新标签页";
    }
}
