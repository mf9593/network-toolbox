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
import com.networktool.app.util.SvgToVectorConverter;

import static com.networktool.app.util.SvgToVectorConverter.extract;
import static com.networktool.app.util.SvgToVectorConverter.fmt;
import static com.networktool.app.util.SvgToVectorConverter.parseF;
import static com.networktool.app.util.SvgToVectorConverter.svgAttr;
import static com.networktool.app.util.SvgToVectorConverter.svgColor;
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

    /** 渐变 id → 第一个色标的颜色（Android Vector 的渐变写法较复杂，这里退化成纯色） */

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
        // 以输入内容为准，而不是只看模式开关：贴了 SVG 却停在 XML 模式时，
        // 矢量渲染器认不出 <rect>/<polyline>/<defs>，预览就是一片空白。
        boolean xmlInput = detectXmlInput(input);
        syncModeSwitch(xmlInput);
        if (xmlInput) {
            return renderXmlDrawable(input);
        }
        return renderSvgWithLibrary(input);
    }

    /**
     * 自动识别输入格式是 SVG 还是 Android Vector XML。
     *
     * @return true 表示矢量 XML（{@code <vector>}），false 表示 SVG（{@code <svg>}）
     */
    private boolean detectXmlInput(String text) {
        if (text == null || text.trim().isEmpty()) return isXmlMode;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int svg = lower.indexOf("<svg");
        int vector = lower.indexOf("<vector");
        if (svg >= 0 && (vector < 0 || svg < vector)) return false;
        if (vector >= 0) return true;
        return isXmlMode;
    }

    /** 让模式开关跟上实际识别出的格式，避免开关显示的模式和渲染结果对不上 */
    private void syncModeSwitch(boolean xml) {
        if (isXmlMode == xml) return;
        isXmlMode = xml;
        if (modeToggleGroup != null) {
            modeToggleGroup.check(xml ? R.id.modeXml : R.id.modeSvg);
        }
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
        // AndroidSVG 不支持 currentColor，按「当前文字色」的语义换成具体颜色。
        // 深色主题下用主题文字色，否则纯黑图标画在深色预览卡片上等于看不见。
        if (sanitized.contains("currentColor"))
            sanitized = sanitized.replace("currentColor", previewInkColor());
        // 只移除根 <svg> 上的 transform，保留子元素/分组 transform
        sanitized = stripRootSvgTransform(sanitized);
        AppErrorLogger.logDebug(TAG, "sanitized=" + sanitized);
        SVG svg;
        try {
            svg = SVG.getFromString(sanitized);
        } catch (Exception e) {
            AppErrorLogger.catchThrowable(TAG, "AndroidSVG 解析失败，尝试内置渲染器兜底", e);
            // 标准库偶尔会拒绝一些写法古怪的 SVG，退回项目内的基础形状渲染器，
            // 至少能把 rect/polyline/line/path 画出来，而不是直接报错空白。
            Drawable fallback = renderSvgWithBuiltin(sanitized);
            if (fallback != null) return fallback;
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

    /**
     * 标准库解析失败时的兜底：用项目内的基础渲染器画出 rect/polyline/line/path。
     * 只支持基础形状与纯色，渐变会退化为首色或透明，但不会白屏。
     */
    private Drawable renderSvgWithBuiltin(String svgContent) {
        try {
            SvgRenderer.SvgDocument doc = SvgRenderer.parse(svgContent);
            if (doc == null || doc.paths == null || doc.paths.isEmpty()) return null;
            float vw = doc.viewportWidth > 0 ? doc.viewportWidth : 24f;
            float vh = doc.viewportHeight > 0 ? doc.viewportHeight : 24f;
            int maxSize = dp(200);
            float scale = Math.min((float) maxSize / vw, (float) maxSize / vh);
            int bw = Math.max(1, Math.round(vw * scale));
            int bh = Math.max(1, Math.round(vh * scale));
            AppErrorLogger.logDebug(TAG, "内置渲染器兜底 paths=" + doc.paths.size() + " " + bw + "x" + bh);
            return SvgRenderer.render(doc, bw, bh);
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "内置渲染器兜底失败", t);
            return null;
        }
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
        int drawnCount = 0;
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
            drawnCount++;
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
                    paint.setColor(forPreview(color));
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
        if (drawnCount == 0) {
            throw new Exception("未找到可渲染的路径：请确认粘贴的是矢量 XML（<vector>），SVG 代码会自动切换模式");
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
            boolean xmlInput = detectXmlInput(src);
            if ("svg".equals(format)) {
                content = xmlInput ? convertXmlToSvg(src) : src;
            } else {
                content = xmlInput ? src : convertSvgToXml(src);
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

    /**
     * 预览用的「当前文字色」。深色主题下纯黑图标画在深色预览卡片上几乎看不见，
     * 所以 currentColor 在深色下取主题文字色。只影响预览，导出/复制仍是原始颜色。
     */
    private String previewInkColor() {
        if (!ThemeManager.isDarkMode(this)) return "#000000";
        return String.format(java.util.Locale.US, "#%06X",
                0xFFFFFF & ThemeManager.getOnSurfaceColor(this));
    }

    /** 同上：纯黑填充/描边在深色预览里替换成主题文字色，避免「预览一片黑」。 */
    private int forPreview(int color) {
        if (color == Color.BLACK && ThemeManager.isDarkMode(this)) {
            return ThemeManager.getOnSurfaceColor(this);
        }
        return color;
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /** 兼容原调用点：转换逻辑已抽到 {@link SvgToVectorConverter} 共用 */
    private String convertSvgToXml(String svg) {
        return SvgToVectorConverter.convert(svg);
    }

    private String convertXmlToSvg(String xml) {
        return SvgToVectorConverter.convertXmlToSvg(xml);
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
        if (!detectXmlInput(text) && text.contains("currentColor") && !hasColor("currentColor")) {
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
            } else if (!detectXmlInput(text) && text.contains("currentColor")) {
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
        String content = detectXmlInput(src) ? convertXmlToSvg(src) : src;
        content = bakeTransform(content, "svg");
        OutputStream os = getContentResolver().openOutputStream(uri);
        os.write(content.getBytes("UTF-8"));
        os.close();
        Toast.makeText(this, "SVG已导出", Toast.LENGTH_SHORT).show();
    }

    private void doExportXml(Uri uri) throws Exception {
        String src = codeEditText.getText().toString();
        if (src.isEmpty()) return;
        String content = detectXmlInput(src) ? src : convertSvgToXml(src);
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

    // ============ 工具 ============

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
