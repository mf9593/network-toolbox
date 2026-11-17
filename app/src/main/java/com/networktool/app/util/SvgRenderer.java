package com.networktool.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class SvgRenderer {

    private static final String TAG = "SvgRenderer";
    private static final String SVG_NS = "http://www.w3.org/2000/svg";

    public static class SvgPathInfo {
        public Path path;
        public String fill;
        public String stroke;
        public float strokeWidth;
        public String strokeLineCap;
        public String strokeLineJoin;

        SvgPathInfo(Path path, String fill, String stroke, float strokeWidth,
                    String strokeLineCap, String strokeLineJoin) {
            this.path = path;
            this.fill = fill;
            this.stroke = stroke;
            this.strokeWidth = strokeWidth;
            this.strokeLineCap = strokeLineCap;
            this.strokeLineJoin = strokeLineJoin;
        }
    }

    public static class SvgDocument {
        public List<SvgPathInfo> paths;
        public float viewportWidth;
        public float viewportHeight;

        SvgDocument(List<SvgPathInfo> paths, float vw, float vh) {
            this.paths = paths;
            this.viewportWidth = vw;
            this.viewportHeight = vh;
        }
    }

    public static SvgDocument parse(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(
                    xmlContent.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            String tagName = root.getTagName();
            if (!"svg".equals(tagName) && !"vector".equals(tagName)) {
                AppErrorLogger.logError(TAG, "parse: unknown root tag=" + tagName, null);
                return null;
            }

            float vw = 0, vh = 0;

            String vb = root.getAttribute("viewBox");
            if (!vb.isEmpty()) {
                String[] p = vb.split("[\\s,]+");
                if (p.length > 3) {
                    vw = parseFloat(p[2], 0);
                    vh = parseFloat(p[3], 0);
                } else {
                    AppErrorLogger.logDebug(TAG, "parse: viewBox format invalid=" + vb);
                }
            }

            if (vw <= 0 || vh <= 0) {
                vw = parseFloat(root.getAttribute("viewportWidth"), 24);
                vh = parseFloat(root.getAttribute("viewportHeight"), 24);
            }

            AppErrorLogger.logDebug(TAG, "parse: viewport=" + vw + "x" + vh + " tag=" + tagName + " viewBox=" + vb);

            String gFill = root.getAttribute("fill");
            if (gFill.isEmpty()) gFill = null;
            String gStroke = root.getAttribute("stroke");
            if (gStroke.isEmpty()) gStroke = null;
            String gStrokeWidth = root.getAttribute("stroke-width");
            if (gStrokeWidth.isEmpty()) gStrokeWidth = null;
            String gLinecap = root.getAttribute("stroke-linecap");
            if (gLinecap.isEmpty()) gLinecap = null;
            String gLinejoin = root.getAttribute("stroke-linejoin");
            if (gLinejoin.isEmpty()) gLinejoin = null;

            List<SvgPathInfo> paths = new ArrayList<>();
            collectPaths(root, paths, gFill, gStroke, gStrokeWidth, gLinecap, gLinejoin);

            return new SvgDocument(paths, vw, vh);
        } catch (Exception e) {
            AppErrorLogger.catchThrowable(TAG, "SVG解析失败", e);
            return null;
        }
    }

    private static void collectPaths(Element parent, List<SvgPathInfo> paths,
                                     String gFill, String gStroke, String gStrokeWidth,
                                     String gLinecap, String gLinejoin) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element el = (Element) children.item(i);
            String tag = el.getLocalName();
            if (tag == null) tag = el.getTagName();

            if ("g".equals(tag)) {
                String cFill = el.getAttribute("fill");
                if (cFill.isEmpty()) cFill = gFill;
                String cStroke = el.getAttribute("stroke");
                if (cStroke.isEmpty()) cStroke = gStroke;
                String cSw = el.getAttribute("stroke-width");
                if (cSw.isEmpty()) cSw = gStrokeWidth;
                String cLc = el.getAttribute("stroke-linecap");
                if (cLc.isEmpty()) cLc = gLinecap;
                String cLj = el.getAttribute("stroke-linejoin");
                if (cLj.isEmpty()) cLj = gLinejoin;
                collectPaths(el, paths, cFill, cStroke, cSw, cLc, cLj);
                continue;
            }

            if ("defs".equals(tag)) continue;

            String fill = el.getAttribute("fill");
            if (fill.isEmpty()) fill = gFill;
            String stroke = el.getAttribute("stroke");
            if (stroke.isEmpty()) stroke = gStroke;
            String sw = el.getAttribute("stroke-width");
            if (sw.isEmpty() && gStrokeWidth != null) sw = gStrokeWidth;
            String lc = el.getAttribute("stroke-linecap");
            if (lc.isEmpty() && gLinecap != null) lc = gLinecap;
            String lj = el.getAttribute("stroke-linejoin");
            if (lj.isEmpty() && gLinejoin != null) lj = gLinejoin;

            float strokeW = 0;
            if (!sw.isEmpty()) {
                try { strokeW = Float.parseFloat(sw); } catch (Exception ignored) { }
            }

            Path path = buildPath(tag, el);
            if (path != null) {
                String f = (fill != null && !"none".equals(fill)) ? normalizeColor(fill) : null;
                String s = (stroke != null && !"none".equals(stroke)) ? normalizeColor(stroke) : null;
                paths.add(new SvgPathInfo(path, f, s, strokeW, lc, lj));
            }
        }
    }

    private static Path buildPath(String tag, Element el) {
        switch (tag) {
            case "path": {
                String d = el.getAttribute("d");
                if (d.isEmpty()) return null;
                return svgPathToAndroidPath(d);
            }
            case "circle": {
                float cx = parseFloat(el.getAttribute("cx"), 0);
                float cy = parseFloat(el.getAttribute("cy"), 0);
                float r = parseFloat(el.getAttribute("r"), 0);
                if (r <= 0) return null;
                Path p = new Path();
                p.addCircle(cx, cy, r, Path.Direction.CW);
                return p;
            }
            case "ellipse": {
                float cx = parseFloat(el.getAttribute("cx"), 0);
                float cy = parseFloat(el.getAttribute("cy"), 0);
                float rx = parseFloat(el.getAttribute("rx"), 0);
                float ry = parseFloat(el.getAttribute("ry"), 0);
                if (rx <= 0 || ry <= 0) return null;
                Path p = new Path();
                RectF rect = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
                p.addOval(rect, Path.Direction.CW);
                return p;
            }
            case "rect": {
                float x = parseFloat(el.getAttribute("x"), 0);
                float y = parseFloat(el.getAttribute("y"), 0);
                float w = parseFloat(el.getAttribute("width"), 0);
                float h = parseFloat(el.getAttribute("height"), 0);
                if (w <= 0 || h <= 0) return null;
                float rx = parseFloat(el.getAttribute("rx"), 0);
                float ry = parseFloat(el.getAttribute("ry"), 0);
                Path p = new Path();
                if (rx > 0 || ry > 0) {
                    if (ry == 0) ry = rx;
                    if (rx > w / 2) rx = w / 2;
                    if (ry > h / 2) ry = h / 2;
                    float[] radii = {rx, ry, rx, ry, rx, ry, rx, ry};
                    p.addRoundRect(x, y, x + w, y + h, radii, Path.Direction.CW);
                } else {
                    p.addRect(x, y, x + w, y + h, Path.Direction.CW);
                }
                return p;
            }
            case "line": {
                float x1 = parseFloat(el.getAttribute("x1"), 0);
                float y1 = parseFloat(el.getAttribute("y1"), 0);
                float x2 = parseFloat(el.getAttribute("x2"), 0);
                float y2 = parseFloat(el.getAttribute("y2"), 0);
                Path p = new Path();
                p.moveTo(x1, y1);
                p.lineTo(x2, y2);
                return p;
            }
            case "polyline":
            case "polygon": {
                String pts = el.getAttribute("points");
                if (pts.isEmpty()) return null;
                List<Float> nums = new ArrayList<>();
                java.util.regex.Matcher nm = java.util.regex.Pattern.compile(
                        "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matcher(pts);
                while (nm.find()) nums.add(Float.parseFloat(nm.group()));
                if (nums.size() < 4) return null;
                Path p = new Path();
                p.moveTo(nums.get(0), nums.get(1));
                for (int j = 2; j + 1 < nums.size(); j += 2)
                    p.lineTo(nums.get(j), nums.get(j + 1));
                if ("polygon".equals(tag)) p.close();
                return p;
            }
        }
        return null;
    }

    public static Drawable renderFromXml(String xmlContent) throws Exception {
        SvgDocument doc = parse(xmlContent);
        if (doc == null) throw new Exception("SVG解析失败，XML格式无效或不是有效的SVG文档");
        return render(doc, Math.round(doc.viewportWidth), Math.round(doc.viewportHeight));
    }

    public static Drawable renderFromXml(String xmlContent, int outputWidth, int outputHeight) throws Exception {
        SvgDocument doc = parse(xmlContent);
        if (doc == null) throw new Exception("SVG解析失败，XML格式无效或不是有效的SVG文档");
        return render(doc, outputWidth, outputHeight);
    }

    public static Drawable render(SvgDocument doc, int outputWidth, int outputHeight) {
        if (doc == null || doc.paths == null || doc.paths.isEmpty()) return null;
        int w = Math.max(outputWidth, 1);
        int h = Math.max(outputHeight, 1);
        AppErrorLogger.logDebug(TAG, "render: output=" + w + "x" + h + " viewport="
                + doc.viewportWidth + "x" + doc.viewportHeight + " paths=" + doc.paths.size());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        if (doc.viewportWidth > 0 && doc.viewportHeight > 0) {
            float sx = (float) w / doc.viewportWidth;
            float sy = (float) h / doc.viewportHeight;
            AppErrorLogger.logDebug(TAG, "render: scale=" + sx + " " + sy);
            canvas.scale(sx, sy);
        }
        draw(canvas, doc.paths);
        return new BitmapDrawable(bmp);
    }

    public static void draw(Canvas canvas, List<SvgPathInfo> paths) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (SvgPathInfo info : paths) {
            if (info.path == null) continue;

            if (info.fill != null) {
                paint.reset();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.FILL);
                try {
                    paint.setColor(Color.parseColor(info.fill));
                } catch (Exception ignored) {
                    paint.setColor(0xFF000000);
                }
                canvas.drawPath(info.path, paint);
            }

            if (info.stroke != null) {
                paint.reset();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.STROKE);
                try {
                    paint.setColor(Color.parseColor(info.stroke));
                } catch (Exception ignored) {
                    paint.setColor(0xFF000000);
                }
                paint.setStrokeWidth(info.strokeWidth > 0 ? info.strokeWidth : 1);
                if (info.strokeLineCap != null) {
                    switch (info.strokeLineCap) {
                        case "round": paint.setStrokeCap(Paint.Cap.ROUND); break;
                        case "square": paint.setStrokeCap(Paint.Cap.SQUARE); break;
                    }
                }
                if (info.strokeLineJoin != null) {
                    switch (info.strokeLineJoin) {
                        case "round": paint.setStrokeJoin(Paint.Join.ROUND); break;
                        case "bevel": paint.setStrokeJoin(Paint.Join.BEVEL); break;
                    }
                }
                canvas.drawPath(info.path, paint);
            }
        }
    }

    public static View renderToView(Context context, String xmlContent) {
        SvgDocument doc = parse(xmlContent);
        if (doc == null) return null;
        int w = Math.round(doc.viewportWidth);
        int h = Math.round(doc.viewportHeight);
        if (w <= 0) w = 24;
        if (h <= 0) h = 24;
        final float fw = w, fh = h;
        final SvgDocument finalDoc = doc;
        return new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float sx = getWidth() / fw;
                float sy = getHeight() / fh;
                canvas.save();
                canvas.scale(sx, sy);
                SvgRenderer.draw(canvas, finalDoc.paths);
                canvas.restore();
            }
        };
    }

    // ============ SVG Path Data 解析 ============

    public static Path parsePathData(String d) {
        return svgPathToAndroidPath(d);
    }

    /**
     * 把 SVG / Android Vector 的 pathData 转成 {@link Path}。
     *
     * <p>这里以前是自己写的正则解析器，`z` 之后没有把当前点复位到子路径起点，
     * 紧跟其后的相对 `m` 就会整体偏移。典型例子：Material 的「文档 + 机器人脸」
     * 图标（M6 22…T18 22z m1-3h10…），内层整块被平移到画布外，预览里脸完全不见。</p>
     *
     * <p>现在直接用 androidx 的 {@link androidx.core.graphics.PathParser}——
     * 它就是 VectorDrawableCompat 解析 pathData 用的实现，和真机渲染结果一致，
     * 圆弧、指数、隐式重复参数、`z` 闭合等边界情况都不用再自己维护。</p>
     */
    private static Path svgPathToAndroidPath(String d) {
        if (d == null || d.trim().isEmpty()) return null;
        try {
            return androidx.core.graphics.PathParser.createPathFromPathData(d);
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "pathData 解析失败: " + d, t);
            return null;
        }
    }

    // ============ 工具方法 ============

    private static float parseFloat(String s, float def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String normalizeColor(String c) {
        if (c == null) return null;
        c = c.trim();
        if (c.equals("none")) return null;
        if (c.equals("currentColor")) return "#FF000000";
        if (c.startsWith("#")) {
            if (c.length() == 4)
                return "#FF" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
            if (c.length() == 7) return "#FF" + c.substring(1);
            return c;
        }
        return "#FF000000";
    }
}
