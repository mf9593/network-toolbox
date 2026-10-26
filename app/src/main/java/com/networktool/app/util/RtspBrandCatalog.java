package com.networktool.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 常见网络摄像头品牌的 RTSP 地址模板（数据参考 rtsp.cam/zh/rtsp-urls，共 162 个品牌）。
 *
 * <p>模板占位符：{@code {user}} 用户名、{@code {pass}} 密码、{@code {host}} 主机、
 * {@code {port}} 端口、{@code {ch}} 通道号。生成地址时按用户输入替换。</p>
 */
public final class RtspBrandCatalog {

    private RtspBrandCatalog() {
    }

    /** 一条候选地址：标签 + 模板 */
    public static final class Stream {
        public final String label;
        public final String template;

        Stream(String label, String template) {
            this.label = label;
            this.template = template;
        }
    }

    /** 一个品牌：名称 + 默认端口 + 默认用户名 + 若干候选地址 */
    public static final class Brand {
        public final String name;
        public final int port;
        public final String defaultUser;
        public final List<Stream> streams;

        Brand(String name, int port, String defaultUser, Stream... streams) {
            this.name = name;
            this.port = port;
            this.defaultUser = defaultUser;
            this.streams = new ArrayList<>();
            Collections.addAll(this.streams, streams);
        }
    }

    private static final List<Brand> BRANDS = new ArrayList<>();

