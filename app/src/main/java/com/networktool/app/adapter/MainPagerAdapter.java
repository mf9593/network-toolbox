package com.networktool.app.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.networktool.app.fragment.InfoFragment;
import com.networktool.app.fragment.DeviceFragment;
import com.networktool.app.fragment.WirelessFragment;
import com.networktool.app.fragment.ToolsFragment;
import com.networktool.app.fragment.SettingsFragment;

/**
 * 主页面ViewPager2适配器
 * 
 * 管理主页面的5个Fragment页面：
 * 信息、设备、无线、工具、设置
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    /**
     * 创建主页面适配器
     * @param fragmentActivity FragmentActivity实例
     */
    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    /**
     * 根据位置创建对应的Fragment
     * @param position 页面位置（0-4）
     * @return 对应的Fragment实例
     */
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new InfoFragment();
            case 1:
                return new DeviceFragment();
            case 2:
                return new WirelessFragment();
            case 3:
                return new ToolsFragment();
            case 4:
                return new SettingsFragment();
            default:
                return new InfoFragment();
        }
    }

    /**
     * 获取页面总数
     * @return 页面数量（5个）
     */
    @Override
    public int getItemCount() {
        return 5;
    }
}
