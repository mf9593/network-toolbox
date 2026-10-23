package com.networktool.app.util;

import com.networktool.app.model.TabItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器标签页管理器
 * 
 * 负责管理浏览器标签页的创建、切换、关闭等操作
 */
public class TabManager {
    
    /** 标签页列表 */
    private List<TabItem> tabList = new ArrayList<>();
    
    /** 当前选中的标签页索引 */
    private int currentTabIndex = -1;
    
    /** 标签页变化监听器 */
    private OnTabChangeListener listener;
    
    /**
     * 标签页变化监听器接口
     */
    public interface OnTabChangeListener {
        /**
         * 标签页列表发生变化
         */
        void onTabListChanged();
        
        /**
         * 当前标签页发生变化
         * @param tabIndex 新选中的标签页索引
         */
        void onCurrentTabChanged(int tabIndex);
    }
    
    public TabManager() {
    }
    
    /**
     * 设置监听器
     */
    public void setOnTabChangeListener(OnTabChangeListener listener) {
        this.listener = listener;
    }
    
    /**
     * 创建新标签页
     * 
     * @param url 初始URL，可为null
     * @param title 初始标题，可为null
     * @return 新创建的TabItem
     */
    public TabItem createTab(String url, String title) {
        TabItem tab = new TabItem(url, title);
        tabList.add(tab);
        currentTabIndex = tabList.size() - 1;
        
        notifyTabListChanged();
        notifyCurrentTabChanged();
        
        return tab;
    }
    
    /**
     * 关闭指定标签页
     * 
     * @param index 标签页索引
     * @return 关闭后应该选中的标签页索引
     */
    public int closeTab(int index) {
        if (index < 0 || index >= tabList.size()) {
            return currentTabIndex;
        }
        
        tabList.remove(index);
        
        // 调整当前选中标签页
        if (tabList.isEmpty()) {
            currentTabIndex = -1;
        } else if (index == currentTabIndex) {
            // 关闭的是当前页
            if (index >= tabList.size()) {
                currentTabIndex = tabList.size() - 1;
            }
        } else if (index < currentTabIndex) {
            // 关闭的在当前页之前
            currentTabIndex--;
        }
        
        notifyTabListChanged();
        notifyCurrentTabChanged();
        
        return currentTabIndex;
    }
    
    /**
     * 切换到指定标签页
     * 
     * @param index 标签页索引
     * @return 切换是否成功
     */
    public boolean switchToTab(int index) {
        if (index < 0 || index >= tabList.size()) {
            return false;
        }
        
        currentTabIndex = index;
        tabList.get(index).setLastAccessTime(System.currentTimeMillis());
        
        notifyCurrentTabChanged();
        
        return true;
    }
    
    /**
     * 获取指定标签页
     * 
     * @param index 标签页索引
     * @return 标签页对象
     */
    public TabItem getTab(int index) {
        if (index < 0 || index >= tabList.size()) {
            return null;
        }
        return tabList.get(index);
    }
    
    /**
     * 获取当前标签页
     * 
     * @return 当前标签页对象
     */
    public TabItem getCurrentTab() {
        if (currentTabIndex < 0 || currentTabIndex >= tabList.size()) {
            return null;
        }
        return tabList.get(currentTabIndex);
    }
    
    /**
     * 获取标签页总数
     * 
     * @return 标签页数量
     */
    public int getTabCount() {
        return tabList.size();
    }
    
    /**
     * 获取当前标签页索引
     * 
     * @return 当前标签页索引
     */
    public int getCurrentTabIndex() {
        return currentTabIndex;
    }
    
    /**
     * 获取所有标签页
     * 
     * @return 标签页列表
     */
    public List<TabItem> getAllTabs() {
        return new ArrayList<>(tabList);
    }
    
    /**
     * 更新标签页信息
     * 
     * @param index 标签页索引
     * @param url URL
     * @param title 标题
     */
    public void updateTab(int index, String url, String title) {
        if (index < 0 || index >= tabList.size()) {
            return;
        }
        
        TabItem tab = tabList.get(index);
        if (url != null) {
            tab.setUrl(url);
        }
        if (title != null) {
            tab.setTitle(title);
        }
        tab.setLastAccessTime(System.currentTimeMillis());
        
        notifyTabListChanged();
    }
    
    /**
     * 更新标签页加载状态
     * 
     * @param index 标签页索引
     * @param isLoading 是否正在加载
     * @param progress 加载进度
     */
    public void updateTabLoadingState(int index, boolean isLoading, int progress) {
        if (index < 0 || index >= tabList.size()) {
            return;
        }
        
        TabItem tab = tabList.get(index);
        tab.setLoading(isLoading);
        tab.setProgress(progress);
        
        notifyTabListChanged();
    }
    
    /**
     * 清空所有标签页
     */
    public void clearAllTabs() {
        tabList.clear();
        currentTabIndex = -1;
        
        notifyTabListChanged();
        notifyCurrentTabChanged();
    }
    
    /**
     * 通知标签页列表变化
     */
    private void notifyTabListChanged() {
        if (listener != null) {
            listener.onTabListChanged();
        }
    }
    
    /**
     * 通知当前标签页变化
     */
    private void notifyCurrentTabChanged() {
        if (listener != null) {
            listener.onCurrentTabChanged(currentTabIndex);
        }
    }
}
