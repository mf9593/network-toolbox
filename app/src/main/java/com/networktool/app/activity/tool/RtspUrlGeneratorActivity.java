package com.networktool.app.activity.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.util.RtspBrandCatalog;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * RTSP 地址生成器：选品牌 → 填 IP/账号 → 生成候选 RTSP 地址，可一键复制或直接预览。
 *
 * <p>品牌与路径数据来自 {@link RtspBrandCatalog}（参考 rtsp.cam 的公开品牌表）。</p>
 */
public class RtspUrlGeneratorActivity extends AppCompatActivity {

    private static final String PREFS = "rtsp_generator";
    private static final String KEY_BRAND = "brand";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USER = "user";
    private static final String KEY_CHANNEL = "channel";

    /** 没选品牌时给几条最常见的通用路径 */
    private static final String[][] GENERIC_PATHS = {
            {"通用 ONVIF 路径", "rtsp://{user}:{pass}@{host}:{port}/onvif1"},
            {"通用 ONVIF 路径 2", "rtsp://{user}:{pass}@{host}:{port}/onvif2"},
            {"海康风格", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}01"},
            {"大华风格", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=0"},
            {"雄迈风格", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"},
    };

    private TextInputEditText hostInput;
    private TextInputEditText portInput;
    private TextInputEditText channelInput;
    private TextInputEditText userInput;
    private TextInputEditText passwordInput;
    private TextView brandValue;
    private LinearLayout resultContainer;
    private TextView resultTitle;

    private SharedPreferences prefs;
    private RtspBrandCatalog.Brand currentBrand;

    /** 当前生成的地址（标签 + 地址），供「全部复制」使用 */
    private final List<String> generatedUrls = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rtsp_generator);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        channelInput = findViewById(R.id.channelInput);
        userInput = findViewById(R.id.userInput);
        passwordInput = findViewById(R.id.passwordInput);
        brandValue = findViewById(R.id.brandValue);
        resultContainer = findViewById(R.id.resultContainer);
        resultTitle = findViewById(R.id.resultTitle);

        restorePrefs();
        applyCardColors();
        applyThemeTints();

        MaterialButton pickBrandButton = findViewById(R.id.pickBrandButton);
        if (pickBrandButton != null) {
            pickBrandButton.setOnClickListener(v -> showBrandPicker());
        }
        MaterialButton copyAllButton = findViewById(R.id.copyAllButton);
        if (copyAllButton != null) {
            copyAllButton.setOnClickListener(v -> copyAll());
        }

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                regenerate();
            }
        };
        if (hostInput != null) hostInput.addTextChangedListener(watcher);
        if (portInput != null) portInput.addTextChangedListener(watcher);
        if (channelInput != null) channelInput.addTextChangedListener(watcher);
        if (userInput != null) userInput.addTextChangedListener(watcher);
        if (passwordInput != null) passwordInput.addTextChangedListener(watcher);

        regenerate();
    }

    /* ==================== 状态 ==================== */

    private void restorePrefs() {
        String brandName = prefs.getString(KEY_BRAND, null);
        currentBrand = RtspBrandCatalog.find(brandName);
        updateBrandLabel();

        if (hostInput != null && TextUtils.isEmpty(hostInput.getText())) {
            hostInput.setText(prefs.getString(KEY_HOST, ""));
        }
        if (portInput != null && TextUtils.isEmpty(portInput.getText())) {
            portInput.setText(String.valueOf(currentBrand != null
                    ? currentBrand.port : prefs.getInt(KEY_PORT, 554)));
        }
        if (channelInput != null && TextUtils.isEmpty(channelInput.getText())) {
            channelInput.setText(String.valueOf(prefs.getInt(KEY_CHANNEL, 1)));
        }
        if (userInput != null && TextUtils.isEmpty(userInput.getText())) {
            String user = prefs.getString(KEY_USER,
                    currentBrand != null && currentBrand.defaultUser != null
                            ? currentBrand.defaultUser : "admin");
            userInput.setText(user);
        }
    }

    private void savePrefs() {
        String brandName = currentBrand == null ? null : currentBrand.name;
        prefs.edit()
                .putString(KEY_BRAND, brandName)
                .putString(KEY_HOST, text(hostInput))
                .putInt(KEY_PORT, parseInt(text(portInput), 554))
                .putString(KEY_USER, text(userInput))
                .putInt(KEY_CHANNEL, parseInt(text(channelInput), 1))
                .apply();
    }

    private void updateBrandLabel() {
        if (brandValue == null) return;
        if (currentBrand == null) {
            brandValue.setText("未选择（使用通用 ONVIF 路径）");
        } else {
            StringBuilder sb = new StringBuilder(currentBrand.name);
            sb.append(" · 默认端口 ").append(currentBrand.port);
            if (currentBrand.defaultUser != null) {
                sb.append(" · 默认账号 ").append(currentBrand.defaultUser);
            }
            sb.append(" · ").append(currentBrand.streams.size()).append(" 条路径");
            brandValue.setText(sb.toString());
        }
    }

    /* ==================== 生成 ==================== */

    private void regenerate() {
        if (resultContainer == null) return;
        resultContainer.removeAllViews();
        generatedUrls.clear();

        String host = normalizeHost(text(hostInput));
        String port = text(portInput);
        String channel = text(channelInput);
        String user = text(userInput);
        String password = text(passwordInput);
        if (port.isEmpty()) {
            port = String.valueOf(currentBrand != null ? currentBrand.port : 554);
        }
        if (channel.isEmpty()) {
            channel = "1";
        }

        if (TextUtils.isEmpty(host)) {
            addEmptyHint("先填写摄像头 IP 或域名");
            updateResultTitle();
            return;
        }

        if (currentBrand != null) {
            for (RtspBrandCatalog.Stream stream : currentBrand.streams) {
                String url = fillTemplate(stream.template, host, port, user, password, channel);
                addResultRow(stream.label, url);
            }
        } else {
            for (String[] entry : GENERIC_PATHS) {
                String url = fillTemplate(entry[1], host, port, user, password, channel);
                addResultRow(entry[0], url);
            }
        }
        updateResultTitle();
        savePrefs();
    }

    private void updateResultTitle() {
        if (resultTitle == null) return;
        if (generatedUrls.isEmpty()) {
            resultTitle.setText("生成的地址");
        } else {
            resultTitle.setText("生成的地址（" + generatedUrls.size() + "）");
        }
    }

    private String fillTemplate(String template, String host, String port,
                                String user, String password, String channel) {
        String url = template;
        boolean hasUser = !TextUtils.isEmpty(user);
        boolean hasPass = !TextUtils.isEmpty(password);

        // 没填用户名：去掉 rtsp://user:pass@ 里的 userInfo，避免出现 rtsp://:@host
        if (!hasUser) {
            url = url.replace("{user}:{pass}@", "");
        }
        // 有些品牌（如 XMEye）把账号密码放在路径里，字段为空时整段去掉，避免 user=_password=
        if (!hasUser && !hasPass) {
            url = url.replace("user={user}_password={pass}_", "");
            url = url.replace("user={user}&password={pass}", "");
        } else if (!hasUser) {
            url = url.replace("user={user}_", "");
            url = url.replace("user={user}&", "");
        } else if (!hasPass) {
            url = url.replace("_password={pass}_", "_");
            url = url.replace("&password={pass}", "");
            url = url.replace("password={pass}&", "");
        }

        url = url.replace("{user}", safe(user));
        url = url.replace("{pass}", safe(password));
        url = url.replace("{host}", host);
        url = url.replace("{port}", TextUtils.isEmpty(port) ? "554" : port);
        url = url.replace("{ch}", TextUtils.isEmpty(channel) ? "1" : channel);

        // 清理空字段留下的分隔符（_password=_、?&、结尾的 ? / &）
        url = url.replace("_password=_", "_");
        url = url.replace("password=&", "&");
        url = url.replace("&password=", "");
        url = url.replace("?&", "?");
        url = url.replace("&&", "&");
        while (url.endsWith("?") || url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 允许用户直接粘贴整条 rtsp:// 地址，自动拆出主机 */
    private String normalizeHost(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String value = raw.trim();
        if (value.startsWith("rtsp://") || value.startsWith("rtsps://")
                || value.startsWith("http://") || value.startsWith("https://")) {
            try {
                Uri uri = Uri.parse(value);
                String host = uri.getHost();
                if (!TextUtils.isEmpty(host)) {
                    if (portInput != null && uri.getPort() > 0) {
                        portInput.setText(String.valueOf(uri.getPort()));
                    }
                    return host;
                }
            } catch (Exception ignored) {
            }
        }
        // 顺手去掉误输入的空白和末尾斜杠
        return value.replace("/", "").replace(" ", "");
    }

    private void addEmptyHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextAppearance(R.style.TextAppearance_NetworkTool_BodySmall);
        hint.setPadding(0, dp(10), 0, dp(10));
        resultContainer.addView(hint);
    }

    /** 单条结果卡片：标签 + 地址 + 复制/预览 */
    private void addResultRow(String label, String url) {
        generatedUrls.add(url);

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);
        card.setRadius(dp(16));
        card.setCardElevation(0f);
        ThemeManager.setupResultCard(card, this);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor((ThemeManager.getOnSurfaceVariantColor(this) & 0x00FFFFFF) | 0x33000000);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(6));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextAppearance(R.style.TextAppearance_NetworkTool_Caption);
        content.addView(labelView);

        TextView urlView = new TextView(this);
        urlView.setText(url);
        urlView.setTextSize(12f);
        urlView.setTextIsSelectable(true);
        urlView.setTextColor(ThemeManager.getOnSurfaceColor(this));
        urlView.setPadding(0, dp(4), 0, dp(4));
        content.addView(urlView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        MaterialButton copyButton = buildActionButton("复制");
        copyButton.setOnClickListener(v -> copyToClipboard(url));
        actions.addView(copyButton);

        MaterialButton previewButton = buildActionButton("预览");
        previewButton.setOnClickListener(v -> openPlayer(url));
        actions.addView(previewButton);

        content.addView(actions);
        card.addView(content);
        resultContainer.addView(card);
    }

    private MaterialButton buildActionButton(String text) {
        MaterialButton button = new MaterialButton(this);
        // 手动做成「文字按钮」：透明底 + 主题色文字，和项目里的 TextButton 观感一致
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.TRANSPARENT));
        button.setElevation(0f);
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(4));
        button.setLayoutParams(params);
        return button;
    }

    /* ==================== 操作 ==================== */

    private void copyToClipboard(String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("RTSP 地址", url));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAll() {
        if (generatedUrls.isEmpty()) {
            Toast.makeText(this, "还没有生成地址", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String url : generatedUrls) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(url);
        }
        copyToClipboard(sb.toString());
    }

    private void openPlayer(String url) {
        try {
            Intent intent = new Intent(this, RtspPlayerActivity.class);
            intent.putExtra("main_stream_url", url);
            String host = normalizeHost(text(hostInput));
            intent.putExtra("device_name", TextUtils.isEmpty(host) ? "RTSP 预览" : host);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开播放器", Toast.LENGTH_SHORT).show();
        }
    }

    /* ==================== 品牌选择 ==================== */

    private void showBrandPicker() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_rtsp_brand_picker, null);
        sheet.setContentView(content);

        TextInputEditText searchInput = content.findViewById(R.id.brandSearchInput);
        themeInputLayout(content.findViewById(R.id.brandSearchLayout));
        // 弹窗里的计数文字用次级色，跟随运行时主题
        TextView countView = content.findViewById(R.id.brandCountText);
        if (countView != null) {
            countView.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));
        }
        TextView countText = content.findViewById(R.id.brandCountText);
        RecyclerView list = content.findViewById(R.id.brandList);
        list.setLayoutManager(new LinearLayoutManager(this));

        BrandAdapter adapter = new BrandAdapter(new ArrayList<>(), brand -> {
            currentBrand = brand;
            updateBrandLabel();
            if (portInput != null) {
                portInput.setText(String.valueOf(brand.port));
            }
            if (userInput != null && TextUtils.isEmpty(text(userInput))
                    && brand.defaultUser != null) {
                userInput.setText(brand.defaultUser);
            }
            sheet.dismiss();
            regenerate();
        });
        list.setAdapter(adapter);

        Runnable refresh = () -> {
            String keyword = searchInput == null ? "" : text(searchInput);
            List<RtspBrandCatalog.Brand> result = RtspBrandCatalog.search(keyword);
            adapter.setItems(result);
            if (countText != null) {
                countText.setText("共 " + RtspBrandCatalog.brands().size() + " 个品牌，匹配 "
                        + result.size() + " 个");
            }
        };
        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    refresh.run();
                }
            });
        }
        refresh.run();
        sheet.show();
    }

    /** 品牌列表适配器：一行一个品牌名 + 默认端口 */
    private class BrandAdapter extends RecyclerView.Adapter<BrandAdapter.Holder> {

        interface OnPick {
            void onPick(RtspBrandCatalog.Brand brand);
        }

        private final List<RtspBrandCatalog.Brand> items;
        private final OnPick onPick;

        BrandAdapter(List<RtspBrandCatalog.Brand> items, OnPick onPick) {
            this.items = items;
            this.onPick = onPick;
        }

        void setItems(List<RtspBrandCatalog.Brand> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            view.setPadding(dp(12), dp(14), dp(12), dp(14));
            view.setTextSize(14f);
            view.setTextColor(ThemeManager.getOnSurfaceColor(parent.getContext()));
            view.setBackgroundResource(resolveSelectableItemBackground());
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            RtspBrandCatalog.Brand brand = items.get(position);
            StringBuilder sb = new StringBuilder(brand.name);
            if (brand.defaultUser != null) {
                sb.append("  ·  ").append(brand.defaultUser);
            }
            sb.append("  ·  端口 ").append(brand.port);
            ((TextView) holder.itemView).setText(sb.toString());
            holder.itemView.setOnClickListener(v -> onPick.onPick(brand));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    private int resolveSelectableItemBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    /* ==================== 工具 ==================== */

    /** XML 卡片用的是静态主题属性，不会跟随运行时主题色，这里按项目规范套一遍底色 */
    private void applyCardColors() {
        try {
            ThemeManager.setupResultCard(findViewById(R.id.deviceCard), this);
            ThemeManager.setupResultCard(findViewById(R.id.brandCard), this);
        } catch (Throwable ignored) {
        }
    }

    private void applyThemeTints() {
        int accent = ThemeManager.getAccessibleThemeColor(this);
        MaterialButton pick = findViewById(R.id.pickBrandButton);
        if (pick != null) {
            pick.setTextColor(accent);
        }
        MaterialButton copyAll = findViewById(R.id.copyAllButton);
        if (copyAll != null) {
            copyAll.setTextColor(accent);
        }
        int[] ids = new int[]{R.id.hostLayout, R.id.portLayout, R.id.channelLayout,
                R.id.userLayout, R.id.passwordLayout};
        for (int id : ids) {
            themeInputLayout(findViewById(id));
        }
    }

    /** 输入框的描边/提示/图标跟随主题色（XML 的 ?attr 不会随运行时主题变化） */
    private void themeInputLayout(com.google.android.material.textfield.TextInputLayout layout) {
        if (layout == null) return;
        try {
            int accent = ThemeManager.getAccessibleThemeColor(this);
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_focused},
                    new int[]{}
            };
            layout.setBoxStrokeColorStateList(new android.content.res.ColorStateList(
                    states, new int[]{accent, ThemeManager.getOnSurfaceVariantColor(this)}));
            layout.setHintTextColor(new android.content.res.ColorStateList(
                    states, new int[]{accent, ThemeManager.getOnSurfaceVariantColor(this)}));
            android.content.res.ColorStateList csl =
                    android.content.res.ColorStateList.valueOf(accent);
            layout.setEndIconTintList(csl);
            layout.setStartIconTintList(csl);
        } catch (Throwable ignored) {
        }
    }

    private String text(TextView view) {
        if (view == null || view.getText() == null) return "";
        return view.getText().toString().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
