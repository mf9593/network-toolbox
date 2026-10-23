package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
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

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DNS反查工具Activity
 * 
 * 功能说明：
 * - 根据IP地址反向查询域名
 * - 支持PTR记录查询
 */
public class DnsReverseActivity extends AppCompatActivity {

    private static final String TAG = "DnsReverseActivity";
    
    private MaterialToolbar toolbar;
    private MaterialCardView resultCard;
    private EditText ipEditText;
    private MaterialButton queryButton;
    private TextView resultTextView;

    private ExecutorService executorService;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns_reverse);

        setupStatusBar();
        initViews();
        setupToolbar();
        setupListeners();

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        resultCard = findViewById(R.id.resultCard);
        ipEditText = findViewById(R.id.ipEditText);
        queryButton = findViewById(R.id.queryButton);
        resultTextView = findViewById(R.id.resultTextView);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("DNS反查");
        }
        
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }
    
    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void setupListeners() {
        queryButton.setOnClickListener(v -> reverseDnsQuery());
        applyThemeColors();
    }
    
    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(queryButton);
        ThemeManager.setupResultCard(resultCard, this);
    }

    private void reverseDnsQuery() {
        String ip = ipEditText.getText().toString().trim();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // 验证IP格式
        if (!isValidIpAddress(ip)) {
            Toast.makeText(this, "请输入有效的IP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        resultTextView.setText("正在反向解析IP: " + ip + "...");
        queryButton.setEnabled(false);

        final String targetIp = ip;
        executorService.execute(() -> {
            try {
                String result = performReverseDnsQuery(targetIp);
                mainHandler.post(() -> {
                    resultTextView.setText(result);
                    queryButton.setEnabled(true);
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "DNS反查失败: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    resultTextView.setText("查询失败: " + e.getMessage());
                    queryButton.setEnabled(true);
                });
            }
        });
    }

    private String performReverseDnsQuery(String ip) {
        StringBuilder result = new StringBuilder();
        result.append("=== DNS反查结果 ===\n\n");
        result.append("查询IP: ").append(ip).append("\n\n");

        try {
            // 使用系统DNS进行反向查询
            InetAddress address = InetAddress.getByName(ip);
            String hostname = address.getCanonicalHostName();
            
            result.append("=== 反向解析结果 ===\n");
            result.append("主机名: ").append(hostname).append("\n");
            
            // 检查是否解析成功
            if (hostname.equals(ip)) {
                result.append("\n注意: 该IP没有配置PTR记录，无法反向解析\n");
                result.append("返回的是原始IP地址\n");
            } else {
                result.append("\nPTR记录解析成功\n");
                
                // 尝试解析所有关联IP
                try {
                    InetAddress[] allAddresses = InetAddress.getAllByName(hostname);
                    if (allAddresses.length > 1) {
                        result.append("\n该域名关联的所有IP:\n");
                        for (InetAddress addr : allAddresses) {
                            result.append("- ").append(addr.getHostAddress()).append("\n");
                        }
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
        } catch (Exception e) {
            result.append("反向解析失败: ").append(e.getMessage()).append("\n");
            result.append("\n可能原因:\n");
            result.append("1. IP地址格式不正确\n");
            result.append("2. DNS服务器没有PTR记录\n");
            result.append("3. 网络连接问题\n");
        }

        // 添加常用命令提示
        result.append("\n=== 常用命令 ===\n");
        result.append("nslookup ").append(ip).append("\n");
        result.append("dig -x ").append(ip).append(" +short\n");
        result.append("host ").append(ip).append("\n");

        return result.toString();
    }

    private boolean isValidIpAddress(String ip) {
        if (TextUtils.isEmpty(ip)) return false;
        
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
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
