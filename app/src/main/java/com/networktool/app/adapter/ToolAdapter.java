package com.networktool.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.ToolItem;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具列表适配器：支持分组标题 + 两列工具卡片 + 收藏标记。
 */
public class ToolAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TOOL = 1;

    private final List<ToolItem> items = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private OnToolClickListener listener;
    private OnToolLongClickListener longClickListener;

    public interface OnToolClickListener {
        void onToolClick(ToolItem tool);
    }

    public interface OnToolLongClickListener {
        void onToolLongClick(ToolItem tool);
    }

    public ToolAdapter(List<ToolItem> tools, OnToolClickListener listener) {
        this.listener = listener;
        if (tools != null) {
            items.addAll(tools);
        }
    }

    public void setItems(List<ToolItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void setFavorites(Set<String> newFavorites) {
        favorites.clear();
        if (newFavorites != null) {
            favorites.addAll(newFavorites);
        }
        notifyDataSetChanged();
    }

    public void setOnToolLongClickListener(OnToolLongClickListener listener) {
        this.longClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isHeader() ? TYPE_HEADER : TYPE_TOOL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_tool_section, parent, false));
        }
        return new ToolViewHolder(inflater.inflate(R.layout.item_tool_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ToolItem item = items.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).sectionTitle.setText(item.getTitle());
            return;
        }

        ToolViewHolder h = (ToolViewHolder) holder;
        h.titleText.setText(item.getTitle());
        h.descText.setText(item.getDescription());
        h.iconImage.setImageResource(item.getIconResId());

        int accentColor = ThemeManager.getAccessibleThemeColor(h.itemView.getContext());
        int surfaceColor = ThemeManager.getSurfaceColor(h.itemView.getContext());
        int chipColor = ThemeManager.getChipSurfaceColor(h.itemView.getContext());

        h.cardView.setCardBackgroundColor(surfaceColor);
        h.iconContainer.setCardBackgroundColor(chipColor);
        h.iconImage.setColorFilter(accentColor);
        h.favoriteIcon.setColorFilter(accentColor);
        h.favoriteIcon.setVisibility(favorites.contains(item.getTitle()) ? View.VISIBLE : View.GONE);

        h.cardView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onToolClick(item);
            }
        });
        h.cardView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onToolLongClick(item);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView sectionTitle;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            sectionTitle = itemView.findViewById(R.id.sectionTitle);
        }
    }

    static class ToolViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView cardView;
        final MaterialCardView iconContainer;
        final ImageView iconImage;
        final ImageView favoriteIcon;
        final TextView titleText;
        final TextView descText;

        ToolViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            iconContainer = itemView.findViewById(R.id.iconContainer);
            iconImage = itemView.findViewById(R.id.iconImage);
            favoriteIcon = itemView.findViewById(R.id.favoriteIcon);
            titleText = itemView.findViewById(R.id.titleText);
            descText = itemView.findViewById(R.id.descText);
        }
    }
}
