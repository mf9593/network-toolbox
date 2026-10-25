package com.networktool.app.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networktool.app.R;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.ThemeManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * QQ 风格聊天消息适配器
 * 支持：
 * - 发送/接收消息气泡样式
 * - 头像显示
 * - 发送者名称显示（群聊模式）
 * - 时间标签
 * - 显示发送者 IP（可选）
 * - 图片消息预览
 * - 文件消息显示和下载
 */
public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder> {

    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;

    private List<LanChatManager.ChatMessage> messages = new ArrayList<>();
    private String myUserName;
    private String myDeviceId;
    private int myTcpPort;
    private boolean isGroupChat = false;  // 是否群聊模式
    private Context context;
    private OnMessageClickListener messageClickListener;

    /**
     * 消息点击监听器
     */
    public interface OnMessageClickListener {
        void onImageClick(LanChatManager.ChatMessage message, String imagePath);
        void onFileDownloadClick(LanChatManager.ChatMessage message);
        void onWifiBackupImportClick(LanChatManager.ChatMessage message);
    }

    public ChatMessageAdapter(Context context, String myUserName) {
        this.context = context;
        this.myUserName = myUserName;
    }

    public ChatMessageAdapter(Context context, String myUserName, boolean isGroupChat) {
        this.context = context;
        this.myUserName = myUserName;
        this.isGroupChat = isGroupChat;
    }

    public void setOnMessageClickListener(OnMessageClickListener listener) {
        this.messageClickListener = listener;
    }

    public void setMessages(List<LanChatManager.ChatMessage> messages) {
        // 必须创建副本，避免与外部列表引用同一对象导致 addMessage 时重复添加
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void addMessage(LanChatManager.ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void setMyDeviceId(String deviceId) {
        this.myDeviceId = deviceId;
    }

    public void setMyTcpPort(int port) {
        this.myTcpPort = port;
    }

    @Override
    public int getItemViewType(int position) {
        LanChatManager.ChatMessage msg = messages.get(position);
        // 判断是否是"我"发送的消息：优先用 deviceId + 本机端口，
        // 昵称可能重名（默认昵称都一样），同名时会把对方消息画到右侧
        if (myDeviceId != null && !myDeviceId.isEmpty() && msg.getDeviceId() != null
                && myDeviceId.equals(msg.getDeviceId())) {
            if (msg.getSenderPort() <= 0 || myTcpPort <= 0 || msg.getSenderPort() == myTcpPort) {
                return TYPE_SENT;
            }
        }
        return TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        LanChatManager.ChatMessage msg = messages.get(position);
        int viewType = getItemViewType(position);

        // 格式化时间
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String timeStr = sdf.format(new Date(msg.getTimestamp()));

        // 判断是否显示时间（相邻消息间隔 5 分钟以上才显示）
        boolean showTime = shouldShowTime(position, msg.getTimestamp());
        holder.timeLabel.setVisibility(showTime ? View.VISIBLE : View.GONE);
        if (showTime) {
            holder.timeLabel.setText(timeStr);
        }

        // 获取是否显示 IP 的设置
        boolean showIp = isShowSenderIpEnabled();

        // 隐藏所有视图
        holder.messageBubble.setVisibility(View.GONE);
        holder.imagePreview.setVisibility(View.GONE);
        holder.fileContainer.setVisibility(View.GONE);

        // 根据消息类型显示不同内容
        String msgType = msg.getType();
        if ("text".equals(msgType) || TextUtils.isEmpty(msgType)) {
            // 文本消息
            holder.messageBubble.setVisibility(View.VISIBLE);
            holder.messageBubble.setText(msg.getContent());
            setupBubbleStyle(holder, msg, viewType);
        } else if ("image".equals(msgType)) {
            // 图片消息
            holder.imagePreview.setVisibility(View.VISIBLE);
            String imageData = msg.getFileData();
            if (!TextUtils.isEmpty(imageData)) {
                try {
                    byte[] decoded = Base64.decode(imageData, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    holder.imagePreview.setImageBitmap(bitmap);

                    // 点击图片查看大图
                    holder.imagePreview.setOnClickListener(v -> {
                        if (messageClickListener != null) {
                            messageClickListener.onImageClick(msg, imageData);
                        }
                    });
                } catch (Exception e) {
                    holder.imagePreview.setImageResource(R.drawable.ic_broken_image);
                }
            } else {
                // fileData为空时，尝试从本地文件路径加载
                String filePath = msg.getFilePath();
                if (!TextUtils.isEmpty(filePath)) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeFile(filePath);
                        if (bitmap != null) {
                            holder.imagePreview.setImageBitmap(bitmap);
                            holder.imagePreview.setOnClickListener(v -> {
                                if (messageClickListener != null) {
                                    messageClickListener.onImageClick(msg, filePath);
                                }
                            });
                        } else {
                            holder.imagePreview.setImageResource(R.drawable.ic_broken_image);
                        }
                    } catch (Exception e) {
                        holder.imagePreview.setImageResource(R.drawable.ic_broken_image);
                    }
                } else {
                    holder.imagePreview.setImageResource(R.drawable.ic_broken_image);
                }
            }
            setupBubbleStyle(holder, msg, viewType);
        } else if ("file".equals(msgType)) {
            // 文件消息
            holder.fileContainer.setVisibility(View.VISIBLE);
            holder.fileName.setText(msg.getFileName());
            holder.fileSize.setText(formatFileSize(msg.getFileSize()));

            // 设置文件图标
            int fileIconRes = getFileIcon(msg.getFileName());
            holder.fileIcon.setImageResource(fileIconRes);

            // 下载按钮点击事件
            holder.downloadButton.setOnClickListener(v -> {
                if (messageClickListener != null) {
                    messageClickListener.onFileDownloadClick(msg);
                } else {
                    Toast.makeText(context, "开始下载：" + msg.getFileName(), Toast.LENGTH_SHORT).show();
                }
            });

            setupBubbleStyle(holder, msg, viewType);
        } else if ("wifi_backup".equals(msgType)) {
            // WiFi备份消息
            holder.fileContainer.setVisibility(View.VISIBLE);
            holder.fileIcon.setImageResource(R.drawable.ic_wifi);
            holder.fileName.setText("WiFi备份数据");
            holder.fileSize.setText("来自 " + msg.getSender() + " 的WiFi备份");

            // 导入按钮点击事件
            holder.downloadButton.setOnClickListener(v -> {
                if (messageClickListener != null) {
                    messageClickListener.onWifiBackupImportClick(msg);
                }
            });

            setupBubbleStyle(holder, msg, viewType);
        } else {
            // 未知消息类型：显示占位文本
            holder.messageBubble.setVisibility(View.VISIBLE);
            holder.messageBubble.setText("[系统消息]");
            setupBubbleStyle(holder, msg, viewType);
        }

        if (viewType == TYPE_SENT) {
            // 我发送的消息
            holder.myAvatar.setVisibility(View.VISIBLE);
            holder.myAvatar.setImageResource(R.drawable.ic_user_online); // 设置默认头像
            holder.senderAvatar.setVisibility(View.GONE);

            // 发送消息靠右对齐
            if (holder.messageContentWrapper != null) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) holder.messageContentWrapper.getLayoutParams();
                lp.gravity = android.view.Gravity.END;
                holder.messageContentWrapper.setLayoutParams(lp);
            }

            // 群聊模式显示发送者名称
            if (isGroupChat) {
                holder.senderName.setVisibility(View.VISIBLE);
                holder.senderName.setText("我");
            } else {
                holder.senderName.setVisibility(View.GONE);
            }

            // IP 显示（自己发送的消息不显示 IP）
            holder.senderIp.setVisibility(View.GONE);

        } else {
            // 接收的消息
            holder.senderAvatar.setVisibility(View.VISIBLE);
            holder.senderAvatar.setImageResource(R.drawable.ic_user_online); // 设置默认头像
            holder.myAvatar.setVisibility(View.GONE);

            // 接收消息靠左对齐
            if (holder.messageContentWrapper != null) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) holder.messageContentWrapper.getLayoutParams();
                lp.gravity = android.view.Gravity.START;
                holder.messageContentWrapper.setLayoutParams(lp);
            }

            // 群聊模式显示发送者名称
            if (isGroupChat) {
                holder.senderName.setVisibility(View.VISIBLE);
                holder.senderName.setText(msg.getSender());
                // 为不同用户分配不同颜色
                holder.senderName.setTextColor(getUserColor(msg.getSender()));
            } else {
                holder.senderName.setVisibility(View.GONE);
            }

            // 显示发送者 IP（如果开启）
            if (showIp) {
                String senderIp = msg.getSenderIp();
                int senderPort = msg.getSenderPort();
                if (!TextUtils.isEmpty(senderIp) && senderPort > 0) {
                    holder.senderIp.setVisibility(View.VISIBLE);
                    holder.senderIp.setText(senderIp + ":" + senderPort);
                } else if (!TextUtils.isEmpty(senderIp)) {
                    holder.senderIp.setVisibility(View.VISIBLE);
                    holder.senderIp.setText(senderIp);
                } else {
                    holder.senderIp.setVisibility(View.GONE);
                }
            } else {
                holder.senderIp.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 设置气泡样式
     */
    private void setupBubbleStyle(MessageViewHolder holder, LanChatManager.ChatMessage msg, int viewType) {
        float radius = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 16,
                context.getResources().getDisplayMetrics());
        float smallRadius = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 4,
                context.getResources().getDisplayMetrics());

        android.graphics.drawable.GradientDrawable bubble = new android.graphics.drawable.GradientDrawable();
        if (viewType == TYPE_SENT) {
            bubble.setColor(ThemeManager.getThemeColor());
            bubble.setCornerRadii(new float[]{radius, radius, radius, radius, smallRadius, smallRadius, radius, radius});
            holder.messageBubble.setTextColor(ThemeManager.getContrastColor(ThemeManager.getThemeColor()));
        } else {
            bubble.setColor(ThemeManager.getSurfaceColor(context));
            bubble.setCornerRadii(new float[]{radius, radius, radius, radius, radius, radius, smallRadius, smallRadius});
            TypedValue tv = new TypedValue();
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutline, tv, true);
            bubble.setStroke(1, tv.data);
            holder.messageBubble.setTextColor(ThemeManager.getContrastColor(ThemeManager.getSurfaceColor(context)));
        }
        holder.messageBubble.setBackground(bubble);
    }

    /**
     * 判断是否应该显示时间标签
     */
    private boolean shouldShowTime(int position, long currentTime) {
        if (position == 0) return true;

        LanChatManager.ChatMessage prevMsg = messages.get(position - 1);
        long prevTime = prevMsg.getTimestamp();

        // 间隔 5 分钟以上显示
        return (currentTime - prevTime) > 5 * 60 * 1000;
    }

    /**
     * 根据用户名获取颜色（基于主题色生成多样化的用户名颜色）
     */
    private int getUserColor(String username) {
        // 名字颜色固定为黑色
        return android.graphics.Color.BLACK;
    }

    /**
     * 获取是否显示发送者 IP 的设置
     */
    private boolean isShowSenderIpEnabled() {
        if (context == null) return false;
        SharedPreferences sp = context.getSharedPreferences("lan_chat", Context.MODE_PRIVATE);
        return sp.getBoolean("show_sender_ip", false);
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 根据文件扩展名获取图标
     */
    private int getFileIcon(String fileName) {
        if (fileName == null) return R.drawable.ic_file;
        
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                return R.drawable.ic_image;
            case "pdf":
                return R.drawable.ic_pdf;
            case "doc":
            case "docx":
                return R.drawable.ic_word;
            case "xls":
            case "xlsx":
                return R.drawable.ic_excel;
            case "ppt":
            case "pptx":
                return R.drawable.ic_powerpoint;
            case "txt":
                return R.drawable.ic_text;
            case "zip":
            case "rar":
            case "7z":
                return R.drawable.ic_archive;
            case "apk":
                return R.drawable.ic_apk;
            default:
                return R.drawable.ic_file;
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageBubble;
        TextView timeLabel;
        TextView senderName;
        TextView senderIp;
        ImageView senderAvatar;
        ImageView myAvatar;
        ImageView imagePreview;
        View fileContainer;
        ImageView fileIcon;
        TextView fileName;
        TextView fileSize;
        ImageView downloadButton;
        FrameLayout messageContentArea;
        LinearLayout messageContentWrapper;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageBubble = itemView.findViewById(R.id.messageBubble);
            timeLabel = itemView.findViewById(R.id.timeLabel);
            senderName = itemView.findViewById(R.id.senderName);
            senderIp = itemView.findViewById(R.id.senderIp);
            senderAvatar = itemView.findViewById(R.id.senderAvatar);
            myAvatar = itemView.findViewById(R.id.myAvatar);
            imagePreview = itemView.findViewById(R.id.imagePreview);
            fileContainer = itemView.findViewById(R.id.fileContainer);
            fileIcon = itemView.findViewById(R.id.fileIcon);
            fileName = itemView.findViewById(R.id.fileName);
            fileSize = itemView.findViewById(R.id.fileSize);
            downloadButton = itemView.findViewById(R.id.downloadButton);
            messageContentArea = itemView.findViewById(R.id.messageContentArea);
            messageContentWrapper = itemView.findViewById(R.id.messageContentWrapper);
        }
    }
}
