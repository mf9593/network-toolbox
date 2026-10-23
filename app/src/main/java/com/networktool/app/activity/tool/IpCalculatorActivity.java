package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
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
import com.networktool.app.util.ThemeManager;

/**
 * IP计算器工具Activity
 * 
 * 功能说明：
 * - 计算网络地址、广播地址、可用主机范围
 * - 支持CIDR前缀长度和子网掩码两种输入方式
 * - 计算可用IP数量
 */
public class IpCalculatorActivity extends AppCompatActivity {

    private static final String TAG = "IpCalculatorActivity";
    
    private MaterialToolbar toolbar;
    private MaterialCardView resultCard;
    private EditText ipEditText;
    private EditText subnetEditText;
    private MaterialButton calculateButton;
    private TextView resultTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ip_calculator);

        setupStatusBar();
        initViews();
        setupToolbar();
        setupListeners();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        resultCard = findViewById(R.id.resultCard);
        ipEditText = findViewById(R.id.ipEditText);
        subnetEditText = findViewById(R.id.subnetEditText);
        calculateButton = findViewById(R.id.calculateButton);
        resultTextView = findViewById(R.id.resultTextView);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("IP计算器");
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
        calculateButton.setOnClickListener(v -> calculateIpInfo());
        applyThemeColors();
    }
    
    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(calculateButton);
        ThemeManager.setupResultCard(resultCard, this);
    }

    private void calculateIpInfo() {
        String ipStr = ipEditText.getText().toString().trim();
        String subnetStr = subnetEditText.getText().toString().trim();

        if (TextUtils.isEmpty(ipStr)) {
            Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(subnetStr)) {
            Toast.makeText(this, "请输入子网掩码", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 解析IP地址
            long ip = ipToLong(ipStr);
            if (ip == -1) {
                Toast.makeText(this, "请输入有效的IP地址", Toast.LENGTH_SHORT).show();
                return;
            }

            // 解析子网掩码
            long mask;
            int prefixLength;
            
            if (subnetStr.contains(".")) {
                // 子网掩码格式
                mask = ipToLong(subnetStr);
                if (mask == -1) {
                    Toast.makeText(this, "请输入有效的子网掩码", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefixLength = maskToPrefixLength(mask);
            } else {
                // CIDR前缀长度格式
                prefixLength = Integer.parseInt(subnetStr);
                if (prefixLength < 0 || prefixLength > 32) {
                    Toast.makeText(this, "前缀长度必须在0-32之间", Toast.LENGTH_SHORT).show();
                    return;
                }
                mask = prefixLengthToMask(prefixLength);
            }

            // 计算网络信息
            long networkAddr = ip & mask;
            long broadcastAddr = networkAddr | (~mask);
            long firstHost = networkAddr + 1;
            long lastHost = broadcastAddr - 1;
            long hostCount = (long) Math.pow(2, 32 - prefixLength) - 2;

            // 生成结果
            StringBuilder result = new StringBuilder();
            result.append("=== IP计算结果 ===\n\n");
            result.append("输入IP: ").append(ipStr).append("\n");
            result.append("子网掩码: ").append(longToIp(mask)).append("\n");
            result.append("前缀长度: /").append(prefixLength).append("\n\n");
            
            result.append("=== 网络信息 ===\n");
            result.append("网络地址: ").append(longToIp(networkAddr)).append("\n");
            result.append("广播地址: ").append(longToIp(broadcastAddr)).append("\n");
            result.append("可用主机范围: ").append(longToIp(firstHost)).append(" - ").append(longToIp(lastHost)).append("\n");
            result.append("可用主机数量: ").append(hostCount).append("\n\n");
            
            result.append("=== 二进制表示 ===\n");
            result.append("IP地址二进制:\n").append(ipToBinary(ip)).append("\n\n");
            result.append("子网掩码二进制:\n").append(ipToBinary(mask)).append("\n\n");
            result.append("网络地址二进制:\n").append(ipToBinary(networkAddr)).append("\n");

            resultTextView.setText(result.toString());

        } catch (NumberFormatException e) {
            Toast.makeText(this, "输入格式不正确", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "计算出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private long ipToLong(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return -1;
            
            long result = 0;
            for (int i = 0; i < 4; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part < 0 || part > 255) return -1;
                result = (result << 8) | part;
            }
            return result;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." +
               ((ip >> 16) & 0xFF) + "." +
               ((ip >> 8) & 0xFF) + "." +
               (ip & 0xFF);
    }

    private int maskToPrefixLength(long mask) {
        int prefix = 0;
        long bit = 1L << 31;
        while ((mask & bit) != 0) {
            prefix++;
            bit >>= 1;
        }
        return prefix;
    }

    private long prefixLengthToMask(int prefixLength) {
        if (prefixLength == 0) return 0;
        return -1L << (32 - prefixLength);
    }

    private String ipToBinary(long ip) {
        StringBuilder binary = new StringBuilder();
        for (int i = 31; i >= 0; i--) {
            binary.append((ip >> i) & 1);
            if (i % 8 == 0 && i > 0) {
                binary.append(".");
            }
        }
        return binary.toString();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
