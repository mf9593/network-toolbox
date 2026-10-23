package com.networktool.app.activity.tool;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import cn.nodemedia.NodePlayer;
import cn.nodemedia.NodePlayerDelegate;
import cn.nodemedia.NodePlayerView;

/**
 * RTSP流预览Activity - 支持全屏播放
 *
 * 功能说明：
 * - 支持手动输入RTSP地址进行预览
 * - 支持从Onvif设备直接跳转播放
 * - 使用NodePlayer 2.x播放RTSP视频流
 * - 进入播放后自动横屏全屏
 * - 点击显示/隐藏控制栏
 * - 播放控制（暂停/播放、退出全屏）
 * - TCP传输模式
 */
public class RtspPlayerActivity extends AppCompatActivity {
    private static final String TAG = "RtspPlayerActivity";

    // 输入界面
    private LinearLayout inputLayout;
    private TextInputEditText rtspUrlEditText;
    private MaterialButton playButton;
    private RadioGroup protocolGroup;

    // 播放界面
    private FrameLayout playerContainer;
    private NodePlayerView videoView;
    private View topBar;
    private View bottomBar;
    private TextView titleText;
    private ImageView backBtn;
    private ImageView playPauseBtn;
    private ImageView fullscreenBtn;
    private TextView statusText;
    private TextView resolutionText;
    private TextView channelNameText;  // 新增：通道名称显示
    private ProgressBar loadingProgress;

    // NodePlayer
    private NodePlayer nodePlayer;

    // 流地址
    private String streamUrl;
    private String deviceName;
    private String channelName = "通道1";  // 默认通道名称

    // 传输协议（默认自动检测）
    private String transportProtocol = "auto";

    // 播放状态
    private boolean isPlaying = false;
    private boolean isFullscreen = false;
    private boolean isControlsVisible = false;

