package com.networktool.app.activity.tool;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.util.WifiDataManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * WiFi数据导入Activity
 * 
 * 支持从文件导入WiFi数据：
 * - .json 加密格式
 * - .bak 备份文件
 * - .zip 压缩包
 * - wpa_supplicant配置文件
 * - vivo格式备份
 */
public class WifiImportActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_OPEN_FILE = 1001;
    private static final int REQUEST_CODE_CUSTOM_FILE = 1002;
    private static final int REQUEST_CODE_READ_STORAGE = 1003;

    private MaterialToolbar toolbar;
    private MaterialButton importButton;
    private MaterialButton networkImportButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView resultText;

    private WifiDataManager wifiDataManager;
    private ExecutorService executorService;
    private boolean pendingCustomPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_import);

        setupStatusBar();
        setupToolbar();

        wifiDataManager = WifiDataManager.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        initViews();
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
     * 设置工具栏主题
     */
    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void initViews() {
        importButton = findViewById(R.id.importButton);
        networkImportButton = findViewById(R.id.networkImportButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);

        MaterialCardView hintCard = findViewById(R.id.hintCard);
        ThemeManager.setupResultCard(hintCard, this);

        ThemeManager.applyPrimaryButton(importButton);
        ThemeManager.applyPrimaryButton(networkImportButton);
        resultText.setTextColor(ThemeManager.getOnSurfaceVariantColor(this));
        importButton.setOnClickListener(v -> openFilePicker());
        networkImportButton.setOnClickListener(v -> showNetworkImportDialog());
    }

    private void showNetworkImportDialog() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/wifi_data.json");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new MaterialAlertDialogBuilder(this)
                .setTitle("从网络导入")
                .setMessage("输入WiFi数据文件的URL地址")
                .setView(input)
                .setPositiveButton("导入", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        importFromNetwork(url);
                    } else {
                        Toast.makeText(this, "请输入URL", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importFromNetwork(String urlStr) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("正在从网络获取数据...");
        resultText.setVisibility(View.GONE);
        importButton.setEnabled(false);
        networkImportButton.setEnabled(false);

        executorService.execute(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setText("网络请求失败: HTTP " + responseCode);
                        importButton.setEnabled(true);
                        networkImportButton.setEnabled(true);
                        Toast.makeText(this, "服务器返回: " + responseCode, Toast.LENGTH_SHORT).show();
                    });
                    conn.disconnect();
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                conn.disconnect();

                String content = sb.toString();
                if (content.isEmpty()) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setText("获取的内容为空");
                        importButton.setEnabled(true);
                        networkImportButton.setEnabled(true);
                        Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 解析WiFi数据
                Map<String, List<String>> parsedData = wifiDataManager.parseWifiData(content);

                if (parsedData.isEmpty()) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setText("未识别的文件格式");
                        importButton.setEnabled(true);
                        networkImportButton.setEnabled(true);
                        Toast.makeText(this, "未找到有效的WiFi数据", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 导入数据
                wifiDataManager.importData(parsedData);

                int wifiCount = parsedData.size();
                int totalCount = 0;
                for (List<String> passwords : parsedData.values()) {
                    totalCount += passwords.size();
                }

                int finalTotalCount = totalCount;
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("导入完成！");
                    resultText.setVisibility(View.VISIBLE);
                    resultText.setText(String.format("成功导入 %d 个WiFi网络，共 %d 条记录", wifiCount, finalTotalCount));
                    importButton.setEnabled(true);
                    networkImportButton.setEnabled(true);
                    Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("导入失败: " + e.getMessage());
                    importButton.setEnabled(true);
                    networkImportButton.setEnabled(true);
                    Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 打开发送文件选择。Android 11+ 使用应用内文件浏览器，避免系统
     * DocumentsUI 将 .xml/.bak 等文件过滤掉；未授权时提供系统选择器兜底。
     */
    private void openFilePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                openCustomFilePicker();
            } else {
                showAllFilesPermissionDialog();
            }
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            openCustomFilePicker();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_READ_STORAGE);
        }
    }

    private void openCustomFilePicker() {
        Intent intent = new Intent(this, WifiFilePickerActivity.class);
        startActivityForResult(intent, REQUEST_CODE_CUSTOM_FILE);
    }

    private void showAllFilesPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("需要文件访问权限")
                .setMessage("应用内文件浏览器需要“所有文件访问权限”，才能直接浏览 Download 等目录中的 JSON、XML、BAK、ZIP 备份文件。\n\n"
                        + "如果不想授权，也可以继续使用系统文件选择器。")
                .setPositiveButton("去授权", (dialog, which) -> {
                    pendingCustomPicker = true;
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ex) {
                            pendingCustomPicker = false;
                            Toast.makeText(this, "无法打开权限设置，请使用系统文件选择器",
                                    Toast.LENGTH_LONG).show();
                            openSystemFilePicker();
                        }
                    }
                })
                .setNeutralButton("系统选择器", (dialog, which) -> openSystemFilePicker())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openSystemFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 不再限制 MIME，确保 .xml、.bak 和没有扩展名的备份文件也能选中
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_CODE_OPEN_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_READ_STORAGE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCustomFilePicker();
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("无法读取文件")
                        .setMessage("没有存储读取权限，是否改用系统文件选择器？")
                        .setPositiveButton("使用系统选择器",
                                (dialog, which) -> openSystemFilePicker())
                        .setNegativeButton("取消", null)
                        .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingCustomPicker) {
            pendingCustomPicker = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()) {
                openCustomFilePicker();
            } else {
                Toast.makeText(this, "未获得所有文件访问权限，可继续使用系统选择器",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }

        if (requestCode == REQUEST_CODE_CUSTOM_FILE) {
            String path = data.getStringExtra(WifiFilePickerActivity.EXTRA_FILE_PATH);
            if (path != null && !path.isEmpty()) {
                importWifiData(Uri.fromFile(new File(path)));
            }
        } else if (requestCode == REQUEST_CODE_OPEN_FILE) {
            Uri uri = data.getData();
            if (uri != null) {
                importWifiData(uri);
            }
        }
    }

    /**
     * 导入WiFi数据
     */
    private void importWifiData(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("正在解析文件...");
        resultText.setVisibility(View.GONE);
        importButton.setEnabled(false);

        executorService.execute(() -> {
            try {
                // 读取文件内容
                String content = readFileContent(uri);
                
                if (content == null || content.isEmpty()) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setText("文件读取失败");
                        importButton.setEnabled(true);
                        Toast.makeText(this, "文件读取失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 解析WiFi数据
                Map<String, List<String>> parsedData = wifiDataManager.parseWifiData(content);

                if (parsedData.isEmpty()) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setText("未找到WiFi数据");
                        importButton.setEnabled(true);
                        Toast.makeText(this, "未找到有效的WiFi数据", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 导入数据
                wifiDataManager.importData(parsedData);

                // 统计结果
                int wifiCount = parsedData.size();
                int totalCount = 0;
                for (List<String> passwords : parsedData.values()) {
                    totalCount += passwords.size();
                }

                int finalTotalCount = totalCount;
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("导入完成！");
                    resultText.setVisibility(View.VISIBLE);
                    resultText.setText(String.format("成功导入 %d 个WiFi网络，共 %d 条记录", wifiCount, finalTotalCount));
                    importButton.setEnabled(true);
                    Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("导入失败: " + e.getMessage());
                    importButton.setEnabled(true);
                    Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 读取文件内容
     */
    private String readFileContent(Uri uri) {
        try {
            InputStream inputStream;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                inputStream = new FileInputStream(new File(uri.getPath()));
            } else {
                inputStream = getContentResolver().openInputStream(uri);
            }
            if (inputStream == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 32 * 1024 * 1024) {
                    break;
                }
            }
            inputStream.close();
            byte[] bytes = buffer.toByteArray();
            if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
                return readZipContent(bytes);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 读取 ZIP 内的文本/备份文件内容。MIUI 的 WiFi 备份通常是 zip 内嵌 .bak。
     */
    private String readZipContent(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        File tempFile = null;
        try {
            // 部分 MIUI 备份 ZIP 使用 ZIP64 扩展头，ZipInputStream 会把本地
            // 头中的 0 当作真实大小并抛出 invalid entry size。先写入临时文件，
            // 再用 ZipFile 读取，可兼容 ZIP64 和普通 ZIP。
            tempFile = File.createTempFile("wifi_import_", ".zip", getCacheDir());
            try (FileOutputStream output = new FileOutputStream(tempFile)) {
                output.write(bytes);
            }
            try (ZipFile zip = new ZipFile(tempFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                byte[] buffer = new byte[8192];
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry == null || entry.isDirectory()) continue;
                    String name = entry.getName() == null ? "" : entry.getName().toLowerCase();
                    if (name.endsWith(".zip")) continue;
                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                    try (InputStream input = zip.getInputStream(entry)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            entryBuffer.write(buffer, 0, read);
                        }
                    }
                    String text = new String(entryBuffer.toByteArray(), StandardCharsets.UTF_8);
                    if (result.length() > 0) result.append("\n");
                    result.append(text);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (tempFile != null) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
        return result.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
