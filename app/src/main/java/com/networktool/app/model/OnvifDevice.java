package com.networktool.app.model;

/**
 * ONVIF设备数据模型
 *
 * 存储ONVIF摄像头设备的完整信息，包括：
 * - 网络连接信息（IP、端口、服务URL）
 * - 设备标识信息（名称、UUID、制造商、型号）
 * - 硬件信息（固件版本、序列号、硬件ID）
 * - 认证信息（用户名、密码、认证状态）
 * - 流媒体信息（RTSP地址）
 * - 品牌识别信息
 *
 * 使用方式：
 * - 设备发现时创建并填充基本信息
 * - 认证后填充凭据和详细信息
 * - 获取流时填充RTSP地址
 */
public class OnvifDevice {
    /* 设备显示名称 */
    private String name;

    /* 设备IP地址 */
    private String ipAddress;

    /* 设备ONVIF服务端口（默认80） */
    private int port;

    /* 硬件类型信息 */
    private String hardware;

    /* 设备物理位置信息 */
    private String location;

    /* 设备制造商名称 */
    private String manufacturer;

    /* 设备型号 */
    private String model;

    /* 固件版本号 */
    private String firmwareVersion;

    /* 设备序列号 */
    private String serialNumber;

    /* 设备作用域信息 */
    private String scopes;

    /* RTSP视频流地址 */
    private String rtspUrl;

    /* 认证用户名 */
    private String username;

    /* 认证密码 */
    private String password;

    /* 是否已通过认证 */
    private boolean authenticated;

    /* 识别出的设备品牌（中文名称） */
    private String brand;

    /* 设备唯一标识符（UUID） */
    private String uuid;

    /** WS-Discovery 里声明的设备类型（摄像头 / 录像机 / 电脑 …），用于避免把电脑当成摄像头 */
    private String deviceType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getHardware() {
        return hardware;
    }

    public void setHardware(String hardware) {
        this.hardware = hardware;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    /**
     * 获取设备服务地址（Device Service）
     *
     * 根据IP和端口构建ONVIF Device Service URL。
     * 格式：http://{ip}:{port}/onvif/device_service
     *
     * @return ONVIF Device Service 地址
     */
    public String getServiceUrl() {
        return "http://" + ipAddress + ":" + port + "/onvif/device_service";
    }

    /**
     * 获取媒体服务地址（Media Service）
     *
     * 根据IP和端口构建ONVIF Media Service URL。
     * 格式：http://{ip}:{port}/onvif/media_service
     *
     * @return ONVIF Media Service 地址
     */
    public String getMediaServiceUrl() {
        return "http://" + ipAddress + ":" + port + "/onvif/media_service";
    }

    /**
     * 设置设备服务地址（空操作）
     *
     * 服务地址由getServiceUrl()动态计算，
     * 此方法保留以兼容旧代码。
     *
     * @param serviceUrl 服务地址（忽略）
     */
    public void setServiceUrl(String serviceUrl) {
        // serviceUrl is computed dynamically, this is a no-op
    }

    /**
     * 获取设备显示名称
     *
     * 按优先级返回设备名称：
     * 1. 自定义名称（如果设置）
     * 2. 设备型号
     * 3. IP地址
     * 4. 默认"未知设备"
     *
     * @return 适合显示的设备名称
     */
    public String getDisplayName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        if (model != null && !model.isEmpty()) {
            return model;
        }
        if (manufacturer != null && !manufacturer.isEmpty()) {
            return manufacturer;
        }
        // 电脑/录像机这类设备没有 name 作用域，用类型兜底，别再一律显示成“摄像头”
        if (deviceType != null && !deviceType.isEmpty()) {
            return deviceType;
        }
        if (ipAddress != null && !ipAddress.isEmpty()) {
            return ipAddress;
        }
        return "未知设备";
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    /** 是否是摄像头/编码器这类视频源（电脑、显示器不算） */
    public boolean isCamera() {
        if (deviceType == null) return true;
        return deviceType.contains("摄像头") || deviceType.contains("录像机")
                || deviceType.contains("编码器") || deviceType.contains("视频");
    }
}
