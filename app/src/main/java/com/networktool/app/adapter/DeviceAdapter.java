package com.networktool.app.adapter;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
        if (protocols.isEmpty()) {
            holder.protocolText.setVisibility(View.GONE);
        } else {
            // 来源标签（ARP表 / Ping / SSDP / mDNS ……）和分类标签保持同一套「卡片」外观，
            // 之前这里是裸文字，跟下面的分类标签风格不一致，看着像漏了边框。
            applyChipStyle(holder, holder.protocolText);
            holder.protocolText.setVisibility(View.VISIBLE);
        }

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

        // 分类标签：设备类型从标题前缀【】挪到这里，标题只保留设备名。
        // 标签放在端口那一行靠左，不挤在标题行右侧（会把标题行撑高）
        String category = categoryLabel(device);
        if (category == null) {
            holder.categoryText.setVisibility(View.GONE);
        } else {
            holder.categoryText.setText(category);
            applyChipStyle(holder, holder.categoryText);
            holder.categoryText.setVisibility(View.VISIBLE);
        }

        // 分类标签和端口共用一行；两个都没有时整行隐藏，避免白占 3dp 间距
        holder.tagRow.setVisibility(
                (category == null
                        && holder.protocolText.getVisibility() == View.GONE
                        && holder.portsText.getVisibility() == View.GONE)
                        ? View.GONE : View.VISIBLE);

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
            case DeviceInfo.ICON_SADP:
                iconRes = R.drawable.ic_sadp;
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

    /**
     * 小标签样式：浅色底 + 同色系 1dp 描边 + 主题文字色。
     *
     * <p>分类标签（摄像头/电脑/路由器）和来源标签（ARP表/Ping/SSDP）共用这一套，
     * 保证同一张卡片里所有小标签外观一致 —— 之前来源标签是裸文字，
     * 看起来像漏了边框。</p>
     */
    private void applyChipStyle(ViewHolder holder, TextView chip) {
        Context context = holder.itemView.getContext();
        chip.setTextColor(ThemeManager.getBadgeTextColor(context));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(ThemeManager.getBadgeSurfaceColor(context));
        badge.setStroke(
                Math.max(1, Math.round(dp(holder.itemView, 1))),
                ThemeManager.getBadgeStrokeColor(context));
        badge.setCornerRadius(dp(holder.itemView, 6));
        chip.setBackground(badge);
    }

    private String joinProtocols(DeviceInfo device) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : device.getProtocolNames().entrySet()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(entry.getKey());
        }
        return builder.toString();
    }

    /**
     * 设备分类标签文本（显示在 IPv4 徽标下方的第二个小标签）。
     *
     * <p>以前这些分类直接写进设备标题里（如「【ONVIF】摄像头」），
     * 标题又长又难认，同一台设备还会因为不同协议给出不同前缀。
     * 现在标题只放设备名，分类单独用一个标签展示。</p>
     *
     * @return 分类文本；无明确分类时返回 null（不显示标签）
     */
    private String categoryLabel(DeviceInfo device) {
        switch (device.getIconType()) {
            case DeviceInfo.ICON_ROUTER:
                return "路由器";
            case DeviceInfo.ICON_COMPUTER:
                return "电脑";
            case DeviceInfo.ICON_CAMERA:
                return "摄像头";
            case DeviceInfo.ICON_PHONE:
                return "手机";
            case DeviceInfo.ICON_PRINTER:
                return "打印机";
            case DeviceInfo.ICON_DLNA:
                return "投屏";
            case DeviceInfo.ICON_NVR:
                return "录像机";
            case DeviceInfo.ICON_ONVIF:
                return "摄像头";
            case DeviceInfo.ICON_SADP:
                return "SADP 设备";
            case DeviceInfo.ICON_SSDP:
                return "UPnP";
            case DeviceInfo.ICON_HTTP:
                return "网页管理";
            default:
                // ARP / Ping 这类只是「发现途径」，不是设备类型，不显示标签
                return null;
        }
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
        final TextView categoryText;
        final LinearLayout tagRow;
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
            categoryText = itemView.findViewById(R.id.categoryText);
            tagRow = itemView.findViewById(R.id.tagRow);
            protocolText = itemView.findViewById(R.id.protocolText);
            portsText = itemView.findViewById(R.id.portsText);
        }
    }
}
