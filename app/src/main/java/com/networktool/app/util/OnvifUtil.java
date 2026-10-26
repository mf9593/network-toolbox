package com.networktool.app.util;

import android.content.Context;
import android.util.Log;

import com.networktool.app.model.OnvifDevice;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ONVIF协议工具类 - 标准ONVIF协议实现
 */
public class OnvifUtil {
    private static final String TAG = "OnvifUtil";

    // ONVIF命名空间常量
    private static final String NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    private static final String NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    private static final String NS_SCHEMA = "http://www.onvif.org/ver10/schema";

    // HTTP客户端
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .sslSocketFactory(createNaiveSSLSocketFactory(), new NaiveTrustManager())
            .hostnameVerifier((hostname, session) -> true)
            .build();

    private static final MediaType SOAP_MEDIA_TYPE = MediaType.parse("application/soap+xml; charset=utf-8");

    // 设备服务地址 -> 媒体服务地址缓存
    private static final ConcurrentHashMap<String, String> MEDIA_SERVICE_URLS = new ConcurrentHashMap<>();

    // ========== 设备发现 ==========
    public static List<OnvifDevice> discoverDevices(Context context) {
        List<OnvifDevice> devices = new ArrayList<>();

        try {
            // 生成标准ONVIF Probe消息
            String probeMessage = createStandardProbeMessage();

            // 发送Probe到组播地址239.255.255.250:3702
            sendProbeAndReceiveResponses(probeMessage, devices);

            Log.d(TAG, "WS-Discovery发现完成，找到 " + devices.size() + " 个设备");
        } catch (Exception e) {
            Log.e(TAG, "设备发现失败: " + e.getMessage(), e);
        }

        return devices;
    }

