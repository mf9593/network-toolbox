package com.networktool.app.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RTSP 地址整理与播放失败诊断。
 *
 * <p>摄像头的 RTSP 地址常见两种写法：</p>
 * <ul>
 *     <li>{@code rtsp://admin:123456@host:554/stream0}</li>
 *     <li>{@code rtsp://host:554/stream0?username=admin&password=123456}</li>
 * </ul>
 *
 * <p>播放器只认第一种的「账号密码写在 URL 里」形式，第二种需要把查询参数里的
 * 账号密码搬到 userInfo 上，否则服务器会返回 401，表现为「播放失败 / Source error」。</p>
 */
public final class RtspDiagnostics {

    private RtspDiagnostics() {
    }

    /**
     * 整理 RTSP 地址：
     * 1) 把 {@code ?username=&password=} 搬到 URL 的 userInfo；
     * 2) 去掉这两个查询参数（部分摄像头带着它们会当作非法参数拒绝）。
     *
     * @param rawUrl 原始地址
     * @return 整理后的地址；解析失败时原样返回
     */
    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return rawUrl;
        try {
            String url = rawUrl.trim();
            int queryIndex = url.indexOf('?');
            if (queryIndex < 0) return url;

            String base = url.substring(0, queryIndex);
            String query = url.substring(queryIndex + 1);

            String user = null;
            String password = null;
            StringBuilder kept = new StringBuilder();
            for (String param : query.split("&")) {
                if (param.isEmpty()) continue;
                int eq = param.indexOf('=');
                String key = eq > 0 ? param.substring(0, eq) : param;
                String value = eq > 0 ? param.substring(eq + 1) : "";
                if ("username".equalsIgnoreCase(key) || "user".equalsIgnoreCase(key)) {
                    user = value;
                } else if ("password".equalsIgnoreCase(key) || "pass".equalsIgnoreCase(key)
                        || "pwd".equalsIgnoreCase(key)) {
                    password = value;
                } else if (kept.length() > 0) {
                    kept.append('&').append(param);
                } else {
                    kept.append(param);
                }
            }

            if (user == null && password == null) return url; // 没有需要搬运的账号密码

            // 已经有 userInfo 就不覆盖
            URI uri = new URI(base);
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                user = null;
                password = null;
            }

            StringBuilder result = new StringBuilder();
            result.append(uri.getScheme()).append("://");
            if (user != null) {
                result.append(user);
                if (password != null) result.append(':').append(password);
                result.append('@');
            }
            if (uri.getHost() != null) result.append(uri.getHost());
            if (uri.getPort() > 0) result.append(':').append(uri.getPort());
            if (uri.getRawPath() != null) result.append(uri.getRawPath());
            if (kept.length() > 0) result.append('?').append(kept);
            return result.toString();
        } catch (Exception ignored) {
            return rawUrl;
        }
    }

    /**
     * 发一条 RTSP DESCRIBE 预检，返回服务器响应状态行（如 {@code 200 OK} / {@code 401 Unauthorized}）。
     * 用于把「播放失败」翻译成用户看得懂的原因。
     *
     * @param rawUrl  RTSP 地址（可带 userInfo）
     * @param timeoutMs 连接与读取超时
     * @return 状态行；失败时返回错误描述（中文）
     */
    public static String describe(String rawUrl, int timeoutMs) {
        if (rawUrl == null || rawUrl.isEmpty()) return "地址为空";
        Socket socket = null;
        try {
            URI uri = new URI(normalizeUrl(rawUrl));
            String host = uri.getHost();
            if (host == null) return "地址无法解析";
            int port = uri.getPort() > 0 ? uri.getPort() : 554;

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            StringBuilder request = new StringBuilder();
            request.append("DESCRIBE ").append(uri.getScheme()).append("://")
                    .append(host).append(':').append(port);
            if (uri.getRawPath() != null) request.append(uri.getRawPath());
            if (uri.getRawQuery() != null) request.append('?').append(uri.getRawQuery());
            request.append(" RTSP/1.0\r\n");
            request.append("CSeq: 1\r\n");
            request.append("User-Agent: NetworkToolbox/2.1.0\r\n");
            request.append("Accept: application/sdp\r\n");
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                String encoded = Base64.getEncoder()
                        .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
                request.append("Authorization: Basic ").append(encoded).append("\r\n");
            }
            request.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                    StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            if (statusLine == null) return "服务器无响应";

            String result = statusLine;
            // 401/403 时再读一行 WWW-Authenticate，便于区分 Basic / Digest
            if (statusLine.contains("401") || statusLine.contains("403")) {
                String line;
                int guard = 0;
                while ((line = reader.readLine()) != null && guard++ < 12) {
                    if (line.toLowerCase().startsWith("www-authenticate")) {
                        result = statusLine + "（" + line + "）";
                        break;
                    }
                    if (line.isEmpty()) break;
                }
            }
            return translate(result);
        } catch (java.net.SocketTimeoutException e) {
            return "连接超时（端口不通或被防火墙拦截）";
        } catch (java.net.ConnectException e) {
            return "连接被拒绝（端口不对或设备未开启 RTSP）";
        } catch (Exception e) {
            return "预检失败：" + e.getClass().getSimpleName();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 把 RTSP 状态行翻译成中文提示 */
    private static String translate(String statusLine) {
        if (statusLine == null) return "未知响应";
        if (statusLine.contains("200")) return "200 OK（地址与账号可用）";
        if (statusLine.contains("401")) {
            return statusLine.contains("Digest")
                    ? "401 需要 Digest 鉴权（当前播放器仅支持 Basic，可能因此失败）"
                    : "401 未授权（账号或密码不对）";
        }
        if (statusLine.contains("403")) return "403 拒绝访问（账号无该路径权限）";
        if (statusLine.contains("404")) return "404 路径不存在（stream 路径写错）";
        if (statusLine.contains("454")) return "454 会话不存在";
        return statusLine;
    }
}
