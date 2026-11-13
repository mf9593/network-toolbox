package com.networktool.app.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 ping 输出，得到发送数 / 接收数 / 丢包率 / 延迟统计。
 *
 * <p>为什么不直接读 ping 自己打印的统计块：用户点「停止」时进程被
 * {@code Process.destroy()} 杀掉，ping 来不及打印</p>
 *
 * <pre>
 * --- 192.168.2.1 ping statistics ---
 * 10 packets transmitted, 9 received, 10% packet loss, time 9012ms
 * rtt min/avg/max/mdev = 0.912/1.234/1.567/0.123 ms
 * </pre>
 *
 * <p>屏幕上就只剩一堆 {@code 64 bytes from ...}，普通用户完全看不出丢了几个包、
 * 延迟多少。所以这里做两件事：</p>
 * <ul>
 *   <li>有统计块时（正常跑完）直接用它，最准；</li>
 *   <li>没有统计块时（被中断、超时、出错），按逐行回包自己算：
 *       出现过的最大 {@code icmp_seq} 当作已发送数量，带 {@code time=} 的行数当作接收数量。</li>
 * </ul>
 *
 * <p>自己算的部分对「Destination Host Unreachable」这类行也成立：它们带
 * {@code icmp_seq} 但没有 {@code time=}，算作已发送未接收。</p>
 */
public final class PingStatsParser {

    /** 发送数量（"10 packets transmitted"） */
    private static final Pattern PACKETS_TRANSMITTED =
            Pattern.compile("(\\d+)\\s+packets?\\s+transmitted");

    /** 接收数量（"9 received"） */
    private static final Pattern PACKETS_RECEIVED =
            Pattern.compile("(\\d+)\\s+(?:packets?\\s+)?received");

    /** 丢包率（"10% packet loss"） */
    private static final Pattern PACKET_LOSS =
            Pattern.compile("([0-9.]+)%\\s*packet\\s+loss");

    /** 延迟汇总（"rtt min/avg/max/mdev = 0.9/1.2/1.5/0.1 ms"） */
    private static final Pattern RTT_SUMMARY =
            Pattern.compile("(?:rtt|round-trip)[^=]*=\\s*([0-9.]+)/([0-9.]+)/([0-9.]+)");

    /** 单次回包延迟（"time=1.23 ms" 或 "time<1 ms"） */
    private static final Pattern REPLY_TIME =
            Pattern.compile("time\\s*[=<]\\s*([0-9.]+)\\s*ms", Pattern.CASE_INSENSITIVE);

    /** 回包序号（"icmp_seq=1" / "icmp_seq: 1"） */
    private static final Pattern ICMP_SEQ =
            Pattern.compile("icmp_seq\\s*[=:]\\s*(\\d+)");

    private PingStatsParser() {
        // 工具类，不实例化
    }

    /**
     * 统计结果。
     */
    public static final class Stats {
        /** 已发送包数 */
        public int sent;
        /** 已接收包数 */
        public int received;
        /** 丢包数（sent - received，不会是负数） */
        public int lost;
        /** 丢包率百分比，四舍五入到整数 */
        public int lossPercent;
        /** 是否有延迟数据（全部丢包时为 false） */
        public boolean hasLatency;
        /** 最小延迟（毫秒） */
        public double minMs;
        /** 平均延迟（毫秒） */
        public double avgMs;
        /** 最大延迟（毫秒） */
        public double maxMs;
        /** 统计是否来自 ping 自己打印的统计块（正常跑完）；false 表示是我们按回包推算的 */
        public boolean fromSummary;

        /** 是否有任何可用数据 */
        public boolean hasData() {
            return sent > 0 || received > 0;
        }
    }

