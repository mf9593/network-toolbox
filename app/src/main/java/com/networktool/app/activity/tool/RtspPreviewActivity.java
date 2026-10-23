package com.networktool.app.activity.tool;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.networktool.app.R;
import com.networktool.app.player.RtspPlayer;
import com.networktool.app.player.RtspPlayerView;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;


/**
 * RTSP流预览Activity - 视频播放器风格
 * 
 * 功能说明：
 * - 使用 Media3 ExoPlayer 播放 RTSP 视频流
 * - 支持主码流/子码流切换（清晰度选择）
 * - 支持横竖屏切换
 * - 竖屏按比例显示视频（居中，不拉伸）
 * - 点击显示/隐藏控制栏
 * - 播放控制（暂停/播放、全屏）
 * - TCP传输模式
 * 
 * 使用Media3 ExoPlayer API
 */
public class RtspPreviewActivity extends AppCompatActivity {
    private static final String TAG = "RtspPreviewActivity";

    // 视图控件
    private RelativeLayout rootView;
    private FrameLayout videoContainer;
    private View videoTopBar;
    private View videoBottomBar;
    private View bottomControls;
    private TextView videoTitleText;
    private TextView videoSubtitleText;
    private ImageView playPauseBtn;
    private ImageView fullscreenBtn;
    private ImageView backButton;
    private TextView speedBtn;
    private TextView resolutionText;
    private ProgressBar loadingProgressBar;
    private TextView statusOverlayText;
    private MaterialButton mainStreamButton;
    private MaterialButton subStreamButton;
    private TextView statusText;
    private TextView channelNameText;

    // Media3 ExoPlayer
    private RtspPlayer nodePlayer;
    private RtspPlayerView videoView;

    // 流地址
    private String mainStreamUrl;
    private String subStreamUrl;
    private String currentStreamUrl;
    private boolean isMainStream = true;

    // 屏幕状态
    private boolean isFullscreen = false;
    private boolean isControlsVisible = false;
    private boolean isPlaying = false;

    // 自动隐藏延迟
    private Handler hideHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable = this::hideControls;
    private static final int HIDE_DELAY = 5000; // 5秒后隐藏

