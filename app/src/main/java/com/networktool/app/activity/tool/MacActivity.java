package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MAC地址厂商查询Activity
 * 
 * 功能说明：
 * - 输入MAC地址查询设备厂商信息
 * - 通过在线API获取MAC地址对应的厂商信息
 */
public class MacActivity extends AppCompatActivity {

    private static final String TAG = "MacActivity";
    
    private MaterialToolbar toolbar;
    private EditText macEditText;
    private MaterialButton searchButton;
    private TextView cardTitle;
    private TextView cardMessage;
    private MaterialCardView resultCard;

    private ExecutorService executorService;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mac);

        // 设置沉浸式状态栏
        setupStatusBar();

        initViews();
        setupToolbar();
        setupListeners();

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 从设备扫描/WiFi 详情页自动填入 MAC，并可直接查询
        String presetMac = getIntent().getStringExtra("mac");
        if (presetMac == null || presetMac.trim().isEmpty()) {
            presetMac = getIntent().getStringExtra("bssid");
        }
        if (presetMac != null && !presetMac.trim().isEmpty()) {
            macEditText.setText(presetMac.trim());
            if (getIntent().getBooleanExtra("auto_query", true)) {
                macEditText.post(this::queryMac);
            }
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        macEditText = findViewById(R.id.macEditText);
        searchButton = findViewById(R.id.searchButton);
        cardTitle = findViewById(R.id.cardTitle);
        cardMessage = findViewById(R.id.cardMessage);
        resultCard = findViewById(R.id.resultCard);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("MAC地址查询");
        }
        
        // 应用主题色到工具栏
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
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

    private void setupListeners() {
        searchButton.setOnClickListener(v -> queryMac());
        
        // 应用主题色
        applyThemeColors();
    }
    
    /**
     * 应用主题色到控件
     */
    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        
        // 搜索按钮使用主题色
        ThemeManager.applyPrimaryButton(searchButton);
        
        // 卡片标题使用可读主题色
        cardTitle.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        cardMessage.setTextColor(ThemeManager.getOnSurfaceColor(this));
        ThemeManager.setupResultCard(resultCard, this);
    }

    private void queryMac() {
        String mac = macEditText.getText().toString().trim();
        if (TextUtils.isEmpty(mac)) {
            Toast.makeText(this, "请输入MAC地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // Clean MAC address
        mac = mac.replace(":", "").replace("-", "").replace(".", "");
        if (mac.length() != 12) {
            Toast.makeText(this, "MAC地址格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "开始查询 " + mac, Toast.LENGTH_SHORT).show();

        final String queryMac = mac;
        executorService.execute(() -> {
            try {
                URL url = new URL("https://api.maclookup.app/v2/macs/" + queryMac);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(response.toString());
                    boolean success = json.optBoolean("success", false);
                    boolean found = json.optBoolean("found", false);

                    if (success && found) {
                        StringBuilder message = new StringBuilder();
                        message.append("MAC地址前缀：").append(json.optString("macPrefix", "N/A")).append("\n");
                        message.append("MAC地址厂商：").append(json.optString("company", "N/A")).append("\n");
                        message.append("厂商地址：").append(json.optString("address", "N/A")).append("\n");
                        message.append("国家：").append(json.optString("country", "N/A")).append("\n");
                        message.append("blockStart：").append(json.optString("blockStart", "N/A")).append("\n");
                        message.append("blockEnd：").append(json.optString("blockEnd", "N/A")).append("\n");
                        message.append("blockSize：").append(json.optString("blockSize", "N/A")).append("\n");
                        message.append("blockType：").append(json.optString("blockType", "N/A")).append("\n");
                        message.append("更新时间：").append(json.optString("updated", "N/A"));

                        mainHandler.post(() -> {
                            cardTitle.setText("查询结果");
                            cardMessage.setText(message.toString());
                            resultCard.setVisibility(View.VISIBLE);
                            Toast.makeText(this, "查询成功", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        mainHandler.post(() -> {
                            cardTitle.setText("查询结果");
                            cardMessage.setText("未找到该MAC地址的信息");
                            resultCard.setVisibility(View.VISIBLE);
                        });
                    }
                } else {
                    mainHandler.post(() -> Toast.makeText(this, "API连接失败", Toast.LENGTH_SHORT).show());
                }

                connection.disconnect();
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "MAC地址查询失败: " + e.getMessage(), e);
                mainHandler.post(() -> Toast.makeText(this, "查询失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
