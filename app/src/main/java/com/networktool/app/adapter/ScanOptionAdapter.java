package com.networktool.app.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import java.util.List;

/**
 * 底部弹窗里的「单选一行」列表（排序方式、协议筛选共用）。
 *
 * <p>每行是「标题 + 一句说明 + 选中勾」，比一堆 chip 更容易看清每个选项是干什么的。
 * 选中态用主题色描边 + 勾，颜色全部走 {@link ThemeManager}，切主题自动跟随。</p>
 */
public class ScanOptionAdapter extends RecyclerView.Adapter<ScanOptionAdapter.ViewHolder> {

    public static class Option {
        /** 业务键，null 表示「全部/默认」 */
        public final String key;
        public final String title;
        public final String desc;

        public Option(String key, String title, String desc) {
            this.key = key;
            this.title = title;
            this.desc = desc;
        }
    }

    public interface OnOptionPicked {
        void onOptionPicked(Option option);
    }

    private final List<Option> options;
    private final OnOptionPicked listener;
    private String selectedKey;

    public ScanOptionAdapter(List<Option> options, String selectedKey, OnOptionPicked listener) {
        this.options = options;
        this.selectedKey = selectedKey;
        this.listener = listener;
    }

    /** 当前选中项的 key；「全部/默认」为 null。用于点「保存」时取值。 */
    public String getSelectedKey() {
        return selectedKey;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scan_option, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Option option = options.get(position);
        Context context = holder.itemView.getContext();
        boolean selected = option.key == null
                ? selectedKey == null
                : option.key.equals(selectedKey);

        holder.title.setText(option.title);
        holder.desc.setText(option.desc);

        int accent = ThemeManager.getAccessibleThemeColor(context);
        holder.card.setStrokeColor(selected ? accent : ThemeManager.getOutlineVariantColor(context));
        holder.card.setStrokeWidth(selected ? dp(context, 2) : dp(context, 1));
        holder.card.setCardBackgroundColor(selected
                ? ThemeManager.getBadgeSurfaceColor(context) : 0x00000000);
        holder.title.setTextColor(selected ? accent : ThemeManager.getOnSurfaceColor(context));
        holder.check.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.check.setImageTintList(ColorStateList.valueOf(accent));

        holder.itemView.setOnClickListener(v -> {
            if (listener == null) return;
            selectedKey = option.key;
            // 立刻刷新选中态，弹窗关闭前也能看到自己点了哪个
            notifyDataSetChanged();
            listener.onOptionPicked(option);
        });
    }

    @Override
    public int getItemCount() {
        return options.size();
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView title;
        final TextView desc;
        final ImageView check;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            title = itemView.findViewById(R.id.optionTitle);
            desc = itemView.findViewById(R.id.optionDesc);
            check = itemView.findViewById(R.id.optionCheck);
        }
    }
}
