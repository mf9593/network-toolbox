package com.networktool.app.livetv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 直播源列表解析器，支持主流格式：
 *
 * <pre>
 * 1) M3U / M3U8
 *    #EXTM3U
 *    #EXTINF:-1 tvg-name="CCTV1" group-title="CCTV",CCTV-1 综合
 *    http://example.com/cctv1.m3u8
 *    （也支持 #EXTGRP:CCTV 作为分组标签）
 *
 * 2) TXT（DIYP / 常见 IPTV 列表）
 *    CCTV,#genre#
 *    CCTV-1 综合,http://a/ch1.m3u8#http://b/ch1.m3u8
 *    （一个频道多条地址用 # 分隔，自动合并成多线路）
 *
 * 3) JSON（DIYP / TVBox 直播源）
 *    {"lives":[{"group":"CCTV","channels":[{"name":"CCTV1","urls":["http://a"]}]}]}
 *    {"urls":[{"name":"CCTV1","url":"http://a"}]}
 *    [{"name":"CCTV1","url":"http://a","group":"CCTV"}]
 * </pre>
 *
 * 同一分组下「同标题」的多条地址会合并成一个频道的多个线路。
 */
public final class LivePlaylistParser {

    /** 允许的直播流协议 */
    private static final String URL_PATTERN =
            "(?i)^(https?|rtsp|rtsps|rtmp|rtmps|rtp|udp|mms|ftp|file)://.+";

    private LivePlaylistParser() {
    }

