package com.networktool.app.activity.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.networktool.app.R;
import com.networktool.app.adapter.WifiDataAdapter;
import com.networktool.app.model.WifiDataItem;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiDataManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WiFi数据库Activity
 *
 * 显示已导入的WiFi数据，支持：
 * - 搜索WiFi名称或密码
 * - 查看WiFi列表
 * - 复制密码
 * - 删除记录
 */
public class WifiDatabaseActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private TextView countText;
    private EditText searchEditText;
    private ImageButton clearSearchButton;

    private WifiDataManager wifiDataManager;
    private WifiDataAdapter adapter;
    private List<WifiDataItem> allWifiDataList;
    private List<WifiDataItem> filteredList;
    private Map<String, List<String>> allWifiData; // 原始多密码数据

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_database);

        setupStatusBar();
        setupToolbar();

        wifiDataManager = WifiDataManager.getInstance(this);
        filteredList = new ArrayList<>();

        initViews();
        loadData();
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    /**
     * 设置工具栏主题
     */
    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            getSupportActionBar().setTitle("数据库管理");
        }

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        countText = findViewById(R.id.countText);
        searchEditText = findViewById(R.id.searchEditText);
        clearSearchButton = findViewById(R.id.clearSearchButton);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        countText.setTextColor(accentColor);
        searchEditText.setTextColor(ThemeManager.getOnSurfaceColor(this));
        searchEditText.setHintTextColor(ThemeManager.getOnSurfaceVariantColor(this));
        clearSearchButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                ThemeManager.getOnSurfaceVariantColor(this)));

        // 搜索框监听
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filterData(s.toString());
                clearSearchButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        // 清除搜索按钮
        clearSearchButton.setOnClickListener(v -> {
            searchEditText.setText("");
            filterData("");
        });
    }

    private void loadData() {
        allWifiData = wifiDataManager.getAllWifiData();
        // 使用 getWifiDataList() 获取同名只显示一个的列表
        allWifiDataList = wifiDataManager.getWifiDataList();
        filteredList = new ArrayList<>(allWifiDataList);

        updateDisplay();
    }

    /**
     * 过滤数据
     */
    private void filterData(String keyword) {
        filteredList.clear();

        if (keyword.isEmpty()) {
            filteredList.addAll(allWifiDataList);
        } else {
            keyword = keyword.toLowerCase();
            for (WifiDataItem item : allWifiDataList) {
                // 搜索SSID或密码
                if (item.getSsid().toLowerCase().contains(keyword) ||
                        (item.getPassword() != null && item.getPassword().toLowerCase().contains(keyword))) {
                    filteredList.add(item);
                }
            }
        }

        updateDisplay();
    }

    private void updateDisplay() {
        if (filteredList.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            countText.setText("0 条记录");
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            int passwordTotal = 0;
            for (WifiDataItem dataItem : filteredList) {
                passwordTotal += Math.max(1, dataItem.getPasswordCount());
            }
            countText.setText(filteredList.size() + " 个 WiFi · "
                    + passwordTotal + " 条密码");

            adapter = new WifiDataAdapter(filteredList);
            adapter.setOnClickListener(this::onItemClick);
            adapter.setOnLongClickListener(this::onItemLongClick);
            recyclerView.setAdapter(adapter);
        }
    }

    /**
     * 点击或长按统一打开底部操作面板，不再使用多层中间弹窗。
     */
    private void onItemClick(WifiDataItem item, int position) {
        showWifiActionSheet(item);
    }

    private void onItemLongClick(WifiDataItem item, int position) {
        showWifiActionSheet(item);
    }

    private void showWifiActionSheet(WifiDataItem item) {
        List<String> passwords = allWifiData.get(item.getSsid());
        if ((passwords == null || passwords.isEmpty()) && item.hasPassword()) {
            passwords = new ArrayList<>();
            passwords.add(item.getPassword());
        }
        if (passwords == null) {
            passwords = new ArrayList<>();
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this)
                .inflate(R.layout.bottomsheet_wifi_database, null);
        dialog.setContentView(view);

        TextView sheetSsid = view.findViewById(R.id.sheetSsid);
        TextView sheetSubtitle = view.findViewById(R.id.sheetSubtitle);
        View passwordScroll = view.findViewById(R.id.passwordScroll);
        LinearLayout passwordContainer = view.findViewById(R.id.passwordListContainer);
        MaterialButton closeButton = view.findViewById(R.id.closeButton);

        sheetSsid.setText(item.getSsid());
        String security = item.getSecurityType() == null
                ? "未知加密" : item.getSecurityType();
        sheetSubtitle.setText(security + " · "
                + (passwords.isEmpty() ? "无密码记录" : passwords.size() + " 个密码"));

        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        int errorColor = ThemeManager.getErrorColor(this);
        closeButton.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));

        passwordContainer.removeAllViews();
        if (passwords.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("该网络没有保存密码");
            empty.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));
            empty.setTextSize(14);
            empty.setPadding(0, 16, 0, 16);
            passwordContainer.addView(empty);
            passwordScroll.getLayoutParams().height = dp(56);
        } else {
            LayoutInflater inflater = LayoutInflater.from(this);
            float density = getResources().getDisplayMetrics().density;
            int rowHeight = Math.round(50 * density);
            int maxHeight = Math.min(Math.round(300 * density),
                    getResources().getDisplayMetrics().heightPixels - dp(220));
            passwordScroll.getLayoutParams().height = Math.max(dp(50),
                    Math.min(rowHeight * passwords.size(), Math.max(dp(80), maxHeight)));

            for (String password : passwords) {
                View row = inflater.inflate(R.layout.item_wifi_password_action,
                        passwordContainer, false);
                TextView passwordText = row.findViewById(R.id.passwordText);
                MaterialButton copyButton = row.findViewById(R.id.copyButton);
                MaterialButton deleteButton = row.findViewById(R.id.deleteButton);
                passwordText.setText(password == null || password.isEmpty()
                        ? "空密码" : password);
                copyButton.setTextColor(accentColor);
                deleteButton.setTextColor(errorColor);
                copyButton.setOnClickListener(v -> {
                    copyToClipboard(password == null ? "" : password);
                    Toast.makeText(this, "密码已复制", Toast.LENGTH_SHORT).show();
                });
                deleteButton.setOnClickListener(v -> {
                    String deletedPassword = password;
                    wifiDataManager.deleteWifi(item.getSsid(), deletedPassword);
                    dialog.dismiss();
                    loadData();
                    Snackbar.make(findViewById(android.R.id.content),
                                    "已删除密码", Snackbar.LENGTH_LONG)
                            .setAction("撤销", undo -> {
                                Map<String, List<String>> restore = new java.util.HashMap<>();
                                restore.put(item.getSsid(),
                                        java.util.Collections.singletonList(deletedPassword));
                                wifiDataManager.importData(restore);
                                loadData();
                                Toast.makeText(this, "已恢复密码", Toast.LENGTH_SHORT).show();
                            })
                            .setActionTextColor(accentColor)
                            .show();
                });
                passwordContainer.addView(row);
            }
        }

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("WiFi Password", text);
        clipboard.setPrimaryClip(clip);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.wifi_database_menu, menu);
        MenuItem clearItem = menu.findItem(R.id.action_clear_empty_passwords);
        if (clearItem != null && clearItem.getIcon() != null) {
            clearItem.getIcon().setTint(ThemeManager.getOnSurfaceVariantColor(this));
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_clear_empty_passwords) {
            clearEmptyPasswords();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 批量清除所有WiFi的空密码
     */
    private void clearEmptyPasswords() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("清除空密码")
                .setMessage("确定要删除所有 WiFi 的空密码记录吗？\n（空密码、\"NONE\" 和开放网络的“空密码”占位记录都会被删除）")
                .setPositiveButton("确定", (dialog, which) -> {
                    int removed = wifiDataManager.removeEmptyPasswords();
                    loadData();
                    Toast.makeText(this, "已清除 " + removed + " 条空密码记录", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }
}
