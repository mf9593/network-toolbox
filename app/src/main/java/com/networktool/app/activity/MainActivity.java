package com.networktool.app.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import com.networktool.app.R;
import com.networktool.app.activity.tool.LanChatRoomActivity;
import com.networktool.app.adapter.MainPagerAdapter;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.ThemeManager;

/**
 * 主 Activity
 *
 * 统一承载 5 个主页面和底部导航，不设置顶部标题栏。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "lan_chat";
    private static final String KEY_LAST_PAGE = "last_page";
    private static final int PAGE_COUNT = 5;

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;
    private android.view.View bottomDivider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupEdgeToEdge();
        setupViewPager();
        setupBottomNavigation();
        applyThemeColors();

        // 恢复上次页面位置
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int lastPage = prefs.getInt(KEY_LAST_PAGE, 0);
        if (lastPage < 0 || lastPage >= PAGE_COUNT) {
            lastPage = 0;
        }
        viewPager.setCurrentItem(lastPage, false);

        // 注册全局房间邀请监听（确保所有页面都能收到邀请）
        LanChatManager chatManager = LanChatManager.getInstance();
        chatManager.setRoomInviteListener((inviterName, roomCode, roomName) -> {
            if (isFinishing() || isDestroyed()) return;
            String displayName = (roomName != null && !roomName.isEmpty()) ? roomName : ("房间 " + roomCode);
            new MaterialAlertDialogBuilder(this)
                    .setTitle("房间邀请")
                    .setMessage((inviterName != null ? inviterName : "好友") + " 邀请你加入 " + displayName)
                    .setPositiveButton("加入", (dialog, which) -> {
                        if (chatManager != null) {
                            chatManager.setRoomCode(roomCode);
                        }
                        Intent intent = new Intent(this, LanChatRoomActivity.class);
                        intent.putExtra("room_code", roomCode);
                        intent.putExtra("is_room", true);
                        intent.putExtra("room_name", roomName);
                        startActivity(intent);
                    })
                    .setNegativeButton("拒绝", null)
                    .show();
        });
    }

    /**
     * 底栏沉浸：内容延伸到系统手势区域，底部导航背景覆盖系统导航栏，避免多出一条白色系统栏。
     */
    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        boolean dark = ThemeManager.isDarkMode(this);
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);

        View root = findViewById(android.R.id.content);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 顶部给状态栏留出空间；底部导航栏自己延伸到手势区域
            view.setPadding(0, bars.top, 0, 0);
            if (bottomNavigationView != null) {
                // 底部不留边距，让导航栏与系统手势小白条重叠
                bottomNavigationView.setPadding(
                        bottomNavigationView.getPaddingLeft(),
                        bottomNavigationView.getPaddingTop(),
                        bottomNavigationView.getPaddingRight(),
                        0);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void initViews() {
        viewPager = findViewById(R.id.viewPager);
        bottomNavigationView = findViewById(R.id.bottomNavigation);
        bottomDivider = findViewById(R.id.bottomDivider);
    }

    private void setupViewPager() {
        MainPagerAdapter adapter = new MainPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setUserInputEnabled(true);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                bottomNavigationView.getMenu().getItem(position).setChecked(true);
            }
        });
    }

    private void setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.nav_info) {
                    viewPager.setCurrentItem(0, true);
                    return true;
                } else if (itemId == R.id.nav_device) {
                    viewPager.setCurrentItem(1, true);
                    return true;
                } else if (itemId == R.id.nav_wireless) {
                    viewPager.setCurrentItem(2, true);
                    return true;
                } else if (itemId == R.id.nav_tools) {
                    viewPager.setCurrentItem(3, true);
                    return true;
                } else if (itemId == R.id.nav_settings) {
                    viewPager.setCurrentItem(4, true);
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 供 Fragment 请求切换主页面（例如前往 WiFi 页授权）
     */
    public void selectPage(int position) {
        if (viewPager != null && position >= 0 && position < PAGE_COUNT) {
            viewPager.setCurrentItem(position, true);
        }
    }

    /**
     * 应用主题颜色到主框架
     */
    private void applyThemeColors() {
        int surfaceColor = ThemeManager.getSurfaceColor(this);
        int outlineColor = ThemeManager.getOutlineVariantColor(this);

        if (bottomDivider != null) {
            bottomDivider.setBackgroundColor(outlineColor);
        }
        if (bottomNavigationView != null) {
            bottomNavigationView.setBackgroundColor(surfaceColor);
            ColorStateList navigationColors = ThemeManager.getNavigationColorStateList(this);
            bottomNavigationView.setItemIconTintList(navigationColors);
            bottomNavigationView.setItemTextColor(navigationColors);
            try {
                bottomNavigationView.setItemActiveIndicatorColor(
                        ColorStateList.valueOf(ThemeManager.getChipSurfaceColor(this)));
            } catch (Exception ignored) {
                // 低版本 Material 组件不支持时忽略
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (viewPager != null) {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit().putInt(KEY_LAST_PAGE, viewPager.getCurrentItem()).apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyThemeColors();
        ensureLanChatRunning();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LanChatManager.getInstance().setRoomInviteListener(null);
    }

    /**
     * 确保局域网聊天服务在后台运行
     */
    private void ensureLanChatRunning() {
        try {
            LanChatManager chatManager = LanChatManager.getInstance();
            if (chatManager.isRunning()) return;

            SharedPreferences lanChatPrefs = getSharedPreferences("lan_chat", MODE_PRIVATE);
            boolean discoverable = lanChatPrefs.getBoolean("discoverable", true);
            if (!discoverable) return;

            String userName = lanChatPrefs.getString("user_name", "");
            if (userName == null || userName.isEmpty()) {
                userName = "用户" + (int) (Math.random() * 1000);
                lanChatPrefs.edit().putString("user_name", userName).apply();
            }

            final String finalUserName = userName;
            new Thread(() -> {
                try {
                    chatManager.start(MainActivity.this, finalUserName, 0);
                } catch (Exception e) {
                    android.util.Log.e("MainActivity", "Failed to ensure LanChat running", e);
                }
            }, "LanChat-Init").start();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to ensure LanChat running", e);
        }
    }
}