    public static List<LiveChannel> parse(String content) {
        List<LiveChannel> result = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) return result;
        String trimmed = content.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                parseJson(trimmed, new Builder(result));
            } else {
                parseText(content, new Builder(result));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /* ==================== 文本格式（M3U / TXT） ==================== */

    private static void parseText(String content, Builder builder) {
        String currentGroup = "默认";
        String pendingExtInfName = null;
        String pendingExtInfGroup = null;
        String pendingExtGrp = null;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty()) continue;

                // M3U 的 #EXTINF 行：#EXTINF:-1 tvg-name=".." group-title="..",频道名
                if (value.startsWith("#EXTINF")) {
                    int comma = value.lastIndexOf(',');
                    pendingExtInfName = comma >= 0 && comma + 1 < value.length()
                            ? value.substring(comma + 1).trim() : "未命名频道";
                    pendingExtInfGroup = extractAttribute(value, "group-title");
                    if (pendingExtInfGroup == null || pendingExtInfGroup.isEmpty()) {
                        pendingExtInfGroup = extractAttribute(value, "tvg-group");
                    }
                    continue;
                }
                // M3U 的分组标签：#EXTGRP:CCTV
                if (value.startsWith("#EXTGRP:")) {
                    String group = value.substring("#EXTGRP:".length()).trim();
                    if (!group.isEmpty()) pendingExtGrp = group;
                    continue;
                }
                if (value.startsWith("#")) continue;

                // TXT 分组行：CCTV,#genre#
                if (value.endsWith(",#genre#")) {
                    String group = value.substring(0, value.length() - "#genre#".length() - 1).trim();
                    if (!group.isEmpty()) {
                        currentGroup = group;
                        builder.group(group);
                    }
                    continue;
                }

                // M3U 的地址独占一行：上一行的 #EXTINF 已经给出频道名，这里直接成条
                if (isStreamUrl(value)) {
                    if (pendingExtInfName != null) {
                        String pureGroup = pendingExtInfGroup != null ? pendingExtInfGroup
                                : (pendingExtGrp != null ? pendingExtGrp : currentGroup);
                        builder.channel(pendingExtInfName, value, pureGroup);
                        currentGroup = pureGroup;
                    }
                    pendingExtInfName = null;
                    pendingExtInfGroup = null;
                    pendingExtGrp = null;
                    continue;
                }

                int comma = value.indexOf(',');
                if (comma <= 0 || comma + 1 >= value.length()) continue;

                String name = value.substring(0, comma).trim();
                String addressPart = value.substring(comma + 1).trim();
                boolean fromM3u = pendingExtInfName != null;
                if (fromM3u) {
                    name = pendingExtInfName;
                    pendingExtInfName = null;
                }
                String group = pendingExtInfGroup != null ? pendingExtInfGroup
                        : (pendingExtGrp != null ? pendingExtGrp : currentGroup);
                pendingExtInfGroup = null;
                pendingExtGrp = null;

                if (name.isEmpty() || addressPart.isEmpty()) continue;

                // 一个频道可能带多条地址：以 # 分隔（DIYP 规范）；M3U 里不做拆分，避免误伤带 # 的地址
                String[] parts = fromM3u ? new String[]{addressPart} : addressPart.split("#");
                boolean added = false;
                for (String part : parts) {
                    String url = part.trim();
                    if (!isStreamUrl(url)) continue;
                    builder.channel(name, url, group);
                    added = true;
                }
                if (added) currentGroup = group;
            }
        } catch (Exception ignored) {
        }
    }

    /* ==================== JSON 格式（DIYP / TVBox） ==================== */

    private static void parseJson(String content, Builder builder) throws Exception {
        if (content.startsWith("[")) {
            JSONArray array = new JSONArray(content);
            for (int i = 0; i < array.length(); i++) {
                JSONObject node = array.optJSONObject(i);
                if (node != null) parseJsonNode(node, null, builder);
            }
            return;
        }

        JSONObject root = new JSONObject(content);
        // 顶层直接是频道表 {"urls":[{...}]}
        JSONArray direct = root.optJSONArray("urls");
        if (direct != null) {
            for (int i = 0; i < direct.length(); i++) {
                JSONObject node = direct.optJSONObject(i);
                if (node != null) parseJsonNode(node, null, builder);
            }
        }
        // 标准直播源结构 {"lives":[{group, channels:[...]}]}
        JSONArray lives = root.optJSONArray("lives");
        if (lives != null) {
            for (int i = 0; i < lives.length(); i++) {
                JSONObject node = lives.optJSONObject(i);
                if (node != null) parseJsonNode(node, null, builder);
            }
        }
        // 单分组结构 {"group":"CCTV","channels":[...]}
        if (direct == null && lives == null) {
            parseJsonNode(root, null, builder);
        }
    }

    /** 递归解析一个 JSON 节点：分组（带 channels）或单个频道 */
    private static void parseJsonNode(JSONObject node, String parentGroup, Builder builder) {
        if (node == null) return;
        String group = firstNonEmpty(node.optString("group", null),
                node.optString("groupName", null), node.optString("name", null));
        String name = firstNonEmpty(node.optString("name", null),
                node.optString("channelName", null), node.optString("title", null));

        JSONArray children = node.optJSONArray("channels");
        if (children == null) children = node.optJSONArray("lives");
        if (children == null) children = node.optJSONArray("list");

        if (children != null) {
            // 分组节点：name 只是分组名
            String groupName = firstNonEmpty(group, parentGroup, "默认");
            builder.group(groupName);
            for (int i = 0; i < children.length(); i++) {
                parseJsonNode(children.optJSONObject(i), groupName, builder);
            }
            return;
        }

        // 频道节点
        String channelGroup = firstNonEmpty(node.optString("group", null), parentGroup, "默认");
        List<String> urls = new ArrayList<>();
        JSONArray urlsArray = node.optJSONArray("urls");
        if (urlsArray != null) {
            for (int i = 0; i < urlsArray.length(); i++) {
                String url = urlsArray.optString(i, "");
                if (isStreamUrl(url)) urls.add(url.trim());
            }
        }
        String single = node.optString("url", "");
        if (isStreamUrl(single)) urls.add(single.trim());

        if (name == null || urls.isEmpty()) return;
        for (String url : urls) {
            builder.channel(name, url, channelGroup);
        }
    }

    /* ==================== 合并与去重 ==================== */

    private static final class Builder {
        private final List<LiveChannel> result;
        private final Set<String> headers = new HashSet<>();
        private final Map<String, LiveChannel> mergedIndex = new HashMap<>();

        Builder(List<LiveChannel> result) {
            this.result = result;
        }

        /** 插入一个分组标题（同名只插一次） */
        void group(String group) {
            if (group == null || group.isEmpty()) return;
            if (headers.add(group)) {
                result.add(LiveChannel.header(group));
            }
        }

        /** 同分组同标题合并为多线路 */
        void channel(String name, String url, String group) {
            if (name == null || name.isEmpty() || url == null || url.isEmpty()) return;
            String safeGroup = group == null || group.isEmpty() ? "默认" : group;
            group(safeGroup);
            String key = safeGroup + "\u0000" + name;
            LiveChannel existing = mergedIndex.get(key);
            if (existing != null) {
                existing.addSource(url);
                return;
            }
            LiveChannel channel = LiveChannel.channel(name, url, safeGroup);
            mergedIndex.put(key, channel);
            result.add(channel);
        }
    }

    /* ==================== 工具方法 ==================== */

    private static boolean isStreamUrl(String url) {
        return url != null && url.matches(URL_PATTERN);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    public static String readText(InputStream input) {
        if (input == null) return "";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
                if (output.size() > 8 * 1024 * 1024) break;
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return text;
        } catch (Exception e) {
            return "";
        } finally {
            try {
                input.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String extractAttribute(String line, String key) {
        String needle = key + "=\"";
        int start = line.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
    }
}
