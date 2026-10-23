package com.networktool.app.activity.tool;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.LanChatDatabase;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

/**
 * 发现好友Fragment（含聊天设置）
 *
 * 功能：
 * - 显示本机IP、用户名、UID、端口
 * - 修改用户名、隐私设置、清除聊天记录
 * - 手动添加好友（支持IP/UID）
 * - 创建/加入房间
 */
public class LanChatDiscoverFragment extends Fragment {

    private MaterialCardView localInfoCard;
    private MaterialCardView settingsCard;
    private MaterialCardView addFriendCard;
    private MaterialCardView roomManageCard;
    private TextView localIpText;
    private TextView localUsernameText;
    private TextView localUidText;
    private TextView localPortText;
    private TextInputEditText usernameInput;
    private SwitchMaterial showSenderIpSwitch;
    private MaterialButton saveSettingsButton;
    private MaterialButton clearHistoryButton;
    private TextInputEditText addIpInput;
    private MaterialButton addPeerButton;
    private TextInputEditText roomCodeInput;
    private MaterialButton joinRoomButton;
    private MaterialButton createPublicButton;

    private LanChatManager chatManager;
    private LanChatDatabase database;
    private SharedPreferences prefs;

    public static LanChatDiscoverFragment newInstance() {
        return new LanChatDiscoverFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lan_chat_discover, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            chatManager = LanChatManager.getInstance();
            database = LanChatDatabase.getInstance(requireContext());
            prefs = requireContext().getSharedPreferences("lan_chat", android.content.Context.MODE_PRIVATE);

            initViews(view);
            setupLocalInfo();
            loadSettings();
            setupListeners();
        } catch (Exception e) {
            AppErrorLogger.catchError("LanChatDiscoverFragment", "初始化发现页面失败", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次回到页面时刷新本机信息（端口可能变化）
        setupLocalInfo();
    }

    private void initViews(View view) {
        localInfoCard = view.findViewById(R.id.localInfoCard);
        settingsCard = view.findViewById(R.id.settingsCard);
        addFriendCard = view.findViewById(R.id.addFriendCard);
        roomManageCard = view.findViewById(R.id.roomManageCard);
        localIpText = view.findViewById(R.id.localIpText);
        localUsernameText = view.findViewById(R.id.localUsernameText);
        localUidText = view.findViewById(R.id.localUidText);
        localPortText = view.findViewById(R.id.localPortText);
        usernameInput = view.findViewById(R.id.usernameInput);
        showSenderIpSwitch = view.findViewById(R.id.showSenderIpSwitch);
        saveSettingsButton = view.findViewById(R.id.saveSettingsButton);
        clearHistoryButton = view.findViewById(R.id.clearHistoryButton);
        addIpInput = view.findViewById(R.id.addIpInput);
        addPeerButton = view.findViewById(R.id.addPeerButton);
        roomCodeInput = view.findViewById(R.id.roomCodeInput);
        joinRoomButton = view.findViewById(R.id.joinRoomButton);
        createPublicButton = view.findViewById(R.id.createPublicButton);

        ThemeManager.setupResultCard(localInfoCard, requireContext());
        ThemeManager.setupResultCard(settingsCard, requireContext());
        ThemeManager.setupResultCard(addFriendCard, requireContext());
        ThemeManager.setupResultCard(roomManageCard, requireContext());

        // 按钮颜色跟随主题色，文字使用对比色
        ThemeManager.applyPrimaryButton(saveSettingsButton);
        ThemeManager.applyPrimaryButton(addPeerButton);
        ThemeManager.applyPrimaryButton(joinRoomButton);
        ThemeManager.applyPrimaryButton(createPublicButton);
        int errorColor = ThemeManager.getErrorColor(requireContext());
        clearHistoryButton.setTextColor(errorColor);
        clearHistoryButton.setStrokeColor(android.content.res.ColorStateList.valueOf(errorColor));
    }

    private void setupLocalInfo() {
        // 显示本机IP
        String localIp = NetworkUtils.getLocalIpAddress();
        localIpText.setText(localIp != null ? localIp : "获取中...");
        localIpText.setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));

