package com.networktool.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networktool.app.R;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 好友列表适配器（QQ风格）
 * 支持好友和房间两种类型的分类型渲染
 */
public class LanPeerAdapter extends RecyclerView.Adapter<LanPeerAdapter.PeerViewHolder> {

    private List<LanChatManager.LanPeer> peers = new ArrayList<>();
    private OnPeerClickListener clickListener;

    public LanPeerAdapter(OnPeerClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setPeers(List<LanChatManager.LanPeer> peers) {
        this.peers = peers != null ? peers : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lan_peer, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        LanChatManager.LanPeer peer = peers.get(position);

        // 房间特殊处理
        if (peer.isRoom()) {
            // 房间名称：优先使用roomName，否则使用userName去掉[房间]后缀
            String displayName = peer.getRoomName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = peer.getUserName();
                // 去掉可能存在的 [房间] 或 [房间号] 后缀
                if (displayName != null) {
                    displayName = displayName.replaceAll("\\s*\\[.*?\\]\\s*$", "");
                }
            }
            if (displayName == null || displayName.isEmpty()) {
                displayName = "聊天室";
            }
            holder.nameText.setText(displayName);

            // 显示房间标签
            holder.roomLabel.setVisibility(View.VISIBLE);
            holder.roomLabel.setText("房间");

            // 房间状态：显示房间号
            String statusText;
            if (peer.isOnline()) {
                statusText = "在线";
            } else {
                statusText = "离线";
            }
            if (peer.getRoomCode() != null && !peer.getRoomCode().isEmpty()) {
                statusText += " · 房间号: " + peer.getRoomCode();
            }
            holder.statusText.setText(statusText);

            // 房主IP显示
            String ownerIp = peer.getOwnerIp();
            if (ownerIp != null && !ownerIp.isEmpty()) {
                holder.ipText.setText("房主: " + ownerIp);
            } else {
                holder.ipText.setText(peer.getIp());
            }

            // 房间头像 - 蓝色标记
            holder.avatarImage.setImageResource(R.drawable.ic_user_online);
            holder.statusText.setTextColor(ThemeManager.getAccessibleThemeColor(holder.itemView.getContext()));

        } else {
            // 好友标准处理
            holder.nameText.setText(peer.getDisplayName());

            // 隐藏房间标签
            holder.roomLabel.setVisibility(View.GONE);

            // 好友状态 - 显示UID作为主键
            holder.statusText.setText(peer.getStatusText());
            holder.ipText.setText("UID:" + peer.getUid() + " · " + peer.getIp());

            // 根据在线状态和好友状态设置头像
            if (!peer.isFriend()) {
                // 陌生人 - 橙色标记
                holder.avatarImage.setImageResource(R.drawable.ic_user_stranger);
                holder.statusText.setTextColor(ThemeManager.getAccessibleThemeColor(holder.itemView.getContext()));
            } else if (peer.isOnline()) {
                // 好友在线 - 绿色标记
                holder.avatarImage.setImageResource(R.drawable.ic_user_online);
                holder.statusText.setTextColor(ThemeManager.getAccessibleThemeColor(holder.itemView.getContext()));
            } else {
                // 好友离线 - 灰色标记
                holder.avatarImage.setImageResource(R.drawable.ic_user_offline);
                Context ctx = holder.itemView.getContext();
                holder.statusText.setTextColor(ThemeManager.isDarkMode(ctx)
                        ? android.graphics.Color.LTGRAY : android.graphics.Color.GRAY);
            }
        }

        // 卡片背景跟随主题色（90%白+10%主题色）
        holder.itemView.setBackgroundColor(ThemeManager.getSurfaceColor(holder.itemView.getContext()));

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onPeerClick(peer);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (clickListener != null) {
                clickListener.onPeerLongClick(peer);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    static class PeerViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImage;
        TextView nameText;
        TextView roomLabel;
        TextView statusText;
        TextView ipText;
        TextView unreadBadge;

        PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarImage = itemView.findViewById(R.id.avatarImage);
            nameText = itemView.findViewById(R.id.nameText);
            roomLabel = itemView.findViewById(R.id.roomLabel);
            statusText = itemView.findViewById(R.id.statusText);
            ipText = itemView.findViewById(R.id.ipText);
            unreadBadge = itemView.findViewById(R.id.unreadBadge);
        }
    }

    /**
     * 好友点击监听器
     */
    public interface OnPeerClickListener {
        void onPeerClick(LanChatManager.LanPeer peer);
        void onPeerLongClick(LanChatManager.LanPeer peer);
    }
}