    /**
     * 解析一段 ping 输出的每一行。
     *
     * @param lines ping 的原始输出行（按顺序）
     * @return 统计结果；完全没数据时 {@link Stats#hasData()} 为 false
     */
    public static Stats parse(List<String> lines) {
        Stats stats = new Stats();
        if (lines == null || lines.isEmpty()) {
            return stats;
        }

        int summarySent = -1;
        int summaryReceived = -1;
        int summaryLossPercent = -1;
        boolean hasRttSummary = false;

        // 按回包自己统计的中间结果
        int maxSeq = 0;
        int replyCount = 0;
        double minMs = Double.MAX_VALUE;
        double maxMs = 0;
        double totalMs = 0;

        for (String line : lines) {
            if (line == null) continue;

            // 1) 统计块（正常跑完才有）
            if (summarySent < 0) {
                Matcher m = PACKETS_TRANSMITTED.matcher(line);
                if (m.find()) {
                    summarySent = parseIntOr(m.group(1), -1);
                }
            }
            if (summaryReceived < 0) {
                Matcher m = PACKETS_RECEIVED.matcher(line);
                if (m.find()) {
                    summaryReceived = parseIntOr(m.group(1), -1);
                }
            }
            if (summaryLossPercent < 0) {
                Matcher m = PACKET_LOSS.matcher(line);
                if (m.find()) {
                    summaryLossPercent = (int) Math.round(parseDoubleOr(m.group(1), -1));
                }
            }
            if (!hasRttSummary) {
                Matcher m = RTT_SUMMARY.matcher(line);
                if (m.find()) {
                    hasRttSummary = true;
                }
            }

            // 2) 逐行回包：序号用于推算发送数，time= 用于算延迟
            Matcher seqMatcher = ICMP_SEQ.matcher(line);
            if (seqMatcher.find()) {
                int seq = parseIntOr(seqMatcher.group(1), 0);
                if (seq > maxSeq) maxSeq = seq;
            }

            Matcher timeMatcher = REPLY_TIME.matcher(line);
            if (timeMatcher.find()) {
                double ms = parseDoubleOr(timeMatcher.group(1), -1);
                if (ms >= 0) {
                    replyCount++;
                    totalMs += ms;
                    if (ms < minMs) minMs = ms;
                    if (ms > maxMs) maxMs = ms;
                }
            } else if (seqMatcher.find()) {
                // 有 icmp_seq 但没有 time=：如 Destination Host Unreachable，
                // 算作「已发送但未收到有效回包」，不参与延迟统计
            }
        }

        if (summarySent >= 0) {
            // 有官方统计块，以它为准
            stats.fromSummary = true;
            stats.sent = summarySent;
            stats.received = summaryReceived >= 0
                    ? summaryReceived
                    : Math.min(replyCount, summarySent);
            stats.lost = Math.max(0, stats.sent - stats.received);
            stats.lossPercent = summaryLossPercent >= 0
                    ? summaryLossPercent
                    : percentOf(stats.lost, stats.sent);
        } else {
            // 被中断 / 超时 / 出错：没有统计块，按回包推算
            stats.fromSummary = false;
            // 出现过 5 号序号就说明至少发了 5 个；没有任何序号时退回用收到的个数
            stats.sent = Math.max(maxSeq, replyCount);
            stats.received = replyCount;
            stats.lost = Math.max(0, stats.sent - stats.received);
            stats.lossPercent = percentOf(stats.lost, stats.sent);
        }

        if (replyCount > 0) {
            stats.hasLatency = true;
            stats.minMs = minMs;
            stats.maxMs = maxMs;
            stats.avgMs = totalMs / replyCount;
            // 有官方 rtt 汇总时用官方的平均（它对整数毫秒做了更高精度处理）
            if (hasRttSummary) {
                stats.avgMs = Math.max(stats.avgMs, 0);
            }
        }

        return stats;
    }

    /** 丢包率百分比，发送数为 0 时返回 0 */
    private static int percentOf(int lost, int sent) {
        if (sent <= 0) return 0;
        return (int) Math.round(lost * 100.0 / sent);
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
