package com.networktool.app.activity.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;
import com.networktool.app.widget.ScanOverlayView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 现代扫一扫页面
 *
 * 全屏相机取景、动态扫描框、手电筒、相册图片识别、历史记录和结果处理。
 */
public class ScanActivity extends AppCompatActivity {

    private static final int MAX_IMAGE_SIZE = 1600;

    private DecoratedBarcodeView barcodeView;
    private CaptureManager captureManager;
    private ImageButton flashButton;
    private ProgressBar scanProgress;
    private File scanHistoryFile;
    private ExecutorService decodeExecutor;

    private boolean torchOn = false;
    private boolean isHandlingResult = false;

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    decodeGalleryImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();
        setContentView(R.layout.activity_scan);
        setupInsets();
        decodeExecutor = Executors.newSingleThreadExecutor();
        initScanHistoryFile();
        initViews();
        setupBarcode(savedInstanceState);
        setupListeners();
    }

    private void setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
    }

    private void setupInsets() {
        View root = findViewById(R.id.scanRoot);
        View topBar = findViewById(R.id.topBar);
        View bottomBar = findViewById(R.id.bottomBar);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(topBar.getPaddingLeft(), bars.top + dp(8),
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), bars.bottom + dp(16));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void initViews() {
        barcodeView = findViewById(R.id.barcode_scanner);
        flashButton = findViewById(R.id.scanFlashButton);
        scanProgress = findViewById(R.id.scanProgress);
    }

    private void initScanHistoryFile() {
        File filesDir = getExternalFilesDir(null);
        if (filesDir != null) {
            scanHistoryFile = new File(filesDir, "扫一扫.json");
        } else {
            scanHistoryFile = new File(getFilesDir(), "扫一扫.json");
        }
    }

    private void setupBarcode(Bundle savedInstanceState) {
        captureManager = new CaptureManager(this, barcodeView);
        captureManager.initializeFromIntent(getIntent(), savedInstanceState);

        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result != null && result.getText() != null) {
                    handleScanResult(result.getText());
                }
            }
        });
        barcodeView.setTorchListener(new DecoratedBarcodeView.TorchListener() {
            @Override
            public void onTorchOn() {
                torchOn = true;
                updateFlashButton();
            }

            @Override
            public void onTorchOff() {
                torchOn = false;
                updateFlashButton();
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.scanBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.scanHistoryButton).setOnClickListener(v ->
                startActivity(new Intent(this, ScanHistoryActivity.class)));
        findViewById(R.id.scanGalleryButton).setOnClickListener(v ->
                galleryLauncher.launch("image/*"));
        flashButton.setOnClickListener(v -> {
            try {
                if (torchOn) {
                    barcodeView.setTorchOff();
                } else {
                    barcodeView.setTorchOn();
                }
            } catch (Exception e) {
                showToast("当前设备不支持手电筒");
            }
        });
        updateFlashButton();
    }

    private void updateFlashButton() {
        if (flashButton == null) return;
        flashButton.setImageResource(torchOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        flashButton.setContentDescription(getString(
                torchOn ? R.string.scan_torch_on : R.string.scan_torch_off));
    }

    private void handleScanResult(String result) {
        if (result == null || result.isEmpty() || isHandlingResult) return;
        isHandlingResult = true;
        try {
            barcodeView.pause();
        } catch (Exception ignored) {
        }
        vibrate();
        saveScanHistory(result);

        if (result.startsWith("http://") || result.startsWith("https://")) {
            showOpenUrlDialog(result);
        } else if (result.toUpperCase(Locale.ROOT).startsWith("WIFI:")) {
            showWifiQrDialog(result);
        } else {
            showScanResultDialog(result);
        }
    }

    private void resumeScanning() {
        if (isFinishing() || isDestroyed() || barcodeView == null) return;
        barcodeView.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                isHandlingResult = false;
                try {
                    barcodeView.resume();
                } catch (Exception ignored) {
                }
            }
        }, 1200);
    }

    private void decodeGalleryImage(Uri uri) {
        if (isHandlingResult) return;
        isHandlingResult = true;
        try {
            barcodeView.pause();
        } catch (Exception ignored) {
        }
        scanProgress.setVisibility(View.VISIBLE);

        decodeExecutor.execute(() -> {
            String text = decodeImage(uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                scanProgress.setVisibility(View.GONE);
                if (text != null && !text.isEmpty()) {
                    isHandlingResult = false;
                    handleScanResult(text);
                } else {
                    showToast(getString(R.string.scan_decode_failed));
                    isHandlingResult = false;
                    resumeScanning();
                }
            });
        });
    }

    private String decodeImage(Uri uri) {
        Bitmap bitmap = null;
        try {
            bitmap = decodeSampledBitmap(uri, MAX_IMAGE_SIZE);
            if (bitmap == null) return null;
            return decodeBitmap(bitmap);
        } catch (Exception e) {
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxSize) {
        ContentResolver resolver = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (Exception e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxSize
                || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        MultiFormatReader reader = new MultiFormatReader();

        int[] angles = {0, 90, 180, 270};
        for (int angle : angles) {
            Bitmap candidate = bitmap;
            try {
                if (angle != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(angle);
                    candidate = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                }
                int[] pixels = new int[candidate.getWidth() * candidate.getHeight()];
                candidate.getPixels(pixels, 0, candidate.getWidth(), 0, 0,
                        candidate.getWidth(), candidate.getHeight());
                RGBLuminanceSource source =
                        new RGBLuminanceSource(candidate.getWidth(), candidate.getHeight(), pixels);
                BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result result = reader.decode(binaryBitmap, hints);
                if (result != null && result.getText() != null) {
                    return result.getText();
                }
            } catch (NotFoundException ignored) {
            } catch (Exception ignored) {
            } finally {
                if (candidate != bitmap && candidate != null && !candidate.isRecycled()) {
                    candidate.recycle();
                }
            }
        }
        return null;
    }

    private void showOpenUrlDialog(String url) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("打开链接")
                .setMessage(url)
                .setPositiveButton("打开", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        showToast("无法打开链接");
                    }
                    finish();
                })
                .setNeutralButton("复制", (dialog, which) -> {
                    copyToClipboard(url);
                    showToast("已复制链接");
                })
                .setNegativeButton("关闭", null)
                .setOnDismissListener(dialog -> resumeScanning())
                .show();
    }

    private void showWifiQrDialog(String raw) {
        Map<String, String> wifi = parseWifiQr(raw);
        String ssid = wifi.containsKey("S") ? wifi.get("S") : "未知 WiFi";
        String password = wifi.containsKey("P") ? wifi.get("P") : "";
        String security = wifiSecurityLabel(wifi.get("T"));

        String message = "WiFi 名称：" + ssid + "\n"
                + "加密方式：" + security + "\n"
                + "密码：" + (password.isEmpty() ? "无密码" : password);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("WiFi 二维码")
                .setMessage(message)
                .setNegativeButton("关闭", null)
                .setNeutralButton("打开 WiFi 设置", (dialog, which) -> openWifiSettings())
                .setOnDismissListener(dialog -> resumeScanning());

        if (password.isEmpty()) {
            builder.setPositiveButton("复制名称", (dialog, which) -> {
                copyToClipboard(ssid);
                showToast("已复制 WiFi 名称");
            });
        } else {
            builder.setPositiveButton("复制密码", (dialog, which) -> {
                copyToClipboard(password);
                showToast("已复制 WiFi 密码");
            });
        }
        builder.show();
    }

    private void showScanResultDialog(String result) {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottomsheet_scan_result, null);
        TextView resultText = sheetView.findViewById(R.id.resultText);
        com.google.android.material.button.MaterialButton copyButton =
                sheetView.findViewById(R.id.copyButton);
        com.google.android.material.button.MaterialButton closeButton =
                sheetView.findViewById(R.id.closeButton);

        resultText.setText(result);
        copyButton.setOnClickListener(v -> {
            copyToClipboard(result);
            showToast("已复制到剪贴板");
            bottomSheet.dismiss();
        });
        closeButton.setOnClickListener(v -> bottomSheet.dismiss());
        bottomSheet.setOnDismissListener(dialog -> resumeScanning());
        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    private Map<String, String> parseWifiQr(String raw) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (raw == null || raw.length() <= 5) return result;
        String body = raw.substring(5);
        List<String> fields = splitWifiFields(body);
        for (String field : fields) {
            int index = field.indexOf(':');
            if (index > 0) {
                String key = field.substring(0, index).trim().toUpperCase(Locale.ROOT);
                String value = field.substring(index + 1);
                result.put(key, value);
            }
        }
        return result;
    }

    private List<String> splitWifiFields(String body) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ';') {
                if (current.length() > 0) {
                    fields.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            fields.add(current.toString());
        }
        return fields;
    }

    private String wifiSecurityLabel(String type) {
        if (type == null || type.isEmpty() || "nopass".equalsIgnoreCase(type)) {
            return "开放网络";
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains("SAE") || upper.contains("WPA3")) return "WPA3";
        if (upper.contains("WEP")) return "WEP";
        if (upper.contains("WPA")) return "WPA/WPA2";
        return type;
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            showToast("无法打开 WiFi 设置");
        }
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(
                            120, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(120);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void copyToClipboard(String text) {
        if (text == null) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Scan Result", text));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveScanHistory(String result) {
        if (scanHistoryFile == null || result == null) return;
        try {
            JSONArray scanData = new JSONArray();
            if (scanHistoryFile.exists()) {
                StringBuilder jsonStr = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(scanHistoryFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonStr.append(line);
                    }
                }
                if (jsonStr.length() > 0) {
                    scanData = new JSONArray(jsonStr.toString());
                }
            }

            JSONObject newEntry = new JSONObject();
            newEntry.put("title", result);
            JSONObject content = new JSONObject();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()).format(new Date());
            content.put("text", timestamp);
            content.put("textColor", ThemeManager.getThemeColor());
            newEntry.put("content", content);
            scanData.put(newEntry);

            try (FileWriter writer = new FileWriter(scanHistoryFile)) {
                writer.write(scanData.toString());
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    private void showToast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setOverlayPaused(false);
        if (captureManager != null) {
            captureManager.onResume();
        }
        if (isHandlingResult && barcodeView != null) {
            try {
                barcodeView.pause();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        setOverlayPaused(true);
        if (captureManager != null) {
            captureManager.onPause();
        }
    }

    private void setOverlayPaused(boolean paused) {
        if (barcodeView != null && barcodeView.getViewFinder() instanceof ScanOverlayView) {
            ((ScanOverlayView) barcodeView.getViewFinder()).setPaused(paused);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (captureManager != null) {
            captureManager.onDestroy();
        }
        if (decodeExecutor != null) {
            decodeExecutor.shutdownNow();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (captureManager != null) {
            captureManager.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (captureManager != null) {
            captureManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            showToast("需要相机权限才能使用扫一扫");
        }
    }
}
