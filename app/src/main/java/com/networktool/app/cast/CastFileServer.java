package com.networktool.app.cast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/**
 * 极简本地 HTTP 文件服务：把手机上的图片 / 视频以 URL 形式提供给电视拉流。
 *
 * <p>支持 Range 请求（视频拖动进度）、HEAD 请求，单客户端串行处理足够投屏使用。
 */
public final class CastFileServer {

    private ServerSocket serverSocket;
    private Thread worker;
    private volatile boolean running;
    private volatile File file;
    private volatile String contentType = "application/octet-stream";
    private volatile String fileName = "media";
    private int port;

    /**
     * 启动服务（已在运行则只替换文件）。
     *
     * @return 监听端口
     */
    public synchronized int start(File file, String contentType, String fileName)
            throws IOException {
        this.file = file;
        this.contentType = contentType == null ? "application/octet-stream" : contentType;
        this.fileName = fileName == null || fileName.isEmpty() ? "media" : fileName;
        if (running && serverSocket != null) {
            return port;
        }
        serverSocket = new ServerSocket(0);
        serverSocket.setReuseAddress(true);
        port = serverSocket.getLocalPort();
        running = true;
        worker = new Thread(this::acceptLoop, "cast-http-server");
        worker.setDaemon(true);
        worker.start();
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    /** 生成给电视用的播放地址 */
    public String getUrl(String localIp) {
        String host = localIp == null || localIp.isEmpty() ? "127.0.0.1" : localIp;
        return "http://" + host + ":" + port + "/" + fileName;
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        serverSocket = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                handle(client);
            } catch (Exception e) {
                if (!running) break;
            } finally {
                try {
                    if (client != null) client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        client.setSoTimeout(15000);
        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String requestLine = reader.readLine();
        if (requestLine == null) return;

        String rangeHeader = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("range:")) {
                rangeHeader = line.substring(6).trim();
            }
        }

        OutputStream output = client.getOutputStream();
        boolean headOnly = requestLine.startsWith("HEAD");
        File current = file;
        if (current == null || !current.isFile() || !current.canRead()) {
            writeSimple(output, "HTTP/1.1 404 Not Found", "text/plain", "file not found");
            return;
        }

        long length = current.length();
        long start = 0;
        long end = length - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    if (dash < spec.length() - 1) {
                        end = Long.parseLong(spec.substring(dash + 1).trim());
                    }
                } else if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1).trim());
                    start = Math.max(0, length - suffix);
                }
                end = Math.min(end, length - 1);
                if (start > end || start < 0) {
                    start = 0;
                    end = length - 1;
                } else {
                    partial = true;
                }
            } catch (Exception ignored) {
                start = 0;
                end = length - 1;
                partial = false;
            }
        }

        long sendLength = end - start + 1;
        StringBuilder header = new StringBuilder();
        header.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Accept-Ranges: bytes\r\n");
        header.append("Content-Length: ").append(sendLength).append("\r\n");
        if (partial) {
            header.append("Content-Range: bytes ").append(start).append("-").append(end)
                    .append("/").append(length).append("\r\n");
        }
        header.append("Connection: close\r\n\r\n");
        output.write(header.toString().getBytes("UTF-8"));

        if (!headOnly) {
            try (RandomAccessFile accessFile = new RandomAccessFile(current, "r")) {
                accessFile.seek(start);
                byte[] buffer = new byte[64 * 1024];
                long remaining = sendLength;
                while (remaining > 0) {
                    int read = accessFile.read(buffer, 0,
                            (int) Math.min(buffer.length, remaining));
                    if (read <= 0) break;
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }
        output.flush();
    }

    private void writeSimple(OutputStream output, String status, String type, String body)
            throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        String header = status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes("UTF-8"));
        output.write(payload);
        output.flush();
    }

    /* ==================== 工具方法 ==================== */

    /** 取本机在局域网中的 IPv4 地址（优先 wlan0） */
    public static String getLocalIpAddress() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (!address.isSiteLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip == null) continue;
                    String name = networkInterface.getName();
                    if (name != null && name.startsWith("wlan")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 按扩展名猜 MIME 类型 */
    public static String guessContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    /** 是否是图片类型 */
    public static boolean isImageType(String contentType) {
        return contentType != null && contentType.startsWith("image/");
    }
}
