package com.networktool.app.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * Media3 ExoPlayer RTSP 播放器适配层。
 *
 * 保留原先使用的 start/pause/stop/setInputUrl/setTransportProtocol 接口，
 * 让现有 Onvif、SADP 和 RTSP 预览页面可以平滑切换播放器。
 */
public class RtspPlayer {

    public static final int RTSP_TRANSPORT_TCP = 1;
    public static final int RTSP_TRANSPORT_UDP = 2;
    public static final int RTSP_TRANSPORT_UDP_MULTICAST = 3;
    public static final int RTSP_TRANSPORT_HTTP = 4;

    public interface Delegate {
        void onEventCallback(RtspPlayer player, int event, String message);
    }

    public static final int EVENT_CONNECTING = 1001;
    public static final int EVENT_CONNECTED = 1101;
    public static final int EVENT_PLAYING = 1104;
    public static final int EVENT_STOPPED = 1004;
    public static final int EVENT_ERROR = 1000;
    public static final int EVENT_ENDED = 1102;

    private final Context context;
    private ExoPlayer player;
    private RtspPlayerView playerView;
    private Delegate delegate;
    private String inputUrl;
    private int transport = RTSP_TRANSPORT_TCP;
    private boolean prepared;
    private boolean released;

    public RtspPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setPlayerView(RtspPlayerView view) {
        this.playerView = view;
        if (player != null && view != null) {
            view.setPlayer(player);
        }
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setInputUrl(String url) {
        this.inputUrl = url;
        this.prepared = false;
    }

    public void setRtspTransport(int transport) {
        this.transport = transport;
    }

    public void setVideoEnable(boolean enabled) {
        // Media3 自动处理视频轨道。
    }

    public void setBufferTime(int ignored) {
        // Media3 的 LoadControl 默认缓冲策略足够局域网使用。
    }

    public void setMaxBufferTime(int ignored) {
    }

    public void setVolume(int volume) {
        if (player != null) {
            player.setVolume(Math.max(0, Math.min(100, volume)) / 100f);
        }
    }

    /** 开始或恢复播放。 */
    public void start() {
        if (released) return;
        if (inputUrl == null || inputUrl.isEmpty()) {
            notifyEvent(EVENT_ERROR, "RTSP 地址为空");
            return;
        }
        if (player == null) {
            createPlayer();
        }
        if (player == null) return;

        if (prepared) {
            player.play();
        } else {
            try {
                notifyEvent(EVENT_CONNECTING, "正在连接...");
                boolean forceTcp = transport == RTSP_TRANSPORT_TCP
                        || transport == RTSP_TRANSPORT_HTTP;
                RtspMediaSource.Factory factory = new RtspMediaSource.Factory()
                        .setForceUseRtpTcp(forceTcp);
                player.setMediaSource(factory.createMediaSource(
                        MediaItem.fromUri(Uri.parse(inputUrl))));
                player.prepare();
                player.play();
                prepared = true;
            } catch (Exception e) {
                notifyEvent(EVENT_ERROR, safeMessage(e));
            }
        }
    }

    private void createPlayer() {
        try {
            player = new ExoPlayer.Builder(context).build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    switch (state) {
                        case Player.STATE_BUFFERING:
                            notifyEvent(EVENT_CONNECTING, "正在连接...");
                            break;
                        case Player.STATE_READY:
                            notifyEvent(EVENT_CONNECTED, "已连接");
                            if (player != null && player.isPlaying()) {
                                notifyEvent(EVENT_PLAYING, "正在播放");
                            }
                            break;
                        case Player.STATE_ENDED:
                            notifyEvent(EVENT_ENDED, "播放完成");
                            break;
                        case Player.STATE_IDLE:
                            // stop/release 也会进入 IDLE，交由调用方处理。
                            break;
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        notifyEvent(EVENT_PLAYING, "正在播放");
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    notifyEvent(EVENT_ERROR, safeMessage(error));
                }
            });
            if (playerView != null) {
                playerView.setPlayer(player);
            }
            setVolume(100);
        } catch (Exception e) {
            player = null;
            notifyEvent(EVENT_ERROR, safeMessage(e));
        }
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
        }
        prepared = false;
    }

    public void release() {
        released = true;
        if (playerView != null) {
            try {
                playerView.setPlayer(null);
            } catch (Exception ignored) {
            }
            playerView = null;
        }
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        delegate = null;
        prepared = false;
    }

    private void notifyEvent(int event, String message) {
        Delegate current = delegate;
        if (current != null) {
            current.onEventCallback(this, event, message);
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
