package com.networktool.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.PortScanResult;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.UrlOpenHelper;

import java.util.List;

/**
 * 端口/IP扫描结果适配器
 * 副文本使用主题色显示
 */
public class PortScanAdapter extends BaseAdapter {

    private Context context;
    private List<PortScanResult> results;
    private int themeColor;

    public PortScanAdapter(Context context, List<PortScanResult> results) {
        this.context = context;
        this.results = results;
        this.themeColor = ThemeManager.getThemeColor();
    }

    @Override
    public int getCount() {
        return results.size();
    }

    @Override
    public Object getItem(int position) {
        return results.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_port_scan, parent, false);
            holder = new ViewHolder();
            holder.cardView = convertView.findViewById(R.id.cardView);
            holder.titleText = convertView.findViewById(R.id.titleText);
            holder.contentText = convertView.findViewById(R.id.contentText);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        PortScanResult result = results.get(position);
        holder.titleText.setText(result.getIp());
        holder.contentText.setText(result.getDisplayText());

        int surfaceColor = ThemeManager.getSurfaceColor(context);
        holder.cardView.setCardBackgroundColor(surfaceColor);

        // 副文本跟随主题颜色
        holder.contentText.setTextColor(ThemeManager.getAccessibleThemeColor(context));

        convertView.setOnClickListener(v -> {
            try {
                // 按用户设置的跳转方式打开（内置浏览器或默认浏览器）
                UrlOpenHelper.open(context, result.getUrl(), result.getDisplayText());
            } catch (Exception e) {
                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        });

        return convertView;
    }

    static class ViewHolder {
        MaterialCardView cardView;
        TextView titleText;
        TextView contentText;
    }
}
