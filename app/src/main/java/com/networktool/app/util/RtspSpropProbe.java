package com.networktool.app.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从摄像头的 RTP 码流里抓取 H.264 / H.265 的参数集（SDP 里 sprop 缺省时用）。
 *
 * <p>部分设备（例如 LIBZRTSP 方案的 {@code live0.264}）在 SDP 里只写
 * {@code a=rtpmap:96 H265/90000}，既没有 {@code a=fmtp}，也没有 sprop-vps/sps/pps。
 * 这种 SDP 不符合 RFC 7798，ExoPlayer 解析时会直接抛
 * {@code IllegalArgumentException: missing attribute fmtp}，表现为「地址与账号都对、
 * 预检 200 OK，但一点播放就失败」。</p>
 *
 * <p>参数集其实就在码流里（IDR 帧前面必然带 VPS/SPS/PPS）。这里先用一次极短的
 * RTSP 会话（DESCRIBE → SETUP → PLAY）把这几帧抓出来，再交给调用方补进 SDP 的 fmtp，
 * 让播放器能正常建轨；抓完立即 TEARDOWN，不影响后续正式播放。</p>
 */
public final class RtspSpropProbe {

    /** 日志出口（可为 null） */
    public interface Logger {
        void log(String message);
    }

    /** H.265 的 NAL 类型 */
    private static final int HEVC_VPS = 32;
    private static final int HEVC_SPS = 33;
    private static final int HEVC_PPS = 34;
    /** H.264 的 NAL 类型 */
    private static final int AVC_SPS = 7;
    private static final int AVC_PPS = 8;

    private RtspSpropProbe() {
    }

