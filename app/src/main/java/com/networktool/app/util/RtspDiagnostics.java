package com.networktool.app.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

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
                    user = decodeValue(value);
                } else if ("password".equalsIgnoreCase(key) || "pass".equalsIgnoreCase(key)
                        || "pwd".equalsIgnoreCase(key)) {
                    password = decodeValue(value);
                } else if (kept.length() > 0) {
                    kept.append('&').append(param);
                } else {
                    kept.append(param);
                }
            }

            if (user == null && password == null) return url; // 没有需要搬运的账号密码

            // 查询参数里的账号密码优先于 URL 内已有的 userInfo：
            // 用户常见做法是只改查询参数，若继续沿用旧 userInfo 会一直 401
            URI uri = new URI(base);
            String existingUserInfo = uri.getUserInfo();
            if (existingUserInfo != null && !existingUserInfo.isEmpty()) {
                int colon = existingUserInfo.indexOf(':');
                String existingUser = colon >= 0
                        ? existingUserInfo.substring(0, colon) : existingUserInfo;
                String existingPassword = colon >= 0
                        ? existingUserInfo.substring(colon + 1) : "";
                if (user == null) user = existingUser;
                if (password == null) password = existingPassword;
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

    /** 一次 DESCRIBE 的结果 */
    private static final class ProbeResult {
        String statusLine;
        /** WWW-Authenticate 头，用于判断 Basic / Digest */
        String authenticate;
        /** 200 响应里的 SDP 正文（用于排查编解码器/传输不兼容） */
        String sdp;
        /** 诊断结论（面向用户的中文描述） */
        String conclusion;
    }

    /** 解码查询参数里的百分号转义（如密码里的 %40 → @），失败时原样返回 */
    private static String decodeValue(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 凭据摘要（只暴露账号名与密码长度，绝不输出密码本身），用于面包屑日志确认凭据是否真的生效。
     *
     * <p>排查「地址里明明有密码却提示 401」时，看一眼这行就能判断是凭据没解析出来、
     * 还是凭据解析对了但服务器不认。</p>
     */
    public static String credentialSummary(String rawUrl) {
        try {
            String normalized = normalizeUrl(rawUrl);
            URI uri = new URI(normalized);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || userInfo.isEmpty()) return "未携带账号密码";
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            String password = colon >= 0 ? userInfo.substring(colon + 1) : "";
            boolean fromQuery = rawUrl != null
                    && rawUrl.toLowerCase(java.util.Locale.ROOT).contains("password=");
            return "账号=" + user + " 密码长度=" + password.length()
                    + (fromQuery ? "（查询参数）" : "（URL 内嵌）");
        } catch (Exception e) {
            return "解析失败";
        }
    }

    /**
     * 发 RTSP DESCRIBE 预检，返回服务器响应状态行（如 {@code 200 OK} / {@code 401 Unauthorized}）。
     *
     * <p>先按 Basic 试一次；若服务器要求 Digest，会用同样的账号密码算出 Digest 应答再试一次，
     * 这样能区分「账号密码错」和「鉴权方式/传输不支持」——后者才是播放器侧的问题。</p>
     *
     * @param rawUrl    RTSP 地址（可带 userInfo，也可带 ?username=&password=）
     * @param timeoutMs 连接与读取超时
     * @return 人类可读的诊断结论
     */
    public static String describe(String rawUrl, int timeoutMs) {
        return performProbe(rawUrl, timeoutMs).conclusion;
    }

    /**
     * 抓取 SDP 摘要（m= 行、rtpmap、fmtp、control 等关键行）。
     *
     * <p>用于定位「服务器 200 OK 但播放器内部抛异常」的情况：SDP 里写的是哪种编码、
     * 传输方式（TCP/UDP interleaved）以及 control 地址，能直接说明是不是编码/传输不兼容。</p>
     *
     * @return 单行摘要；取不到时返回空串
     */
    public static String sdpSummary(String rawUrl, int timeoutMs) {
        ProbeResult result = performProbe(rawUrl, timeoutMs);
        return result.sdp == null ? "" : summarizeSdp(result.sdp);
    }

    /** 执行 Basic → Digest 的两次 DESCRIBE 探测，返回结论与 SDP */
    private static ProbeResult performProbe(String rawUrl, int timeoutMs) {
        ProbeResult out = new ProbeResult();
        if (rawUrl == null || rawUrl.isEmpty()) {
            out.conclusion = "地址为空";
            return out;
        }
        try {
            URI uri = new URI(normalizeUrl(rawUrl));
            String host = uri.getHost();
            if (host == null) {
                out.conclusion = "地址无法解析";
                return out;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 554;
            String path = uri.getRawPath() == null ? "/" : uri.getRawPath();

            String userInfo = uri.getUserInfo();
            String user = null;
            String password = null;
            if (userInfo != null && !userInfo.isEmpty()) {
                int colon = userInfo.indexOf(':');
                user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
                password = colon >= 0 ? userInfo.substring(colon + 1) : "";
            }

            // ① Basic 先试
            ProbeResult result = sendDescribe(host, port, path, basicHeader(user, password), timeoutMs);
            if (result == null || result.statusLine == null) {
                out.conclusion = "服务器无响应";
                return out;
            }

            if (result.statusLine.contains("401") && result.authenticate != null
                    && result.authenticate.toLowerCase(java.util.Locale.ROOT).contains("digest")
                    && user != null) {
                // ② 服务器要 Digest：算出应答再试一次
                String digestHeader = buildDigestHeader(result.authenticate, user, password, "DESCRIBE",
                        "rtsp://" + host + ":" + port + path);
                if (digestHeader != null) {
                    ProbeResult second = sendDescribe(host, port, path, digestHeader, timeoutMs);
                    if (second != null && second.statusLine != null) {
                        String line = second.statusLine;
                        out.sdp = second.sdp != null ? second.sdp : result.sdp;
                        if (line.contains("200")) {
                            out.conclusion = "200 OK（Digest 鉴权通过，地址与账号可用 → 失败在播放器/传输侧）";
                            return out;
                        }
                        if (line.contains("401")) {
                            out.conclusion = line + "（Digest 鉴权仍未通过，账号密码或权限有问题）";
                            return out;
                        }
                        out.conclusion = translate(line);
                        return out;
                    }
                }
                out.conclusion = "401 需要 Digest 鉴权（预检未能完成第二次鉴权）";
                return out;
            }
            out.conclusion = translate(result.statusLine);
            out.sdp = result.sdp;
            return out;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.net.SocketTimeoutException) {
                out.conclusion = "连接超时（端口不通或被防火墙拦截）";
            } else if (cause instanceof java.net.ConnectException) {
                out.conclusion = "连接被拒绝（端口不对或设备未开启 RTSP）";
            } else {
                out.conclusion = "预检失败：" + (cause != null ? cause.getClass().getSimpleName()
                        : e.getClass().getSimpleName());
            }
            return out;
        } catch (Exception e) {
            out.conclusion = "预检失败：" + e.getClass().getSimpleName();
            return out;
        }
    }

    /**
     * 抓取 DESCRIBE 响应的原始前若干字节，把回车/换行显式转义出来。
     *
     * <p>用于排查「服务器能返回 200 但播放器报 IllegalArgumentException（无消息）」：
     * 这类异常通常源于报文没按 CRLF 分行或状态行不合规，只有看到原始字节才能确认。</p>
     *
     * @return 单行可读文本；失败返回空串
     */
    public static String rawHead(String rawUrl, int timeoutMs) {
        if (rawUrl == null || rawUrl.isEmpty()) return "";
        Socket socket = null;
        try {
            URI uri = new URI(normalizeUrl(rawUrl));
            String host = uri.getHost();
            if (host == null) return "";
            int port = uri.getPort() > 0 ? uri.getPort() : 554;
            String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
            String userInfo = uri.getUserInfo();
            String user = null;
            String password = null;
            if (userInfo != null && !userInfo.isEmpty()) {
                int colon = userInfo.indexOf(':');
                user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
                password = colon >= 0 ? userInfo.substring(colon + 1) : "";
            }

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            StringBuilder request = new StringBuilder();
            request.append("DESCRIBE rtsp://").append(host).append(':').append(port)
                    .append(path).append(" RTSP/1.0\r\n");
            request.append("CSeq: 1\r\n");
            request.append("User-Agent: NetworkToolbox/2.1.0\r\n");
            request.append("Accept: application/sdp\r\n");
            String authorization = basicHeader(user, password);
            if (authorization != null) {
                request.append("Authorization: ").append(authorization).append("\r\n");
            }
            request.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 只读前面一段（状态行 + 头部），避免把整段 SDP 拖进来
            byte[] buffer = new byte[600];
            int offset = 0;
            InputStream in = socket.getInputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (offset < buffer.length && System.currentTimeMillis() < deadline) {
                int read;
                try {
                    read = in.read(buffer, offset, buffer.length - offset);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                if (read <= 0) break;
                offset += read;
                // 已经读到头部结束就够定位格式问题了
                String tail = new String(buffer, 0, offset, StandardCharsets.UTF_8);
                if (tail.contains("\r\n\r\n") || tail.contains("\n\n")) break;
            }
            if (offset <= 0) return "";
            String text = new String(buffer, 0, offset, StandardCharsets.UTF_8);
            return text.replace("\r", "\\r").replace("\n", "\\n");
        } catch (Exception e) {
            return "";
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 把 SDP 压缩成一行关键信息（编码、传输、control 地址） */    private static String summarizeSdp(String sdp) {
        StringBuilder summary = new StringBuilder();
        int kept = 0;
        for (String rawLine : sdp.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            boolean interesting = line.startsWith("m=") || line.startsWith("s=")
                    || lower.startsWith("a=rtpmap") || lower.startsWith("a=fmtp")
                    || lower.startsWith("a=control") || lower.startsWith("a=range")
                    || lower.startsWith("a=rtcp") || lower.startsWith("b=");
            if (!interesting) continue;
            if (summary.length() + line.length() > 900) {
                summary.append(" …");
                break;
            }
            if (kept++ > 0) summary.append(" / ");
            summary.append(line);
        }
        return summary.toString();
    }

    private static String basicHeader(String user, String password) {
        if (user == null) return null;
        // minSdk 21 上 java.util.Base64 不可用，用 android.util.Base64
        String encoded = android.util.Base64.encodeToString(
                (user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        return "Basic " + encoded;
    }

    /** 发一条 DESCRIBE，返回状态行与 WWW-Authenticate 头 */
    private static ProbeResult sendDescribe(String host, int port, String path,
                                            String authorization, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            StringBuilder request = new StringBuilder();
            request.append("DESCRIBE rtsp://").append(host).append(':').append(port)
                    .append(path).append(" RTSP/1.0\r\n");
            request.append("CSeq: 1\r\n");
            request.append("User-Agent: NetworkToolbox/2.1.0\r\n");
            request.append("Accept: application/sdp\r\n");
            if (authorization != null) {
                request.append("Authorization: ").append(authorization).append("\r\n");
            }
            request.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            ProbeResult result = new ProbeResult();
            result.statusLine = reader.readLine();
            if (result.statusLine == null) return result;

            int contentLength = -1;
            String line;
            int guard = 0;
            while ((line = reader.readLine()) != null && guard++ < 40) {
                if (line.isEmpty()) break;
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("www-authenticate")) {
                    result.authenticate = line;
                } else if (lower.startsWith("content-length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
            // 200 才有 SDP 正文，读回来供诊断分析（截断到 20KB，避免异常响应拖死）
            if (contentLength > 0 && result.statusLine.contains("200")) {
                StringBuilder body = new StringBuilder();
                int limit = Math.min(contentLength, 20000);
                int read;
                while (body.length() < limit && (read = reader.read()) != -1) {
                    body.append((char) read);
                }
                result.sdp = body.toString();
            }
            return result;
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(e);
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 构造 Digest 鉴权头（RFC 2617，qop=auth）。
     * 解析失败时返回 null。
     */
    private static String buildDigestHeader(String authenticate, String user, String password,
                                           String method, String uri) {
        try {
            String realm = extractDigestParam(authenticate, "realm");
            String nonce = extractDigestParam(authenticate, "nonce");
            if (realm == null || nonce == null) return null;
            String opaque = extractDigestParam(authenticate, "opaque");
            String algorithm = extractDigestParam(authenticate, "algorithm");
            String qop = extractDigestParam(authenticate, "qop");

            String ha1 = md5(user + ":" + realm + ":" + password);
            String ha2 = md5(method + ":" + uri);
            String cnonce = Long.toHexString(System.nanoTime());
            String nc = "00000001";
            String response;
            String qopValue = null;
            if (qop != null && qop.toLowerCase(java.util.Locale.ROOT).contains("auth")) {
                qopValue = "auth";
                response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qopValue + ":" + ha2);
            } else {
                response = md5(ha1 + ":" + nonce + ":" + ha2);
            }

            StringBuilder header = new StringBuilder("Digest ");
            header.append("username=\"").append(user).append("\", ");
            header.append("realm=\"").append(realm).append("\", ");
            header.append("nonce=\"").append(nonce).append("\", ");
            header.append("uri=\"").append(uri).append("\", ");
            header.append("response=\"").append(response).append("\"");
            if (algorithm != null) header.append(", algorithm=").append(algorithm);
            if (opaque != null) header.append(", opaque=\"").append(opaque).append("\"");
            if (qopValue != null) {
                header.append(", qop=").append(qopValue);
                header.append(", nc=").append(nc);
                header.append(", cnonce=\"").append(cnonce).append("\"");
            }
            return header.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 WWW-Authenticate 里取出指定参数值 */
    private static String extractDigestParam(String header, String name) {
        if (header == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(name + "\\s*=\\s*\"?([^\",]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(header);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String md5(String text) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
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