    // 自动隐藏控制栏
    private Handler hideHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable = this::hideControls;
    private static final int HIDE_DELAY = 5000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置沉浸式状态栏
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0x00000000);
        }

        setContentView(R.layout.activity_rtsp_player);

        // 获取参数
        streamUrl = getIntent().getStringExtra("main_stream_url");
        String subStreamUrl = getIntent().getStringExtra("sub_stream_url");
        deviceName = getIntent().getStringExtra("device_name");
        channelName = getIntent().getStringExtra("channel_name");  // 获取通道名称参数
        if (TextUtils.isEmpty(channelName)) {
            channelName = "通道1";  // 默认通道名称
        }

        // 优先使用主码流，其次子码流
        if (TextUtils.isEmpty(streamUrl) && !TextUtils.isEmpty(subStreamUrl)) {
            streamUrl = subStreamUrl;
        }

        try {
            initViews();

            // 如果有RTSP地址，直接开始播放
            if (!TextUtils.isEmpty(streamUrl)) {
                startPlayback();
            }
        } catch (Exception e) {
            Log.e(TAG, "初始化失败: " + e.getMessage(), e);
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initViews() {
        // 输入界面
        inputLayout = findViewById(R.id.inputLayout);
        rtspUrlEditText = findViewById(R.id.rtspUrlEditText);
        playButton = findViewById(R.id.playButton);
        protocolGroup = findViewById(R.id.protocolGroup);

        // 协议选择监听
        protocolGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.protocolAuto) {
                transportProtocol = "auto";
            } else if (checkedId == R.id.protocolTcp) {
                transportProtocol = "tcp";
            } else if (checkedId == R.id.protocolUdp) {
                transportProtocol = "udp";
            } else if (checkedId == R.id.protocolUdpMulticast) {
                transportProtocol = "udp_multicast";
            }
            Log.d(TAG, "传输协议切换: " + transportProtocol);
        });

        // 播放界面
        playerContainer = findViewById(R.id.playerContainer);
        videoView = findViewById(R.id.videoView);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        titleText = findViewById(R.id.titleText);
        backBtn = findViewById(R.id.backBtn);
        playPauseBtn = findViewById(R.id.playPauseBtn);
        fullscreenBtn = findViewById(R.id.fullscreenBtn);
        statusText = findViewById(R.id.statusText);
        resolutionText = findViewById(R.id.resolutionText);
        channelNameText = findViewById(R.id.channelNameText);  // 新增：通道名称显示
        loadingProgress = findViewById(R.id.loadingProgress);

        // 设置设备名称
        if (!TextUtils.isEmpty(deviceName)) {
            titleText.setText(deviceName);
        } else {
            titleText.setText("RTSP预览");
        }
        
        // 设置默认通道名称
        if (channelNameText != null) {
            channelNameText.setText(channelName);
        }

        // 播放按钮点击事件
        playButton.setOnClickListener(v -> {
            String url = rtspUrlEditText.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                Toast.makeText(this, "请输入RTSP地址", Toast.LENGTH_SHORT).show();
                return;
            }
            streamUrl = url;
            deviceName = extractDeviceName(url);
            titleText.setText(deviceName);
            // 重置通道名称为默认值
            setChannelName("通道1");
            startPlayback();
        });

        // 返回按钮
        backBtn.setOnClickListener(v -> {
            if (isFullscreen) {
                // 横屏状态直接退出，不返回竖屏
                onBackPressed();
            } else {
                // 竖屏状态直接退出
                onBackPressed();
            }
        });

        // 播放/暂停按钮
        playPauseBtn.setOnClickListener(v -> togglePlayPause());

        // 全屏按钮
        fullscreenBtn.setOnClickListener(v -> toggleFullscreen());

        // 点击视频区域切换控制栏显示
        if (videoView != null) {
            videoView.setOnClickListener(v -> toggleControls());
        }

        // 应用主题色到按钮
        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(playButton);
    }

    /**
     * 开始播放
     */
    private void startPlayback() {
        // 隐藏输入界面，显示播放界面
        if (inputLayout != null) {
            inputLayout.setVisibility(View.GONE);
        }
        if (playerContainer != null) {
            playerContainer.setVisibility(View.VISIBLE);
        }

        // 显示加载提示
        if (loadingProgress != null) {
            loadingProgress.setVisibility(View.VISIBLE);
        }
        if (statusText != null) {
            statusText.setText("正在连接...");
        }

        // 初始化播放器
        initPlayer();

        // 延迟进入全屏（等待播放成功后进入）
        hideHandler.postDelayed(() -> {
            if (!isFullscreen && isPlaying) {
                Log.d(TAG, "自动进入横屏全屏");
                enterFullscreen();
            }
        }, 1500); // 增加到1.5秒，确保播放成功
    }

    /**
     * 初始化播放器
     */
    private void initPlayer() {
        if (TextUtils.isEmpty(streamUrl)) {
            Log.e(TAG, "播放地址为空");
            if (statusText != null) {
                statusText.setText("播放地址为空");
            }
            return;
        }

        try {
            // 如果videoView是null，需要动态创建
            if (videoView == null) {
                videoView = new NodePlayerView(this);
                videoView.setId(View.generateViewId());
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
                playerContainer.addView(videoView, 0, params);
            }

            // 设置渲染类型
            videoView.setRenderType(NodePlayerView.RenderType.SURFACEVIEW);
            // 设置视频画面缩放模式：保持比例居中显示，不拉伸
            videoView.setUIViewContentMode(NodePlayerView.UIViewContentMode.ScaleAspectFit);

            // 创建NodePlayer
            nodePlayer = new NodePlayer(this);
            nodePlayer.setPlayerView(videoView);

            // 设置传输协议
            setTransportProtocol();

            // 设置URL
            nodePlayer.setInputUrl(streamUrl);

            // 启用视频
            nodePlayer.setVideoEnable(true);

            // 设置缓冲（UDP需要更大缓冲）
            if (transportProtocol.equals("udp") || transportProtocol.equals("udp_multicast")) {
                nodePlayer.setBufferTime(1000);
                nodePlayer.setMaxBufferTime(5000);
                Log.d(TAG, "UDP协议使用较大缓冲");
            } else {
                nodePlayer.setBufferTime(500);
                nodePlayer.setMaxBufferTime(3000);
            }

            // 设置音量
            nodePlayer.setVolume(100);

            // 设置回调
            nodePlayer.setNodePlayerDelegate(new NodePlayerDelegate() {
                @Override
                public void onEventCallback(NodePlayer player, int event, String msg) {
                    runOnUiThread(() -> handlePlayerEvent(event, msg));
                }
            });

            // 开始播放
            nodePlayer.start();
            isPlaying = true;
            updatePlayPauseIcon();

            Log.d(TAG, "开始播放: " + streamUrl + " (协议: " + transportProtocol + ")");

        } catch (Exception e) {
            Log.e(TAG, "初始化播放器失败: " + e.getMessage(), e);
            if (statusText != null) {
                statusText.setText("播放失败: " + e.getMessage());
            }
            if (loadingProgress != null) {
                loadingProgress.setVisibility(View.GONE);
            }
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 根据URL和设置选择传输协议
     * 
     * 支持的协议：
     * - auto: 自动检测（默认）
     * - tcp: RTSP over TCP
     * - udp: RTSP over UDP
     * - udp_multicast: RTSP over UDP Multicast
     * - http: RTSP over HTTP
     */
    private void setTransportProtocol() {
        if (!transportProtocol.equals("auto")) {
            // 手动指定协议
            applyTransportProtocol(transportProtocol);
            Log.d(TAG, "使用指定传输协议: " + transportProtocol);
            return;
        }

        // 自动检测协议
        String urlLower = streamUrl.toLowerCase();
        
        if (urlLower.startsWith("rtsp://")) {
            // RTSP流默认使用TCP
            applyTransportProtocol("tcp");
            Log.d(TAG, "检测到RTSP流，使用TCP传输");
        } else if (urlLower.startsWith("rtmp://")) {
            // RTMP流不需要设置传输协议
            Log.d(TAG, "检测到RTMP流");
        } else if (urlLower.startsWith("http://") || urlLower.startsWith("https://")) {
            // HTTP/HTTPS流不需要设置传输协议
            Log.d(TAG, "检测到HTTP流");
        } else if (urlLower.startsWith("rtp://")) {
            // RTP流
            Log.d(TAG, "检测到RTP流");
        } else if (urlLower.contains(":1234") || urlLower.contains(":5004")) {
            // 常见UDP端口，尝试UDP
            applyTransportProtocol("udp");
            Log.d(TAG, "检测到可能的UDP流");
        } else {
            // 默认尝试RTSP TCP
            applyTransportProtocol("tcp");
            Log.d(TAG, "未知协议，尝试RTSP TCP");
        }
    }

    /**
     * 应用传输协议设置
     */
    private void applyTransportProtocol(String protocol) {
        switch (protocol) {
            case "tcp":
                nodePlayer.setRtspTransport(NodePlayer.RTSP_TRANSPORT_TCP);
                break;
            case "udp":
                nodePlayer.setRtspTransport(NodePlayer.RTSP_TRANSPORT_UDP);
                break;
            case "udp_multicast":
                nodePlayer.setRtspTransport(NodePlayer.RTSP_TRANSPORT_UDP_MULTICAST);
                break;
            case "http":
                nodePlayer.setRtspTransport(NodePlayer.RTSP_TRANSPORT_HTTP);
                break;
            case "auto":
            default:
                // 自动模式不设置，由自动检测决定
                break;
        }
    }

    /**
     * 处理播放器事件
     */
    private void handlePlayerEvent(int event, String msg) {
        Log.d(TAG, "播放器事件: " + event + ", " + msg);
        
        switch (event) {
            case 1001: // 连接中
                if (statusText != null) statusText.setText("正在连接...");
                if (loadingProgress != null) loadingProgress.setVisibility(View.VISIBLE);
                break;
                
            case 1101: // 已连接
                if (statusText != null) statusText.setText("已连接");
                break;
                
            case 1104: // 开始播放
                if (statusText != null) statusText.setText("正在播放");
                if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
                isPlaying = true;
                updatePlayPauseIcon();
                break;
                
            case 1004: // 停止播放
                if (statusText != null) statusText.setText("已停止");
                isPlaying = false;
                updatePlayPauseIcon();
                break;
                
            case 1000: // 错误
                String errorMsg = "播放错误: " + msg;
                Log.e(TAG, errorMsg);
                if (statusText != null) statusText.setText(errorMsg);
                if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
                isPlaying = false;
                updatePlayPauseIcon();
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /**
     * 切换播放/暂停
     */
    private void togglePlayPause() {
        if (nodePlayer == null) return;

        try {
            if (isPlaying) {
                nodePlayer.pause();
                isPlaying = false;
                if (statusText != null) statusText.setText("已暂停");
            } else {
                nodePlayer.start();
                isPlaying = true;
                if (statusText != null) statusText.setText("正在播放");
            }
            updatePlayPauseIcon();
            resetHideTimer();
        } catch (Exception e) {
            Log.e(TAG, "切换播放状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新播放/暂停图标（白色）
     */
    private void updatePlayPauseIcon() {
        if (playPauseBtn != null) {
            int iconRes = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
            playPauseBtn.setImageResource(iconRes);
            playPauseBtn.setColorFilter(Color.WHITE);
        }
    }

    /**
     * 切换全屏
     */
    private void toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    /**
     * 进入全屏（横屏）
     */
    private void enterFullscreen() {
        isFullscreen = true;
        
        // 设置横屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        
        // 隐藏状态栏和导航栏
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        
        // 更新全屏图标
        if (fullscreenBtn != null) {
            fullscreenBtn.setImageResource(R.drawable.ic_fullscreen_exit);
        }
        
        hideControls();
    }

    /**
     * 退出全屏（竖屏）
     */
    private void exitFullscreen() {
        isFullscreen = false;
        
        // 设置竖屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        
        // 恢复状态栏和导航栏
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        
        // 显示底部控制
        if (bottomBar != null) {
            bottomBar.setVisibility(View.VISIBLE);
        }
        
        // 更新全屏图标
        if (fullscreenBtn != null) {
            fullscreenBtn.setImageResource(R.drawable.ic_fullscreen);
        }
        
        showControls();
    }

    /**
     * 切换控制栏显示
     */
    private void toggleControls() {
        if (isControlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    /**
     * 显示控制栏
     */
    private void showControls() {
        isControlsVisible = true;
        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        // 横屏和竖屏都显示底部控制栏
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        resetHideTimer();
    }

    /**
     * 隐藏控制栏
     */
    private void hideControls() {
        isControlsVisible = false;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        hideHandler.removeCallbacks(hideRunnable);
    }

    /**
     * 重置隐藏定时器
     */
    private void resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.postDelayed(hideRunnable, HIDE_DELAY);
    }

    /**
     * 从URL提取设备名称
     */
    private String extractDeviceName(String url) {
        if (TextUtils.isEmpty(url)) return "RTSP设备";

        try {
            String tempUrl = url;
            if (tempUrl.startsWith("rtsp://")) tempUrl = tempUrl.substring(7);
            if (tempUrl.startsWith("rtmp://")) tempUrl = tempUrl.substring(7);
            if (tempUrl.startsWith("http://")) tempUrl = tempUrl.substring(7);
            if (tempUrl.startsWith("https://")) tempUrl = tempUrl.substring(8);

            int atIndex = tempUrl.indexOf('@');
            if (atIndex > 0) tempUrl = tempUrl.substring(atIndex + 1);

            int slashIndex = tempUrl.indexOf('/');
            int colonIndex = tempUrl.indexOf(':');
            int endIndex = -1;

            if (slashIndex > 0 && colonIndex > 0) {
                endIndex = Math.min(slashIndex, colonIndex);
            } else if (slashIndex > 0) {
                endIndex = slashIndex;
            } else if (colonIndex > 0) {
                endIndex = colonIndex;
            }

            if (endIndex > 0) return tempUrl.substring(0, endIndex);
            return tempUrl;
        } catch (Exception e) {
            return "RTSP设备";
        }
    }

    /**
     * 设置通道名称
     */
    private void setChannelName(String name) {
        channelName = name;
        if (channelNameText != null) {
            channelNameText.setText(channelName);
        }
    }

    /**
     * 获取通道名称
     */
    private String getChannelName() {
        return channelName;
    }

    @Override
    public void onBackPressed() {
        // 停止播放并释放资源
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
                nodePlayer.release();
                nodePlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "停止失败: " + e.getMessage(), e);
            }
        }
        hideHandler.removeCallbacksAndMessages(null);
        // 调用父类方法退出Activity
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nodePlayer != null && isPlaying) {
            try {
                nodePlayer.pause();
            } catch (Exception e) {
                Log.e(TAG, "暂停失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nodePlayer != null && !isPlaying) {
            try {
                nodePlayer.start();
                isPlaying = true;
                updatePlayPauseIcon();
            } catch (Exception e) {
                Log.e(TAG, "恢复播放失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
                nodePlayer.release();
                nodePlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "释放失败: " + e.getMessage(), e);
            }
        }
        hideHandler.removeCallbacksAndMessages(null);
    }
}
