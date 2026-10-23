package com.networktool.app.adapter;

import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.SettingItem;
import com.networktool.app.model.SettingSection;
import com.networktool.app.util.ThemeManager;

import java.util.List;

public class SettingAdapter extends RecyclerView.Adapter<SettingAdapter.SectionViewHolder> {

    private final List<SettingSection> sections;
    private final OnSettingClickListener listener;

    public interface OnSettingClickListener {
        void onSettingClick(SettingItem setting);
    }

    public SettingAdapter(List<SettingSection> sections, OnSettingClickListener listener) {
        this.sections = sections;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setting_section, parent, false);
        return new SectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        SettingSection section = sections.get(position);
        int accentColor = ThemeManager.getAccessibleThemeColor(holder.itemView.getContext());
        int surfaceColor = ThemeManager.getSurfaceColor(holder.itemView.getContext());
        int chipColor = ThemeManager.getChipSurfaceColor(holder.itemView.getContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(holder.itemView.getContext());
        int dividerColor = ThemeManager.getOutlineVariantColor(holder.itemView.getContext());

        holder.sectionTitle.setText(section.getTitle() == null ? "" : section.getTitle());
        holder.sectionTitle.setTextColor(onSurfaceVariant);
        holder.cardView.setCardBackgroundColor(surfaceColor);

        holder.itemsContainer.removeAllViews();
        List<SettingItem> items = section.getItems();
        if (items == null) return;

        for (int i = 0; i < items.size(); i++) {
            SettingItem item = items.get(i);
            View row = LayoutInflater.from(holder.itemView.getContext())
                    .inflate(R.layout.item_setting_row, holder.itemsContainer, false);

            MaterialCardView iconContainer = row.findViewById(R.id.iconContainer);
            ImageView icon = row.findViewById(R.id.iconImage);
            TextView title = row.findViewById(R.id.titleText);
            TextView subtitle = row.findViewById(R.id.subtitleText);
            ImageView endIcon = row.findViewById(R.id.endIcon);

            iconContainer.setCardBackgroundColor(chipColor);
            icon.setImageResource(item.getIconResId());
            icon.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
            title.setText(item.getTitle() == null ? "" : item.getTitle());

            String subtitleText = item.getSubtitle();
            if (subtitleText != null && !subtitleText.isEmpty()) {
                subtitle.setText(subtitleText);
                subtitle.setTextColor(onSurfaceVariant);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setVisibility(View.GONE);
            }

            endIcon.setImageResource(item.getEndIconResId());
            endIcon.setColorFilter(onSurfaceVariant, PorterDuff.Mode.SRC_IN);

            row.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSettingClick(item);
                }
            });
            holder.itemsContainer.addView(row);

            if (i < items.size() - 1) {
                View divider = new View(holder.itemView.getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(holder.itemView, 1));
                params.leftMargin = dp(holder.itemView, 64);
                divider.setLayoutParams(params);
                divider.setBackgroundColor(dividerColor);
                holder.itemsContainer.addView(divider);
            }
        }
    }

    private int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView cardView;
        final TextView sectionTitle;
        final LinearLayout itemsContainer;

        SectionViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            sectionTitle = itemView.findViewById(R.id.sectionTitle);
            itemsContainer = itemView.findViewById(R.id.itemsContainer);
        }
    }
}
