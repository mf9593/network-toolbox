package com.networktool.app.activity.tool;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.adapter.ChatMessageAdapter;
import com.networktool.app.adapter.EmojiAdapter;
import com.networktool.app.util.DebugLogger;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.LanChatDatabase;
import com.networktool.app.util.LanChatFileManager;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiDataManager;
import com.networktool.app.util.AESUtil;
import com.networktool.app.model.WifiDataItem;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.widget.ImageView;
import com.networktool.app.util.DownloadDirManager;
import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * 聊天房间 Activity（一对一聊天）
 *
 * 功能：
 * - 文字消息发送/接收
 * - 表情选择器
 * - 消息列表显示（气泡样式）
 */
public class LanChatRoomActivity extends AppCompatActivity implements LanChatManager.OnPeerChangeListener {

    private MaterialToolbar toolbar;
    private RecyclerView messageRecyclerView;
    private TextInputEditText messageInput;
    private MaterialButton sendButton;
    private ImageButton emojiButton;
    private ImageButton plusButton;
    private View plusMenuContainer;
    private View sendImageButton;
    private View sendFileButton;
    private View sendCameraButton;
    private View sendWifiBackupButton;
    private ImageButton onlineFriendsButton;
    private ImageButton roomMembersButton;
    private FrameLayout emojiPanelContainer;

    private ChatMessageAdapter messageAdapter;
    private final List<LanChatManager.ChatMessage> messageList = new CopyOnWriteArrayList<>();
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();

    private LanChatManager chatManager;
    private LanChatManager.LanPeer currentPeer;
    private String peerName;
    private String peerIp;
    private int peerPort;
    private String roomCode;
    private String roomName; // 房间名称
    private boolean isRoomChat = false;
    private TextView roomInfoBar; // 房间信息条
    private LanChatDatabase database;
    private LanChatFileManager fileManager;

    private boolean isEmojiVisible = false;
    private boolean isPlusMenuVisible = false;

    private static final int IMAGE_PICK_REQUEST_CODE = 1002;
    private static final int CAMERA_REQUEST_CODE = 1003;
    private static final int FILE_PICK_REQUEST_CODE = 1001;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 2001;

    private Uri currentPhotoUri;
    private File currentPhotoFile;

