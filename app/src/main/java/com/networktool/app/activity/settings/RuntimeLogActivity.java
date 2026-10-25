package com.networktool.app.activity.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.RuntimeLogStore;
import com.networktool.app.util.ThemeManager;

/**
 * 运行日志页面
 *
 * 分两个页签：
 * 1. 崩溃日志 —— 持久化，存放在 files/crashes，重启后仍可查看。
 * 2. 运行报文 —— 仅保存在内存（{@link RuntimeLogStore}），
 *    进程退出即清空，不会写入磁盘，因此不会长期保留可能含密码的报文。
 *    可按协议名或关键字搜索，例如点击设备的 mDNS/ONVIF 标签后跳转到此页
 *    查看该协议的原始报文，用于核对标题截取是否正确。
 */
public class RuntimeLogActivity extends AppCompatActivity {

    /** 传入的协议名过滤（可为空） */
    public static final String EXTRA_PROTOCOL = "extra_protocol";

    private TextView logText;
    private TextInputEditText searchInput;
    private TextInputLayout searchLayout;
    private TabLayout tabLayout;
    private String currentProtocol = "";
    private int currentTab = 0; // 0=崩溃日志 1=运行报文

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_runtime_log);

        currentProtocol = getIntent().getStringExtra(EXTRA_PROTOCOL);

        setupStatusBar();
        setupToolbar();
        initViews();

        // 若外部传入协议名，默认切到「运行报文」页签并预填搜索
        if (currentProtocol != null && !currentProtocol.isEmpty()) {
            currentTab = 1;
            tabLayout.selectTab(tabLayout.getTabAt(1));
            searchInput.setText(currentProtocol);
        }

        render();
    }

    private void setupStatusBar() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("运行日志");
        }
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        logText = findViewById(R.id.logText);
        searchInput = findViewById(R.id.searchInput);
        searchLayout = findViewById(R.id.searchLayout);
        tabLayout = findViewById(R.id.tabLayout);

        applyThemeColors();

        tabLayout.addTab(tabLayout.newTab().setText("崩溃日志"));
        tabLayout.addTab(tabLayout.newTab().setText("运行报文"));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                render();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }
            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) { render(); }
        });
    }

    /** 让输入框描边/提示、刷新按钮等强调色跟随主题 */
    private void applyThemeColors() {
        int accent = ThemeManager.getAccessibleThemeColor(this);
        if (searchLayout != null) {
            searchLayout.setBoxStrokeColor(accent);
            searchLayout.setBoxStrokeColorStateList(
                    ColorStateList.valueOf(accent));
            searchLayout.setDefaultHintTextColor(ColorStateList.valueOf(accent));
        }
        if (searchInput != null) {
            searchInput.setTextColor(ThemeManager.getOnSurfaceColor(this));
            searchInput.setHintTextColor(ColorStateList.valueOf(accent));
        }
        logText.setTextColor(ThemeManager.getOnSurfaceColor(this));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_runtime_log, menu);
        // 图标着色跟随文字色（标题栏）
        int titleColor = ThemeManager.getOnSurfaceColor(this);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.getIcon() != null) {
                item.getIcon().setTint(titleColor);
            }
        }
        return true;
    }

    /** 根据当前页签和搜索词渲染内容 */
    private void render() {
        String keyword = searchInput.getText() == null
                ? "" : searchInput.getText().toString().trim();

        if (currentTab == 0) {
            // 崩溃日志：持久化内容，按关键字过滤
            String raw = AppErrorLogger.getCrashLogs();
            logText.setText(keyword.isEmpty() ? raw : filterLines(raw, keyword));
        } else {
            // 运行报文：内存存储，按协议+关键字搜索
            java.util.List<RuntimeLogStore.Entry> entries =
                    RuntimeLogStore.search(null, keyword);
            if (entries.isEmpty()) {
                logText.setText(keyword.isEmpty()
                        ? "暂无运行报文。\n\n报文只保留在内存中，应用重启后会自动清空。\n重新扫描设备后即可在此查看各协议的原始报文。"
                        : "没有匹配「" + keyword + "」的报文。\n\n可尝试清空搜索词，或重新扫描设备。");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("本次运行报文共 ").append(entries.size()).append(" 条")
              .append("（仅内存，重启清空）\n")
              .append("========================================\n\n");
            for (RuntimeLogStore.Entry e : entries) {
                sb.append(RuntimeLogStore.format(e)).append("\n\n");
            }
            logText.setText(sb.toString());
        }
    }

    /** 简单按行过滤关键字 */
    private String filterLines(String text, String keyword) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.contains(keyword)) {
                sb.append(line).append("\n");
            }
        }
        return sb.length() == 0 ? "没有匹配「" + keyword + "」的记录。" : sb.toString();
    }

    /** 复制当前显示内容 */
    private void copyLogs() {
        try {
            CharSequence text = logText.getText();
            if (text == null || text.length() == 0) {
                Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("运行日志", text));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 清除前确认：崩溃日志清持久化文件，运行报文清内存 */
    private void confirmClear() {
        boolean isCrashTab = currentTab == 0;
        String msg = isCrashTab
                ? "确定清除全部持久化崩溃日志吗？此操作不可恢复。"
                : "确定清空本次运行的报文记录吗？（仅清内存，不影响崩溃日志）";
        new MaterialAlertDialogBuilder(this)
                .setTitle("清除日志")
                .setMessage(msg)
                .setPositiveButton("清除", (d, w) -> {
                    if (isCrashTab) {
                        int n = AppErrorLogger.clearCrashLogs();
                        Toast.makeText(this, "已清除 " + n + " 条崩溃日志", Toast.LENGTH_SHORT).show();
                    } else {
                        RuntimeLogStore.clear();
                        Toast.makeText(this, "已清空运行报文", Toast.LENGTH_SHORT).show();
                    }
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_copy) {
            copyLogs();
            return true;
        }
        if (id == R.id.action_refresh) {
            render();
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_clear) {
            confirmClear();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
