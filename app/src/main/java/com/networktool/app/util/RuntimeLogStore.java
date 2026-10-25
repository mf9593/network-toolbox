package com.networktool.app.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行期内存日志存储（报文/协议调试信息）
 *
 * 与 AppErrorLogger 的崩溃日志不同，本存储**只保留在内存中**，
 * 进程退出后自动清空，因此：
 * - 不落盘，不占用磁盘空间，也避免把可能含密码/密钥的报文持久化
 * - 适合临时查看某个协议的原始报文（比如标题截取不对时核对来源数据）
 *
 * 使用方式：
 * <pre>
 * RuntimeLogStore.record("ONVIF", "192.168.1.10", "收发", rawData);
 * RuntimeLogStore.record("mDNS", "192.168.1.20", "收到", hostName);
 * </pre>
 */
public final class RuntimeLogStore {

    /** 协议名常量，方便过滤时统一 */
    public static final String P_ONVIF = "ONVIF";
    public static final String P_MDNS = "mDNS";
    public static final String P_SSDP = "SSDP";
    public static final String P_SADP = "SADP";
    public static final String P_HTTP = "HTTP";
    public static final String P_NETBIOS = "NetBIOS";
    public static final String P_ARP = "ARP表";
    public static final String P_UDP = "UDP广播";
    public static final String P_ROUTER = "路由器";
    public static final String P_PING = "Ping探测";
    public static final String P_IPV6 = "IPv6";
    public static final String P_SERVICE = "端口探测";

    /** 最大保留条数，超出后丢弃最旧的 */
    private static final int MAX_ENTRIES = 1000;

    /** 单条报文最大字符数，超出截断，避免超大 XML 占用过多内存 */
    private static final int MAX_CONTENT_CHARS = 4000;

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private RuntimeLogStore() {
    }

    /**
     * 单条日志
     */
    public static final class Entry {
        /** 时间戳（毫秒） */
        public final long time;
        /** 协议名 */
        public final String protocol;
        /** 来源（通常是 IP） */
        public final String source;
        /** 方向/事件，如 "收到" / "发送" */
        public final String direction;
        /** 原始内容 */
        public final String content;

        Entry(long time, String protocol, String source, String direction, String content) {
            this.time = time;
            this.protocol = protocol;
            this.source = source;
            this.direction = direction;
            this.content = content;
        }
    }

    /**
     * 记录一条报文
     *
     * @param protocol  协议名（ONVIF/mDNS/SSDP/SADP/HTTP 等）
     * @param source    来源，通常为 IP；可为空
     * @param direction 方向/事件描述，可为空
     * @param content   原始报文内容，可为空
     */
    public static void record(String protocol, String source, String direction, String content) {
        if (protocol == null || protocol.isEmpty()) {
            return;
        }
        // 归一化 IPv6 变体协议名（ONVIF_IPv6 -> ONVIF），便于按设备标签过滤
        String normProtocol = protocol;
        String normDirection = direction == null ? "" : direction;
        String lower = protocol.toLowerCase(Locale.US);
        if (lower.endsWith("_ipv6") || lower.endsWith("_v6")) {
            normProtocol = protocol.substring(0, protocol.lastIndexOf('_'));
            if (!normDirection.isEmpty()) normDirection += " ";
            normDirection += "(IPv6)";
        }
        String safeContent = content == null ? "" : content;
        if (safeContent.length() > MAX_CONTENT_CHARS) {
            safeContent = safeContent.substring(0, MAX_CONTENT_CHARS)
                    + "\n……（内容过长，已截断，共 " + content.length() + " 字符）";
        }
        Entry entry = new Entry(System.currentTimeMillis(), normProtocol,
                source == null ? "" : source,
                normDirection,
                safeContent);
        synchronized (ENTRIES) {
            ENTRIES.addLast(entry);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
        }
    }

    /**
     * 便捷方法：只记录来源和内容
     */
    public static void record(String protocol, String source, String content) {
        record(protocol, source, "收到", content);
    }

    /**
     * 获取全部条目（快照，倒序：最新在前）
     */
    public static List<Entry> getAll() {
        synchronized (ENTRIES) {
            List<Entry> list = new ArrayList<>(ENTRIES);
            Collections.reverse(list);
            return list;
        }
    }

    /**
     * 按协议过滤条目（倒序：最新在前）
     *
     * @param protocol 协议名，为 null/空时返回全部
     */
    public static List<Entry> filter(String protocol) {
        if (protocol == null || protocol.isEmpty()) {
            return getAll();
        }
        List<Entry> result = new ArrayList<>();
        synchronized (ENTRIES) {
            for (Entry e : ENTRIES) {
                if (protocol.equalsIgnoreCase(e.protocol)) {
                    result.add(e);
                }
            }
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * 按协议和关键词过滤（倒序：最新在前）
     *
     * @param protocol 协议名，可空
     * @param keyword  关键词，在协议名/来源/内容中模糊匹配，可空
     */
    public static List<Entry> search(String protocol, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        List<Entry> result = new ArrayList<>();
        synchronized (ENTRIES) {
            for (Entry e : ENTRIES) {
                if (protocol != null && !protocol.isEmpty() && !protocol.equalsIgnoreCase(e.protocol)) {
                    continue;
                }
                if (!kw.isEmpty()) {
                    boolean hit = e.protocol.contains(kw) || e.source.contains(kw)
                            || e.content.contains(kw);
                    if (!hit) continue;
                }
                result.add(e);
            }
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * 格式化单条为可读文本
     */
    public static String format(Entry e) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(e.time));
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(time).append("] ")
                .append("[").append(e.protocol).append("]");
        if (!e.source.isEmpty()) {
            sb.append(" [").append(e.source).append("]");
        }
        if (!e.direction.isEmpty()) {
            sb.append(" ").append(e.direction);
        }
        sb.append("\n");
        if (!e.content.isEmpty()) {
            sb.append(e.content).append("\n");
        }
        sb.append("----------------------------------------");
        return sb.toString();
    }

    /**
     * 清空内存中的全部记录
     */
    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    /**
     * 当前条目数
     */
    public static int size() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    /**
     * 把二进制报文转成可读文本（ASCII 优先 + 十六六进制补充），
     * 用于 mDNS / NetBIOS 等二进制协议的原始报文查看。
     *
     * @param data 原始字节
     * @param len  有效长度
     */
    public static String bytesToDisplay(byte[] data, int len) {
        if (data == null || len <= 0) return "(空报文)";
        int n = Math.min(len, data.length);
        StringBuilder sb = new StringBuilder(n * 2 + 64);
        StringBuilder ascii = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xff;
            sb.append(String.format(Locale.US, "%02x", b));
            ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
            if ((i + 1) % 16 == 0) {
                sb.append("  ").append(ascii).append("\n");
                ascii.setLength(0);
            } else {
                sb.append(' ');
            }
        }
        if (ascii.length() > 0) {
            int pad = 16 - ascii.length();
            for (int i = 0; i < pad; i++) sb.append("   ");
            sb.append("  ").append(ascii);
        }
        return sb.toString();
    }
}
