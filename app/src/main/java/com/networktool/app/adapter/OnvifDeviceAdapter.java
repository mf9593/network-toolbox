package com.networktool.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.OnvifDevice;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF设备列表适配器
 *
 * 功能说明：
 * - 在RecyclerView中显示ONVIF设备列表
 * - 处理设备项的点击事件（详情、连接、流媒体、菜单）
 * - 显示设备基本信息（名称、IP、型号、认证状态）
 * - 根据认证状态显示不同的操作按钮
 *
 * 使用方式：
 * - 创建时传入OnDeviceClickListener监听器
 * - 通过setDevices()设置设备列表
 * - 通过addDevice()添加单个设备
 * - 通过updateDevice()更新设备状态
 */
public class OnvifDeviceAdapter extends RecyclerView.Adapter<OnvifDeviceAdapter.ViewHolder> {

    /* 设备数据列表 */
    private List<OnvifDevice> deviceList;

    /* 设备点击事件监听器 */
    private OnDeviceClickListener clickListener;

    /**
     * 设备点击事件回调接口
     *
     * 定义四种点击事件：
     * - onDeviceClick: 点击设备项（显示详情）
     * - onDeviceConnect: 点击连接按钮（进行认证）
     * - onDeviceStream: 点击流媒体按钮（获取RTSP流）
     * - onDeviceMenu: 点击菜单按钮（显示功能列表）
     */
    public interface OnDeviceClickListener {
        /**
         * 设备项被点击（显示详情）
         * @param device 被点击的设备对象
         */
        void onDeviceClick(OnvifDevice device);

        /**
         * 连接按钮被点击（进行设备认证）
         * @param device 需要认证的设备对象
         */
        void onDeviceConnect(OnvifDevice device);

        /**
         * 流媒体按钮被点击（获取RTSP流）
         * @param device 需要获取流的设备对象
         */
        void onDeviceStream(OnvifDevice device);

        /**
         * 菜单按钮被点击（显示功能菜单）
         * @param device 需要显示菜单的设备对象
         */
        void onDeviceMenu(OnvifDevice device);

        /**
         * 设备项被长按（显示完整详情）
         * @param device 被长按的设备对象
         */
        void onDeviceLongClick(OnvifDevice device);
    }

    public OnvifDeviceAdapter() {
        this.deviceList = new ArrayList<>();
    }

    /**
     * 构造函数（带监听器）
     *
     * @param clickListener 设备点击事件监听器
     */
    public OnvifDeviceAdapter(OnDeviceClickListener clickListener) {
        this.deviceList = new ArrayList<>();
        this.clickListener = clickListener;
    }

    /**
     * 设置设备列表
     *
     * 替换所有设备数据并刷新整个列表显示。
     *
     * @param devices 新的设备列表（如果为null则设为空列表）
     */
    public void setDevices(List<OnvifDevice> devices) {
        this.deviceList = devices != null ? devices : new ArrayList<>();
        notifyDataSetChanged();
    }

    /**
     * 添加单个设备
     *
     * 检查是否已存在相同UUID的设备，避免重复添加。
     *
     * @param device 要添加的设备对象
     */
    public void addDevice(OnvifDevice device) {
        // 避免重复添加（通过UUID判断）
        for (OnvifDevice d : deviceList) {
            if (d.getUuid() != null && d.getUuid().equals(device.getUuid())) {
                return;
            }
        }
        deviceList.add(device);
        notifyItemInserted(deviceList.size() - 1);
    }

    /**
     * 更新单个设备
     *
     * 根据UUID查找设备并更新，只刷新对应项。
     *
     * @param device 更新后的设备对象
     */
    public void updateDevice(OnvifDevice device) {
        for (int i = 0; i < deviceList.size(); i++) {
            if (deviceList.get(i).getUuid() != null &&
                deviceList.get(i).getUuid().equals(device.getUuid())) {
                deviceList.set(i, device);
                notifyItemChanged(i);
                return;
            }
        }
    }

    /**
     * 清空所有设备
     *
     * 移除所有设备并刷新列表显示。
     */
    public void clearDevices() {
        deviceList.clear();
        notifyDataSetChanged();
    }

    /**
     * 获取设备列表
     *
     * @return 当前设备列表
     */
    public List<OnvifDevice> getDeviceList() {
        return deviceList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onvif_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OnvifDevice device = deviceList.get(position);

        holder.cardView.setCardBackgroundColor(ThemeManager.getSurfaceColor(holder.itemView.getContext()));
        holder.nameText.setText(device.getDisplayName());
        holder.addressText.setText((device.getIpAddress() != null ? device.getIpAddress() : "未知") + ":" + device.getPort());

        // 显示制造商和型号
        StringBuilder info = new StringBuilder();
        if (device.getManufacturer() != null && !device.getManufacturer().isEmpty()) {
            info.append(device.getManufacturer());
        }
        if (device.getModel() != null && !device.getModel().isEmpty()) {
            if (info.length() > 0) info.append(" ");
            info.append(device.getModel());
        }
        holder.infoText.setText(info.toString());

        // 显示认证状态
        if (device.isAuthenticated()) {
            holder.statusText.setText("已认证");
            holder.statusText.setTextColor(0xFF66BB6A);
            // 认证成功后显示菜单按钮
            holder.menuButton.setVisibility(View.VISIBLE);
        } else {
            holder.statusText.setText("未认证");
            holder.statusText.setTextColor(0xFFFFA726);
            holder.menuButton.setVisibility(View.GONE);
        }

        // 按钮点击
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onDeviceClick(device);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (clickListener != null) {
                clickListener.onDeviceLongClick(device);
            }
            return true;
        });

        holder.connectButton.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onDeviceConnect(device);
            }
        });

        holder.streamButton.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onDeviceStream(device);
            }
        });

        holder.menuButton.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onDeviceMenu(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView nameText;
        TextView addressText;
        TextView infoText;
        TextView statusText;
        ImageButton connectButton;
        ImageButton streamButton;
        ImageButton menuButton;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            nameText = itemView.findViewById(R.id.nameText);
            addressText = itemView.findViewById(R.id.addressText);
            infoText = itemView.findViewById(R.id.infoText);
            statusText = itemView.findViewById(R.id.statusText);
            connectButton = itemView.findViewById(R.id.connectButton);
            streamButton = itemView.findViewById(R.id.streamButton);
            menuButton = itemView.findViewById(R.id.menuButton);
        }
    }
}
