package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 应用管理Activity
 *
 * 显示已安装应用列表，支持：
 * - 查看应用信息
 * - 打开应用
 * - 应用详情设置
 * - 分享/提取应用
 */
public class AppManagerActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private LinearLayout loadingLayout;
    private ProgressBar loadingProgress;
    private MaterialCardView searchCard;
    private EditText searchEditText;
    private ImageButton clearSearchButton;

    private AppAdapter adapter;
    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> filteredApps = new ArrayList<>();
    private boolean isLoading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_manager);

        // 设置沉浸式状态栏
        setupStatusBar();

        // 初始化工具栏
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setupToolbar();

        initViews();
        loadApps();
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
     * 设置工具栏主题
     */
    private void setupToolbar() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("应用管理");
        }
        
        // 应用主题色到工具栏
        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        loadingLayout = findViewById(R.id.loadingLayout);
        loadingProgress = findViewById(R.id.loadingProgress);
        searchCard = findViewById(R.id.searchCard);
        searchEditText = findViewById(R.id.searchEditText);
        clearSearchButton = findViewById(R.id.clearSearchButton);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 搜索框监听
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filterApps(s.toString());
                clearSearchButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        clearSearchButton.setOnClickListener(v -> searchEditText.setText(""));

        Toast.makeText(this, "长按项目打开菜单", Toast.LENGTH_SHORT).show();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_META_DATA);

            allApps.clear();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

            for (PackageInfo pkg : packages) {
                ApplicationInfo appInfo = pkg.applicationInfo;

                AppInfo info = new AppInfo();
                info.packageName = pkg.packageName;
                info.versionName = pkg.versionName;
                info.versionCode = pkg.versionCode;
                info.name = pm.getApplicationLabel(appInfo).toString();
                info.icon = pm.getApplicationIcon(appInfo);
                info.isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                info.sourceDir = appInfo.sourceDir;
                info.uid = appInfo.uid;
                info.flags = appInfo.flags;

                // 获取应用大小
                File file = new File(appInfo.sourceDir);
                info.size = file.length();
                info.sizeText = Formatter.formatFileSize(this, info.size);

                // 获取更新时间
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(pkg.lastUpdateTime);
                info.lastUpdateTime = sdf.format(cal.getTime());

                allApps.add(info);
            }

            // 按名称排序
            Collections.sort(allApps, Comparator.comparing(a -> a.name.toLowerCase()));

            runOnUiThread(() -> {
                isLoading = false;
                loadingLayout.setVisibility(View.GONE);
                loadingProgress.setVisibility(View.GONE);

                if (allApps.isEmpty()) {
                    emptyView.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                } else {
                    emptyView.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                    filteredApps.addAll(allApps);
                    adapter = new AppAdapter(filteredApps);
                    recyclerView.setAdapter(adapter);
                }
            });
        }).start();
    }

    private void filterApps(String keyword) {
        if (isLoading) return;

        filteredApps.clear();
        if (keyword.isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            keyword = keyword.toLowerCase();
            for (AppInfo app : allApps) {
                if (app.name.toLowerCase().contains(keyword) ||
                        app.packageName.toLowerCase().contains(keyword)) {
                    filteredApps.add(app);
                }
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        if (filteredApps.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 应用信息模型
     */
    private static class AppInfo {
        String name;
        String packageName;
        String versionName;
        long versionCode;
        android.graphics.drawable.Drawable icon;
        boolean isSystemApp;
        String sourceDir;
        int uid;
        int flags;
        long size;
        String sizeText;
        String lastUpdateTime;
    }

    /**
     * 应用列表适配器
     */
    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

        private List<AppInfo> apps;

        AppAdapter(List<AppInfo> apps) {
            this.apps = apps;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_manager, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            AppInfo app = apps.get(position);

            holder.titleText.setText(app.name);
            holder.subtitleText.setText(app.packageName);
            holder.iconImage.setImageDrawable(app.icon);

            // 设置应用信息
            StringBuilder info = new StringBuilder();
            info.append("版本 ").append(app.versionName);
            info.append(" | 代号 ").append(app.versionCode);
            info.append(" | 更新时间 ").append(app.lastUpdateTime);
            holder.infoText.setText(info.toString());

            // 设置大小标签颜色
            int bgColor, strokeColor;
            if (app.isSystemApp) {
                bgColor = 0x73fb8c00; // 橙色
                strokeColor = 0xfffb8c00;
            } else {
                bgColor = 0x7643a047; // 绿色
                strokeColor = 0xff43a047;
            }
            holder.sizeCard.setCardBackgroundColor(bgColor);
            holder.sizeCard.setStrokeColor(strokeColor);
            holder.sizeText.setText(app.sizeText);

            // 点击显示详情
            holder.itemView.setOnClickListener(v -> showAppDetails(app));

            // 长按显示菜单
            holder.itemView.setOnLongClickListener(v -> {
                showAppMenu(app, v);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView titleText;
            TextView subtitleText;
            ImageView iconImage;
            TextView infoText;
            MaterialCardView sizeCard;
            TextView sizeText;

            ViewHolder(View itemView) {
                super(itemView);
                titleText = itemView.findViewById(R.id.titleText);
                subtitleText = itemView.findViewById(R.id.subtitleText);
                iconImage = itemView.findViewById(R.id.iconImage);
                infoText = itemView.findViewById(R.id.infoText);
                sizeCard = itemView.findViewById(R.id.sizeCard);
                sizeText = itemView.findViewById(R.id.sizeText);
            }
        }
    }

    /**
     * 显示应用详情
     */
    private void showAppDetails(AppInfo app) {
        StringBuilder info = new StringBuilder();
        info.append("[应用] ").append(app.name);
        info.append("\n[包名] ").append(app.packageName);
        info.append("\n[版本] ").append(app.versionName);
        info.append("\n[代号] ").append(app.versionCode);
        info.append("\n[大小] ").append(app.sizeText).append(" (").append(app.size).append(")");
        info.append("\n[appUid] uid-").append(app.uid);
        info.append("\n[appflags] flags-").append(app.flags);
        info.append("\n[更新时间] ").append(app.lastUpdateTime);
        info.append("\n[类型] ").append(app.isSystemApp ? "系统应用" : "用户应用");

        new MaterialAlertDialogBuilder(this)
                .setIcon(app.icon)
                .setTitle("应用信息")
                .setMessage(info.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * 显示应用菜单
     */
    private void showAppMenu(AppInfo app, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("打开应用");
        popup.getMenu().add("应用详情");
        popup.getMenu().add("分享应用");
        popup.getMenu().add("提取应用");
        popup.getMenu().add("复制包名");

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            switch (title) {
                case "打开应用":
                    openApp(app.packageName);
                    break;
                case "应用详情":
                    openAppSettings(app.packageName);
                    break;
                case "分享应用":
                    shareApp(app);
                    break;
                case "提取应用":
                    extractApp(app);
                    break;
                case "复制包名":
                    copyToClipboard(app.packageName);
                    Toast.makeText(this, "包名已复制", Toast.LENGTH_SHORT).show();
                    break;
            }
            return true;
        });

        popup.show();
    }

    private void openApp(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "该应用没有启动入口", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法启动应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppSettings(String packageName) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用详情", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareApp(AppInfo app) {
        new Thread(() -> {
            try {
                File sourceFile = new File(app.sourceDir);
                File targetFile = new File(getExternalCacheDir(),
                        "[网络工具箱] " + app.name + "_" + app.versionName + ".apk");

                copyFile(sourceFile, targetFile);

                runOnUiThread(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/vnd.android.package-archive");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(targetFile));
                    startActivity(Intent.createChooser(shareIntent, "分享应用"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void extractApp(AppInfo app) {
        new Thread(() -> {
            try {
                File sourceFile = new File(app.sourceDir);
                File targetDir = new File(getExternalFilesDir(null), "提取应用");
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                File targetFile = new File(targetDir,
                        "[网络工具箱] " + app.name + "_" + app.versionName + ".apk");

                copyFile(sourceFile, targetFile);

                runOnUiThread(() -> Toast.makeText(this,
                        "提取成功，保存于：" + targetFile.getAbsolutePath(),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "提取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void copyFile(File source, File dest) throws Exception {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(dest);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = fis.read(buffer)) != -1) {
            fos.write(buffer, 0, len);
        }
        fos.close();
        fis.close();
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("text", text);
        clipboard.setPrimaryClip(clip);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
