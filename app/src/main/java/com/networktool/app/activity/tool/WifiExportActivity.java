package com.networktool.app.activity.tool;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.model.WifiDataItem;
import com.networktool.app.util.AESUtil;
import com.networktool.app.util.AppErrorLogger;

import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiDataManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WiFi数据导出Activity
 *
 * 将WiFi数据导出到文件，支持：
 * - 选择要导出的数据
 * - AES加密导出
 * - 多种导出格式
 * - 异步加载避免卡顿
 *
 * 注意：使用 ActivityResultLauncher 替代已弃用的 startActivityForResult
 * 使用 RecyclerView 替代 ScrollView 避免滑动时视图回收导致闪退
 */
public class WifiExportActivity extends AppCompatActivity {

    // 使用 ActivityResultLauncher 替代已弃用的 startActivityForResult
    private ActivityResultLauncher<Intent> saveFileLauncher;

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private TextView countText;
    private MaterialButton exportAllButton;
    private MaterialButton exportSelectedButton;
    private MenuItem selectAllMenuItem;
    private ProgressBar loadingProgressBar;

    private WifiDataManager wifiDataManager;
    private List<WifiDataItem> wifiDataList;

    private ExecutorService executorService;
    private Handler mainHandler;

    // 导出缓存
    private String pendingContent;
    private String pendingFileName;
    private String pendingMimeType;

    // 选中状态跟踪，避免依赖视图
    private boolean[] selectedStates;

    // RecyclerView 适配器
    private WifiExportAdapter exportAdapter;

    /**
     * WiFi 数据导出 RecyclerView 适配器
     */
    private class WifiExportAdapter extends RecyclerView.Adapter<WifiExportAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_wifi_export, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            WifiDataItem item = wifiDataList.get(position);
            holder.ssidText.setText(item.getSsid());
            holder.securityText.setText(item.getSecurityType());
            styleThemeBadge(holder.securityText);
            holder.passwordText.setText(maskPassword(item.getPassword()));
            holder.checkbox.setChecked(selectedStates[position]);

            int themeColor = ThemeManager.getAccessibleThemeColor(holder.itemView.getContext());
            holder.wifiIcon.setColorFilter(themeColor, PorterDuff.Mode.SRC_IN);
            ThemeManager.setupResultCard(holder.cardView, holder.itemView.getContext());

