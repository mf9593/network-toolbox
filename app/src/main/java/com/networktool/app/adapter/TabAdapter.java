package com.networktool.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networktool.app.R;
import com.networktool.app.model.TabItem;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签页列表适配器
 */
public class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabViewHolder> {

    private List<TabItem> tabList = new ArrayList<>();
    private int currentTabIndex = -1;
    private OnTabClickListener listener;

    /**
     * 标签页点击监听器
     */
    public interface OnTabClickListener {
        void onTabClick(int position);
        void onTabClose(int position);
    }

    public TabAdapter(OnTabClickListener listener) {
        this.listener = listener;
    }

    /**
     * 更新数据
     */
    public void updateTabs(List<TabItem> tabs, int currentIndex) {
        this.tabList = new ArrayList<>(tabs);
        this.currentTabIndex = currentIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tab_list, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        TabItem tab = tabList.get(position);
        
        // 设置标题和URL
        holder.tabTitleText.setText(tab.getDisplayTitle());
        holder.tabUrlText.setText(tab.getUrl() != null ? tab.getUrl() : "新标签页");
        
        // 高亮当前选中的标签页
        if (position == currentTabIndex) {
            holder.itemView.setBackgroundColor(ThemeManager.getThemeColor() & 0x15000000);
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        
        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTabClick(position);
            }
        });
        
        // 关闭按钮点击事件
        holder.closeTabButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTabClose(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tabList.size();
    }

    static class TabViewHolder extends RecyclerView.ViewHolder {
        TextView tabTitleText;
        TextView tabUrlText;
        ImageView tabIcon;
        ImageButton closeTabButton;

        TabViewHolder(View itemView) {
            super(itemView);
            tabTitleText = itemView.findViewById(R.id.tabTitleText);
            tabUrlText = itemView.findViewById(R.id.tabUrlText);
            tabIcon = itemView.findViewById(R.id.tabIcon);
            closeTabButton = itemView.findViewById(R.id.closeTabButton);
        }
    }
}