    // 常用表情列表
    private static final String[] EMOJIS = {
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
        "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰",
        "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜",
        "🤪", "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏",
        "👍", "👎", "👊", "✊", "🤛", "🤜", "👏", "🙌",
        "👐", "🤲", "🤝", "🙏", "✌️", "🤟", "🤘", "👌",
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "💯", "💢", "💥", "💫", "💦", "🌟", "⭐", "✨"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置全局未捕获异常处理器，确保闪退信息被记录
        final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            AppErrorLogger.logError("LanChatRoom", "未捕获异常崩溃: " + thread.getName(), throwable);
            // 调用原始处理器（退出应用）
            if (originalHandler != null) {
                originalHandler.uncaughtException(thread, throwable);
            }
        });

        try {
            setContentView(R.layout.activity_lan_chat_room);

            // 判断是房间聊天还是好友聊天
            isRoomChat = getIntent().getBooleanExtra("is_room", false);
            roomCode = getIntent().getStringExtra("room_code");
            roomName = getIntent().getStringExtra("room_name"); // 房间名称

            if (!isRoomChat) {
                // 好友聊天模式
                String peerIp = getIntent().getStringExtra("peer_ip");
                peerName = getIntent().getStringExtra("peer_name");
                peerPort = getIntent().getIntExtra("peer_port", 0);
                String roomCode = getIntent().getStringExtra("room_code"); // 获取验证码

                // 参数校验
                if (peerIp == null || peerIp.isEmpty()) {
                    Toast.makeText(this, "参数错误：peerIp 为空", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                chatManager = LanChatManager.getInstance();
                database = LanChatDatabase.getInstance(this);
                fileManager = LanChatFileManager.getInstance();

                // 查找对应的 peer（优先匹配IP+有效端口）
                for (LanChatManager.LanPeer peer : chatManager.getPeers()) {
                    if (peer.getIp() != null && peer.getIp().equals(peerIp) && peer.getTcpPort() > 0) {
                        currentPeer = peer;
                        break;
                    }
                }
                // 如果没找到有效端口的peer，再匹配任意IP相同的peer
                if (currentPeer == null) {
                    for (LanChatManager.LanPeer peer : chatManager.getPeers()) {
                        if (peer.getIp() != null && peer.getIp().equals(peerIp)) {
                            currentPeer = peer;
                            break;
                        }
                    }
                }

                // 如果没找到，尝试用用户名查找（兼容旧数据中IP为127.0.0.1的情况）
                if (currentPeer == null && peerName != null && !peerName.isEmpty()) {
                    for (LanChatManager.LanPeer peer : chatManager.getPeers()) {
                        if (peer.getUserName() != null && peer.getUserName().equals(peerName)) {
                            currentPeer = peer;
                            peerIp = peer.getIp(); // 更新为正确的IP
                            break;
                        }
                    }
                }

                // 如果没有找到 peer，创建一个新的临时 peer 用于手动添加
                if (currentPeer == null) {
                    LanChatManager.LanPeer newPeer = new LanChatManager.LanPeer();
                    newPeer.setIp(peerIp);
                    newPeer.setUserName(peerName != null ? peerName : "手动添加");
                    newPeer.setTcpPort(peerPort);
                    newPeer.setOnline(false); // 默认离线
                    // addAndConnectPeer 返回已有的或新添加的 peer 对象
                    currentPeer = chatManager.addAndConnectPeer(newPeer);
                    // 发送连接请求广播，让对方知道本机想连接
                    chatManager.sendConnectRequest(currentPeer);
                } else {
                    // 已有 peer，确保 TCP 连接已建立
                    if (!currentPeer.isOnline() || currentPeer.getWriter() == null) {
                        // 先尝试直接TCP连接
                        chatManager.connectToPeer(currentPeer);
                        // 同时发送连接请求广播，让对方知道本机想连接并主动回连
                        chatManager.sendConnectRequest(currentPeer);
                    }
                }

                // 保存 peerIp 到成员变量
                this.peerIp = peerIp;
            } else {
                // 房间聊天模式
                chatManager = LanChatManager.getInstance();
                database = LanChatDatabase.getInstance(this);
                fileManager = LanChatFileManager.getInstance();
                peerIp = null;
                currentPeer = null;

                // 同步房间码到LanChatManager，确保组播监听在正确的端口
                if (roomCode != null && !roomCode.isEmpty()) {
                    chatManager.setRoomCode(roomCode);
                }
            }

            setupStatusBar();
            initViews();
            setupToolbar();
            setupMessageList();
            loadHistory();
            setupInputArea();
        } catch (Exception e) {
            android.util.Log.e("LanChatRoom", "Error in onCreate", e);
            Toast.makeText(this, "初始化聊天界面失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void loadHistory() {
        if (isRoomChat) {
            // 房间聊天模式 - 从数据库加载该房间的历史消息
            List<LanChatManager.ChatMessage> history = database.getMessagesByRoomCode(roomCode, 100);
            messageList.addAll(history);
            messageAdapter.setMessages(messageList);

            if (!messageList.isEmpty()) {
                messageRecyclerView.scrollToPosition(messageList.size() - 1);
            }
        } else {
            // 好友聊天模式 - 从数据库加载历史消息
            List<LanChatManager.ChatMessage> history = database.getMessages(peerIp, 100);
            messageList.addAll(history);
            messageAdapter.setMessages(messageList);

            // 滚动到底部
            if (!messageList.isEmpty()) {
                messageRecyclerView.scrollToPosition(messageList.size() - 1);
            }

            // 标记为已读
            database.markAsRead(peerIp);
        }
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        messageRecyclerView = findViewById(R.id.messageRecyclerView);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        emojiButton = findViewById(R.id.emojiButton);
        plusButton = findViewById(R.id.plusButton);
        plusMenuContainer = findViewById(R.id.plusMenuContainer);
        roomInfoBar = findViewById(R.id.roomInfoBar);
        sendImageButton = findViewById(R.id.sendImageButton);
        sendFileButton = findViewById(R.id.sendFileButton);
        sendCameraButton = findViewById(R.id.sendCameraButton);
        sendWifiBackupButton = findViewById(R.id.sendWifiBackupButton);
        onlineFriendsButton = findViewById(R.id.onlineFriendsButton);
        roomMembersButton = findViewById(R.id.roomMembersButton);
        emojiPanelContainer = findViewById(R.id.emojiPanelContainer);
        ThemeManager.applyPrimaryButton(sendButton);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

            if (isRoomChat) {
                StringBuilder title = new StringBuilder();
                if (roomName != null && !roomName.isEmpty()) {
                    title.append(roomName);
                    if (roomCode != null && !roomCode.isEmpty()) {
                        title.append(" (").append(roomCode).append(")");
                    }
                } else if (roomCode != null && !roomCode.isEmpty()) {
                    title.append("房间 ").append(roomCode);
                } else {
                    title.append("房间");
                }
                getSupportActionBar().setTitle(title.toString());
                updateRoomInfoBar();
            } else {
                String title = currentPeer != null ? currentPeer.getDisplayName() : (peerName != null ? peerName : "聊天");
                getSupportActionBar().setTitle(title);
            }
        }

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));

        // 房间模式：邀请按钮
        if (isRoomChat && onlineFriendsButton != null) {
            onlineFriendsButton.setVisibility(View.VISIBLE);
            onlineFriendsButton.setOnClickListener(v -> showInviteFriendsDialog());
        } else if (onlineFriendsButton != null) {
            onlineFriendsButton.setVisibility(View.GONE);
        }

        // 房间模式：成员列表按钮
        if (isRoomChat && roomMembersButton != null) {
            roomMembersButton.setVisibility(View.VISIBLE);
            roomMembersButton.setOnClickListener(v -> showRoomMembersListDialog());
        } else if (roomMembersButton != null) {
            roomMembersButton.setVisibility(View.GONE);
        }
    }

    /**
     * 更新房间信息栏（显示在线人数）
     */
    private void updateRoomInfoBar() {
        if (roomInfoBar == null || chatManager == null) return;

        // 显示在线人数（self已在成员列表中，无需+1）
        List<LanChatManager.LanPeer> members = chatManager.getRoomMembers(roomCode);
        int onlineCount = 0;
        for (LanChatManager.LanPeer m : members) {
            if (m.isOnline()) onlineCount++;
        }

        StringBuilder info = new StringBuilder();
        info.append("房间成员: ").append(onlineCount).append("人");
        roomInfoBar.setText(info.toString());
        roomInfoBar.setVisibility(View.VISIBLE);
        roomInfoBar.setOnClickListener(null);
    }

    /**
     * 显示房间成员列表对话框
     */
    private void showRoomMembersListDialog() {
        if (chatManager == null || roomCode == null || roomCode.isEmpty()) return;

        List<LanChatManager.LanPeer> members = chatManager.getRoomMembers(roomCode);
        String localIp = NetworkUtils.getLocalIpAddress();
        long myUid = chatManager.getMyUid();

        // 确保自己在成员列表中
        boolean selfIncluded = false;
        for (LanChatManager.LanPeer m : members) {
            if (m.getUid() == myUid) {
                selfIncluded = true;
                m.setOnline(true);
                break;
            }
        }
        if (!selfIncluded) {
            LanChatManager.LanPeer self = new LanChatManager.LanPeer();
            self.setUid(myUid);
            self.setUserName(chatManager.getUserName());
            self.setIp(localIp);
            self.setOnline(true);
            members.add(0, self);
        }

        String[] memberNames = new String[members.size()];
        for (int i = 0; i < members.size(); i++) {
            LanChatManager.LanPeer member = members.get(i);
            StringBuilder name = new StringBuilder();
            name.append(member.getUserName());
            if (member.getUid() == myUid) name.append(" (我)");
            if (!member.isOnline()) name.append(" [离线]");
            memberNames[i] = name.toString();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("房间成员 (" + members.size() + ")")
                .setItems(memberNames, null)
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 显示房间邀请确认对话框
     */
    private void showRoomInviteDialog(String inviterName, String roomCode, String roomName) {
        String displayName = (roomName != null && !roomName.isEmpty()) ? roomName : ("房间 " + roomCode);
        new MaterialAlertDialogBuilder(this)
                .setTitle("房间邀请")
                .setMessage((inviterName != null ? inviterName : "好友") + " 邀请你加入 " + displayName)
                .setPositiveButton("加入", (dialog, which) -> {
                    if (chatManager != null) {
                        chatManager.setRoomCode(roomCode);
                    }
                    // 跳转到房间聊天页面
                    Intent intent = new Intent(this, LanChatRoomActivity.class);
                    intent.putExtra("room_code", roomCode);
                    intent.putExtra("is_room", true);
                    intent.putExtra("room_name", roomName);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("拒绝", null)
                .show();
    }

    /**
     * 显示邀请好友对话框
     */
    private void showInviteFriendsDialog() {
        if (chatManager == null) return;
        
        List<LanChatManager.LanPeer> allPeers = chatManager.getPeers();
        List<LanChatManager.LanPeer> onlinePeers = new ArrayList<>();
        long myUid = chatManager.getMyUid();

        for (LanChatManager.LanPeer peer : allPeers) {
            if (peer.isOnline() && !peer.isRoom() && 
                peer.getUid() != myUid && peer.getTcpPort() > 0) {
                onlinePeers.add(peer);
            }
        }

        if (onlinePeers.isEmpty()) {
            Toast.makeText(this, "没有在线的用户可以邀请", Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建选项列表
        String[] peerNames = new String[onlinePeers.size()];
        for (int i = 0; i < onlinePeers.size(); i++) {
            LanChatManager.LanPeer peer = onlinePeers.get(i);
            peerNames[i] = peer.getUserName() + " (UID:" + peer.getUid() + ")";
        }

        // 使用boolean数组跟踪选中状态
        boolean[] checkedItems = new boolean[onlinePeers.size()];

        // 显示多选对话框
        new MaterialAlertDialogBuilder(this)
                .setTitle("邀请好友加入房间")
                .setMultiChoiceItems(peerNames, checkedItems, (dialog, which, isChecked) -> {
                    checkedItems[which] = isChecked;
                })
                .setPositiveButton("邀请", (dialog, which) -> {
                    boolean hasSelected = false;
                    for (int i = 0; i < onlinePeers.size(); i++) {
                        if (checkedItems[i]) {
                            LanChatManager.LanPeer selectedPeer = onlinePeers.get(i);
                            sendRoomInvite(selectedPeer);
                            hasSelected = true;
                        }
                    }
                    if (hasSelected) {
                        Toast.makeText(this, "邀请已发送", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "请选择要邀请的好友", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 发送房间邀请给指定好友（直接加入，无需同意）
     */
    private void sendRoomInvite(LanChatManager.LanPeer peer) {
        // 创建邀请消息
        LanChatManager.ChatMessage inviteMsg = new LanChatManager.ChatMessage();
        inviteMsg.setType("room_invite");
        inviteMsg.setSender(chatManager.getUserName());
        inviteMsg.setSenderIp(NetworkUtils.getLocalIpAddress());
        inviteMsg.setSenderPort(chatManager.getTcpServerPort());
        inviteMsg.setTargetUser(peer.getUserName());
        inviteMsg.setTargetIp(peer.getIp());
        inviteMsg.setTargetPort(peer.getTcpPort());
        inviteMsg.setRoomCode(roomCode);
        inviteMsg.setRoomName(chatManager.getRoomName(roomCode));
        inviteMsg.setContent("邀请您加入房间 " + roomCode);
        inviteMsg.setTimestamp(System.currentTimeMillis());
        
        // 通过TCP发送邀请消息（必须在子线程）
        if (peer.isOnline() && peer.getWriter() != null) {
            String json = chatManager.getGson().toJson(inviteMsg);
            final LanChatManager.LanPeer sendTarget = peer;
            new Thread(() -> {
                try {
                    sendTarget.getWriter().println(json);
                    sendTarget.getWriter().flush();
                    runOnUiThread(() -> DebugLogger.logDiscovery("LanChat", "发送房间邀请", "→ " + sendTarget.getDisplayName()));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "发送邀请失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        } else {
            Toast.makeText(this, "无法发送邀请给 " + peer.getDisplayName() + "（未连接）", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupMessageList() {
        // 房间聊天使用群聊模式显示发送者名称
        messageAdapter = new ChatMessageAdapter(this, chatManager.getUserName(), isRoomChat);
        messageRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messageRecyclerView.setAdapter(messageAdapter);

        // 设置消息点击监听器
        messageAdapter.setOnMessageClickListener(new ChatMessageAdapter.OnMessageClickListener() {
            @Override
            public void onImageClick(LanChatManager.ChatMessage message, String imagePath) {
                // 查看图片
                viewImage(imagePath);
            }

            @Override
            public void onFileDownloadClick(LanChatManager.ChatMessage message) {
                // 下载文件
                downloadFile(message);
            }

            @Override
            public void onWifiBackupImportClick(LanChatManager.ChatMessage message) {
                // 导入WiFi备份
                importWifiBackup(message);
            }
        });
    }

    private void setupInputArea() {
        // 发送按钮点击
        sendButton.setOnClickListener(v -> sendMessage());

        // 输入框回车发送
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            // 软键盘的IME_ACTION_SEND/DONE，或物理键盘回车键按下时发送
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                sendMessage();
                return true;
            }
            // 其他回车事件（如换行输入）不消费，允许正常换行
            return false;
        });

        // 输入框文本变化监听 - 控制发送按钮显示
        messageInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendButtonVisibility();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // 表情按钮点击
        emojiButton.setOnClickListener(v -> toggleEmojiPanel());

        // 加号按钮点击
        plusButton.setOnClickListener(v -> togglePlusMenu());

        // 发送图片按钮点击
        sendImageButton.setOnClickListener(v -> {
            pickImage();
            hidePlusMenu();
        });

        // 拍摄按钮点击
        sendCameraButton.setOnClickListener(v -> {
            openCamera();
            hidePlusMenu();
        });

        // 发送文件按钮点击
        sendFileButton.setOnClickListener(v -> {
            pickFile();
            hidePlusMenu();
        });

        // WiFi备份按钮点击
        sendWifiBackupButton.setOnClickListener(v -> {
            showWifiBackupSendDialog();
            hidePlusMenu();
        });
    }

    /**
     * 根据输入框内容更新发送按钮和加号按钮的可见性
     * 仿微信：有文字时显示发送按钮，无文字时显示加号按钮
     */
    private void updateSendButtonVisibility() {
        boolean hasText = false;
        if (messageInput.getText() != null) {
            String text = messageInput.getText().toString().trim();
            hasText = !text.isEmpty();
        }
        if (hasText) {
            sendButton.setVisibility(View.VISIBLE);
            plusButton.setVisibility(View.GONE);
        } else {
            sendButton.setVisibility(View.GONE);
            plusButton.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 隐藏软键盘
     */
    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * 切换加号菜单显示
     */
    private void togglePlusMenu() {
        if (isPlusMenuVisible) {
            hidePlusMenu();
        } else {
            // 打开加号菜单时先隐藏键盘，防止被遮挡
            hideKeyboard();
            // 关闭表情面板
            if (isEmojiVisible) {
                hideEmojiPanel();
            }
            showPlusMenu();
        }
    }

    /**
     * 显示加号菜单
     */
    private void showPlusMenu() {
        plusMenuContainer.setVisibility(View.VISIBLE);
        isPlusMenuVisible = true;
    }

    /**
     * 隐藏加号菜单
     */
    private void hidePlusMenu() {
        plusMenuContainer.setVisibility(View.GONE);
        isPlusMenuVisible = false;
    }

    /**
     * 选择图片
     */
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        try {
            startActivityForResult(Intent.createChooser(intent, "选择图片"), IMAGE_PICK_REQUEST_CODE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "请先安装图片浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 打开相机拍照
     */
    private void openCamera() {
        // Android 6.0+ 需要运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA},
                        CAMERA_PERMISSION_REQUEST_CODE);
                return;
            }
        }
        doOpenCamera();
    }

    /**
     * 实际打开相机（权限已获取）
     */
    private void doOpenCamera() {
        try {
            // 创建临时文件保存照片
            String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";
            File photoDir = new File(getExternalCacheDir(), "photos");
            if (!photoDir.exists()) photoDir.mkdirs();
            currentPhotoFile = new File(photoDir, fileName);

            currentPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    currentPhotoFile);

            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, currentPhotoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivityForResult(intent, CAMERA_REQUEST_CODE);
            DebugLogger.logDiscovery("LanChat", "打开相机", "照片保存路径: " + currentPhotoFile.getAbsolutePath());
        } catch (Exception e) {
            String errMsg = "无法打开相机：" + e.getMessage();
            Toast.makeText(this, errMsg, Toast.LENGTH_SHORT).show();
            DebugLogger.logDiscovery("LanChat", "打开相机失败", errMsg);
        }
    }

    /**
     * 选择文件
     */
    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        try {
            startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_PICK_REQUEST_CODE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "请先安装文件管理器", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 切换表情面板显示
     */
    private void toggleEmojiPanel() {
        if (isEmojiVisible) {
            hideEmojiPanel();
        } else {
            // 打开表情面板时先隐藏键盘，防止被遮挡
            hideKeyboard();
            // 关闭加号菜单
            if (isPlusMenuVisible) {
                hidePlusMenu();
            }
            showEmojiPanel();
        }
    }

    /**
     * 隐藏表情面板
     */
    private void hideEmojiPanel() {
        emojiPanelContainer.setVisibility(View.GONE);
        isEmojiVisible = false;
    }

    /**
     * 显示表情面板
     */
    private void showEmojiPanel() {
        // 创建表情 GridView
        GridView emojiGrid = new GridView(this);
        emojiGrid.setNumColumns(7);
        emojiGrid.setVerticalSpacing(8);
        emojiGrid.setHorizontalSpacing(8);
        emojiGrid.setPadding(16, 16, 16, 16);
        emojiGrid.setBackgroundColor(getResources().getColor(android.R.color.transparent, null));

        EmojiAdapter adapter = new EmojiAdapter(EMOJIS, emoji -> {
            // 表情点击，插入到输入框
            String current = messageInput.getText() != null ? messageInput.getText().toString() : "";
            messageInput.setText(current + emoji);
            messageInput.setSelection(messageInput.getText().length());
        });

        emojiGrid.setAdapter(adapter);

        // 添加到容器
        emojiPanelContainer.removeAllViews();
        emojiPanelContainer.addView(emojiGrid);
        emojiPanelContainer.setVisibility(View.VISIBLE);
        isEmojiVisible = true;
    }

    /**
     * 发送消息
     */
    private void sendMessage() {
        try {
            String content = "";
            if (messageInput != null && messageInput.getText() != null) {
                content = messageInput.getText().toString().trim();
            }

            // 调试信息
            android.util.Log.d("LanChatRoom", "sendMessage called, content: '" + content + "', length: " + content.length());

            if (content.isEmpty()) {
                Toast.makeText(this, "请输入消息内容", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isRoomChat) {
                // 房间聊天模式 - 使用 UDP 组播发送
                sendRoomMessage(content);
            } else {
                // 好友聊天模式 - 使用 TCP 直连
                if (currentPeer == null) {
                    Toast.makeText(this, "对方不在线", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 交给 chatManager.sendMessage() 处理连接和发送
                // chatManager.sendMessage() 内部有轮询等待逻辑（最多3秒）
                // 不在 UI 层做连接检查和等待，避免阻塞 UI 线程

                // 构建消息
                LanChatManager.ChatMessage message = new LanChatManager.ChatMessage();
                message.setType("text");
                message.setContent(content);
                message.setMessageId(java.util.UUID.randomUUID().toString()); // 唯一消息ID
                if (chatManager != null) {
                    message.setSender(chatManager.getUserName());
                }
                message.setTimestamp(System.currentTimeMillis());

                // 先添加到本地列表显示
                messageList.add(message);
                if (messageAdapter != null) {
                    messageAdapter.addMessage(message);
                }
                if (messageRecyclerView != null) {
                    messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);
                }

                // 保存到数据库
                if (database != null && peerIp != null && peerName != null) {
                    database.saveMessage(peerIp, peerName, message);
                }

                // 异步发送消息（chatManager.sendMessage 内部有轮询等待，不能在UI线程执行）
                if (chatManager != null && currentPeer != null) {
                    LanChatManager.LanPeer sendPeer = currentPeer;
                    LanChatManager.ChatMessage sendMsg = message;
                    chatExecutor.execute(() -> {
                        boolean success = chatManager.sendMessage(sendPeer, sendMsg);
                        if (!success) {
                            runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "消息发送失败，请检查网络连接", Toast.LENGTH_SHORT).show());
                        }
                    });
                }
            }

            // 清空输入框
            if (messageInput != null) {
                messageInput.setText("");
            }
            // 发送后恢复加号按钮显示
            updateSendButtonVisibility();
        } catch (Exception e) {
            android.util.Log.e("LanChatRoom", "Error in sendMessage", e);
            Toast.makeText(this, "发送消息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 发送房间消息（UDP 组播）
     */
    private void sendRoomMessage(String content) {
        try {
            if (chatManager == null) {
                Toast.makeText(this, "聊天管理器未初始化", Toast.LENGTH_SHORT).show();
                return;
            }

            LanChatManager.ChatMessage message = new LanChatManager.ChatMessage();
            message.setType("text");
            message.setContent(content);
            message.setMessageId(java.util.UUID.randomUUID().toString()); // 唯一消息ID
            message.setSender(chatManager.getUserName());
            message.setTimestamp(System.currentTimeMillis());

            // 先添加到本地列表显示
            messageList.add(message);
            if (messageAdapter != null) {
                messageAdapter.addMessage(message);
            }
            if (messageRecyclerView != null) {
                messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);
            }

            // 保存到数据库
            if (database != null && roomCode != null) {
                database.saveMessage(null, roomCode, message);
            }

            // 通过 UDP 组播发送
            chatManager.sendRoomMessage(message);
            
            // 显示发送成功的提示
            Toast.makeText(this, "消息已发送", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("LanChatRoom", "Error in sendRoomMessage", e);
            Toast.makeText(this, "发送房间消息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 添加收到的消息到列表
     */
    public void addReceivedMessage(LanChatManager.ChatMessage message) {
        try {
            if (message == null) return;

            // 房间邀请消息不存入聊天记录
            if ("room_invite".equals(message.getType())) {
                return;
            }

            // 去重：检查是否已存在相同内容 + 时间戳 + 发送者的消息
            for (LanChatManager.ChatMessage existing : messageList) {
                if (existing.getTimestamp() == message.getTimestamp() &&
                    existing.getContent() != null && existing.getContent().equals(message.getContent()) &&
                    existing.getSender() != null && existing.getSender().equals(message.getSender())) {
                    if (existing.getSenderIp() != null && message.getSenderIp() != null &&
                        !existing.getSenderIp().equals(message.getSenderIp())) {
                        continue;
                    }
                    if (existing.getSenderPort() != 0 && message.getSenderPort() != 0 &&
                        existing.getSenderPort() != message.getSenderPort()) {
                        continue;
                    }
                    return;
                }
            }

            // 保存到数据库（如果尚未由handleTcpClient保存）
            if (!message.isSavedToDb()) {
                if (isRoomChat) {
                    database.saveMessage(null, roomCode, message);
                } else {
                    // 好友聊天模式：使用消息中的 senderIp 而不是 peerIp（避免 null）
                    String saveIp = message.getSenderIp();
                    if (saveIp == null || saveIp.isEmpty()) {
                        saveIp = peerIp;
                    }
                    if (saveIp != null && !saveIp.isEmpty()) {
                        database.saveMessage(saveIp, message.getSender(), message);
                    }
                }
            }

            runOnUiThread(() -> {
                try {
                    messageList.add(message);
                    messageAdapter.addMessage(message);
                    messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);
                } catch (Exception e) {
                    android.util.Log.e("LanChatRoom", "Error updating UI with received message", e);
                }
            });
        } catch (Exception e) {
            android.util.Log.e("LanChatRoom", "Error in addReceivedMessage", e);
        }
    }

    /**
     * 更新消息状态（送达确认）
     */
    private void updateMessageStatus(String messageId, String status) {
        synchronized (messageList) {
            for (int i = 0; i < messageList.size(); i++) {
                LanChatManager.ChatMessage msg = messageList.get(i);
                if (messageId.equals(msg.getMessageId())) {
                    msg.setContent(msg.getContent()); // 保持内容不变
                    messageAdapter.notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                DebugLogger.logDiscovery("LanChat", "相机权限", "用户已授权相机权限");
                doOpenCamera();
            } else {
                String msg = "需要相机权限才能拍照";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                DebugLogger.logDiscovery("LanChat", "相机权限", "用户拒绝相机权限");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @androidx.annotation.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK) {
            return;
        }

        if (requestCode == FILE_PICK_REQUEST_CODE) {
            Uri fileUri = data != null ? data.getData() : null;
            if (fileUri != null) {
                sendFile(fileUri);
            }
        } else if (requestCode == IMAGE_PICK_REQUEST_CODE) {
            Uri imageUri = data != null ? data.getData() : null;
            if (imageUri != null) {
                sendImage(imageUri);
            }
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            // 拍照完成，询问用户是否发送
            if (currentPhotoFile != null && currentPhotoFile.exists()) {
                showPhotoConfirmDialog(currentPhotoUri);
            }
        }
    }

    /**
     * 显示拍照确认对话框
     */
    private void showPhotoConfirmDialog(Uri photoUri) {
        try {
            // 读取照片生成缩略图
            android.graphics.Bitmap bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                    getContentResolver(), photoUri);
            // 缩放缩略图显示
            int maxSize = 400;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            float scale = Math.min((float) maxSize / width, (float) maxSize / height);
            android.graphics.Bitmap thumbnail = android.graphics.Bitmap.createScaledBitmap(
                    bitmap, (int)(width * scale), (int)(height * scale), true);

            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(thumbnail);
            imageView.setAdjustViewBounds(true);
            imageView.setMaxWidth(600);
            imageView.setPadding(32, 32, 32, 32);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("发送照片")
                    .setView(imageView)
                    .setPositiveButton("发送", (dialog, which) -> sendImage(photoUri))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            // 如果无法生成缩略图，直接询问是否发送
            new android.app.AlertDialog.Builder(this)
                    .setTitle("发送照片")
                    .setMessage("是否发送此照片？")
                    .setPositiveButton("发送", (dialog, which) -> sendImage(photoUri))
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /**
     * 发送图片
     */
    private void sendImage(Uri imageUri) {
        if (isRoomChat) {
            // 房间聊天：发送给所有在线好友
            sendImageToRoomMembers(imageUri);
            return;
        }

        // 好友聊天模式
        if (currentPeer == null) {
            Toast.makeText(this, "对方不在线", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!currentPeer.isOnline() || currentPeer.getWriter() == null) {
            chatManager.connectToPeer(currentPeer);
            chatManager.sendConnectRequest(currentPeer);
            Toast.makeText(this, "正在连接对方，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        sendImageWithPeer(imageUri, currentPeer);
    }
    
    /**
     * 使用指定peer发送图片
     */
    private void sendImageWithPeer(Uri imageUri, LanChatManager.LanPeer peer) {

        // 检查 Uri 是否有效
        if (imageUri == null) {
            Toast.makeText(this, "图片 URI 无效", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 读取图片并转换为 Base64
            // 使用 InputStream 方式读取，兼容 Android 10+ 作用域存储
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            if (inputStream == null) {
                Toast.makeText(this, "无法读取图片文件", Toast.LENGTH_SHORT).show();
                return;
            }

            // 使用 BitmapFactory.Options 避免 OutOfMemoryError
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(inputStream, null, options);
            
            // 重置输入流
            inputStream.close();
            inputStream = getContentResolver().openInputStream(imageUri);
            if (inputStream == null) {
                Toast.makeText(this, "无法读取图片文件", Toast.LENGTH_SHORT).show();
                return;
            }

            // 计算缩放比例以避免内存溢出
            options.inJustDecodeBounds = false;
            options.inSampleSize = calculateInSampleSize(options, 1024, 1024); // 最大1024x1024
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, options);

            if (bitmap == null) {
                Toast.makeText(this, "图片格式无效或已损坏", Toast.LENGTH_SHORT).show();
                inputStream.close();
                return;
            }
            
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos);
            inputStream.close();
            byte[] imageBytes = baos.toByteArray();
            String base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP); // 不换行

            // 获取文件名
            String fileName = "image_" + System.currentTimeMillis() + ".jpg";
            android.database.Cursor cursor = getContentResolver().query(imageUri, null, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
                cursor.close();
            }

            // 显示发送消息
            LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
            msg.setType("image");
            msg.setContent("[图片]");
            msg.setFileName(fileName);
            msg.setFileSize(imageBytes.length);
            msg.setFileData(base64Image);
            msg.setSender(chatManager.getUserName());
            msg.setSenderIp(peer.getIp()); // 使用 peer 的 IP
            msg.setSenderPort(peer.getTcpPort()); // 使用 peer 的端口
            msg.setTimestamp(System.currentTimeMillis());

            // 保存图片本地副本，以便重启后仍可查看
            try {
                File imageDir = DownloadDirManager.getInstance(this).getImageDir();
                File localFile = new File(imageDir, System.currentTimeMillis() + "_" + fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile);
                fos.write(imageBytes);
                fos.close();
                msg.setFilePath(localFile.getAbsolutePath());
            } catch (Exception e) {
                AppErrorLogger.logError("LanChatRoom", "保存图片本地副本失败", e);
            }

            messageList.add(msg);
            messageAdapter.addMessage(msg);
            messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);

            // 保存到数据库 - 使用 peer 的 IP 和用户名
            String saveIp = peer.getIp();
            String saveName = peer.getUserName();
            if (saveIp != null && !saveIp.isEmpty()) {
                database.saveMessage(saveIp, saveName, msg);
            }

            // 使用文件传输协议发送图片（避免大 JSON 导致传输失败）
            sendFileData(fileName, imageBytes, peer);

        } catch (java.io.FileNotFoundException e) {
            String TAG = "LanChatRoom";
            AppErrorLogger.logError(TAG, "图片文件未找到", e);
            Toast.makeText(this, "图片文件未找到，可能已被删除或移动", Toast.LENGTH_SHORT).show();
        } catch (OutOfMemoryError e) {
            String TAG = "LanChatRoom";
            AppErrorLogger.logError(TAG, "内存不足", e);
            Toast.makeText(this, "图片太大，内存不足", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            String TAG = "LanChatRoom";
            AppErrorLogger.logError(TAG, "读取图片失败", e);
            Toast.makeText(this, "读取图片失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

    }
    /**
     * 计算BitmapFactory.Options的inSampleSize值
     * 用于缩小图片以避免内存溢出
     */
    private int calculateInSampleSize(android.graphics.BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // 原始图片的宽高
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // 计算最大的inSampleSize值，使得图片的宽高都大于等于目标宽高
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * 发送文件
     */
    private void sendFile(Uri fileUri) {
        if (isRoomChat) {
            // 房间聊天：发送给所有在线好友
            sendFileToRoomMembers(fileUri);
            return;
        }

        // 好友聊天模式
        if (currentPeer == null) {
            Toast.makeText(this, "对方不在线", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!currentPeer.isOnline() || currentPeer.getWriter() == null) {
            chatManager.connectToPeer(currentPeer);
            chatManager.sendConnectRequest(currentPeer);
            Toast.makeText(this, "正在连接对方，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        sendFileWithPeer(fileUri, currentPeer);
    }

    /**
     * 房主发送文件给房间内所有在线好友
     */
    private void sendFileToRoomMembers(Uri fileUri) {
        List<LanChatManager.LanPeer> onlineMembers = getOnlineRoomMembers();
        if (onlineMembers.isEmpty()) {
            Toast.makeText(this, "房间内没有在线好友", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在发送给 " + onlineMembers.size() + " 位在线好友...", Toast.LENGTH_SHORT).show();
        for (LanChatManager.LanPeer member : onlineMembers) {
            sendFileWithPeer(fileUri, member);
        }
    }

    /**
     * 房主发送图片给房间内所有在线好友
     */
    private void sendImageToRoomMembers(Uri imageUri) {
        List<LanChatManager.LanPeer> onlineMembers = getOnlineRoomMembers();
        if (onlineMembers.isEmpty()) {
            Toast.makeText(this, "房间内没有在线好友", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在发送给 " + onlineMembers.size() + " 位在线好友...", Toast.LENGTH_SHORT).show();
        for (LanChatManager.LanPeer member : onlineMembers) {
            sendImageWithPeer(imageUri, member);
        }
    }

    /**
     * 获取房间内所有在线好友（通过UID排除自己，多开场景兼容）
     */
    private List<LanChatManager.LanPeer> getOnlineRoomMembers() {
        List<LanChatManager.LanPeer> onlineMembers = new ArrayList<>();
        long myUid = chatManager != null ? chatManager.getMyUid() : 0;
        List<LanChatManager.LanPeer> allPeers = chatManager.getPeers();

        for (LanChatManager.LanPeer peer : allPeers) {
            if (peer.isOnline() && peer.isFriend() && peer.getUid() != myUid) {
                onlineMembers.add(peer);
            }
        }
        return onlineMembers;
    }

    /**
     * 使用指定peer发送文件
     */
    private void sendFileWithPeer(Uri fileUri, LanChatManager.LanPeer peer) {
        try {
            sendFileWithPeerInternal(fileUri, peer);
        } catch (Exception e) {
            AppErrorLogger.logError("LanChatRoom", "发送文件异常", e);
            runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "发送文件失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void sendFileWithPeerInternal(Uri fileUri, LanChatManager.LanPeer peer) {

        // 获取文件名和大小
        String fileName = "file";
        long fileSize = 0;
        File tempFile = null;

        try {
            // 将Uri转换为File
            String filePath = getPathFromUri(fileUri);
            if (filePath != null) {
                tempFile = new File(filePath);
                fileName = tempFile.getName();
                fileSize = tempFile.length();
            } else {
                android.database.Cursor cursor = getContentResolver().query(fileUri, null, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) { // 检查是否有数据
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameIndex >= 0) fileName = cursor.getString(nameIndex);
                        if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex);
                    }
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // 忽略异常，使用默认值
            AppErrorLogger.logError("LanChatRoom", "获取文件信息失败", e);
        }

        // 显示发送消息
        LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
        msg.setType("file");
        msg.setContent("[文件] " + fileName);
        msg.setFileName(fileName);
        msg.setFileSize(fileSize);
        msg.setSender(chatManager.getUserName());
        msg.setTimestamp(System.currentTimeMillis());

        messageList.add(msg);
        messageAdapter.addMessage(msg);
        messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);

        // 保存到数据库
        database.saveMessage(peerIp, peerName, msg);

        // 检查文件大小限制
        if (fileSize > 100 * 1024 * 1024) { // 100MB限制
            runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "文件过大，最大支持100MB", Toast.LENGTH_SHORT).show());
            return;
        }

        // 显示发送消息
        Toast.makeText(this, "正在发送文件：" + fileName, Toast.LENGTH_SHORT).show();

        // 通过新的文件传输管理器发送（独立TCP连接 + 分块传输 + CRC32校验）
        if (peer != null && tempFile != null && tempFile.exists()) {
            final File sendFile = tempFile;
            final LanChatManager.LanPeer sendPeer = peer;
            
            LanChatFileManager.getInstance().sendFile(
                sendPeer.getIp(),
                sendPeer.getTcpPort(),
                sendFile,
                new LanChatFileManager.OnFileTransferListener() {
                    @Override
                    public void onFileOfferReceived(LanChatFileManager.FileTransferOffer offer) {
                        // 发送方不会收到这个回调
                    }

                    @Override
                    public void onFileTransferProgress(String fileId, int progress) {
                        runOnUiThread(() -> {
                            // 可以更新进度UI
                        });
                    }

                    @Override
                    public void onFileReceived(String fileId, String fileName, String filePath, long fileSize) {
                        // 发送方不会收到这个回调
                    }

                    @Override
                    public void onFileSent(String fileId, String fileName, long fileSize) {
                        runOnUiThread(() -> {
                            Toast.makeText(LanChatRoomActivity.this, "文件发送成功：" + fileName, Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onFileTransferError(String error) {
                        runOnUiThread(() -> {
                            Toast.makeText(LanChatRoomActivity.this, "文件发送失败：" + error, Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            );
        } else {
            runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "文件不存在或peer无效", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 查看图片
     */
    private void viewImage(String imageData) {
        try {
            final Bitmap[] bitmapRef = new Bitmap[1];

            // 判断是文件路径还是Base64数据：优先检查是否为存在的文件
            if (imageData != null) {
                File testFile = new File(imageData);
                if (testFile.exists()) {
                    // 文件路径：直接从文件加载
                    bitmapRef[0] = android.graphics.BitmapFactory.decodeFile(imageData);
                } else {
                    // Base64数据：解码后加载
                    byte[] decoded = android.util.Base64.decode(imageData, android.util.Base64.DEFAULT);
                    bitmapRef[0] = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                }
            }

            if (bitmapRef[0] == null) {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
                return;
            }

            // 创建 ImageView 显示图片
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bitmapRef[0]);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setPadding(16, 16, 16, 16);

            // 使用 AlertDialog 显示大图
            new android.app.AlertDialog.Builder(this)
                .setView(imageView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("保存图片", (dialog, which) -> saveImage(bitmapRef[0]))
                .show();
        } catch (Exception e) {
            Toast.makeText(this, "查看图片失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 保存图片到下载目录
     */
    private void saveImage(Bitmap bitmap) {
        try {
            File imageDir = DownloadDirManager.getInstance(this).getImageDir();
            String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";
            File imageFile = new File(imageDir, fileName);

            java.io.FileOutputStream fos = new java.io.FileOutputStream(imageFile);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();

            Toast.makeText(this, "图片已保存到：" + imageFile.getAbsolutePath(), Toast.LENGTH_LONG).show();

            // 通知媒体扫描器
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(android.net.Uri.fromFile(imageFile));
            sendBroadcast(mediaScanIntent);

        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 发送文件数据（字节数组）
     * 使用 FILE_REQUEST/FILE_DATA/FILE_END 协议
     */
    private void sendFileData(String fileName, byte[] fileData, LanChatManager.LanPeer peer) {
        if (peer == null) {
            Toast.makeText(this, "对方不在线", Toast.LENGTH_SHORT).show();
            return;
        }

        // 异步发送文件数据（包括文件请求，避免在主线程进行网络操作）
        final LanChatManager.LanPeer finalPeer = peer;
        chatExecutor.execute(() -> {
            try {
                // 发送文件请求（在子线程中执行，避免 NetworkOnMainThreadException）
                if (!finalPeer.sendFileRequest(fileName, fileData.length)) {
                    runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "发送文件请求失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 等待对方准备接收
                Thread.sleep(500);

                // 将数据分块Base64编码并发送
                String base64Data = android.util.Base64.encodeToString(fileData, android.util.Base64.NO_WRAP);

                // 按行发送Base64数据（每行不超过8000字符）
                int chunkSize = 8000;
                for (int i = 0; i < base64Data.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, base64Data.length());
                    String chunk = base64Data.substring(i, end);
                    if (!finalPeer.sendRawLine("FILE_DATA:" + chunk)) {
                        throw new Exception("发送文件数据失败");
                    }
                }

                // 发送结束标记
                if (!finalPeer.sendRawLine("FILE_END:")) {
                    throw new Exception("发送文件结束标记失败");
                }

                runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "发送完成", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                android.util.Log.e("LanChatRoom", "发送失败", e);
                runOnUiThread(() -> Toast.makeText(LanChatRoomActivity.this, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 下载文件
     */
    private void downloadFile(LanChatManager.ChatMessage message) {
        String fileData = message.getFileData();
        String fileName = message.getFileName();
        String filePath = message.getFilePath();

        // 优先检查本地文件是否存在
        if (!TextUtils.isEmpty(filePath)) {
            File localFile = new File(filePath);
            if (localFile.exists()) {
                // 检测是否为WiFi备份数据
                checkAndOfferWifiImport(localFile, "文件已存在：" + filePath + "\n\n是否打开文件？");
                return;
            }
        }

        if (TextUtils.isEmpty(fileData)) {
            // fileData为空时，尝试从本地文件路径读取（文件可能已由handleTcpClient保存）
            if (!TextUtils.isEmpty(filePath)) {
                File existingFile = new File(filePath);
                if (existingFile.exists()) {
                    try {
                        byte[] existingBytes = new byte[(int) existingFile.length()];
                        java.io.FileInputStream fis = new java.io.FileInputStream(existingFile);
                        fis.read(existingBytes);
                        fis.close();
                        fileData = android.util.Base64.encodeToString(existingBytes, android.util.Base64.DEFAULT);
                        fileName = existingFile.getName();
                    } catch (Exception e) {
                        // 读取失败，继续尝试其他方式
                    }
                }
            }
            if (TextUtils.isEmpty(fileData)) {
                Toast.makeText(this, "文件数据为空", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        try {
            File fileDir = DownloadDirManager.getInstance(this).getFileDir();
            File file = new File(fileDir, fileName);

            byte[] fileBytes = android.util.Base64.decode(fileData, android.util.Base64.DEFAULT);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(fileBytes);
            fos.close();

            Toast.makeText(this, "文件已保存到：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();

            // 检测WiFi数据并提供导入
            checkAndOfferWifiImport(file, "下载完成\n\n是否打开文件？");

        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检测文件是否为WiFi备份数据，如果是则提供导入选项
     */
    private void checkAndOfferWifiImport(File file, String openMessage) {
        try {
            byte[] fileBytes = new byte[(int) file.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            fis.read(fileBytes);
            fis.close();

            String fileContent = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
            WifiDataManager wifiDataManager = WifiDataManager.getInstance(this);
            java.util.Map<String, java.util.List<String>> wifiData = wifiDataManager.parseWifiData(fileContent);

            if (!wifiData.isEmpty()) {
                int wifiCount = wifiData.size();
                int totalCount = 0;
                for (java.util.List<String> passwords : wifiData.values()) {
                    totalCount += passwords.size();
                }

                new MaterialAlertDialogBuilder(this)
                    .setTitle("已识别为WiFi数据")
                    .setMessage("文件包含 " + wifiCount + " 个WiFi网络，共 " + totalCount + " 条记录。\n\n是否导入到本地？")
                    .setPositiveButton("导入", (d, w) -> {
                        wifiDataManager.importData(wifiData);
                        Toast.makeText(this, "成功导入 " + wifiCount + " 个WiFi网络", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("打开文件", (d, w) -> openFile(file))
                    .setNeutralButton("取消", null)
                    .show();
                return;
            }
        } catch (Exception e) {
            // 检测失败，走普通打开流程
        }

        // 非WiFi数据，普通打开
        new android.app.AlertDialog.Builder(this)
            .setTitle("下载完成")
            .setMessage(openMessage)
            .setPositiveButton("打开", (dialog, which) -> openFile(file))
            .setNegativeButton("稍后", null)
            .show();
    }

    /**
     * 显示WiFi备份发送对话框（选择要发送的WiFi数据）
     */
    private void showWifiBackupSendDialog() {
        new Thread(() -> {
            try {
                WifiDataManager wifiDataManager = WifiDataManager.getInstance(this);
                java.util.List<WifiDataItem> wifiList = wifiDataManager.getWifiDataListFull();

                if (wifiList == null || wifiList.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "暂无WiFi数据，请先导出WiFi", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 构建选择列表
                String[] ssids = new String[wifiList.size()];
                for (int i = 0; i < wifiList.size(); i++) {
                    ssids[i] = wifiList.get(i).getSsid();
                }
                boolean[] checked = new boolean[ssids.length];

                runOnUiThread(() -> {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("选择要发送的WiFi数据")
                            .setMultiChoiceItems(ssids, checked, (dialog, which, isChecked) -> {
                                checked[which] = isChecked;
                            })
                            .setPositiveButton("发送", (dialog, which) -> {
                                // 收集选中的WiFi数据
                                java.util.List<WifiDataItem> selectedList = new ArrayList<>();
                                for (int i = 0; i < ssids.length; i++) {
                                    if (checked[i]) {
                                        selectedList.add(wifiList.get(i));
                                    }
                                }
                                if (selectedList.isEmpty()) {
                                    Toast.makeText(this, "请选择要发送的WiFi", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                sendWifiBackupData(selectedList);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "读取WiFi数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 发送WiFi备份数据到聊天
     */
    private void sendWifiBackupData(java.util.List<WifiDataItem> selectedList) {
        new Thread(() -> {
            try {
                WifiDataManager wifiDataManager = WifiDataManager.getInstance(this);
                String jsonData = wifiDataManager.exportSelectedToString(selectedList);
                String encryptedData = AESUtil.encrypt(jsonData);

                LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
                msg.setType("wifi_backup");
                msg.setContent(encryptedData);
                msg.setFileName("WiFi备份数据");
                msg.setSender(chatManager.getUserName());
                msg.setSenderIp(NetworkUtils.getLocalIpAddress());
                msg.setSenderPort(chatManager.getTcpServerPort());
                msg.setTimestamp(System.currentTimeMillis());

                if (isRoomChat) {
                    chatManager.sendRoomMessage(msg);
                } else if (currentPeer != null) {
                    chatManager.sendMessage(currentPeer, msg);
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "WiFi备份数据已发送", Toast.LENGTH_SHORT).show();
                    messageList.add(msg);
                    if (messageAdapter != null) {
                        messageAdapter.addMessage(msg);
                    }
                    if (messageRecyclerView != null) {
                        messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 导入WiFi备份数据
     */
    private void importWifiBackup(LanChatManager.ChatMessage message) {
        String encryptedData = message.getContent();
        if (TextUtils.isEmpty(encryptedData)) {
            Toast.makeText(this, "备份数据为空", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("导入WiFi备份")
                .setMessage("是否导入来自 " + message.getSender() + " 的WiFi备份数据？")
                .setPositiveButton("导入", (dialog, which) -> {
                    new Thread(() -> {
                        try {
                            WifiDataManager wifiDataManager = WifiDataManager.getInstance(this);
                            java.util.Map<String, java.util.List<String>> parsedData =
                                    wifiDataManager.parseWifiData(encryptedData);

                            if (parsedData.isEmpty()) {
                                runOnUiThread(() -> Toast.makeText(this,
                                        "未找到有效的WiFi数据", Toast.LENGTH_SHORT).show());
                                return;
                            }

                            wifiDataManager.importData(parsedData);

                            int wifiCount = parsedData.size();
                            int totalCount = 0;
                            for (java.util.List<String> passwords : parsedData.values()) {
                                totalCount += passwords.size();
                            }

                            int finalTotalCount = totalCount;
                            runOnUiThread(() -> Toast.makeText(this,
                                    "成功导入 " + wifiCount + " 个WiFi网络，共 " + finalTotalCount + " 条记录",
                                    Toast.LENGTH_SHORT).show());

                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 打开文件
     */
    private void openFile(File file) {
        try {
            Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getName())
            ));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onFileRequestReceived(LanChatManager.LanPeer peer, String fileName, long fileSize, String filePath) {
        // 文件数据已在 handleTcpClient 中接收并保存
        // 这里仅更新UI
        runOnUiThread(() -> {
            // 显示接收文件的消息
            LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
            msg.setType("file");
            msg.setContent("[文件] " + fileName);
            msg.setFileName(fileName);
            msg.setFileSize(fileSize);
            msg.setFilePath(filePath);
            msg.setSender(peer.getUserName());
            msg.setTimestamp(System.currentTimeMillis());

            // 添加到消息列表
            messageList.add(msg);
            messageAdapter.addMessage(msg);
            messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);

            // 保存到数据库
            database.saveMessage(peerIp, peerName, msg);

            Toast.makeText(LanChatRoomActivity.this, "文件接收完成：" + fileName, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 重新注册监听器（从后台回来时恢复）
        if (chatManager != null) {
            chatManager.removePeerChangeListener(this);
            chatManager.addPeerChangeListener(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停时取消监听（确保后台能收到通知）
        if (chatManager != null) {
            chatManager.removePeerChangeListener(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 处理通知点击重新进入房间
        if (intent != null) {
            boolean newIsRoom = intent.getBooleanExtra("is_room", false);
            String newRoomCode = intent.getStringExtra("room_code");
            String newRoomName = intent.getStringExtra("room_name");
            if (newIsRoom && newRoomCode != null && !newRoomCode.isEmpty()) {
                isRoomChat = true;
                roomCode = newRoomCode;
                roomName = newRoomName;
                if (chatManager != null) {
                    chatManager.setRoomCode(roomCode);
                }
                setupToolbar();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 退出房间：发送离开通知 + 清理本地状态
        if (isRoomChat && chatManager != null && roomCode != null) {
            chatManager.leaveRoom(roomCode);
        }
        super.onDestroy();
        // 清除监听器，避免内存泄漏
        if (chatManager != null) {
            chatManager.removePeerChangeListener(this);
        }
        chatExecutor.shutdownNow();
    }

    /**
     * 从Uri获取文件路径
     */
    private String getPathFromUri(Uri uri) {
        String filePath = null;
        try {
            // 使用内容解析器查询文件路径
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex("_data");
                    if (columnIndex >= 0) {
                        filePath = cursor.getString(columnIndex);
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            // 忽略异常，返回null
        }
        return filePath;
    }

    // ==================== OnPeerChangeListener ====================

    @Override
    public void onPeerListChanged(List<LanChatManager.LanPeer> peers) {
        // 好友列表变化，更新当前聊天对象状态
        if (currentPeer != null) {
            for (LanChatManager.LanPeer peer : peers) {
                if (peer.getIp().equals(currentPeer.getIp())) {
                    currentPeer = peer;
                    break;
                }
            }
        }
        // 房间模式下更新房间信息栏（在线人数、房主等）
        if (isRoomChat) {
            runOnUiThread(() -> updateRoomInfoBar());
        }
    }

    @Override
    public void onMessageReceived(LanChatManager.LanPeer peer, LanChatManager.ChatMessage message) {
        try {
            if (message == null) return;

            // 过滤非聊天类型的消息（握手响应、好友请求等协议消息不应显示在聊天界面）
            String msgType = message.getType();
            if ("handshake".equals(msgType) || "handshake_response".equals(msgType)
                    || "friend_response".equals(msgType)) {
                return;
            }

            // 送达确认消息：更新对应消息的状态
            if ("delivery_status".equals(msgType)) {
                String deliveredMsgId = message.getMessageId();
                if (deliveredMsgId != null) {
                    runOnUiThread(() -> updateMessageStatus(deliveredMsgId, "delivered"));
                }
                return;
            }

            // 房间创建消息：提取创建者IP
            if ("room_create".equals(msgType)) {
                runOnUiThread(() -> updateRoomInfoBar());
                return;
            }

            // 被踢出房间消息
            if ("KICKED".equals(msgType)) {
                runOnUiThread(() -> {
                    Toast.makeText(LanChatRoomActivity.this, "你已被踢出房间", Toast.LENGTH_LONG).show();
                    finish(); // 关闭聊天页面
                });
                return;
            }

            // 房间踢人消息（通知其他人）
            if ("room_kick".equals(msgType)) {
                String targetUser = message.getTargetUser();
                if (targetUser != null && !targetUser.isEmpty()) {
                    runOnUiThread(() -> {
                        Toast.makeText(LanChatRoomActivity.this, "用户 " + targetUser + " 已被踢出房间", Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }

            // 房间邀请消息：弹窗确认是否加入
            if ("room_invite".equals(msgType)) {
                String invitedRoomCode = message.getRoomCode();
                String inviterName = message.getSender();
                String invitedRoomName = message.getRoomName();
                if (invitedRoomCode != null && !invitedRoomCode.isEmpty()) {
                    runOnUiThread(() -> showRoomInviteDialog(inviterName, invitedRoomCode, invitedRoomName));
                }
                return;
            }

            String myUserName = chatManager != null ? chatManager.getUserName() : null;
            String sender = message.getSender();

            // 跳过自己发送的消息（避免重复显示）
            if (myUserName != null && myUserName.equals(sender)) {
                // 对于房间聊天，还需要通过IP和端口进一步确认是否是自己发送的消息
                if (isRoomChat) {
                    String localIp = com.networktool.app.util.NetworkUtils.getLocalIpAddress();
                    int localPort = chatManager != null ? chatManager.getTcpServerPort() : 0;

                    // 跳过自己发送的消息（通过IP+端口判断）
                    if (localIp != null && localIp.equals(message.getSenderIp()) &&
                        localPort == message.getSenderPort()) {
                        return;
                    }
                } else {
                    // 好友聊天模式下，用户名相同就跳过
                    return;
                }
            }

            if (isRoomChat) {
                // 房间聊天模式 - 检查消息的roomCode是否匹配当前房间
                String msgRoomCode = message.getRoomCode();
                if (msgRoomCode != null && !msgRoomCode.isEmpty() && roomCode != null
                    && !roomCode.equals(msgRoomCode)) {
                    android.util.Log.d("LanChatRoom", "Skipping message from different room: current=" + roomCode + " msg=" + msgRoomCode);
                    return;
                }
                // 显示其他人发送的消息
                // 不再从消息猜测房主，房主IP只通过 member_list_response 或 room_transfer 设置
                String senderIp = message.getSenderIp();
                if (senderIp != null && !senderIp.isEmpty()) {
                    runOnUiThread(() -> updateRoomInfoBar());
                }
                runOnUiThread(() -> addReceivedMessage(message));
            } else {
                // 好友聊天模式 - 只显示当前聊天对象发送的消息
                if (peer != null && peerIp != null && peer.getIp() != null &&
                    peer.getIp().equals(peerIp) && peer.getTcpPort() == message.getSenderPort()) {
                    runOnUiThread(() -> addReceivedMessage(message));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("LanChatRoom", "Error in onMessageReceived", e);
        }
    }

    /**
     * 显示好友选项菜单
     */
    private void showFriendOptions(LanChatManager.LanPeer peer) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(peer.getUserName());

        String[] options = {"查看详情", "发起私聊"};

        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: // 查看详情
                    showFriendDetails(peer);
                    break;
                case 1: // 发起私聊
                    startPrivateChat(peer);
                    break;
            }
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /**
     * 显示好友详情
     */
    private void showFriendDetails(LanChatManager.LanPeer peer) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("好友详情");

        String details = "用户名: " + peer.getUserName() + "\n" +
                "IP地址: " + peer.getIp() + "\n" +
                "TCP端口: " + peer.getTcpPort() + "\n" +
                "状态: " + (peer.isOnline() ? "在线" : "离线") + "\n" +
                "最后活跃: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(peer.getLastSeen()));

        builder.setMessage(details);
        builder.setPositiveButton("确定", null);
        builder.show();
    }

    /**
     * 发起私聊
     */
    private void startPrivateChat(LanChatManager.LanPeer peer) {
        Intent intent = new Intent(this, LanChatRoomActivity.class);
        intent.putExtra("is_room", false);
        intent.putExtra("peer_ip", peer.getIp());
        intent.putExtra("peer_name", peer.getUserName());
        intent.putExtra("peer_port", peer.getTcpPort());
        startActivity(intent);
    }
}
