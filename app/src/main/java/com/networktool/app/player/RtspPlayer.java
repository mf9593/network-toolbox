package com.networktool.app.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.networktool.app.util.RuntimeLogStore;

/**
 * ExoPlayer 播放适配层。
 *
 * 使用 Google ExoPlayer 2.19.1 作为播放内核。
 *
 * 选择依据：jdtech libmpv 0.4.1 的视频输出层在 Android 上有根本性缺陷
 * （VIDEO_RECONFIG 触发但无法渲染到 Surface），已尝试 5 种配置均无法出画面。
 * ExoPlayer 是 Google 官方维护的播放器，内置 HTTP/MPEG-TS 提取器和 MediaCodec 解码器，
 * 无需 FFmpeg，Apache 2.0 许可，与项目方向一致。
 *
 * 保留原有事件号与对外接口，RTSP 预览、ONVIF、SADP、电视直播页无需改动。
 */
public class RtspPlayer {

    public static final int RTSP_TRANSPORT_TCP = 1;
    public static final int RTSP_TRANSPORT_UDP = 2;
    public static final int RTSP_TRANSPORT_UDP_MULTICAST = 3;
    public static final int RTSP_TRANSPORT_HTTP = 4;

    public static final int EVENT_CONNECTING = 1001;
    public static final int EVENT_CONNECTED = 1101;
    public static final int EVENT_PLAYING = 1104;
    public static final int EVENT_STOPPED = 1004;
    public static final int EVENT_ERROR = 1000;
    public static final int EVENT_ENDED = 1102;
    /** 播放失败后的 RTSP 预检结果（例如 401 需要账号密码） */
    public static final int EVENT_DIAGNOSTIC = 1200;

    public interface Delegate {
        void onEventCallback(RtspPlayer player, int event, String message);
    }

    private static final String TAG = "RtspPlayer";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RtspPlayerView playerView;
    private Delegate delegate;
    private SurfaceHolder attachedHolder;
    private String inputUrl;
    private int transport = RTSP_TRANSPORT_TCP;
    /** 当前是否强制 RTP over TCP（失败后会自动翻转重试一次） */
    private boolean forceTcpTransport = true;
    /** 本次播放是否已经自动切换过传输 */
    private boolean transportRetried;
    /** 本地 RTSP 兼容代理（仅在摄像头报文不合规时启用） */
    private RtspCompatProxy compatProxy;
    /** 本次播放是否已经启用过兼容代理 */
    private boolean proxyRetried;
    /** 兼容代理生效时的实际播放地址（inputUrl 始终保留原始地址） */
    private String playUrlOverride;
    private String hwdec = "mediacodec";
    private int volume = 100;
    /** 音量增强倍数（1.0 = 不增强） */
    private float audioGain = 1f;
    private GainAudioProcessor gainProcessor;
    // 默认使用 TextureView：SurfaceView 是独立窗口，在部分设备/布局下会被
    // Activity 窗口的不透明背景遮挡，导致「解码成功但无画面」。
    private RtspPlayerView.RenderType renderType = RtspPlayerView.RenderType.TEXTUREVIEW;

    private String currentUrl;
    private String currentHwdec;
    private boolean prepared;
    private boolean paused;
    private boolean released;
    private boolean pendingStart;
    private Runnable renderTimeoutRunnable;

    /* ExoPlayer 实例 */
    private ExoPlayer exoPlayer;

    /* 网络带宽估算（用于界面显示实时码率） */
    private com.google.android.exoplayer2.upstream.BandwidthMeter bandwidthMeter;

    /* 最近一次视频尺寸/帧率（用于画面比例与信息浮层） */
    private int lastVideoWidth;
    private int lastVideoHeight;
    private float lastFrameRate;

    /* ExoPlayer 监听器 */
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (released) return;
            writeBreadcrumb("ExoPlayer 状态: " + stateName(playbackState));