    // 视频尺寸
    private int videoWidth = 0;
    private int videoHeight = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化全屏
        // setDecorFitsSystemWindows 需要 Android 11 (API 30) 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0x00000000);
        }
        
        setContentView(R.layout.activity_rtsp_preview);

        // 获取传递的参数
        mainStreamUrl = getIntent().getStringExtra("main_stream_url");
        subStreamUrl = getIntent().getStringExtra("sub_stream_url");
        String deviceName = getIntent().getStringExtra("device_name");
        String channelName = getIntent().getStringExtra("channel_name");

        if (mainStreamUrl == null && subStreamUrl == null) {
            Toast.makeText(this, "未提供RTSP地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 默认竖屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        initViews(deviceName, channelName);
        initPlayer();
    }

    private void initViews(String deviceName, String channelName) {
        // 根视图
        rootView = findViewById(R.id.rootView);

        // 视频容器
        videoContainer = findViewById(R.id.videoContainer);

        // 控制栏
        videoTopBar = findViewById(R.id.videoTopBar);
        videoBottomBar = findViewById(R.id.videoBottomBar);
        bottomControls = findViewById(R.id.bottomControls);
        videoTitleText = findViewById(R.id.videoTitleText);
        videoSubtitleText = findViewById(R.id.videoSubtitleText);
        playPauseBtn = findViewById(R.id.playPauseBtn);
        fullscreenBtn = findViewById(R.id.fullscreenBtn);
        backButton = findViewById(R.id.backButton);
        speedBtn = findViewById(R.id.speedBtn);
        resolutionText = findViewById(R.id.resolutionText);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        statusOverlayText = findViewById(R.id.statusOverlayText);

        // 码流选择
        mainStreamButton = findViewById(R.id.mainStreamButton);
        subStreamButton = findViewById(R.id.subStreamButton);
        statusText = findViewById(R.id.statusText);
        channelNameText = findViewById(R.id.channelNameText);

        // 设置标题
        String title = "RTSP预览";
        if (deviceName != null && !deviceName.isEmpty()) {
            title = deviceName;
        }
        if (videoTitleText != null) {
            videoTitleText.setText(title);
        }

        // 设置右下角通道名称
        if (channelNameText != null) {
            if (channelName != null && !channelName.isEmpty()) {
                channelNameText.setText(channelName);
                channelNameText.setVisibility(View.VISIBLE);
            } else {
                channelNameText.setVisibility(View.GONE);
            }
        }

        // 默认使用主码流
        if (mainStreamUrl != null && !mainStreamUrl.isEmpty()) {
            currentStreamUrl = mainStreamUrl;
            isMainStream = true;
        } else if (subStreamUrl != null && !subStreamUrl.isEmpty()) {
            currentStreamUrl = subStreamUrl;
            isMainStream = false;
        }
        
        updateStreamButtons();

        // 视频容器点击事件 - 显示/隐藏控制栏
        videoContainer.setOnClickListener(v -> toggleControls());

        // 返回按钮
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // 播放/暂停按钮
        if (playPauseBtn != null) {
            playPauseBtn.setOnClickListener(v -> togglePlayPause());
        }

        // 全屏按钮
        if (fullscreenBtn != null) {
            fullscreenBtn.setOnClickListener(v -> toggleFullscreen());
        }

        // 主码流按钮
        mainStreamButton.setOnClickListener(v -> {
            if (mainStreamUrl == null || mainStreamUrl.isEmpty()) {
                Toast.makeText(this, "主码流地址不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isMainStream) {
                isMainStream = true;
                currentStreamUrl = mainStreamUrl;
                updateStreamButtons();
                switchStream();
            }
        });

        // 子码流按钮
        subStreamButton.setOnClickListener(v -> {
            if (subStreamUrl == null || subStreamUrl.isEmpty()) {
                Toast.makeText(this, "子码流地址不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isMainStream) {
                isMainStream = false;
                currentStreamUrl = subStreamUrl;
                updateStreamButtons();
                switchStream();
            }
        });
    }

    /**
     * 初始化RtspPlayer (2.x API)
     *
     * 创建并配置RtspPlayer实例，设置以下参数：
     * - 渲染类型为SURFACEVIEW
     * - RTSP传输模式为TCP
     * - 缓冲时间500ms，最大缓冲3000ms
     * - 启用视频，音量100%
     * - 设置事件回调处理播放状态变化
     */
    private void initPlayer() {
        try {
            // 创建RtspPlayerView
            videoView = new RtspPlayerView(this);
            // 设置渲染类型为SURFACEVIEW
            videoView.setRenderType(RtspPlayerView.RenderType.SURFACEVIEW);
            // 设置视频画面缩放模式为填充
            videoView.setUIViewContentMode(RtspPlayerView.UIViewContentMode.ScaleToFill);

            // 将videoView添加到videoContainer
            // 先确保videoContainer中没有子view
            videoContainer.removeAllViews();
            
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            videoContainer.addView(videoView, layoutParams);

            // 创建RtspPlayer实例 (2.x版本只需要Context)
            nodePlayer = new RtspPlayer(this);

            // 按照参数顺序设置播放器配置
            
            // 步骤1: 设置播放视图
            nodePlayer.setPlayerView(videoView);

            // 步骤2: 设置RTSP流使用TCP传输（避免UDP丢包问题）
            nodePlayer.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_TCP);

            // 步骤3: 设置URL（如果有）
            if (currentStreamUrl != null && !currentStreamUrl.isEmpty()) {
                nodePlayer.setInputUrl(currentStreamUrl);
            }

            // 步骤4: 启用视频播放
            nodePlayer.setVideoEnable(true);

            // 步骤5: 设置缓冲时间（500ms初始缓冲，3000ms最大缓冲）
            nodePlayer.setBufferTime(500);
            nodePlayer.setMaxBufferTime(3000);

            // 步骤6: 设置音量为100%
            nodePlayer.setVolume(100);

            // 步骤7: 开始播放（在设置回调之前）
            if (currentStreamUrl != null && !currentStreamUrl.isEmpty()) {
                nodePlayer.start();
                isPlaying = true;
            }

            // 设置事件回调（在 start() 之后）
            nodePlayer.setDelegate(new RtspPlayer.Delegate() {
                @Override
                public void onEventCallback(RtspPlayer player, int event, String msg) {
                    Log.d(TAG, "RtspPlayer事件: " + event + ", 消息: " + msg);
                    // 在UI线程处理事件
                    runOnUiThread(() -> handlePlayerEvent(event, msg));
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "初始化RtspPlayer失败: " + e.getMessage(), e);
            handlePlayerError(e.getMessage());
        }
    }

    /**
     * 处理播放器事件
     *
     * 根据RtspPlayer返回的事件码处理不同的播放状态：
     * - 1001: 连接中
     * - 1101: 已连接
     * - 1104: 开始播放
     * - 1004: 停止播放
     * - 1000: 错误
     * - 1102: 播放完成
     *
     * @param event 事件码
     * @param msg 事件消息
     */
    private void handlePlayerEvent(int event, String msg) {
        switch (event) {
            case 1001: // 连接中
                statusText.setText("正在连接...");
                showStatusOverlay("连接中...");
                break;
            case 1101: // 已连接
                statusText.setText("已连接到服务器");
                break;
            case 1104: // 开始播放
                statusText.setText("正在播放: " + (isMainStream ? "主码流" : "子码流"));
                hideStatusOverlay();
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
                // 更新分辨率显示
                if (resolutionText != null) {
                    String resText = (videoWidth > 0 && videoHeight > 0) 
                            ? videoWidth + "x" + videoHeight 
                            : "未知分辨率";
                    resolutionText.setText(resText);
                }
                break;
            case 1004: // 停止播放
                statusText.setText("已停止播放");
                isPlaying = false;
                updatePlayPauseIcon();
                break;
            case 1000: // 错误
                Log.e(TAG, "播放错误: " + msg);
                statusText.setText("播放错误: " + msg);
                hideStatusOverlay();
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
                Toast.makeText(this, "播放失败: " + msg, Toast.LENGTH_SHORT).show();
                break;
            case 1102: // 播放完成
                statusText.setText("播放完成");
                break;
            default:
                Log.d(TAG, "未知事件: " + event);
                break;
        }
    }

    /**
     * 处理播放器错误
     */
    private void handlePlayerError(String errorMsg) {
        if (errorMsg != null && errorMsg.toLowerCase().contains("unauthorized")) {
            statusText.setText("RtspPlayer未授权\n\n提示：\n1. RTSP地址已复制到剪贴板\n2. 可使用VLC等外部播放器打开");
            copyToClipboard(currentStreamUrl);
            Toast.makeText(this, "RtspPlayer需要授权，RTSP地址已复制", Toast.LENGTH_LONG).show();
        } else {
            statusText.setText("播放器初始化失败: " + errorMsg);
            Toast.makeText(this, "播放器初始化失败: " + errorMsg, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("RTSP URL", text);
            clipboard.setPrimaryClip(clip);
        } catch (Exception e) {
            Log.e(TAG, "复制到剪贴板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 开始预览 (2.x API)
     *
     * 设置RTSP流URL并开始播放。
     * 显示加载动画，10秒后自动隐藏。
     * 如果播放失败，调用错误处理函数。
     */
    private void startPreview() {
        if (currentStreamUrl == null || currentStreamUrl.isEmpty()) {
            statusText.setText("RTSP地址为空");
            return;
        }

        if (nodePlayer == null) {
            statusText.setText("播放器未初始化");
            return;
        }

        try {
            // 显示加载动画
            if (loadingProgressBar != null) {
                loadingProgressBar.setVisibility(View.VISIBLE);
            }
            showStatusOverlay("正在连接...");

            // 设置URL并启动播放（如果 initPlayer 中没有启动）
            nodePlayer.setInputUrl(currentStreamUrl);
            
            // 如果播放器未运行，启动它
            if (!isPlaying) {
                nodePlayer.start();
                isPlaying = true;
            }
            
            updatePlayPauseIcon();
            statusText.setText("正在连接...");
            Log.d(TAG, "开始播放RTSP流: " + currentStreamUrl);

            // 10秒后隐藏加载动画
            hideHandler.postDelayed(() -> {
                if (loadingProgressBar != null && loadingProgressBar.getVisibility() == View.VISIBLE) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }, 10000);

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            Log.e(TAG, "播放失败: " + errorMsg, e);
            handlePlayerError(errorMsg);

            hideStatusOverlay();
            if (loadingProgressBar != null) {
                loadingProgressBar.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 切换码流
     *
     * 在主码流和子码流之间切换。先停止当前播放，
     * 然后使用新的URL开始播放。
     */
    private void switchStream() {
        statusText.setText("正在切换至" + (isMainStream ? "主码流" : "子码流") + "...");
        showStatusOverlay("切换中...");
        
        // 停止当前播放
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
                nodePlayer.release();
                isPlaying = false;
                updatePlayPauseIcon();
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败: " + e.getMessage(), e);
            }
        }
        
        // 重新初始化播放器
        initPlayer();
    }

    /**
     * 更新码流按钮状态
     *
     * 根据当前播放的码流类型更新按钮的启用状态和透明度：
     * - 当前播放的码流按钮禁用（半透明）
     * - 另一个码流按钮启用（不透明）
     * - 更新状态文字显示
     */
    private void updateStreamButtons() {
        if (isMainStream) {
            mainStreamButton.setEnabled(false);
            mainStreamButton.setAlpha(0.5f);
            subStreamButton.setEnabled(true);
            subStreamButton.setAlpha(1.0f);
            statusText.setText("当前: 主码流 (高清)");
        } else {
            mainStreamButton.setEnabled(true);
            mainStreamButton.setAlpha(1.0f);
            subStreamButton.setEnabled(false);
            subStreamButton.setAlpha(0.5f);
            statusText.setText("当前: 子码流 (流畅)");
        }
    }

    /**
     * 显示/隐藏控制栏
     *
     * 切换顶部和底部控制栏的显示状态。
     * 点击视频画面时触发此操作。
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
     *
     * 显示顶部标题栏和底部控制按钮，并设置5秒后自动隐藏。
     */
    private void showControls() {
        isControlsVisible = true;
        if (videoTopBar != null) {
            videoTopBar.setVisibility(View.VISIBLE);
        }
        if (videoBottomBar != null) {
            videoBottomBar.setVisibility(View.VISIBLE);
        }
        
        // 延迟隐藏
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.postDelayed(hideRunnable, HIDE_DELAY);
    }

    /**
     * 隐藏控制栏
     *
     * 隐藏顶部标题栏和底部控制按钮，并取消自动隐藏定时器。
     */
    private void hideControls() {
        isControlsVisible = false;
        if (videoTopBar != null) {
            videoTopBar.setVisibility(View.GONE);
        }
        if (videoBottomBar != null) {
            videoBottomBar.setVisibility(View.GONE);
        }
        hideHandler.removeCallbacks(hideRunnable);
    }

    /**
     * 切换播放/暂停 (2.x API)
     *
     * 切换视频的播放和暂停状态。
     * 暂停时显示"已暂停"提示，继续时显示"继续播放"提示。
     */
    private void togglePlayPause() {
        if (nodePlayer == null) return;
        
        try {
            if (isPlaying) {
                // 暂停
                nodePlayer.pause();
                isPlaying = false;
                showStatusOverlay("已暂停");
            } else {
                // 继续播放
                nodePlayer.start();
                isPlaying = true;
                showStatusOverlay("继续播放");
            }
            updatePlayPauseIcon();
            
            // 点击后重新计时隐藏
            if (isControlsVisible) {
                hideHandler.removeCallbacks(hideRunnable);
                hideHandler.postDelayed(hideRunnable, HIDE_DELAY);
            }
        } catch (Exception e) {
            Log.e(TAG, "切换播放状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新播放/暂停图标
     *
     * 根据当前播放状态更新播放按钮图标：
     * - 播放中：显示暂停图标
     * - 暂停中：显示播放图标
     */
    private void updatePlayPauseIcon() {
        if (playPauseBtn != null) {
            if (isPlaying) {
                playPauseBtn.setImageResource(R.drawable.ic_pause);
            } else {
                playPauseBtn.setImageResource(R.drawable.ic_play);
            }
        }
    }

    /**
     * 切换全屏
     *
     * 在横屏（全屏）和竖屏（普通）模式之间切换。
     * 横屏时隐藏底部控制栏，竖屏时显示底部控制栏。
     */
    private void toggleFullscreen() {
        Configuration config = getResources().getConfiguration();
        
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 当前是横屏，切换为竖屏
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isFullscreen = false;
            if (fullscreenBtn != null) {
                fullscreenBtn.setImageResource(R.drawable.ic_fullscreen);
            }
            // 竖屏显示底部控制
            if (bottomControls != null) {
                bottomControls.setVisibility(View.VISIBLE);
            }
        } else {
            // 当前是竖屏，切换为横屏
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            isFullscreen = true;
            if (fullscreenBtn != null) {
                fullscreenBtn.setImageResource(R.drawable.ic_fullscreen_exit);
            }
            // 横屏隐藏底部控制
            if (bottomControls != null) {
                bottomControls.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 设置全屏
     *
     * 使用Immersive Sticky模式隐藏状态栏和导航栏，
     * 实现真正的沉浸式全屏体验。
     */
    private void setFullScreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    /**
     * 退出全屏
     *
     * 恢复状态栏和导航栏的显示，退出沉浸式模式。
     */
    private void clearFullScreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    /**
     * 显示状态覆盖层
     *
     * 在视频中央显示临时状态提示（如"连接中"、"切换中"等）。
     *
     * @param message 要显示的状态消息
     */
    private void showStatusOverlay(String message) {
        if (statusOverlayText != null) {
            statusOverlayText.setText(message);
            statusOverlayText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 隐藏状态覆盖层
     *
     * 隐藏视频中央的状态提示。
     */
    private void hideStatusOverlay() {
        if (statusOverlayText != null) {
            statusOverlayText.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        // 如果是全屏，先退出全屏
        if (isFullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isFullscreen = false;
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        // 屏幕方向改变时调整
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏：隐藏状态栏和底部控制，沉浸式
            setFullScreen();
            hideControls();
            if (bottomControls != null) {
                bottomControls.setVisibility(View.GONE);
            }
            // 调整视频容器为全屏
            ViewGroup.LayoutParams params = videoContainer.getLayoutParams();
            if (params != null) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                videoContainer.setLayoutParams(params);
            }
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            // 竖屏：显示底部控制
            clearFullScreen();
            showControls();
            if (bottomControls != null) {
                bottomControls.setVisibility(View.VISIBLE);
            }
            // 调整视频容器，留出底部空间
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) videoContainer.getLayoutParams();
            if (params != null) {
                params.width = RelativeLayout.LayoutParams.MATCH_PARENT;
                params.height = RelativeLayout.LayoutParams.MATCH_PARENT;
                params.addRule(RelativeLayout.ABOVE, 0);
                videoContainer.setLayoutParams(params);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停播放
        if (nodePlayer != null && isPlaying) {
            try {
                nodePlayer.pause();
            } catch (Exception e) {
                Log.e(TAG, "暂停播放失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // onStop 只停止播放，保留播放器以便返回前台后恢复；真正释放放在 onDestroy。
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复播放 - 只在nodePlayer已停止时才调用start()
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
    protected void onDestroy() {
        super.onDestroy();
        // 停止并释放RtspPlayer资源
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
                nodePlayer.release();
                nodePlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败: " + e.getMessage(), e);
            }
        }
        hideHandler.removeCallbacksAndMessages(null);
    }
}
