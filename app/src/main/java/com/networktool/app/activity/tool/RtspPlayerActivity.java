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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.networktool.app.R;
import com.networktool.app.player.RtspPlayer;
import com.networktool.app.player.RtspPlayerView;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;


/**
 * RTSP流预览Activity - 支持全屏播放
 *
 * 功能说明：
 * - 支持手动输入RTSP地址进行预览
 * - 支持从Onvif设备直接跳转播放
 * - 使用 ExoPlayer 播放 RTSP 视频流
 * - 进入播放后自动横屏全屏
 * - 点击显示/隐藏控制栏
 * - 播放控制（暂停/播放、退出全屏）
 * - TCP传输模式
 */
public class RtspPlayerActivity extends AppCompatActivity {
    private static final String TAG = "RtspPlayerActivity";

    // 输入界面
    private View inputLayout;
    private TextInputEditText rtspUrlEditText;
    private MaterialButton playButton;
    private MaterialButton pasteUrlBtn;
    private View inputBackBtn;
    private RadioGroup protocolGroup;

    // 播放界面
    private FrameLayout playerContainer;
    private RtspPlayerView videoView;
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

    // 新增控制项：音量、画面比例、实时信息
    private ImageView muteBtn;
    private ImageView aspectBtn;
    private SeekBar volumeSeekBar;
    private TextView infoText;
    private int lastVolume = 100;
    private int aspectModeIndex = 0;
    private final Handler infoHandler = new Handler(Looper.getMainLooper());
    private Runnable infoRunnable;

    // RtspPlayer
    private RtspPlayer nodePlayer;

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

        // 新增控制项
        muteBtn = findViewById(R.id.muteBtn);
        aspectBtn = findViewById(R.id.aspectBtn);
        volumeSeekBar = findViewById(R.id.volumeSeekBar);
        infoText = findViewById(R.id.infoText);
        pasteUrlBtn = findViewById(R.id.pasteUrlBtn);
        inputBackBtn = findViewById(R.id.inputBackBtn);

        setupInputExtras();
        setupVolumeControls();
        setupAspectControl();
        setupProtocolChips();
        applySystemInsets();

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

    /* ==================== 输入页辅助操作 ==================== */