        // 显示用户名
        String username = prefs.getString("user_name", "用户");
        localUsernameText.setText(username);

        // 显示UID
        long uid = chatManager.getMyUid();
        localUidText.setText(String.valueOf(uid));
        localUidText.setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));

        // 显示端口
        int port = chatManager.getTcpServerPort();
        if (port > 0) {
            localPortText.setText(String.valueOf(port));
        } else if (chatManager.isRunning()) {
            localPortText.setText("协商中...");
        } else {
            localPortText.setText("未启动");
        }
        localPortText.setTextColor(ThemeManager.getAccessibleThemeColor(requireContext()));
    }

    private void loadSettings() {
        String savedUsername = prefs.getString("user_name", "");
        boolean showSenderIp = prefs.getBoolean("show_sender_ip", false);

        usernameInput.setText(savedUsername);
        showSenderIpSwitch.setChecked(showSenderIp);
    }

    private void setupListeners() {
        // 保存设置
        saveSettingsButton.setOnClickListener(v -> saveSettings());

        // 清除聊天记录
        clearHistoryButton.setOnClickListener(v -> clearChatHistory());

        // 手动添加好友
        addPeerButton.setOnClickListener(v -> addPeerManually());

        // 加入房间
        joinRoomButton.setOnClickListener(v -> joinRoom());

        // 创建公开房间
        createPublicButton.setOnClickListener(v -> createRoom());

        // 房间号输入框只能输入数字
        roomCodeInput.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789"));
    }

    private void saveSettings() {
        try {
            String username = usernameInput.getText() != null ? usernameInput.getText().toString().trim() : "";
            boolean showSenderIp = showSenderIpSwitch.isChecked();

            if (username.isEmpty()) {
                Toast.makeText(requireContext(), "请输入用户名", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("user_name", username);
            editor.putBoolean("show_sender_ip", showSenderIp);
            editor.apply();

            chatManager.setUserName(username);

            // 确保服务运行（始终允许被发现）
            if (!chatManager.isRunning()) {
                final android.content.Context appContext = requireContext().getApplicationContext();
                new Thread(() -> {
                    try {
                        chatManager.start(appContext, username, 0);
                    } catch (Exception e) {
                        AppErrorLogger.catchError("LanChatDiscoverFragment", "启动聊天服务失败", e);
                    }
                }, "lan-chat-start").start();
            }

            // 刷新本机信息显示
            setupLocalInfo();

            Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            AppErrorLogger.catchError("LanChatDiscoverFragment", "保存设置失败", e);
            Toast.makeText(requireContext(), "保存设置失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearChatHistory() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("清除聊天记录")
                .setMessage("确定要清除所有聊天记录吗？此操作不可恢复。")
                .setPositiveButton("确定", (dialog, which) -> {
                    android.database.sqlite.SQLiteDatabase db = database.getWritableDatabase();
                    db.delete("messages", null, null);
                    Toast.makeText(requireContext(), "聊天记录已清除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 手动添加好友（支持IP/UID）
     */
    private void addPeerManually() {
        String input = addIpInput.getText() != null ? addIpInput.getText().toString().trim() : "";

        if (input.isEmpty()) {
            Toast.makeText(requireContext(), "请输入IP地址或UID", Toast.LENGTH_SHORT).show();
            return;
        }

        String ip;
        int port = 0;

        // 判断输入类型：纯数字为UID，否则为IP
        if (input.matches("^\\d+$")) {
            // 输入为纯数字，当作UID处理
            try {
                long targetUid = Long.parseLong(input);
                // 从已知peer列表中查找对应UID
                LanChatManager.LanPeer foundPeer = findPeerByUid(targetUid);
                if (foundPeer == null) {
                    Toast.makeText(requireContext(), "未找到UID为 " + targetUid + " 的用户，请等待广播发现", Toast.LENGTH_SHORT).show();
                    return;
                }
                ip = foundPeer.getIp();
                port = foundPeer.getTcpPort();
                Toast.makeText(requireContext(), "已找到用户 " + foundPeer.getUserName() + "，IP: " + ip, Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "UID格式错误", Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (input.contains(":")) {
            // 支持 IP:端口 格式
            String[] parts = input.split(":");
            ip = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "端口号格式错误", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            ip = input;
        }

        // 打开聊天页面
        Intent intent = new Intent(requireContext(), LanChatRoomActivity.class);
        intent.putExtra("peer_ip", ip);
        intent.putExtra("peer_name", "手动添加");
        if (port > 0) {
            intent.putExtra("peer_port", port);
        }
        startActivity(intent);
    }

    /**
     * 通过UID查找peer
     */
    private LanChatManager.LanPeer findPeerByUid(long uid) {
        for (LanChatManager.LanPeer peer : chatManager.getPeers()) {
            if (peer.getUid() == uid) {
                return peer;
            }
        }
        return null;
    }

    /**
     * 加入房间
     */
    private void joinRoom() {
        String code = roomCodeInput.getText() != null ? roomCodeInput.getText().toString().trim() : "";

        if (code.isEmpty() || code.length() != 5) {
            Toast.makeText(requireContext(), "请输入5位房间号", Toast.LENGTH_SHORT).show();
            return;
        }

        chatManager.setRoomCode(code);

        Intent intent = new Intent(requireContext(), LanChatRoomActivity.class);
        intent.putExtra("room_code", code);
        intent.putExtra("is_room", true);
        startActivity(intent);
    }

    /**
     * 创建房间（所有房间都是公开的）
     */
    private void createRoom() {
        String code = String.valueOf((int)(Math.random() * 90000) + 10000);

        chatManager.setRoomCode(code);

        Toast.makeText(requireContext(), "已创建房间，房间号: " + code, Toast.LENGTH_LONG).show();

        LanChatManager.LanPeer roomPeer = new LanChatManager.LanPeer();
        roomPeer.setIp(NetworkUtils.getLocalIpAddress());
        roomPeer.setUserName(chatManager.getUserName());
        roomPeer.setTcpPort(chatManager.getTcpServerPort());
        roomPeer.setFriend(false);
        roomPeer.setRoom(true);
        roomPeer.setOnline(true);
        roomPeer.setLastSeen(System.currentTimeMillis());
        roomPeer.setDiscoverable(true); // 所有房间都是公开的
        roomPeer.setRoomCode(code);
        roomPeer.setRoomName(chatManager.getUserName() + "的房间");

        chatManager.addRoom(roomPeer);

        LanChatManager.ChatMessage createMsg = new LanChatManager.ChatMessage();
        createMsg.setType("room_create");
        createMsg.setContent("房间已创建");
        createMsg.setSender(chatManager.getUserName());
        createMsg.setDeviceId(chatManager.getDeviceId());
        createMsg.setSenderIp(NetworkUtils.getLocalIpAddress());
        createMsg.setSenderPort(chatManager.getTcpServerPort());
        createMsg.setDiscoverable(true); // 所有房间都是公开的
        createMsg.setTimestamp(System.currentTimeMillis());
        createMsg.setRoomCode(code);
        createMsg.setRoomName(chatManager.getUserName() + "的房间");
        createMsg.setRoomIp(NetworkUtils.getLocalIpAddress());
        createMsg.setRoomPort(chatManager.getTcpServerPort());
        createMsg.setRoomOwner(true);
        chatManager.sendBroadcastMessage(createMsg);

        Intent intent = new Intent(requireContext(), LanChatRoomActivity.class);
        intent.putExtra("room_code", code);
        intent.putExtra("is_room", true);
        intent.putExtra("is_public", true);
        startActivity(intent);
    }
}
