package com.networktool.app.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探测「上级网段」。
 *
 * <p>典型场景：手机连在 192.168.2.0/24 的路由器上，这台路由器的 WAN 口接在
 * 上级 192.168.1.0/24 里。此时 192.168.1.x 的设备其实是能 ping 通的（经过路由器转发），
 * 但广播/多播过不了路由器，默认扫描也只扫本机所在的 /24，所以永远发现不了它们。</p>
 *
 * <p>做法是 traceroute 的原理：把 ping 的 TTL 依次设成 1、2、3……，
 * 每一跳的路由器都会回一条 ICMP 超时报文，从报文的 from 地址就能拿到跳点 IP。
 * 取第一个「私网地址且与本机不在同一个 /24」的跳点，作为额外的扫描网段。</p>
 *
 * <p>不依赖 root，也不需要新的权限；ping 用系统自带的 /system/bin/ping。</p>
 */
public class UpstreamNetworkDetector {

    private static final String TAG = "UpstreamDetector";

    /** 探测目标：任意公网地址即可，走到第一跳就会超时返回，不需要真的能访问 */
    private static final String PROBE_TARGET = "223.5.5.5";

    /** 最多向上找 4 跳 */
    private static final int MAX_TTL = 4;

    /** 单次 ping 的超时（秒） */
    private static final int PING_TIMEOUT_SECONDS = 1;

    private static final Pattern FROM_PATTERN =
            Pattern.compile("^From\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private UpstreamNetworkDetector() {
    }

    /**
     * 同步探测上级网段，会阻塞 1~4 秒左右，必须在子线程调用。
     *
     * @param localIpv4 本机 IPv4 地址，用于排除本网段
     * @return 上级网关 IP（例如 192.168.1.1），探测不到返回 null
     */
    public static String detect(String localIpv4) {
        String localPrefix = NetworkUtils.subnetPrefix24(localIpv4);
        for (int ttl = 1; ttl <= MAX_TTL; ttl++) {
            String hop = pingHop(ttl);
            if (hop == null) continue;
            // 公网跳点说明上级没有内网，继续往上找也没意义
            if (!NetworkUtils.isPrivateIpv4(hop)) return null;
            // 172.16/12 在运营商内部网络、CGNAT、VPN 里大量使用，误判率太高，不当上级局域网
            if (!NetworkUtils.isCommonLanSubnet(hop)) continue;
            String hopPrefix = NetworkUtils.subnetPrefix24(hop);
            if (hopPrefix == null) continue;
            if (hopPrefix.equals(localPrefix)) continue; // 本机网关，同网段
            AppErrorLogger.logDebug(TAG, "发现上级网段网关: " + hop + " (TTL=" + ttl + ")");
            return hop;
        }
        return null;
    }

    /**
     * 发一个指定 TTL 的探测包，返回回应 ICMP 超时的路由器 IP。
     */
    private static String pingHop(int ttl) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/ping",
                    "-c", "1",
                    "-W", String.valueOf(PING_TIMEOUT_SECONDS),
                    "-t", String.valueOf(ttl),
                    PROBE_TARGET);
            builder.redirectErrorStream(true);
            process = builder.start();

            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                // 已经拿到跳点就不用继续读了
                if (parseHop(output.toString()) != null) break;
            }
            process.waitFor(PING_TIMEOUT_SECONDS + 2L, TimeUnit.SECONDS);
            return parseHop(output.toString());
        } catch (Exception e) {
            AppErrorLogger.logDebug(TAG, "TTL " + ttl + " 探测失败: " + e.getMessage());
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 从 ping 输出里提取 "From 192.168.1.1 icmp_seq=1 Time to live exceeded" 中的 IP。
     * 只认超时报文，直连成功时的 "bytes from" 不算跳点。
     */
    private static String parseHop(String output) {
        if (output == null || output.isEmpty()) return null;
        Matcher matcher = FROM_PATTERN.matcher(output);
        if (!matcher.find()) return null;
        return matcher.group(1);
    }
}
