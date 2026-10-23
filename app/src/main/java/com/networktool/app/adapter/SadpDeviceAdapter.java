package com.networktool.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.SadpDevice;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * SADP设备适配器
 */
public class SadpDeviceAdapter extends RecyclerView.Adapter<SadpDeviceAdapter.ViewHolder> {

    private List<SadpDevice> devices = new ArrayList<>();
    private OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(SadpDevice device);
        void onDeviceMenu(SadpDevice device);
    }

    public SadpDeviceAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    public void setDevices(List<SadpDevice> devices) {
        this.devices = devices != null ? devices : new ArrayList<>();
        notifyDataSetChanged();
    }

    public List<SadpDevice> getDevices() {
        return devices;
    }

    public void addDevice(SadpDevice device) {
        // 检查是否已存在
        for (SadpDevice d : devices) {
            if (d.getSerialNumber() != null && d.getSerialNumber().equals(device.getSerialNumber())) {
                return;
            }
        }
        devices.add(device);
        notifyItemInserted(devices.size() - 1);
    }

    public void clearDevices() {
        devices.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sadp_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SadpDevice device = devices.get(position);
        Context context = holder.itemView.getContext();
        int accentColor = ThemeManager.getAccessibleThemeColor(context);

        ThemeManager.setupResultCard(holder.cardView, context);
        holder.deviceIcon.setColorFilter(accentColor);
        holder.deviceNameText.setTextColor(ThemeManager.getOnSurfaceColor(context));
        holder.deviceIpText.setTextColor(ThemeManager.getOnSurfaceVariantColor(context));
        holder.deviceInfoText.setTextColor(ThemeManager.getOnSurfaceVariantColor(context));
        holder.menuButton.setTextColor(accentColor);
        
        holder.deviceNameText.setText(device.getDisplayName());
        holder.deviceIpText.setText(device.getIpAddress() + ":" + device.getPort());
        
        StringBuilder info = new StringBuilder();
        if (device.getSerialNumber() != null) {
            info.append("序列号: ").append(device.getSerialNumber());
        }
        if (device.getMacAddress() != null) {
            if (info.length() > 0) info.append("\n");
            info.append("MAC: ").append(device.getMacAddress());
        }
        if (device.getFirmwareVersion() != null) {
            if (info.length() > 0) info.append("\n");
            info.append("固件: ").append(device.getFirmwareVersion());
        }
        if (device.getChannelCount() > 0) {
            if (info.length() > 0) info.append("\n");
            info.append("通道数: ").append(device.getChannelCount());
        }
        
        if (info.length() > 0) {
            holder.deviceInfoText.setText(info.toString());
            holder.deviceInfoText.setVisibility(View.VISIBLE);
        } else {
            holder.deviceInfoText.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeviceClick(device);
            }
        });

        holder.menuButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeviceMenu(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        ImageView deviceIcon;
        TextView deviceNameText;
        TextView deviceIpText;
        TextView deviceInfoText;
        MaterialButton menuButton;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            deviceIcon = itemView.findViewById(R.id.deviceIcon);
            deviceNameText = itemView.findViewById(R.id.deviceNameText);
            deviceIpText = itemView.findViewById(R.id.deviceIpText);
            deviceInfoText = itemView.findViewById(R.id.deviceInfoText);
            menuButton = itemView.findViewById(R.id.menuButton);
        }
    }
}
