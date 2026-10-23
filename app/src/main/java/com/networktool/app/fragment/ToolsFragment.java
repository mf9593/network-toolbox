package com.networktool.app.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.activity.tool.AppManagerActivity;
import com.networktool.app.activity.tool.DnsActivity;
import com.networktool.app.activity.tool.DnsReverseActivity;
import com.networktool.app.activity.tool.DomainWhoisActivity;
import com.networktool.app.activity.tool.HttpDebugActivity;
import com.networktool.app.activity.tool.IconConverterActivity;
import com.networktool.app.activity.tool.IpCalculatorActivity;
import com.networktool.app.activity.tool.IpScanActivity;
import com.networktool.app.activity.tool.LanChatActivity;
import com.networktool.app.activity.tool.MacActivity;
import com.networktool.app.activity.tool.OnvifActivity;
import com.networktool.app.activity.tool.PingActivity;
import com.networktool.app.activity.tool.PortScanActivity;
import com.networktool.app.activity.tool.RtspPlayerActivity;
import com.networktool.app.activity.tool.SadpActivity;
import com.networktool.app.activity.tool.SavedNetworkActivity;
import com.networktool.app.activity.tool.SpeedTestActivity;
import com.networktool.app.activity.tool.TracerouteActivity;
import com.networktool.app.activity.tool.WebDebugActivity;
import com.networktool.app.activity.tool.WebViewActivity;
import com.networktool.app.activity.tool.WifiDatabaseActivity;
import com.networktool.app.activity.tool.WifiExportActivity;
import com.networktool.app.activity.tool.WifiImportActivity;
import com.networktool.app.adapter.ToolAdapter;
import com.networktool.app.model.ToolItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 工具箱：搜索、分组、最近使用和收藏。
 */
