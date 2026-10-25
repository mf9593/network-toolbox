package com.networktool.app.activity.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

public class PermissionManagerActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_CODE = 100;
    /** 所有文件访问权限（Android 11+ 的 MANAGE_EXTERNAL_STORAGE，只能跳系统页手动开） */
    private static final String ALL_FILES_ACCESS = "android.permission.MANAGE_EXTERNAL_STORAGE";
    private static final int REQUEST_ALL_FILES_CODE = 102;
    private static final int REQUEST_INSTALL_CODE = 101;

    private RecyclerView recyclerView;
    private PermissionAdapter adapter;
    private MaterialToolbar toolbar;

    private static class PermissionItem {
        final String name;
        final String description;
        final String affect;
        final String manifestPermission;
        final int iconRes;
        boolean isGranted;

        PermissionItem(String name, String description, String affect,
                       String manifestPermission, int iconRes) {
            this.name = name;
            this.description = description;
            this.affect = affect;
            this.manifestPermission = manifestPermission;
            this.iconRes = iconRes;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_manager);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("权限管理");
        }

        applyToolbarTheme();
        setupStatusBar();

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new PermissionAdapter(buildPermissionList());
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置返回后刷新授权状态
        if (adapter != null) {
            refreshGrantStatus(adapter.items);
            adapter.notifyDataSetChanged();
        }
    }

    private void applyToolbarTheme() {
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

    private List<PermissionItem> buildPermissionList() {
        List<PermissionItem> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list.add(new PermissionItem(
                    "所有文件访问权限",
                    "读取整机存储（含根目录 /storage/emulated/0），导入直播源 / WiFi 配置需要",
                    "无法读取根目录下的列表文件、无法导出/导入到任意目录",
                    ALL_FILES_ACCESS,
                    R.drawable.ic_archive
            ));
        }
        list.add(new PermissionItem(
                "存储",
                "读取和写入设备存储空间",
                "无法导入/导出WiFi配置、保存图片和文件、查看聊天记录中的文件",
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                R.drawable.ic_archive
        ));
        list.add(new PermissionItem(
                "位置信息",
                "获取设备的精确和粗略位置",
                "无法扫描WiFi网络、无法发现局域网设备、部分WiFi工具无法使用",
                Manifest.permission.ACCESS_FINE_LOCATION,
                R.drawable.ic_wifi_tethering
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(new PermissionItem(
                    "附近 WiFi 设备",
                    "扫描和连接附近的 WiFi 网络",
                    "无法扫描或连接 WiFi，点击网络时可能提示没有权限",
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    R.drawable.ic_wifi
            ));
        }
        list.add(new PermissionItem(
                "摄像头",
                "使用设备摄像头拍照和录像",
                "无法拍照发送、ONVIF调试无法预览画面",
                Manifest.permission.CAMERA,
                R.drawable.ic_camera
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(new PermissionItem(
                    "通知",
                    "显示应用通知消息",
                    "收到局域网聊天消息时不会弹出通知提醒",
                    Manifest.permission.POST_NOTIFICATIONS,
                    R.drawable.ic_chat
            ));
        }
        list.add(new PermissionItem(
                "WiFi状态",
                "查看和修改WiFi连接状态",
                "无法查看WiFi信息、无法扫描WiFi网络、WiFi工具无法使用",
                Manifest.permission.ACCESS_WIFI_STATE,
                R.drawable.ic_wifi
        ));
        list.add(new PermissionItem(
                "网络状态",
                "查看网络连接状态",
                "无法检测网络是否可用、无法显示网络信息",
                Manifest.permission.ACCESS_NETWORK_STATE,
                R.drawable.ic_signal_wifi_4_bar
        ));
        list.add(new PermissionItem(
                "安装应用",
                "允许安装来自未知来源的应用",
                "无法在应用管理中安装APK文件",
                Manifest.permission.REQUEST_INSTALL_PACKAGES,
                R.drawable.ic_download
        ));
        list.add(new PermissionItem(
                "振动",
                "允许设备振动",
                "扫描完成、操作反馈等不会振动提示",
                Manifest.permission.VIBRATE,
                R.drawable.ic_phone
        ));
        refreshGrantStatus(list);
        return list;
    }

    private void refreshGrantStatus(List<PermissionItem> list) {
        for (PermissionItem item : list) {
            if (item.manifestPermission == null) {
                item.isGranted = true;
                continue;
            }
            if (ALL_FILES_ACCESS.equals(item.manifestPermission)) {
                item.isGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && Environment.isExternalStorageManager();
                continue;
            }
            if (Manifest.permission.REQUEST_INSTALL_PACKAGES.equals(item.manifestPermission)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    item.isGranted = getPackageManager().canRequestPackageInstalls();
                } else {
                    item.isGranted = true;
                }
                continue;
            }
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(item.manifestPermission)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item.isGranted = true;
                continue;
            }
            int result = ContextCompat.checkSelfPermission(this, item.manifestPermission);
            item.isGranted = (result == PackageManager.PERMISSION_GRANTED);
        }
    }

    private void requestPermission(PermissionItem item, int position) {
        String perm = item.manifestPermission;
        if (perm == null) return;

        if (ALL_FILES_ACCESS.equals(perm)) {
            openAllFilesAccessPage();
            return;
        }

        if (Manifest.permission.REQUEST_INSTALL_PACKAGES.equals(perm)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    item.isGranted = true;
                    adapter.notifyItemChanged(position);
                } else {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_INSTALL_CODE);
                }
            }
            return;
        }

        ActivityCompat.requestPermissions(this, new String[]{perm}, REQUEST_PERMISSION_CODE);
    }

    /** 跳到系统「所有文件访问权限」页面，用户手动点开关 */
    private void openAllFilesAccessPage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            openPermissionSettings();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_ALL_FILES_CODE);
            Toast.makeText(this, "请打开「允许访问所有文件」", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // 部分机型没有这个页面，退回到应用详情页
            openPermissionSettings();
        }
    }

    private void openPermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
        Toast.makeText(this, "请在系统设置中开启所需权限", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CODE) {
            refreshGrantStatus(adapter.items);
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_CODE || requestCode == REQUEST_ALL_FILES_CODE) {
            refreshGrantStatus(adapter.items);
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class PermissionAdapter extends RecyclerView.Adapter<PermissionAdapter.ViewHolder> {

        private final List<PermissionItem> items;

        PermissionAdapter(List<PermissionItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_permission, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PermissionItem item = items.get(position);
            android.content.Context context = holder.itemView.getContext();
            int accentColor = ThemeManager.getAccessibleThemeColor(context);
            int surfaceColor = ThemeManager.getSurfaceColor(context);
            int onSurfaceVariant = ThemeManager.getOnSurfaceVariantColor(context);
            int chipColor = ThemeManager.getChipSurfaceColor(context);
            int successColor = ThemeManager.getSuccessColor(context);
            int errorColor = ThemeManager.getErrorColor(context);

            holder.cardView.setCardBackgroundColor(surfaceColor);
            holder.iconContainer.setCardBackgroundColor(chipColor);
            holder.iconImage.setImageResource(item.iconRes);
            holder.iconImage.setColorFilter(accentColor);

            holder.tvName.setText(item.name);
            holder.tvDescription.setTextColor(onSurfaceVariant);
            holder.tvAffect.setTextColor(errorColor);
            holder.tvAffect.setText("拒绝影响：" + item.affect);

            boolean isStorageAutoGranted = Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(item.manifestPermission)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
            if (isStorageAutoGranted) {
                holder.tvDescription.setText(item.description + "（Android 11+ 自动授权）");
            } else {
                holder.tvDescription.setText(item.description);
            }

            int statusColor = item.isGranted ? successColor : errorColor;
            GradientDrawable statusBackground = new GradientDrawable();
            statusBackground.setColor((statusColor & 0x00FFFFFF) | 0x22000000);
            statusBackground.setCornerRadius(dp(context, 10));
            holder.tvStatus.setBackground(statusBackground);
            holder.tvStatus.setTextColor(statusColor);
            holder.tvStatus.setText(item.isGranted ? "已授权" : "未授权");

            if (ALL_FILES_ACCESS.equals(item.manifestPermission)) {
                // 系统页开关式权限：无论有没有授权，都跳系统设置页让用户自己点
                holder.btnAction.setText("去设置");
                holder.btnAction.setTextColor(accentColor);
                holder.btnAction.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
                holder.btnAction.setEnabled(true);
                holder.btnAction.setOnClickListener(v -> openAllFilesAccessPage());
                return;
            }
            if (item.isGranted) {
                holder.btnAction.setText("权限设置");
                holder.btnAction.setTextColor(accentColor);
                holder.btnAction.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
                holder.btnAction.setEnabled(true);
                holder.btnAction.setOnClickListener(v -> openPermissionSettings());
            } else {
                holder.btnAction.setText("去授权");
                holder.btnAction.setTextColor(ThemeManager.getContrastColor(
                        ThemeManager.getThemeColor()));
                holder.btnAction.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(ThemeManager.getThemeColor()));
                holder.btnAction.setEnabled(true);
                holder.btnAction.setOnClickListener(v -> requestPermission(item, position));
            }
        }

        private int dp(android.content.Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView cardView;
            final MaterialCardView iconContainer;
            final ImageView iconImage;
            final TextView tvName;
            final TextView tvDescription;
            final TextView tvAffect;
            final TextView tvStatus;
            final MaterialButton btnAction;

            ViewHolder(View itemView) {
                super(itemView);
                cardView = itemView.findViewById(R.id.cardView);
                iconContainer = itemView.findViewById(R.id.iconContainer);
                iconImage = itemView.findViewById(R.id.permissionIcon);
                tvName = itemView.findViewById(R.id.tvPermissionName);
                tvDescription = itemView.findViewById(R.id.tvPermissionDescription);
                tvAffect = itemView.findViewById(R.id.tvPermissionAffect);
                tvStatus = itemView.findViewById(R.id.tvPermissionStatus);
                btnAction = itemView.findViewById(R.id.btnPermissionAction);
            }
        }
    }
}