    private static String createStandardProbeMessage() {
        String messageId = "uuid:" + UUID.randomUUID().toString();

        StringBuilder probe = new StringBuilder();
        probe.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        probe.append("<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"\n");
        probe.append("    xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"\n");
        probe.append("    xmlns:wsd=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\n");
        probe.append("  <soap:Header>\n");
        probe.append("    <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>\n");
        probe.append("    <wsa:MessageID>").append(messageId).append("</wsa:MessageID>\n");
        probe.append("    <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>\n");
        probe.append("  </soap:Header>\n");
        probe.append("  <soap:Body>\n");
        probe.append("    <wsd:Probe>\n");
        probe.append("      <wsd:Types>tds:Device</wsd:Types>\n");
        probe.append("    </wsd:Probe>\n");
        probe.append("  </soap:Body>\n");
        probe.append("</soap:Envelope>");

        return probe.toString();
    }

    private static void sendProbeAndReceiveResponses(String probeMessage, List<OnvifDevice> devices) {
        java.net.MulticastSocket socket = null;
        try {
            // 创建组播socket，绑定到3702端口
            socket = new java.net.MulticastSocket(3702);
            socket.setTimeToLive(10);
            socket.setSoTimeout(3000);

            // 加入组播组
            java.net.InetAddress group = java.net.InetAddress.getByName("239.255.255.250");
            socket.joinGroup(group);

            // 发送Probe消息
            byte[] probeData = probeMessage.getBytes(StandardCharsets.UTF_8);
            java.net.DatagramPacket probePacket = new java.net.DatagramPacket(
                    probeData, probeData.length, group, 3702);
            socket.send(probePacket);
            Log.d(TAG, "已发送WS-Discovery Probe消息");

            // 接收响应
            byte[] buffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 3000) {
                java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    OnvifDevice device = parseDiscoveryResponse(response);
                    if (device != null) {
                        // 检查是否已存在
                        boolean exists = false;
                        for (OnvifDevice d : devices) {
                            if (d.getIpAddress().equals(device.getIpAddress())) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            devices.add(device);
                            Log.d(TAG, "发现设备: " + device.getIpAddress());
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 超时，继续循环
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WS-Discovery失败: " + e.getMessage(), e);
        } finally {
            if (socket != null) {
                try {
                    socket.leaveGroup(java.net.InetAddress.getByName("239.255.255.250"));
                } catch (Exception ignored) {}
                socket.close();
            }
        }
    }

    private static OnvifDevice parseDiscoveryResponse(String response) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            // 检查是否是ProbeMatch响应
            NodeList probeMatchList = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/ws/2005/04/discovery", "ProbeMatch");
            if (probeMatchList.getLength() == 0) {
                return null;
            }

            OnvifDevice device = new OnvifDevice();

            // 解析XAddrs（服务地址）
            NodeList xAddrsList = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/ws/2005/04/discovery", "XAddrs");
            if (xAddrsList.getLength() > 0) {
                String xAddrs = xAddrsList.item(0).getTextContent();
                // 提取IP地址和端口
                java.net.URI uri = new java.net.URI(xAddrs.split("\\s+")[0]);
                device.setIpAddress(uri.getHost());
                device.setPort(uri.getPort() > 0 ? uri.getPort() : 80);
            }

            // 解析UUID
            NodeList addressList = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/ws/2004/08/addressing", "Address");
            if (addressList.getLength() > 0) {
                device.setUuid(addressList.item(0).getTextContent());
            }

            // 解析Scopes（提取name/hardware/location）
            NodeList scopesList = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/ws/2005/04/discovery", "Scopes");
            if (scopesList.getLength() > 0) {
                String scopes = scopesList.item(0).getTextContent();
                device.setScopes(scopes);
                for (String scope : scopes.trim().split("\\s+")) {
                    if (scope.contains("/name/")) {
                        device.setName(decodeScope(scope.substring(scope.indexOf("/name/") + 6)));
                    } else if (scope.contains("/Hardware/")) {
                        device.setHardware(decodeScope(scope.substring(scope.indexOf("/Hardware/") + 10)));
                    } else if (scope.contains("/location/")) {
                        String loc = decodeScope(scope.substring(scope.indexOf("/location/") + 10));
                        if (device.getLocation() != null) {
                            device.setLocation(device.getLocation() + " " + loc);
                        } else {
                            device.setLocation(loc);
                        }
                    }
                }
            }

            return device;
        } catch (Exception e) {
            Log.w(TAG, "解析Discovery响应失败: " + e.getMessage());
            return null;
        }
    }

    // ========== 设备信息 ==========
    public static String getDeviceInformation(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:GetDeviceInformation/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取设备信息失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static void parseDeviceInformation(String response, OnvifDevice device) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            NodeList manufacturerList = doc.getElementsByTagNameNS(NS_DEVICE, "Manufacturer");
            if (manufacturerList.getLength() > 0) {
                device.setManufacturer(manufacturerList.item(0).getTextContent());
            }

            NodeList modelList = doc.getElementsByTagNameNS(NS_DEVICE, "Model");
            if (modelList.getLength() > 0) {
                device.setModel(modelList.item(0).getTextContent());
            }

            NodeList firmwareList = doc.getElementsByTagNameNS(NS_DEVICE, "FirmwareVersion");
            if (firmwareList.getLength() > 0) {
                device.setFirmwareVersion(firmwareList.item(0).getTextContent());
            }

            NodeList serialList = doc.getElementsByTagNameNS(NS_DEVICE, "SerialNumber");
            if (serialList.getLength() > 0) {
                device.setSerialNumber(serialList.item(0).getTextContent());
            }

            NodeList hardwareList = doc.getElementsByTagNameNS(NS_DEVICE, "HardwareId");
            if (hardwareList.getLength() > 0) {
                device.setHardware(hardwareList.item(0).getTextContent());
            }
        } catch (Exception e) {
            Log.e(TAG, "解析设备信息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析设备信息响应并返回字符串（兼容旧代码）
     *
     * @param response SOAP响应字符串
     * @return 格式化的设备信息字符串
     */
    public static String parseDeviceInformation(String response) {
        if (response == null) return "获取失败";
        try {
            OnvifDevice device = new OnvifDevice();
            parseDeviceInformation(response, device);
            StringBuilder sb = new StringBuilder();
            if (device.getManufacturer() != null) sb.append("制造商: ").append(device.getManufacturer()).append("\n");
            if (device.getModel() != null) sb.append("型号: ").append(device.getModel()).append("\n");
            if (device.getFirmwareVersion() != null) sb.append("固件版本: ").append(device.getFirmwareVersion()).append("\n");
            if (device.getSerialNumber() != null) sb.append("序列号: ").append(device.getSerialNumber()).append("\n");
            if (device.getHardware() != null) sb.append("硬件ID: ").append(device.getHardware()).append("\n");
            return sb.length() > 0 ? sb.toString() : "解析失败";
        } catch (Exception e) {
            return "解析失败: " + e.getMessage();
        }
    }

    // ========== Profiles和流 ==========
    public static String getProfiles(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><trt:GetProfiles/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取Profiles失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static List<String> parseProfileTokens(String response) {
        List<String> tokens = new ArrayList<>();
        if (response == null) return tokens;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            // ONVIF标准: token是<trt:Profiles>元素的属性
            // 例如: <trt:Profiles token="Profile_1">
            NodeList profileList = doc.getElementsByTagNameNS(NS_MEDIA, "Profiles");
            if (profileList.getLength() == 0) {
                profileList = doc.getElementsByTagName("trt:Profiles");
            }
            if (profileList.getLength() == 0) {
                profileList = doc.getElementsByTagName("Profiles");
            }

            for (int i = 0; i < profileList.getLength(); i++) {
                Element profile = (Element) profileList.item(i);
                String token = profile.getAttribute("token");
                if (token != null && !token.isEmpty()) {
                    tokens.add(token);
                }
            }

            // 回退：如果上面没找到，尝试查找<tt:token>子元素（部分品牌兼容）
            if (tokens.isEmpty()) {
                NodeList tokenList = doc.getElementsByTagNameNS(NS_SCHEMA, "token");
                for (int i = 0; i < tokenList.getLength(); i++) {
                    String t = tokenList.item(i).getTextContent();
                    if (t != null && !t.isEmpty()) tokens.add(t);
                }
            }

            Log.d(TAG, "解析到 " + tokens.size() + " 个Profile Token");
        } catch (Exception e) {
            Log.e(TAG, "解析Profile Tokens失败: " + e.getMessage(), e);
        }
        return tokens;
    }

    public static String getStreamUri(String serviceUrl, String profileToken, String username, String password) {
        try {
            String soapBody = "<soap:Body>" +
                "<trt:GetStreamUri>" +
                "<trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                "<trt:ProfileToken>" + escapeXml(profileToken) + "</trt:ProfileToken>" +
                "</trt:GetStreamUri>" +
                "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取流URI失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static String parseStreamUri(String response) {
        if (response == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            // ONVIF标准响应: <trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://...</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>
            // Uri元素命名空间是 http://www.onvif.org/ver10/schema (NS_SCHEMA)
            NodeList uriList = doc.getElementsByTagNameNS(NS_SCHEMA, "Uri");
            if (uriList.getLength() == 0) {
                uriList = doc.getElementsByTagName("tt:Uri");
            }
            if (uriList.getLength() == 0) {
                uriList = doc.getElementsByTagName("Uri");
            }
            if (uriList.getLength() > 0) {
                return uriList.item(0).getTextContent().trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "解析流URI失败: " + e.getMessage(), e);
        }
        return null;
    }

    // ========== 时间同步 ==========
    public static String getSystemDateAndTime(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:GetSystemDateAndTime/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取系统日期时间失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static String setSystemDateAndTime(String serviceUrl, String username, String password, String utcDateTime) {
        try {
            // 解析UTC时间字符串 (格式: 2024-01-15T10:30:00Z)
            String[] parts = utcDateTime.replace("Z", "").split("T");
            String[] dateParts = parts[0].split("-");
            String[] timeParts = parts[1].split(":");

            String soapBody = "<soap:Body>" +
                "<tds:SetSystemDateAndTime>" +
                "<tds:DateTimeType>Manual</tds:DateTimeType>" +
                "<tds:DaylightSavings>false</tds:DaylightSavings>" +
                "<tds:TimeZone><tt:TZ>CST-8</tt:TZ></tds:TimeZone>" +
                "<tds:UTCDateTime>" +
                "<tt:Time><tt:Hour>" + timeParts[0] + "</tt:Hour><tt:Minute>" + timeParts[1] + "</tt:Minute><tt:Second>" + timeParts[2] + "</tt:Second></tt:Time>" +
                "<tt:Date><tt:Year>" + dateParts[0] + "</tt:Year><tt:Month>" + dateParts[1] + "</tt:Month><tt:Day>" + dateParts[2] + "</tt:Day></tt:Date>" +
                "</tds:UTCDateTime>" +
                "</tds:SetSystemDateAndTime>" +
                "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "设置系统日期时间失败: " + e.getMessage(), e);
            return null;
        }
    }

    // ========== 设备友好名称（通道名） ==========

    /**
     * 获取设备友好名称（GetUserFriendlyName）
     *
     * 获取ONVIF设备的友好名称（设备名），许多NVR将此名称显示为通道名称。
     * 这是设备服务操作，发送到 device_service 地址。
     *
     * @param serviceUrl 设备服务地址（如 http://ip:port/onvif/device_service）
     * @param username 用户名
     * @param password 密码
     * @return SOAP响应XML，失败返回null
     */
    public static String getUserFriendlyName(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:GetUserFriendlyName/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取设备友好名称失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析GetUserFriendlyName响应，提取友好名称
     *
     * @param response GetUserFriendlyName的SOAP响应
     * @return 友好名称，解析失败返回null
     */
    public static String parseUserFriendlyName(String response) {
        if (response == null || response.isEmpty()) return null;
        try {
            // 匹配 <tds:FriendlyName>xxx</tds:FriendlyName> 或 < FriendlyName>xxx</FriendlyName>
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<[^:]*:?FriendlyName[^>]*>([^<]*)</[^:]*:?FriendlyName[^>]*>",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                return name.isEmpty() ? null : name;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析设备友好名称失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 设置设备友好名称（SetUserFriendlyName）
     *
     * 修改ONVIF设备的友好名称（设备名），许多NVR将此名称显示为通道名称。
     * 这是设备服务操作，发送到 device_service 地址。
     * 部分设备不支持此操作，会返回SOAP Fault。
     *
     * @param serviceUrl 设备服务地址（如 http://ip:port/onvif/device_service）
     * @param username 用户名
     * @param password 密码
     * @param friendlyName 新的友好名称
     * @return SOAP响应XML，失败返回null
     */
    public static String setUserFriendlyName(String serviceUrl, String username, String password,
                                              String friendlyName) {
        try {
            String soapBody = "<soap:Body>" +
                "<tds:SetUserFriendlyName>" +
                "<tds:FriendlyName>" + escapeXml(friendlyName) + "</tds:FriendlyName>" +
                "</tds:SetUserFriendlyName>" +
                "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "设置设备友好名称失败: " + e.getMessage(), e);
            return null;
        }
    }

    // ========== 网络配置 ==========
    public static String getNetworkInterfaces(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:GetNetworkInterfaces/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取网络接口失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析网络接口响应，提取接口Token列表
     *
     * @param response GetNetworkInterfaces的SOAP响应
     * @return 接口Token列表，失败返回空列表
     */
    public static List<String> parseNetworkInterfaceTokens(String response) {
        List<String> tokens = new ArrayList<>();
        if (response == null) return tokens;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            // 查找NetworkInterfaces节点
            NodeList ifaceList = doc.getElementsByTagNameNS(NS_DEVICE, "NetworkInterfaces");
            if (ifaceList.getLength() == 0) {
                ifaceList = doc.getElementsByTagName("tds:NetworkInterfaces");
            }
            if (ifaceList.getLength() == 0) {
                ifaceList = doc.getElementsByTagName("NetworkInterfaces");
            }

            for (int i = 0; i < ifaceList.getLength(); i++) {
                Element iface = (Element) ifaceList.item(i);
                String token = iface.getAttribute("token");
                if (token != null && !token.isEmpty()) {
                    tokens.add(token);
                }
            }

            Log.d(TAG, "解析到 " + tokens.size() + " 个网络接口Token");
        } catch (Exception e) {
            Log.e(TAG, "解析网络接口Token失败: " + e.getMessage(), e);
        }
        return tokens;
    }

    /**
     * 将子网掩码转换为前缀长度
     * 例如 255.255.255.0 -> 24
     */
    private static int subnetMaskToPrefixLength(String subnetMask) {
        if (subnetMask == null || subnetMask.isEmpty()) return 24;
        try {
            String[] parts = subnetMask.split("\\.");
            if (parts.length != 4) return 24;
            int bits = 0;
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                // 计算每个字节中1的位数
                bits += Integer.bitCount(octet);
            }
            return bits;
        } catch (NumberFormatException e) {
            Log.w(TAG, "子网掩码格式错误: " + subnetMask + "，使用默认值24");
            return 24;
        }
    }

    public static String setNetworkInterfaces(String serviceUrl, String username, String password,
                                              String interfaceToken, String ipAddress,
                                              String subnetMask, String gateway) {
        try {
            int prefixLength = subnetMaskToPrefixLength(subnetMask);
            String soapBody = "<soap:Body>" +
                "<tds:SetNetworkInterfaces>" +
                "<tds:InterfaceToken>" + interfaceToken + "</tds:InterfaceToken>" +
                "<tds:NetworkInterface>" +
                "<tt:Enabled>true</tt:Enabled>" +
                "<tt:IPv4>" +
                "<tt:Enabled>true</tt:Enabled>" +
                "<tt:Manual>" +
                "<tt:Address>" + ipAddress + "</tt:Address>" +
                "<tt:PrefixLength>" + prefixLength + "</tt:PrefixLength>" +
                "</tt:Manual>" +
                "<tt:DHCP>false</tt:DHCP>" +
                "</tt:IPv4>" +
                "</tds:NetworkInterface>" +
                "</tds:SetNetworkInterfaces>" +
                "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "设置网络接口失败: " + e.getMessage(), e);
            return null;
        }
    }

    // ========== 视频配置 ==========
    public static String getVideoEncoderConfiguration(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><trt:GetVideoEncoderConfigurations/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取视频编码配置失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取视频编码配置（兼容旧代码）
     */
    public static String getVideoEncoderConfiguration(String serviceUrl, String profileToken, String username, String password) {
        // 忽略profileToken，获取所有配置
        return getVideoEncoderConfiguration(serviceUrl, username, password);
    }

    public static String setVideoEncoderConfiguration(String serviceUrl, String username, String password,
                                                      String configToken, int width, int height,
                                                      int bitrate, int fps, String encoding) {
        try {
            String encodingStr = (encoding != null && !encoding.isEmpty()) ? encoding : "H264";
            String soapBody = "<soap:Body>" +
                "<trt:SetVideoEncoderConfiguration>" +
                "<trt:Configuration token=\"" + escapeXml(configToken) + "\">" +
                "<tt:Name>VideoEncoder</tt:Name>" +
                "<tt:Encoding>" + encodingStr + "</tt:Encoding>" +
                "<tt:Resolution><tt:Width>" + width + "</tt:Width><tt:Height>" + height + "</tt:Height></tt:Resolution>" +
                "<tt:Quality>5</tt:Quality>" +
                "<tt:RateControl><tt:FrameRateLimit>" + fps + "</tt:FrameRateLimit><tt:BitrateLimit>" + bitrate + "</tt:BitrateLimit></tt:RateControl>" +
                "</trt:Configuration>" +
                "<trt:ForcePersistence>true</trt:ForcePersistence>" +
                "</trt:SetVideoEncoderConfiguration>" +
                "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "设置视频编码配置失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 设置视频编码配置（兼容旧代码 - OnvifActivity版本，6参数）
     */
    public static String setVideoEncoderConfiguration(String serviceUrl, String username, String password,
                                                      String encoding, String resolution, String profileToken) {
        // 解析分辨率
        int width = 1920, height = 1080;
        if (resolution != null && resolution.contains("x")) {
            String[] parts = resolution.split("x");
            try {
                width = Integer.parseInt(parts[0].trim());
                height = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {}
        }
        return setVideoEncoderConfiguration(serviceUrl, username, password, profileToken, width, height, 4096, 25, encoding);
    }

    /**
     * 设置视频编码配置（兼容旧代码 - OnvifVideoConfigActivity版本，7参数）
     */
    public static String setVideoEncoderConfiguration(String serviceUrl, String username, String password,
                                                      String encoding, String widthStr, String heightStr, String profileToken) {
        // 解析分辨率
        int width = 1920, height = 1080;
        try {
            width = Integer.parseInt(widthStr);
            height = Integer.parseInt(heightStr);
        } catch (Exception ignored) {}
        return setVideoEncoderConfiguration(serviceUrl, username, password, profileToken, width, height, 4096, 25, encoding);
    }

    // ========== 系统重启 ==========
    public static String systemReboot(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:SystemReboot/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "系统重启失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 尝试匿名发送 SystemReboot。
     * ONVIF 标准要求该操作具备管理员权限；仅当摄像头主动关闭了 ONVIF 认证时才会成功。
     */
    public static String systemRebootAnonymous(String serviceUrl) {
        try {
            String soapBody = "<soap:Body><tds:SystemReboot/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, null, null);
            return sendSoapRequest(serviceUrl, envelope, null, null);
        } catch (Exception e) {
            Log.e(TAG, "匿名重启失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 判断重启响应是否表示请求已被设备接受。
     * 部分设备在重启前只返回空响应，因此空字符串也视为已发送。
     */
    public static boolean isSystemRebootSuccess(String response) {
        if (response == null) return false;
        String normalized = response.trim();
        if (normalized.isEmpty()) return true;
        return normalized.toLowerCase(java.util.Locale.US)
                .contains("systemrebootresponse");
    }

    // ========== 系统重启（兼容旧代码） ==========
    public static String setRestart(String serviceUrl, String username, String password) {
        return systemReboot(serviceUrl, username, password);
    }

    // ========== 工厂重置（兼容旧代码） ==========
    public static String setFactoryDefault(String serviceUrl, String username, String password) {
        // 标准ONVIF没有工厂重置，返回null
        return null;
    }

    // ========== 品牌检测（兼容旧代码） ==========
    public static String detectBrand(String manufacturer, String model) {
        if (manufacturer == null) return "未知品牌";
        String lower = manufacturer.toLowerCase();
        if (lower.contains("hikvision") || lower.contains("海康")) return "海康威视";
        if (lower.contains("dahua") || lower.contains("大华")) return "大华";
        if (lower.contains("tp-link")) return "TP-Link";
        if (lower.contains("uniview") || lower.contains("宇视")) return "宇视";
        return manufacturer;
    }

    // ========== RTSP URL处理（兼容旧代码） ==========
    public static String processRtspUrl(String rtspUrl, String password) {
        return processRtspUrl(rtspUrl, "admin", password);
    }

    /**
     * 在 RTSP URL 缺少认证信息时，使用当前 ONVIF 用户名和密码补齐。
     * 不覆盖设备已经返回的认证信息。
     */
    public static String processRtspUrl(String rtspUrl, String username, String password) {
        if (rtspUrl == null || password == null || password.isEmpty()) {
            return rtspUrl;
        }
        try {
            java.net.URI uri = java.net.URI.create(rtspUrl);
            if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
                return rtspUrl;
            }
        } catch (Exception ignored) {
            // URI 解析失败时继续使用字符串替换
        }

        String user = (username == null || username.isEmpty()) ? "admin" : username;
        String encodedUser = encodeUserInfo(user);
        String encodedPassword = encodeUserInfo(password);
        int schemeEnd = rtspUrl.indexOf("://");
        if (schemeEnd < 0) {
            return rtspUrl;
        }
        return rtspUrl.substring(0, schemeEnd + 3)
                + encodedUser + ":" + encodedPassword + "@"
                + rtspUrl.substring(schemeEnd + 3);
    }

    private static String encodeUserInfo(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    // ========== 解析方法 ==========

    /**
     * 解析 GetSystemDateAndTime 响应，提取设备时间信息
     *
     * @param response SOAP响应XML
     * @return 格式化的时间字符串，包含UTC时间和本地时间
     */
    public static String parseSystemDateAndTime(String response) {
        if (response == null) return "获取失败";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            StringBuilder sb = new StringBuilder();

            // 解析时间类型
            String dateTimeType = getXmlNodeText(doc, "DateTimeType");
            if (dateTimeType != null && !dateTimeType.isEmpty()) {
                sb.append("时间类型: ").append(dateTimeType).append("\n");
            }

            // 解析夏令时
            String daylightSavings = getXmlNodeText(doc, "DaylightSavings");
            if (daylightSavings != null && !daylightSavings.isEmpty()) {
                sb.append("夏令时: ").append("true".equalsIgnoreCase(daylightSavings) ? "开启" : "关闭").append("\n");
            }

            // 解析时区
            String tz = getXmlNodeText(doc, "TZ");
            if (tz != null && !tz.isEmpty()) {
                sb.append("时区: ").append(tz).append("\n");
            }

            // 解析UTC时间
            String utcTime = extractDateTime(doc, "UTCDateTime");
            if (utcTime != null) {
                sb.append("UTC时间: ").append(utcTime).append("\n");
            }

            // 解析本地时间
            String localTime = extractDateTime(doc, "LocalDateTime");
            if (localTime != null) {
                sb.append("本地时间: ").append(localTime).append("\n");
            }

            return sb.length() > 0 ? sb.toString() : "解析失败：未找到时间信息";
        } catch (Exception e) {
            Log.e(TAG, "解析系统时间失败: " + e.getMessage(), e);
            return "解析失败: " + e.getMessage();
        }
    }

    /**
     * 从XML文档中提取日期时间
     */
    private static String extractDateTime(Document doc, String parentTag) {
        try {
            NodeList parentList = doc.getElementsByTagNameNS(NS_SCHEMA, parentTag);
            if (parentList.getLength() == 0) {
                parentList = doc.getElementsByTagName("tt:" + parentTag);
            }
            if (parentList.getLength() == 0) {
                parentList = doc.getElementsByTagName(parentTag);
            }
            if (parentList.getLength() == 0) return null;

            Element parent = (Element) parentList.item(0);

            String year = getChildText(parent, "Year");
            String month = getChildText(parent, "Month");
            String day = getChildText(parent, "Day");
            String hour = getChildText(parent, "Hour");
            String minute = getChildText(parent, "Minute");
            String second = getChildText(parent, "Second");

            if (year != null && month != null && day != null) {
                String dateStr = year + "-" + padZero(month) + "-" + padZero(day);
                if (hour != null && minute != null) {
                    dateStr += " " + padZero(hour) + ":" + padZero(minute);
                    if (second != null) {
                        dateStr += ":" + padZero(second);
                    }
                }
                return dateStr;
            }
        } catch (Exception e) {
            Log.w(TAG, "提取" + parentTag + "失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取子元素的文本内容
     */
    private static String getChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagNameNS(NS_SCHEMA, tagName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName("tt:" + tagName);
        }
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(tagName);
        }
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    /**
     * 从Document中获取指定标签的文本内容
     */
    private static String getXmlNodeText(Document doc, String tagName) {
        NodeList list = doc.getElementsByTagNameNS(NS_SCHEMA, tagName);
        if (list.getLength() == 0) {
            list = doc.getElementsByTagName("tt:" + tagName);
        }
        if (list.getLength() == 0) {
            list = doc.getElementsByTagName(tagName);
        }
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    private static String padZero(String value) {
        if (value == null) return "00";
        try {
            int num = Integer.parseInt(value);
            return String.format("%02d", num);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * 解析SOAP Fault响应，提取错误信息
     *
     * @param response SOAP响应XML
     * @return 错误描述字符串，无错误返回null
     */
    public static String parseSoapFault(String response) {
        if (response == null) return "未知错误";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))));

            // 检查是否存在实际的Fault元素节点
            NodeList faultNodes = doc.getElementsByTagNameNS("*", "Fault");
            if (faultNodes.getLength() == 0) {
                // 没有实际Fault元素，说明是正常响应
                return null;
            }

            // 尝试多种方式获取Fault信息
            String faultString = getFaultText(doc, "faultstring");
            String faultCode = getFaultText(doc, "faultcode");
            String detail = getFaultText(doc, "detail");

            StringBuilder sb = new StringBuilder();
            if (faultCode != null) {
                sb.append("错误代码: ").append(faultCode);
            }
            if (faultString != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("错误信息: ").append(faultString);
            }
            if (detail != null && !detail.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("详细信息: ").append(detail);
            }

            if (sb.length() > 0) {
                // 常见错误映射
                String result = sb.toString();
                if (result.contains("NotAuthorized") || result.contains("not authorized")) {
                    return "认证失败：用户名或密码错误";
                }
                if (result.contains("Sender")) {
                    return "请求格式错误：" + result;
                }
                return result;
            }

            // 关键词回退
            if (response.contains("NotAuthorized")) return "认证失败";
            if (response.contains("Sender")) return "请求错误";
            return "操作失败";
        } catch (Exception e) {
            Log.w(TAG, "解析SOAP Fault失败: " + e.getMessage());
            // 关键词回退
            if (response.contains("NotAuthorized")) return "认证失败";
            if (response.contains("Sender")) return "请求错误";
            return "操作失败";
        }
    }

    /**
     * 从SOAP Fault中提取指定标签的文本
     */
    private static String getFaultText(Document doc, String tagName) {
        // 尝试带命名空间
        NodeList list = doc.getElementsByTagNameNS("*", tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        // 尝试不带命名空间
        list = doc.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    public static String parseProfilesToString(String response) {
        List<String> tokens = parseProfileTokens(response);
        if (tokens.isEmpty()) return "未找到Profiles";
        StringBuilder sb = new StringBuilder("Profiles:\n");
        for (String token : tokens) {
            sb.append("  - ").append(token).append("\n");
        }
        return sb.toString();
    }

    // ========== 设备能力与服务 ==========
    public static String getCapabilities(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:GetCapabilities>" +
                    "<tds:Category>All</tds:Category>" +
                    "</tds:GetCapabilities></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取设备能力失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static String getServices(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><tds:GetServices>" +
                    "<tds:IncludeCapability>false</tds:IncludeCapability>" +
                    "</tds:GetServices></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(serviceUrl, envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取设备服务失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static String getImagingSettings(String serviceUrl, String token, String username, String password) {
        return null;
    }

    public static String parseImagingSettings(String response) {
        return "暂不支持";
    }

    public static String getVideoSources(String serviceUrl, String username, String password) {
        return getVideoSourceConfigurations(serviceUrl, username, password);
    }

    /**
     * 获取视频源配置（GetVideoSourceConfigurations）
     *
     * 返回所有VideoSourceConfiguration，包含通道名称、SourceToken、Bounds等信息。
     */
    public static String getVideoSourceConfigurations(String serviceUrl, String username, String password) {
        try {
            String soapBody = "<soap:Body><trt:GetVideoSourceConfigurations/></soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取视频源配置失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 设置视频源配置（SetVideoSourceConfigurations）
     *
     * 修改摄像头的通道名称等视频源配置。
     *
     * @param serviceUrl 设备服务地址
     * @param username 用户名
     * @param password 密码
     * @param configToken VideoSourceConfiguration的token
     * @param name 新的通道名称
     * @param sourceToken VideoSource的token
     * @param bounds Bounds字符串（如 "0,0,1920,1080"），为null时使用默认值
     */
    public static String setVideoSourceConfigurations(String serviceUrl, String username, String password,
                                                       String configToken, String name,
                                                       String sourceToken, String bounds) {
        try {
            // 解析Bounds
            int x = 0, y = 0, width = 1920, height = 1080;
            if (bounds != null && !bounds.isEmpty()) {
                String[] parts = bounds.split(",");
                try {
                    if (parts.length >= 4) {
                        x = Integer.parseInt(parts[0].trim());
                        y = Integer.parseInt(parts[1].trim());
                        width = Integer.parseInt(parts[2].trim());
                        height = Integer.parseInt(parts[3].trim());
                    }
                } catch (Exception ignored) {}
            }

            String soapBody = "<soap:Body>" +
                "<trt:SetVideoSourceConfigurations>" +
                "<trt:Configurations>" +
                "<trt:VideoSourceConfiguration token=\"" + escapeXml(configToken) + "\">" +
                "<tt:Name>" + name + "</tt:Name>" +
                "<tt:SourceToken>" + sourceToken + "</tt:SourceToken>" +
                "<tt:Bounds x=\"" + x + "\" y=\"" + y + "\" width=\"" + width + "\" height=\"" + height + "\"/>" +
                "</trt:VideoSourceConfiguration>" +
                "</trt:Configurations>" +
                "<trt:ForcePersistence>true</trt:ForcePersistence>" +
                "</trt:SetVideoSourceConfigurations>" +
                "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "设置视频源配置失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析VideoSourceConfiguration列表，提取每个配置的token、Name、SourceToken、Bounds
     *
     * @return List<String[]> 每个元素为 [token, name, sourceToken, bounds]
     */
    public static List<String[]> parseVideoSourceConfigurations(String response) {
        List<String[]> configs = new ArrayList<>();
        if (response == null || response.isEmpty()) return configs;

        try {
            // 匹配每个VideoSourceConfiguration元素
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<trt:VideoSourceConfiguration[^>]*token=\"([^\"]+)\"[^>]*>(.*?)</trt:VideoSourceConfiguration>",
                java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = pattern.matcher(response);

            while (matcher.find()) {
                String token = matcher.group(1);
                String content = matcher.group(2);

                // 提取Name
                String name = "";
                java.util.regex.Matcher nameMatcher = java.util.regex.Pattern.compile(
                    "<tt:Name>([^<]*)</tt:Name>").matcher(content);
                if (nameMatcher.find()) {
                    name = nameMatcher.group(1);
                }

                // 提取SourceToken
                String sourceToken = "";
                java.util.regex.Matcher sourceMatcher = java.util.regex.Pattern.compile(
                    "<tt:SourceToken>([^<]*)</tt:SourceToken>").matcher(content);
                if (sourceMatcher.find()) {
                    sourceToken = sourceMatcher.group(1);
                }

                // 提取Bounds
                String bounds = "";
                java.util.regex.Matcher boundsMatcher = java.util.regex.Pattern.compile(
                    "<tt:Bounds[^>]*x=\"([^\"]+)\"[^>]*y=\"([^\"]+)\"[^>]*width=\"([^\"]+)\"[^>]*height=\"([^\"]+)\"").matcher(content);
                if (boundsMatcher.find()) {
                    bounds = boundsMatcher.group(1) + "," + boundsMatcher.group(2) + ","
                           + boundsMatcher.group(3) + "," + boundsMatcher.group(4);
                }

                configs.add(new String[]{token, name, sourceToken, bounds});
            }
        } catch (Exception e) {
            Log.e(TAG, "解析VideoSourceConfiguration失败: " + e.getMessage(), e);
        }
        return configs;
    }

    // ==================== OSD 操作 ====================

    /**
     * 获取设备OSD配置列表
     */
    public static String getOSDs(String serviceUrl, String username, String password) {
        try {
            String mediaUrl = resolveMediaServiceUrl(serviceUrl, username, password);
            String soapBody = "<GetOSDs xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>";
            String soapEnvelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(mediaUrl, soapEnvelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取OSD配置失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 设置OSD配置（文字内容和位置）
     *
     * @param serviceUrl 设备服务地址
     * @param username 用户名
     * @param password 密码
     * @param osdToken OSD配置token
     * @param videoSourceToken 视频源token
     * @param text OSD文字内容
     * @param positionX 位置X坐标（-1.0到1.0，-1为最左，1为最右）
     * @param positionY 位置Y坐标（-1.0到1.0，-1为最下，1为最上）
     * @param fontSize 字体大小（可选，传null使用默认）
     * @param fontColor 字体颜色（可选，传null使用默认，格式如"white"或"#FFFFFF"）
     */
    public static String setOSD(String serviceUrl, String username, String password,
                                 String osdToken, String videoSourceToken,
                                 String text, float positionX, float positionY,
                                 String fontSize, String fontColor) {
        try {
            String mediaUrl = resolveMediaServiceUrl(serviceUrl, username, password);

            StringBuilder osdXml = new StringBuilder();
            osdXml.append("<SetOSD xmlns=\"http://www.onvif.org/ver10/media/wsdl\">");
            osdXml.append("<OSD token=\"").append(escapeXml(osdToken)).append("\">");
            osdXml.append("<VideoSourceConfigurationToken>").append(escapeXml(videoSourceToken)).append("</VideoSourceConfigurationToken>");
            osdXml.append("<Type>Text</Type>");
            osdXml.append("<Position>");
            osdXml.append("<Type>Custom</Type>");
            osdXml.append("<Pos x=\"").append(positionX).append("\" y=\"").append(positionY).append("\"/>");
            osdXml.append("</Position>");
            osdXml.append("<TextString>");
            osdXml.append("<Type>Plain</Type>");
            osdXml.append("<PlainText>").append(escapeXml(text)).append("</PlainText>");
            osdXml.append("</TextString>");
            if (fontSize != null && !fontSize.isEmpty()) {
                osdXml.append("<TextString>");
                osdXml.append("<FontSize>").append(fontSize).append("</FontSize>");
                osdXml.append("</TextString>");
            }
            if (fontColor != null && !fontColor.isEmpty()) {
                osdXml.append("<TextColor Color=\"").append(escapeXml(fontColor)).append("\"/>");
            }
            osdXml.append("</OSD>");
            osdXml.append("</SetOSD>");

            String soapEnvelope = buildSoapEnvelope(osdXml.toString(), username, password);
            return sendSoapRequest(mediaUrl, soapEnvelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "设置OSD失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 创建OSD配置
     */
    public static String createOSD(String serviceUrl, String username, String password,
                                    String videoSourceToken, String text,
                                    float positionX, float positionY) {
        try {
            String mediaUrl = resolveMediaServiceUrl(serviceUrl, username, password);

            StringBuilder osdXml = new StringBuilder();
            osdXml.append("<CreateOSD xmlns=\"http://www.onvif.org/ver10/media/wsdl\">");
            osdXml.append("<OSD>");
            osdXml.append("<VideoSourceConfigurationToken>").append(escapeXml(videoSourceToken)).append("</VideoSourceConfigurationToken>");
            osdXml.append("<Type>Text</Type>");
            osdXml.append("<Position>");
            osdXml.append("<Type>Custom</Type>");
            osdXml.append("<Pos x=\"").append(positionX).append("\" y=\"").append(positionY).append("\"/>");
            osdXml.append("</Position>");
            osdXml.append("<TextString>");
            osdXml.append("<Type>Plain</Type>");
            osdXml.append("<PlainText>").append(escapeXml(text)).append("</PlainText>");
            osdXml.append("</TextString>");
            osdXml.append("</OSD>");
            osdXml.append("</CreateOSD>");

            String soapEnvelope = buildSoapEnvelope(osdXml.toString(), username, password);
            return sendSoapRequest(mediaUrl, soapEnvelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "创建OSD失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除OSD配置
     */
    public static String deleteOSD(String serviceUrl, String username, String password, String osdToken) {
        try {
            String mediaUrl = resolveMediaServiceUrl(serviceUrl, username, password);
            String soapBody = "<DeleteOSD xmlns=\"http://www.onvif.org/ver10/media/wsdl\">" +
                    "<OSDToken>" + escapeXml(osdToken) + "</OSDToken>" +
                    "</DeleteOSD>";
            String soapEnvelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(mediaUrl, soapEnvelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "删除OSD失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static String getLighting(String serviceUrl, String username, String password) {
        return null;
    }

    public static String parseLighting(String response) {
        return "未实现";
    }

    public static String getVideoEncoderConfigurationOptions(String serviceUrl, String token, String username, String password) {
        try {
            String safeToken = escapeXml(token == null ? "" : token);
            String soapBody = "<soap:Body>" +
                    "<trt:GetVideoEncoderConfigurationOptions>" +
                    (safeToken.isEmpty() ? "" : "<trt:ProfileToken>" + safeToken + "</trt:ProfileToken>") +
                    "</trt:GetVideoEncoderConfigurationOptions>" +
                    "</soap:Body>";
            String envelope = buildSoapEnvelope(soapBody, username, password);
            return sendSoapRequest(resolveMediaServiceUrl(serviceUrl, username, password), envelope, username, password);
        } catch (Exception e) {
            Log.e(TAG, "获取编码参数范围失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析设备的 Media Service 地址。
     * 优先从 GetCapabilities 返回的 Media/XAddr 获取，失败时回退到路径替换。
     */
    private static String resolveMediaServiceUrl(String serviceUrl, String username, String password) {
        if (serviceUrl == null) return null;
        String cached = MEDIA_SERVICE_URLS.get(serviceUrl);
        if (cached != null) return cached;

        String resolved = null;
        try {
            String capabilities = getCapabilities(serviceUrl, username, password);
            if (capabilities != null) {
                java.util.regex.Matcher mediaMatcher = java.util.regex.Pattern.compile(
                        "<(?:[\\w.-]+:)?Media\\b[^>]*>(.*?)</(?:[\\w.-]+:)?Media>",
                        java.util.regex.Pattern.DOTALL).matcher(capabilities);
                if (mediaMatcher.find()) {
                    java.util.regex.Matcher xaddrMatcher = java.util.regex.Pattern.compile(
                            "<(?:[\\w.-]+:)?XAddr>(.*?)</(?:[\\w.-]+:)?XAddr>",
                            java.util.regex.Pattern.DOTALL).matcher(mediaMatcher.group(1));
                    if (xaddrMatcher.find()) {
                        String candidate = xaddrMatcher.group(1).trim();
                        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                            resolved = candidate;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "解析Media Service地址失败: " + e.getMessage());
        }

        if (resolved == null || resolved.isEmpty()) {
            // 认证失败或设备不支持 GetCapabilities 时使用路径回退，不缓存，便于下次凭据正确后重试
            return serviceUrl.replace("/device_service", "/media_service");
        }
        MEDIA_SERVICE_URLS.put(serviceUrl, resolved);
        return resolved;
    }

    /**
     * 设置网络配置（兼容旧代码 - 带interfaceToken版本）
     */
    public static String setNetworkConfiguration(String serviceUrl, String username, String password,
                                                  String interfaceToken, String ipAddress,
                                                  String subnetMask, String gateway) {
        return setNetworkInterfaces(serviceUrl, username, password, interfaceToken, ipAddress, subnetMask, gateway);
    }

    /**
     * 设置网络配置（兼容旧代码 - 自动获取接口Token）
     *
     * 注意：此方法会先调用GetNetworkInterfaces获取接口Token，
     * 然后使用第一个可用接口设置网络配置。
     * 建议先调用getNetworkInterfaces + parseNetworkInterfaceTokens获取Token，
     * 再使用setNetworkConfiguration(带interfaceToken版本)。
     */
    public static String setNetworkConfiguration(String serviceUrl, String username, String password,
                                                  boolean useDhcp, String ipAddress,
                                                  String subnetMask, String gateway, String dns) {
        try {
            // 先获取网络接口Token
            String ifaceResponse = getNetworkInterfaces(serviceUrl, username, password);
            List<String> tokens = parseNetworkInterfaceTokens(ifaceResponse);

            String interfaceToken;
            if (!tokens.isEmpty()) {
                interfaceToken = tokens.get(0);
                Log.d(TAG, "使用获取到的接口Token: " + interfaceToken);
            } else {
                // 回退：尝试常见接口名称
                String[] fallbackTokens = {"eth0", "lan0", "wlan0", "eth1", "NetworkInterface"};
                interfaceToken = fallbackTokens[0];
                Log.w(TAG, "未获取到接口Token，尝试使用: " + interfaceToken);
            }

            if (useDhcp) {
                // DHCP模式
                String soapBody = "<soap:Body>" +
                    "<tds:SetNetworkInterfaces>" +
                    "<tds:InterfaceToken>" + interfaceToken + "</tds:InterfaceToken>" +
                    "<tds:NetworkInterface>" +
                    "<tt:Enabled>true</tt:Enabled>" +
                    "<tt:IPv4>" +
                    "<tt:Enabled>true</tt:Enabled>" +
                    "<tt:DHCP>true</tt:DHCP>" +
                    "</tt:IPv4>" +
                    "</tds:NetworkInterface>" +
                    "</tds:SetNetworkInterfaces>" +
                    "</soap:Body>";
                String envelope = buildSoapEnvelope(soapBody, username, password);
                return sendSoapRequest(serviceUrl, envelope, username, password);
            } else {
                return setNetworkInterfaces(serviceUrl, username, password, interfaceToken, ipAddress, subnetMask, gateway);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置网络配置失败: " + e.getMessage(), e);
            return null;
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 构建SOAP Envelope（不含WS-Security头）
     * 认证通过HTTP Basic/Digest头处理
     */
    private static String buildSoapEnvelope(String soapBody, String username, String password) {
        StringBuilder envelope = new StringBuilder();
        envelope.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        envelope.append("<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"\n");
        envelope.append("    xmlns:tds=\"").append(NS_DEVICE).append("\"\n");
        envelope.append("    xmlns:trt=\"").append(NS_MEDIA).append("\"\n");
        envelope.append("    xmlns:tt=\"").append(NS_SCHEMA).append("\">\n");
        // 注意: soapBody已包含<soap:Body>标签，直接追加
        envelope.append(soapBody).append("\n");
        envelope.append("</soap:Envelope>");

        return envelope.toString();
    }

    /**
     * 构建带WS-Security PasswordDigest认证的SOAP Envelope
     * 用于HTTP Digest认证也失败时的回退方案
     */
    private static String buildEnvelopeWithPasswordDigest(String soapBody, String username, String password) {
        try {
            // 生成随机Nonce
            byte[] nonceBytes = new byte[16];
            new java.security.SecureRandom().nextBytes(nonceBytes);
            String nonce = java.util.Base64.getEncoder().encodeToString(nonceBytes);

            // 生成Created时间戳 (ISO 8601 UTC)
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String created = sdf.format(new java.util.Date());

            // 计算PasswordDigest: Base64(SHA1(nonce + created + password))
            byte[] nonceRaw = java.util.Base64.getDecoder().decode(nonce);
            byte[] createdBytes = created.getBytes(StandardCharsets.UTF_8);
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[nonceRaw.length + createdBytes.length + passwordBytes.length];
            System.arraycopy(nonceRaw, 0, combined, 0, nonceRaw.length);
            System.arraycopy(createdBytes, 0, combined, nonceRaw.length, createdBytes.length);
            System.arraycopy(passwordBytes, 0, combined, nonceRaw.length + createdBytes.length, passwordBytes.length);
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            String passwordDigest = java.util.Base64.getEncoder().encodeToString(sha1.digest(combined));

            StringBuilder envelope = new StringBuilder();
            envelope.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            envelope.append("<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"\n");
            envelope.append("    xmlns:tds=\"").append(NS_DEVICE).append("\"\n");
            envelope.append("    xmlns:trt=\"").append(NS_MEDIA).append("\"\n");
            envelope.append("    xmlns:tt=\"").append(NS_SCHEMA).append("\">\n");
            envelope.append("  <soap:Header>\n");
            envelope.append("    <Security xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\"\n");
            envelope.append("        xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">\n");
            envelope.append("      <UsernameToken wsu:Id=\"UsernameToken-1\">\n");
            envelope.append("        <Username>").append(escapeXml(username)).append("</Username>\n");
            envelope.append("        <Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">");
            envelope.append(passwordDigest).append("</Password>\n");
            envelope.append("        <Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">");
            envelope.append(nonce).append("</Nonce>\n");
            envelope.append("        <wsu:Created>").append(created).append("</wsu:Created>\n");
            envelope.append("      </UsernameToken>\n");
            envelope.append("    </Security>\n");
            envelope.append("  </soap:Header>\n");
            // 注意: 调用方可能传入已解包的 Body 内容，这里统一补齐 <soap:Body> 包裹
            String body = (soapBody == null || soapBody.trim().isEmpty())
                    ? "<soap:Body/>" : soapBody;
            if (!body.contains("<soap:Body")) {
                body = "<soap:Body>" + body + "</soap:Body>";
            }
            envelope.append(body).append("\n");
            envelope.append("</soap:Envelope>");

            return envelope.toString();
        } catch (Exception e) {
            Log.e(TAG, "构建PasswordDigest Envelope失败: " + e.getMessage(), e);
            // 降级到无WS-Security
            return buildSoapEnvelope(soapBody, username, password);
        }
    }

    private static String sendSoapRequest(String serviceUrl, String envelope) {
        return sendSoapRequestWithAuth(serviceUrl, envelope, null, null, 0);
    }

    private static String sendSoapRequest(String serviceUrl, String envelope, String username, String password) {
        return sendSoapRequestWithAuth(serviceUrl, envelope, username, password, 0);
    }

    /**
     * 发送SOAP请求，支持Basic/Digest/WS-Security PasswordDigest三级认证回退
     * 
     * 认证策略：
     * 1. 先尝试 HTTP Basic（无WS-Security）
     * 2. 401时尝试 HTTP Digest
     * 3. Digest也失败时，使用WS-Security PasswordDigest重试
     */
    private static String sendSoapRequestWithAuth(String serviceUrl, String envelope,
                                          String username, String password, int retryCount) {
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(serviceUrl)
                    .post(RequestBody.create(envelope, SOAP_MEDIA_TYPE));

            // 如果有认证信息，添加Authorization头
            if (username != null && password != null) {
                // 先尝试Basic认证
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                requestBuilder.header("Authorization", "Basic " + auth);
            }

            Request request = requestBuilder.build();

            try (Response response = CLIENT.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return response.body() != null ? response.body().string() : "";
                } else if (response.code() == 401 && username != null && password != null) {
                    // 尝试HTTP Digest认证
                    boolean digestTried = false;
                    if (retryCount < 1) {
                        String authHeader = response.header("WWW-Authenticate");
                        if (authHeader != null && authHeader.contains("Digest")) {
                            String digestResult = sendDigestAuthRequest(serviceUrl, envelope, authHeader, username, password);
                            if (digestResult != null) {
                                return digestResult;
                            }
                            digestTried = true;
                            Log.d(TAG, "HTTP Digest认证返回空结果");
                        }
                    }
                    // Digest失败或不可用，尝试WS-Security PasswordDigest（标准ONVIF认证方式）
                    if (retryCount < (digestTried ? 2 : 1)) {
                        Log.d(TAG, (digestTried ? "HTTP Digest认证失败" : "WWW-Authenticate无Digest挑战") 
                                + "，尝试WS-Security PasswordDigest");
                        String digestEnvelope = buildEnvelopeWithPasswordDigest(
                            extractSoapBody(envelope), username, password);
                        Request.Builder digestBuilder = new Request.Builder()
                                .url(serviceUrl)
                                .post(RequestBody.create(digestEnvelope, SOAP_MEDIA_TYPE));
                        Request digestRequest = digestBuilder.build();
                        try (Response digestResp = CLIENT.newCall(digestRequest).execute()) {
                            if (digestResp.isSuccessful()) {
                                return digestResp.body() != null ? digestResp.body().string() : "";
                            } else {
                                Log.w(TAG, "WS-Security PasswordDigest认证失败: " + digestResp.code());
                            }
                        }
                    }
                    Log.w(TAG, "所有认证方式均失败 (retryCount=" + retryCount + ")");
                    return null;
                } else {
                    Log.w(TAG, "SOAP请求失败: " + response.code());
                    return null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "发送SOAP请求失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从完整的SOAP Envelope中提取Body内容
     */
    private static String extractSoapBody(String envelope) {
        if (envelope == null) return "";
        int bodyStart = envelope.indexOf("<soap:Body>");
        int bodyEnd = envelope.indexOf("</soap:Body>");
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            return envelope.substring(bodyStart + "<soap:Body>".length(), bodyEnd);
        }
        // 尝试无命名空间前缀
        bodyStart = envelope.indexOf("<Body>");
        bodyEnd = envelope.indexOf("</Body>");
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            return envelope.substring(bodyStart + "<Body>".length(), bodyEnd);
        }
        return envelope;
    }

    /**
     * 发送Digest认证请求
     */
    private static String sendDigestAuthRequest(String serviceUrl, String envelope,
                                                String authHeader, String username, String password) {
        try {
            // 解析Digest参数
            String realm = extractDigestParam(authHeader, "realm");
            String nonce = extractDigestParam(authHeader, "nonce");
            String qop = extractDigestParam(authHeader, "qop");
            
            if (realm == null || nonce == null) {
                Log.w(TAG, "无法解析Digest认证参数");
                return null;
            }

            // 生成cnonce
            String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String nc = "00000001";
            String method = "POST";
            String uri = java.net.URI.create(serviceUrl).getPath();
            if (uri == null || uri.isEmpty()) uri = "/";

            // 计算HA1 = MD5(username:realm:password)
            String ha1 = md5(username + ":" + realm + ":" + password);
            // 计算HA2 = MD5(method:uri)
            String ha2 = md5(method + ":" + uri);
            // 计算response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
            String response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

            // 构建Authorization头
            StringBuilder authBuilder = new StringBuilder();
            authBuilder.append("Digest username=\"").append(username).append("\", ");
            authBuilder.append("realm=\"").append(realm).append("\", ");
            authBuilder.append("nonce=\"").append(nonce).append("\", ");
            authBuilder.append("uri=\"").append(uri).append("\", ");
            authBuilder.append("qop=").append(qop).append(", ");
            authBuilder.append("nc=").append(nc).append(", ");
            authBuilder.append("cnonce=\"").append(cnonce).append("\", ");
            authBuilder.append("response=\"").append(response).append("\"");

            Request request = new Request.Builder()
                    .url(serviceUrl)
                    .post(RequestBody.create(envelope, SOAP_MEDIA_TYPE))
                    .header("Authorization", authBuilder.toString())
                    .build();

            try (Response resp = CLIENT.newCall(request).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    return resp.body().string();
                } else {
                    Log.w(TAG, "Digest认证请求失败: " + resp.code());
                    return null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Digest认证失败: " + e.getMessage(), e);
            return null;
        }
    }

    private static String extractDigestParam(String authHeader, String paramName) {
        String pattern = paramName + "=\"([^\"]+)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(authHeader);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    private static javax.net.ssl.SSLSocketFactory createNaiveSSLSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new NaiveTrustManager()}, null);
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class NaiveTrustManager implements X509TrustManager {
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

    /**
     * 解码 scope 里的转义字符。
     *
     * <p>ONVIF 的 Scopes 是 URI 形式，名称里的空格等字符会被转义，
     * 例如 {@code onvif://www.onvif.org/name/HD%20IPC} 应显示为 {@code HD IPC}。
     * 这里只做 URL 解码，不处理 {@code +}（scope 中它是真实字符）。</p>
     */
    private static String decodeScope(String value) {
        if (value == null || value.isEmpty() || value.indexOf('%') < 0) return value;
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
