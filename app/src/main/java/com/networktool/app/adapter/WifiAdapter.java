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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WiFi 列表适配器
 *
 * <p>同名 SSID 会折叠成一行（信号最强的那台做代表），点击箭头/整行展开，
 * 展开后列出该名称下的所有接入点（同一路由器/多 AP 组的多个 BSSID）。</p>
 */
public class WifiAdapter extends RecyclerView.Adapter<WifiAdapter.ViewHolder> {

    private List<WifiInfoModel> wifiList;
    private final OnWifiClickListener clickListener;
    private final OnWifiLongClickListener longClickListener;
    private final WifiDataManager wifiDataManager;
    private Map<String, List<String>> savedWifiData;

    /** 折叠后的展示行：组头 或 组内的某个 AP */
    private final List<Row> rows = new ArrayList<>();
    /** 当前已展开的分组 key */
    private final java.util.Set<String> expandedGroups = new java.util.HashSet<>();

    /** 列表里的一行 */
    private static final class Row {
        final String groupKey;
        final WifiInfoModel wifi;
        final boolean child;
        final int memberCount;

        Row(String groupKey, WifiInfoModel wifi, boolean child, int memberCount) {
            this.groupKey = groupKey;
            this.wifi = wifi;
            this.child = child;
            this.memberCount = memberCount;
        }