    /**
     * 沉浸式（setDecorFitsSystemWindows(false)）下自己吃系统栏内边距，
     * 否则输入页的返回按钮会顶到状态栏、底部控制条会贴到导航栏。
     */
    private void applySystemInsets() {
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                    getWindow().getDecorView(), (v, insets) -> {
                        androidx.core.graphics.Insets bars = insets.getInsets(
                                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                        // 沉浸式下 insets 可能为 0（状态栏隐藏），但某些机型状态栏仍会
                        // 盖住内容，这里按状态栏高度兜底，保证返回按钮不贴顶
                        int topInset = bars.top;
                        int bottomInset = bars.bottom;
                        if (topInset <= 0 && isStatusBarVisible()) {
                            topInset = getStatusBarHeight();
                        }
                        if (inputLayout != null) {
                            inputLayout.setPadding(bars.left, topInset,
                                    bars.right, bottomInset);
                        }
                        setTopMargin(topBar, dp(12) + topInset);
                        setBottomMargin(bottomBar, dp(12) + bottomInset);
                        return insets;
                    });
            androidx.core.view.ViewCompat.requestApplyInsets(getWindow().getDecorView());
        } catch (Throwable e) {
            AppErrorLogger.logError(TAG, "应用系统栏内边距失败", e);
        }
    }

    /** 状态栏是否可见（未被全屏标志隐藏） */
    private boolean isStatusBarVisible() {
        try {
            int flags = getWindow().getDecorView().getWindowSystemUiVisibility();
            return (flags & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
        } catch (Throwable e) {
            return true;
        }
    }

    /** 状态栏高度（兜底用） */
    private int getStatusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return dp(24);
    }

    /** 调整控件上外边距（保留原有值作为基准） */
    private void setTopMargin(View view, int margin) {
        if (view == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
            if (params.topMargin != margin) {
                params.topMargin = margin;
                view.setLayoutParams(params);
            }
        }
    }

    /** 调整控件下外边距 */
    private void setBottomMargin(View view, int margin) {
        if (view == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin;
                view.setLayoutParams(params);
            }
        }
    }

    private void setupInputExtras() {
        if (pasteUrlBtn != null) {
            pasteUrlBtn.setOnClickListener(v -> {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                        if (!TextUtils.isEmpty(text) && rtspUrlEditText != null) {
                            String value = text.toString().trim();
                            rtspUrlEditText.setText(value);
                            rtspUrlEditText.setSelection(value.length());
                            return;
                        }
                    }
                    Toast.makeText(this, "剪贴板没有可用地址", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    AppErrorLogger.logError(TAG, "粘贴地址失败", t);
                }
            });
        }
        if (inputBackBtn != null) {
            inputBackBtn.setOnClickListener(v -> finish());
        }
    }

    /* ==================== 音量 / 静音 ==================== */

    private void setupVolumeControls() {
        if (volumeSeekBar != null) {
            int accent = ThemeManager.getAccessibleThemeColor(this);
            android.content.res.ColorStateList csl = android.content.res.ColorStateList.valueOf(accent);
            volumeSeekBar.setProgressTintList(csl);
            volumeSeekBar.setThumbTintList(csl);
            volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    setVolume(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    hideHandler.removeCallbacks(hideRunnable);
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    resetHideTimer();
                }
            });
        }
        if (muteBtn != null) {
            muteBtn.setOnClickListener(v -> {
                if (nodePlayer == null) return;
                if (nodePlayer.getVolume() > 0) {
                    lastVolume = nodePlayer.getVolume();
                    setVolume(0);
                } else {
                    setVolume(lastVolume > 0 ? lastVolume : 100);
                }
                resetHideTimer();
            });
            // 长按喇叭：切换音量增强倍数（源本身音量偏小时用）
            muteBtn.setOnLongClickListener(v -> {
                cycleAudioGain();
                return true;
            });
        }
    }

    /** 音量增强倍数在 100% / 150% / 200% / 300% 之间循环 */
    private void cycleAudioGain() {
        android.content.SharedPreferences prefs = getSharedPreferences("live_tv", MODE_PRIVATE);
        float current = prefs.getFloat("audio_gain", 1f);
        float next = current < 1.4f ? 1.5f : (current < 1.9f ? 2f : (current < 2.9f ? 3f : 1f));
        prefs.edit().putFloat("audio_gain", next).apply();
        if (nodePlayer != null) nodePlayer.setAudioGain(next);
        Toast.makeText(this, "音量增强：" + Math.round(next * 100) + "%",
                Toast.LENGTH_SHORT).show();
        resetHideTimer();
    }

    private void setVolume(int value) {
        int target = Math.max(0, Math.min(100, value));
        if (volumeSeekBar != null && volumeSeekBar.getProgress() != target) {
            volumeSeekBar.setProgress(target);
        }
        if (nodePlayer != null) {
            nodePlayer.setVolume(target);
        }
        updateMuteIcon(target);
    }

    private void updateMuteIcon(int volume) {
        if (muteBtn == null) return;
        boolean muted = volume <= 0;
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        muteBtn.setImageTintList(android.content.res.ColorStateList.valueOf(
                muted ? ThemeManager.getAccessibleThemeColor(this) : Color.WHITE));
        muteBtn.setContentDescription(muted ? "已静音，点击恢复" : "静音");
    }

    /* ==================== 传输协议选项样式 ==================== */

    /** 传输协议选项做成卡片式：选中态用主题色方块背景 + 描边，未选中为淡底 */
    private void setupProtocolChips() {
        if (protocolGroup == null) return;
        int accent = ThemeManager.getAccessibleThemeColor(this);
        int onSurface = ThemeManager.getOnSurfaceColor(this);
        int outline = ThemeManager.getOutlineVariantColor(this);
        int surface = ThemeManager.getChipSurfaceColor(this);

        for (int i = 0; i < protocolGroup.getChildCount(); i++) {
            View child = protocolGroup.getChildAt(i);
            if (!(child instanceof RadioButton)) continue;
            RadioButton option = (RadioButton) child;

            android.graphics.drawable.GradientDrawable checkedBg =
                    new android.graphics.drawable.GradientDrawable();
            checkedBg.setColor(withAlpha(accent, 0x3D));
            checkedBg.setCornerRadius(dp(12));
            checkedBg.setStroke(dp(1), withAlpha(accent, 0xC0));

            android.graphics.drawable.GradientDrawable normalBg =
                    new android.graphics.drawable.GradientDrawable();
            normalBg.setColor(surface);
            normalBg.setCornerRadius(dp(12));
            normalBg.setStroke(dp(1), withAlpha(outline, 0x66));

            android.graphics.drawable.StateListDrawable background =
                    new android.graphics.drawable.StateListDrawable();
            background.addState(new int[]{android.R.attr.state_checked}, checkedBg);
            background.addState(new int[]{}, normalBg);
            option.setBackground(background);

            option.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{accent, onSurface}));
            option.setTextSize(13);
            option.setGravity(android.view.Gravity.CENTER);
            option.setButtonDrawable(null);
            option.setPadding(dp(4), dp(11), dp(4), dp(11));

            ViewGroup.LayoutParams raw = option.getLayoutParams();
            if (raw instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) raw;
                params.setMarginStart(i == 0 ? 0 : dp(6));
                params.setMarginEnd(0);
                option.setLayoutParams(params);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /* ==================== 画面比例 ==================== */

    private static final RtspPlayerView.UIViewContentMode[] ASPECT_MODES = {
            RtspPlayerView.UIViewContentMode.ScaleAspectFit,   // 适应（保留黑边）
            RtspPlayerView.UIViewContentMode.ScaleAspectFill,  // 裁剪（铺满裁边）
            RtspPlayerView.UIViewContentMode.ScaleToFill       // 拉伸（变形铺满）
    };
    private static final String[] ASPECT_NAMES = {"适应（完整显示）", "裁剪（铺满裁边）", "拉伸（变形铺满）"};

    private void setupAspectControl() {
        if (aspectBtn != null) {
            aspectBtn.setOnClickListener(v -> showAspectMenu(v));
        }
    }

    /** 画面比例：点按钮弹出选项（互斥单选，标出当前项） */
    private void showAspectMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu popup =
                new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenu().setGroupCheckable(0, true, true);
        for (int i = 0; i < ASPECT_NAMES.length; i++) {
            android.view.MenuItem item = popup.getMenu().add(0, i, i, ASPECT_NAMES[i]);
            item.setCheckable(true);
            item.setChecked(i == aspectModeIndex);
        }
        popup.setOnMenuItemClickListener(item -> {
            aspectModeIndex = item.getItemId();
            applyAspectMode();
            resetHideTimer();
            return true;
        });
        popup.show();
        hideHandler.removeCallbacks(hideRunnable);
    }

    private void applyAspectMode() {
        if (videoView == null) return;
        videoView.setUIViewContentMode(ASPECT_MODES[aspectModeIndex]);
        if (nodePlayer != null) {
            videoView.setVideoSize(nodePlayer.getVideoWidth(), nodePlayer.getVideoHeight());
        }
    }

    /* ==================== 实时信息浮层 ==================== */

    private void startInfoUpdates() {
        stopInfoUpdates();
        infoRunnable = new Runnable() {
            @Override
            public void run() {
                updateInfoOverlay();
                infoHandler.postDelayed(this, 1000);
            }
        };
        infoHandler.post(infoRunnable);
    }

    private void stopInfoUpdates() {
        if (infoRunnable != null) {
            infoHandler.removeCallbacks(infoRunnable);
            infoRunnable = null;
        }
    }

    private void updateInfoOverlay() {
        if (nodePlayer == null) return;
        int width = 0;
        int height = 0;
        float fps = 0f;
        long bitrate = -1;
        try {
            width = nodePlayer.getVideoWidth();
            height = nodePlayer.getVideoHeight();
            fps = nodePlayer.getVideoFrameRate();
            bitrate = nodePlayer.getBitrateEstimate();
        } catch (Throwable ignored) {}
        if (videoView != null && width > 0 && height > 0) {
            videoView.setVideoSize(width, height);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(width > 0 && height > 0 ? width + "×" + height : "分辨率未知");
        if (bitrate > 0) {
            sb.append(" · ").append(formatBitrate(bitrate));
        }
        if (fps > 0) {
            sb.append(" · ").append(Math.round(fps)).append("fps");
        }
        if (infoText != null) {
            infoText.setText(sb.toString());
        }
        if (resolutionText != null) {
            resolutionText.setText(width > 0 && height > 0 ? width + "×" + height : "未知分辨率");
        }
    }

    private String formatBitrate(long bitsPerSecond) {
        double kbps = bitsPerSecond / 1000d;
        if (kbps >= 1000) {
            return String.format(java.util.Locale.US, "%.1fMbps", kbps / 1000d);
        }
        return String.format(java.util.Locale.US, "%.0fkbps", kbps);
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

        // 启动实时信息刷新（分辨率/码率/帧率）
        startInfoUpdates();

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
                videoView = new RtspPlayerView(this);
                videoView.setId(View.generateViewId());
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
                playerContainer.addView(videoView, 0, params);
            }

            // 设置渲染类型：跟随直播页保存的偏好，默认 TextureView（SurfaceView 在部分设备无画面）
            android.content.SharedPreferences prefs =
                    getSharedPreferences("live_tv", MODE_PRIVATE);
            String savedRender = prefs.getString("render_type", "textureview");
            videoView.setRenderType("textureview".equals(savedRender)
                    ? RtspPlayerView.RenderType.TEXTUREVIEW
                    : RtspPlayerView.RenderType.SURFACEVIEW);
            // 设置视频画面缩放模式：保持比例居中显示，不拉伸
            videoView.setUIViewContentMode(RtspPlayerView.UIViewContentMode.ScaleAspectFit);
            aspectModeIndex = 0;

            // 创建RtspPlayer
            nodePlayer = new RtspPlayer(this);
            nodePlayer.setPlayerView(videoView);
            nodePlayer.setHwdec(prefs.getString("hwdec_mode", "mediacodec"));
            nodePlayer.setVolume(volumeSeekBar != null ? volumeSeekBar.getProgress() : 100);
            // 音量增强倍数跟随电视直播页的设置（共享 live_tv 偏好）
            nodePlayer.setAudioGain(prefs.getFloat("audio_gain", 1f));

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
            nodePlayer.setDelegate(new RtspPlayer.Delegate() {
                @Override
                public void onEventCallback(RtspPlayer player, int event, String msg) {
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
                nodePlayer.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_TCP);
                break;
            case "udp":
                nodePlayer.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_UDP);
                break;
            case "udp_multicast":
                nodePlayer.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_UDP_MULTICAST);
                break;
            case "http":
                nodePlayer.setRtspTransport(RtspPlayer.RTSP_TRANSPORT_HTTP);
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

            case 1200: // RTSP 预检结果：告诉用户真实失败原因
                if (statusText != null) statusText.setText("失败原因：" + msg);
                if (msg != null && msg.contains("401")) {
                    if (urlHasCredentials()) {
                        showReasonDialog("账号或密码不正确（服务器返回 401）", msg);
                    } else {
                        showAuthDialog(msg);
                    }
                } else {
                    showReasonDialog("无法播放这个地址", msg);
                }
                break;
        }
    }


    /** 地址里是否已经带了账号密码 */
    private boolean urlHasCredentials() {
        if (TextUtils.isEmpty(streamUrl)) return false;
        try {
            java.net.URI uri = new java.net.URI(
                    com.networktool.app.util.RtspDiagnostics.normalizeUrl(streamUrl));
            String userInfo = uri.getUserInfo();
            return userInfo != null && !userInfo.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 服务器要账号密码：弹窗让用户补上，确认后带鉴权重试 */
    private void showAuthDialog(String reason) {
        if (isFinishing() || isDestroyed()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_rtsp_auth, null);
        android.widget.EditText userInput = content.findViewById(R.id.userInput);
        android.widget.EditText passwordInput = content.findViewById(R.id.passwordInput);
        android.widget.TextView hint = content.findViewById(R.id.authHint);
        if (hint != null) hint.setText(reason);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("需要登录信息")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("重试", (dialog, which) -> {
                    String user = userInput.getText() == null ? ""
                            : userInput.getText().toString().trim();
                    String password = passwordInput.getText() == null ? ""
                            : passwordInput.getText().toString();
                    if (user.isEmpty()) {
                        Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String url = buildUrlWithCredentials(streamUrl, user, password);
                    if (url == null) {
                        Toast.makeText(this, "地址格式无法加入账号", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    streamUrl = url;
                    if (rtspUrlEditText != null) rtspUrlEditText.setText(url);
                    startPlayback();
                })
                .show();
    }

    /** 把 user:pass@ 插到地址里（已有则替换） */
    private String buildUrlWithCredentials(String url, String user, String password) {
        try {
            java.net.URI uri = new java.net.URI(
                    com.networktool.app.util.RtspDiagnostics.normalizeUrl(url));
            String host = uri.getHost();
            if (host == null) return null;
            int port = uri.getPort() > 0 ? uri.getPort() : 554;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath();
            String query = uri.getRawQuery();
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://")
                    .append(java.net.URLEncoder.encode(user, "UTF-8")).append(':')
                    .append(java.net.URLEncoder.encode(password == null ? "" : password, "UTF-8"))
                    .append('@').append(host);
            if (port > 0) sb.append(':').append(port);
            sb.append(path);
            if (query != null && !query.isEmpty()) sb.append('?').append(query);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 非鉴权类失败：把原因完整展示出来，方便判断是端口不通还是路径不对 */
    private void showReasonDialog(String title, String reason) {
        if (isFinishing() || isDestroyed()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(reason + "\n\n地址：" + streamUrl)
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制地址", (dialog, which) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null && streamUrl != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("RTSP", streamUrl));
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("重试", (dialog, which) -> startPlayback())
                .show();
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
        // 正在播放时，返回键先回到 RTSP 输入页（取消全屏 → 退出播放），
        // 再按一次才真正离开页面；之前是直接结束 Activity，看起来就像“返回跳到了主页”。
        boolean playingView = playerContainer != null
                && playerContainer.getVisibility() == View.VISIBLE
                && (inputLayout == null || inputLayout.getVisibility() != View.VISIBLE);
        if (playingView) {
            if (isFullscreen) {
                toggleFullscreen();
            }
            releasePlayer();
            showInputPage();
            return;
        }
        releasePlayer();
        hideHandler.removeCallbacksAndMessages(null);
        super.onBackPressed();
    }

    /** 停止并释放播放器 */
    private void releasePlayer() {
        hideHandler.removeCallbacks(hideRunnable);
        if (nodePlayer != null) {
            try {
                nodePlayer.stop();
                nodePlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "停止失败: " + e.getMessage(), e);
            }
            nodePlayer = null;
        }
        isPlaying = false;
    }

    /** 回到 RTSP 地址输入页 */
    private void showInputPage() {
        if (playerContainer != null) {
            playerContainer.setVisibility(View.GONE);
        }
        if (inputLayout != null) {
            inputLayout.setVisibility(View.VISIBLE);
        }
        if (loadingProgress != null) {
            loadingProgress.setVisibility(View.GONE);
        }
        stopInfoUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopInfoUpdates();
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
        // onStop 已停止流；返回前台时由 onResume 重新加载，避免 isPlaying 仍为 true 导致黑屏。
        isPlaying = false;
        updatePlayPauseIcon();
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
        stopInfoUpdates();
    }
}
