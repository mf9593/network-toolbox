package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.google.android.material.button.MaterialButton;
import com.networktool.app.R;
import com.networktool.app.adapter.DnsAdapter;
import com.networktool.app.model.DnsItem;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DNS查询工具Activity
 * 
 * 功能说明：
 * - 查询域名在不同DNS服务器下的解析结果
 * - 支持多个常用DNS服务器
 * - 显示DNS查询延迟
 */
public class DnsActivity extends AppCompatActivity {

    private static final String TAG = "DnsActivity";
    
    private MaterialToolbar toolbar;
    private EditText urlEditText;
    private MaterialButton searchButton;
    private ListView listView;

    private DnsAdapter adapter;
    private List<DnsItem> dnsItems;
    private ExecutorService executorService;
    private Handler mainHandler;

    private static final String[][] DNS_SERVERS = {
            {"202.96.128.86", "广东电信DNS"},
            {"202.96.128.166", "广东电信DNS"},
            {"202.96.134.33", "广东电信DNS"},
            {"202.96.134.133", "广东电信DNS"},
            {"120.80.80.80", "广东联通DNS"},
            {"221.5.88.88", "广东联通DNS"},
            {"221.179.38.7", "广东移动DNS"},
            {"120.196.165.7", "广东移动DNS"},
            {"101.198.198.198", "360 DNS"},
            {"101.198.199.200", "360 DNS"},
            {"223.5.5.5", "阿里 DNS"},
            {"223.6.6.6", "阿里 DNS"},
            {"119.29.29.29", "腾讯 DNS"},
            {"182.254.116.116", "腾讯 DNS"},
            {"180.76.76.76", "百度 DNS"},
            {"114.114.114.114", "114 DNS"},
            {"114.114.115.115", "114 DNS"},
            {"8.8.8.8", "谷歌 DNS"},
            {"8.8.4.4", "谷歌 DNS"},
            {"1.2.4.8", "CNNIC sDNS"},
            {"210.2.4.8", "CNNIC sDNS"},
            {"101.6.6.6", "TUNA DNS666"},
            {"1.1.1.1", "Cloudflare"},
            {"1.0.0.1", "Cloudflare"},
            {"208.67.222.222", "OpenDNS"},
            {"208.67.220.220", "OpenDNS"},
            {"9.9.9.9", "IBM Quad9"},
            {"149.112.112.112", "IBM Quad9"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns);

        // 设置沉浸式状态栏
        setupStatusBar();

        initViews();
        setupToolbar();
        setupListeners();

        dnsItems = new ArrayList<>();
        for (String[] dns : DNS_SERVERS) {
            dnsItems.add(new DnsItem(dns[0], dns[1], "待测试"));
        }

        adapter = new DnsAdapter(this, dnsItems);
        listView.setAdapter(adapter);

        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        urlEditText = findViewById(R.id.urlEditText);
        searchButton = findViewById(R.id.searchButton);
        listView = findViewById(R.id.listView);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("DNS优选");
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
        searchButton.setOnClickListener(v -> testDnsLatency());
        
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
    }

    private void testDnsLatency() {
        String url = urlEditText.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            url = "www.baidu.com";
        }

        // Extract domain from URL
        String domain = extractDomain(url);
        if (domain == null) {
            Toast.makeText(this, "请输入有效的域名", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在获取DNS延迟", Toast.LENGTH_SHORT).show();

        for (int i = 0; i < dnsItems.size(); i++) {
            final int index = i;
            final String dnsServer = dnsItems.get(i).getIp();
            final String targetDomain = domain;

            executorService.execute(() -> {
                long latency = queryDnsLatency(targetDomain, dnsServer);
                final String result = latency >= 0 ? latency + " ms" : "超时";

                mainHandler.post(() -> {
                    dnsItems.get(index).setDelay(result);
                    adapter.notifyDataSetChanged();
                });
            });
        }
    }

    private String extractDomain(String url) {
        Pattern pattern = Pattern.compile("[0-9a-z]+\\.[0-9a-z]+\\.?[0-9a-z]*\\.?[0-9a-z]*");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group();
        }
        return url;
    }

    private long queryDnsLatency(String domain, String dnsServer) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(2000);

            byte[] query = buildDnsQuery(domain);
            DatagramPacket packet = new DatagramPacket(query, query.length,
                    InetAddress.getByName(dnsServer), 53);

            long startTime = System.currentTimeMillis();
            socket.send(packet);

            byte[] response = new byte[512];
            DatagramPacket responsePacket = new DatagramPacket(response, response.length);
            socket.receive(responsePacket);
            long endTime = System.currentTimeMillis();

            socket.close();

            return endTime - startTime;
        } catch (Exception e) {
            return -1;
        }
    }

    private byte[] buildDnsQuery(String domain) {
        byte[] query = new byte[512];
        int pos = 0;

        // Transaction ID
        query[pos++] = (byte) (Math.random() * 256);
        query[pos++] = (byte) (Math.random() * 256);

        // Flags
        query[pos++] = 0x01;
        query[pos++] = 0x00;

        // Questions
        query[pos++] = 0x00;
        query[pos++] = 0x01;

        // Answer RRs
        query[pos++] = 0x00;
        query[pos++] = 0x00;

        // Authority RRs
        query[pos++] = 0x00;
        query[pos++] = 0x00;

        // Additional RRs
        query[pos++] = 0x00;
        query[pos++] = 0x00;

        // Domain name
        String[] labels = domain.split("\\.");
        for (String label : labels) {
            query[pos++] = (byte) label.length();
            for (char c : label.toCharArray()) {
                query[pos++] = (byte) c;
            }
        }
        query[pos++] = 0x00;

        // QType A
        query[pos++] = 0x00;
        query[pos++] = 0x01;

        // QClass IN
        query[pos++] = 0x00;
        query[pos++] = 0x01;

        byte[] result = new byte[pos];
        System.arraycopy(query, 0, result, 0, pos);
        return result;
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
