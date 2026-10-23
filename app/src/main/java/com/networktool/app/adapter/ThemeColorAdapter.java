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
import com.networktool.app.model.ThemeColor;
import com.networktool.app.util.ThemeManager;

import java.util.List;

/**
 * 主题颜色卡片适配器
 */
public class ThemeColorAdapter extends RecyclerView.Adapter<ThemeColorAdapter.ViewHolder> {

    private final List<ThemeColor> colorList;
    private final OnColorClickListener clickListener;

    public interface OnColorClickListener {
        void onColorClick(ThemeColor themeColor, int position);
    }

    public ThemeColorAdapter(List<ThemeColor> colorList, OnColorClickListener clickListener) {
        this.colorList = colorList;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_theme_color, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ThemeColor themeColor = colorList.get(position);
        Context context = holder.itemView.getContext();

        int currentThemeColor = ThemeManager.getThemeColor();
        int accentColor = ThemeManager.getAccessibleThemeColor(context);
        int surfaceColor = ThemeManager.getSurfaceColor(context);
        int outlineColor = ThemeManager.getOutlineVariantColor(context);
        int onSurfaceColor = ThemeManager.getOnSurfaceColor(context);
        int onSurfaceVariantColor = ThemeManager.getOnSurfaceVariantColor(context);

        int swatchColor = themeColor.isRandom()
                ? currentThemeColor : themeColor.getColor();
        boolean selected = themeColor.isSelected();

        holder.themeCard.setStrokeWidth(dp(context, selected ? 2 : 1));
        holder.themeCard.setStrokeColor(selected ? accentColor : outlineColor);
        ThemeManager.setupResultCard(holder.themeCard, context);
        holder.themeCard.setRippleColor(ColorStateList.valueOf(
                ThemeManager.blendColorWithBase(accentColor, surfaceColor, 28)));

        holder.colorCard.setCardBackgroundColor(swatchColor);
        holder.randomIcon.setVisibility(themeColor.isRandom() ? View.VISIBLE : View.GONE);
        holder.randomIcon.setColorFilter(ThemeManager.getContrastColor(swatchColor));

        holder.nameText.setText(themeColor.getName());
        holder.nameText.setTextColor(onSurfaceColor);
        holder.colorValueText.setText(ThemeManager.colorToHex(swatchColor));
        holder.colorValueText.setTextColor(onSurfaceVariantColor);

        if (selected) {
            holder.checkCard.setVisibility(View.VISIBLE);
            holder.checkCard.setCardBackgroundColor(accentColor);
            holder.checkIcon.setColorFilter(ThemeManager.getContrastColor(accentColor));
        } else {
            holder.checkCard.setVisibility(View.GONE);
        }

        String description = themeColor.getName() + "，" + ThemeManager.colorToHex(swatchColor);
        if (themeColor.isRandom()) {
            description += selected
                    ? "，已选中，再次点击生成新的随机颜色"
                    : "，点击生成新的随机颜色";
        } else if (selected) {
            description += "，已选中";
        }
        holder.themeCard.setContentDescription(description);

        holder.themeCard.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onColorClick(themeColor, holder.getBindingAdapterPosition());
            }
        });
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return colorList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView themeCard;
        final MaterialCardView colorCard;
        final MaterialCardView checkCard;
        final ImageView checkIcon;
        final ImageView randomIcon;
        final TextView nameText;
        final TextView colorValueText;

        ViewHolder(View itemView) {
            super(itemView);
            themeCard = itemView.findViewById(R.id.themeCard);
            colorCard = itemView.findViewById(R.id.colorCard);
            checkCard = itemView.findViewById(R.id.checkCard);
            checkIcon = itemView.findViewById(R.id.checkIcon);
            randomIcon = itemView.findViewById(R.id.randomIcon);
            nameText = itemView.findViewById(R.id.nameText);
            colorValueText = itemView.findViewById(R.id.colorValueText);
        }
    }
}
