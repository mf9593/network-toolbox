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
import com.networktool.app.model.DeviceInfo;
import com.networktool.app.util.ThemeManager;

import java.util.List;
import java.util.Map;

/**
 * 局域网设备列表适配器
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    private final List<DeviceInfo> deviceList;
    private final OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(DeviceInfo device);

        void onDeviceLongClick(DeviceInfo device, String protocolInfo);
    }

    public DeviceAdapter(List<DeviceInfo> deviceList, OnDeviceClickListener listener) {
        this.deviceList = deviceList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DeviceInfo device = deviceList.get(position);
        int themeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(holder.itemView.getContext());
        int surfaceColor = ThemeManager.getSurfaceColor(holder.itemView.getContext());
        int chipColor = ThemeManager.getChipSurfaceColor(holder.itemView.getContext());

        holder.cardView.setCardBackgroundColor(surfaceColor);
        holder.iconContainer.setCardBackgroundColor(chipColor);
        holder.iconImage.setColorFilter(accentColor);

        String title = device.getTitle();
        holder.titleText.setText(title == null || title.isEmpty() ? "未知设备" : title);

        String deviceIp = device.getIp();
        holder.ipText.setText(deviceIp == null || deviceIp.isEmpty() ? "--" : deviceIp);

        String deviceMac = device.getMacAddress();
        if (deviceMac == null || deviceMac.isEmpty()) {
            holder.macText.setVisibility(View.GONE);
        } else {
            holder.macText.setText("MAC " + deviceMac);
            holder.macText.setVisibility(View.VISIBLE);
        }

        String protocols = joinProtocols(device);
        holder.protocolText.setText(protocols);
        holder.protocolText.setTextColor(ThemeManager.getOnSurfaceVariantColor(holder.itemView.getContext()));
        holder.protocolText.setVisibility(protocols.isEmpty() ? View.GONE : View.VISIBLE);

        // 展示发现的服务端口（如 HTTP 80、RTSP 554）；无端口则隐藏
        java.util.List<Integer> ports = device.getPorts();
        if (ports == null || ports.isEmpty()) {
            holder.portsText.setVisibility(View.GONE);
        } else {
            StringBuilder portsBuilder = new StringBuilder("端口: ");
            for (int i = 0; i < ports.size(); i++) {
                if (i > 0) portsBuilder.append(", ");
                portsBuilder.append(ports.get(i));
            }
            holder.portsText.setText(portsBuilder.toString());
            holder.portsText.setTextColor(ThemeManager.getOnSurfaceVariantColor(holder.itemView.getContext()));
            holder.portsText.setVisibility(View.VISIBLE);
        }

        boolean ipv6 = device.getIpVersion() == DeviceInfo.IPV6;
        holder.ipVersionText.setText(ipv6 ? "IPv6" : "IPv4");
        holder.ipVersionText.setTextColor(ipv6
                ? ThemeManager.getOnSurfaceVariantColor(holder.itemView.getContext())
                : ThemeManager.getContrastColor(themeColor));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(ipv6 ? chipColor : themeColor);
        badge.setCornerRadius(dp(holder.itemView, 6));
        holder.ipVersionText.setBackground(badge);

        int iconRes;
        switch (device.getIconType()) {
            case DeviceInfo.ICON_ROUTER:
                iconRes = R.drawable.ic_router;
                break;
            case DeviceInfo.ICON_COMPUTER:
                iconRes = R.drawable.ic_computer;
                break;
            case DeviceInfo.ICON_CAMERA:
                iconRes = R.drawable.ic_camera;
                break;
            case DeviceInfo.ICON_PHONE:
                iconRes = R.drawable.ic_phone;
                break;
            case DeviceInfo.ICON_PRINTER:
                iconRes = R.drawable.ic_printer;
                break;
            case DeviceInfo.ICON_DLNA:
                iconRes = R.drawable.ic_dlna;
                break;
            case DeviceInfo.ICON_NVR:
                iconRes = R.drawable.ic_nvr;
                break;
            case DeviceInfo.ICON_PING:
                iconRes = R.drawable.ic_ping_scan;
                break;
            case DeviceInfo.ICON_HTTP:
                iconRes = R.drawable.ic_http;
                break;
            case DeviceInfo.ICON_SSDP:
                iconRes = R.drawable.ic_ssdp;
                break;
            case DeviceInfo.ICON_ONVIF:
                iconRes = R.drawable.ic_onvif;
                break;
            case DeviceInfo.ICON_ARP:
                iconRes = R.drawable.ic_arp;
                break;
            default:
                iconRes = R.drawable.ic_computer;
                break;
        }
        holder.iconImage.setImageResource(iconRes);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeviceClick(device);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onDeviceLongClick(device, protocols);
            }
            return true;
        });
    }

    private String joinProtocols(DeviceInfo device) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : device.getProtocolNames().entrySet()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(entry.getKey());
        }
        return builder.toString();
    }

    private float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView cardView;
        final MaterialCardView iconContainer;
        final ImageView iconImage;
        final TextView titleText;
        final TextView ipText;
        final TextView macText;
        final TextView ipVersionText;
        final TextView protocolText;
        final TextView portsText;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            iconContainer = itemView.findViewById(R.id.iconContainer);
            iconImage = itemView.findViewById(R.id.iconImage);
            titleText = itemView.findViewById(R.id.titleText);
            ipText = itemView.findViewById(R.id.ipText);
            macText = itemView.findViewById(R.id.macText);
            ipVersionText = itemView.findViewById(R.id.ipVersionText);
            protocolText = itemView.findViewById(R.id.protocolText);
            portsText = itemView.findViewById(R.id.portsText);
        }
    }
}
