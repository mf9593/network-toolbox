package com.networktool.app.cast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * DLNA 投屏控制（AVTransport 服务）。
 *
 * <p>只依赖标准 SOAP 请求，不引入任何第三方库。
 */
public final class DlnaController {

    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";

    public enum MediaType {
        VIDEO, IMAGE
    }

    private DlnaController() {
    }

    /** 投一个媒体地址到设备并开始播放 */
    public static String cast(DlnaDevice device, String url, String title, MediaType type) {
        if (device == null || !device.isReady()) {
            return "未选择可用的投屏设备";
        }
        String metadata = buildMetadata(url, title, type);
        String error = send(device, "SetAVTransportURI",
                "<InstanceID>0</InstanceID>"
                        + "<CurrentURI>" + escape(url) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + escape(metadata) + "</CurrentURIMetaData>");
        if (error != null) return error;
        return send(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    /** 暂停 */
    public static String pause(DlnaDevice device) {
        if (device == null || !device.isReady()) return "未选择投屏设备";
        return send(device, "Pause", "<InstanceID>0</InstanceID>");
    }

    /** 继续播放 */
    public static String resume(DlnaDevice device) {
        if (device == null || !device.isReady()) return "未选择投屏设备";
        return send(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    /** 停止投屏并清空播放地址 */
    public static String stop(DlnaDevice device) {
        if (device == null || !device.isReady()) return "未选择投屏设备";
        send(device, "Stop", "<InstanceID>0</InstanceID>");
        return send(device, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI></CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>");
    }

    /** 返回 null 表示成功，否则是错误描述 */
    private static String send(DlnaDevice device, String action, String argumentsXml) {
        HttpURLConnection connection = null;
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\""
                    + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<s:Body><u:" + action + " xmlns:u=\"" + AVT + "\">"
                    + argumentsXml
                    + "</u:" + action + "></s:Body></s:Envelope>";

            connection = (HttpURLConnection) new URL(device.controlUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPACTION", "\"" + AVT + "#" + action + "\"");
            connection.setRequestProperty("User-Agent", "NetworkToolbox/2.1.0");
            byte[] payload = body.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                drain(connection.getInputStream());
                return null;
            }
            String detail = readAll(connection.getErrorStream());
            String reason = extractUpnpError(detail);
            return "设备拒绝（HTTP " + code + "）" + (reason == null ? "" : "：" + reason);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String extractUpnpError(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            int start = xml.indexOf("<errorDescription>");
            int end = xml.indexOf("</errorDescription>");
            if (start >= 0 && end > start) {
                return xml.substring(start + 18, end).trim();
            }
            int codeStart = xml.indexOf("<errorCode>");
            int codeEnd = xml.indexOf("</errorCode>");
            if (codeStart >= 0 && codeEnd > codeStart) {
                return "错误码 " + xml.substring(codeStart + 11, codeEnd).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void drain(InputStream input) {
        try {
            if (input == null) return;
            byte[] buffer = new byte[2048];
            while (input.read(buffer) > 0) {
                // 丢弃
            }
            input.close();
        } catch (Exception ignored) {
        }
    }

    private static String readAll(InputStream input) {
        if (input == null) return "";
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 组装 DIDL-Lite 元数据（很多电视缺少这个就不识别） */
    private static String buildMetadata(String url, String title, MediaType type) {
        String safeTitle = title == null || title.trim().isEmpty() ? "网络工具箱" : title;
        String upnpClass = type == MediaType.IMAGE
                ? "object.item.imageItem" : "object.item.videoItem";
        String protocol = type == MediaType.IMAGE
                ? "http-get:*:image/jpeg:*" : "http-get:*:video/mp4:*";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escape(safeTitle) + "</dc:title>"
                + "<upnp:class>" + upnpClass + "</upnp:class>"
                + "<res protocolInfo=\"" + protocol + "\">" + escape(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
