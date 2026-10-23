package com.networktool.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networktool.app.R;
import com.networktool.app.model.DeviceInfoItem;
import com.networktool.app.util.ThemeManager;

import java.util.List;

/**
 * 设备信息适配器
 */
public class DeviceInfoAdapter extends RecyclerView.Adapter<DeviceInfoAdapter.ViewHolder> {

    private List<DeviceInfoItem> infoList;
    private Context context;

    public DeviceInfoAdapter(List<DeviceInfoItem> infoList) {
        this.infoList = infoList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device_info, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DeviceInfoItem item = infoList.get(position);
        
        holder.nameText.setText(item.getName());
        holder.valueText.setText(item.getValue());
        holder.valueText.setTextColor(ThemeManager.getAccessibleThemeColor(context));

        // 长按复制
        holder.itemView.setOnLongClickListener(v -> {
            copyToClipboard(item.getName() + ": " + item.getValue());
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = 
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("device_info", text);
        clipboard.setPrimaryClip(clip);
    }

    @Override
    public int getItemCount() {
        return infoList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameText;
        TextView valueText;

        ViewHolder(View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.nameText);
            valueText = itemView.findViewById(R.id.valueText);
        }
    }
}
