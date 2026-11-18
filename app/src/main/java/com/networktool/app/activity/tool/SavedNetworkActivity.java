package com.networktool.app.activity.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputLayout;
import com.networktool.app.R;
import com.networktool.app.adapter.SavedNetworkAdapter;
import com.networktool.app.model.SavedNetworkModel;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.BottomSheetHelper;
import com.networktool.app.util.ShizukuHelper;
import com.networktool.app.util.ShizukuShell;
import com.networktool.app.util.SavedNetworkStore;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiPrivilegedReader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 已保存网络 Activity
 *
 * <p>已保存网络的列表和明文密码都在系统侧，普通 App 拿不到：Android 10+ 的公开接口返回的
 * 密码是占位符，Android 16 甚至连列表都不再返回。这里通过 Shizuku 以 shell(uid 2000) 的身份
 * 调用系统隐藏接口 {@code getPrivilegedConfiguredNetworks}——Android 11 起 shell 被赋予了该权限，
 * 因此不需要 root 就能读到列表和密码。</p>
 *
 * <p><b>读取是手动刷新的</b>：读到一次就缓存到本地（{@link SavedNetworkStore}），下次进页面先看缓存，
 * 用户点右上角刷新才重新读取；Shizuku 授权走标题栏钥匙图标的底部弹窗。不再每次进页面自动刷新。</p>
 *
 * <p>只读：删除网络走系统的 {@code IWifiManager.forget}，由 Shizuku 代为执行。</p>
 */
public class SavedNetworkActivity extends AppCompatActivity {

    private static final String TAG = "SavedNetworkActivity";

    /** 向 Shizuku 申请权限的 requestCode，结果走 onRequestPermissionsResult */
    private static final int REQ_SHIZUKU = 101;

    /** Shizuku 的包名，用于把用户引导到它的界面 */
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private TextView countText;
    private TextView cacheTimeText;
    private TextView emptyText;
    private TextView emptyHintText;
    private TextInputLayout searchLayout;
    private EditText searchEditText;

    private MenuItem shizukuMenuItem;

    private SavedNetworkAdapter adapter;
    /** 全量列表：来自缓存或一次成功读取 */
    private final List<SavedNetworkModel> allNetworks = new ArrayList<>();
    /** 经搜索过滤后真正展示的列表 */
    private final List<SavedNetworkModel> filteredNetworks = new ArrayList<>();
    /** 当前搜索关键词 */
    private String query = "";

    private SavedNetworkStore store;
    /** 最近一次成功读取的落盘时间（毫秒），0 表示还没有缓存 */
    private long cacheTime;

