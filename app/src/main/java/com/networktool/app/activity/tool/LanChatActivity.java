package com.networktool.app.activity.tool;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.LanChatDatabase;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;

/**
 * 局域网聊天主Activity
 *
 * 包含两个Tab：
 * 1. 好友列表 - 显示在线好友，点击进入聊天
 * 2. 发现 - 搜索公开好友/房间
 */
public class LanChatActivity extends AppCompatActivity {

    private static final String TAG = "LanChatActivity";

    private MaterialToolbar toolbar;
    private TabLayout tabLayout;
    private ViewPager viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Log.d(TAG, "LanChatActivity starting");

            setContentView(R.layout.activity_lan_chat);
            Log.d(TAG, "setContentView completed");

            setupStatusBar();
            Log.d(TAG, "setupStatusBar completed");

            initViews();
            Log.d(TAG, "initViews completed");

            setupToolbar();
            Log.d(TAG, "setupToolbar completed");

            // 检查用户名，如果是随机默认名则提示修改
            SharedPreferences prefs = getSharedPreferences("lan_chat", MODE_PRIVATE);
            String savedUserName = prefs.getString("user_name", "");

            if (savedUserName.isEmpty() || savedUserName.startsWith("用户")) {
                showUserNameDialog(prefs);
                return; // 对话框会异步处理后续逻辑
            }

            setupTabLayout();
            Log.d(TAG, "setupTabLayout completed");

            startChatManager(prefs, savedUserName);
            Log.d(TAG, "LanChatActivity created successfully");
        } catch (Exception e) {
            AppErrorLogger.catchError(TAG, "LanChatActivity onCreate failed", e);
            Log.e(TAG, "LanChatActivity crash", e);
            finish();
        }
    }

    /**
     * 显示设置用户名对话框
     */
    private void showUserNameDialog(SharedPreferences prefs) {
        EditText input = new EditText(this);
        input.setHint("请输入你的昵称");
        input.setPadding(48, 24, 48, 24);

        new MaterialAlertDialogBuilder(this)
                .setTitle("设置昵称")
                .setMessage("你当前使用的是随机昵称，请设置一个专属昵称以便好友识别")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("确定", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show();
                        showUserNameDialog(prefs); // 重新弹出
                        return;
                    }
                    // 保存用户名
                    prefs.edit().putString("user_name", name).apply();
                    // 继续初始化
                    setupTabLayout();
                    startChatManager(prefs, name);
                })
                .setNegativeButton("退出", (dialog, which) -> finish())
                .show();
    }

    /**
     * 启动聊天管理器
     */
    private void startChatManager(SharedPreferences prefs, String userName) {
        int tcpPort = 9000 + (int)(Math.random() * 1000);

        Log.d(TAG, "Starting LanChatManager with user: " + userName + ", port: " + tcpPort);
        LanChatManager chatManager = LanChatManager.getInstance();

        // 先注册房间邀请监听，避免服务启动期间遗漏邀请
        chatManager.setRoomInviteListener((inviterName, roomCode, roomName) -> {
            if (isFinishing() || isDestroyed()) return;
            String displayName = (roomName != null && !roomName.isEmpty()) ? roomName : ("房间 " + roomCode);
            new MaterialAlertDialogBuilder(this)
                    .setTitle("房间邀请")
                    .setMessage((inviterName != null ? inviterName : "好友") + " 邀请你加入 " + displayName)
                    .setPositiveButton("加入", (dialog, which) -> {
                        chatManager.setRoomCode(roomCode);
                        Intent intent = new Intent(this, LanChatRoomActivity.class);
                        intent.putExtra("room_code", roomCode);
                        intent.putExtra("is_room", true);
                        intent.putExtra("room_name", roomName);
                        startActivity(intent);
                    })
                    .setNegativeButton("拒绝", null)
                    .show();
        });

        // 端口协商包含等待，不能放在主线程，避免进入聊天页卡顿
        new Thread(() -> {
            try {
                chatManager.start(getApplicationContext(), userName, tcpPort);
            } catch (Exception e) {
                AppErrorLogger.catchError(TAG, "启动局域网聊天服务失败", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "局域网聊天服务启动失败", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "lan-chat-start").start();
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
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.tabContent);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("局域网聊天");
        }

        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupTabLayout() {
        // 设置ViewPager适配器
        viewPager.setAdapter(new ChatPagerAdapter(getSupportFragmentManager()));

        // setupWithViewPager会自动从适配器的getPageTitle获取Tab标题
        tabLayout.setupWithViewPager(viewPager);

        // 确保 Tab 文字颜色在深色/浅色主题下都清晰
        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        int unselectedColor = ThemeManager.getOnSurfaceVariantColor(this);
        tabLayout.setTabTextColors(unselectedColor, accentColor);
        tabLayout.setSelectedTabIndicatorColor(accentColor);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_lan_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_clear_chat) {
            showClearChatDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 显示清空聊天列表确认对话框
     */
    private void showClearChatDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("清空聊天列表")
                .setMessage("确定要清空所有好友和聊天记录吗？\n\n" +
                        "将清除：\n" +
                        "• 所有好友列表\n" +
                        "• 所有聊天消息\n\n" +
                        "此操作不可恢复！")
                .setPositiveButton("确定清空", (dialog, which) -> clearChatList())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 清空聊天列表（好友 + 聊天记录）
     */
    private void clearChatList() {
        try {
            // 清空数据库
            LanChatDatabase database = LanChatDatabase.getInstance(this);
            database.clearAllMessages();
            database.clearAllFriends();

            // 清空内存中的peer列表
            LanChatManager chatManager = LanChatManager.getInstance();
            chatManager.clearPeers();

            Toast.makeText(this, "聊天列表已清空", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing chat list", e);
            Toast.makeText(this, "清空失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清除房间邀请监听（避免内存泄漏）
        LanChatManager.getInstance().setRoomInviteListener(null);
    }

    /**
     * ViewPager适配器
     */
    private class ChatPagerAdapter extends FragmentPagerAdapter {

        private final String[] tabTitles = {"好友", "发现"};

        public ChatPagerAdapter(@NonNull FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return LanChatPeersFragment.newInstance();
                case 1:
                    return LanChatDiscoverFragment.newInstance();
                default:
                    return LanChatPeersFragment.newInstance();
            }
        }

        @Override
        public int getCount() {
            return tabTitles.length;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return tabTitles[position];
        }
    }
}
