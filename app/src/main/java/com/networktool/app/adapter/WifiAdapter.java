package com.networktool.app.adapter;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.WifiInfoModel;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiDataManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WiFi 列表适配器
 */
public class WifiAdapter extends RecyclerView.Adapter<WifiAdapter.ViewHolder> {

    private List<WifiInfoModel> wifiList;
    private final OnWifiClickListener clickListener;
    private final OnWifiLongClickListener longClickListener;
    private final WifiDataManager wifiDataManager;
    private Map<String, List<String>> savedWifiData;

    public interface OnWifiClickListener {
        void onWifiClick(WifiInfoModel wifi);
    }

    public interface OnWifiLongClickListener {
        void onWifiLongClick(WifiInfoModel wifi);
    }

    public WifiAdapter(List<WifiInfoModel> wifiList, OnWifiClickListener clickListener) {
        this(wifiList, clickListener, null);
    }

    public WifiAdapter(List<WifiInfoModel> wifiList, OnWifiClickListener clickListener,
                       OnWifiLongClickListener longClickListener) {
        this(wifiList, clickListener, longClickListener, null);
    }

    public WifiAdapter(List<WifiInfoModel> wifiList, OnWifiClickListener clickListener,
                       OnWifiLongClickListener longClickListener, WifiDataManager wifiDataManager) {
        this.wifiList = wifiList != null ? wifiList : new ArrayList<>();
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
        this.wifiDataManager = wifiDataManager;
        if (wifiDataManager != null) {
            savedWifiData = wifiDataManager.getAllWifiData();
        }
    }

    public void updateData(List<WifiInfoModel> newList) {
        this.wifiList = newList != null ? newList : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateWifiData() {
        if (wifiDataManager != null) {
            savedWifiData = wifiDataManager.getAllWifiData();
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_wifi, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WifiInfoModel wifi = wifiList.get(position);
        int accentColor = ThemeManager.getAccessibleThemeColor(holder.itemView.getContext());
        int chipSurface = ThemeManager.getChipSurfaceColor(holder.itemView.getContext());
        int surfaceColor = ThemeManager.getSurfaceColor(holder.itemView.getContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(holder.itemView.getContext());

        holder.cardView.setCardBackgroundColor(surfaceColor);
        holder.ssidText.setText(wifi.getSsid() == null ? "隐藏 SSID" : wifi.getSsid());

        String bssid = wifi.getBssid();
        holder.bssidText.setText(bssid == null || bssid.isEmpty() ? "BSSID --" : "BSSID " + bssid);
        holder.standardText.setText(wifi.getStandard() == null || wifi.getStandard().isEmpty()
                ? "Wi-Fi" : wifi.getStandard());
        holder.channelText.setText(wifi.getChannelWidth() == null || wifi.getChannelWidth().isEmpty()
                ? "带宽 --" : wifi.getChannelWidth());
        holder.signalText.setText(wifi.getLevel() + " dBm");

        int level = wifi.getLevel();
        if (level >= -50) {
            holder.signalIcon.setImageResource(R.drawable.ic_signal_wifi_outline_4);
        } else if (level >= -70) {
            holder.signalIcon.setImageResource(R.drawable.ic_signal_wifi_outline_3);
        } else if (level >= -85) {
            holder.signalIcon.setImageResource(R.drawable.ic_signal_wifi_outline_2);
        } else if (level >= -100) {
            holder.signalIcon.setImageResource(R.drawable.ic_signal_wifi_outline_1);
        } else {
            holder.signalIcon.setImageResource(R.drawable.ic_signal_wifi_outline_0);
        }
        holder.signalIcon.setColorFilter(accentColor);
        holder.lockIcon.setColorFilter(onSurfaceVariant);

        String capabilities = wifi.getCapabilities();
        boolean isOpen = capabilities == null
                || (capabilities.contains("ESS")
                && !capabilities.contains("WPA")
                && !capabilities.contains("WEP"));
        if (isOpen) {
            holder.lockIcon.setVisibility(View.GONE);
        } else {
            holder.lockIcon.setVisibility(View.VISIBLE);
            boolean hasSavedPassword = false;
            if (savedWifiData != null && wifi.getSsid() != null) {
                List<String> passwords = savedWifiData.get(wifi.getSsid());
                hasSavedPassword = passwords != null && !passwords.isEmpty();
            }
            holder.lockIcon.setImageResource(hasSavedPassword ? R.drawable.ic_key : R.drawable.ic_lock);
            holder.lockIcon.setContentDescription(hasSavedPassword ? "有保存密码" : "加密网络");
        }

        double ghz = wifi.getFrequency() / 1000.0;
        holder.freqText.setText(wifi.getFrequency() > 0
                ? String.format(java.util.Locale.CHINA, "%.1fG", ghz) : "频段 --");

        setChipBackground(holder.freqText, chipSurface, onSurfaceVariant);
        setChipBackground(holder.standardText, chipSurface, onSurfaceVariant);
        setChipBackground(holder.channelText, chipSurface, onSurfaceVariant);
        setChipBackground(holder.signalText, chipSurface, onSurfaceVariant);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onWifiClick(wifi);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onWifiLongClick(wifi);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return wifiList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView cardView;
        final ImageView signalIcon;
        final ImageView lockIcon;
        final TextView ssidText;
        final TextView bssidText;
        final TextView standardText;
        final TextView channelText;
        final TextView freqText;
        final TextView signalText;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            signalIcon = itemView.findViewById(R.id.signalIcon);
            lockIcon = itemView.findViewById(R.id.lockIcon);
            ssidText = itemView.findViewById(R.id.ssidText);
            bssidText = itemView.findViewById(R.id.bssidText);
            standardText = itemView.findViewById(R.id.standardText);
            channelText = itemView.findViewById(R.id.channelText);
            freqText = itemView.findViewById(R.id.freqText);
            signalText = itemView.findViewById(R.id.signalText);
        }
    }

    private void setChipBackground(TextView chip, int color, int textColor) {
        chip.setBackgroundResource(R.drawable.bg_chip_filled);
        Drawable background = chip.getBackground();
        if (background != null) {
            background.mutate().setTint(color);
        }
        chip.setTextColor(textColor);
    }
}
