package com.networktool.app.livetv;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 频道列表适配器：配合顶部分类标签栏，只显示当前分类下的频道。
 *
 * <p>选中行用主题色填充 + 左侧高亮竖条，行尾显示线路数量徽标。
 */
public class LiveChannelAdapter extends RecyclerView.Adapter<LiveChannelAdapter.ViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(LiveChannel channel);
    }

    /** 全部分类标签的键 */
    public static final String GROUP_ALL = "全部";

    /** 全部频道（不含分组标题） */
    private final List<LiveChannel> items = new ArrayList<>();
    /** 当前分类下的频道 */
    private final List<LiveChannel> visibleItems = new ArrayList<>();
    private final OnChannelClickListener listener;
    private String currentGroup = GROUP_ALL;
    private LiveChannel selectedChannel;

    public LiveChannelAdapter(OnChannelClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<LiveChannel> channels) {
        items.clear();
        if (channels != null) {
            for (LiveChannel channel : channels) {
                // 分组标题不再作为列表项，分类改由顶部标签栏承担
                if (channel != null && !channel.isHeader()) items.add(channel);
            }
        }
        selectedChannel = null;
        currentGroup = GROUP_ALL;
        rebuildVisible();
        notifyDataSetChanged();
    }

    /** 所有分类（按出现顺序，首项为「全部」） */
    public List<String> getGroups() {
        Set<String> groups = new LinkedHashSet<>();
        groups.add(GROUP_ALL);
        for (LiveChannel channel : items) {
            String group = channel.getGroup();
            if (group != null && !group.isEmpty()) groups.add(group);
        }
        return new ArrayList<>(groups);
    }

    /** 切换分类标签 */
    public void setGroup(String group) {
        String target = group == null || group.isEmpty() ? GROUP_ALL : group;
        if (target.equals(currentGroup)) return;
        currentGroup = target;
        rebuildVisible();
        notifyDataSetChanged();
    }

    public String getGroup() {
        return currentGroup;
    }

    private void rebuildVisible() {
        visibleItems.clear();
        for (LiveChannel channel : items) {
            if (GROUP_ALL.equals(currentGroup)
                    || currentGroup.equals(channel.getGroup())) {
                visibleItems.add(channel);
            }
        }
    }

    public void setSelectedChannel(LiveChannel channel) {
        LiveChannel old = selectedChannel;
        selectedChannel = channel;
        int oldIndex = old == null ? -1 : visibleItems.indexOf(old);
        int newIndex = channel == null ? -1 : visibleItems.indexOf(channel);
        if (oldIndex >= 0) notifyItemChanged(oldIndex);
        if (newIndex >= 0) notifyItemChanged(newIndex);
    }

    public LiveChannel getSelectedChannel() {
        return selectedChannel;
    }

    /** 频道在可见列表中的下标，找不到返回 -1 */
    public int displayIndexOf(LiveChannel channel) {
        if (channel == null) return -1;
        return visibleItems.indexOf(channel);
    }

    public LiveChannel getItem(int position) {
        return position >= 0 && position < visibleItems.size()
                ? visibleItems.get(position) : null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LiveChannel channel = visibleItems.get(position);
        android.content.Context context = holder.itemView.getContext();
        int accent = ThemeManager.getAccessibleThemeColor(context);
        boolean selected = channel == selectedChannel;

        holder.name.setText(channel.getName());
        holder.url.setText(channel.hasMultipleSources()
                ? channel.getSourceLabel() + " · " + channel.getUrl()
                : channel.getUrl());
        // 抽屉是深色玻璃底：未选中文字必须用白色系。
        // 不能用随主题走的 onSurface —— 浅色主题下它是近黑色，在深色抽屉里完全看不清。
        holder.name.setTextColor(selected ? 0xFFFFFFFF : 0xFFF2F7FA);
        holder.url.setTextColor(selected ? 0xE6FFFFFF : 0xC9FFFFFF);

        if (channel.hasMultipleSources()) {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText(channel.getSourceCount() + " 源");
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(withAlpha(accent, selected ? 60 : 36));
            badgeBg.setCornerRadius(dp(context, 6));
            badgeBg.setStroke(dp(context, 1), withAlpha(accent, selected ? 130 : 90));
            holder.badge.setBackground(badgeBg);
            holder.badge.setTextColor(accent);
        } else {
            holder.badge.setVisibility(View.GONE);
        }

        if (selected) {
            // 主题色填充 + 描边，文字提亮到纯白
            GradientDrawable highlight = new GradientDrawable();
            highlight.setColor(withAlpha(accent, 58));
            highlight.setCornerRadius(dp(context, 10));
            highlight.setStroke(dp(context, 1), withAlpha(accent, 150));
            holder.itemView.setBackground(new InsetDrawable(highlight,
                    dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3)));
            holder.name.setTextColor(0xFFFFFFFF);
            holder.indicator.setVisibility(View.VISIBLE);
            GradientDrawable bar = new GradientDrawable();
            bar.setColor(accent);
            bar.setCornerRadius(dp(context, 2));
            holder.indicator.setBackground(bar);
        } else {
            holder.itemView.setBackground(null);
            holder.name.setTextColor(0xFFF2F7FA);
            holder.indicator.setVisibility(View.INVISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener == null) return;
            int index = holder.getBindingAdapterPosition();
            if (index == RecyclerView.NO_POSITION) return;
            LiveChannel current = visibleItems.get(index);
            setSelectedChannel(current);
            listener.onChannelClick(current);
        });
    }

    @Override
    public int getItemCount() {
        return visibleItems.size();
    }

    private int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** 供 Activity 给分类标签上色用 */
    static ColorStateList textColor(int normal, int active) {
        return new ColorStateList(new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{active, normal});
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView url;
        final TextView badge;
        final View indicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.channelName);
            url = itemView.findViewById(R.id.channelUrl);
            badge = itemView.findViewById(R.id.channelSourceBadge);
            indicator = itemView.findViewById(R.id.channelIndicator);
        }
    }
}