    static {
        add(new Brand("360Eye", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/ch00_0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1")));
        add(new Brand("7Links", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/ch0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/videoMain"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live1.264")));
        add(new Brand("ABUS", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live.sdp")));
        add(new Brand("ADT", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/img/video.sav"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam4/mpeg4")));
        add(new Brand("Advidia", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/MediaInput/mpeg4")));
        add(new Brand("Agasio", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/videoMain")));
        add(new Brand("AirLink", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/mpeg4")));
        add(new Brand("AirLive", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/rtsph2641080p"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/stream1")));
        add(new Brand("Alecto", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/live/av0?user={user}&passwd={pass}")));
        add(new Brand("Alhua", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor")));
        add(new Brand("ALLNET", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/video.h264")));
        add(new Brand("Amcrest", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1")));
        add(new Brand("Amovision", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12")));
        add(new Brand("Annke", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}01"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}02"),
                new Stream("旧版固件", "rtsp://{user}:{pass}@{host}:{port}/H264/ch{ch}/main/av_stream")));
        add(new Brand("Anpviz", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam1/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/h264_stream"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/mpeg4")));
        add(new Brand("ANRAN", 10554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}&password={pass}&channel={ch}&stream=0.sdp?"),
                new Stream("备用路径", "rtsp://{host}:{port}/ch0_0.264")));
        add(new Brand("Apexis", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/mpeg4/media.amp")));
        add(new Brand("Avigilon", 554, "administrator",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/defaultPrimary?streamType=u"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/defaultSecondary?streamType=u")));
        add(new Brand("Axis", 554, "root",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/axis-media/media.amp?camera=1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/axis-media/media.amp?camera=1&resolution=640x360"),
                new Stream("主码流（H.265 型号）", "rtsp://{user}:{pass}@{host}:{port}/axis-media/media.amp?camera=1&videocodec=h265")));
        add(new Brand("Besder", 10554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/h264_stream")));
        add(new Brand("Beward", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/av0_0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/av0_1")));
        add(new Brand("Boavision", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{host}:{port}/1/{user}:{pass}/main"),
                new Stream("备用路径", "rtsp://{host}:{port}/1/h264major")));
        add(new Brand("Bosch", 554, "service",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/?h26x=4&line=1&inst=1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/?h26x=4&line=1&inst=2")));
        add(new Brand("Brickcom", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ONVIF/channel1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ONVIF/channel2"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/channel1")));
        add(new Brand("CamHi", 5544, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Canon", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/stream/profile2=r"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/stream/profile1=r")));
        add(new Brand("CDR King", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/12")));
        add(new Brand("Cisco", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/img/media.sav"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/img/video.sav"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/[code]")));
        add(new Brand("Compro", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/medias1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/medias2"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/1")));
        add(new Brand("Conceptronic", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/ch0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/ch2"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor")));
        add(new Brand("Cotier", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch01.264")));
        add(new Brand("CP Plus", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor")));
        add(new Brand("Ctronics", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/1")));
        add(new Brand("D-Link", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live1.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/live2.sdp"),
                new Stream("旧版固件", "rtsp://{user}:{pass}@{host}:{port}/play1.sdp")));
        add(new Brand("Dahua", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1"),
                new Stream("第三码流", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=2")));
        add(new Brand("DBPOWER", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264")));
        add(new Brand("Denver", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11/videostream.cgi"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/MediaInput/mpeg4")));
        add(new Brand("Dericam", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ONVIF/channel2")));
        add(new Brand("Digital Watchdog", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/channel1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mjpeg")));
        add(new Brand("DIGITUS", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12")));
        add(new Brand("Digoo", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/onvif1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/h264")));
        add(new Brand("Edimax", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ipcam_h264.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ipcam.sdp")));
        add(new Brand("ELP", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}&password={pass}&channel={ch}&stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}&password={pass}&channel={ch}&stream=1.sdp?")));
        add(new Brand("ELRO", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/1/{user}:{pass}/main")));
        add(new Brand("Eminent", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("eneo", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live1.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch1/stream0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/h264")));
        add(new Brand("ESCAM", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=1.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/11")));
        add(new Brand("eufy", 554, "在 App 中设置",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live0")));
        add(new Brand("EverFocus", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cgi-bin/rtspStreamOvf/1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cgi-bin/rtspStreamOvf/0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/streaming/channels/0")));
        add(new Brand("EZVIZ", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264/ch{ch}/main/av_stream"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/h264/ch{ch}/sub/av_stream")));
        add(new Brand("Falcon Eye", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live3.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/ch01_sub.264")));
        add(new Brand("FDT", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12")));
        add(new Brand("FLIR", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/stream1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0")));
        add(new Brand("Floureon", 10554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/1/h264major"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/cam1/mpeg4")));
        add(new Brand("Foscam", 88, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/videoMain"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/videoSub")));
        add(new Brand("Ganz", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/gnz_media/main"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0_unicast_firststream")));
        add(new Brand("Genbolt", 5544, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/ch0")));
        add(new Brand("GeoVision", 8554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/CH001.sdp")));
        add(new Brand("Grandstream", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/4")));
        add(new Brand("GW Security", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/mpeg4cif"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live1.264")));
        add(new Brand("H.View", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/main")));
        add(new Brand("Hanwha Vision (Samsung Wisenet)", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/profile2/media.smp"),
                new Stream("主码流（H.265 型号）", "rtsp://{user}:{pass}@{host}:{port}/profile3/media.smp")));
        add(new Brand("Heden", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/videostream.asf")));
        add(new Brand("HeimVision", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264_stream"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/MainStream")));
        add(new Brand("Herospeed", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/onvif-h264")));
        add(new Brand("Hikvision", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}01"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}02"),
                new Stream("第三码流", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}03"),
                new Stream("旧版固件", "rtsp://{user}:{pass}@{host}:{port}/h264/ch{ch}/main/av_stream")));
        add(new Brand("HiLook", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264_stream"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}01")));
        add(new Brand("Hiseeu", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/cam1/mpeg4"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/1")));
        add(new Brand("HiWatch", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}01"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/h264_stream")));
        add(new Brand("Honeywell", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/cam/realmonitor"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/cam/realmonitor?channel={ch}&subtype=01")));
        add(new Brand("HooToo", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/1")));
        add(new Brand("Hosafe", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live0.264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live1.264")));
        add(new Brand("Hunt", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/video1+audio1")));
        add(new Brand("iCamera", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/img/media.sav"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/img/video.sav"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mpeg4")));
        add(new Brand("iCSee", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/onvif1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/ch00_0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("ieGeek", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Imou", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1")));
        add(new Brand("INSTAR", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/MediaInput/mpeg4")));
        add(new Brand("Insteon", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/videoMain"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/videoSub")));
        add(new Brand("Intelbras", 554, null,
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/cam/realmonitor?channel={ch}&subtype=0"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=1.sdp")));
        add(new Brand("IPCC", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mpeg4")));
        add(new Brand("IQeye", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/rtsp/onvif"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/now.mp4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mp4")));
        add(new Brand("Jaycar", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live3.sdp")));
        add(new Brand("Jennov", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Jidetech", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam1/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam2/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Jooan", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=1.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/")));
        add(new Brand("Jovision", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live1.264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}")));
        add(new Brand("JVC", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/livestream"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/PSIA/Streaming/channels/1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/PSIA/Streaming/channels/0?videoCodecType=H.264")));
        add(new Brand("Kaikong", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12")));
        add(new Brand("KKmoon", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/1")));
        add(new Brand("LaView", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/Streaming/channels/101"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1")));
        add(new Brand("LevelOne", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/channel1")));
        add(new Brand("LG", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Master-0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/video1+audio1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/Slave-0")));
        add(new Brand("Linksys", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/img/video.sav"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/img/media.sav")));
        add(new Brand("Loftek", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live.sdp")));
        add(new Brand("Longse", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/"),
                new Stream("备用路径", "rtsp://{host}:{port}/cam1/onvif-h264")));
        add(new Brand("Loosafe", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/11"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=1.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp")));
        add(new Brand("Lorex", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1")));
        add(new Brand("LTS", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/Streaming/Channels/{ch}"),
                new Stream("备用路径", "rtsp://{host}:{port}/HighResolutionVideo"),
                new Stream("备用路径", "rtsp://{host}:{port}/cam1/mpeg4?user={user}&pwd={pass}")));
        add(new Brand("LUPUS", 554, null,
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=0&unicast=true&proto=Onvif"),
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/v1")));
        add(new Brand("Merit LILIN", 80, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/rtsph2641080p"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/rtsph264480p")));
        add(new Brand("Messoa", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/mpeg4")));
        add(new Brand("Microseven", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12")));
        add(new Brand("Milesight", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/main"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/sub")));
        add(new Brand("Mobotix", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/mobotix.h264")));
        add(new Brand("Momentum", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/video.h264"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/H264/sub")));
        add(new Brand("Motorola", 6667, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/blinkhd")));
        add(new Brand("Neo Coolcam", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/0/av1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/h264")));
        add(new Brand("Netcam", 10554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_1")));
        add(new Brand("Night Owl", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/channels/301")));
        add(new Brand("NOVIcam", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/0"),
                new Stream("备用路径", "rtsp://{host}:{port}/Streaming/Channels/{ch}01")));
        add(new Brand("NVT", 554, null,
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=1.sdp"),
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("备用路径", "rtsp://{host}:{port}/")));
        add(new Brand("Optimus", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}&password={pass}&channel={ch}&stream=0.sdp?")));
        add(new Brand("Panasonic i-PRO", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/MediaInput/h264"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/MediaInput/h264/stream_2"),
                new Stream("主码流（H.265 型号）", "rtsp://{user}:{pass}@{host}:{port}/MediaInput/h265")));
        add(new Brand("Pelco", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/stream1")));
        add(new Brand("PLANET", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ipcam.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/jpeg"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/stream1")));
        add(new Brand("Planex", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Polyvision", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/live/sub"),
                new Stream("备用路径", "rtsp://{host}:{port}/live/main")));
        add(new Brand("Provision-ISR", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/profile1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_0")));
        add(new Brand("Q-See", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor")));
        add(new Brand("Raysharp", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ch1/0")));
        add(new Brand("Reolink", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/h264Preview_01_main"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/h264Preview_01_sub"),
                new Stream("主码流（H.265 型号）", "rtsp://{user}:{pass}@{host}:{port}/h265Preview_01_main")));
        add(new Brand("Revotech", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live1.264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/stream1")));
        add(new Brand("RVi", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/RVi/1/1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/VideoInput/1/h264/1")));
        add(new Brand("SafeHome", 7070, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/ch0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/h264")));
        add(new Brand("SANNCE", 8554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Unicast/channels/101")));
        add(new Brand("Sinocam", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ucast/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Sony", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/media/video1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/media/video2")));
        add(new Brand("Speco", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/")));
        add(new Brand("Sricam", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/onvif1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ucast/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/onvif2")));
        add(new Brand("SriCCTV", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/onvif1")));
        add(new Brand("Srihome", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/cam1/mpeg4"),
                new Stream("备用路径", "rtsp://{host}:{port}/1/h264major"),
                new Stream("备用路径", "rtsp://{host}:{port}/cam1/mpeg4?user={user}&pwd={pass}")));
        add(new Brand("SunEyes", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/H264")));
        add(new Brand("Supra Space", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/Streaming/Channels/{ch}")));
        add(new Brand("Swann", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ch01/0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/ch01/1")));
        add(new Brand("SZ Sinocam", 5544, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ucast/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ucast/12")));
        add(new Brand("Techage", 554, null,
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=0.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}_password={pass}_channel={ch}_stream=1.sdp")));
        add(new Brand("TechView", 8001, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/12"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Tenda", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/profile0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/ch=1?subtype=0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Tiandy", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live3.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/h264_stream"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/11")));
        add(new Brand("Toshiba", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live3.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live2.sdp")));
        add(new Brand("TP-Link Tapo", 554, "在 App 中设置",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/stream1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/stream2")));
        add(new Brand("TP-Link VIGI", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/stream1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/stream2")));
        add(new Brand("TRASSIR", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/main")));
        add(new Brand("Trivision", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live_mpeg4.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/0/onvif.sdp")));
        add(new Brand("Ubiquiti (airCam / UniFi Video)", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/s0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/s1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/s2")));
        add(new Brand("Ubiquiti UniFi Protect", 7447, "在 App 中设置",
                new Stream("主码流（高清）", "rtsp://{host}:{port}/TOKEN")));
        add(new Brand("Uniarch", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/mpeg4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/videoMain")));
        add(new Brand("Uniview (UNV)", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/unicast/c1/s0/live"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/unicast/c1/s1/live"),
                new Stream("旧版固件", "rtsp://{user}:{pass}@{host}:{port}/media/video1")));
        add(new Brand("Victure", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/ch00_0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/cam/realmonitor?channel={ch}&subtype=1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/h264")));
        add(new Brand("Vivotek", 554, "root",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live1s1.sdp"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/live1s2.sdp"),
                new Stream("旧版固件", "rtsp://{user}:{pass}@{host}:{port}/live.sdp")));
        add(new Brand("Vstarcam", 10554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_0"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_1")));
        add(new Brand("Wansview", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/ch0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/ch1"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/ch2")));
        add(new Brand("Xiaomi", 8554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/unicast"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live/ch00_0")));
        add(new Brand("XMEye / Xiongmai", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{host}:{port}/user={user}&password={pass}&channel={ch}&stream=0.sdp?real_stream"),
                new Stream("子码流（低分辨率）", "rtsp://{host}:{port}/user={user}&password={pass}&channel={ch}&stream=1.sdp?real_stream")));
        add(new Brand("Xvision", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ch01.264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/mpeg4/media.amp?resolution=640x480"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/cam1/onvif-h264-1")));
        add(new Brand("Y-cam", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/live/0/onvif.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/live_mpeg4.sdp"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/v01")));
        add(new Brand("YI", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/ch0_0.h264"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/ch0_1.h264"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ch0.h264")));
        add(new Brand("Yoosee / generic ONVIF", 554, "admin",
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/onvif1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/onvif2")));
        add(new Brand("Zavio", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/video.mp4"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/video.pro2"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/video.pro1")));
        add(new Brand("Zmodo", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_0"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/VideoInput/1/h264/1"),
                new Stream("子码流（低分辨率）", "rtsp://{user}:{pass}@{host}:{port}/tcp/av0_1")));
        add(new Brand("ZOSI", 554, null,
                new Stream("主码流（高清）", "rtsp://{user}:{pass}@{host}:{port}/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/ucast/11"),
                new Stream("备用路径", "rtsp://{user}:{pass}@{host}:{port}/video1")));
    }

    private static void add(Brand brand) {
        BRANDS.add(brand);
    }

    /** 全部品牌（只读） */
    public static List<Brand> brands() {
        return Collections.unmodifiableList(BRANDS);
    }

    /** 按名称精确查找 */
    public static Brand find(String name) {
        if (name == null) return null;
        for (Brand b : BRANDS) {
            if (name.equals(b.name)) return b;
        }
        return null;
    }

    /** 名称模糊匹配，供搜索用 */
    public static List<Brand> search(String keyword) {
        List<Brand> result = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            result.addAll(BRANDS);
            return result;
        }
        String key = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        for (Brand b : BRANDS) {
            if (b.name.toLowerCase(java.util.Locale.ROOT).contains(key)) {
                result.add(b);
            }
        }
        return result;
    }
}
