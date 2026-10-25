package com.networktool.app.cast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 极简 SSDP 设备发现：只找 DLNA 的 MediaRenderer 设备（电视 / 机顶盒）。
 *
 * <p>流程：组播发 M-SEARCH → 收 200 OK 响应 → 拉取设备描述 XML → 解析出
 * AVTransport 的 controlURL（投屏指令发往这里）。
 */
public final class SsdpDiscovery {

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SEARCH_TARGET =
            "urn:schemas-upnp-org:device:MediaRenderer:1";

    public interface Callback {
        /** 发现一个可用设备（工作线程回调，需自行切主线程） */
        void onDeviceFound(DlnaDevice device);

        /** 搜索结束 */
        void onFinished();

        /** 出错（一般不影响已找到的设备） */
        void onError(String message);
    }

    private final Context context;
    private volatile boolean running;
    private Thread worker;
    private DatagramSocket socket;
    private WifiManager.MulticastLock multicastLock;

    public SsdpDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRunning() {
        return running;
    }

    /** 开始搜索（自动结束，约 6 秒） */
    public void start(Callback callback) {
        if (running) return;
        running = true;
        worker = new Thread(() -> {
            acquireMulticastLock();
            try {
                socket = new DatagramSocket();
                socket.setSoTimeout(1200);
                sendSearch();
                Set<String> seen = new HashSet<>();
                long deadline = System.currentTimeMillis() + 6000;
                int searchRound = 1;
                while (running && System.currentTimeMillis() < deadline) {
                    byte[] buffer = new byte[4096];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException timeout) {
                        // 超时后补发一次搜索，再继续等
                        if (searchRound < 3 && running) {
                            sendSearch();
                            searchRound++;
                        }
                        continue;
                    }
                    String response = new String(packet.getData(), 0, packet.getLength(),
                            "UTF-8");
                    String location = headerValue(response, "LOCATION");
                    if (location == null) continue;
                    if (!seen.add(location)) continue;
                    DlnaDevice device = resolveDevice(location);
                    if (device != null && device.isReady() && running) {
                        callback.onDeviceFound(device);
                    }
                }
            } catch (Exception e) {
                if (callback != null) callback.onError(
                        e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                releaseSocket();
                releaseMulticastLock();
                running = false;
                if (callback != null) callback.onFinished();
            }
        }, "dlna-ssdp");
        worker.start();
    }

    /** 停止搜索 */
    public void stop() {
        running = false;
        releaseSocket();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void sendSearch() {
        try {
            String message = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: " + SEARCH_TARGET + "\r\n"
                    + "\r\n";
            byte[] data = message.getBytes("UTF-8");
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    new InetSocketAddress(group, SSDP_PORT));
            if (socket != null && !socket.isClosed()) {
                socket.send(packet);
            }
        } catch (Exception ignored) {
        }
    }

    private String headerValue(String response, String key) {
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** 拉取设备描述并解析出 AVTransport 控制地址 */
    private DlnaDevice resolveDevice(String location) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(location).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent", "NetworkToolbox/2.1.0");
            try (InputStream input = connection.getInputStream()) {
                String xml = readAll(input);
                return parseDescription(xml, location);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private DlnaDevice parseDescription(String xml, String location) {
        DlnaDevice device = new DlnaDevice();
        device.location = location;
        String urlBase = null;
        String serviceType = null;
        String serviceControl = null;
        int serviceDepth = -1;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("friendlyName".equalsIgnoreCase(tag) && device.friendlyName == null) {
                        device.friendlyName = parser.nextText().trim();
                    } else if ("modelName".equalsIgnoreCase(tag) && device.modelName == null) {
                        device.modelName = parser.nextText().trim();
                    } else if ("UDN".equalsIgnoreCase(tag) && device.udn == null) {
                        device.udn = parser.nextText().trim();
                    } else if ("URLBase".equalsIgnoreCase(tag) && urlBase == null) {
                        urlBase = parser.nextText().trim();
                    } else if ("service".equalsIgnoreCase(tag)) {
                        serviceType = null;
                        serviceControl = null;
                        serviceDepth = parser.getDepth();
                    } else if ("serviceType".equalsIgnoreCase(tag) && serviceDepth > 0) {
                        serviceType = parser.nextText().trim();
                    } else if ("controlURL".equalsIgnoreCase(tag) && serviceDepth > 0) {
                        serviceControl = parser.nextText().trim();
                    }
                } else if (event == XmlPullParser.END_TAG
                        && "service".equalsIgnoreCase(parser.getName())) {
                    if (serviceType != null && serviceControl != null
                            && serviceType.contains("AVTransport")
                            && device.controlUrl == null) {
                        device.controlUrl = resolveUrl(
                                urlBase != null ? urlBase : location, serviceControl);
                    }
                    serviceDepth = -1;
                }
                event = parser.next();
            }
        } catch (Exception e) {
            return device.isReady() ? device : null;
        }
        // 记录 IP
        try {
            device.ip = new URL(location).getHost();
        } catch (Exception ignored) {
        }
        return device;
    }

    private String resolveUrl(String base, String relative) {
        try {
            if (relative.startsWith("http://") || relative.startsWith("https://")) {
                return relative;
            }
            URL baseUrl = new URL(base);
            String path = relative.startsWith("/") ? relative : "/" + relative;
            return baseUrl.getProtocol() + "://" + baseUrl.getHost()
                    + (baseUrl.getPort() > 0 ? ":" + baseUrl.getPort() : "") + path;
        } catch (Exception e) {
            return relative;
        }
    }

    private String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), "UTF-8");
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("network-toolbox-dlna");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }

    private void releaseSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
    }

    /** 常用工具：把设备列表转成名称数组 */
    public static String[] names(List<DlnaDevice> devices) {
        List<String> names = new ArrayList<>();
        for (DlnaDevice device : devices) {
            names.add(device.getDisplayName());
        }
        return names.toArray(new String[0]);
    }
}
