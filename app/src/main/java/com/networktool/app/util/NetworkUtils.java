package com.networktool.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 网络工具类
 * 
 * 提供常用的网络相关功能：
 * - 网络连接状态检测（WiFi、移动数据、以太网）
 * - 获取网关地址
 * - 获取IP地址
 * - 端口连通性测试
 * - IP地址格式转换
 */
public class NetworkUtils {

    /**
     * 检查是否通过WiFi连接网络
     * @param context 上下文
     * @return 如果是WiFi连接返回true，否则返回false
     */
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                return info != null && info.isConnected()
                        && info.getType() == ConnectivityManager.TYPE_WIFI;
            }
            Network network = connectivityManager.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return true;
                }
            }
            // 活动网络可能是移动数据（例如连的是没有外网的摄像头/内网 WiFi），
            // 此时只要系统里存在已连接的 WiFi 网络，也要认为 WiFi 可用，
            // 否则局域网扫描会直接判定“没有 WiFi”而不工作。
            return hasTransport(connectivityManager, NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 系统中是否存在某种传输方式（WiFi/以太网）的已连接网络。
     */
    private static boolean hasTransport(ConnectivityManager connectivityManager, int transport) {
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(transport)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 获取可用于局域网扫描的网络（优先 WiFi，其次以太网）。
     * 不依赖“当前活动网络”，因为内网无外网时活动网络往往还是移动数据。
     *
     * @return 对应的 {@link Network}，没有可用局域网时返回 null
     */
    public static Network getLanNetwork(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;
        try {
            Network wifi = null;
            Network ethernet = null;
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null) continue;
                if (wifi == null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifi = network;
                } else if (ethernet == null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethernet = network;
                }
            }
            return wifi != null ? wifi : ethernet;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 把当前进程的默认网络切到局域网（WiFi/以太网）。
     *
     * 内网没有外网时，系统的默认网络仍是移动数据，未显式绑定网络的 Socket
     * 会把广播/探测包发到移动数据网卡，导致扫描不到任何内网设备。
     * 扫描前绑定、扫描结束解绑即可，不影响其他功能。
     * 内部带引用计数，多个页面同时持有不会互相踢掉，最后一个释放时才解绑。
     *
     * @return 是否绑定成功
     */
    public static synchronized boolean acquireLanNetwork(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        Network lanNetwork = getLanNetwork(context);
        if (lanNetwork == null) return false;
        try {
            connectivityManager.bindProcessToNetwork(lanNetwork);
            lanBindRefCount++;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 释放一次局域网网络绑定，引用计数归零时恢复系统默认网络。
     */
    public static synchronized void releaseLanNetwork(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (lanBindRefCount > 0) lanBindRefCount--;
        if (lanBindRefCount > 0) return;
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context
                    .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                connectivityManager.bindProcessToNetwork(null);
            }
        } catch (Exception ignored) {
        }
    }

    /** 局域网网络绑定的引用计数 */
    private static int lanBindRefCount = 0;

    /**
     * 是否内网 IPv4 地址（10/8、172.16/12、192.168/16、169.254/16）
     */
    public static boolean isPrivateIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) return false;
        int a, b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (a == 10) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 169 && b == 254) return true;
        return false;
    }

    /**
     * 是否常见家用/办公局域网网段（192.168.0.0/16、10.0.0.0/8）。
     *
     * <p>172.16.0.0/12 虽然也是私网段，但运营商内部网络、CGNAT、VPN、容器网络
     * 大量使用它，自动探测时把它当成「上级局域网」去扫描，扫到的多半是运营商设备，
     * 所以自动探测只认这两种。</p>
     */
    public static boolean isCommonLanSubnet(String ip) {
        if (ip == null) return false;
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 192 && b == 168) return true;
            return a == 10;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 主机名或 IP 是否属于局域网目标。
     * 私网 IP、不带点的主机名、.local/.lan/.home 之类的名字按内网处理，其余按公网处理。
     */
    public static boolean isLanTarget(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        if (h.isEmpty()) return false;
        if (isPrivateIpv4(h)) return true;
        if (h.indexOf('.') < 0) return true; // localhost、nas 这类单段主机名
        return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home");
    }

    /**
     * 取 IPv4 地址所在 /24 的前缀，例如 192.168.1.23 → "192.168.1."
     */
    public static String subnetPrefix24(String ip) {
        if (ip == null) return null;
        int lastDot = ip.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return ip.substring(0, lastDot + 1);
    }

    /**
     * 获取局域网扫描应绑定的 Network（供工具页判断用）
     */
    public static Network getLanNetworkForScan(Context context) {
        return getLanNetwork(context);
    }

    /**
     * 检查是否通过移动数据连接网络
     * @param context 上下文
     * @return 如果是移动数据连接返回true，否则返回false
     */
    public static boolean isMobileDataConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                return info != null && info.isConnected()
                        && info.getType() == ConnectivityManager.TYPE_MOBILE;
            }
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null) return false;

            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查网络是否已连接（支持WiFi、移动数据、以太网）
     * @param context 上下文
     * @return 如果任一网络已连接返回true，否则返回false
     */
    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            return capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取局域网（WiFi 优先，其次以太网）的网关地址。
     * 不依赖系统「活动网络」——连着没有外网的 WiFi 时，活动网络会是移动数据，
     * 用活动网络取网关会拿到运营商的网关，页面上就会显示成移动数据的路由器。
     */
    public static String getLanGateway(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return null;
        Network lanNetwork = getLanNetwork(context);
        if (lanNetwork == null) return null;
        try {
            String gateway = extractGateway(connectivityManager.getLinkProperties(lanNetwork));
            if (gateway != null) return gateway;
        } catch (Exception ignored) {
        }
        // WiFi 网络读不到路由时退回 DHCP
        String wifiGateway = getWifiDhcpGateway(context);
        if (wifiGateway != null) return wifiGateway;
        try {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(lanNetwork);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return extractGateway(connectivityManager.getLinkProperties(lanNetwork));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 局域网（WiFi 优先，其次以太网）的本地 IPv4 地址。
     * 移动数据成为系统活动网络时，页面统计、设备过滤都应该用这个，而不是移动数据的 IP。
     */
    public static String getLanLocalIpv4Address(Context context) {
        String wifiIp = getWifiLocalIpv4Address(context);
        if (isIpv4(wifiIp) && !"0.0.0.0".equals(wifiIp)) return wifiIp;
        String ethernetIp = getEthernetLocalIpv4Address(context);
        if (isIpv4(ethernetIp) && !"0.0.0.0".equals(ethernetIp)) return ethernetIp;
        return null;
    }

    /**
     * 局域网网络的首选 DNS（WiFi/以太网），拿不到返回 null。
     */
    public static String getLanDns1(Context context) {
        List<Inet4Address> servers = getLanDnsServers(context);
        return servers.isEmpty() ? null : servers.get(0).getHostAddress();
    }

    /** 局域网网络的备用 DNS，拿不到返回 null */
    public static String getLanDns2(Context context) {
        List<Inet4Address> servers = getLanDnsServers(context);
        return servers.size() < 2 ? null : servers.get(1).getHostAddress();
    }

    private static List<Inet4Address> getLanDnsServers(Context context) {
        List<Inet4Address> result = new ArrayList<>();
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context
                    .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network lanNetwork = getLanNetwork(context);
            if (connectivityManager == null || lanNetwork == null) return result;
            LinkProperties linkProperties = connectivityManager.getLinkProperties(lanNetwork);
            if (linkProperties == null) return result;
            for (InetAddress address : linkProperties.getDnsServers()) {
                if (address instanceof Inet4Address) {
                    result.add((Inet4Address) address);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * 获取网关地址
     * @param context 上下文
     * @return 网关IP地址字符串，获取失败返回null
     */
    public static String getGateway(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        // 优先用 WiFi/以太网的网关。内网 WiFi 没有外网时，系统活动网络仍是移动数据，
        // 直接读活动网络会拿到运营商网关，首页「路由器后台」就会指错地方。
        String lanGateway = getLanGateway(context);
        if (lanGateway != null) return lanGateway;

        // 优先使用当前活动网络的 LinkProperties。它对 WiFi/以太网都适用，
        // 比读取 WiFi DHCP 或 /proc/net/route 更准确。
        try {
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = connectivityManager.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
                    String gateway = extractGateway(linkProperties);
                    if (gateway != null) return gateway;

                    // 活动网络是 WiFi 时，再回退到 WiFi DHCP
                    NetworkCapabilities capabilities =
                            connectivityManager.getNetworkCapabilities(activeNetwork);
                    if (capabilities != null
                            && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        String wifiGateway = getWifiDhcpGateway(context);
                        if (wifiGateway != null) return wifiGateway;
                    }
                }
            } else {
                // Android 5/6 没有 getActiveNetwork()，使用 WiFi DHCP
                String wifiGateway = getWifiDhcpGateway(context);
                if (wifiGateway != null) return wifiGateway;
            }
        } catch (Exception ignored) {
        }

        // 最后尝试读取系统路由表
        return getGatewayFromRoute();
    }

    /**
     * 从 LinkProperties 的默认路由中提取网关（优先 IPv4）
     */
    private static String extractGateway(LinkProperties linkProperties) {
        if (linkProperties == null) return null;
        try {
            for (RouteInfo route : linkProperties.getRoutes()) {
                if (route == null || !route.isDefaultRoute()) continue;
                InetAddress gateway = route.getGateway();
                if (gateway instanceof Inet4Address && !gateway.isAnyLocalAddress()) {
                    return gateway.getHostAddress();
                }
            }
            // DHCP 服务器地址通常就是网关，API 30+ 可用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InetAddress dhcpServer = linkProperties.getDhcpServerAddress();
                if (dhcpServer instanceof Inet4Address && !dhcpServer.isAnyLocalAddress()) {
                    return dhcpServer.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 获取任意一块已连接以太网网的网关（不要求它是系统当前活动网络）。
     * 适用于有线网没有互联网、系统仍把移动数据作为活动网络的场景。
     */
    public static String getEthernetGateway(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return null;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    String gateway = extractGateway(connectivityManager.getLinkProperties(network));
                    if (gateway != null) return gateway;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 获取任意一块已连接以太网网的 IPv4 地址
     */
    public static String getEthernetLocalIpv4Address(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return null;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                    if (linkProperties != null) {
                        for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                            InetAddress address = linkAddress.getAddress();
                            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String getWifiDhcpGateway(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context
                    .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null && wifiInfo.getNetworkId() != -1) {
                    String gateway = intToIp(wifiManager.getDhcpInfo().gateway);
                    if (gateway != null && !gateway.equals("0.0.0.0")) {
                        return gateway;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 获取 WiFi 网关。移动数据是当前活动网络时，仍优先返回 WiFi 网关。
     */
    public static String getWifiGateway(Context context) {
        return getWifiDhcpGateway(context);
    }

    /**
     * 获取 WiFi 接口的 IPv4 地址。避免移动数据作为系统活动网络时，
     * 设备扫描错误地使用移动数据网段。
     */
    public static String getWifiLocalIpv4Address(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context
                    .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                android.net.DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                if (dhcpInfo != null && dhcpInfo.ipAddress != 0) {
                    String ip = intToIp(dhcpInfo.ipAddress);
                    if (isIpv4(ip) && !"0.0.0.0".equals(ip)) return ip;
                }
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null && wifiInfo.getNetworkId() != -1) {
                    String ip = intToIp(wifiInfo.getIpAddress());
                    if (isIpv4(ip) && !"0.0.0.0".equals(ip)) return ip;
                }
            }
        } catch (Exception ignored) {
        }
        String fallback = getLocalIpAddress();
        return isIpv4(fallback) && !"0.0.0.0".equals(fallback) ? fallback : null;
    }

    private static boolean isIpv4(String ip) {
        return ip != null && ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    /**
     * 从路由表获取默认网关
     */
    private static String getGatewayFromRoute() {
        try {
            Process process = Runtime.getRuntime().exec("ip route show default");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // 格式: default via 192.168.1.1 dev eth0
                if (line.contains("default via")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        reader.close();
                        return parts[2];
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 备用方法：读取 /proc/net/route
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/route"));
            String line;
            reader.readLine(); // 跳过标题行
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    String destination = parts[1];
                    String gatewayHex = parts[2];
                    // 默认路由的目的地为 00000000
                    if ("00000000".equals(destination)) {
                        // 将十六进制网关地址转换为IP（注意字节序）
                        int gatewayInt = Integer.parseInt(gatewayHex, 16);
                        // 需要反转字节序
                        int reversed = Integer.reverseBytes(gatewayInt);
                        String gateway = intToIp(reversed);
                        reader.close();
                        return gateway;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取首选DNS服务器地址
     * @param context 上下文
     * @return DNS服务器IP地址，获取失败返回null
     */
    public static String getDns1(Context context) {
        List<Inet4Address> servers = getActiveDnsServers(context);
        return servers.isEmpty() ? null : servers.get(0).getHostAddress();
    }

    /**
     * 获取备用DNS服务器地址
     * @param context 上下文
     * @return DNS服务器IP地址，获取失败返回null
     */
    public static String getDns2(Context context) {
        List<Inet4Address> servers = getActiveDnsServers(context);
        return servers.size() < 2 ? null : servers.get(1).getHostAddress();
    }

    /**
     * 获取当前活动网络的 DNS（WiFi、以太网、移动数据都适用）
     */
    private static List<Inet4Address> getActiveDnsServers(Context context) {
        List<Inet4Address> result = new ArrayList<>();
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = connectivityManager.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (InetAddress address : linkProperties.getDnsServers()) {
                            if (address instanceof Inet4Address) {
                                result.add((Inet4Address) address);
                            }
                        }
                    }
                }
            }
            if (result.isEmpty()) {
                WifiManager wifiManager = (WifiManager) context
                        .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    android.net.DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                    if (dhcpInfo != null) {
                        int dns1 = dhcpInfo.dns1;
                        int dns2 = dhcpInfo.dns2;
                        if (dns1 != 0) {
                            InetAddress address = InetAddress.getByName(intToIp(dns1));
                            if (address instanceof Inet4Address) result.add((Inet4Address) address);
                        }
                        if (dns2 != 0) {
                            InetAddress address = InetAddress.getByName(intToIp(dns2));
                            if (address instanceof Inet4Address) result.add((Inet4Address) address);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * 将整数IP地址转换为点分十进制格式
     * @param ip 整数表示的IP地址
     * @return 点分十进制格式的IP字符串
     */
    public static String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    /**
     * 获取本地IPv4地址（仅返回已激活的非移动数据网络接口IP）
     *
     * 优先返回WiFi/以太网接口的IP，排除移动数据接口（rmnet、pdp等）。
     *
     * @return 本地IPv4地址字符串，获取失败返回null
     */
    public static String getLocalIpAddress() {
        try {
            String fallbackIp = null;
            String mobileDataIp = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // 跳过未激活的接口和回环接口
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                String name = networkInterface.getName().toLowerCase();

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        // 优先返回WiFi/以太网接口的IP
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return ip;
                        }
                        // 记录备选IP（WiFi/以太网的其他IP）
                        if (fallbackIp == null) {
                            fallbackIp = ip;
                        }
                        // 记录移动数据接口IP作为最后备选
                        if (mobileDataIp == null && (name.startsWith("rmnet") || name.startsWith("pdp")
                                || name.startsWith("ccmni") || name.startsWith("rmnet_data"))) {
                            mobileDataIp = ip;
                        }
                    }
                }
            }
            // 没有WiFi/以太网时返回备选IP（包括移动数据）
            if (fallbackIp != null) return fallbackIp;
            return mobileDataIp;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 判断本机是哪一类设备，返回给列表用的分类标签。
     *
     * <p>以前本机在列表里一律标「电脑」，因为占位行写死了 {@code ICON_COMPUTER}。
     * 实际跑这个 App 的绝大多数是手机/平板，标成电脑是错的。这里按系统信息判断：</p>
     *
     * <ul>
     *   <li>{@code uiMode} = TELEVISION / WATCH / CAR → 电视 / 手表 / 车机</li>
     *   <li>最小宽度 ≥ 600dp → 平板</li>
     *   <li>有电话功能 → 手机</li>
     *   <li>都没有 → 电脑（平板形态的桌面系统 / 模拟器）</li>
     * </ul>
     */
    public static String getLocalDeviceTypeLabel(Context context) {
        try {
            if (context != null) {
                android.content.res.Configuration config = context.getResources().getConfiguration();
                int uiMode = config.uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK;
                if (uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return "电视";
                if (uiMode == android.content.res.Configuration.UI_MODE_TYPE_WATCH) return "手表";
                if (uiMode == android.content.res.Configuration.UI_MODE_TYPE_CAR) return "车机";
                int smallestWidth = config.smallestScreenWidthDp;
                if (smallestWidth >= 600) return "平板";
                if (context.getPackageManager()
                        .hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) {
                    return "手机";
                }
            }
        } catch (Exception ignored) {
        }
        return "电脑";
    }

    /**
     * 获取本机设备的显示名称。
     *
     * <p>优先用用户在系统设置里改过的「设备名称」（`Settings.Global.DEVICE_NAME`，
     * 和蓝牙/热点名同一个值），没有就用机型（`Build.MODEL`），最后兜底 "本机"。</p>
     *
     * <p>不能只用 {@code InetAddress.getLocalHost().getHostName()}：Android 上它
     * 通常返回 "localhost" 或 "android-xxxx"，对用户没有意义。</p>
     */
    public static String getLocalDeviceName(Context context) {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        // 机型名：厂商 + 型号，避免「M511CD」「2211133C」这种看不出是谁的代号
        String modelLabel = model;
        if (!model.isEmpty() && !manufacturer.isEmpty()
                && !model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            modelLabel = manufacturer + " " + model;
        }

        // 1) 系统「设备名称」：用户在设置里能改，也是热点/蓝牙显示的名字
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && context != null) {
            try {
                String deviceName = android.provider.Settings.Global.getString(
                        context.getContentResolver(),
                        android.provider.Settings.Global.DEVICE_NAME);
                if (deviceName != null && !deviceName.trim().isEmpty()) {
                    String trimmed = deviceName.trim();
                    // 系统默认值就是机型代号时，补上厂商标识更好认
                    if (!model.isEmpty() && trimmed.equalsIgnoreCase(model)) {
                        return modelLabel;
                    }
                    return trimmed;
                }
            } catch (Exception ignored) {
            }
        }
        if (!modelLabel.isEmpty()) {
            return modelLabel;
        }
        return "本机";
    }

    /**
     * 获取当前活动网络（WiFi/以太网/移动数据）的 IPv4 地址。
     * 通过 LinkProperties 读取，避免枚举 NetworkInterface 时拿到错误接口。
     */
    public static String getActiveLocalIpv4Address(Context context) {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context
                    .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = connectivityManager.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                            InetAddress address = linkAddress.getAddress();
                            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return getLocalIpAddress();
    }

    /**
     * 获取本地链路本地 IPv6 地址
     * @return IPv6地址字符串（fe80::开头），获取失败返回null
     */
    public static String getLocalIpv6Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                String name = networkInterface.getName().toLowerCase(Locale.US);
                if (name.startsWith("rmnet") || name.startsWith("pdp")
                        || name.startsWith("ccmni") || name.startsWith("rmnet_data")) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address && !address.isLoopbackAddress()
                            && address.isLinkLocalAddress()) {
                        String ip = address.getHostAddress();
                        // 移除 % 接口后缀
                        int percentIdx = ip.indexOf('%');
                        return percentIdx > 0 ? ip.substring(0, percentIdx) : ip;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 判断是否是IPv6链路本地地址（fe80::/10）
     */
    public static boolean isLinkLocalIpv6(String ip) {
        return ip != null && ip.toLowerCase(Locale.US).startsWith("fe80");
    }

    /**
     * 获取IP地址的版本
     * @param ip IP地址
     * @return 4 或 6，无法识别返回0
     */
    public static int getIpVersion(String ip) {
        if (ip == null || ip.isEmpty()) return 0;
        if (ip.contains(":")) return 6;
        if (ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) return 4;
        return 0;
    }

    /**
     * 获取本地网络的广播地址
     * @return 广播地址字符串，获取失败返回"255.255.255.255"
     */
    public static String getBroadcastAddress() {
        try {
            String localIp = getLocalIpAddress();
            if (localIp != null && !localIp.isEmpty()) {
                // 将IP地址的最后一段替换为255得到广播地址
                // 例如: 192.168.1.100 -> 192.168.1.255
                String[] parts = localIp.split("\\.");
                if (parts.length == 4) {
                    return parts[0] + "." + parts[1] + "." + parts[2] + ".255";
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 回退到通用广播地址
        return "255.255.255.255";
    }

    /**
     * 获取当前使用的IPv4地址（通过连接外部服务器确定）
     * @return IPv4地址字符串，获取失败时回退到getLocalIpAddress
     */
    public static String getCurrentIpV4() {
        try {
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress("8.8.8.8", 53), 1000);
            String localAddress = socket.getLocalAddress().getHostAddress();
            socket.close();
            return localAddress;
        } catch (Exception e) {
            return getLocalIpAddress();
        }
    }

    /**
     * 验证IPv4地址格式是否有效
     * @param ip IP地址字符串
     * @return 如果是有效的IPv4地址返回true，否则返回false
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
        return ip.matches(pattern);
    }

    /**
     * 验证URL格式是否有效
     * @param url URL字符串
     * @return 如果是有效的URL返回true，否则返回false
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String pattern = "^(https?://)?[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$";
        return url.matches(pattern);
    }

    /**
     * 比较两个IP地址的大小
     * @param ip1 第一个IP地址
     * @param ip2 第二个IP地址
     * @return ip1小于ip2返回负数，等于返回0，大于返回正数
     */
    public static int compareIp(String ip1, String ip2) {
        long num1 = ipToNumber(ip1);
        long num2 = ipToNumber(ip2);
        return Long.compare(num1, num2);
    }

    private static long ipToNumber(String ip) {
        if (ip == null) return Long.MAX_VALUE;
        String[] parts = ip.split(":")[0].split("\\.");
        if (parts.length != 4) return Long.MAX_VALUE;
        try {
            return (Long.parseLong(parts[0]) << 24) +
                    (Long.parseLong(parts[1]) << 16) +
                    (Long.parseLong(parts[2]) << 8) +
                    Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
