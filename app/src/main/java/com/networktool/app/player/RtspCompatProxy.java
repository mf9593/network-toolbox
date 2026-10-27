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
                out.write(patched.getBytes(StandardCharsets.ISO_8859_1));
                int bodyLength = parseContentLength(headerText);
                if (bodyLength > 0) {
                    copyExactly(in, out, bodyLength, buffer);
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
