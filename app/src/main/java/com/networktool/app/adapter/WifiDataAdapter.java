package com.networktool.app.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.WifiDataItem;
import com.networktool.app.util.ThemeManager;

import java.util.List;

/**
 * WiFi数据适配器
 */
public class WifiDataAdapter extends RecyclerView.Adapter<WifiDataAdapter.ViewHolder> {

    private List<WifiDataItem> dataList;
    private OnItemClickListener clickListener;
    private OnItemLongClickListener longClickListener;

    public interface OnItemClickListener {
        void onItemClick(WifiDataItem item, int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(WifiDataItem item, int position);
    }

    public WifiDataAdapter(List<WifiDataItem> dataList) {
        this.dataList = dataList;
    }

    public void setOnClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnLongClickListener(OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void updateData(List<WifiDataItem> newData) {
        this.dataList = newData;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_wifi_data, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WifiDataItem item = dataList.get(position);
        int accentColor = ThemeManager.getAccessibleThemeColor(holder.itemView.getContext());
        ThemeManager.setupResultCard(holder.cardView, holder.itemView.getContext());
        holder.wifiIcon.setColorFilter(accentColor);
        holder.ssidText.setText(item.getSsid());
        holder.securityText.setText(item.getSecurityType());
        styleThemeBadge(holder.securityText);
        
        // 显示密码数量指示器
        if (item.hasMultiplePasswords()) {
            holder.passwordCountBadge.setVisibility(View.VISIBLE);
            holder.passwordCountBadge.setText(item.getPasswordCount() + " 个密码");
            styleThemeBadge(holder.passwordCountBadge);
        } else {
            holder.passwordCountBadge.setVisibility(View.GONE);
        }
        
        // 显示密码（完整显示，不隐藏）
        if (item.hasPassword()) {
            holder.passwordText.setText(item.getPassword());
        } else {
            holder.passwordText.setText("无密码");
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onItemClick(item, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onItemLongClick(item, position);
            }
            return true;
        });
    }

    private void styleThemeBadge(TextView badge) {
        android.content.Context context = badge.getContext();
        int themeColor = ThemeManager.getThemeColor();
        int surfaceColor = ThemeManager.getSurfaceColor(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeManager.blendColorWithBase(themeColor, surfaceColor, 44));
        background.setCornerRadius(dp(context, 8));
        badge.setBackground(background);
        badge.setTextColor(ThemeManager.getAccessibleThemeColor(context));
    }

    private int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return dataList != null ? dataList.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        ImageView wifiIcon;
        TextView ssidText;
        TextView securityText;
        TextView passwordText;
        TextView passwordCountBadge;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            wifiIcon = itemView.findViewById(R.id.wifiIcon);
            ssidText = itemView.findViewById(R.id.ssidText);
            securityText = itemView.findViewById(R.id.securityText);
            passwordText = itemView.findViewById(R.id.passwordText);
            passwordCountBadge = itemView.findViewById(R.id.passwordCountBadge);
        }
    }
}
