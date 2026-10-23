package com.networktool.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.networktool.app.R;

/**
 * 表情网格适配器
 */
public class EmojiAdapter extends BaseAdapter {

    private String[] emojis;
    private OnEmojiClickListener listener;

    public EmojiAdapter(String[] emojis, OnEmojiClickListener listener) {
        this.emojis = emojis;
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return emojis.length;
    }

    @Override
    public Object getItem(int position) {
        return emojis[position];
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        
        if (convertView == null) {
            convertView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_emoji, parent, false);
            holder = new ViewHolder();
            holder.emojiText = convertView.findViewById(R.id.emojiText);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        
        holder.emojiText.setText(emojis[position]);
        holder.emojiText.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEmojiClick(emojis[position]);
            }
        });
        
        return convertView;
    }

    static class ViewHolder {
        TextView emojiText;
    }

    /**
     * 表情点击监听器
     */
    public interface OnEmojiClickListener {
        void onEmojiClick(String emoji);
    }
}