            if (playbackState == Player.STATE_BUFFERING) {
                cancelRenderTimeout();
                notifyEvent(EVENT_CONNECTING, "正在连接...");
            } else if (playbackState == Player.STATE_READY) {
                cancelRenderTimeout();
                // 诊断：检查播放器状态
                try {
                    boolean playing = exoPlayer.isPlaying();
                    float vol = exoPlayer.getVolume();
                    writeBreadcrumb("诊断: isPlaying=" + playing + " volume=" + vol);
                } catch (Throwable t) {
                    writeBreadcrumb("诊断失败: " + t.getMessage());
                }
                notifyEvent(EVENT_CONNECTED, "已连接");
                notifyEvent(EVENT_PLAYING, "正在播放");
                prepared = true;
                paused = false;
            } else if (playbackState == Player.STATE_ENDED) {
                prepared = false;
                notifyEvent(EVENT_STOPPED, "播放结束");
                notifyEvent(EVENT_ENDED, "播放结束");
            }
            // STATE_IDLE 不通知（初始状态或已停止）
        }

        @Override
        public void onRenderedFirstFrame() {
            if (released) return;
            writeBreadcrumb("首帧已渲染（画面确认输出）"
                    + (compatProxy != null
                    ? "，兼容代理已改写 " + compatProxy.getRewrittenHeaders() + " 处响应头" : ""));
        }

        @Override
        public void onVideoSizeChanged(@NonNull com.google.android.exoplayer2.video.VideoSize size) {
            if (released) return;
            lastVideoWidth = size.width;
            lastVideoHeight = size.height;
            writeBreadcrumb("视频尺寸: " + size.width + "x" + size.height);
            if (playerView != null) {
                playerView.setVideoSize(size.width, size.height);
            }
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (released) return;
            try {
                int groupCount = tracks.getGroups().size();
                writeBreadcrumb("轨道变化: 共 " + groupCount + " 组");
                for (Tracks.Group group : tracks.getGroups()) {
                    for (int i = 0; i < group.length; i++) {
                        Format f = group.getTrackFormat(i);
                        writeBreadcrumb("  轨道[" + group.getType() + "] mime=" + f.sampleMimeType
                                + " codec=" + f.codecs
                                + " " + f.width + "x" + f.height
                                + " 支持=" + group.isTrackSupported(i)
                                + " 已选=" + group.isTrackSelected(i));
                    }
                }
            } catch (Throwable t) {
                writeBreadcrumb("轨道诊断失败: " + t.getMessage());
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (released) return;
            String msg = (error != null && error.getMessage() != null)
                    ? error.getMessage() : error.toString();
            if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
            writeBreadcrumb("ExoPlayer 错误: " + msg);
            if (error != null) {
                StringBuilder detail = new StringBuilder("code=").append(error.errorCode);
                Throwable cause = error.getCause();
                int depth = 0;
                while (cause != null && depth++ < 4) {
                    detail.append(" | ").append(cause.getClass().getSimpleName())
                            .append(": ").append(cause.getMessage());
                    cause = cause.getCause();
                }
                writeBreadcrumb("错误详情: " + detail);

                // 形如 IllegalArgumentException: null 的异常来自 ExoPlayer 内部断言，
                // 消息里没有有效信息，只有堆栈能指出具体位置，逐行写入面包屑便于定位
                Throwable deepest = error;
                int guard = 0;
                while (deepest.getCause() != null && deepest.getCause() != deepest && guard++ < 6) {
                    deepest = deepest.getCause();
                }
                writeBreadcrumb("异常类型: " + deepest.getClass().getName()
                        + (deepest.getMessage() == null ? "（无消息）" : ""));
                int frames = 0;
                for (StackTraceElement frame : deepest.getStackTrace()) {
                    String className = frame.getClassName();
                    if (className.startsWith("java.") || className.startsWith("android.")
                            || className.startsWith("com.android.internal")) {
                        continue;
                    }
                    writeBreadcrumb("  栈" + (++frames) + ": " + frame);
                    if (frames >= 14) break;
                }
            }
            cancelRenderTimeout();
            prepared = false;
            // RTSP 常见的 SETUP 传输不支持，先自动切换 RTP 传输重试一次
            if (retryWithAltTransport()) {
                probeRtspCause();
                return;
            }
            // 传输都不通、且是「服务器报文不合规」这类错误时，启用本地兼容代理再试
            if (retryWithCompatProxy()) {
                probeRtspCause();
                return;
            }
            notifyEvent(EVENT_ERROR, "播放失败：" + msg);
            probeRtspCause();
        }
    };

    public RtspPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    /* ==================== Surface 生命周期 ==================== */

    public void setPlayerView(RtspPlayerView view) {
        this.playerView = view;
        if (view != null) {
            view.setSurfaceCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    attachedHolder = holder;
                    // 如果实例已创建，立即附加
                    if (exoPlayer != null) {
                        try {
                            exoPlayer.setVideoSurfaceHolder(holder);
                            writeBreadcrumb("Surface 已附加（运行中）");
                        } catch (Throwable t) {
                            writeBreadcrumb("Surface 附加失败: " + t.getMessage());
                        }
                    }
                    if (pendingStart) {
                        pendingStart = false;
                        startInternal();
                    }
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format,
                        int width, int height) {
                    // ExoPlayer 自动适配 Surface 尺寸
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    if (attachedHolder == holder) {
                        attachedHolder = null;
                    }
                    if (exoPlayer != null) {
                        try {
                            exoPlayer.setVideoSurfaceHolder(null);
                            writeBreadcrumb("Surface 已分离");
                        } catch (Throwable t) {
                            writeBreadcrumb("Surface 分离失败: " + t.getMessage());
                        }
                    }
                }
            });
        }
    }

    /* ==================== 配置接口 ==================== */

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** 播放器是否已就绪（能出画面），用于自动换源判断 */
    public boolean isReady() {
        try {
            return exoPlayer != null
                    && exoPlayer.getPlaybackState() == com.google.android.exoplayer2.Player.STATE_READY;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void setInputUrl(String url) {
        this.inputUrl = url;
        this.prepared = false;
        this.pendingStart = false;
        this.paused = false;
    }

    public void setRtspTransport(int transport) {
        this.transport = transport;
        this.forceTcpTransport = transport == RTSP_TRANSPORT_TCP;
        this.transportRetried = false;
    }

    /**
     * 切换渲染类型：SurfaceView 或 TextureView。
     * 需在下次 start 前设置才对当前流生效。
     */
    public void setRenderType(RtspPlayerView.RenderType type) {
        this.renderType = type;
    }

    public RtspPlayerView.RenderType getRenderType() {
        return renderType;
    }

    /**
     * 切换解码方式。
     *
     * ExoPlayer 的硬解由 MediaCodec 控制：
     * - mediacodec  硬件解码（默认）
     * - no          软件解码
     *
     * 需在下次 start 前设置才对当前流生效。
     */
    public void setHwdec(String hwdec) {
        this.hwdec = (hwdec == null || hwdec.isEmpty()) ? "no" : hwdec;
    }

    public String getHwdec() {
        return hwdec;
    }

    public void setVideoEnable(boolean enabled) {}
    public void setBufferTime(int ignored) {}
    public void setMaxBufferTime(int ignored) {}

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
        if (exoPlayer != null) {
            try {
                exoPlayer.setVolume(volume / 100f);
            } catch (Throwable ignored) {}
        }
    }

    public int getVolume() {
        return volume;
    }

    /**
     * 设置音量增强倍数（1.0 = 原样，2.0 = 放大一倍）。
     * 用于直播源本身音量偏小的情况，可在播放中实时生效。
     */
    public void setAudioGain(float gain) {
        this.audioGain = Math.max(1f, Math.min(4f, gain));
        GainAudioProcessor processor = gainProcessor;
        if (processor != null) {
            processor.setGain(this.audioGain);
        }
    }

    public float getAudioGain() {
        return audioGain;
    }

    /* ==================== 画面信息（UI 信息浮层使用） ==================== */

    /** 视频宽度（像素），未知返回 0 */
    public int getVideoWidth() {
        if (exoPlayer != null) {
            try {
                Format format = exoPlayer.getVideoFormat();
                if (format != null && format.width > 0) return format.width;
            } catch (Throwable ignored) {}
        }
        return lastVideoWidth;
    }

    /** 视频高度（像素），未知返回 0 */
    public int getVideoHeight() {
        if (exoPlayer != null) {
            try {
                Format format = exoPlayer.getVideoFormat();
                if (format != null && format.height > 0) return format.height;
            } catch (Throwable ignored) {}
        }
        return lastVideoHeight;
    }

    /** 视频帧率，未知返回 0 */
    public float getVideoFrameRate() {
        if (exoPlayer != null) {
            try {
                Format format = exoPlayer.getVideoFormat();
                if (format != null && format.frameRate > 0) {
                    lastFrameRate = format.frameRate;
                    return lastFrameRate;
                }
            } catch (Throwable ignored) {}
        }
        return lastFrameRate;
    }

    /** 当前估算码率（bit/s），未知返回 -1 */
    public long getBitrateEstimate() {
        if (bandwidthMeter != null) {
            long estimate = bandwidthMeter.getBitrateEstimate();
            if (estimate > 0) return estimate;
        }
        if (exoPlayer != null) {
            try {
                Format format = exoPlayer.getVideoFormat();
                if (format != null && format.bitrate > 0) return format.bitrate;
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private com.google.android.exoplayer2.upstream.BandwidthMeter getBandwidthMeter() {
        if (bandwidthMeter == null) {
            bandwidthMeter = new com.google.android.exoplayer2.upstream.DefaultBandwidthMeter.Builder(context)
                    .build();
        }
        return bandwidthMeter;
    }

    /* ==================== 播放控制 ==================== */

    /** 判断是否是 HLS 地址（看扩展名，兼顾不带扩展名的直播源） */
    private boolean isHlsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("format=m3u8");
    }

    public void start() {
        if (released) return;
        writeBreadcrumb("start() 入口");
        if (inputUrl == null || inputUrl.isEmpty()) {
            notifyEvent(EVENT_ERROR, "播放地址为空");
            return;
        }
        // 换地址时重置「传输重试」标记
        if (currentUrl == null || !inputUrl.equals(currentUrl)) {
            transportRetried = false;
            proxyRetried = false;
            playUrlOverride = null;
            closeCompatProxy();
        }
        // 已在播放同一地址则跳过
        if (prepared && !paused && inputUrl.equals(currentUrl)) return;
        // 暂停中恢复
        if (prepared && paused && inputUrl.equals(currentUrl)) {
            try {
                exoPlayer.play();
                paused = false;
                writeBreadcrumb("暂停恢复");
                notifyEvent(EVENT_PLAYING, "正在播放");
            } catch (Throwable t) {
                fail(t);
            }
            return;
        }
        pendingStart = true;
        if (attachedHolder == null) {
            notifyEvent(EVENT_CONNECTING, "正在等待画面...");
            return;
        }
        pendingStart = false;
        startInternal();
    }

    /** 空值安全的字符串比较 */
    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void startInternal() {
        if (inputUrl == null || inputUrl.isEmpty()) return;
        writeBreadcrumb("startInternal: " + safeUrl(inputUrl));

        // 检查是否需要重建实例（hwdec 变化或首次启动）
        boolean needsRecreate = (exoPlayer == null) || !safeEquals(currentHwdec, hwdec);

        try {
            if (needsRecreate) {
                // 释放旧实例
                if (exoPlayer != null) {
                    writeBreadcrumb("释放旧实例");
                    releaseExoPlayer();
                    writeBreadcrumb("旧实例已释放");
                } else {
                    writeBreadcrumb("无旧实例，首次创建");
                }
                createExoPlayer();
                writeBreadcrumb("实例已创建并初始化");
            }

            // 切换渲染类型
            if (playerView != null && playerView.getRenderType() != renderType) {
                playerView.setRenderType(renderType);
                writeBreadcrumb("渲染类型切换为: " + renderType);
            }

            // 从视图重新获取最新 Surface（切换后可能已变化）
            if (playerView != null) {
                SurfaceHolder latest = playerView.getCurrentHolder();
                if (latest != null) attachedHolder = latest;
            }

            // 附加 Surface（必须在 prepare 之前；若暂不可用，后续 surfaceCreated 回调会补上）
            if (attachedHolder != null) {
                try {
                    exoPlayer.setVideoSurfaceHolder(attachedHolder);
                    writeBreadcrumb("Surface 已附加");
                } catch (Throwable t) {
                    writeBreadcrumb("Surface 附加失败: " + t.getMessage());
                }
            } else {
                writeBreadcrumb("警告：Surface 暂不可用，等待回调");
            }

            // 设置音量
            try {
                exoPlayer.setVolume(volume / 100f);
            } catch (Throwable ignored) {}

            // 加载流：按协议分流（RTSP 需专用 MediaSource，渐进源不支持 rtsp:// 协议）
            // 先整理地址：把 ?username=&password= 搬到 userInfo，否则服务器会返回 401
            String playUrl = playUrlOverride != null
                    ? playUrlOverride
                    : com.networktool.app.util.RtspDiagnostics.normalizeUrl(inputUrl);
            if (playUrlOverride == null && playUrl != null && !playUrl.equals(inputUrl)) {
                writeBreadcrumb("RTSP 地址已整理（账号密码移入 URL 鉴权位）");
            }
            String lowerUrl = playUrl == null ? "" : playUrl.toLowerCase(java.util.Locale.ROOT);
            if (playUrlOverride == null && lowerUrl.startsWith("rtsp")) {
                // 只输出账号名与密码长度，便于确认凭据是否真的解析生效
                writeBreadcrumb("RTSP 凭据: "
                        + com.networktool.app.util.RtspDiagnostics.credentialSummary(inputUrl));
            }
            Uri uri = Uri.parse(playUrl);
            MediaItem mediaItem = MediaItem.fromUri(uri);
            String scheme = uri.getScheme();
            if ("rtsp".equalsIgnoreCase(scheme) || "rtsps".equalsIgnoreCase(scheme)) {
                // 相机预览默认走 TCP 内联传输（UDP 常被 NAT/防火墙拦截）
                RtspMediaSource rtspSource = new RtspMediaSource.Factory()
                        .setForceUseRtpTcp(forceTcpTransport)
                        .setUserAgent("NetworkToolbox/2.1.0")
                        // 默认 8s 对慢摄像头偏紧，放宽到 12s（页面兜底 15s）
                        .setTimeoutMs(12000)
                        .createMediaSource(mediaItem);
                exoPlayer.setMediaSource(rtspSource);
                writeBreadcrumb("RTSP 源已创建（RTP " + (forceTcpTransport ? "TCP 内联" : "UDP") + "）");
            } else if ("rtmp".equalsIgnoreCase(scheme) || "rtmps".equalsIgnoreCase(scheme)) {
                // RTMP 必须用专用 DataSource，默认的 DefaultDataSource 不支持 rtmp 协议
                MediaSource rtmpSource = new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                        new com.google.android.exoplayer2.ext.rtmp.RtmpDataSource.Factory())
                        .createMediaSource(mediaItem);
                exoPlayer.setMediaSource(rtmpSource);
                writeBreadcrumb("RTMP 源已创建");
            } else if (isHlsUrl(playUrl)) {
                // HLS（.m3u8）需要显式创建 HlsMediaSource：DefaultMediaSourceFactory
                // 在没有 HLS 模块或扩展名不标准时会报 No suitable media source factory
                MediaSource hlsSource = new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(
                        new com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(context))
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem);
                exoPlayer.setMediaSource(hlsSource);
                writeBreadcrumb("HLS 源已创建");
            } else {
                try {
                    exoPlayer.setMediaItem(mediaItem);
                    writeBreadcrumb("渐进源已设置");
                } catch (Throwable t) {
                    // 兜底：扩展名不规范但其实是 HLS 的地址
                    writeBreadcrumb("渐进源失败，按 HLS 重试: " + t.getMessage());
                    MediaSource hlsSource = new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(
                            new com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(context))
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(mediaItem);
                    exoPlayer.setMediaSource(hlsSource);
                }
            }
            exoPlayer.prepare();
            exoPlayer.play();

            currentUrl = inputUrl;
            currentHwdec = hwdec;
            prepared = false;
            paused = false;
            writeBreadcrumb("prepare+play 已发送，等待回调");

            notifyEvent(EVENT_CONNECTING, "正在连接...");
            startRenderTimeout();

        } catch (Throwable t) {
            writeBreadcrumb("startInternal 异常: "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            prepared = false;
            fail(t);
        }
    }

    /* ==================== ExoPlayer 实例管理 ==================== */

    /**
     * 创建并初始化 ExoPlayer 实例。
     */
    private void createExoPlayer() {
        writeBreadcrumb("ExoPlayer.Builder");

        // 音量增强处理器（直播源本身音量偏小时使用）
        final GainAudioProcessor gainProcessor = new GainAudioProcessor();
        gainProcessor.setGain(audioGain);
        this.gainProcessor = gainProcessor;

        // 配置渲染器工厂
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected com.google.android.exoplayer2.audio.AudioSink buildAudioSink(
                    android.content.Context ctx, boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams, boolean enableOffload) {
                return new com.google.android.exoplayer2.audio.DefaultAudioSink.Builder(ctx)
                        .setAudioProcessors(new com.google.android.exoplayer2.audio.AudioProcessor[]{
                                gainProcessor})
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        // 关闭 offload：offload 会绕过软件处理链，音量增强将失效
                        .setOffloadMode(com.google.android.exoplayer2.audio.DefaultAudioSink
                                .OFFLOAD_MODE_DISABLED)
                        .build();
            }

            @Override
            protected void buildAudioRenderers(android.content.Context ctx, int enableDecoderFallback,
                    com.google.android.exoplayer2.mediacodec.MediaCodecSelector mediaCodecSelector,
                    boolean enableAudioTrackPlaybackConfig,
                    com.google.android.exoplayer2.audio.AudioSink audioSink,
                    android.os.Handler audioEventHandler,
                    com.google.android.exoplayer2.audio.AudioRendererEventListener audioEventListener,
                    java.util.ArrayList<com.google.android.exoplayer2.Renderer> rendererList) {
                super.buildAudioRenderers(ctx, enableDecoderFallback, mediaCodecSelector,
                        enableAudioTrackPlaybackConfig, audioSink, audioEventHandler,
                        audioEventListener, rendererList);
                // MP2（MPEG-1 Layer II）系统无解码器，插入纯 Java 软解渲染器到最前面
                rendererList.add(0, new Mp2AudioRenderer(audioEventHandler, audioEventListener, audioSink));
            }
        };
        // 禁用硬件解码（hwdec="no"）
        boolean useHw = !"no".equals(hwdec);
        if (!useHw) {
            renderersFactory.setEnableDecoderFallback(false);
            writeBreadcrumb("hwdec=no，已禁用硬件解码回退");
        }

        exoPlayer = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setBandwidthMeter(getBandwidthMeter())
                .build();

        exoPlayer.addListener(playerListener);
        writeBreadcrumb("ExoPlayer 已创建并初始化");
    }

    /** 释放 ExoPlayer 实例 */
    private void releaseExoPlayer() {
        if (exoPlayer == null) return;
        writeBreadcrumb("release: removeListener");
        try { exoPlayer.removeListener(playerListener); } catch (Throwable ignored) {}
        writeBreadcrumb("release: release");
        try { exoPlayer.release(); } catch (Throwable ignored) {}
        writeBreadcrumb("release: 完成");
        exoPlayer = null;
    }

    /* ==================== 渲染超时兜底 ==================== */

    /**
     * 渲染兜底超时。若 15 秒内仍未收到 STATE_READY，
     * 提示用户可能是地址不通或格式未识别。
     */
    private void startRenderTimeout() {
        cancelRenderTimeout();
        renderTimeoutRunnable = () -> {
            if (released || paused) return;
            writeBreadcrumb("等待超时：未收到 STATE_READY 回调");
            notifyEvent(EVENT_ERROR,
                    "连接超时。可能是地址不通、网络受限，或格式未识别。"
                            + "可尝试切换传输方式(TCP/UDP)，或更换直播源地址");
        };
        mainHandler.postDelayed(renderTimeoutRunnable, 15000);
    }

    private void cancelRenderTimeout() {
        if (renderTimeoutRunnable != null) {
            mainHandler.removeCallbacks(renderTimeoutRunnable);
            renderTimeoutRunnable = null;
        }
    }

    /* ==================== 其他控制方法 ==================== */

    public void pause() {
        if (exoPlayer == null) return;
        try {
            if (prepared && !paused) {
                exoPlayer.pause();
                paused = true;
                writeBreadcrumb("已暂停");
            }
        } catch (Throwable t) {
            writeBreadcrumb("暂停失败: " + t.getMessage());
        }
    }

    public void stop() {
        if (exoPlayer == null) return;
        try {
            exoPlayer.stop();
            writeBreadcrumb("已停止");
        } catch (Throwable t) {
            writeBreadcrumb("停止失败: " + t.getMessage());
        }
        prepared = false;
        pendingStart = false;
        paused = false;
    }

    public void release() {
        if (released) return;
        released = true;
        cancelRenderTimeout();
        closeCompatProxy();
        if (exoPlayer != null) {
            releaseExoPlayer();
        }
        attachedHolder = null;
        playerView = null;
        delegate = null;
        prepared = false;
        pendingStart = false;
    }

    /* ==================== 工具方法 ==================== */

    /** 将 ExoPlayer 状态码映射为可读名称 */
    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /** 对外暴露的日志入口（供内部渲染器使用） */
    public static void log(String step) {
        writeBreadcrumb(step);
    }

    /** 写入播放操作面包屑（持久化），用于崩溃回溯 */
    private static void writeBreadcrumb(String step) {
        try {
            android.content.Context appCtx =
                    com.networktool.app.NetworkToolApplication.getInstance();
            if (appCtx == null) return;
            java.io.File dir = new java.io.File(appCtx.getFilesDir(), "crashes");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "ijk_breadcrumb.log");
            String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write("[" + ts + "] " + step + "\n");
            }
            RuntimeLogStore.record("播放器", "", step, "");
        } catch (Throwable ignored) {}
    }

    /** 脱敏 URL：去掉可能的密码 */
    private static String safeUrl(String url) {
        if (url == null) return "";
        String s = url.replaceAll("://[^@/]+@", "://***@");
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private void fail(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) message = t.getClass().getSimpleName();
        notifyEvent(EVENT_ERROR, "播放失败：" + message);
    }

    /**
     * RTSP 失败后自动切换 RTP 传输（TCP 内联 ⇄ UDP）重试一次。
     * 不少摄像头只支持其中一种，SETUP 阶段会直接回 461 Unsupported Transport。
     *
     * @return true 表示已安排重试
     */
    private boolean retryWithAltTransport() {
        if (transportRetried || released || inputUrl == null) return false;
        String lower = inputUrl.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("rtsp://") && !lower.startsWith("rtsps://")) return false;
        if (exoPlayer == null) return false;
        transportRetried = true;
        forceTcpTransport = !forceTcpTransport;
        writeBreadcrumb("RTP 传输切换为 " + (forceTcpTransport ? "TCP(内联)" : "UDP") + "，重试播放");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!released) startInternal();
            } catch (Throwable ignored) {
            }
        }, 500);
        return true;
    }

    /**
     * 启用本地 RTSP 兼容代理后重试。
     *
     * <p>部分摄像头（LIVE555 方案）在 OPTIONS 响应的 {@code Public:} 头里写了播放器不认识的
     * 方法名，ExoPlayer 解析时直接抛无消息的 {@code IllegalArgumentException}；
     * 其 {@code Content-Base:} 还可能是 {@code rtsp://rtsp://ip:554/stream0/} 这种重复协议头，
     * 导致 SETUP 的 track 地址拼错。代理只改这两处，其余字节（含内联 RTP）原样透传。</p>
     *
     * @return true 表示已安排重试
     */
    private boolean retryWithCompatProxy() {
        if (proxyRetried || released || inputUrl == null) return false;
        String lower = inputUrl.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("rtsp://") && !lower.startsWith("rtsps://")) return false;
        if (exoPlayer == null) return false;
        proxyRetried = true;
        writeBreadcrumb("启用本地 RTSP 兼容代理（改写不合规的 Public / Content-Base 头）");

        final String normalized = com.networktool.app.util.RtspDiagnostics.normalizeUrl(inputUrl);
        new Thread(() -> {
            RtspCompatProxy proxy = null;
            try {
                java.net.URI uri = new java.net.URI(normalized);
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 554;
                if (host == null) {
                    writeBreadcrumb("兼容代理跳过：地址无法解析");
                    return;
                }
                closeCompatProxy();
                proxy = RtspCompatProxy.start(host, port);
                final String proxiedUrl = RtspCompatProxy.toProxyUrl(normalized, proxy.getLocalPort());
                compatProxy = proxy;
                writeBreadcrumb("兼容代理就绪：127.0.0.1:" + proxy.getLocalPort()
                        + " → " + host + ":" + port);
                mainHandler.postDelayed(() -> {
                    if (released || exoPlayer == null || proxiedUrl == null) return;
                    // 代理链路固定走 TCP 内联：RTP 与 RTSP 走同一条连接才能原样透传
                    forceTcpTransport = true;
                    playUrlOverride = proxiedUrl;
                    try {
                        startInternal();
                    } catch (Throwable ignored) {
                    }
                }, 300);
            } catch (Throwable t) {
                writeBreadcrumb("兼容代理启动失败: " + t.getClass().getSimpleName());
                if (proxy != null) {
                    try {
                        proxy.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "rtsp-compat-proxy").start();
        return true;
    }

    /** 关闭本地 RTSP 兼容代理 */
    private void closeCompatProxy() {
        if (compatProxy != null) {
            try {
                compatProxy.close();
            } catch (Throwable ignored) {
            }
            compatProxy = null;
        }
    }

    /** 播放失败后发一次 RTSP DESCRIBE 预检，把失败原因写进面包屑（子线程） */
    private void probeRtspCause() {
        final String url = currentUrl;
        if (url == null || released) return;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("rtsp://") && !lower.startsWith("rtsps://")) return;
        new Thread(() -> {
            try {
                String result = com.networktool.app.util.RtspDiagnostics.describe(url, 2500);
                writeBreadcrumb("RTSP 预检: " + result);
                // 顺带把 SDP 关键行写进面包屑：能直接看出编码/传输是否被播放器支持
                String sdp = com.networktool.app.util.RtspDiagnostics.sdpSummary(url, 2500);
                if (sdp != null && !sdp.isEmpty()) {
                    writeBreadcrumb("SDP: " + sdp);
                }
                // 原始报文：确认是否用 CRLF 分行、状态行是否规范
                String raw = com.networktool.app.util.RtspDiagnostics.rawHead(url, 2500);
                if (raw != null && !raw.isEmpty()) {
                    writeBreadcrumb("原始响应: " + raw);
                }
                // 直接把原因反馈给界面，方便用户补账号密码或检查端口
                notifyEvent(EVENT_DIAGNOSTIC, result);
            } catch (Exception ignored) {
            }
        }, "rtsp-probe").start();
    }

    private void notifyEvent(int event, String message) {
        Delegate current = delegate;
        if (current == null) return;
        mainHandler.post(() -> {
            Delegate active = delegate;
            if (active != null) active.onEventCallback(this, event, message);
        });
    }
}
