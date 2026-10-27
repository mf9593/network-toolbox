package com.networktool.app.player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 RTSP 兼容代理（127.0.0.1 回环）。
 *
 * <p>部分国产摄像头（LIVE555 方案）返回的 RTSP 报文不符合 RFC，会让 ExoPlayer 直接抛
 * {@code IllegalArgumentException}（无消息），表现为「地址和账号都对、预检 200 OK，但一点播放就失败」：</p>
 *
 * <ul>
 *     <li>{@code Public:} 头里带了 ExoPlayer 不认识的方法名 →
 *         {@code RtspMessageUtil.parseMethodString()} 直接抛异常；</li>
 *     <li>{@code Content-Base:} 头里的地址是 {@code rtsp://rtsp://ip:554/stream0/} 这种重复协议头，
 *         导致 SETUP 时 track 地址拼接错误。</li>
 * </ul>
 *
 * <p>做法：播放器连本机端口，本代理转发到摄像头，只改写上面两个响应头，其余字节
 * （包括 RTSP 正文与 TCP 内联的 RTP 帧）原样透传。鉴权头照常转发，因此 Basic/Digest 都不受影响。</p>
 */
public final class RtspCompatProxy implements Closeable {

    /** 面包屑日志出口，由播放器注入，便于把 SDP 修补细节写进日志 */
    public interface LogSink {
        void log(String message);
    }

    /** 全局日志出口（不注入时静默） */
    public static volatile LogSink logSink;

    private static final String TAG = "RtspCompatProxy";
    /** ExoPlayer 认识的方法集合，改写为这个安全值 */
    private static final String SAFE_PUBLIC_VALUE =
            "OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER";
    private static final Pattern PUBLIC_LINE = Pattern.compile("(?i)^public\\s*:.*$");
    private static final Pattern CONTENT_BASE_LINE =
            Pattern.compile("(?i)^content-base\\s*:\\s*(.*)$");
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final ServerSocket serverSocket;
    private final String targetHost;
    private final int targetPort;
    private final List<Socket> liveSockets = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    /** 已改写的响应头数量，用于面包屑日志确认兼容逻辑是否生效 */
    private volatile int rewrittenHeaders;
    /** SDP 被净化的次数（H.265 sprop 参数损坏时） */
    private volatile int sdpRewrites;
    /** 是否已经从摄像头拿到过 SDP（拿到说明鉴权是通过的） */
    private volatile boolean sdpSeen;

    private RtspCompatProxy(ServerSocket serverSocket, String targetHost, int targetPort) {
        this.serverSocket = serverSocket;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /**
     * 启动代理。
     *
     * @param host 摄像头地址
     * @param port RTSP 端口
     */
    public static RtspCompatProxy start(String host, int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        RtspCompatProxy proxy = new RtspCompatProxy(serverSocket, host, port);
        proxy.startAcceptLoop();
        return proxy;
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    public int getRewrittenHeaders() {
        return rewrittenHeaders;
    }

    /** SDP 被净化的次数（去掉了损坏的 H.265 sprop 参数） */
    public int getSdpRewrites() {
        return sdpRewrites;
    }

    /** 是否已经从摄像头拿到 SDP：拿到即说明鉴权通过，可用于压掉误报的 401 提示 */
    public boolean hasSdp() {
        return sdpSeen;
    }

    /**
     * 把原始 RTSP 地址改写成指向本代理的地址，保留账号密码、路径与查询参数。
     *
     * <p>例如 {@code rtsp://admin:123456@192.168.2.146:554/stream0} →
     * {@code rtsp://admin:123456@127.0.0.1:41235/stream0}</p>
     */
    public static String toProxyUrl(String originalUrl, int localPort) {
        if (originalUrl == null) return null;
        int schemeEnd = originalUrl.indexOf("://");
        if (schemeEnd < 0) return null;
        String rest = originalUrl.substring(schemeEnd + 3);

        String authority;
        String path;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            authority = rest;
            path = "/";
        } else {
            authority = rest.substring(0, slash);
            path = rest.substring(slash);
        }
        String userInfo = "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userInfo = authority.substring(0, at + 1);
        }
        return "rtsp://" + userInfo + "127.0.0.1:" + localPort + path;
    }

    private void startAcceptLoop() {
        Thread thread = new Thread(() -> {
            while (!closed) {
                Socket client = null;
                try {
                    client = serverSocket.accept();
                    Socket target = new Socket();
                    target.connect(new InetSocketAddress(targetHost, targetPort), 8000);
                    target.setTcpNoDelay(true);
                    client.setTcpNoDelay(true);
                    liveSockets.add(client);
                    liveSockets.add(target);
                    Socket finalClient = client;
                    Socket finalTarget = target;
                    new Thread(() -> relayClientToServer(finalClient, finalTarget), TAG + "-up").start();
                    new Thread(() -> relayServerToClient(finalClient, finalTarget), TAG + "-down").start();
                } catch (Throwable t) {
                    closeQuietly(client);
                    if (closed) break;
                }
            }
        }, TAG + "-accept");
        thread.setDaemon(true);
        thread.start();
    }

    /** 播放器 → 摄像头：原样透传（含 Authorization 等鉴权头） */
    private void relayClientToServer(Socket client, Socket target) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = client.getInputStream();
            OutputStream out = target.getOutputStream();
            int read;
            while (!closed && (read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(client);
            closeQuietly(target);
        }
    }

    /** 摄像头 → 播放器：按帧/报文转发，只改写不合规的响应头 */
    private void relayServerToClient(Socket client, Socket target) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = new BufferedInputStream(target.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 16384);
            while (!closed) {
                int first = in.read();
                if (first < 0) break;

                if (first == '$') {
                    // RTP/RTCP 内联帧：$ + 通道号(1B) + 长度(2B) + 数据，原样透传
                    int channel = in.read();
                    int high = in.read();
                    int low = in.read();
                    if (channel < 0 || high < 0 || low < 0) break;
                    int length = (high << 8) | low;
                    out.write('$');
                    out.write(channel);
                    out.write(high);
                    out.write(low);
                    copyExactly(in, out, length, buffer);
                    out.flush();
                    continue;
                }

                // RTSP 报文：读到空行（\r\n\r\n）为止
                ByteArrayOutputStream head = new ByteArrayOutputStream(512);
                head.write(first);
                if (!readHeaderBlock(in, head)) break;
                String headerText = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
                String patched = normalizeHeaderBlock(headerText);

                int bodyLength = parseContentLength(headerText);
                byte[] body = bodyLength > 0 ? readExactly(in, bodyLength) : null;
                if (body != null && isSdpBody(headerText, body)) {
                    sdpSeen = true;
                    if (body.length > 0) {
                        log("代理收到 SDP（" + body.length + " 字节）");
                    }
                    // 部分摄像头的 H.265 sprop-* 参数损坏，会让 ExoPlayer 解析 SPS 时数组越界闪退
                    byte[] sanitized = sanitizeSdp(body);
                    if (sanitized.length != body.length) {
                        patched = replaceContentLength(patched, sanitized.length);
                        sdpRewrites++;
                        rewrittenHeaders++;
                    }
                    body = sanitized;
                }

                out.write(patched.getBytes(StandardCharsets.ISO_8859_1));
                if (body != null) {
                    out.write(body);
                }
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(client);
            closeQuietly(target);
        }
    }

    /** 读到连续两个 CRLF 为止（即整个头部块），返回 false 表示连接已结束 */
    private boolean readHeaderBlock(InputStream in, ByteArrayOutputStream sink) throws IOException {
        int tail = 0;
        while (sink.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) return false;
            sink.write(b);
            // 检查末尾是否为 \r\n\r\n
            tail = ((tail << 8) | (b & 0xFF)) & 0xFFFFFFFF;
            if (tail == 0x0D0A0D0A) return true;
        }
        return true;
    }

    /** 改写 Public: 与 Content-Base: 两处不合规的头，其余行原样保留 */
    private String normalizeHeaderBlock(String headerText) {
        String[] lines = headerText.split("\r\n", -1);
        StringBuilder rebuilt = new StringBuilder(headerText.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (PUBLIC_LINE.matcher(line).matches()) {
                if (!SAFE_PUBLIC_VALUE.equalsIgnoreCase(valueOf(line))) {
                    rewrittenHeaders++;
                }
                line = "Public: " + SAFE_PUBLIC_VALUE;
            } else {
                Matcher contentBase = CONTENT_BASE_LINE.matcher(line);
                if (contentBase.matches()) {
                    String value = contentBase.group(1).trim();
                    String fixed = normalizeContentBase(value);
                    if (!fixed.equals(value)) {
                        rewrittenHeaders++;
                    }
                    line = "Content-Base: " + fixed;
                }
            }
            rebuilt.append(line);
            if (i < lines.length - 1) rebuilt.append("\r\n");
        }
        return rebuilt.toString();
    }

    /** 修正 {@code rtsp://rtsp://host/path/} 这类重复协议头，并保证以 / 结尾 */
    private static String normalizeContentBase(String value) {
        if (value == null) return "";
        String result = value;
        while (true) {
            String lower = result.toLowerCase(Locale.ROOT);
            if (lower.startsWith("rtsp://rtsp://")) {
                result = result.substring("rtsp://".length());
            } else if (lower.startsWith("rtsps://rtsp://") || lower.startsWith("rtsp://rtsps://")) {
                result = result.substring("rtsps://".length());
            } else {
                break;
            }
        }
        if (!result.isEmpty() && !result.endsWith("/")) {
            result = result + "/";
        }
        return result;
    }

    private static String valueOf(String headerLine) {
        int colon = headerLine.indexOf(':');
        return colon < 0 ? "" : headerLine.substring(colon + 1).trim();
    }

    private static int parseContentLength(String headerText) {
        for (String line : headerText.split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length")) {
                int colon = line.indexOf(':');
                if (colon < 0) return -1;
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void copyExactly(InputStream in, OutputStream out, int length, byte[] buffer)
            throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset == length) return data;
        byte[] trimmed = new byte[offset];
        System.arraycopy(data, 0, trimmed, 0, offset);
        return trimmed;
    }

    /** 判断报文正文是否为 SDP（看 Content-Type，退化为看 v= 开头） */
    private static boolean isSdpBody(String headerText, byte[] body) {
        if (headerText != null && headerText.toLowerCase(Locale.ROOT)
                .contains("content-type: application/sdp")) {
            return true;
        }
        return body != null && body.length > 2 && body[0] == 'v' && body[1] == '=';
    }

    /**
     * 净化 SDP：对 H.265（HEVC）轨道的 {@code a=fmtp} 去掉 sprop-* 参数。
     *
     * <p>不少摄像头的 sprop-vps/sps/pps 数据是损坏或截断的，ExoPlayer 解析时会在
     * {@code NalUnitUtil.skipH265ScalingList} 数组越界闪退，而且异常抛在播放器自己的
     * HandlerThread 上，应用层接不住（表现就是一播就崩）。去掉这些提示参数后，
     * 播放器改用码流里内嵌的 VPS/SPS/PPS。</p>
     */
    private static byte[] sanitizeSdp(byte[] body) {
        String sdp = new String(body, StandardCharsets.ISO_8859_1);
        String[] lines = sdp.split("\r\n", -1);
        List<String> output = new java.util.ArrayList<>(lines.length);
        boolean hevcTrack = false;
        boolean changed = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("m=")) {
                hevcTrack = false;
            } else if (trimmed.toLowerCase(Locale.ROOT).startsWith("a=rtpmap:")) {
                String value = trimmed.substring("a=rtpmap:".length());
                int space = value.indexOf(' ');
                if (space > 0) {
                    String encoding = value.substring(space + 1).trim().toUpperCase(Locale.ROOT);
                    if (encoding.startsWith("H265") || encoding.startsWith("HEVC")) {
                        hevcTrack = true;
                    }
                }
            } else if (hevcTrack && trimmed.toLowerCase(Locale.ROOT).startsWith("a=fmtp:")) {
                String rebuilt = repairHevcFmtp(trimmed);
                if (!rebuilt.equals(trimmed)) {
                    changed = true;
                    output.add(rebuilt);
                    continue;
                }
            }
            output.add(line);
        }
        if (!changed) return body;
        StringBuilder joined = new StringBuilder(sdp.length());
        for (int i = 0; i < output.size(); i++) {
            joined.append(output.get(i));
            if (i < output.size() - 1) joined.append("\r\n");
        }
        return joined.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 修复 H.265 fmtp 里的 sprop 参数。
     *
     * <p>不删参数（ExoPlayer 强制要求三个都在），而是把 sprop-vps/sps/pps 的值解成 NAL，
     * 按 NAL 头里的真实类型（32=VPS、33=SPS、34=PPS）重新归位，并去掉起始码/长度前缀等杂字节。
     * 很多国产摄像头会把这三个值写错位置，ExoPlayer 拿错字节当 SPS 解析就会数组越界闪退。</p>
     */
    private static String repairHevcFmtp(String fmtpLine) {
        int colon = fmtpLine.indexOf(':');
        if (colon < 0) return fmtpLine;
        String prefix = fmtpLine.substring(0, colon + 1);
        String params = fmtpLine.substring(colon + 1).trim();

        String payloadType = "";
        int space = params.indexOf(' ');
        if (space > 0) {
            payloadType = params.substring(0, space).trim();
            params = params.substring(space + 1);
        }

        java.util.List<String> others = new java.util.ArrayList<>();
        java.util.Map<Integer, byte[]> byNalType = new java.util.HashMap<>();
        boolean sawSprop = false;
        for (String rawParam : params.split(";")) {
            String single = rawParam.trim();
            if (single.isEmpty()) continue;
            int eq = single.indexOf('=');
            String name = eq > 0
                    ? single.substring(0, eq).trim().toLowerCase(Locale.ROOT)
                    : single.toLowerCase(Locale.ROOT);
            if (name.startsWith("sprop")) {
                sawSprop = true;
                collectNalUnits(eq > 0 ? single.substring(eq + 1) : "",
                        guessNalType(name), byNalType);
            } else {
                others.add(single);
            }
        }
        if (!sawSprop) return fmtpLine;

        byte[] vps = byNalType.get(32);
        byte[] sps = byNalType.get(33);
        byte[] pps = byNalType.get(34);
        if (vps == null || sps == null || pps == null) {
            log("SDP sprop 参数不成套（vps=" + (vps != null) + " sps=" + (sps != null)
                    + " pps=" + (pps != null) + "），改为去掉 sprop 避免解析越界闪退");
            // 宁可让播放器抛出可捕获的「缺少 sprop-vps」错误，也不要它解析坏数据把进程带崩
            StringBuilder fallback = new StringBuilder(prefix);
            if (!payloadType.isEmpty()) fallback.append(payloadType);
            for (String other : others) fallback.append(' ').append(other).append(';');
            int length = fallback.length();
            if (length > 0 && fallback.charAt(length - 1) == ';') {
                fallback.setLength(length - 1);
            }
            return fallback.toString();
        }

        StringBuilder rebuilt = new StringBuilder(prefix);
        // 注意：a=fmtp: 后面必须紧跟负载类型号，不能有空格，否则 ExoPlayer 整条属性作废
        if (!payloadType.isEmpty()) rebuilt.append(payloadType);
        rebuilt.append(" sprop-vps=").append(encodeBase64(vps))
                .append(";sprop-sps=").append(encodeBase64(sps))
                .append(";sprop-pps=").append(encodeBase64(pps));
        for (String other : others) rebuilt.append(';').append(other);
        String result = rebuilt.toString();
        if (!result.equals(fmtpLine)) {
            log("SDP 已按 NAL 类型重排 H.265 参数: " + abbreviate(fmtpLine) + " → " + abbreviate(result));
        }
        return result;
    }

    /** 参数名里带的 NAL 类型提示：sprop-vps → 32、sprop-sps → 33、sprop-pps → 34 */
    private static int guessNalType(String name) {
        if (name == null) return -1;
        if (name.contains("vps")) return 32;
        if (name.contains("sps")) return 33;
        if (name.contains("pps")) return 34;
        return -1;
    }

    /** H.265 参数集 NAL 头两字节：类型左移一位 + 0x01 */
    private static byte[] hevcNalHeader(int type) {
        return new byte[]{(byte) ((type << 1) & 0x3F), 0x01};
    }

    /** 读 NAL 头里的类型（只认参数集 32/33/34，其它返回 -1） */
    private static int hevcNalType(byte[] nal) {
        if (nal == null || nal.length < 3) return -1;
        int type = (nal[0] >> 1) & 0x3F;
        // 第二字节必须是 0x01（nuh_layer_id=0、temporal_id_plus1=1），否则不是合法参数集头
        if ((nal[1] & 0xFF) != 0x01) return -1;
        return (type == 32 || type == 33 || type == 34) ? type : -1;
    }

    /**
     * 从 sprop 值里解出所有 NAL，登记到「NAL 类型 → 字节」表里。
     *
     * <p>能认出 NAL 头就按头里的真实类型归位（兼容摄像头把三个值写错位置）；
     * 认不出说明这个摄像头省掉了 2 字节 NAL 头，按参数名补上再归位。</p>
     */
    private static void collectNalUnits(String value, int hintType,
                                        java.util.Map<Integer, byte[]> target) {
        if (value == null || value.isEmpty()) return;
        for (String part : value.split("[,:]")) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) continue;
            byte[] bytes = decodeBase64(trimmedPart);
            if (bytes == null || bytes.length < 2) continue;
            for (byte[] nal : splitNalUnits(bytes)) {
                if (nal.length < 2) continue;
                int type = hevcNalType(nal);
                byte[] unit = nal;
                if (type < 0) {
                    // 没有合法 NAL 头：按参数名补头
                    if (hintType < 0) continue;
                    byte[] header = hevcNalHeader(hintType);
                    unit = new byte[header.length + nal.length];
                    System.arraycopy(header, 0, unit, 0, header.length);
                    System.arraycopy(nal, 0, unit, header.length, nal.length);
                    type = hintType;
                }
                if (unit.length < 3) continue;
                target.put(type, unit);
            }
        }
    }

    /** 把一段字节切成独立 NAL（支持 annex-B 起始码、4 字节长度前缀、裸 NAL 三种形式） */
    private static java.util.List<byte[]> splitNalUnits(byte[] bytes) {
        java.util.List<byte[]> result = new java.util.ArrayList<>();
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1) {
                starts.add(i + 3);
            } else if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 1) {
                starts.add(i + 4);
            }
        }
        if (!starts.isEmpty()) {
            for (int i = 0; i < starts.size(); i++) {
                int from = starts.get(i);
                int to = i + 1 < starts.size() ? starts.get(i + 1) : bytes.length;
                while (to > from && bytes[to - 1] == 0) to--;
                if (to - from > 2) result.add(subArray(bytes, from, to));
            }
            return result;
        }
        if (bytes.length > 4) {
            int declared = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
            if (declared == bytes.length - 4) {
                result.add(subArray(bytes, 4, bytes.length));
                return result;
            }
        }
        result.add(bytes);
        return result;
    }

    private static byte[] subArray(byte[] source, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(source, from, out, 0, out.length);
        return out;
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.isEmpty()) return null;
        for (int flags : new int[]{android.util.Base64.DEFAULT, android.util.Base64.URL_SAFE}) {
            try {
                return android.util.Base64.decode(value, flags);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String encodeBase64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    private static void log(String message) {
        LogSink sink = RtspCompatProxy.logSink;
        if (sink != null) sink.log(message);
    }

    /** 正文长度变化后同步改写 Content-Length 头，否则播放器会按旧长度读错位 */
    private static String replaceContentLength(String headerText, int newLength) {
        String[] lines = headerText.split("\r\n", -1);
        StringBuilder rebuilt = new StringBuilder(headerText.length() + 8);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length")) {
                line = "Content-Length: " + newLength;
            }
            rebuilt.append(line);
            if (i < lines.length - 1) rebuilt.append("\r\n");
        }
        return rebuilt.toString();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(serverSocket);
        for (Socket socket : liveSockets) {
            closeQuietly(socket);
        }
        liveSockets.clear();
    }
}
