package com.networktool.app.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG → Android VectorDrawable(XML) 转换器。
 *
 * <p>从 {@code IconConverterActivity} 抽出来，供「在线图标库」等其它入口复用，
 * 避免两处各写一份转换逻辑。</p>
 *
 * <p>优先走 DOM 递归解析（能保留 {@code <g>} 的 transform 与 fill/stroke 继承）；
 * DOM 解析失败时退回正则兼容模式。所有 pathData 都保持原始字符串，
 * 具体绘制交给 {@code androidx.core.graphics.PathParser}，不在这里手写路径解析。</p>
 */
public final class SvgToVectorConverter {

    private static final String TAG = "SvgToVectorConverter";

    private static final Map<String, String> svgGradientColors = new HashMap<>();

    private SvgToVectorConverter() {
    }

    // ============ SVG ↔ XML ============

    public static String convert(String svg) {
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
    private static String convertSvgToXmlWithDom(String svg) throws Exception {
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
        collectSvgGradients(root);

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

    private static void appendSvgElement(Element element, SvgPaint parentPaint, StringBuilder out) {
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

        String fill = resolveSvgPaint(paint.fill);
        boolean strokeVisible = paint.stroke != null && !"none".equals(paint.stroke);
        if (fill == null) {
            fill = strokeVisible ? "none" : "#000000";
        }
        out.append("\n        android:fillColor=\"")
                .append("none".equals(fill) ? "#00000000" : svgColor(fill))
                .append("\"");

        if (strokeVisible) {
            out.append("\n        android:strokeColor=\"").append(svgColor(resolveSvgPaint(paint.stroke))).append("\"");
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

    private static boolean isSvgShape(String tag) {
        return "path".equals(tag) || "circle".equals(tag) || "ellipse".equals(tag)
                || "rect".equals(tag) || "line".equals(tag)
                || "polyline".equals(tag) || "polygon".equals(tag);
    }

    private static void applySvgPaint(Element element, SvgPaint paint) {
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

    private static String svgElementValue(Element element, String name) {
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

    /**
     * 收集文档里所有渐变的「首个色标颜色」，转换时用来把 url(#id) 退化成纯色。
     */
    private static void collectSvgGradients(Element root) {
        svgGradientColors.clear();
        collectSvgGradientsRecursive(root);
    }

    private static void collectSvgGradientsRecursive(Element element) {
        String tag = element.getLocalName() != null
                ? element.getLocalName() : element.getTagName();
        if (tag != null) {
            tag = tag.toLowerCase(java.util.Locale.US);
            if ("lineargradient".equals(tag) || "radialgradient".equals(tag)) {
                String id = element.getAttribute("id");
                if (id != null && !id.trim().isEmpty()) {
                    String color = firstStopColor(element);
                    if (color != null) svgGradientColors.put(id.trim(), color);
                }
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) collectSvgGradientsRecursive((Element) node);
        }
    }

    /** 取渐变里第一个能读到颜色的 stop（兼容属性和 style 两种写法） */
    private static String firstStopColor(Element gradient) {
        NodeList children = gradient.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element stop = (Element) node;
            String tag = stop.getLocalName() != null ? stop.getLocalName() : stop.getTagName();
            if (tag == null || !"stop".equalsIgnoreCase(tag)) continue;
            String color = svgElementValue(stop, "stop-color");
            if (color != null && !"none".equals(color)) return color;
        }
        return null;
    }

    /**
     * 把 fill/stroke 里的 {@code url(#gradient)} 解析成渐变首色。
     * Android Vector 要用 aapt:attr 内嵌 gradient，这里的转换器不支持，
     * 退化成纯色至少不会把图标涂成黑色。
     */
    private static String resolveSvgPaint(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (!v.startsWith("url(")) return v;
        int hash = v.indexOf('#');
        if (hash < 0) return "#000000";
        int close = v.indexOf(')', hash + 1);
        String id = close > hash ? v.substring(hash + 1, close) : v.substring(hash + 1);
        id = id.trim();
        if (id.startsWith("'") || id.startsWith("\"")) id = id.substring(1);
        if (id.endsWith("'") || id.endsWith("\"")) id = id.substring(0, id.length() - 1);
        String color = svgGradientColors.get(id);
        return color != null ? color : "#000000";
    }

    private static String svgElementAttrs(Element element) {
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

    private static String svgGroupAttrs(String transform) {
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

    private static String convertSvgToXmlLegacy(String svg) {
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

    private static String buildElementPath(String tag, String attrs) {
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

    /** #AARRGGBB → #RRGGBB（VectorDrawable 的 fillColor 支持八位，但 XML→SVG 时去掉 alpha） */
    public static String androidColor(String c) {
        if (c == null) return "#000000";
        return (c.length() == 9 && (c.charAt(1) == 'F' || c.charAt(1) == 'f'))
                ? "#" + c.substring(3) : c;
    }

    /** Vector XML → SVG，供转换页的「XML 模式」导出使用 */
    public static String convertXmlToSvg(String xml) {
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

    public static float parseF(String s) {
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

    public static String fmt(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return Integer.toString(Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    public static String svgAttr(String attrs, String name, String def) {
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

    public static String extract(String str, String attr, String def) {
        Matcher m = Pattern.compile(attr + "\\s*=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(str);
        return m.find() ? m.group(1) : def;
    }

    public static String svgColor(String c) {
        if (c == null || c.equals("none")) return "#00000000";
        if (c.startsWith("#")) {
            if (c.length() == 4) return "#FF" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
            if (c.length() == 7) return "#FF" + c.substring(1);
            return c;
        }
        return "#FF000000";
    }

}
