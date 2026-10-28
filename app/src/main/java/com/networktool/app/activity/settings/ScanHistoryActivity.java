package com.networktool.app.activity.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.PorterDuff;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.widget.ImageView;

/**
 * 扫一扫历史记录Activity
 * 从JSON文件读取扫描历史
 */
public class ScanHistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private MaterialToolbar toolbar;
    private ScanHistoryAdapter adapter;
    private List<ScanHistoryItem> historyList = new ArrayList<>();
    private File scanHistoryFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_history);

        // 初始化扫描历史文件路径
        initScanHistoryFile();

        // 设置工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("扫一扫历史记录");
        }

        // 应用主题色到工具栏
        applyToolbarTheme();

        setupStatusBar();

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ScanHistoryAdapter(historyList);
        recyclerView.setAdapter(adapter);

        // 加载历史数据
        loadScanHistory();
    }

    /**
     * 初始化扫描历史JSON文件路径
     */
    private void initScanHistoryFile() {
        File filesDir = getExternalFilesDir(null);
        if (filesDir != null) {
            scanHistoryFile = new File(filesDir, "扫一扫.json");
        } else {
            scanHistoryFile = new File(getFilesDir(), "扫一扫.json");
        }
    }

    /**
     * 设置沉浸式状态栏
     */
    private void setupStatusBar() {
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    /**
     * 从JSON文件加载扫描历史
     */
    private void loadScanHistory() {
        historyList.clear();
        
        if (scanHistoryFile == null || !scanHistoryFile.exists()) {
            adapter.notifyDataSetChanged();
            return;
        }

        try {
            StringBuilder jsonStr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(scanHistoryFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonStr.append(line);
                }
            }

            if (jsonStr.length() > 0) {
                JSONArray scanData = new JSONArray(jsonStr.toString());
                for (int i = 0; i < scanData.length(); i++) {
                    JSONObject item = scanData.getJSONObject(i);
                    String title = item.optString("title", "");
                    JSONObject content = item.optJSONObject("content");
                    String timestamp = "";
                    if (content != null) {
                        timestamp = content.optString("text", "");
                    }
                    historyList.add(new ScanHistoryItem(title, timestamp));
                }
            }
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }

        adapter.notifyDataSetChanged();
    }

    /**
     * 应用主题色到工具栏
     */
    private void applyToolbarTheme() {
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 扫描历史记录数据类
     */
    static class ScanHistoryItem {
        String title;
        String timestamp;

        ScanHistoryItem(String title, String timestamp) {
            this.title = title;
            this.timestamp = timestamp;
        }
    }

    /**
     * 扫描历史适配器
     */
    class ScanHistoryAdapter extends RecyclerView.Adapter<ScanHistoryAdapter.ViewHolder> {

        private List<ScanHistoryItem> items;

        ScanHistoryAdapter(List<ScanHistoryItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ScanHistoryItem item = items.get(position);
            holder.timestampText.setText(item.timestamp);
            holder.titleText.setText(item.title);

            int themeColor = ThemeManager.getThemeColor();
            int surfaceColor = ThemeManager.getSurfaceColor(holder.itemView.getContext());
            holder.cardView.setCardBackgroundColor(surfaceColor);
            holder.copyButton.setIconTintResource(android.R.color.transparent);
            if (holder.copyButton.getIcon() != null) {
                holder.copyButton.getIcon().setColorFilter(themeColor, PorterDuff.Mode.SRC_IN);
            }

            // 点击显示弹窗
            holder.itemView.setOnClickListener(v -> {
                showScanResultDialog(item.title);
            });

            // 长按复制
            holder.itemView.setOnLongClickListener(v -> {
                copyToClipboard(item.title);
                Toast.makeText(ScanHistoryActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                return true;
            });

            // 复制按钮点击事件
            holder.copyButton.setOnClickListener(v -> {
                copyToClipboard(item.title);
                Toast.makeText(ScanHistoryActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView cardView;
            TextView timestampText;
            TextView titleText;
            MaterialButton copyButton;

            ViewHolder(View itemView) {
                super(itemView);
                cardView = itemView.findViewById(R.id.cardView);
                timestampText = itemView.findViewById(R.id.timestampText);
                titleText = itemView.findViewById(R.id.titleText);
                copyButton = itemView.findViewById(R.id.copyButton);
            }
        }
    }

    /**
     * 显示扫描结果弹窗
     */
    private void showScanResultDialog(String result) {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottomsheet_scan_result, null);

        TextView resultText = sheetView.findViewById(R.id.resultText);
        MaterialButton copyButton = sheetView.findViewById(R.id.copyButton);
        MaterialButton closeButton = sheetView.findViewById(R.id.closeButton);

        // 内容可能带多行换行，整段显示 + 按主题给按钮上色
        com.networktool.app.util.ScanResultSheet.bind(sheetView, result);

        copyButton.setOnClickListener(v -> {
            copyToClipboard(result);
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            bottomSheet.dismiss();
        });

        closeButton.setOnClickListener(v -> bottomSheet.dismiss());

        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Scan Result", text);
        clipboard.setPrimaryClip(clip);
    }
}
