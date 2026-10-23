package com.networktool.app.activity.tool;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButtonToggleGroup;

import com.caverock.androidsvg.SVG;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.SvgRenderer;
import com.networktool.app.util.ThemeManager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IconConverterActivity extends AppCompatActivity {

    private static final String TAG = "IconConverterActivity";

    private MaterialCardView editorCard, previewCard, colorCard;
    private MaterialButtonToggleGroup modeToggleGroup;
    private EditText codeEditText;
    private MaterialButton previewButton, formatButton;
    private MaterialButton exportSvgButton, exportXmlButton, exportPngButton;
    private MaterialButton copySvgButton, copyXmlButton;
    private MaterialButton clearInputButton, copyInputButton;
    private ImageView previewImageView;
    private TextView previewHintTextView;
    private LinearLayout colorListLayout;
    private TextView colorHintTextView;

    private boolean isXmlMode = true;
    private final List<ColorInfo> detectedColors = new ArrayList<>();
    private String pendingExportFormat;
    private int pendingExportWidth, pendingExportHeight;
    private boolean flipH, flipV;
    private float rotateDeg;

    private final ActivityResultLauncher<String> exportTextLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null) return;
                try {
                    if ("svg".equals(pendingExportFormat)) doExportSvg(uri);
                    else doExportXml(uri);
                } catch (Throwable t) {
                    AppErrorLogger.catchThrowable(TAG, "导出失败", t);
                    Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> exportPngLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("image/png"), uri -> {
                if (uri == null) return;
                try {
                    doExportPng(uri);
                } catch (Throwable t) {
                    AppErrorLogger.catchThrowable(TAG, "导出失败", t);
                    Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    private static class ColorInfo {
        String originalText;
        String colorValue;
        int startPos, endPos;

        ColorInfo(String originalText, String colorValue, int startPos, int endPos) {
            this.originalText = originalText;
            this.colorValue = colorValue;
            this.startPos = startPos;
            this.endPos = endPos;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_icon_converter);
            setupStatusBar();
            initViews();
            setupToolbar();
            setupListeners();
            applyThemeColors();
            loadSampleXml();
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "初始化失败", t);
            Toast.makeText(this, "初始化失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initViews() {
        editorCard = findViewById(R.id.editorCard);
        previewCard = findViewById(R.id.previewCard);
        colorCard = findViewById(R.id.colorCard);
        modeToggleGroup = findViewById(R.id.modeToggleGroup);
        codeEditText = findViewById(R.id.codeEditText);
        previewButton = findViewById(R.id.previewButton);
        formatButton = findViewById(R.id.formatButton);
        exportSvgButton = findViewById(R.id.exportSvgButton);
        exportXmlButton = findViewById(R.id.exportXmlButton);
        exportPngButton = findViewById(R.id.exportPngButton);
        previewImageView = findViewById(R.id.previewImageView);
        previewHintTextView = findViewById(R.id.previewHintTextView);
        colorListLayout = findViewById(R.id.colorListLayout);
        colorHintTextView = findViewById(R.id.colorHintTextView);
        copySvgButton = findViewById(R.id.copySvgButton);
        copyXmlButton = findViewById(R.id.copyXmlButton);
        clearInputButton = findViewById(R.id.clearInputButton);
        copyInputButton = findViewById(R.id.copyInputButton);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("图标转换");
        }
        int tc = ThemeManager.getThemeColor();
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
    }

    private void setupListeners() {
        modeToggleGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) {
                isXmlMode = id == R.id.modeXml;
                Toast.makeText(this, isXmlMode ? "XML模式" : "SVG模式", Toast.LENGTH_SHORT).show();
            }
        });
        previewButton.setOnClickListener(v -> renderPreview());
        formatButton.setOnClickListener(v -> formatCode());
        exportSvgButton.setOnClickListener(v -> safeExport("svg"));
        exportXmlButton.setOnClickListener(v -> safeExport("xml"));
        exportPngButton.setOnClickListener(v -> safeExport("png"));
        copySvgButton.setOnClickListener(v -> copyToClipboard("svg"));
        copyXmlButton.setOnClickListener(v -> copyToClipboard("xml"));
        clearInputButton.setOnClickListener(v -> {
            codeEditText.setText("");
            previewImageView.setImageDrawable(null);
            previewHintTextView.setVisibility(View.VISIBLE);
            previewHintTextView.setText("请输入代码后点击预览");
        });
        copyInputButton.setOnClickListener(v -> {
            String text = codeEditText.getText().toString();
            if (text.isEmpty()) return;
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("icon_code", text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        codeEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { detectColors(); }
        });
        setupTransformButtons();
    }

    private void setupTransformButtons() {
        findViewById(R.id.flipHButton).setOnClickListener(v -> {
            flipH = !flipH;
            v.setActivated(flipH);
            renderPreview();
        });
        findViewById(R.id.flipVButton).setOnClickListener(v -> {
            flipV = !flipV;
            v.setActivated(flipV);
            renderPreview();
        });
        findViewById(R.id.rotateLeftButton).setOnClickListener(v -> {
            rotateDeg = (rotateDeg + 270) % 360;
            renderPreview();
        });
        findViewById(R.id.rotateRightButton).setOnClickListener(v -> {
            rotateDeg = (rotateDeg + 90) % 360;
            renderPreview();
        });
        findViewById(R.id.rotate180Button).setOnClickListener(v -> {
            rotateDeg = (rotateDeg + 180) % 360;
            renderPreview();
        });
        findViewById(R.id.resetTransformButton).setOnClickListener(v -> {
            flipH = false;
            flipV = false;
            rotateDeg = 0;
            ((MaterialButton)findViewById(R.id.flipHButton)).setActivated(false);
            ((MaterialButton)findViewById(R.id.flipVButton)).setActivated(false);
            renderPreview();
        });
    }

    private void applyThemeColors() {
        int tc = ThemeManager.getThemeColor();
        int cc = ThemeManager.getContrastColor(tc);
        MaterialButton flipHBtn = findViewById(R.id.flipHButton);
        MaterialButton flipVBtn = findViewById(R.id.flipVButton);
        MaterialButton rotateLeftBtn = findViewById(R.id.rotateLeftButton);
        MaterialButton rotateRightBtn = findViewById(R.id.rotateRightButton);
        MaterialButton rotate180Btn = findViewById(R.id.rotate180Button);
        MaterialButton resetTransformBtn = findViewById(R.id.resetTransformButton);
        for (MaterialButton btn : new MaterialButton[]{previewButton, formatButton, exportSvgButton, exportXmlButton, exportPngButton, copySvgButton, copyXmlButton, clearInputButton, copyInputButton, flipHBtn, flipVBtn, rotateLeftBtn, rotateRightBtn, rotate180Btn, resetTransformBtn}) {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tc));
            btn.setTextColor(cc);
        }
        // Toggle 按钮：选中项背景跟随主题色，文字颜色跟随对比色
        for (int i = 0; i < modeToggleGroup.getChildCount(); i++) {
            View v = modeToggleGroup.getChildAt(i);
            if (v instanceof MaterialButton) {
                MaterialButton btn = (MaterialButton) v;
                btn.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                    },
                    new int[]{cc, 0xFF888888}
                ));
                btn.setBackgroundTintList(new android.content.res.ColorStateList(
                    new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                    },
                    new int[]{tc, android.graphics.Color.TRANSPARENT}
                ));
                btn.setStrokeColor(new android.content.res.ColorStateList(
                    new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                    },
                    new int[]{tc, tc}
                ));
            }
        }
        ThemeManager.setupResultCard(editorCard, this);
        ThemeManager.setupResultCard(previewCard, this);
        ThemeManager.setupResultCard((com.google.android.material.card.MaterialCardView)findViewById(R.id.transformCard), this);
        ThemeManager.setupResultCard(colorCard, this);
    }

    private void safeExport(String format) {
        long ts = System.currentTimeMillis();
        if ("png".equals(format)) {
            showResolutionPicker(() -> {
                pendingExportFormat = format;
                exportPngLauncher.launch("icon_" + ts + ".png");
            });
            return;
        }
        pendingExportFormat = format;
        exportTextLauncher.launch("icon_" + ts + "." + format);
    }

    private void showResolutionPicker(Runnable onSelected) {
        String[] labels = {"128×128", "256×256", "512×512", "1024×1024", "2048×2048"};
        int[][] sizes = {{128,128}, {256,256}, {512,512}, {1024,1024}, {2048,2048}};
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("选择导出分辨率");
        b.setItems(labels, (dialog, which) -> {
            pendingExportWidth = sizes[which][0];
            pendingExportHeight = sizes[which][1];
            onSelected.run();
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void loadSampleXml() {
        try {
            codeEditText.setText(getString(R.string.sample_vector_icon));
            renderPreview();
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "加载示例失败", t);
        }
    }

    private void formatCode() {
        String text = codeEditText.getText().toString();
        if (text.isEmpty()) return;
        codeEditText.setText(text.replaceAll(">\\s+<", ">\n<").replaceAll("/>\\s+", "/>\n"));
    }

    // ============ 预览 ============

    private void renderPreview() {
        String input = codeEditText.getText().toString().trim();
        if (input.isEmpty()) {
            previewHintTextView.setVisibility(View.VISIBLE);
            previewHintTextView.setText("请输入代码后点击预览");
            previewImageView.setImageDrawable(null);
            return;
        }
        try {
            Drawable d = renderVectorDrawable(input);
            previewImageView.setImageDrawable(d);
            previewHintTextView.setVisibility(View.GONE);
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "预览渲染失败", t);
            previewHintTextView.setVisibility(View.VISIBLE);
            String msg = t.getMessage();
            previewHintTextView.setText(msg != null ? msg : "未知渲染错误");
        }
    }

    private Drawable renderVectorDrawable(String input) throws Exception {
        if (isXmlMode) {
            return renderXmlDrawable(input);
        }
        return renderSvgWithLibrary(input);
    }

    private String stripRootSvgTransform(String svg) {
        if (svg == null) return null;
        try {
            Matcher matcher = Pattern.compile("<svg\\b[^>]*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(svg);
            if (!matcher.find()) return svg;
            String tag = matcher.group();
            String cleaned = tag.replaceAll(
                    "(?i)\\s+transform\\s*=\\\"[^\\\"]*\\\"", "");
            return svg.substring(0, matcher.start()) + cleaned + svg.substring(matcher.end());
        } catch (Exception e) {
            return svg;
        }
    }

    private Drawable renderSvgWithLibrary(String svgContent) throws Exception {
        String sanitized = svgContent;
        // 将 currentColor 替换为 #000000（AndroidSVG 不支持 currentColor）
        if (sanitized.contains("currentColor"))
            sanitized = sanitized.replace("currentColor", "#000000");
        // 只移除根 <svg> 上的 transform，保留子元素/分组 transform
        sanitized = stripRootSvgTransform(sanitized);
        AppErrorLogger.logDebug(TAG, "sanitized=" + sanitized);
        SVG svg;
        try {
            svg = SVG.getFromString(sanitized);
        } catch (Exception e) {
            AppErrorLogger.catchThrowable(TAG, "AndroidSVG 解析失败", e);
            throw new Exception("SVG解析失败: " + e.getMessage());
        }
        float docW, docH;
        try {
            docW = svg.getDocumentWidth();
            docH = svg.getDocumentHeight();
        } catch (Exception e) {
            AppErrorLogger.catchThrowable(TAG, "AndroidSVG 文档空", e);
            docW = 24; docH = 24;
        }
        if (docW <= 0 || docH <= 0) { docW = 24; docH = 24; }
        int maxSize = dp(200);
        float scale = Math.min((float) maxSize / docW, (float) maxSize / docH);
        int bw = Math.round(docW * scale);
        int bh = Math.round(docH * scale);
        if (bw < 1) bw = 1;
        if (bh < 1) bh = 1;
        AppErrorLogger.logDebug(TAG, "bmp=" + bw + "x" + bh);
        svg.setDocumentWidth(Integer.toString(bw));
        svg.setDocumentHeight(Integer.toString(bh));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        svg.renderToCanvas(canvas);
        if (flipH || flipV || rotateDeg != 0) {
            Matrix m = new Matrix();
            m.postTranslate(-bw / 2f, -bh / 2f);
            if (flipH) m.postScale(-1, 1);
            if (flipV) m.postScale(1, -1);
            if (rotateDeg != 0) m.postRotate(rotateDeg);
            m.postTranslate(bw / 2f, bh / 2f);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bw, bh, m, true);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private Drawable renderXmlDrawable(String text) throws Exception {
        int vw = 24, vh = 24;
        Matcher vm = Pattern.compile("viewportWidth\\s*=\\\"([^\\\"]+)\\\"").matcher(text);
        if (vm.find()) vw = (int) Float.parseFloat(vm.group(1));
        vm = Pattern.compile("viewportHeight\\s*=\\\"([^\\\"]+)\\\"").matcher(text);
        if (vm.find()) vh = (int) Float.parseFloat(vm.group(1));
        if (vw <= 0) vw = 24;
        if (vh <= 0) vh = 24;

        int maxSize = dp(200);
        float scale = Math.min((float) maxSize / vw, (float) maxSize / vh);
        int bw = Math.round(vw * scale);
        int bh = Math.round(vh * scale);
        if (bw < 1) bw = 1;
        if (bh < 1) bh = 1;

        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.scale((float) bw / vw, (float) bh / vh);

        // 同时解析 <group> 与 <path>，保留分组位移/缩放/旋转，避免有分组的图标预览错位
        Matcher pm = Pattern.compile(
                "<group\\b([^>]*)>|</group>|<path\\s+([^>]*)/?>",
                Pattern.CASE_INSENSITIVE).matcher(text);
        int groupDepth = 0;
        while (pm.find()) {
            if (pm.group(1) != null) {
                canvas.save();
                applyXmlGroup(canvas, pm.group(1));
                groupDepth++;
                continue;
            }
            if (pm.group().startsWith("</group")) {
                if (groupDepth > 0) {
                    canvas.restore();
                    groupDepth--;
                }
                continue;
            }

            String attrs = pm.group(2);
            if (attrs == null) continue;
            String d = extract(attrs, "android:pathData", null);
            if (d == null || d.isEmpty()) continue;
            String fill = extract(attrs, "android:fillColor", null);
            String stroke = extract(attrs, "android:strokeColor", null);
            String sw = extract(attrs, "android:strokeWidth", null);
            String lc = extract(attrs, "android:strokeLineCap", null);

            Path path = SvgRenderer.parsePathData(d);
            if (path == null) continue;
            String fillType = extract(attrs, "android:fillType", null);
            if ("evenOdd".equals(fillType)) {
                path.setFillType(Path.FillType.EVEN_ODD);
            }

            android.graphics.Paint paint = new android.graphics.Paint(Paint.ANTI_ALIAS_FLAG);
            if (fill != null) {
                Integer color = parseAndroidColor(fill);
                if (color != null) {
                    paint.reset(); paint.setAntiAlias(true);
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    paint.setColor(color);
                    canvas.drawPath(path, paint);
                }
            }
            if (stroke != null) {
                Integer color = parseAndroidColor(stroke);
                if (color != null) {
                    paint.reset(); paint.setAntiAlias(true);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setColor(color);
                    if (sw != null) try { paint.setStrokeWidth(Float.parseFloat(sw)); } catch (Exception ignored) { }
                    if ("round".equals(lc)) paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    else if ("square".equals(lc)) paint.setStrokeCap(android.graphics.Paint.Cap.SQUARE);
                    canvas.drawPath(path, paint);
                }
            }
        }
        while (groupDepth > 0) {
            canvas.restore();
            groupDepth--;
        }
        if (flipH || flipV || rotateDeg != 0) {
            Matrix m = new Matrix();
            m.postTranslate(-bw / 2f, -bh / 2f);
            if (flipH) m.postScale(-1, 1);
            if (flipV) m.postScale(1, -1);
            if (rotateDeg != 0) m.postRotate(rotateDeg);
            m.postTranslate(bw / 2f, bh / 2f);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bw, bh, m, true);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private void copyToClipboard(String format) {
        try {
            String src = codeEditText.getText().toString();
            if (src.isEmpty()) return;
            String content;
            if ("svg".equals(format)) {
                content = isXmlMode ? convertXmlToSvg(src) : src;
            } else {
                content = isXmlMode ? src : convertSvgToXml(src);
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    format.toUpperCase(), content));
            Toast.makeText(this, (format.equals("svg") ? "SVG" : "XML") + "已复制到剪贴板",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "复制失败", t);
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyXmlGroup(Canvas canvas, String attrs) {
        float tx = parseF(extract(attrs, "android:translateX", "0"));
        float ty = parseF(extract(attrs, "android:translateY", "0"));
        float sx = parseF(extract(attrs, "android:scaleX", "1"));
        float sy = parseF(extract(attrs, "android:scaleY", "1"));
        float rotation = parseF(extract(attrs, "android:rotation", "0"));
        float pivotX = parseF(extract(attrs, "android:pivotX", "0"));
        float pivotY = parseF(extract(attrs, "android:pivotY", "0"));

        canvas.translate(tx + pivotX, ty + pivotY);
        canvas.rotate(rotation);
        canvas.scale(sx, sy);
        canvas.translate(-pivotX, -pivotY);
    }

    private Integer parseAndroidColor(String value) {
        if (value == null) return null;
        String color = value.trim();
        if ("@android:color/transparent".equals(color)
                || "transparent".equalsIgnoreCase(color)) {
            return Color.TRANSPARENT;
        }
        if (color.contains("colorAccent") || color.contains("colorPrimary")
                || color.contains("colorControlHighlight")) {
            return ThemeManager.getThemeColor();
        }
        try {
            return Color.parseColor(color);
        } catch (Exception e) {
            return null;
        }
    }

    private float parseF(String s) {
        try {
            if (s == null || s.isEmpty()) return 0;
            s = s.trim();
            if (s.startsWith(".")) s = "0" + s;
            if (s.startsWith("-.")) s = "-0." + s.substring(1);
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ============ SVG ↔ XML ============

    private String convertSvgToXml(String svg) {
        try {
            String converted = convertSvgToXmlWithDom(svg);
            if (converted != null && converted.contains("<path")) {
                return converted;
            }
        } catch (Exception e) {
            AppErrorLogger.catchThrowable(TAG, "DOM方式转换SVG失败，使用兼容模式", e);
        }
        return convertSvgToXmlLegacy(svg);
    }

    /**
     * 使用 DOM 递归转换 SVG，保留 group 的 transform 与 fill/stroke 继承。
     */
    private String convertSvgToXmlWithDom(String svg) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
        } catch (Exception ignored) {
        }
        Document document = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(svg)));
        Element root = document.getDocumentElement();
        if (root == null) return null;

        String vw = "24", vh = "24";
        String viewBox = root.getAttribute("viewBox");
        if (viewBox != null && !viewBox.trim().isEmpty()) {
            String[] p = viewBox.trim().split("[\\s,]+");
            if (p.length >= 4) {
                vw = p[2];
                vh = p[3];
            }
        } else {
            String w = root.getAttribute("width").replaceAll("[^0-9.]", "");
            String h = root.getAttribute("height").replaceAll("[^0-9.]", "");
            if (!w.isEmpty()) vw = w;
            if (!h.isEmpty()) vh = h;
        }

        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        out.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
        out.append("    android:width=\"").append(vw).append("dp\"\n");
        out.append("    android:height=\"").append(vh).append("dp\"\n");
        out.append("    android:viewportWidth=\"").append(vw).append("\"\n");
        out.append("    android:viewportHeight=\"").append(vh).append("\">\n");

        SvgPaint rootPaint = new SvgPaint();
        applySvgPaint(root, rootPaint);
        String rootGroup = svgGroupAttrs(root.getAttribute("transform"));
        if (rootGroup != null) out.append("    <group ").append(rootGroup).append(">\n");

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                appendSvgElement((Element) node, rootPaint, out);
            }
        }

        if (rootGroup != null) out.append("    </group>\n");
        out.append("</vector>\n");
        return out.toString();
    }

    private void appendSvgElement(Element element, SvgPaint parentPaint, StringBuilder out) {
        String tag = element.getLocalName() != null
                ? element.getLocalName() : element.getTagName();
        if (tag == null) return;
        tag = tag.toLowerCase(java.util.Locale.US);

        if ("defs".equals(tag) || "clippath".equals(tag) || "mask".equals(tag)
                || "style".equals(tag) || "title".equals(tag) || "desc".equals(tag)
                || "metadata".equals(tag) || "lineargradient".equals(tag)
                || "radialgradient".equals(tag) || "filter".equals(tag)
                || "pattern".equals(tag) || "use".equals(tag) || "symbol".equals(tag)) {
            return;
        }

        SvgPaint paint = parentPaint.copy();
        applySvgPaint(element, paint);

        if ("g".equals(tag)) {
            String groupAttrs = svgGroupAttrs(element.getAttribute("transform"));
            if (groupAttrs != null) {
                out.append("    <group ").append(groupAttrs).append(">\n");
            }
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element) {
                    appendSvgElement((Element) node, paint, out);
                }
            }
            if (groupAttrs != null) out.append("    </group>\n");
            return;
        }

        if (!isSvgShape(tag)) return;
        String attrs = svgElementAttrs(element);
        String pathData = buildElementPath(tag, attrs);
        if (pathData == null || pathData.isEmpty()) return;

        String groupAttrs = svgGroupAttrs(element.getAttribute("transform"));
        if (groupAttrs != null) out.append("    <group ").append(groupAttrs).append(">\n");

        out.append("    <path\n        android:pathData=\"")
                .append(pathData.replace("\"", "&quot;")).append("\"");

        String fill = paint.fill;
        boolean strokeVisible = paint.stroke != null && !"none".equals(paint.stroke);
        if (fill == null) {
            fill = strokeVisible ? "none" : "#000000";
        }
        out.append("\n        android:fillColor=\"")
                .append("none".equals(fill) ? "#00000000" : svgColor(fill))
                .append("\"");

        if (strokeVisible) {
            out.append("\n        android:strokeColor=\"").append(svgColor(paint.stroke)).append("\"");
            String sw = paint.strokeWidth != null ? paint.strokeWidth : "1";
            out.append("\n        android:strokeWidth=\"").append(sw).append("\"");
            if ("round".equals(paint.lineCap)) {
                out.append("\n        android:strokeLineCap=\"round\"");
            } else if ("square".equals(paint.lineCap)) {
                out.append("\n        android:strokeLineCap=\"square\"");
            }
            if ("round".equals(paint.lineJoin)) {
                out.append("\n        android:strokeLineJoin=\"round\"");
            } else if ("bevel".equals(paint.lineJoin)) {
                out.append("\n        android:strokeLineJoin=\"bevel\"");
            }
        }
        out.append(" />\n");

        if (groupAttrs != null) out.append("    </group>\n");
    }

    private boolean isSvgShape(String tag) {
        return "path".equals(tag) || "circle".equals(tag) || "ellipse".equals(tag)
                || "rect".equals(tag) || "line".equals(tag)
                || "polyline".equals(tag) || "polygon".equals(tag);
    }

    private void applySvgPaint(Element element, SvgPaint paint) {
        String fill = svgElementValue(element, "fill");
        if (fill != null) paint.fill = fill;
        String stroke = svgElementValue(element, "stroke");
        if (stroke != null) paint.stroke = stroke;
        String strokeWidth = svgElementValue(element, "stroke-width");
        if (strokeWidth != null) paint.strokeWidth = strokeWidth;
        String lineCap = svgElementValue(element, "stroke-linecap");
        if (lineCap != null) paint.lineCap = lineCap;
        String lineJoin = svgElementValue(element, "stroke-linejoin");
        if (lineJoin != null) paint.lineJoin = lineJoin;
    }

    private String svgElementValue(Element element, String name) {
        String value = element.getAttribute(name);
        if (value != null && !value.trim().isEmpty()) return value.trim();
        String style = element.getAttribute("style");
        if (style != null && !style.trim().isEmpty()) {
            for (String part : style.split(";")) {
                int colon = part.indexOf(':');
                if (colon > 0 && name.equals(part.substring(0, colon).trim())) {
                    return part.substring(colon + 1).trim();
                }
            }
        }
        return null;
    }

    private String svgElementAttrs(Element element) {
        StringBuilder attrs = new StringBuilder();
        NamedNodeMap map = element.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node attr = map.item(i);
            String name = attr.getNodeName();
            if (name == null || name.startsWith("xmlns")) continue;
            String value = attr.getNodeValue();
            if (value == null) continue;
            attrs.append(name).append("=\"")
                    .append(value.replace("\"", "&quot;")).append("\" ");
        }
        return attrs.toString();
    }

    private String svgGroupAttrs(String transform) {
        if (transform == null || transform.trim().isEmpty()) return null;
        float tx = 0f, ty = 0f, sx = 1f, sy = 1f, rotation = 0f;
        float pivotX = 0f, pivotY = 0f;
        Matcher matcher = Pattern.compile(
                "(translate|scale|rotate)\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE).matcher(transform);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(java.util.Locale.US);
            String[] values = matcher.group(2).trim().split("[\\s,]+");
            if (values.length == 0 || values[0].isEmpty()) continue;
            try {
                if ("translate".equals(type)) {
                    tx += parseF(values[0]);
                    if (values.length > 1) ty += parseF(values[1]);
                } else if ("scale".equals(type)) {
                    float scaleX = parseF(values[0]);
                    float scaleY = values.length > 1 ? parseF(values[1]) : scaleX;
                    sx *= scaleX;
                    sy *= scaleY;
                } else {
                    rotation += parseF(values[0]);
                    if (values.length >= 3) {
                        pivotX = parseF(values[1]);
                        pivotY = parseF(values[2]);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (Math.abs(tx) < 0.001f && Math.abs(ty) < 0.001f
                && Math.abs(sx - 1f) < 0.001f && Math.abs(sy - 1f) < 0.001f
                && Math.abs(rotation) < 0.001f) {
            return null;
        }
        StringBuilder attrs = new StringBuilder();
        if (Math.abs(pivotX) > 0.001f) attrs.append("android:pivotX=\"").append(fmt(pivotX)).append("\" ");
        if (Math.abs(pivotY) > 0.001f) attrs.append("android:pivotY=\"").append(fmt(pivotY)).append("\" ");
        if (Math.abs(tx) > 0.001f) attrs.append("android:translateX=\"").append(fmt(tx)).append("\" ");
        if (Math.abs(ty) > 0.001f) attrs.append("android:translateY=\"").append(fmt(ty)).append("\" ");
        if (Math.abs(sx - 1f) > 0.001f) attrs.append("android:scaleX=\"").append(fmt(sx)).append("\" ");
        if (Math.abs(sy - 1f) > 0.001f) attrs.append("android:scaleY=\"").append(fmt(sy)).append("\" ");
        if (Math.abs(rotation) > 0.001f) attrs.append("android:rotation=\"").append(fmt(rotation)).append("\" ");
        return attrs.length() > 0 ? attrs.toString().trim() : null;
    }

    private static class SvgPaint {
        String fill;
        String stroke;
        String strokeWidth;
        String lineCap;
        String lineJoin;

        SvgPaint copy() {
            SvgPaint copy = new SvgPaint();
            copy.fill = fill;
            copy.stroke = stroke;
            copy.strokeWidth = strokeWidth;
            copy.lineCap = lineCap;
            copy.lineJoin = lineJoin;
            return copy;
        }
    }

    private String convertSvgToXmlLegacy(String svg) {
        String vw = "24", vh = "24";
        Matcher vm = Pattern.compile("viewBox\\s*=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(svg);
        if (vm.find()) {
            String[] p = vm.group(1).split("[\\s,]+");
            if (p.length > 2) { vw = p[2]; vh = p[3]; }
        }
        // 提取 SVG 全局属性（fill/stroke/stroke-width/stroke-linecap/stroke-linejoin）
        String gFill = null, gStroke = null, gStrokeWidth = null, gLinecap = null, gLinejoin = null;
        Matcher svgMatcher = Pattern.compile("<svg\\s+([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(svg);
        if (svgMatcher.find()) {
            String sAttrs = svgMatcher.group(1);
            gFill = svgAttr(sAttrs, "fill", null);
            gStroke = svgAttr(sAttrs, "stroke", null);
            gStrokeWidth = svgAttr(sAttrs, "stroke-width", null);
            gLinecap = svgAttr(sAttrs, "stroke-linecap", null);
            gLinejoin = svgAttr(sAttrs, "stroke-linejoin", null);
        }
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
        x.append("    android:width=\"").append(vw).append("dp\"\n");
        x.append("    android:height=\"").append(vh).append("dp\"\n");
        x.append("    android:viewportWidth=\"").append(vw).append("\"\n");
        x.append("    android:viewportHeight=\"").append(vh).append("\">\n");

        // 遍历所有子元素，合并全局属性
        Pattern elemP = Pattern.compile("<(\\w+)\\s+([^>]*)/?>", Pattern.CASE_INSENSITIVE);
        Matcher em = elemP.matcher(svg);
        while (em.find()) {
            String tag = em.group(1);
            if ("svg".equals(tag) || "g".equals(tag) || "defs".equals(tag)) continue;
            String elAttrs = em.group(2);
            String pathData = buildElementPath(tag, elAttrs);
            if (pathData == null || pathData.isEmpty()) continue;
            x.append("    <path\n        android:pathData=\"").append(pathData).append("\"");

            // fill: 元素级 > 全局
            String fill = svgAttr(elAttrs, "fill", gFill);
            // stroke: 元素级 > 全局
            String stroke = svgAttr(elAttrs, "stroke", gStroke);
            boolean hasVisibleStroke = stroke != null && !"none".equals(stroke);
            if (fill != null) {
                fill = fill.replace("currentColor", "#000000");
                x.append("\n        android:fillColor=\"")
                        .append("none".equals(fill) ? "@android:color/transparent" : svgColor(fill))
                        .append("\"");
            } else if (hasVisibleStroke) {
                // 只有描边的图标必须显式透明填充，否则 Android Vector 默认会填充成黑色
                x.append("\n        android:fillColor=\"@android:color/transparent\"");
            } else {
                x.append("\n        android:fillColor=\"#FF000000\"");
            }
            if (hasVisibleStroke) {
                stroke = stroke.replace("currentColor", "#000000");
                x.append("\n        android:strokeColor=\"").append(svgColor(stroke)).append("\"");
                String sw = svgAttr(elAttrs, "stroke-width", gStrokeWidth);
                if (sw != null) x.append("\n        android:strokeWidth=\"").append(sw).append("\"");
                String lc = svgAttr(elAttrs, "stroke-linecap", gLinecap);
                if (lc != null) {
                    if ("round".equals(lc)) x.append("\n        android:strokeLineCap=\"round\"");
                    else if ("square".equals(lc)) x.append("\n        android:strokeLineCap=\"square\"");
                }
                String lj = svgAttr(elAttrs, "stroke-linejoin", gLinejoin);
                if (lj != null) {
                    if ("round".equals(lj)) x.append("\n        android:strokeLineJoin=\"round\"");
                    else if ("bevel".equals(lj)) x.append("\n        android:strokeLineJoin=\"bevel\"");
                    else if ("miter".equals(lj)) x.append("\n        android:strokeLineJoin=\"miter\"");
                }
            }
            x.append(" />\n");
        }
        x.append("</vector>\n");
        return x.toString();
    }

    private String buildElementPath(String tag, String attrs) {
        switch (tag) {
            case "path": return extract(attrs, "d", "");
            case "circle": {
                float cx = parseF(extract(attrs, "cx", "0"));
                float cy = parseF(extract(attrs, "cy", "0"));
                float r = parseF(extract(attrs, "r", "0"));
                if (r <= 0) return null;
                return "M" + cx + "," + (cy - r) + " a" + r + "," + r + " 0 1,1 0," + (r*2) + " a" + r + "," + r + " 0 1,1 0,-" + (r*2);
            }
            case "ellipse": {
                float cx = parseF(extract(attrs, "cx", "0"));
                float cy = parseF(extract(attrs, "cy", "0"));
                float rx = parseF(extract(attrs, "rx", "0"));
                float ry = parseF(extract(attrs, "ry", "0"));
                if (rx <= 0 || ry <= 0) return null;
                return "M" + (cx - rx) + "," + cy + " a" + rx + "," + ry + " 0 1,1 " + (rx*2) + ",0 a" + rx + "," + ry + " 0 1,1 -" + (rx*2) + ",0";
            }
            case "rect": {
                float x = parseF(extract(attrs, "x", "0"));
                float y = parseF(extract(attrs, "y", "0"));
                float w = parseF(extract(attrs, "width", "0"));
                float h = parseF(extract(attrs, "height", "0"));
                if (w <= 0 || h <= 0) return null;
                float rx = parseF(extract(attrs, "rx", "0"));
                float ry = parseF(extract(attrs, "ry", "0"));
                if (ry == 0) ry = rx;
                if (rx > w / 2) rx = w / 2;
                if (ry > h / 2) ry = h / 2;
                if (rx > 0 || ry > 0) {
                    return "M" + (x + rx) + "," + y
                        + " h" + (w - 2 * rx)
                        + " a" + rx + "," + ry + " 0 0,1 " + rx + "," + ry
                        + " v" + (h - 2 * ry)
                        + " a" + rx + "," + ry + " 0 0,1 -" + rx + "," + ry
                        + " h-" + (w - 2 * rx)
                        + " a" + rx + "," + ry + " 0 0,1 -" + rx + ",-" + ry
                        + " v-" + (h - 2 * ry)
                        + " a" + rx + "," + ry + " 0 0,1 " + rx + ",-" + ry
                        + " Z";
                }
                return "M" + x + "," + y + " h" + w + " v" + h + " h-" + w + " Z";
            }
            case "line": {
                float x1 = parseF(extract(attrs, "x1", "0"));
                float y1 = parseF(extract(attrs, "y1", "0"));
                float x2 = parseF(extract(attrs, "x2", "0"));
                float y2 = parseF(extract(attrs, "y2", "0"));
                return "M" + x1 + "," + y1 + " L" + x2 + "," + y2;
            }
            case "polyline": {
                String pts = extract(attrs, "points", "");
                if (pts.isEmpty()) return null;
                List<Float> nums = new ArrayList<>();
                Matcher nm = Pattern.compile("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matcher(pts);
                while (nm.find()) nums.add(Float.parseFloat(nm.group()));
                if (nums.size() < 4) return null;
                StringBuilder sb = new StringBuilder("M");
                sb.append(nums.get(0)).append(",").append(nums.get(1));
                for (int i = 2; i + 1 < nums.size(); i += 2)
                    sb.append(" L").append(nums.get(i)).append(",").append(nums.get(i + 1));
                return sb.toString();
            }
            case "polygon": {
                String pts = extract(attrs, "points", "");
                if (pts.isEmpty()) return null;
                List<Float> nums = new ArrayList<>();
                Matcher nm = Pattern.compile("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matcher(pts);
                while (nm.find()) nums.add(Float.parseFloat(nm.group()));
                if (nums.size() < 4) return null;
                StringBuilder sb = new StringBuilder("M");
                sb.append(nums.get(0)).append(",").append(nums.get(1));
                for (int i = 2; i + 1 < nums.size(); i += 2)
                    sb.append(" L").append(nums.get(i)).append(",").append(nums.get(i + 1));
                sb.append(" Z");
                return sb.toString();
            }
        }
        return null;
    }

    private String convertXmlToSvg(String xml) {
        String vw = extract(xml, "android:viewportWidth", "24");
        String vh = extract(xml, "android:viewportHeight", "24");
        StringBuilder s = new StringBuilder();
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\"\n");
        s.append("    viewBox=\"0 0 ").append(vw).append(" ").append(vh).append("\"\n");
        s.append("    width=\"").append(vw).append("\" height=\"").append(vh).append("\">\n");
        Pattern p = Pattern.compile("<path\\s+([^>]*)/?>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(xml);
        while (m.find()) {
            String a = m.group(1);
            String d = extract(a, "android:pathData", "");
            if (d.isEmpty()) continue;
            s.append("    <path d=\"").append(d).append("\"");
            String fill = extract(a, "android:fillColor", null);
            if (fill != null) s.append(" fill=\"").append(androidColor(fill)).append("\"");
            String stroke = extract(a, "android:strokeColor", null);
            if (stroke != null) {
                s.append(" stroke=\"").append(androidColor(stroke)).append("\"");
                String sw = extract(a, "android:strokeWidth", null);
                if (sw != null) s.append(" stroke-width=\"").append(sw).append("\"");
            }
            s.append("/>\n");
        }
        s.append("</svg>\n");
        return s.toString();
    }

    // ============ 颜色编辑 ============

    private void detectColors() {
        detectedColors.clear();
        String text = codeEditText.getText().toString();
        Matcher m = Pattern.compile("#[0-9A-Fa-f]{3,8}").matcher(text);
        while (m.find()) {
            String n = normalize(m.group());
            if (!hasColor(n) && !"#00000000".equals(n)) detectedColors.add(new ColorInfo(m.group(), n, m.start(), m.end()));
        }
        // SVG 模式下检测 currentColor
        if (!isXmlMode && text.contains("currentColor") && !hasColor("currentColor")) {
            int idx = text.indexOf("currentColor");
            detectedColors.add(new ColorInfo("currentColor", "#000000", idx, idx + 12));
        }
        renderColorChips();
    }

    private boolean hasColor(String c) {
        for (ColorInfo ci : detectedColors) {
            if (ci.colorValue.equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    private String normalize(String c) {
        return (c.length() == 4) ? "#" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3) : c;
    }

    private void renderColorChips() {
        colorListLayout.removeAllViews();
        if (detectedColors.isEmpty()) {
            colorHintTextView.setVisibility(View.VISIBLE);
            colorHintTextView.setText("未检测到颜色值");
            return;
        }
        colorHintTextView.setVisibility(View.GONE);
        LinearLayout row = null;
        for (int i = 0; i < detectedColors.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                colorListLayout.addView(row);
            }
            final ColorInfo ci = detectedColors.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            int m = (int) (4 * getResources().getDisplayMetrics().density);
            lp.setMargins(m, m, m, m);

            View chip = getLayoutInflater().inflate(R.layout.item_color_chip, row, false);
            chip.setLayoutParams(lp);
            try {
                chip.findViewById(R.id.colorDot).getBackground().setColorFilter(
                        Color.parseColor(ci.colorValue), PorterDuff.Mode.SRC);
            } catch (Exception ignored) {}
            ((TextView) chip.findViewById(R.id.colorText)).setText(ci.colorValue);

            chip.setOnClickListener(v -> showColorPicker(ci));
            row.addView(chip);
        }
    }

    private void showColorPicker(final ColorInfo ci) {
        final int dp4 = (int) (4 * getResources().getDisplayMetrics().density);
        final int dp8 = dp4 * 2;

        int initColor;
        try { initColor = android.graphics.Color.parseColor(ci.originalText); }
        catch (Exception e) { initColor = 0xFF000000; }
        final float[] curHsv = new float[3];
        android.graphics.Color.colorToHSV(initColor, curHsv);
        final int[] curAlpha = new int[]{android.graphics.Color.alpha(initColor)};

        // ── Forward references for views created later ──
        final View[] colorPreviewHolder = new View[1];
        final EditText[] hexInputHolder = new EditText[1];
        final View[] svPaletteHolder = new View[1];

        // ── Preview updater (declared BEFORE anonymous views that call it) ──
        final Runnable updatePreview = () -> {
            if (colorPreviewHolder[0] == null || hexInputHolder[0] == null) return;
            try {
                int color = android.graphics.Color.HSVToColor(curAlpha[0], curHsv);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                gd.setCornerRadius(dp4);
                gd.setColor(color);
                gd.setStroke(1, 0xFFBDBDBD);
                colorPreviewHolder[0].setBackground(gd);
                hexInputHolder[0].setText(colorToHex(curHsv, curAlpha[0], true));
                if (svPaletteHolder[0] != null) svPaletteHolder[0].invalidate();
            } catch (Exception e) {
                AppErrorLogger.catchThrowable("ColorPicker", "更新预览失败", e);
            }
        };

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp8 * 2, dp8, dp8 * 2, dp8);

        // ── Title ──
        android.widget.TextView titleView = new android.widget.TextView(this);
        titleView.setText("调色板");
        titleView.setTextSize(16);
        titleView.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView);
        root.addView(spacer(dp8));

        // ── Hue bar (LinearGradient, no loop) ──
        final View hueBar = new View(this) {
            private final android.graphics.Paint bgPaint = new android.graphics.Paint();
            private android.graphics.LinearGradient hueGradient;
            private int lastW = -1;
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                if (w <= 0) return;
                if (hueGradient == null || lastW != (int) w) {
                    lastW = (int) w;
                    int[] colors = new int[7];
                    for (int i = 0; i < 7; i++)
                        colors[i] = android.graphics.Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
                    hueGradient = new android.graphics.LinearGradient(0, 0, w, 0, colors, null, android.graphics.Shader.TileMode.CLAMP);
                }
                bgPaint.setShader(hueGradient);
                c.drawRect(0, 0, w, h, bgPaint);
                float ix = curHsv[0] / 360f * w;
                bgPaint.setShader(null);
                bgPaint.setColor(0xFFFFFFFF); bgPaint.setStrokeWidth(dp4); c.drawLine(ix, 0, ix, h, bgPaint);
                bgPaint.setColor(0xFF000000); bgPaint.setStrokeWidth(1);  c.drawLine(ix, 0, ix, h, bgPaint);
            }
            @Override
            public boolean onTouchEvent(android.view.MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) {
                    curHsv[0] = Math.max(0, Math.min(360, ev.getX() / getWidth() * 360f));
                    invalidate();
                    updatePreview.run();
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        };
        hueBar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        root.addView(hueBar);

        root.addView(spacer(dp8));

        // ── SV palette (Bitmap pixel rendering – most compatible) ──
        final android.graphics.Paint svPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Bitmap[] svBitmap = new android.graphics.Bitmap[1];
        final float[] lastHue = new float[]{-1f};
        final int[] lastBmpW = new int[]{-1}, lastBmpH = new int[]{-1};
        final View svPalette = new View(this) {
            private void rebuildBitmap(int w, int h, float hue) {
                if (svBitmap[0] != null && lastBmpW[0] == w && lastBmpH[0] == h && lastHue[0] == hue) return;
                if (w <= 0 || h <= 0) return;
                if (svBitmap[0] != null && (lastBmpW[0] != w || lastBmpH[0] != h)) {
                    svBitmap[0].recycle();
                    svBitmap[0] = null;
                }
                if (svBitmap[0] == null)
                    svBitmap[0] = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                int[] pixels = new int[w * h];
                for (int y = 0; y < h; y++) {
                    float v = 1f - (float) y / h;
                    int rowOff = y * w;
                    for (int x = 0; x < w; x++) {
                        float s = (float) x / w;
                        pixels[rowOff + x] = android.graphics.Color.HSVToColor(new float[]{hue, s, v});
                    }
                }
                svBitmap[0].setPixels(pixels, 0, w, 0, 0, w, h);
                lastBmpW[0] = w; lastBmpH[0] = h; lastHue[0] = hue;
            }
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                rebuildBitmap(w, h, curHsv[0]);
                if (svBitmap[0] != null) {
                    c.drawBitmap(svBitmap[0], 0, 0, null);
                }
                float sx = curHsv[1] * w, sy = (1f - curHsv[2]) * h;
                svPaint.setStyle(android.graphics.Paint.Style.STROKE);
                svPaint.setShader(null);
                svPaint.setColor(0xFFFFFFFF); svPaint.setStrokeWidth(dp4);
                c.drawCircle(sx, sy, dp8, svPaint);
                svPaint.setColor(0xFF000000); svPaint.setStrokeWidth(1);
                c.drawCircle(sx, sy, dp8, svPaint);
            }
            @Override
            public boolean onTouchEvent(android.view.MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) {
                    curHsv[1] = Math.max(0, Math.min(1, ev.getX() / getWidth()));
                    curHsv[2] = Math.max(0, Math.min(1, 1f - ev.getY() / getHeight()));
                    invalidate();
                    updatePreview.run();
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        };
        svPalette.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        svPaletteHolder[0] = svPalette;
        root.addView(svPalette);

        root.addView(spacer(dp8));

        // ── Preview + Hex ──
        int previewSize = dp(40);
        final View colorPreview = new View(this);
        colorPreview.setLayoutParams(new android.widget.LinearLayout.LayoutParams(previewSize, previewSize));
        colorPreview.setBackgroundColor(0xFF888888);
        final EditText hexInput = new EditText(this);
        hexInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        hexInput.setSelectAllOnFocus(true);
        hexInput.setHorizontallyScrolling(false);
        hexInput.setMaxLines(3);
        hexInput.setMinHeight(dp(48));
        hexInput.setHint("#AARRGGBB");

        android.widget.LinearLayout previewRow = new android.widget.LinearLayout(this);
        previewRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        previewRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout.LayoutParams plp = new android.widget.LinearLayout.LayoutParams(previewSize, previewSize);
        plp.setMargins(0, 0, dp8, 0);
        colorPreview.setLayoutParams(plp);
        previewRow.addView(colorPreview);
        hexInput.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        previewRow.addView(hexInput);
        root.addView(previewRow);

        // Wire up holders
        colorPreviewHolder[0] = colorPreview;
        hexInputHolder[0] = hexInput;

        root.addView(spacer(dp8));

        // ── Preset colors ──
        android.widget.TextView presetLabel = new android.widget.TextView(this);
        presetLabel.setText("预设");
        presetLabel.setTextSize(12);
        presetLabel.setTextColor(ThemeManager.getAccessibleThemeColor(this));
        root.addView(presetLabel);

        String[] presetColors = {
            "#FF2196F3", "#FF4CAF50", "#FFFF9800", "#FFF44336",
            "#FF9C27B0", "#FF00BCD4", "#FF795548", "#FF607D8B",
            "#FFE91E63", "#FFCDDC39", "#FFFF5722", "#FF673AB7",
            "#FF009688", "#FFFFEB3B", "#FF3F51B5", "#FF000000"
        };
        android.widget.LinearLayout row = null;
        for (int i = 0; i < presetColors.length; i++) {
            if (i % 8 == 0) {
                row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                root.addView(row);
            }
            final String pc = presetColors[i];
            android.widget.ImageView dot = new android.widget.ImageView(this);
            android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(dp(28), dp(28));
            dlp.setMargins(dp4, dp4, dp4, dp4);
            dot.setLayoutParams(dlp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(android.graphics.Color.parseColor(pc));
            gd.setStroke(1, 0xFFBDBDBD);
            dot.setImageDrawable(gd);
            dot.setOnClickListener(view -> {
                int c = android.graphics.Color.parseColor(pc);
                android.graphics.Color.colorToHSV(c, curHsv);
                curAlpha[0] = android.graphics.Color.alpha(c);
                hexInput.setText(pc);
                hueBar.invalidate();
                svPalette.invalidate();
                updatePreview.run();
            });
            row.addView(dot);
        }

        root.addView(spacer(dp8));

        // ── Buttons ──
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        root.addView(btnRow);

        int tc = ThemeManager.getThemeColor();
        int cc = ThemeManager.getContrastColor(tc);
        com.google.android.material.button.MaterialButton applyBtn =
                new com.google.android.material.button.MaterialButton(this);
        applyBtn.setText("应用");
        applyBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tc));
        applyBtn.setTextColor(cc);
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        blp.setMargins(0, 0, dp4, 0);
        applyBtn.setLayoutParams(blp);
        com.google.android.material.button.MaterialButton cancelBtn =
                new com.google.android.material.button.MaterialButton(this);
        cancelBtn.setText("取消");
        cancelBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tc));
        cancelBtn.setTextColor(cc);
        cancelBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(applyBtn);
        btnRow.addView(cancelBtn);

        // Init preview – always show a valid hex, never "currentColor"
        String initHex = ci.originalText;
        try { android.graphics.Color.parseColor(initHex); } catch (Exception e) { initHex = colorToHex(curHsv, curAlpha[0], true); }
        hexInput.setText(initHex);
        updatePreview.run();

        // 判断原颜色是否带 alpha（9 字符 #AARRGGBB）
        final boolean origHasAlpha = ci.originalText != null && ci.originalText.length() == 9;

        // Apply action
        Runnable applyAction = () -> {
            String hex = hexInput.getText().toString().trim();
            if (hex.isEmpty()) hex = colorToHex(curHsv, curAlpha[0], origHasAlpha || curAlpha[0] < 255);
            if (!hex.startsWith("#")) hex = "#" + hex;
            try { android.graphics.Color.parseColor(hex); } catch (Exception e) {
                Toast.makeText(IconConverterActivity.this, "无效的颜色值", Toast.LENGTH_SHORT).show();
                return;
            }
            // 输出格式与原格式一致：原 #RRGGBB → 输出 #RRGGBB
            if (!origHasAlpha && hex.length() == 9) hex = "#" + hex.substring(3);
            String text = codeEditText.getText().toString();
            if (ci.startPos >= 0 && ci.endPos <= text.length()) {
                text = text.substring(0, ci.startPos) + hex + text.substring(ci.endPos);
            } else if (text.contains(ci.colorValue)) {
                text = text.replace(ci.colorValue, hex);
            } else if (text.contains(ci.originalText)) {
                text = text.replace(ci.originalText, hex);
            } else if (!isXmlMode && text.contains("currentColor")) {
                text = text.replace("currentColor", hex);
            } else {
                // XML 检测的颜色是 #AARRGGBB 格式，尝试去掉 alpha 前缀匹配 SVG 的 #RRGGBB
                String stripped = stripAlpha(ci.colorValue);
                if (!stripped.equals(ci.colorValue) && text.contains(stripped)) {
                    text = text.replace(stripped, hex);
                }
            }
            codeEditText.setText(text);
            renderPreview();
        };

        // Dialog – ScrollView + fixed height + ADJUST_RESIZE to avoid keyboard squeeze
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT));
        scrollView.addView(root);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(scrollView);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        applyBtn.setOnClickListener(v -> { applyAction.run(); dialog.dismiss(); });
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null)
                dialog.getWindow().getDecorView().post(updatePreview);
        });
        dialog.show();
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private String colorToHex(float[] hsv, int alpha, boolean includeAlpha) {
        int rgb = android.graphics.Color.HSVToColor(alpha, hsv);
        if (includeAlpha) {
            return String.format("#%02X%02X%02X%02X",
                    android.graphics.Color.alpha(rgb),
                    android.graphics.Color.red(rgb),
                    android.graphics.Color.green(rgb),
                    android.graphics.Color.blue(rgb));
        }
        return String.format("#%02X%02X%02X",
                android.graphics.Color.red(rgb),
                android.graphics.Color.green(rgb),
                android.graphics.Color.blue(rgb));
    }

    // #AARRGGBB -> #RRGGBB；如果不是9字符则原样返回
    private String stripAlpha(String c) {
        if (c != null && c.length() == 9 && c.startsWith("#")) {
            return "#" + c.substring(3);
        }
        return c;
    }

    // ============ 导出 ============

    private void doExportSvg(Uri uri) throws Exception {
        String src = codeEditText.getText().toString();
        if (src.isEmpty()) return;
        String content = isXmlMode ? convertXmlToSvg(src) : src;
        content = bakeTransform(content, "svg");
        OutputStream os = getContentResolver().openOutputStream(uri);
        os.write(content.getBytes("UTF-8"));
        os.close();
        Toast.makeText(this, "SVG已导出", Toast.LENGTH_SHORT).show();
    }

    private void doExportXml(Uri uri) throws Exception {
        String src = codeEditText.getText().toString();
        if (src.isEmpty()) return;
        String content = isXmlMode ? src : convertSvgToXml(src);
        content = bakeTransform(content, "xml");
        OutputStream os = getContentResolver().openOutputStream(uri);
        os.write(content.getBytes("UTF-8"));
        os.close();
        Toast.makeText(this, "XML已导出", Toast.LENGTH_SHORT).show();
    }

    private void doExportPng(Uri uri) throws Exception {
        Drawable d = previewImageView.getDrawable();
        if (d == null) { Toast.makeText(this, "请先预览", Toast.LENGTH_SHORT).show(); return; }
        int w = Math.max(pendingExportWidth, 1);
        int h = Math.max(pendingExportHeight, 1);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        OutputStream os = getContentResolver().openOutputStream(uri);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        Toast.makeText(this, "PNG已导出 (" + w + "×" + h + ")", Toast.LENGTH_SHORT).show();
    }

    private String bakeTransform(String content, String format) {
        if (!flipH && !flipV && rotateDeg == 0) return content;
        float[] viewport = viewportSize(content, format);
        float cx = viewport[0] / 2f;
        float cy = viewport[1] / 2f;

        if ("xml".equals(format)) {
            int vectorStart = content.indexOf("<vector");
            int openEnd = vectorStart >= 0 ? content.indexOf('>', vectorStart) : -1;
            int closeStart = content.lastIndexOf("</vector>");
            if (openEnd < 0 || closeStart < openEnd) return content;

            StringBuilder attrs = new StringBuilder();
            attrs.append("android:pivotX=\"").append(fmt(cx)).append("\" ");
            attrs.append("android:pivotY=\"").append(fmt(cy)).append("\"");
            if (flipH) attrs.append(" android:scaleX=\"-1\"");
            if (flipV) attrs.append(" android:scaleY=\"-1\"");
            if (rotateDeg != 0) attrs.append(" android:rotation=\"").append(rotateDeg).append("\"");

            return content.substring(0, openEnd + 1)
                    + "\n    <group " + attrs + ">\n"
                    + content.substring(openEnd + 1, closeStart)
                    + "    </group>\n"
                    + content.substring(closeStart);
        }

        // SVG：用内部 <g> 包裹，避免把 transform 写到根 <svg> 上
        StringBuilder t = new StringBuilder();
        t.append("translate(").append(fmt(cx)).append(",").append(fmt(cy)).append(")");
        if (flipH) t.append(" scale(-1,1)");
        if (flipV) t.append(" scale(1,-1)");
        if (rotateDeg != 0) t.append(" rotate(").append(rotateDeg).append(")");
        t.append(" translate(-").append(fmt(cx)).append(",-").append(fmt(cy)).append(")");

        int svgStart = content.indexOf("<svg");
        int openEnd = svgStart >= 0 ? content.indexOf('>', svgStart) : -1;
        int closeStart = content.lastIndexOf("</svg>");
        if (openEnd < 0 || closeStart < openEnd) return content;
        return content.substring(0, openEnd + 1)
                + "\n<g transform=\"" + t + "\">\n"
                + content.substring(openEnd + 1, closeStart)
                + "</g>\n"
                + content.substring(closeStart);
    }

    private float[] viewportSize(String content, String format) {
        float width = 24f, height = 24f;
        try {
            if ("xml".equals(format)) {
                width = parseF(extract(content, "android:viewportWidth", "24"));
                height = parseF(extract(content, "android:viewportHeight", "24"));
            } else {
                Matcher vm = Pattern.compile("viewBox\\s*=\\\"([^\\\"]+)\\\"",
                        Pattern.CASE_INSENSITIVE).matcher(content);
                if (vm.find()) {
                    String[] p = vm.group(1).split("[\\s,]+");
                    if (p.length >= 4) {
                        width = parseF(p[2]);
                        height = parseF(p[3]);
                    }
                } else {
                    width = parseF(extract(content, "width", "24").replaceAll("[^0-9.]", ""));
                    height = parseF(extract(content, "height", "24").replaceAll("[^0-9.]", ""));
                }
            }
        } catch (Exception ignored) {
        }
        if (width <= 0) width = 24f;
        if (height <= 0) height = 24f;
        return new float[]{width, height};
    }

    private String fmt(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return Integer.toString(Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    // ============ 工具 ============

    private String svgAttr(String attrs, String name, String def) {
        if (attrs == null) return def;
        String direct = extract(attrs, name, null);
        if (direct != null) return direct;
        String style = extract(attrs, "style", null);
        if (style != null) {
            for (String part : style.split(";")) {
                int colon = part.indexOf(':');
                if (colon > 0 && name.equals(part.substring(0, colon).trim())) {
                    return part.substring(colon + 1).trim();
                }
            }
        }
        return def;
    }

    private String extract(String str, String attr, String def) {
        Matcher m = Pattern.compile(attr + "\\s*=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(str);
        return m.find() ? m.group(1) : def;
    }

    private String svgColor(String c) {
        if (c == null || c.equals("none")) return "#00000000";
        if (c.startsWith("#")) {
            if (c.length() == 4) return "#FF" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
            if (c.length() == 7) return "#FF" + c.substring(1);
            return c;
        }
        return "#FF000000";
    }

    private String androidColor(String c) {
        if (c == null) return "#000000";
        return (c.length() == 9 && (c.charAt(1) == 'F' || c.charAt(1) == 'f')) ? "#" + c.substring(3) : c;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