            if (item.getPasswordCount() > 1) {
                holder.passwordCountBadge.setVisibility(View.VISIBLE);
                holder.passwordCountBadge.setText(item.getPasswordCount() + " 个密码");
                styleThemeBadge(holder.passwordCountBadge);
            } else {
                holder.passwordCountBadge.setVisibility(View.GONE);
            }

            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
            };
            int[] colors = new int[]{themeColor, 0xFF888888};
            holder.checkbox.setButtonTintList(new android.content.res.ColorStateList(states, colors));

            // 使用选中状态数组，避免依赖视图
            holder.checkbox.setOnCheckedChangeListener(null); // 清除旧监听
            holder.checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int currentPosition = holder.getBindingAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION
                        && selectedStates != null
                        && currentPosition < selectedStates.length) {
                    selectedStates[currentPosition] = isChecked;
                    updateSelectedCount();
                }
            });
        }

        @Override
        public int getItemCount() {
            return wifiDataList != null ? wifiDataList.size() : 0;
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView cardView;
            ImageView wifiIcon;
            TextView ssidText;
            TextView securityText;
            TextView passwordText;
            TextView passwordCountBadge;
            CheckBox checkbox;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                cardView = itemView.findViewById(R.id.cardView);
                wifiIcon = itemView.findViewById(R.id.wifiIcon);
                ssidText = itemView.findViewById(R.id.ssidText);
                securityText = itemView.findViewById(R.id.securityText);
                passwordText = itemView.findViewById(R.id.passwordText);
                passwordCountBadge = itemView.findViewById(R.id.passwordCountBadge);
                checkbox = itemView.findViewById(R.id.itemCheckbox);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_export);

        setupStatusBar();
        setupToolbar();

        // 注册 Activity Result Launcher
        saveFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null && data.getData() != null) {
                            saveFileToUri(data.getData());
                        }
                    }
                });

        wifiDataManager = WifiDataManager.getInstance(this);
        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        loadDataAsync();
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
            getSupportActionBar().setTitle("导出WiFi数据");
        }

        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        countText = findViewById(R.id.countText);
        exportAllButton = findViewById(R.id.exportAllButton);
        exportSelectedButton = findViewById(R.id.exportSelectedButton);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);

        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        countText.setTextColor(accentColor);
        exportAllButton.setTextColor(accentColor);
        exportAllButton.setStrokeColor(android.content.res.ColorStateList.valueOf(accentColor));
        ThemeManager.applyPrimaryButton(exportSelectedButton);

        exportAllButton.setOnClickListener(v -> showExportDialog(true));
        exportSelectedButton.setOnClickListener(v -> showExportDialog(false));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_wifi_export, menu);
        selectAllMenuItem = menu.findItem(R.id.action_select_all);
        updateSelectAllMenu();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_select_all) {
            selectAll(!isAllSelected());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isAllSelected() {
        if (selectedStates == null || selectedStates.length == 0) return false;
        for (boolean selected : selectedStates) {
            if (!selected) return false;
        }
        return true;
    }

    private void selectAll(boolean selected) {
        if (selectedStates == null) return;
        for (int i = 0; i < selectedStates.length; i++) {
            selectedStates[i] = selected;
        }
        if (exportAdapter != null) {
            exportAdapter.notifyDataSetChanged();
        }
        updateSelectedCount();
    }

    private void updateSelectAllMenu() {
        if (selectAllMenuItem == null) return;
        boolean hasData = selectedStates != null && selectedStates.length > 0;
        selectAllMenuItem.setVisible(hasData);
        boolean allSelected = hasData && isAllSelected();
        selectAllMenuItem.setTitle(allSelected ? "取消全选" : "全选");
        selectAllMenuItem.setChecked(allSelected);
        selectAllMenuItem.setIcon(allSelected ? R.drawable.ic_close : R.drawable.ic_check);
        if (selectAllMenuItem.getIcon() != null) {
            selectAllMenuItem.getIcon().setTint(ThemeManager.getOnSurfaceColor(this));
        }
    }

    private void loadDataAsync() {
        // 显示加载中
        loadingProgressBar.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);

        executorService.execute(() -> {
            // 在后台线程加载数据
            wifiDataList = wifiDataManager.getWifiDataList();

            // 在主线程更新UI
            mainHandler.post(() -> {
                loadingProgressBar.setVisibility(View.GONE);
                displayData();
            });
        });
    }

    private void displayData() {
        if (wifiDataList == null || wifiDataList.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            countText.setText("没有可导出的WiFi数据");
            exportAllButton.setEnabled(false);
            exportSelectedButton.setEnabled(false);
            selectedStates = null;
            updateSelectAllMenu();
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            int passwordTotal = 0;
            for (WifiDataItem dataItem : wifiDataList) {
                passwordTotal += Math.max(1, dataItem.getPasswordCount());
            }
            countText.setText("共 " + wifiDataList.size() + " 个 WiFi · "
                    + passwordTotal + " 条密码");

            // 初始化选中状态
            selectedStates = new boolean[wifiDataList.size()];

            // 设置 RecyclerView
            exportAdapter = new WifiExportAdapter();
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(exportAdapter);
            updateSelectedCount();
        }
    }

    private void styleThemeBadge(TextView badge) {
        int themeColor = ThemeManager.getThemeColor();
        int surfaceColor = ThemeManager.getSurfaceColor(this);
        int backgroundColor = ThemeManager.blendColorWithBase(themeColor, surfaceColor, 44);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(8));
        badge.setBackground(background);
        badge.setTextColor(ThemeManager.getAccessibleThemeColor(this));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String maskPassword(String password) {
        if (password == null || password.isEmpty() || "空密码".equals(password)) {
            return "无密码";
        }
        if (password.length() <= 4) {
            return "****";
        }
        return password.substring(0, 2) + "****" + password.substring(password.length() - 2);
    }

    private void updateSelectedCount() {
        int selectedCount = 0;
        // 使用选中状态数组，避免依赖视图
        if (selectedStates != null) {
            for (boolean selected : selectedStates) {
                if (selected) {
                    selectedCount++;
                }
            }
        }
        exportSelectedButton.setText("导出选中(" + selectedCount + ")");
        exportSelectedButton.setEnabled(selectedCount > 0);
        updateSelectAllMenu();
    }

    /**
     * 从底部弹出导出格式选择
     */
    private void showExportDialog(boolean exportAll) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this)
                .inflate(R.layout.bottomsheet_export_format, null);
        dialog.setContentView(view);

        int accentColor = ThemeManager.getAccessibleThemeColor(this);
        MaterialButton encrypted = view.findViewById(R.id.formatEncrypted);
        MaterialButton plain = view.findViewById(R.id.formatPlain);
        MaterialButton txt = view.findViewById(R.id.formatTxt);
        MaterialButton cancel = view.findViewById(R.id.cancelFormatButton);

        encrypted.setTextColor(accentColor);
        plain.setTextColor(accentColor);
        txt.setTextColor(accentColor);
        cancel.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));

        encrypted.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExportData(exportAll, 0);
        });
        plain.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExportData(exportAll, 1);
        });
        txt.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExportData(exportAll, 2);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 异步准备导出数据，避免UI卡顿
     */
    private void prepareExportData(boolean exportAll, int format) {
        // 显示进度
        loadingProgressBar.setVisibility(View.VISIBLE);

        // 先在主线程收集需要导出的数据索引
        List<Integer> selectedIndices = new ArrayList<>();
        if (exportAll) {
            for (int i = 0; i < wifiDataList.size(); i++) {
                selectedIndices.add(i);
            }
        } else {
            // 使用选中状态数组，避免依赖视图
            if (selectedStates != null) {
                for (int i = 0; i < selectedStates.length; i++) {
                    if (selectedStates[i]) {
                        selectedIndices.add(i);
                    }
                }
            }
        }

        if (selectedIndices.isEmpty()) {
            loadingProgressBar.setVisibility(View.GONE);
            Toast.makeText(this, "没有选中的数据", Toast.LENGTH_SHORT).show();
            return;
        }

        // 在后台线程构建JSON数据
        executorService.execute(() -> {
            try {
                JSONArray dataArray = new JSONArray();

                for (int index : selectedIndices) {
                    if (index >= wifiDataList.size()) continue;

                    WifiDataItem item = wifiDataList.get(index);
                    try {
                        // 一个 SSID 可能保存了多个密码，导出时全部带上
                        List<String> passwords = wifiDataManager.getAllWifiData()
                                .get(item.getSsid());
                        if (passwords == null || passwords.isEmpty()) {
                            passwords = new ArrayList<>();
                            passwords.add(item.getPassword());
                        }
                        for (String password : passwords) {
                            JSONObject wifiObj = new JSONObject();
                            wifiObj.put("ssid", item.getSsid());
                            wifiObj.put("password", password == null || password.isEmpty()
                                    ? "空密码" : password);
                            wifiObj.put("timestamp", item.getTimestamp());
                            dataArray.put(wifiObj);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (dataArray.length() == 0) {
                    mainHandler.post(() -> {
                        loadingProgressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "没有有效数据", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 根据格式处理内容
                String content;
                String fileName;
                String mimeType;

                switch (format) {
                    case 0: // JSON加密
                        content = AESUtil.encrypt(dataArray.toString());
                        fileName = "网络工具箱_加密_" + getTimeString() + ".json";
                        mimeType = "application/json";
                        break;
                    case 1: // JSON明文
                        content = dataArray.toString();
                        fileName = "网络工具箱_" + getTimeString() + ".json";
                        mimeType = "application/json";
                        break;
                    case 2: // TXT格式
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < dataArray.length(); i++) {
                            try {
                                JSONObject obj = dataArray.getJSONObject(i);
                                sb.append("SSID: ").append(obj.getString("ssid")).append("\n");
                                sb.append("密码: ").append(obj.getString("password")).append("\n");
                                sb.append("------------------------\n");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        content = sb.toString();
                        fileName = "网络工具箱_" + getTimeString() + ".txt";
                        mimeType = "text/plain";
                        break;
                    default:
                        mainHandler.post(() -> loadingProgressBar.setVisibility(View.GONE));
                        return;
                }

                // 缓存结果
                pendingContent = content;
                pendingFileName = fileName;
                pendingMimeType = mimeType;

                // 打开文件保存对话框
                mainHandler.post(() -> {
                    loadingProgressBar.setVisibility(View.GONE);
                    openSaveFilePicker();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    loadingProgressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "准备数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 打开文件保存选择器
     * 使用 ActivityResultLauncher 替代已弃用的 startActivityForResult
     */
    private void openSaveFilePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(pendingMimeType);
        intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);

        saveFileLauncher.launch(intent);
    }

    /**
     * 异步保存文件
     */
    private void saveFileToUri(Uri uri) {
        loadingProgressBar.setVisibility(View.VISIBLE);

        executorService.execute(() -> {
            try {
                OutputStream outputStream = getContentResolver().openOutputStream(uri);
                if (outputStream != null && pendingContent != null) {
                    outputStream.write(pendingContent.getBytes("UTF-8"));
                    outputStream.close();

                    mainHandler.post(() -> {
                        loadingProgressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    mainHandler.post(() -> {
                        loadingProgressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    loadingProgressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String getTimeString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        return sdf.format(new Date());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
