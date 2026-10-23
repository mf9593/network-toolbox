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
import com.networktool.app.model.SavedNetworkModel;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 已保存WiFi网络适配器
 */
public class SavedNetworkAdapter extends RecyclerView.Adapter<SavedNetworkAdapter.ViewHolder> {

    private List<SavedNetworkModel> networkList;
    private OnNetworkClickListener clickListener;
    private OnNetworkLongClickListener longClickListener;

    public interface OnNetworkClickListener {
        void onNetworkClick(SavedNetworkModel network);
    }

    public interface OnNetworkLongClickListener {
        void onNetworkLongClick(SavedNetworkModel network);
    }

    public SavedNetworkAdapter(List<SavedNetworkModel> networkList) {
        this(networkList, null, null);
    }

    public SavedNetworkAdapter(List<SavedNetworkModel> networkList,
                               OnNetworkClickListener clickListener,
                               OnNetworkLongClickListener longClickListener) {
        this.networkList = networkList != null ? networkList : new ArrayList<>();
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    public void updateData(List<SavedNetworkModel> newList) {
        this.networkList = newList != null ? newList : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_saved_network, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SavedNetworkModel network = networkList.get(position);

        holder.ssidText.setText(network.getSsid());
        holder.securityText.setText(network.getSecurity());
        holder.networkIdText.setText("ID: " + network.getNetworkId());

        android.content.Context context = holder.itemView.getContext();
        int themeColor = ThemeManager.getAccessibleThemeColor(context);
        ThemeManager.setupResultCard(holder.cardView, context);
        holder.signalIcon.setColorFilter(themeColor);
        holder.lockIcon.setColorFilter(ThemeManager.getOnSurfaceVariantColor(context));
        holder.currentIcon.setImageTintList(
                android.content.res.ColorStateList.valueOf(themeColor));

        if (network.isCurrentNetwork()) {
            holder.currentIcon.setVisibility(View.VISIBLE);
            holder.cardView.setStrokeWidth(dp(context, 2));
            holder.cardView.setStrokeColor(themeColor);
        } else {
            holder.currentIcon.setVisibility(View.GONE);
            holder.cardView.setStrokeWidth(0);
        }

        // 显示锁定图标（加密网络）
        boolean isOpen = "开放".equals(network.getSecurity()) || "OPEN".equals(network.getSecurity());
        holder.lockIcon.setVisibility(isOpen ? View.GONE : View.VISIBLE);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onNetworkClick(network);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onNetworkLongClick(network);
            }
            return true;
        });
    }

    private int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return networkList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        ImageView signalIcon;
        ImageView lockIcon;
        ImageView currentIcon;
        TextView ssidText;
        TextView securityText;
        TextView networkIdText;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            signalIcon = itemView.findViewById(R.id.signalIcon);
            lockIcon = itemView.findViewById(R.id.lockIcon);
            currentIcon = itemView.findViewById(R.id.currentIcon);
            ssidText = itemView.findViewById(R.id.ssidText);
            securityText = itemView.findViewById(R.id.securityText);
            networkIdText = itemView.findViewById(R.id.networkIdText);
        }
    }
}
