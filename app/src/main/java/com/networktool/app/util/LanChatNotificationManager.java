package com.networktool.app.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.networktool.app.R;
import com.networktool.app.activity.tool.LanChatRoomActivity;

/**
 * 局域网聊天通知管理器
 * 
 * 用于显示收到新消息的通知
 */
public class LanChatNotificationManager {
    
    private static final String CHANNEL_ID = "lan_chat_messages";
    private static final String CHANNEL_NAME = "局域网聊天消息";
    private static final int NOTIFICATION_ID = 1001;
    
    private final Context context;
    private final NotificationManager notificationManager;
    
    private static LanChatNotificationManager instance;
    
    public static synchronized LanChatNotificationManager getInstance(Context context) {
        if (instance == null) {
            instance = new LanChatNotificationManager(context.getApplicationContext());
        }
        return instance;
    }
    
    private LanChatNotificationManager(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        // 创建通知渠道（Android 8.0+）
        createNotificationChannel();
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("收到局域网聊天消息时显示通知");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 250, 250});
            
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * 显示收到消息的通知
     */
    public void showMessageNotification(String senderName, String senderIp, String messageContent) {
        showMessageNotification(senderName, senderIp, messageContent, false, null, null);
    }

    /**
     * 显示收到消息的通知（支持房间消息）
     */
    public void showMessageNotification(String senderName, String senderIp, String messageContent,
                                         boolean isRoom, String roomCode, String roomName) {
        if (notificationManager == null) return;
        
        // 创建点击通知后打开聊天页面的Intent
        Intent intent = new Intent(context, LanChatRoomActivity.class);
        if (isRoom && roomCode != null) {
            intent.putExtra("is_room", true);
            intent.putExtra("room_code", roomCode);
            intent.putExtra("room_name", roomName);
        } else {
            intent.putExtra("peer_ip", senderIp);
            intent.putExtra("peer_name", senderName);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    context, 
                    0, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(
                    context, 
                    0, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
        
        // 构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(senderName != null ? senderName : senderIp)
                .setContentText(messageContent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL);
        
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
    
    /**
     * 取消通知
     */
    public void cancelNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }
}