        boolean hasChildren() {
            return memberCount > 1;
        }
    }

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
        rebuildRows();
    }

    public void updateData(List<WifiInfoModel> newList) {
        this.wifiList = newList != null ? newList : new ArrayList<>();
        rebuildRows();
        notifyDataSetChanged();
    }

    public void updateWifiData() {
        if (wifiDataManager != null) {
            savedWifiData = wifiDataManager.getAllWifiData();
        }
        notifyDataSetChanged();
    }

    /**
     * 按 SSID 分组并重排行。
     *
     * 排序规则：组按「最强信号」从强到弱，组内也按信号从强到弱。
     * 隐藏 SSID（名称为空）无法判断是否同名，每台单独成组。
     */
    private void rebuildRows() {
        rows.clear();

        // 1. 分组
        LinkedHashMap<String, List<WifiInfoModel>> groups = new LinkedHashMap<>();
        int hiddenIndex = 0;
        for (WifiInfoModel wifi : wifiList) {
            if (wifi == null) continue;
            String ssid = wifi.getSsid();
            String key;
            if (ssid == null || ssid.trim().isEmpty()) {
                key = "__hidden__" + hiddenIndex++;
            } else {
                key = "ssid:" + ssid.trim();
            }
            List<WifiInfoModel> members = groups.get(key);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(key, members);
            }
            members.add(wifi);
        }

        // 2. 组内按信号从强到弱
        List<Map.Entry<String, List<WifiInfoModel>>> groupEntries =
                new ArrayList<>(groups.entrySet());
        for (Map.Entry<String, List<WifiInfoModel>> entry : groupEntries) {
            entry.getValue().sort((a, b) -> Integer.compare(b.getLevel(), a.getLevel()));
        }

        // 3. 组之间按代表（最强）信号排序
        // dBm 数值越大（越接近 0）信号越强，所以要按降序排
        groupEntries.sort((a, b) -> Integer.compare(
                b.getValue().get(0).getLevel(), a.getValue().get(0).getLevel()));

        // 4. 生成展示行
        for (Map.Entry<String, List<WifiInfoModel>> entry : groupEntries) {
            String key = entry.getKey();
            List<WifiInfoModel> members = entry.getValue();
            rows.add(new Row(key, members.get(0), false, members.size()));
            boolean expanded = expandedGroups.contains(key);
            if (members.size() > 1 && expanded) {
                for (WifiInfoModel member : members) {
                    rows.add(new Row(key, member, true, members.size()));
                }
            }
        }
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
        Row row = rows.get(position);
        WifiInfoModel wifi = row.wifi;
        int accentColor = ThemeManager.getAccessibleThemeColor(holder.itemView.getContext());
        int chipSurface = ThemeManager.getChipSurfaceColor(holder.itemView.getContext());
        int surfaceColor = ThemeManager.getSurfaceColor(holder.itemView.getContext());
        int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(holder.itemView.getContext());

        holder.cardView.setCardBackgroundColor(surfaceColor);
        holder.ssidText.setText(wifi.getSsid() == null || wifi.getSsid().trim().isEmpty()
                ? "隐藏 SSID" : wifi.getSsid());
        // 展开出来的单个 AP 稍作收敛，和组头区分
        holder.ssidText.setTextSize(row.child ? 14f : 16f);
        holder.ssidText.setAlpha(row.child ? 0.85f : 1f);

        // 子项缩进，形成层级
        ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) lp;
            margins.setMarginStart(dp(holder.itemView, row.child ? 24 : 6));
            margins.topMargin = dp(holder.itemView, row.child ? 2 : 4);
            margins.bottomMargin = dp(holder.itemView, row.child ? 2 : 4);
            holder.itemView.setLayoutParams(margins);
        }

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
                ? String.format(Locale.CHINA, "%.1fG", ghz) : "频段 --");

        setChipBackground(holder.freqText, chipSurface, onSurfaceVariant);
        setChipBackground(holder.standardText, chipSurface, onSurfaceVariant);
        setChipBackground(holder.channelText, chipSurface, onSurfaceVariant);
        setChipBackground(holder.signalText, chipSurface, onSurfaceVariant);

        // 同名多 AP：组头显示数量和展开箭头
        boolean showGroupMark = !row.child && row.hasChildren();
        holder.apCountText.setVisibility(showGroupMark ? View.VISIBLE : View.GONE);
        if (showGroupMark) {
            holder.apCountText.setText(row.memberCount + " 个");
            setChipBackground(holder.apCountText, chipSurface, onSurfaceVariant);
        }
        holder.expandIcon.setVisibility(showGroupMark ? View.VISIBLE : View.GONE);
        if (showGroupMark) {
            boolean expanded = expandedGroups.contains(row.groupKey);
            holder.expandIcon.setImageResource(expanded
                    ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            holder.expandIcon.setColorFilter(onSurfaceVariant);
        }

        holder.itemView.setOnClickListener(v -> {
            if (showGroupMark) {
                // 组头：点击展开/收起同名网络
                toggleGroup(row.groupKey);
                return;
            }
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

    /** 展开/收起某个同名分组 */
    private void toggleGroup(String groupKey) {
        if (groupKey == null) return;
        if (!expandedGroups.remove(groupKey)) {
            expandedGroups.add(groupKey);
        }
        rebuildRows();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView cardView;
        final ImageView signalIcon;
        final ImageView lockIcon;
        final ImageView expandIcon;
        final TextView ssidText;
        final TextView bssidText;
        final TextView standardText;
        final TextView channelText;
        final TextView freqText;
        final TextView signalText;
        final TextView apCountText;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            signalIcon = itemView.findViewById(R.id.signalIcon);
            lockIcon = itemView.findViewById(R.id.lockIcon);
            expandIcon = itemView.findViewById(R.id.expandIcon);
            ssidText = itemView.findViewById(R.id.ssidText);
            bssidText = itemView.findViewById(R.id.bssidText);
            standardText = itemView.findViewById(R.id.standardText);
            channelText = itemView.findViewById(R.id.channelText);
            freqText = itemView.findViewById(R.id.freqText);
            signalText = itemView.findViewById(R.id.signalText);
            apCountText = itemView.findViewById(R.id.apCountText);
        }
    }

    /** 列表里的小标签：背景透明，只靠文字色区分层级 */
    private void setChipBackground(TextView chip, int color, int textColor) {
        chip.setBackground(null);
        chip.setTextColor(textColor);
    }
}