    /**
     * 探测视频轨参数集，返回可直接放进 fmtp 的属性串。
     *
     * @param url       带账号密码的 RTSP 地址
     * @param timeoutMs 整体超时（探测发生在 DESCRIBE 返回前，建议 ≤ 4 秒）
     * @return H.265 返回 {@code sprop-vps=..;sprop-sps=..;sprop-pps=..}；
     * H.264 返回 {@code sprop-parameter-sets=<sps>,<pps>}；失败返回 null
     */
    public static String probeFmtp(String url, int timeoutMs, Logger logger) {
        if (url == null || url.isEmpty()) return null;
        Conn conn = null;
        DatagramSocket udpRtp = null;
        DatagramSocket udpRtcp = null;
        try {
            java.net.URI uri = new java.net.URI(RtspDiagnostics.normalizeUrl(url));
            String host = uri.getHost();
            if (host == null) return null;
            int port = uri.getPort() > 0 ? uri.getPort() : 554;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath();
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) path = path + "?" + query;

            String userInfo = uri.getUserInfo();
            String user = null;
            String password = null;
            if (userInfo != null && !userInfo.isEmpty()) {
                int colon = userInfo.indexOf(':');
                user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
                password = colon >= 0 ? userInfo.substring(colon + 1) : "";
            }

            String targetUrl = "rtsp://" + host + ":" + port + path;
            // 整个探测必须在播放器的 DESCRIBE 超时（ExoPlayer 默认 8 秒）之内返回
            long endAt = System.currentTimeMillis() + Math.max(1500, timeoutMs);
            conn = new Conn(host, port, Math.min(timeoutMs, 3000));
            conn.setDeadline(endAt);

            // ① DESCRIBE：先 Basic，401 且要 Digest 时再算一次
            String basicAuth = null;
            if (user != null) {
                String basic = RtspDiagnostics.basicAuthorization(user, password);
                if (basic != null) basicAuth = "Authorization: " + basic + "\r\n";
            }
            String challenge = null;
            Resp describe = conn.request("DESCRIBE", targetUrl,
                    "Accept: application/sdp\r\n" + (basicAuth == null ? "" : basicAuth));
            if (describe != null && describe.status == 401 && user != null) {
                String next = describe.header("www-authenticate");
                if (next != null && next.toLowerCase(Locale.ROOT).contains("digest")) {
                    challenge = next;
                    describe = conn.request("DESCRIBE", targetUrl,
                            "Accept: application/sdp\r\n"
                                    + authHeader("DESCRIBE", targetUrl, basicAuth,
                                    challenge, user, password));
                }
            }
            if (describe == null || describe.status != 200 || describe.body == null) {
                log(logger, "参数集探测: DESCRIBE 失败 "
                        + (describe == null ? "无响应" : String.valueOf(describe.status)));
                return null;
            }

            SdpInfo info = parseSdp(describe.body, describe.header("content-base"), targetUrl);
            if (info == null || info.trackUrl == null) {
                log(logger, "参数集探测: SDP 里没有可用的视频轨");
                return null;
            }
            if (hasFmtpFor(describe.body, info.payloadType)) {
                // 已经带了 fmtp，不需要补
                return null;
            }

            // ② SETUP：优先 TCP 内联，失败再试 UDP
            boolean tcp = true;
            Resp setup = conn.request("SETUP", info.trackUrl,
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
                            + authHeader("SETUP", info.trackUrl, basicAuth, challenge,
                            user, password));
            if (setup == null || setup.status != 200) {
                tcp = false;
                udpRtp = new DatagramSocket();
                int rtcpPort = udpRtp.getLocalPort() + 1;
                try {
                    udpRtcp = new DatagramSocket(rtcpPort);
                } catch (Exception ignored) {
                    // 端口被占也不影响：参数集只需要 RTP
                    udpRtcp = null;
                }
                setup = conn.request("SETUP", info.trackUrl,
                        "Transport: RTP/AVP;unicast;client_port=" + udpRtp.getLocalPort()
                                + "-" + rtcpPort + "\r\n"
                                + authHeader("SETUP", info.trackUrl, basicAuth, challenge,
                                user, password));
            }
            if (setup == null || setup.status != 200) {
                log(logger, "参数集探测: SETUP 失败 "
                        + (setup == null ? "无响应" : String.valueOf(setup.status)));
                return null;
            }
            String session = setup.header("session");
            if (session != null) {
                int semi = session.indexOf(';');
                if (semi > 0) session = session.substring(0, semi);
                session = session.trim();
            }

            // ③ PLAY，然后读 RTP 直到参数集齐了或超时
            // PLAY 按 RFC 应打在会话（聚合）地址上，串流地址只在 SETUP 用
            String baseUrl = describe.header("content-base");
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = targetUrl;
            } else {
                baseUrl = baseUrl.trim();
            }
            Resp play = conn.request("PLAY", baseUrl,
                    "Range: npt=0.000-\r\n"
                            + (session == null ? "" : "Session: " + session + "\r\n")
                            + authHeader("PLAY", baseUrl, basicAuth, challenge, user, password));
            if (play == null || play.status != 200) {
                log(logger, "参数集探测: PLAY 失败 "
                        + (play == null ? "无响应" : String.valueOf(play.status)));
                teardown(conn, baseUrl, session, authHeader("TEARDOWN", baseUrl, basicAuth,
                        challenge, user, password));
                return null;
            }

            byte[] vps = null;
            byte[] sps = null;
            byte[] pps = null;
            NalCollector collector = new NalCollector();
            java.util.List<NalInfo> nals = new java.util.ArrayList<>(4);
            long deadline = Math.min(endAt, System.currentTimeMillis() + 2000);
            while (System.currentTimeMillis() < deadline) {
                byte[] rtp = tcp
                        ? conn.readInterleavedPacket(deadline)
                        : readUdpPacket(udpRtp, deadline);
                if (rtp == null) continue;
                nals.clear();
                collector.push(rtp, info.hevc, nals);
                for (NalInfo nal : nals) {
                    if (info.hevc) {
                        if (nal.type == HEVC_VPS && vps == null) vps = nal.data;
                        else if (nal.type == HEVC_SPS && sps == null) sps = nal.data;
                        else if (nal.type == HEVC_PPS && pps == null) pps = nal.data;
                    } else {
                        if (nal.type == AVC_SPS && sps == null) sps = nal.data;
                        else if (nal.type == AVC_PPS && pps == null) pps = nal.data;
                    }
                }
                if (info.hevc) {
                    if (vps != null && sps != null && pps != null) break;
                } else if (sps != null && pps != null) {
                    break;
                }
            }

