package com.networktool.app.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.networktool.app.R;
import com.networktool.app.model.DnsItem;

import java.util.List;

/**
 * DNS服务器列表适配器
 * 
 * 用于显示DNS服务器列表，包含：
 * - DNS服务器IP地址
 * - 服务器名称
 * - 延迟时间（根据延迟显示不同颜色）
 */
public class DnsAdapter extends BaseAdapter {

    /** 上下文 */
    private Context context;
    /** DNS服务器数据列表 */
    private List<DnsItem> items;

    /**
     * 创建DNS适配器
     * @param context 上下文
     * @param items DNS服务器数据列表
     */
    public DnsAdapter(Context context, List<DnsItem> items) {
        this.context = context;
        this.items = items;
    }

    /**
     * 获取列表项数量
     * @return 数据项数量
     */
    @Override
    public int getCount() {
        return items.size();
    }

    /**
     * 获取指定位置的数据项
     * @param position 位置索引
     * @return DNS数据项
     */
    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    /**
     * 获取指定位置的ID
     * @param position 位置索引
     * @return 位置索引作为ID
     */
    @Override
    public long getItemId(int position) {
        return position;
    }

    /**
     * 获取列表项视图
     * @param position 位置索引
     * @param convertView 复用的视图
     * @param parent 父视图
     * @return 配置好的列表项视图
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_dns, parent, false);
            holder = new ViewHolder();
            holder.titleText = convertView.findViewById(R.id.titleText);
            holder.textText = convertView.findViewById(R.id.textText);
            holder.delayText = convertView.findViewById(R.id.delayText);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        DnsItem item = items.get(position);
        holder.titleText.setText(item.getIp());
        holder.textText.setText(item.getName());
        holder.delayText.setText(item.getDelay());

        // 根据延迟值设置文字颜色
        int delayColor = getDelayColor(item.getDelay());
        holder.delayText.setTextColor(delayColor);

        return convertView;
    }

    /**
     * 根据延迟值获取对应的显示颜色
     * < 100ms: 绿色
     * 100-200ms: 橙色
     * > 200ms: 红色
     * 待测试/超时: 灰色
     * 
     * @param delayStr 延迟字符串
     * @return 颜色值
     */
    private int getDelayColor(String delayStr) {
        if (delayStr.equals("待测试") || delayStr.equals("超时")) {
            return Color.GRAY;
        }

        try {
            int delay = Integer.parseInt(delayStr.replace(" ms", ""));
            if (delay < 100) {
                return Color.parseColor("#00FF00"); // 绿色
            } else if (delay < 200) {
                return Color.parseColor("#FF8000"); // 橙色
            } else {
                return Color.parseColor("#FF0000"); // 红色
            }
        } catch (Exception e) {
            return Color.GRAY;
        }
    }

    /**
     * 视图持有者类
     */
    static class ViewHolder {
        TextView titleText;
        TextView textText;
        TextView delayText;
    }
}
