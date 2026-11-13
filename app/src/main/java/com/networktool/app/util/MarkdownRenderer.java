package com.networktool.app.util;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.TypedValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Markdown渲染工具类
 * 
 * 功能说明：
 * - 从 assets 目录读取 Markdown 文件
 * - 将 Markdown 简单语法转换为 HTML
 * - 支持深色模式样式
 * 
 * 支持的 Markdown 语法：
 * - # 标题 (h1-h6)
 * - **粗体**
 * - - 无序列表
 * - 普通段落
 */
public class MarkdownRenderer {

    /**
     * 从 assets 目录读取 Markdown 文件并转换为 HTML
     *
     * @param context 上下文
     * @param fileName Markdown 文件名（如 "help.md"）
     * @return 转换后的 HTML 字符串
     */
    public static String renderMarkdown(Context context, String fileName) {
        return renderMarkdown(context, fileName, ThemeManager.isDarkMode(context));
    }

    /**
     * 从 assets 目录读取 Markdown 文件并转换为 HTML
     *
     * @param context 上下文
     * @param fileName Markdown 文件名（如 "help.md"）
     * @param isDarkMode 是否为深色模式
     * @return 转换后的 HTML 字符串
     */
    public static String renderMarkdown(Context context, String fileName, boolean isDarkMode) {
        String markdown = readFromAssets(context, fileName);
        if (markdown == null || markdown.isEmpty()) {
            return "<html><body><p>文件加载失败</p></body></html>";
        }
        return convertToHtml(context, markdown, isDarkMode);
    }

    /**
     * 从 assets 目录读取文件
     */
    private static String readFromAssets(Context context, String fileName) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return sb.toString();
    }

    /**
     * 将 Markdown 转换为 HTML。
     *
     * <p>二级标题（{@code ##}）会被渲染成一个可折叠章节，并在正文前自动生成目录。
     * 折叠用的是 HTML5 原生 {@code <details>/<summary>}，不是自己写的展开逻辑。</p>
     */
    private static String convertToHtml(Context context, String markdown, boolean isDarkMode) {
        String[] lines = markdown.split("\n");
        StringBuilder html = new StringBuilder();

        // 从主题解析实际颜色值
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        String bgColor = colorToHex(tv.data);
        context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        String textColor = colorToHex(tv.data);
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
        String subTextColor = colorToHex(tv.data);
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true);
        String accentColor = colorToHex(tv.data);
        String borderColor = isDarkMode ? "#33FFFFFF" : "#1F000000";
        String cardColor = isDarkMode ? "#1FFFFFFF" : "#08000000";

        // 先收集二级标题，用于生成目录
        java.util.List<String> sections = new java.util.ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                sections.add(line.substring(3).trim());
            }
        }

        html.append("<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            .append("<style>")
            .append("body { background-color: ").append(bgColor).append("; color: ").append(textColor)
            .append("; padding: 16px; font-family: sans-serif; line-height: 1.6; }")
            .append("h1 { color: ").append(textColor).append("; font-size: 24px; margin: 0 0 16px 0; }")
            .append("h2 { color: ").append(textColor).append("; font-size: 18px; margin: 0; }")
            .append("h3 { color: ").append(accentColor).append("; font-size: 16px; margin: 18px 0 6px 0; }")
            .append("p { color: ").append(textColor).append("; margin: 8px 0; }")
            .append("ul { padding-left: 20px; margin: 6px 0; }")
            .append("li { color: ").append(textColor).append("; margin: 4px 0; }")
            .append("strong { font-weight: bold; }")
            .append("hr { border: none; border-top: 1px solid ").append(borderColor).append("; margin: 16px 0; }")
            // 目录
            .append(".toc { background-color: ").append(cardColor).append("; border: 1px solid ")
            .append(borderColor).append("; border-radius: 12px; padding: 12px 14px; margin-bottom: 18px; }")
            .append(".toc-title { color: ").append(subTextColor)
            .append("; font-size: 12px; letter-spacing: 1px; margin-bottom: 8px; }")
            .append(".toc-item { display: block; color: ").append(accentColor)
            .append("; text-decoration: none; font-size: 15px; padding: 7px 0; border-bottom: 1px solid ")
            .append(borderColor).append("; }")
            .append(".toc-item:last-child { border-bottom: none; }")
            // 可折叠章节
            .append("details.sec { border: 1px solid ").append(borderColor)
            .append("; border-radius: 12px; margin-bottom: 10px; background-color: ")
            .append(cardColor).append("; }")
            .append("details.sec > summary { cursor: pointer; padding: 14px; font-size: 17px; font-weight: bold; color: ")
            .append(textColor).append("; list-style: none; }")
            .append("details.sec > summary::-webkit-details-marker { display: none; }")
            .append("details.sec > summary::after { content: '\u2304'; float: right; color: ")
            .append(subTextColor).append("; }")
            .append("details.sec[open] > summary::after { content: '\u2303'; }")
            .append(".sec-body { padding: 0 14px 14px 14px; }")
            .append("</style>")
            // 目录点击时把对应章节展开（<details> 默认只滚动不展开）
            .append("<script>function ntOpen(id){var d=document.getElementById(id);if(d){d.open=true;}return true;}</script>")
            .append("</head><body>");

        // 目录：章节数够多才生成，两三节反而占地方
        if (sections.size() >= 2) {
            html.append("<div class='toc'><div class='toc-title'>目录 · 点击展开</div>");
            for (int i = 0; i < sections.size(); i++) {
                html.append("<a class='toc-item' href='#nt-sec-").append(i)
                    .append("' onclick=\"return ntOpen('nt-sec-").append(i).append("')\">")
                    .append(processInline(sections.get(i))).append("</a>");
            }
            html.append("</div>");
        }

        boolean inList = false;
        boolean sectionOpen = false;
        int sectionIndex = -1;

        for (String line : lines) {
            line = line.trim();

            // 跳过空行
            if (line.isEmpty()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                continue;
            }

            // 分割线
            if (line.equals("---") || line.equals("***")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<hr>");
                continue;
            }

            // 标题
            if (line.startsWith("#")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                int level = 0;
                for (char c : line.toCharArray()) {
                    if (c == '#') level++;
                    else break;
                }
                if (level > 6) level = 6;
                String content = line.substring(level).trim();

                if (level == 2) {
                    // 二级标题开一个新章节，上一节先收尾
                    if (sectionOpen) {
                        html.append("</div></details>");
                    }
                    sectionIndex++;
                    html.append("<details class='sec' id='nt-sec-").append(sectionIndex).append("'")
                        .append(sectionIndex == 0 ? " open" : "").append(">")
                        .append("<summary>").append(processInline(content)).append("</summary>")
                        .append("<div class='sec-body'>");
                    sectionOpen = true;
                } else {
                    html.append("<h").append(level).append(">")
                        .append(processInline(content))
                        .append("</h").append(level).append(">");
                }
                continue;
            }

            // 无序列表
            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                String content = line.substring(2).trim();
                html.append("<li>").append(processInline(content)).append("</li>");
                continue;
            }

            // 普通段落
            if (inList) {
                html.append("</ul>");
                inList = false;
            }
            html.append("<p>").append(processInline(line)).append("</p>");
        }

        if (inList) {
            html.append("</ul>");
        }
        if (sectionOpen) {
            html.append("</div></details>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String colorToHex(int color) {
        return String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * 处理行内 Markdown 语法(粗体)
     */
    private static String processInline(String text) {
        // 先转义 HTML 特殊字符
        text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        // 再处理 **粗体** (使用转义后的标记)
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        return text;
    }
}
