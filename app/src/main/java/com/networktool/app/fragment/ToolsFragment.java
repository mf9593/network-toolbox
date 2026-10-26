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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import com.networktool.app.activity.tool.CastActivity;
import com.networktool.app.livetv.LiveTvActivity;
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
import com.networktool.app.util.ThemeManager;

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
    /** 常用工具上限 */
    private static final int MAX_FAVORITES = 5;

    private RecyclerView recyclerView;
    private TextInputEditText searchEdit;
    private View emptyView;
    private ToolAdapter adapter;
    private SharedPreferences prefs;
    private GridLayoutManager gridLayoutManager;

    private final List<Category> categories = new ArrayList<>();
    private final List<ToolItem> displayItems = new ArrayList<>();
    /** 常用（收藏）工具标题，顺序即展示顺序，最多 {@link #MAX_FAVORITES} 个 */
    private final List<String> favorites = new ArrayList<>();
    /** 顶部「我的常用」横向小卡片容器 */
    private LinearLayout favoriteSection;
    private LinearLayout favoriteStrip;
    private TextView favoriteTitle;
    private TextView favoriteHint;
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
        seedDefaultFavorites();
        setupSearch();
        rebuildList();
    }

    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recyclerView);
        searchEdit = view.findViewById(R.id.searchEdit);
        emptyView = view.findViewById(R.id.emptyView);
        favoriteSection = view.findViewById(R.id.favoriteSection);
        favoriteStrip = view.findViewById(R.id.favoriteStrip);
        favoriteTitle = view.findViewById(R.id.favoriteTitle);
        favoriteHint = view.findViewById(R.id.favoriteHint);
        applySearchTheme(view);

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

    /** 让搜索框的描边/提示/图标高亮跟随主题色（XML 的 ?attr 可能不随动态主题更新） */
    private void applySearchTheme(View view) {
        try {
            if (!isAdded()) return;
            com.google.android.material.textfield.TextInputLayout layout =
                    view.findViewById(R.id.searchLayout);
            int accent = ThemeManager.getAccessibleThemeColor(requireContext());
            if (layout != null) {
                layout.setBoxStrokeColor(accent);
                layout.setBoxStrokeColorStateList(
                        android.content.res.ColorStateList.valueOf(accent));
                layout.setDefaultHintTextColor(
                        android.content.res.ColorStateList.valueOf(accent));
                layout.setStartIconTintList(
                        android.content.res.ColorStateList.valueOf(accent));
            }
            if (searchEdit != null) {
                searchEdit.setTextColor(ThemeManager.getOnSurfaceColor(requireContext()));
                searchEdit.setHintTextColor(android.content.res.ColorStateList.valueOf(accent));
            }
        } catch (Throwable ignored) {
        }
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
        camera.add(new ToolItem("电视直播", R.drawable.ic_live_tv, "加载自定义直播源文件", this::onLiveTv));
        camera.add(new ToolItem("投屏", R.drawable.ic_cast, "投屏图片/视频/直播源到电视", this::onCast));
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
            // 常用工具在顶部以横向小卡片单独展示，网格里保持完整分类
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

        renderFavoriteStrip(query.isEmpty());

        displayItems.clear();
        displayItems.addAll(output);
        adapter.setItems(output);
        adapter.setFavorites(new LinkedHashSet<>(favorites));
        if (emptyView != null) {
            emptyView.setVisibility(output.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** 首次安装时给「我的常用」预置几个高频工具，避免一片空白 */
    private void seedDefaultFavorites() {
        if (prefs == null || prefs.contains(KEY_FAVORITES)) return;
        String[] defaults = {"Ping", "端口扫描", "IP 扫描", "DNS 优选", "局域网聊天"};
        for (String title : defaults) {
            if (findTool(title) != null && !favorites.contains(title)) {
                favorites.add(title);
            }
        }
        saveSet(KEY_FAVORITES, new LinkedHashSet<>(favorites));
    }

    /** 渲染顶部「我的常用」横向小卡片；搜索时隐藏，避免占用结果区域 */
    private void renderFavoriteStrip(boolean show) {
        if (favoriteSection == null || favoriteStrip == null) return;
        favoriteStrip.removeAllViews();
        if (!show || favorites.isEmpty()) {
            favoriteSection.setVisibility(View.GONE);
            return;
        }
        favoriteSection.setVisibility(View.VISIBLE);
        if (favoriteTitle != null) {
            favoriteTitle.setText("我的常用 · " + favorites.size() + "/" + MAX_FAVORITES);
        }
        if (favoriteHint != null) {
            favoriteHint.setText(favorites.size() >= MAX_FAVORITES
                    ? "长按可调整" : "长按可调整 · 还能加 "
                            + (MAX_FAVORITES - favorites.size()) + " 个");
        }

        for (String title : favorites) {
            ToolItem tool = findTool(title);
            if (tool == null) continue;
            favoriteStrip.addView(buildFavoriteCard(tool));
        }
    }

    /** 单张常用小卡片：图标在上、名称在下，点击进入工具，长按弹出调整菜单 */
    private View buildFavoriteCard(ToolItem tool) {
        Context context = requireContext();
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(context);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                dpToPx(78), LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMarginEnd(dpToPx(8));
        card.setLayoutParams(cardParams);
        card.setRadius(dpToPx(14));
        card.setCardElevation(0f);
        card.setStrokeWidth(dpToPx(1));
        // 描边用低透明度前景色，浅色/深色主题都协调
        card.setStrokeColor((ThemeManager.getOnSurfaceVariantColor(context) & 0x00FFFFFF) | 0x33000000);
        card.setCardBackgroundColor(ThemeManager.getSurfaceColor(context));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10));

        ImageView icon = new ImageView(context);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(tool.getIconResId());
        icon.setColorFilter(ThemeManager.getThemeColor(), android.graphics.PorterDuff.Mode.SRC_IN);
        content.addView(icon);

        TextView name = new TextView(context);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dpToPx(6);
        name.setLayoutParams(nameParams);
        name.setText(tool.getTitle());
        name.setTextSize(11f);
        name.setGravity(android.view.Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(ThemeManager.getOnSurfaceVariantColor(context));
        content.addView(name);

        card.addView(content);
        card.setOnClickListener(v -> onToolClick(tool));
        card.setOnLongClickListener(v -> {
            showFavoriteActions(tool.getTitle());
            return true;
        });
        return card;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** 常用区条目的长按菜单：上移 / 下移 / 移出常用 */
    private void showFavoriteActions(String title) {
        if (title == null) return;
        int index = favorites.indexOf(title);
        if (index < 0) return;
        List<String> actions = new ArrayList<>();
        if (index > 0) actions.add("上移");
        if (index < favorites.size() - 1) actions.add("下移");
        actions.add("移出常用");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    int current = favorites.indexOf(title);
                    if ("上移".equals(action) && current > 0) {
                        java.util.Collections.swap(favorites, current, current - 1);
                    } else if ("下移".equals(action) && current >= 0
                            && current < favorites.size() - 1) {
                        java.util.Collections.swap(favorites, current, current + 1);
                    } else if ("移出常用".equals(action)) {
                        favorites.remove(title);
                    }
                    saveSet(KEY_FAVORITES, new LinkedHashSet<>(favorites));
                    rebuildList();
                })
                .show();
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
        String title = tool.getTitle();
        if (favorites.contains(title)) {
            favorites.remove(title);
            showToast("已移出常用：" + title);
        } else {
            if (favorites.size() >= MAX_FAVORITES) {
                showToast("常用最多 " + MAX_FAVORITES + " 个，先在「我的常用」里移出一个");
                return;
            }
            favorites.add(title);
            showToast("已加入常用：" + title);
        }
        saveSet(KEY_FAVORITES, new LinkedHashSet<>(favorites));
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

    private void onLiveTv(ToolItem tool) {
        open(LiveTvActivity.class);
    }

    private void onCast(ToolItem tool) {
        open(CastActivity.class);
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