    private ExecutorService executor;
    /** 是否已经弹过授权请求，避免重复弹 */
    private boolean permissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_saved_network);

            setupStatusBar();
            setupToolbar();
            initManagers();
            initViews();

            // 先展示本地缓存（如果有），读取靠用户手动刷新
            store = new SavedNetworkStore(this);
            loadCache();
            updateShizukuStatus();
        } catch (Throwable e) {
            AppErrorLogger.catchThrowable(TAG, "已保存网络页初始化失败", e);
        }
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
     * 设置工具栏：返回按钮 + 右侧两个图标按钮（Shizuku 授权、刷新）
     */
    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
        toolbar.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    private void initManagers() {
        executor = Executors.newSingleThreadExecutor();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        countText = findViewById(R.id.countText);
        cacheTimeText = findViewById(R.id.cacheTimeText);
        emptyText = findViewById(R.id.emptyText);
        emptyHintText = findViewById(R.id.emptyHintText);
        countText.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        cacheTimeText.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));

        searchLayout = findViewById(R.id.searchLayout);
        searchEditText = findViewById(R.id.searchEditText);
        // 搜索框跟随运行时主题色（Material3 OutlinedBox 默认取静态 colorPrimary）
        ThemeManager.applyInputTheme(searchLayout, this);
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim();
                applyFilter();
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        adapter = new SavedNetworkAdapter(filteredNetworks,
                this::showNetworkDetails,
                this::onNetworkLongClick);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            // 只刷新 Shizuku 状态（钥匙图标颜色），不再自动读取；读取靠用户点刷新
            updateShizukuStatus();
        } catch (Throwable e) {
            AppErrorLogger.catchThrowable(TAG, "刷新已保存网络页状态失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 把 Shizuku 进程里那个执行器结束掉，免得它一直挂着
        ShizukuShell.unbind(this);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_SHIZUKU) return;

        permissionRequested = false;
        if (ShizukuHelper.hasPermission()) {
            Toast.makeText(this, "已获得 Shizuku 授权，点刷新读取", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "没有 Shizuku 权限，读不到已保存网络", Toast.LENGTH_LONG).show();
        }
        updateShizukuStatus();
        applyFilter();
    }

    /** 标题栏菜单点击：钥匙 = 授权弹窗，刷新 = 手动读取 */
    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_shizuku) {
            showShizukuSheet();
            return true;
        } else if (id == R.id.action_refresh) {
            refreshFromShizuku();
            return true;
        }
        return false;
    }

    /* ==================== 缓存 ==================== */

    /** 读本地缓存并展示；缓存时间显示在数量旁边 */
    private void loadCache() {
        allNetworks.clear();
        SavedNetworkStore.Snapshot snapshot = store.load();
        allNetworks.addAll(snapshot.networks);
        cacheTime = snapshot.time;
        applyFilter();
    }

    /** 把当前列表写进缓存 */
    private void saveCache() {
        cacheTime = System.currentTimeMillis();
        store.save(cacheTime, allNetworks);
    }

    /* ==================== 读取（手动刷新） ==================== */

    /**
     * 刷新入口：核对 Shizuku 状态，未就绪就引导到授权弹窗，就绪才真正读取。
     */
    private void refreshFromShizuku() {
        try {
            if (!ShizukuHelper.isRunning()) {
                Toast.makeText(this, "Shizuku 未运行，请先启动", Toast.LENGTH_SHORT).show();
                showShizukuSheet();
                return;
            }
            if (!ShizukuHelper.hasPermission()) {
                showShizukuSheet();
                return;
            }
            setLoading(true);
            // 读当前连接、删网络都要跑 shell 命令，先把执行器绑上
            ShizukuShell.bind(this);
            executor.execute(this::doLoadData);
        } catch (Throwable e) {
            setLoading(false);
            AppErrorLogger.catchThrowable(TAG, "读取已保存网络失败", e);
            Toast.makeText(this, "读取已保存网络失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 在子线程里读取，并标记当前连接的网络 */
    private void doLoadData() {
        final WifiPrivilegedReader.Result result = WifiPrivilegedReader.read();
        if (result.isOk()) {
            markCurrentConnection(result.networks);
        }

        runOnUiThread(() -> {
            setLoading(false);
            if (result.isOk()) {
                allNetworks.clear();
                allNetworks.addAll(result.networks);
                saveCache();
                applyFilter();
                updateShizukuStatus();
                if (allNetworks.isEmpty()) {
                    Toast.makeText(this, "系统里没有已保存的网络", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "已读取 " + allNetworks.size() + " 个网络",
                            Toast.LENGTH_SHORT).show();
                }
            } else if (result.status == WifiPrivilegedReader.Status.UNSUPPORTED) {
                // 接口本身在 Android 9 才有，更早的系统够不着，这是"做不到"不是"出错了"
                allNetworks.clear();
                applyFilter();
                emptyText.setText("当前系统版本不支持");
                emptyHintText.setText("读取已保存网络需要 Android 9 及以上，此设备无法获取");
            } else {
                String detail = result.error == null ? "" : result.error;
                Toast.makeText(this, "读取失败：" + detail, Toast.LENGTH_LONG).show();
                // 读取失败时保留已有缓存展示，不清空
                applyFilter();
            }
        });
    }

    /**
     * 标记当前连接的网络。
     *
     * <p>比对 {@code cmd wifi status} 输出里带引号的 SSID，而不是解析它的句子：那条命令的文案
     * 各 ROM 会本地化，SSID 本身不会，所以按引号串匹配最稳。</p>
     */
    private void markCurrentConnection(List<SavedNetworkModel> list) {
        String status;
        try {
            status = ShizukuShell.exec("cmd wifi status").output;
        } catch (Throwable e) {
            AppErrorLogger.catchThrowable(TAG, "读取当前连接信息失败", e);
            return;
        }
        for (SavedNetworkModel model : list) {
            String ssid = model.getSsid();
            if (ssid != null && status.contains("\"" + ssid + "\"")) {
                model.setCurrentNetwork(true);
            }
        }
    }

    /** 读取中把数量行换成进度提示，按钮保持可点（重复点不叠加，靠 executor 单线程串行） */
    private void setLoading(boolean loading) {
        if (loading) {
            cacheTimeText.setText("正在读取…");
        } else {
            updateHeader();
        }
    }

    /* ==================== 展示 ==================== */

    /** 按关键词过滤 allNetworks 到 filteredNetworks，并刷新列表 / 空态 / 数量 */
    private void applyFilter() {
        filteredNetworks.clear();
        for (SavedNetworkModel model : allNetworks) {
            if (matchesQuery(model)) {
                filteredNetworks.add(model);
            }
        }
        adapter.updateData(filteredNetworks);

        boolean hasData = !filteredNetworks.isEmpty();
        recyclerView.setVisibility(hasData ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(hasData ? View.GONE : View.VISIBLE);
        updateHeader();
        updateEmptyState();
    }

    /** 关键词匹配：按名称（SSID）不区分大小写；空关键词匹配全部 */
    private boolean matchesQuery(SavedNetworkModel model) {
        if (TextUtils.isEmpty(query)) return true;
        String ssid = model.getSsid();
        return ssid != null && ssid.toLowerCase(Locale.ROOT)
                .contains(query.toLowerCase(Locale.ROOT));
    }

    /** 数量行：总数 + 缓存时间（读取中由 setLoading 覆盖） */
    private void updateHeader() {
        int total = allNetworks.size();
        if (!TextUtils.isEmpty(query)) {
            countText.setText("匹配 " + filteredNetworks.size() + " / " + total + " 个");
        } else {
            countText.setText("共 " + total + " 个已保存网络");
        }

        if (cacheTime > 0) {
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            cacheTimeText.setText("缓存于 " + fmt.format(new Date(cacheTime)));
        } else {
            cacheTimeText.setText("");
        }
    }

    /** 空态文案：区分「搜索无匹配」和「真的没数据」 */
    private void updateEmptyState() {
        if (!filteredNetworks.isEmpty()) return;

        if (!allNetworks.isEmpty()) {
            // 有数据但被搜索过滤光了
            emptyText.setText("没有匹配的网络");
            emptyHintText.setText("换个关键词试试，搜索按网络名称匹配");
            return;
        }

        boolean running = ShizukuHelper.isRunning();
        boolean granted = running && ShizukuHelper.hasPermission();
        if (!running) {
            emptyText.setText("未检测到运行中的 Shizuku");
            emptyHintText.setText("点右上角钥匙图标连接 Shizuku，用无线调试（或 root）启动后点刷新读取");
        } else if (!granted) {
            emptyText.setText("尚未获得 Shizuku 授权");
            emptyHintText.setText("点右上角钥匙图标申请授权，授权后点刷新读取");
        } else if (cacheTime <= 0) {
            emptyText.setText("还没有读取过");
            emptyHintText.setText("点右上角刷新按钮，读取系统保存的网络与密码");
        } else {
            emptyText.setText("暂无已保存网络");
            emptyHintText.setText("系统里当前没有保存任何 WiFi 网络");
        }
    }

    /* ==================== Shizuku 状态 ==================== */

    /** 更新钥匙图标颜色：已授权用主题色，未授权用中性色 */
    private void updateShizukuStatus() {
        if (shizukuMenuItem == null) {
            shizukuMenuItem = toolbar.getMenu().findItem(R.id.action_shizuku);
        }
        if (shizukuMenuItem == null) return;

        boolean granted = ShizukuHelper.isRunning() && ShizukuHelper.hasPermission();
        int color = granted
                ? ThemeManager.getAccessibleThemeColor(this)
                : ThemeManager.getOnSurfaceVariantColor(this);
        Drawable icon = shizukuMenuItem.getIcon();
        if (icon != null) {
            icon = icon.mutate();
            icon.setTintList(ColorStateList.valueOf(color));
            shizukuMenuItem.setIcon(icon);
        }
    }

    /**
     * Shizuku 授权底部弹窗：显示当前状态，并给出对应的下一步操作。
     */
    private void showShizukuSheet() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this);
            View content = getLayoutInflater()
                    .inflate(R.layout.bottomsheet_shizuku_auth, null);
            dialog.setContentView(content);
            BottomSheetHelper.capHeight(dialog, 0.7f);

            bindShizukuSheet(content, dialog);
            dialog.show();
        } catch (Throwable e) {
            AppErrorLogger.catchThrowable(TAG, "打开 Shizuku 授权弹窗失败", e);
        }
    }

    /** 按当前 Shizuku 状态填充弹窗文案与按钮 */
    private void bindShizukuSheet(View content, BottomSheetDialog dialog) {
        MaterialCardView statusCard = content.findViewById(R.id.sheetStatusCard);
        ImageView statusIcon = content.findViewById(R.id.sheetStatusIcon);
        TextView statusText = content.findViewById(R.id.sheetStatusText);
        TextView hintText = content.findViewById(R.id.sheetHintText);
        MaterialButton actionButton = content.findViewById(R.id.sheetActionButton);
        MaterialButton secondaryButton = content.findViewById(R.id.sheetSecondaryButton);

        ThemeManager.setupResultCard(statusCard, this);
        int accent = ThemeManager.getAccessibleThemeColor(this);
        int neutral = ThemeManager.getOnSurfaceVariantColor(this);

        boolean running = ShizukuHelper.isRunning();
        boolean granted = running && ShizukuHelper.hasPermission();
        int uid = ShizukuHelper.getUid();

        if (!running) {
            statusIcon.setImageResource(R.drawable.ic_key);
            statusIcon.setColorFilter(neutral);
            statusText.setText("Shizuku 未运行");
            hintText.setText("请先用无线调试（或 root）启动 Shizuku，再回来读取已保存网络");
            actionButton.setText("打开 Shizuku 应用");
            ThemeManager.applyPrimaryButton(actionButton);
            actionButton.setOnClickListener(v -> {
                dialog.dismiss();
                openShizukuApp();
            });
            secondaryButton.setVisibility(View.VISIBLE);
            secondaryButton.setText("我没装 Shizuku，去了解");
            ThemeManager.applyTonalButton(secondaryButton);
            secondaryButton.setOnClickListener(v -> {
                dialog.dismiss();
                openShizukuApp();
            });
        } else if (!granted) {
            statusIcon.setImageResource(R.drawable.ic_key);
            statusIcon.setColorFilter(neutral);
            statusText.setText("Shizuku 已运行 · 待授权");
            hintText.setText("点下方按钮，在 Shizuku 的授权弹窗里选择「允许」");
            actionButton.setText("申请授权");
            ThemeManager.applyPrimaryButton(actionButton);
            actionButton.setOnClickListener(v -> {
                dialog.dismiss();
                requestShizukuPermission();
            });
            secondaryButton.setVisibility(View.VISIBLE);
            secondaryButton.setText("打开 Shizuku 应用");
            ThemeManager.applyTonalButton(secondaryButton);
            secondaryButton.setOnClickListener(v -> {
                dialog.dismiss();
                openShizukuApp();
            });
        } else {
            String who = uid == 0 ? "root" : "shell(uid " + uid + ")";
            statusIcon.setImageResource(R.drawable.ic_check);
            statusIcon.setColorFilter(accent);
            statusText.setText("Shizuku 已授权 · " + who);
            hintText.setText("已可以读取，点下方按钮立即读取；之后也可用右上角刷新");
            actionButton.setText("立即读取");
            ThemeManager.applyPrimaryButton(actionButton);
            actionButton.setOnClickListener(v -> {
                dialog.dismiss();
                refreshFromShizuku();
            });
            secondaryButton.setVisibility(View.GONE);
        }
    }

    /** 向 Shizuku 申请使用权限，结果走 onRequestPermissionsResult */
    private void requestShizukuPermission() {
        if (permissionRequested) return;
        permissionRequested = true;
        if (!ShizukuHelper.requestPermission(this, REQ_SHIZUKU)) {
            permissionRequested = false;
            Toast.makeText(this, "无法向 Shizuku 申请授权", Toast.LENGTH_SHORT).show();
        }
    }

    /** 把用户带到 Shizuku 的界面，由用户自己去启动服务 */
    private void openShizukuApp() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setPackage(SHIZUKU_PACKAGE));
        } catch (Exception e) {
            Toast.makeText(this, "没有找到 Shizuku，请先安装", Toast.LENGTH_SHORT).show();
        }
    }

    /* ==================== 网络操作 ==================== */

    private void onNetworkLongClick(SavedNetworkModel network) {
        if (network.getNetworkId() != -1) {
            showNetworkOptions(network);
        }
    }

    private void showNetworkDetails(SavedNetworkModel network) {
        StringBuilder info = new StringBuilder();
        info.append("SSID: ").append(network.getSsid()).append("\n");
        info.append("安全类型: ").append(network.getSecurity()).append("\n");
        info.append("网络ID: ").append(network.getNetworkId()).append("\n");
        info.append("优先级: ").append(network.getPriority()).append("\n");
        info.append("密码: ").append(isEmpty(network.getPreSharedKey())
                ? "无" : network.getPreSharedKey()).append("\n");
        info.append("状态: ").append(network.isCurrentNetwork() ? "当前连接" : "已保存");

        androidx.appcompat.app.AlertDialog.Builder builder =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("网络详情")
                        .setMessage(info.toString())
                        .setPositiveButton("确定", null);

        // 有密码才给复制按钮，避免空点一下没反应
        if (!isEmpty(network.getPreSharedKey())) {
            builder.setNeutralButton("复制密码", (dialog, which) ->
                    copyText("wifi_password", network.getPreSharedKey()));
        }
        builder.show();
    }

    private void showNetworkOptions(SavedNetworkModel network) {
        String[] options = {"查看详情", "忘记此网络"};

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(network.getSsid())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showNetworkDetails(network);
                    } else if (which == 1) {
                        forgetNetwork(network);
                    }
                })
                .show();
    }

    /**
     * 忘记网络。
     *
     * <p>系统侧的网络只能由系统删：App 自己的 {@code removeNetwork()} 在 Android 10+ 已经无效，
     * 所以借同一个 shell 身份直接调系统的 {@code IWifiManager.forget}。</p>
     */
    private void forgetNetwork(SavedNetworkModel network) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("忘记网络")
                .setMessage("确定要忘记网络 \"" + network.getSsid()
                        + "\" 吗？系统保存的密码会一起删除。")
                .setPositiveButton("忘记", (dialog, which) -> executor.execute(() -> {
                    try {
                        final boolean ok =
                                WifiPrivilegedReader.forget(network.getNetworkId());
                        runOnUiThread(() -> {
                            if (ok) {
                                Toast.makeText(this, "已忘记网络 " + network.getSsid(),
                                        Toast.LENGTH_SHORT).show();
                                refreshFromShizuku();
                            } else {
                                Toast.makeText(this, "忘记失败：当前系统不支持该操作",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    } catch (Throwable e) {
                        AppErrorLogger.catchThrowable(TAG, "忘记网络失败", e);
                        runOnUiThread(() -> Toast.makeText(this, "忘记网络失败",
                                Toast.LENGTH_SHORT).show());
                    }
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "设备不支持剪贴板", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
