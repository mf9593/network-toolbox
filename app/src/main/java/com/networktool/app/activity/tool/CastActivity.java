package com.networktool.app.activity.tool;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.activity.BaseToolActivity;
import com.networktool.app.cast.CastFileServer;
import com.networktool.app.cast.DlnaController;
import com.networktool.app.cast.DlnaDevice;
import com.networktool.app.cast.SsdpDiscovery;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 投屏页：搜索局域网内的 DLNA 电视，把本地图片 / 视频或在线链接投上去。
 */
public class CastActivity extends BaseToolActivity {

    private static final String TAG = "CastActivity";

    private LinearLayout deviceListLayout;
    private TextView deviceStatusText;
    private TextView localFileText;
    private TextView statusText;
    private ProgressBar searchProgress;
    private TextInputEditText urlEditText;
    private MaterialButton searchButton;
    private MaterialButton castButton;

    private final List<DlnaDevice> devices = new ArrayList<>();
    private DlnaDevice selectedDevice;
    private SsdpDiscovery discovery;
    private CastFileServer fileServer;
    private File localFile;
    private String localFileType;
    private String localFileName;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_cast);
            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            setupToolbar(toolbar, "投屏");
            initViews();
            applyTheme();
            String prefill = getIntent() == null ? null : getIntent().getStringExtra("cast_url");
            if (prefill != null && !prefill.isEmpty()) {
                urlEditText.setText(prefill);
                statusText.setText("已带入直播源链接，选择设备后点击「开始投屏」");
            }
            startSearch();
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "投屏页初始化失败", e);
            Toast.makeText(this, "页面初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initViews() {
        deviceListLayout = findViewById(R.id.deviceListLayout);
        deviceStatusText = findViewById(R.id.deviceStatusText);
        localFileText = findViewById(R.id.localFileText);
        statusText = findViewById(R.id.statusText);
        searchProgress = findViewById(R.id.searchProgress);
        urlEditText = findViewById(R.id.urlEditText);
        searchButton = findViewById(R.id.searchButton);
        castButton = findViewById(R.id.castButton);

        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> handlePickedFile(uri));
        videoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> handlePickedFile(uri));

        searchButton.setOnClickListener(v -> startSearch());
        findViewById(R.id.pickImageButton).setOnClickListener(v -> {
            try {
                imagePicker.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.pickVideoButton).setOnClickListener(v -> {
            try {
                videoPicker.launch("video/*");
            } catch (Exception e) {
                Toast.makeText(this, "无法打开视频选择器", Toast.LENGTH_SHORT).show();
            }
        });
        castButton.setOnClickListener(v -> doCast());
        findViewById(R.id.stopButton).setOnClickListener(v -> doStop());
    }

    private void applyTheme() {
        int onSurface = ThemeManager.getOnSurfaceColor(this);
        int muted = ThemeManager.getOnSurfaceVariantColor(this);
        int accent = ThemeManager.getAccessibleThemeColor(this);

        MaterialCardView deviceCard = findViewById(R.id.deviceCard);
        MaterialCardView contentCard = findViewById(R.id.contentCard);
        ThemeManager.setupResultCard(deviceCard, this);
        ThemeManager.setupResultCard(contentCard, this);

        TextView deviceHeader = findViewById(R.id.deviceHeader);
        TextView contentHeader = findViewById(R.id.contentHeader);
        deviceHeader.setTextColor(onSurface);
        contentHeader.setTextColor(onSurface);
        deviceStatusText.setTextColor(muted);
        localFileText.setTextColor(muted);
        statusText.setTextColor(muted);
        searchProgress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(accent));

        ThemeManager.applyPrimaryButton(searchButton);
        ThemeManager.applyPrimaryButton(castButton);
        ((MaterialButton) findViewById(R.id.pickImageButton)).setTextColor(accent);
        ((MaterialButton) findViewById(R.id.pickVideoButton)).setTextColor(accent);
        ((MaterialButton) findViewById(R.id.stopButton)).setTextColor(accent);
    }

    /* ==================== 设备搜索 ==================== */

    private void startSearch() {
        try {
            if (discovery != null) discovery.stop();
            devices.clear();
            selectedDevice = null;
            deviceListLayout.removeAllViews();
            deviceStatusText.setText("正在搜索局域网内的 DLNA 设备…");
            searchProgress.setVisibility(View.VISIBLE);
            searchButton.setEnabled(false);

            discovery = new SsdpDiscovery(this);
            discovery.start(new SsdpDiscovery.Callback() {
                @Override
                public void onDeviceFound(DlnaDevice device) {
                    runOnUiThread(() -> addDevice(device));
                }

                @Override
                public void onFinished() {
                    runOnUiThread(() -> {
                        searchProgress.setVisibility(View.GONE);
                        searchButton.setEnabled(true);
                        deviceStatusText.setText(devices.isEmpty()
                                ? "未找到设备，请确认电视已开机并和手机在同一 WiFi"
                                : "找到 " + devices.size() + " 个设备，点击选择");
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        searchProgress.setVisibility(View.GONE);
                        searchButton.setEnabled(true);
                        deviceStatusText.setText("搜索异常：" + message);
                    });
                }
            });
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "启动投屏设备搜索失败", e);
            deviceStatusText.setText("搜索失败：" + e.getMessage());
        }
    }

    private void addDevice(DlnaDevice device) {
        for (DlnaDevice exist : devices) {
            if (exist.udn != null && exist.udn.equals(device.udn)) return;
            if (exist.controlUrl != null && exist.controlUrl.equals(device.controlUrl)) return;
        }
        devices.add(device);
        deviceStatusText.setText("已找到 " + devices.size() + " 个设备");

        View row = LayoutInflater.from(this).inflate(R.layout.item_cast_device,
                deviceListLayout, false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Math.round(6 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(rowParams);
        TextView name = row.findViewById(R.id.deviceName);
        TextView sub = row.findViewById(R.id.deviceSub);
        TextView check = row.findViewById(R.id.deviceCheck);
        ImageView icon = row.findViewById(R.id.deviceIcon);

        name.setText(device.getDisplayName());
        sub.setText(device.getSubTitle());
        name.setTextColor(ThemeManager.getOnSurfaceColor(this));
        sub.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));
        check.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                ThemeManager.getAccessibleThemeColor(this)));
        row.setTag(device);
        row.setOnClickListener(v -> selectDevice(device));
        deviceListLayout.addView(row);
        if (selectedDevice == null) {
            selectDevice(device);
        } else {
            applyRowHighlight();
        }
    }

    private void selectDevice(DlnaDevice device) {
        selectedDevice = device;
        applyRowHighlight();
        statusText.setText("已选择设备：" + device.getDisplayName());
    }

    private void applyRowHighlight() {
        int accent = ThemeManager.getAccessibleThemeColor(this);
        int chipSurface = ThemeManager.getChipSurfaceColor(this);
        int outline = ThemeManager.getOutlineVariantColor(this);
        int radius = Math.round(14 * getResources().getDisplayMetrics().density);
        int stroke = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        for (int i = 0; i < deviceListLayout.getChildCount(); i++) {
            View row = deviceListLayout.getChildAt(i);
            Object tag = row.getTag();
            boolean selected = tag == selectedDevice;
            TextView check = row.findViewById(R.id.deviceCheck);
            check.setVisibility(selected ? View.VISIBLE : View.GONE);
            // 未选中也给同样的圆角底，避免「有的圆角有的方角」
            android.graphics.drawable.GradientDrawable background =
                    new android.graphics.drawable.GradientDrawable();
            if (selected) {
                background.setColor((accent & 0x00FFFFFF) | 0x22 << 24);
                background.setStroke(stroke, (accent & 0x00FFFFFF) | 0x66 << 24);
            } else {
                background.setColor(chipSurface);
                background.setStroke(stroke, (outline & 0x00FFFFFF) | 0x33 << 24);
            }
            background.setCornerRadius(radius);
            row.setBackground(background);
        }
    }

    /* ==================== 本地文件 ==================== */

    private void handlePickedFile(Uri uri) {
        if (uri == null) return;
        localFileText.setText("正在准备文件…");
        executor.execute(() -> {
            try {
                String displayName = queryDisplayName(uri);
                String type = getContentResolver().getType(uri);
                if (type == null) type = CastFileServer.guessContentType(displayName);
                File target = new File(getCacheDir(),
                        "cast_" + System.currentTimeMillis() + "_" + sanitize(displayName));
                try (InputStream input = getContentResolver().openInputStream(uri);
                     OutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IllegalStateException("无法读取所选文件");
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                fileServer = fileServer == null ? new CastFileServer() : fileServer;
                int port = fileServer.start(target, type, sanitize(displayName));
                String url = fileServer.getUrl(CastFileServer.getLocalIpAddress());
                localFile = target;
                localFileType = type;
                localFileName = displayName;
                runOnUiThread(() -> {
                    localFileText.setText("已选择：" + displayName + "\n局域网地址：" + url);
                    urlEditText.setText("");
                    statusText.setText("文件已就绪（HTTP 端口 " + port + "），点击「开始投屏」");
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "准备投屏文件失败", e);
                runOnUiThread(() -> localFileText.setText("准备文件失败：" + e.getMessage()));
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = "media_" + System.currentTimeMillis();
        }
        return name;
    }

    private String sanitize(String name) {
        if (name == null || name.isEmpty()) return "media";
        return name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
    }

    /* ==================== 投屏控制 ==================== */

    private void doCast() {
        if (selectedDevice == null) {
            Toast.makeText(this, "请先选择投屏设备", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = urlEditText.getText() == null ? "" : urlEditText.getText().toString().trim();
        final String finalUrl;
        final String title;
        final DlnaController.MediaType mediaType;

        if (!url.isEmpty()) {
            finalUrl = url;
            title = "网络工具箱投屏";
            mediaType = DlnaController.MediaType.VIDEO;
        } else if (localFile != null && fileServer != null && fileServer.isRunning()) {
            finalUrl = fileServer.getUrl(CastFileServer.getLocalIpAddress());
            title = localFileName == null ? "网络工具箱投屏" : localFileName;
            mediaType = CastFileServer.isImageType(localFileType)
                    ? DlnaController.MediaType.IMAGE : DlnaController.MediaType.VIDEO;
        } else {
            Toast.makeText(this, "请输入链接，或先选择本地图片/视频", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("正在投屏到 " + selectedDevice.getDisplayName() + " …");
        castButton.setEnabled(false);
        executor.execute(() -> {
            String error = DlnaController.cast(selectedDevice, finalUrl, title, mediaType);
            runOnUiThread(() -> {
                castButton.setEnabled(true);
                if (error == null) {
                    statusText.setText("投屏指令已发送 ✔\n设备：" + selectedDevice.getDisplayName()
                            + "\n地址：" + finalUrl);
                } else {
                    statusText.setText("投屏失败：" + error
                            + "\n提示：电视可能不支持该格式，或需要先打开投屏/多屏互动功能。");
                }
            });
        });
    }

    private void doStop() {
        if (selectedDevice == null) {
            Toast.makeText(this, "还没有投屏设备", Toast.LENGTH_SHORT).show();
            return;
        }
        DlnaDevice device = selectedDevice;
        executor.execute(() -> {
            String error = DlnaController.stop(device);
            runOnUiThread(() -> statusText.setText(error == null
                    ? "已停止投屏" : "停止失败：" + error));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (discovery != null) discovery.stop();
        } catch (Exception ignored) {
        }
        try {
            if (fileServer != null) fileServer.stop();
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
    }

    /** 供其他页面跳转使用的构造方法 */
    public static Intent createIntent(android.content.Context context, String url) {
        Intent intent = new Intent(context, CastActivity.class);
        if (url != null && !url.isEmpty()) {
            intent.putExtra("cast_url", url);
        }
        return intent;
    }
}