public class ToolsFragment extends Fragment
        implements ToolAdapter.OnToolClickListener, ToolAdapter.OnToolLongClickListener {

    private static final String PREFS_NAME = "tools_prefs";
    private static final String KEY_RECENT = "recent_tools";
    private static final String KEY_FAVORITES = "favorite_tools";

    private RecyclerView recyclerView;
    private TextInputEditText searchEdit;
    private View emptyView;
    private ToolAdapter adapter;
    private SharedPreferences prefs;
    private GridLayoutManager gridLayoutManager;

    private final List<Category> categories = new ArrayList<>();
    private final List<ToolItem> displayItems = new ArrayList<>();
    private final LinkedHashSet<String> favorites = new LinkedHashSet<>();
    private final LinkedHashSet<String> recent = new LinkedHashSet<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tools, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadStoredSets();
        initViews(view);
        buildCatalog();
        setupSearch();
        rebuildList();
    }

    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recyclerView);
        searchEdit = view.findViewById(R.id.searchEdit);
        emptyView = view.findViewById(R.id.emptyView);

        gridLayoutManager = new GridLayoutManager(requireContext(), toolGridSpanCount());
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= displayItems.size()) {
                    return gridLayoutManager.getSpanCount();
                }
                return displayItems.get(position).isHeader()
                        ? gridLayoutManager.getSpanCount() : 1;
            }
        });
        recyclerView.setLayoutManager(gridLayoutManager);

        adapter = new ToolAdapter(new ArrayList<>(), this);
        adapter.setOnToolLongClickListener(this);
        recyclerView.setAdapter(adapter);
    }

    private int toolGridSpanCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 900) return 4;
        if (widthDp >= 600) return 3;
        return 2;
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (gridLayoutManager != null) {
            int spanCount = toolGridSpanCount();
            if (spanCount != gridLayoutManager.getSpanCount()) {
                gridLayoutManager.setSpanCount(spanCount);
                adapter.notifyDataSetChanged();
            }
        }
    }

    private void setupSearch() {
        if (searchEdit == null) return;
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                rebuildList();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void buildCatalog() {
        categories.clear();

        List<ToolItem> diagnose = new ArrayList<>();
        diagnose.add(new ToolItem("Ping", R.drawable.ic_ping, "测试主机连通性与延迟", this::onPing));
        diagnose.add(new ToolItem("端口扫描", R.drawable.ic_port_scan, "检测目标主机的开放端口", this::onPortScan));
        diagnose.add(new ToolItem("IP 扫描", R.drawable.ic_ip_scan, "发现同一网段内的主机", this::onIpScan));
        diagnose.add(new ToolItem("路由追踪", R.drawable.ic_traceroute, "查看数据包经过的网络节点", this::onTraceroute));
        diagnose.add(new ToolItem("网络测速", R.drawable.ic_speed, "网页测速与网络延迟", this::onSpeedTest));
        categories.add(new Category("网络诊断", diagnose));

        List<ToolItem> dns = new ArrayList<>();
        dns.add(new ToolItem("DNS 优选", R.drawable.ic_dns, "对比公共 DNS 的响应延迟", this::onDnsOptimize));
        dns.add(new ToolItem("DNS 反查", R.drawable.ic_dns_reverse, "根据 IP 查询关联域名", this::onDnsReverse));
        dns.add(new ToolItem("域名查询", R.drawable.ic_scan_search, "查询域名 Whois 与注册信息", this::onDomainWhois));
        dns.add(new ToolItem("IP 计算器", R.drawable.ic_ipcalculator, "子网掩码与网段计算", this::onIpCalculator));
        categories.add(new Category("域名与 DNS", dns));

        List<ToolItem> wifi = new ArrayList<>();
        wifi.add(new ToolItem("导入 WiFi 数据", R.drawable.ic_import, "从文件或文本导入网络数据", this::onWifiImport));
        wifi.add(new ToolItem("导出 WiFi 数据", R.drawable.ic_export, "导出已保存的网络数据", this::onWifiExport));
        wifi.add(new ToolItem("数据库查询", R.drawable.ic_database, "搜索本地 WiFi 密码库", this::onDatabaseQuery));
        wifi.add(new ToolItem("已保存网络", R.drawable.ic_saved_network, "查看系统已保存的网络", this::onSavedNetwork));
        wifi.add(new ToolItem("局域网聊天", R.drawable.ic_chat, "同一局域网内收发消息", this::onLanChat));
        categories.add(new Category("WiFi 与局域网", wifi));

        List<ToolItem> camera = new ArrayList<>();
        camera.add(new ToolItem("ONVIF 调试", R.drawable.ic_webcam, "发现并调试 ONVIF 摄像头", this::onOnvifConsole));
        camera.add(new ToolItem("SADP 调试", R.drawable.ic_cctv, "海康设备发现与配置", this::onSadpDebug));
        camera.add(new ToolItem("RTSP 预览", R.drawable.ic_rtsp, "播放 RTSP 视频流", this::onRtspPlayer));
        categories.add(new Category("摄像头与流媒体", camera));

        List<ToolItem> web = new ArrayList<>();
        web.add(new ToolItem("HTTP 调试", R.drawable.ic_http_debug, "发送 GET/POST 接口请求", this::onHttpDebug));
        web.add(new ToolItem("网页调试", R.drawable.ic_web_debug, "抓取并解析网页内容", this::onWebDebug));
        web.add(new ToolItem("内置浏览器", R.drawable.ic_browser, "轻量网页浏览器", this::onBrowser));
        categories.add(new Category("Web 与 HTTP", web));

        List<ToolItem> system = new ArrayList<>();
        system.add(new ToolItem("应用管理", R.drawable.ic_app_manager, "查看与导出已安装应用", this::onAppManager));
        system.add(new ToolItem("MAC 地址查询", R.drawable.ic_mac, "查询 MAC 厂商与地址信息", this::onMacQuery));
        system.add(new ToolItem("图标转换", R.drawable.ic_swap, "SVG/PNG 图标格式转换", this::onIconConverter));
        categories.add(new Category("系统与实验室", system));
    }

    private void rebuildList() {
        if (adapter == null) return;
        String query = searchEdit == null ? "" : searchEdit.getText().toString().trim().toLowerCase(Locale.CHINA);
        List<ToolItem> output = new ArrayList<>();

        if (query.isEmpty()) {
            // 分类中始终展示完整工具列表；收藏只通过卡片右上角星标表示，
            // 不再额外插入“最近使用/我的收藏”区，避免工具重复或点击后从分类中消失。
            for (Category category : categories) {
                if (category.tools.isEmpty()) continue;
                output.add(ToolItem.header(category.title));
                output.addAll(category.tools);
            }
        } else {
            for (Category category : categories) {
                List<ToolItem> matched = new ArrayList<>();
                for (ToolItem tool : category.tools) {
                    if (matches(tool, query)) {
                        matched.add(tool);
                    }
                }
                if (!matched.isEmpty()) {
                    output.add(ToolItem.header(category.title));
                    output.addAll(matched);
                }
            }
        }

        displayItems.clear();
        displayItems.addAll(output);
        adapter.setItems(output);
        adapter.setFavorites(favorites);
        if (emptyView != null) {
            emptyView.setVisibility(output.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private boolean matches(ToolItem tool, String query) {
        String title = tool.getTitle() == null ? "" : tool.getTitle().toLowerCase(Locale.CHINA);
        String desc = tool.getDescription() == null ? "" : tool.getDescription().toLowerCase(Locale.CHINA);
        return title.contains(query) || desc.contains(query);
    }

    private ToolItem findTool(String title) {
        if (title == null) return null;
        for (Category category : categories) {
            for (ToolItem tool : category.tools) {
                if (title.equals(tool.getTitle())) {
                    return tool;
                }
            }
        }
        return null;
    }

    @Override
    public void onToolClick(ToolItem tool) {
        if (tool == null) return;
        recordRecent(tool.getTitle());
        if (tool.getClickListener() != null) {
            tool.getClickListener().onToolClick(tool);
        }
    }

    @Override
    public void onToolLongClick(ToolItem tool) {
        if (tool == null) return;
        if (favorites.contains(tool.getTitle())) {
            favorites.remove(tool.getTitle());
            showToast("已取消收藏：" + tool.getTitle());
        } else {
            favorites.add(tool.getTitle());
            showToast("已收藏：" + tool.getTitle());
        }
        saveSet(KEY_FAVORITES, favorites);
        rebuildList();
    }

    private void recordRecent(String title) {
        if (title == null) return;
        List<String> ordered = new ArrayList<>(recent);
        ordered.remove(title);
        ordered.add(0, title);
        while (ordered.size() > 5) {
            ordered.remove(ordered.size() - 1);
        }
        recent.clear();
        recent.addAll(ordered);
        saveSet(KEY_RECENT, recent);
    }

    private void loadStoredSets() {
        favorites.clear();
        favorites.addAll(loadSet(KEY_FAVORITES));
        recent.clear();
        recent.addAll(loadSet(KEY_RECENT));
    }

    private List<String> loadSet(String key) {
        List<String> result = new ArrayList<>();
        String value = prefs == null ? "" : prefs.getString(key, "");
        if (value == null || value.isEmpty()) return result;
        for (String line : value.split("\n")) {
            if (!line.trim().isEmpty()) {
                result.add(line.trim());
            }
        }
        return result;
    }

    private void saveSet(String key, LinkedHashSet<String> set) {
        if (prefs == null) return;
        StringBuilder builder = new StringBuilder();
        for (String value : set) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(value);
        }
        prefs.edit().putString(key, builder.toString()).apply();
    }

    private void showToast(String message) {
        try {
            if (isAdded()) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            rebuildList();
        }
    }

    private void open(Class<?> target) {
        try {
            startActivity(new Intent(requireContext(), target));
        } catch (Exception e) {
            showToast("无法打开该工具");
        }
    }

    private void onWifiImport(ToolItem tool) {
        open(WifiImportActivity.class);
    }

    private void onWifiExport(ToolItem tool) {
        open(WifiExportActivity.class);
    }

    private void onDatabaseQuery(ToolItem tool) {
        open(WifiDatabaseActivity.class);
    }

    private void onSavedNetwork(ToolItem tool) {
        open(SavedNetworkActivity.class);
    }

    private void onSpeedTest(ToolItem tool) {
        open(SpeedTestActivity.class);
    }

    private void onPing(ToolItem tool) {
        open(PingActivity.class);
    }

    private void onPortScan(ToolItem tool) {
        open(PortScanActivity.class);
    }

    private void onIpScan(ToolItem tool) {
        open(IpScanActivity.class);
    }

    private void onMacQuery(ToolItem tool) {
        open(MacActivity.class);
    }

    private void onDnsOptimize(ToolItem tool) {
        open(DnsActivity.class);
    }

    private void onHttpDebug(ToolItem tool) {
        open(HttpDebugActivity.class);
    }

    private void onWebDebug(ToolItem tool) {
        open(WebDebugActivity.class);
    }

    private void onBrowser(ToolItem tool) {
        open(WebViewActivity.class);
    }

    private void onRtspPlayer(ToolItem tool) {
        open(RtspPlayerActivity.class);
    }

    private void onAppManager(ToolItem tool) {
        open(AppManagerActivity.class);
    }

    private void onOnvifConsole(ToolItem tool) {
        open(OnvifActivity.class);
    }

    private void onSadpDebug(ToolItem tool) {
        open(SadpActivity.class);
    }

    private void onLanChat(ToolItem tool) {
        open(LanChatActivity.class);
    }

    private void onTraceroute(ToolItem tool) {
        open(TracerouteActivity.class);
    }

    private void onDomainWhois(ToolItem tool) {
        open(DomainWhoisActivity.class);
    }

    private void onDnsReverse(ToolItem tool) {
        open(DnsReverseActivity.class);
    }

    private void onIpCalculator(ToolItem tool) {
        open(IpCalculatorActivity.class);
    }

    private void onIconConverter(ToolItem tool) {
        open(IconConverterActivity.class);
    }

    private static class Category {
        final String title;
        final List<ToolItem> tools;

        Category(String title, List<ToolItem> tools) {
            this.title = title;
            this.tools = tools;
        }
    }
}