            teardown(conn, baseUrl, session, authHeader("TEARDOWN", baseUrl, basicAuth,
                    challenge, user, password));
            // 给摄像头一点时间释放这一路会话，避免紧接着播放器的 SETUP 被拒
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            if (info.hevc) {
                if (vps == null || sps == null || pps == null) {
                    log(logger, "参数集探测: 没抓全 H.265 参数集（VPS/SPS/PPS）");
                    return null;
                }
                String fmtp = "sprop-vps=" + b64(vps)
                        + ";sprop-sps=" + b64(sps)
                        + ";sprop-pps=" + b64(pps);
                log(logger, "参数集探测: 已抓到 H.265 参数集（VPS " + vps.length
                        + "B / SPS " + sps.length + "B / PPS " + pps.length + "B）");
                return fmtp;
            }
            if (sps == null || pps == null) {
                log(logger, "参数集探测: 没抓全 H.264 参数集（SPS/PPS）");
                return null;
            }
            String fmtp = "sprop-parameter-sets=" + b64(sps) + "," + b64(pps);
            log(logger, "参数集探测: 已抓到 H.264 参数集（SPS " + sps.length
                    + "B / PPS " + pps.length + "B）");
            return fmtp;
        } catch (Throwable t) {
            log(logger, "参数集探测异常: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (udpRtp != null) udpRtp.close();
            if (udpRtcp != null) udpRtcp.close();
            if (conn != null) conn.close();
        }
    }

    /** 收一个 UDP RTP 包 */
    private static byte[] readUdpPacket(DatagramSocket socket, long deadline) {
        if (socket == null) return null;
        try {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) return null;
            socket.setSoTimeout((int) Math.max(1, remain));
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(buffer, 0, data, 0, packet.getLength());
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    private static void teardown(Conn conn, String url, String session, String auth) {
        if (conn == null) return;
        try {
            conn.request("TEARDOWN", url,
                    (session == null ? "" : "Session: " + session + "\r\n")
                            + (auth == null ? "" : auth), true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 生成一条 {@code Authorization: ...} 请求头（含结尾 CRLF）。
     *
     * <p>优先按 Digest 挑战现算（每次请求的 method/uri 都要匹配），否则退回 Basic。</p>
     */
    private static String authHeader(String method, String uri, String basicAuth,
                                    String challenge, String user, String password) {
        if (challenge != null && user != null) {
            String digest = RtspDiagnostics.buildDigestAuthorization(
                    challenge, user, password, method, uri);
            if (digest != null) return "Authorization: " + digest + "\r\n";
        }
        return basicAuth == null ? "" : basicAuth;
    }

    private static String b64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static void log(Logger logger, String message) {
        if (logger != null) logger.log(message);
    }

    /** SDP 里视频轨的关键信息 */
    private static final class SdpInfo {
        String trackUrl;
        boolean hevc;
        int payloadType;
    }

    /**
     * 解析 SDP，找出视频轨的 control 地址、编码与负载类型。
     */
    private static SdpInfo parseSdp(String sdp, String contentBase, String describeUrl) {
        String base = contentBase != null && !contentBase.isEmpty() ? contentBase : describeUrl;
        String control = null;
        boolean hevc = false;
        boolean inVideo = false;
        int payloadType = -1;
        String mediaType = null;

        for (String rawLine : sdp.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("m=")) {
                inVideo = line.startsWith("m=video");
                control = null;
                hevc = false;
                payloadType = -1;
                if (inVideo) {
                    String[] parts = line.substring(2).split("\\s+");
                    if (parts.length >= 4) {
                        try {
                            payloadType = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                continue;
            }
            if (!inVideo) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("a=rtpmap:")) {
                String value = line.substring("a=rtpmap:".length()).trim();
                int space = value.indexOf(' ');
                if (space > 0) {
                    String pt = value.substring(0, space);
                    String codec = value.substring(space + 1).trim().toUpperCase(Locale.ROOT);
                    try {
                        payloadType = Integer.parseInt(pt);
                    } catch (NumberFormatException ignored) {
                    }
                    if (codec.startsWith("H265") || codec.startsWith("HEVC")) {
                        hevc = true;
                        mediaType = "H265";
                    } else if (codec.startsWith("H264")) {
                        mediaType = "H264";
                    }
                }
            } else if (lower.startsWith("a=control:")) {
                control = line.substring("a=control:".length()).trim();
            }
        }
        if (mediaType == null) return null;

        SdpInfo info = new SdpInfo();
        info.hevc = hevc;
        info.payloadType = payloadType;
        if (control == null || "*".equals(control)) {
            info.trackUrl = base;
        } else if (control.startsWith("rtsp://") || control.startsWith("rtsps://")) {
            info.trackUrl = control;
        } else {
            String normalizedBase = base.endsWith("/") ? base : base + "/";
            info.trackUrl = normalizedBase + control;
        }
        return info;
    }

    /** 这条 SDP 是否已经给了该负载类型的 fmtp */
    private static boolean hasFmtpFor(String sdp, int payloadType) {
        String prefix = "a=fmtp:" + payloadType + " ";
        for (String rawLine : sdp.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 从 RTP 包里取出参数集 NAL（支持单 NAL、STAP/AP 聚合、FU 分片） */
    private static final class NalInfo {
        int type;
        byte[] data;
    }

    /**
     * 极简 RTP 负载拆解器：只关心参数集（H.264 的 SPS/PPS、H.265 的 VPS/SPS/PPS）。
     *
     * <p>参数集通常是一个完整 NAL，但被 MTU 切开（FU）或和其它 NAL 打包在一起
     * （H.264 STAP-A / H.265 AP）也很常见，必须原样拼完整，否则喂给播放器的是半截
     * SPS，会直接导致解析越界。</p>
     */
    private static final class NalCollector {
        private int fuType = -1;
        private byte[] fuNalHeader;
        private ByteArrayOutputStream fuData;

        /** 送入一个 RTP 包，把其中的完整参数集 NAL 追加到 out */
        void push(byte[] packet, boolean hevc, java.util.List<NalInfo> out) {
            if (packet == null || packet.length < 13) return;
            int firstByte = packet[0] & 0xFF;
            if ((firstByte >> 6) != 2) return;
            int offset = 12 + (firstByte & 0x0F) * 4;
            if ((firstByte & 0x10) != 0) {
                if (offset + 4 > packet.length) return;
                int words = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
                offset += 4 + words * 4;
            }
            int end = packet.length;
            if ((firstByte & 0x20) != 0) {
                int padding = packet[end - 1] & 0xFF;
                if (padding > 0 && end - padding > offset) end -= padding;
            }
            if (offset >= end) return;
            if (hevc) {
                pushHevc(packet, offset, end, out);
            } else {
                pushAvc(packet, offset, end, out);
            }
        }

        private void pushHevc(byte[] packet, int offset, int end, java.util.List<NalInfo> out) {
            int type = (packet[offset] >> 1) & 0x3F;
            if (type == 49) {          // FU
                if (offset + 3 > end) return;
                int subType = packet[offset + 2] & 0x3F;
                boolean start = (packet[offset + 2] & 0x80) != 0;
                boolean finish = (packet[offset + 2] & 0x40) != 0;
                appendFragment(packet, offset, end, subType, start, finish, 2, true, out);
                return;
            }
            if (type == 48) {          // AP：多个 NAL 打包
                resetFu();
                int p = offset + 2;
                while (p + 2 <= end) {
                    int length = ((packet[p] & 0xFF) << 8) | (packet[p + 1] & 0xFF);
                    p += 2;
                    if (length <= 0 || p + length > end) break;
                    addIfParameterSet(packet, p, p + length, true, out);
                    p += length;
                }
                return;
            }
            resetFu();
            addIfParameterSet(packet, offset, end, true, out);
        }

        private void pushAvc(byte[] packet, int offset, int end, java.util.List<NalInfo> out) {
            int type = packet[offset] & 0x1F;
            if (type == 28) {          // FU-A
                if (offset + 2 > end) return;
                int subType = packet[offset + 1] & 0x1F;
                boolean start = (packet[offset + 1] & 0x80) != 0;
                boolean finish = (packet[offset + 1] & 0x40) != 0;
                appendFragment(packet, offset, end, subType, start, finish, 1, false, out);
                return;
            }
            if (type == 24) {          // STAP-A
                resetFu();
                int p = offset + 1;
                while (p + 2 <= end) {
                    int length = ((packet[p] & 0xFF) << 8) | (packet[p + 1] & 0xFF);
                    p += 2;
                    if (length <= 0 || p + length > end) break;
                    addIfParameterSet(packet, p, p + length, false, out);
                    p += length;
                }
                return;
            }
            resetFu();
            addIfParameterSet(packet, offset, end, false, out);
        }

        /**
         * 处理一个 FU 分片。
         *
         * @param headerLength 原 NAL 头长度（H.264 是 1，H.265 是 2）
         */
        private void appendFragment(byte[] packet, int offset, int end, int subType,
                                    boolean start, boolean finish, int headerLength,
                                    boolean hevc, java.util.List<NalInfo> out) {
            int dataStart = offset + 1 + headerLength;   // FU indicator(N) + FU header
            if (dataStart > end) return;
            if (start) {
                fuType = subType;
                if (headerLength == 2) {
                    fuNalHeader = new byte[]{
                            (byte) ((packet[offset] & 0x81) | ((subType & 0x3F) << 1)),
                            packet[offset + 1]};
                } else {
                    fuNalHeader = new byte[]{(byte) ((packet[offset] & 0xE0) | (subType & 0x1F))};
                }
                fuData = new ByteArrayOutputStream();
                fuData.write(packet, dataStart, end - dataStart);
            } else if (fuData != null && subType == fuType) {
                fuData.write(packet, dataStart, end - dataStart);
            } else {
                resetFu();
                return;
            }
            if (!finish) return;
            byte[] body = fuData.toByteArray();
            byte[] data = new byte[fuNalHeader.length + body.length];
            System.arraycopy(fuNalHeader, 0, data, 0, fuNalHeader.length);
            System.arraycopy(body, 0, data, fuNalHeader.length, body.length);
            int finishedType = fuType;
            resetFu();
            if (isParameterSet(finishedType, hevc)) {
                NalInfo info = new NalInfo();
                info.type = finishedType;
                info.data = data;
                out.add(info);
            }
        }

        private void addIfParameterSet(byte[] packet, int from, int to,
                                       boolean hevc, java.util.List<NalInfo> out) {
            int type = hevc
                    ? ((packet[from] & 0xFF) >> 1) & 0x3F
                    : (packet[from] & 0xFF) & 0x1F;
            if (!isParameterSet(type, hevc)) return;
            NalInfo info = new NalInfo();
            info.type = type;
            info.data = slice(packet, from, to);
            out.add(info);
        }

        private void resetFu() {
            fuType = -1;
            fuNalHeader = null;
            fuData = null;
        }
    }

    private static boolean isParameterSet(int type, boolean hevc) {
        return hevc
                ? type == HEVC_VPS || type == HEVC_SPS || type == HEVC_PPS
                : type == AVC_SPS || type == AVC_PPS;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }

    /* ==================== 极简 RTSP 连接 ==================== */

    /** 一条 RTSP 报文（应答） */
    private static final class Resp {
        int status;
        Map<String, String> headers = new HashMap<>();
        String body;

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final class Conn implements Closeable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private int cseq;
        /** 整体探测截止时间：所有请求都必须在它之前完成，否则播放器会先超时 */
        private long deadline;

        void setDeadline(long endAt) {
            this.deadline = endAt;
        }

        Conn(String host, int port, int timeoutMs) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream(), 8192);
            out = socket.getOutputStream();
        }

        /** 发一条请求并读回应答；fireAndForget=true 时不等待应答内容 */
        Resp request(String method, String url, String extraHeaders) throws IOException {
            return request(method, url, extraHeaders, false);
        }

        Resp request(String method, String url, String extraHeaders, boolean fireAndForget)
                throws IOException {
            if (deadline > 0) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new IOException("参数集探测超时");
                socket.setSoTimeout((int) Math.max(200, Math.min(remain, 3000)));
            }
            cseq++;
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
            sb.append("CSeq: ").append(cseq).append("\r\n");
            sb.append("User-Agent: NetworkToolbox/2.1.0\r\n");
            if (extraHeaders != null) sb.append(extraHeaders);
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            if (fireAndForget) return null;
            return readResponse();
        }

        Resp readResponse() throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream(512);
            int tail = 0;
            while (head.size() < 64 * 1024) {
                int b = in.read();
                if (b < 0) return null;
                head.write(b);
                tail = ((tail << 8) | (b & 0xFF)) & 0xFFFFFFFF;
                if (tail == 0x0D0A0D0A) break;
            }
            String text = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n", -1);
            if (lines.length == 0) return null;
            Resp resp = new Resp();
            try {
                String[] statusParts = lines[0].split("\\s+");
                resp.status = statusParts.length > 1 ? Integer.parseInt(statusParts[1]) : -1;
            } catch (Exception e) {
                resp.status = -1;
            }
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                resp.headers.put(name, value);
                if ("content-length".equals(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (contentLength > 0) {
                byte[] body = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int read = in.read(body, offset, contentLength - offset);
                    if (read < 0) break;
                    offset += read;
                }
                resp.body = new String(body, 0, offset, StandardCharsets.ISO_8859_1);
            }
            return resp;
        }

        /** 读一个 RTSP 内联 RTP 包（$ 帧），跳过中间的 RTSP 报文 */
        byte[] readInterleavedPacket(long deadline) throws IOException {
            while (System.currentTimeMillis() < deadline) {
                socket.setSoTimeout(300);
                int first;
                try {
                    first = in.read();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (first < 0) return null;
                socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                if (first != '$') {
                    // RTSP 报文（例如 PLAY 的后续通知），读掉整段
                    ByteArrayOutputStream head = new ByteArrayOutputStream(256);
                    head.write(first);
                    int tail = first & 0xFF;
                    while (head.size() < 64 * 1024) {
                        int b = in.read();
                        if (b < 0) return null;
                        head.write(b);
                        tail = ((tail << 8) | (b & 0xFF)) & 0xFFFFFFFF;
                        if (tail == 0x0D0A0D0A) break;
                    }
                    continue;
                }
                int channel = in.read();
                int high = in.read();
                int low = in.read();
                if (channel < 0 || high < 0 || low < 0) return null;
                int length = (high << 8) | low;
                byte[] data = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int read = in.read(data, offset, length - offset);
                    if (read < 0) return null;
                    offset += read;
                }
                // 只处理 RTP（偶数通道），RTCP 跳过
                if ((channel & 1) != 0) continue;
                return data;
            }
            return null;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
