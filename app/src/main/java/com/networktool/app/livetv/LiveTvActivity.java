package com.networktool.app.livetv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networktool.app.R;
import com.networktool.app.player.RtspPlayer;
import com.networktool.app.player.RtspPlayerView;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播：加载 44023.txt 之类的频道列表，左侧选台、右侧 ExoPlayer 播放。
 */
public class LiveTvActivity extends AppCompatActivity implements RtspPlayer.Delegate {

    private static final String TAG = "LiveTvActivity";
    private static final int REQUEST_PICK_LIST = 401;
    private static final String PREFS_NAME = "live_tv";
    private static final String KEY_LAST_PLAYLIST = "last_playlist_path";
    private static final String KEY_HWDEC = "hwdec_mode";
    private static final String KEY_RENDER_TYPE = "render_type";
    /** 频道抽屉宽度比例（可拖动调整） */
    private static final String KEY_PANEL_RATIO = "panel_width_ratio";
    /** 音量增强倍数（直播源本身音量偏小时用） */
    private static final String KEY_AUDIO_GAIN = "audio_gain";
    /** 自动换源：超过该秒数还没出画面就自动切下一条线路（0 表示关闭，最小 5 秒） */
    private static final String KEY_AUTO_SWITCH = "auto_switch_seconds";
    private static final int AUTO_SWITCH_MIN_SECONDS = 5;
    private static final String KEY_GITHUB_URL = "github_playlist_url";
    private static final String GITHUB_CACHE_NAME = "github_playlist_cache.txt";
    /** 预置的 GitHub 列表仓库（本地不内置任何频道数据，运行时从这里拉取） */
    private static final String DEFAULT_GITHUB_LIST =
            "https://github.com/iptv-org/iptv/blob/master/streams/cn.m3u";
    private static final String IMPORTED_PLAYLIST_NAME = "imported_playlist.txt";

    private RtspPlayerView videoView;
    private RtspPlayer player;
    private RecyclerView channelList;
    private LiveChannelAdapter adapter;
    private TextView currentChannelText;
    private TextView streamStatusText;
    private TextView centerStatusText;
    private TextView listPathText;
    private TextView channelCountText;
    /** 左侧竖排分类容器 / 外层滚动条 */
    private LinearLayout categoryTabs;
    private android.widget.ScrollView categoryScroll;
    private View liveBadge;
    private View channelPanel;
    private View playerTopBar;
    private View playerBottomBar;
    private ProgressBar loadingProgress;
    private android.widget.ImageButton prevButton;
    private android.widget.ImageButton nextButton;
    private android.widget.ImageButton retryButton;
    private android.widget.ImageButton decodeButton;
    private android.widget.ImageButton playPauseButton;
    private android.widget.ImageButton playlistButton;
    private android.widget.ImageButton muteBtn;
    private android.widget.ImageButton aspectBtn;
    private android.widget.SeekBar volumeSeekBar;
    private TextView infoText;
    private TextView sourceButton;
    private android.widget.ImageButton syncButton;
    private android.widget.ImageButton castButton;
    private View panelResizeHandle;
    /** 频道抽屉宽度占屏幕比例（1/4 ~ 1/2） */
    private float panelRatio = 0.38f;
    private int lastVolume = 100;
    private int aspectModeIndex = 0;
    /** 本次播放开始时间，用于自动换源超时判断（0 表示已就绪或未计时） */
    private long playStartAt = 0L;
    /** 本次频道已经自动换源的次数，防止在线路之间无限循环 */
    private int autoSwitchCount = 0;
    private final android.os.Handler infoHandler = new android.os.Handler(Looper.getMainLooper());
    private Runnable infoRunnable;
    private boolean isPaused = false;

    private boolean controlsVisible = false;
    private final android.os.Handler hideHandler = new android.os.Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideControls;
    private static final long CONTROLS_HIDE_DELAY_MS = 4000;
    /** 频道抽屉打开后，无操作自动收起的延时 */
    private static final long CHANNEL_HIDE_DELAY_MS = 5000;

    private final List<LiveChannel> channels = new ArrayList<>();
    private LiveChannel currentChannel;
    private String currentPath;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_live_tv);

            applyImmersiveMode();
            initViews();
            initPlayer();
            loadDefaultPlaylist();
        } catch (Throwable t) {
            AppErrorLogger.logError(TAG, "初始化电视直播页面失败", t);
            Toast.makeText(this, "直播页面初始化失败", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        // 允许画面铺进刘海/挖孔区域，横屏全屏时不留黑边
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        hideSystemBars();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 隐藏状态栏 / 导航栏，并允许边缘滑动临时唤出 */
    private void hideSystemBars() {
        try {
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } catch (Throwable ignored) {
        }
    }

    private void initViews() {
        videoView = findViewById(R.id.videoView);
        channelList = findViewById(R.id.channelList);
        currentChannelText = findViewById(R.id.currentChannelText);
        streamStatusText = findViewById(R.id.streamStatusText);
        centerStatusText = findViewById(R.id.centerStatusText);
        listPathText = findViewById(R.id.listPathText);
        channelCountText = findViewById(R.id.channelCountText);
        categoryTabs = findViewById(R.id.categoryTabs);
        categoryScroll = findViewById(R.id.categoryScroll);
        liveBadge = findViewById(R.id.liveBadge);
        loadingProgress = findViewById(R.id.loadingProgress);
        prevButton = findViewById(R.id.prevButton);
        nextButton = findViewById(R.id.nextButton);
        retryButton = findViewById(R.id.retryButton);
        decodeButton = findViewById(R.id.decodeButton);
        playPauseButton = findViewById(R.id.playPauseButton);
        playlistButton = findViewById(R.id.playlistButton);
        channelPanel = findViewById(R.id.channelPanel);
        playerTopBar = findViewById(R.id.playerTopBar);
        playerBottomBar = findViewById(R.id.playerBottomBar);
        muteBtn = findViewById(R.id.muteBtn);
        aspectBtn = findViewById(R.id.aspectBtn);
        volumeSeekBar = findViewById(R.id.volumeSeekBar);
        infoText = findViewById(R.id.infoText);
        sourceButton = findViewById(R.id.sourceButton);
        syncButton = findViewById(R.id.syncButton);
        castButton = findViewById(R.id.castButton);
        panelResizeHandle = findViewById(R.id.panelResizeHandle);
        setupPanelResize();

        int accent = ThemeManager.getAccessibleThemeColor(this);
        loadingProgress.setIndeterminateTintList(ColorStateList.valueOf(accent));

        adapter = new LiveChannelAdapter(channel ->
                playChannel(channel, channels.indexOf(channel)));
        channelList.setLayoutManager(new LinearLayoutManager(this));
        channelList.setAdapter(adapter);
        // 在列表里滑动/点击都算「有操作」，重置 5 秒自动收起计时
        // 列表内的点击/滑动同样算「有操作」：子项会吃掉触摸事件，
        // 所以用 OnItemTouchListener 拦截一次，只观察不消费。
        channelList.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@androidx.annotation.NonNull RecyclerView rv,
                                                 @androidx.annotation.NonNull android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN
                        || action == android.view.MotionEvent.ACTION_MOVE) {
                    postponeHide();
                }
                return false;
            }
        });
        channelList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                postponeHide();
            }
        });

        findViewById(R.id.backButton).setOnClickListener(v -> backToMain());
        // 只保留文件选择导入；长按仍可手动输入路径
        findViewById(R.id.importButton).setOnClickListener(v -> pickPlaylistFile());
        findViewById(R.id.importButton).setOnLongClickListener(v -> {
            showPathDialog();
            return true;
        });
        playlistButton.setOnClickListener(v -> toggleChannelPanel());
        prevButton.setOnClickListener(v -> playOffset(-1));
        nextButton.setOnClickListener(v -> playOffset(1));
        retryButton.setOnClickListener(v -> {
            if (currentChannel != null) playChannel(currentChannel, channels.indexOf(currentChannel));
        });
        decodeButton.setOnClickListener(v -> showDecodeDialog());
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        if (sourceButton != null) {
            sourceButton.setOnClickListener(v -> showSourceDialog());
        }
        if (syncButton != null) {
            syncButton.setOnClickListener(v -> showGithubSyncDialog());
        }
        if (castButton != null) {
            castButton.setOnClickListener(v -> showCastDialog());
        }

        // 音量 / 画面比例控制
        if (muteBtn != null) {
            muteBtn.setOnClickListener(v -> {
                if (player == null) return;
                if (player.getVolume() > 0) {
                    lastVolume = player.getVolume();
                    setVolume(0);
                } else {
                    setVolume(lastVolume > 0 ? lastVolume : 100);
                }
                resetHideControls();
            });
        }
        if (volumeSeekBar != null) {
            ColorStateList csl = ColorStateList.valueOf(ThemeManager.getAccessibleThemeColor(this));
            volumeSeekBar.setProgressTintList(csl);
            volumeSeekBar.setThumbTintList(csl);
            volumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) setVolume(progress);
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                    hideHandler.removeCallbacks(hideControlsRunnable);
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    resetHideControls();
                }
            });
        }
        if (aspectBtn != null) {
            aspectBtn.setOnClickListener(v -> showAspectMenu(v));
        }

        // 点击画面切换菜单浮层；抽屉打开时优先关闭抽屉
        View root = findViewById(R.id.rootContainer);
        root.setOnClickListener(v -> {
            if (channelPanel.getVisibility() == View.VISIBLE) {
                channelPanel.setVisibility(View.GONE);
                resetHideControls();
                return;
            }
            toggleControls();
        });

        applySavedHwdec();
        applyPlayerTheme();
    }

    /** 频道抽屉宽度拖拽：右边缘可拉到半个屏幕 */
    private void setupPanelResize() {
        if (panelResizeHandle == null) return;
        panelResizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private int startWidth;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startWidth = channelPanel.getWidth();
                        hideHandler.removeCallbacks(hideControlsRunnable);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        applyPanelWidth(startWidth + Math.round(event.getRawX() - startX));
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        prefs().edit().putFloat(KEY_PANEL_RATIO, panelRatio).apply();
                        hideHandler.postDelayed(hideControlsRunnable, CHANNEL_HIDE_DELAY_MS);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 应用抽屉宽度（自动收敛到屏幕的 1/4 ~ 1/2）并同步拖拽把手位置 */
    private void applyPanelWidth(int widthPx) {
        if (channelPanel == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // 左侧分类 + 右侧频道双层结构，最窄也要留够两列
        int min = Math.round(screenWidth / 3f);
        int max = Math.round(screenWidth / 2f);
        int width = Math.max(min, Math.min(max, widthPx));

        android.view.ViewGroup.LayoutParams params = channelPanel.getLayoutParams();
        if (params != null) {
            params.width = width;
            channelPanel.setLayoutParams(params);
        }
        panelRatio = width / (float) screenWidth;

        if (panelResizeHandle != null) {
            android.view.ViewGroup.MarginLayoutParams handleParams =
                    (android.view.ViewGroup.MarginLayoutParams)
                            panelResizeHandle.getLayoutParams();
            if (handleParams != null) {
                handleParams.leftMargin = dp(12) + width - dp(8);
                panelResizeHandle.setLayoutParams(handleParams);
            }
        }
    }

    /** 播放器控制层与频道抽屉的强调色统一跟随主题色 */
    private void applyPlayerTheme() {
        int accent = ThemeManager.getAccessibleThemeColor(this);
        float density = getResources().getDisplayMetrics().density;

        // 频道抽屉：圆角玻璃卡片 + 主题色描边 + 宽度占屏幕 1/3
        if (channelPanel != null) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xF20B0F14);
            background.setCornerRadius(24 * density);
            background.setStroke(Math.max(1, Math.round(density)), withAlpha(accent, 90));
            channelPanel.setBackground(background);

            panelRatio = prefs().getFloat(KEY_PANEL_RATIO, 0.38f);
            applyPanelWidth(Math.round(getResources().getDisplayMetrics().widthPixels
                    * panelRatio));
        }
        tintIcon(findViewById(R.id.syncButton), accent);
        tintIcon(findViewById(R.id.importButton), accent);
        tintIcon(castButton, accent);
        if (sourceButton != null) {
            sourceButton.setTextColor(accent);
        }
        if (decodeButton != null) {
            decodeButton.setImageTintList(ColorStateList.valueOf(withAlpha(accent, 235)));
        }
    }

    private void tintIcon(View view, int color) {
        if (view instanceof android.widget.ImageView) {
            ((android.widget.ImageView) view).setImageTintList(ColorStateList.valueOf(color));
        }
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void initPlayer() {
        player = new RtspPlayer(this);
        player.setPlayerView(videoView);
        player.setDelegate(this);
    }

    private void loadDefaultPlaylist() {
        // 优先加载上次导入/打开的列表，确保手动添加的列表重启后仍然存在。
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastPath = prefs.getString(KEY_LAST_PLAYLIST, null);
        if (lastPath != null && !lastPath.isEmpty()) {
            File last = new File(lastPath);
            if (last.isFile() && last.canRead()) {
                loadPlaylistFile(last);
                return;
            }
        }

        File external = Environment.getExternalStorageDirectory();
        File[] candidates = {
                new File(external, "44023.txt"),
                new File(external, "Download/44023.txt"),
                new File(external, "备份/电视直播/44023.txt"),
                new File(getExternalFilesDir(null), "44023.txt")
        };
        for (File file : candidates) {
            if (file != null && file.isFile() && file.canRead()) {
                loadPlaylistFile(file);
                return;
            }
        }
        String message = "未找到频道列表\n请点击左上角导入列表文件，或使用 GitHub 云同步";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            message = "未授予“所有文件访问权限”，无法读取根目录\n仍可点击左上角导入列表文件";
        }
        // 没有任何本地列表时，尝试加载上次 GitHub 同步的缓存
        File cached = new File(getFilesDir(), GITHUB_CACHE_NAME);
        if (cached.isFile() && cached.canRead()) {
            loadPlaylistFile(cached);
            return;
        }
        showCenter(message);
        listPathText.setText("未找到列表文件");
        streamStatusText.setText("等待导入频道列表");
        channelCountText.setText("0 个频道");
        // 没有列表：直接把抽屉打开，导入按钮 / 云同步按钮一眼就能看到、点得到
        openChannelPanel();
    }

    private void loadPlaylistFile(File file) {
        loadPlaylist(readFile(file), file.getAbsolutePath());
    }

    private String readFile(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return LivePlaylistParser.readText(input);
        } catch (Exception e) {
            Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return "";
        }
    }

    private void loadPlaylist(String text, String source) {
        List<LiveChannel> parsed = LivePlaylistParser.parse(text);
        if (parsed.isEmpty()) {
            showCenter("列表中没有可播放的频道\n请检查文件格式：频道名,直播地址");
            streamStatusText.setText("列表为空");
            return;
        }
        channels.clear();
        channels.addAll(parsed);
        adapter.setItems(channels);
        buildCategoryTabs();
        currentPath = source;
        listPathText.setText(source == null ? "手动导入" : source);
        if (source != null && !source.isEmpty() && source.startsWith("/")) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_LAST_PLAYLIST, source).apply();
        }
        int count = 0;
        int multiSource = 0;
        for (LiveChannel channel : channels) {
            if (channel.isHeader()) continue;
            count++;
            if (channel.hasMultipleSources()) multiSource++;
        }
        channelCountText.setText(count + " 个频道");
        android.util.Log.d(TAG, "解析列表: 共 " + parsed.size() + " 项 / " + count
                + " 个频道 / " + multiSource + " 个多线路, 来源=" + source);

        int first = firstPlayablePosition();
        if (first >= 0) {
            playChannel(channels.get(first), first);
        }
    }

    private int firstPlayablePosition() {
        for (int i = 0; i < channels.size(); i++) {
            if (!channels.get(i).isHeader()) return i;
        }
        return -1;
    }

    private void playChannel(LiveChannel channel, int position) {
        playChannel(channel, position, false);
    }

    /**
     * 播放指定频道。
     *
     * @param fromAutoSwitch true 表示由「自动换源」触发，保留已换源计数
     */
    private void playChannel(LiveChannel channel, int position, boolean fromAutoSwitch) {
        if (channel == null || channel.isHeader()) return;
        if (!fromAutoSwitch) autoSwitchCount = 0;
        playStartAt = System.currentTimeMillis();
        currentChannel = channel;
        isPaused = false;
        adapter.setSelectedChannel(channel);
        // 当前分类下看不到这个频道时，自动切到它所属的分类标签
        if (adapter.displayIndexOf(channel) < 0) {
            applyCategory(channel.getGroup());
        }
        int displayIndex = adapter.displayIndexOf(channel);
        if (displayIndex >= 0) {
            channelList.scrollToPosition(displayIndex);
        }
        currentChannelText.setText(channel.getName());
        // 恢复该频道上次使用的线路
        int savedSource = prefs().getInt(sourceKey(channel), 0);
        channel.setSelectedSourceIndex(savedSource);
        updateSourceButton(channel);
        streamStatusText.setText(channel.hasMultipleSources()
                ? channel.getSourceLabel() + " 正在连接…" : "正在连接…");
        loadingProgress.setVisibility(View.VISIBLE);
        centerStatusText.setVisibility(View.GONE);
        liveBadge.setVisibility(View.GONE);
        player.setInputUrl(channel.getUrl());
        player.setAudioGain(prefs().getFloat(KEY_AUDIO_GAIN, 1f));
        player.start();
        resetHideControls();
        startInfoUpdates();
    }

    /* ==================== 音量 / 画面比例 / 信息浮层 ==================== */

    private static final RtspPlayerView.UIViewContentMode[] ASPECT_MODES = {
            RtspPlayerView.UIViewContentMode.ScaleAspectFit,   // 适应
            RtspPlayerView.UIViewContentMode.ScaleAspectFill,  // 裁剪
            RtspPlayerView.UIViewContentMode.ScaleToFill       // 拉伸
    };
    private static final String[] ASPECT_NAMES = {"适应（完整显示）", "裁剪（铺满裁边）", "拉伸（变形铺满）"};

    /* ==================== 投屏到电视（DLNA） ==================== */

    private com.networktool.app.cast.SsdpDiscovery castDiscovery;
    private final java.util.List<com.networktool.app.cast.DlnaDevice> castDevices
            = new java.util.ArrayList<>();

    /** 把当前频道投到电视：先搜索设备，再选择 */
    private void showCastDialog() {
        if (currentChannel == null) {
            Toast.makeText(this, "请先播放一个频道", Toast.LENGTH_SHORT).show();
            return;
        }
        final String url = currentChannel.getUrl();
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "当前频道没有可用地址", Toast.LENGTH_SHORT).show();
            return;
        }
        castDevices.clear();

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("正在搜索局域网内的投屏设备…");
        // 弹窗是深色玻璃底，固定浅色文字（浅色主题下 onSurface 会变成近黑色，看不见）
        tip.setTextColor(0xFFE9F1F7);
        box.addView(tip);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(
                ColorStateList.valueOf(ThemeManager.getAccessibleThemeColor(this)));
        android.widget.LinearLayout.LayoutParams barParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = pad / 2;
        box.addView(bar, barParams);

        androidx.appcompat.app.AlertDialog searchDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("投屏到电视")
                .setView(box)
                .setNegativeButton("取消", (dialog, which) -> {
                    if (castDiscovery != null) castDiscovery.stop();
                })
                .create();
        searchDialog.show();

        castDiscovery = new com.networktool.app.cast.SsdpDiscovery(this);
        castDiscovery.start(new com.networktool.app.cast.SsdpDiscovery.Callback() {
            @Override
            public void onDeviceFound(com.networktool.app.cast.DlnaDevice device) {
                runOnUiThread(() -> castDevices.add(device));
            }

            @Override
            public void onFinished() {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        searchDialog.dismiss();
                    } catch (Exception ignored) {
                    }
                    if (castDevices.isEmpty()) {
                        Toast.makeText(LiveTvActivity.this,
                                "未找到投屏设备，请确认电视已开机且与手机同一 WiFi",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    showSelectCastDevice(url);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(LiveTvActivity.this,
                        "搜索异常：" + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showSelectCastDevice(String url) {
        String[] names = new String[castDevices.size()];
        for (int i = 0; i < castDevices.size(); i++) {
            names[i] = castDevices.get(i).getDisplayName();
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择投屏设备")
                .setItems(names, (dialog, which) -> castToDevice(castDevices.get(which), url))
                .setNegativeButton("取消", null)
                .show();
    }

    private void castToDevice(com.networktool.app.cast.DlnaDevice device, String url) {
        String title = currentChannel == null ? "网络工具箱" : currentChannel.getName();
        Toast.makeText(this, "正在投屏到 " + device.getDisplayName() + "…",
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = com.networktool.app.cast.DlnaController.cast(
                    device, url, title, com.networktool.app.cast.DlnaController.MediaType.VIDEO);
            final String result = error;
            runOnUiThread(() -> {
                if (result == null) {
                    Toast.makeText(LiveTvActivity.this,
                            "已投屏到 " + device.getDisplayName(), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(LiveTvActivity.this,
                            "投屏失败：" + result + "\n（部分电视不支持该流格式）",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "dlna-cast").start();
    }

    /* ==================== 多信号源（线路）切换 ==================== */

    private String sourceKey(LiveChannel channel) {
        if (channel == null) return "src_unknown";
        return "src_" + Math.abs((channel.getGroup() + "|" + channel.getName()).hashCode());
    }

    private void updateSourceButton(LiveChannel channel) {
        if (sourceButton == null) return;
        if (channel != null && !channel.isHeader() && channel.hasMultipleSources()) {
            sourceButton.setVisibility(View.VISIBLE);
            sourceButton.setText("线路 " + (channel.getSelectedSourceIndex() + 1) + "/" + channel.getSourceCount());
        } else {
            sourceButton.setVisibility(View.GONE);
        }
    }

    /** 弹出线路选择列表 */
    private void showSourceDialog() {
        if (currentChannel == null || !currentChannel.hasMultipleSources()) {
            Toast.makeText(this, "当前频道只有一个信号源", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = currentChannel.getSourceCount();
        int accent = ThemeManager.getAccessibleThemeColor(this);
        int selectedIndex = currentChannel.getSelectedSourceIndex();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_dialog_card);
        content.setPadding(dp(18), dp(16), dp(18), dp(14));

        TextView title = new TextView(this);
        title.setText(currentChannel.getName() + " · 选择线路");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView hint = new TextView(this);
        hint.setText("共 " + count + " 条线路，点一条立即切换");
        hint.setTextColor(0xA6FFFFFF);
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(4);
        hintParams.bottomMargin = dp(10);
        content.addView(hint, hintParams);

        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        for (int i = 0; i < count; i++) {
            final int index = i;
            boolean active = i == selectedIndex;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(10));
            rowBg.setColor(active ? withAlpha(accent, 0x46) : 0x1AFFFFFF);
            if (active) rowBg.setStroke(dp(1), withAlpha(accent, 0xCC));
            row.setBackground(rowBg);

            TextView rowTitle = new TextView(this);
            rowTitle.setText("线路 " + (i + 1) + (active ? " · 当前使用" : ""));
            rowTitle.setTextColor(active ? accent : 0xFFFFFFFF);
            rowTitle.setTextSize(13);
            rowTitle.setTypeface(active
                    ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            row.addView(rowTitle);

            TextView rowUrl = new TextView(this);
            rowUrl.setText(currentChannel.getUrlAt(i));
            rowUrl.setTextColor(active ? 0xE6FFFFFF : 0xC0FFFFFF);
            rowUrl.setTextSize(10);
            rowUrl.setSingleLine(true);
            rowUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            urlParams.topMargin = dp(3);
            row.addView(rowUrl, urlParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            content.addView(row, rowParams);

            row.setOnClickListener(v -> {
                currentChannel.setSelectedSourceIndex(index);
                prefs().edit().putInt(sourceKey(currentChannel), index).apply();
                updateSourceButton(currentChannel);
                if (holder[0] != null) holder[0].dismiss();
                playCurrentSource();
            });
        }

        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scroll)
                .create();
        holder[0] = dialog;
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        // 线路很多时限制弹窗高度，内部滚动，避免顶满全屏
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        scroll.post(() -> {
            if (scroll.getHeight() > maxHeight) {
                android.view.ViewGroup.LayoutParams params = scroll.getLayoutParams();
                params.height = maxHeight;
                scroll.setLayoutParams(params);
            }
        });
        try {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.TRANSPARENT));
                window.setGravity(android.view.Gravity.CENTER);
                int width = Math.min(dp(520), Math.round(
                        getResources().getDisplayMetrics().widthPixels * 0.55f));
                window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "设置线路弹窗样式失败", e);
        }
    }

    private String shortUrl(String url) {
        if (url == null) return "";
        String value = url;
        int cut = value.indexOf("://");
        if (cut >= 0) value = value.substring(cut + 3);
        return value.length() > 42 ? value.substring(0, 39) + "…" : value;
    }

    /** 用当前线路重新播放（切线路不换频道） */
    private void playCurrentSource() {
        if (currentChannel == null || player == null) return;
        isPaused = false;
        streamStatusText.setText(currentChannel.hasMultipleSources()
                ? currentChannel.getSourceLabel() + " 正在连接…" : "正在连接…");
        loadingProgress.setVisibility(View.VISIBLE);
        centerStatusText.setVisibility(View.GONE);
        liveBadge.setVisibility(View.GONE);
        player.setInputUrl(currentChannel.getUrl());
        player.setAudioGain(prefs().getFloat(KEY_AUDIO_GAIN, 1f));
        player.start();
        startInfoUpdates();
    }

    /* ==================== GitHub 云同步（不内置任何频道数据） ==================== */

    private void showGithubSyncDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String saved = prefs().getString(KEY_GITHUB_URL, DEFAULT_GITHUB_LIST);
        input.setText(saved);
        input.setSelection(saved.length());

        new MaterialAlertDialogBuilder(this)
                .setTitle("GitHub 云同步")
                .setMessage("支持三种写法：\n"
                        + "1. raw 地址（raw.githubusercontent.com/…）\n"
                        + "2. 仓库文件页地址（github.com/…/blob/…）\n"
                        + "3. owner/repo/分支/文件路径\n\n"
                        + "同步后缓存到本地，下次启动自动加载。")
                .setView(input)
                .setPositiveButton("同步", (dialog, which) -> {
                    String address = input.getText().toString().trim();
                    if (address.isEmpty()) return;
                    syncFromGithub(address);
                })
                .setNeutralButton("加载缓存", (dialog, which) -> loadGithubCache())
                .setNegativeButton("取消", null)
                .show();
    }

    private void syncFromGithub(String address) {
        String rawUrl = toRawGithubUrl(address);
        Toast.makeText(this, "正在从 GitHub 同步…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String content = null;
            String error = null;
            // 先直连，失败再走镜像（部分网络环境下 raw.githubusercontent 不可达）
            String[] candidates = {rawUrl, "https://ghfast.top/" + rawUrl};
            for (String candidate : candidates) {
                try {
                    String text = downloadText(candidate);
                    if (text != null && !text.trim().isEmpty()) {
                        content = text;
                        break;
                    }
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + " " + e.getMessage();
                }
            }
            final String result = content;
            final String failure = error;
            runOnUiThread(() -> {
                if (result == null || result.trim().isEmpty()) {
                    Toast.makeText(this, "同步失败：" + (failure == null ? "内容为空" : failure),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                prefs().edit().putString(KEY_GITHUB_URL, address).apply();
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), GITHUB_CACHE_NAME))) {
                    out.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
                loadPlaylist(result, "GitHub 同步 · " + address);
                Toast.makeText(this, "同步完成", Toast.LENGTH_SHORT).show();
            });
        }, "github-playlist-sync").start();
    }

    private String downloadText(String url) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "NetworkToolbox/2.1.0");
        try (InputStream in = conn.getInputStream()) {
            return LivePlaylistParser.readText(in);
        } finally {
            conn.disconnect();
        }
    }

    /** 各种写法统一转成 raw 地址 */
    private String toRawGithubUrl(String address) {
        String value = address.trim();
        if (value.startsWith("http://raw.githubusercontent.com")
                || value.startsWith("https://raw.githubusercontent.com")) {
            return value;
        }
        String marker = "github.com/";
        int index = value.indexOf(marker);
        if (index >= 0) {
            String rest = value.substring(index + marker.length());
            String[] parts = rest.split("/");
            if (parts.length >= 5 && "blob".equals(parts[2])) {
                StringBuilder path = new StringBuilder();
                for (int i = 4; i < parts.length; i++) {
                    if (path.length() > 0) path.append('/');
                    path.append(parts[i]);
                }
                return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1]
                        + "/" + parts[3] + "/" + path;
            }
        }
        String[] parts = value.split("/");
        if (parts.length == 2) {
            // owner/repo → 默认 main 分支下的 44023.txt
            return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/main/44023.txt";
        }
        if (parts.length >= 4 && index < 0) {
            StringBuilder path = new StringBuilder();
            for (int i = 3; i < parts.length; i++) {
                if (path.length() > 0) path.append('/');
                path.append(parts[i]);
            }
            return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1]
                    + "/" + parts[2] + "/" + path;
        }
        return value;
    }

    private void loadGithubCache() {
        File cache = new File(getFilesDir(), GITHUB_CACHE_NAME);
        if (!cache.isFile()) {
            Toast.makeText(this, "暂无缓存，请先同步一次", Toast.LENGTH_SHORT).show();
            return;
        }
        loadPlaylistFile(cache);
        Toast.makeText(this, "已加载本地缓存", Toast.LENGTH_SHORT).show();
    }

    private void setVolume(int value) {
        int target = Math.max(0, Math.min(100, value));
        if (volumeSeekBar != null && volumeSeekBar.getProgress() != target) {
            volumeSeekBar.setProgress(target);
        }
        if (player != null) {
            player.setVolume(target);
        }
        updateMuteIcon(target);
    }

    private void updateMuteIcon(int volume) {
        if (muteBtn == null) return;
        boolean muted = volume <= 0;
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        muteBtn.setImageTintList(ColorStateList.valueOf(muted
                ? ThemeManager.getAccessibleThemeColor(this) : Color.WHITE));
        muteBtn.setContentDescription(muted ? "已静音，点击恢复" : "静音");
    }

    private void applyAspectMode() {
        if (videoView == null) return;
        videoView.setUIViewContentMode(ASPECT_MODES[aspectModeIndex]);
        if (player != null) {
            videoView.setVideoSize(player.getVideoWidth(), player.getVideoHeight());
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
            resetHideControls();
            return true;
        });
        popup.show();
        // 弹窗期间不要自动收起控制层
        hideHandler.removeCallbacks(hideControlsRunnable);
    }

    private void startInfoUpdates() {
        stopInfoUpdates();
        infoRunnable = new Runnable() {
            @Override
            public void run() {
                updateInfoOverlay();
                checkAutoSwitchSource();
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

    /**
     * 自动换源：播放超时还没就绪，就切到下一条线路重试。
     *
     * 只在线路互不相同、且未把该频道的线路全部试过时才换，
     * 避免在几条坏线路之间无限循环。
     */
    private void checkAutoSwitchSource() {
        int limit = prefs().getInt(KEY_AUTO_SWITCH, 0);
        if (limit < AUTO_SWITCH_MIN_SECONDS) return;
        if (currentChannel == null || player == null || playStartAt <= 0L) return;
        if (player.isReady()) {
            playStartAt = 0L;
            return;
        }
        if (System.currentTimeMillis() - playStartAt < limit * 1000L) return;

        playStartAt = 0L;
        int total = currentChannel.getSourceCount();
        if (total <= 1 || autoSwitchCount >= total - 1) {
            loadingProgress.setVisibility(View.GONE);
            centerStatusText.setVisibility(View.VISIBLE);
            centerStatusText.setText(total > 1
                    ? "该频道全部 " + total + " 条线路都连不上\n可手动换台或换个源"
                    : "连不上该频道\n可在设置里切换解码方式后重试");
            streamStatusText.setText("播放失败");
            return;
        }
        if (currentChannel.nextSource()) {
            autoSwitchCount++;
            prefs().edit().putInt(sourceKey(currentChannel),
                    currentChannel.getSelectedSourceIndex()).apply();
            Toast.makeText(this, "播放超时，" + currentChannel.getSourceLabel()
                    + " 自动重试", Toast.LENGTH_SHORT).show();
            playChannel(currentChannel, channels.indexOf(currentChannel), true);
        }
    }

    private void updateInfoOverlay() {
        if (player == null || infoText == null) return;
        int width = 0;
        int height = 0;
        float fps = 0f;
        long bitrate = -1;
        try {
            width = player.getVideoWidth();
            height = player.getVideoHeight();
            fps = player.getVideoFrameRate();
            bitrate = player.getBitrateEstimate();
        } catch (Throwable ignored) {}
        if (videoView != null && width > 0 && height > 0) {
            videoView.setVideoSize(width, height);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(width > 0 && height > 0 ? width + "×" + height : "分辨率未知");
        if (bitrate > 0) sb.append(" · ").append(formatBitrate(bitrate));
        if (fps > 0) sb.append(" · ").append(Math.round(fps)).append("fps");
        infoText.setText(sb.toString());
    }

    private String formatBitrate(long bitsPerSecond) {
        double kbps = bitsPerSecond / 1000d;
        if (kbps >= 1000) {
            return String.format(java.util.Locale.US, "%.1fMbps", kbps / 1000d);
        }
        return String.format(java.util.Locale.US, "%.0fkbps", kbps);
    }

    private void playOffset(int offset) {
        if (channels.isEmpty()) return;
        int position = currentChannel == null ? -1 : channels.indexOf(currentChannel);
        if (position < 0) position = firstPlayablePosition();
        int next = position;
        do {
            next += offset;
            if (next < 0) next = channels.size() - 1;
            if (next >= channels.size()) next = 0;
        } while (next != position && channels.get(next).isHeader());
        if (next >= 0 && next < channels.size() && !channels.get(next).isHeader()) {
            playChannel(channels.get(next), next);
        }
    }

    private void pickPlaylistFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_LIST);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPathDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(currentPath == null
                ? new File(Environment.getExternalStorageDirectory(), "44023.txt").getAbsolutePath()
                : currentPath);
        new MaterialAlertDialogBuilder(this)
                .setTitle("输入列表文件路径")
                .setMessage("例如：/storage/emulated/0/44023.txt")
                .setView(input)
                .setPositiveButton("加载", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) return;
                    File file = new File(path);
                    if (!file.isFile()) {
                        Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    loadPlaylistFile(file);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_LIST || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            String content = LivePlaylistParser.readText(input);
            // SAF 的临时授权可能在重启后失效，复制到应用私有目录以便持久化。
            File imported = new File(getFilesDir(), IMPORTED_PLAYLIST_NAME);
            try (FileOutputStream output = new FileOutputStream(imported)) {
                output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            loadPlaylistFile(imported);
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "导入直播列表失败", e);
            Toast.makeText(this, "读取列表失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onEventCallback(RtspPlayer player, int event, String message) {
        runOnUiThread(() -> {
            if (event == RtspPlayer.EVENT_CONNECTING) {
                streamStatusText.setText(message == null ? "正在连接…" : message);
                loadingProgress.setVisibility(View.VISIBLE);
            } else if (event == RtspPlayer.EVENT_CONNECTED) {
                streamStatusText.setText("已连接");
            } else if (event == RtspPlayer.EVENT_PLAYING) {
                loadingProgress.setVisibility(View.GONE);
                centerStatusText.setVisibility(View.GONE);
                streamStatusText.setText("正在播放");
                liveBadge.setVisibility(View.VISIBLE);
            } else if (event == RtspPlayer.EVENT_ERROR) {
                loadingProgress.setVisibility(View.GONE);
                streamStatusText.setText(message == null ? "播放失败" : message);
                showCenter(message == null ? "播放失败" : message);
                liveBadge.setVisibility(View.GONE);
            } else if (event == RtspPlayer.EVENT_STOPPED) {
                loadingProgress.setVisibility(View.GONE);
                liveBadge.setVisibility(View.GONE);
                streamStatusText.setText("已停止");
            }
        });
    }

    /* ==================== 顶部分类标签栏 ==================== */

    /** 依据列表里的分组重建分类标签（全部 / CCTV / 卫视 …） */
    private void buildCategoryTabs() {
        if (categoryTabs == null) return;
        categoryTabs.removeAllViews();
        for (String group : adapter.getGroups()) {
            TextView tab = new TextView(this);
            tab.setText(group);
            tab.setTextSize(12.5f);
            tab.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(10), dp(11), dp(8), dp(11));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(5);
            tab.setLayoutParams(params);
            tab.setTag(group);
            tab.setOnClickListener(v -> {
                Object tag = v.getTag();
                selectCategory(tag == null ? LiveChannelAdapter.GROUP_ALL : tag.toString());
            });
            categoryTabs.addView(tab);
        }
        applyCategory(adapter.getGroup());
        android.util.Log.d(TAG, "分类标签: " + adapter.getGroups()
                + " 当前=" + adapter.getGroup() + " 可见频道=" + adapter.getItemCount());
    }

    /** 用户点击分类标签 */
    private void selectCategory(String group) {
        applyCategory(group);
        android.util.Log.d(TAG, "切换分类: " + group + " 可见频道=" + adapter.getItemCount());
        if (channelList != null) channelList.scrollToPosition(0);
        postponeHide();
    }

    /** 切分类并刷新标签样式（不滚动） */
    private void applyCategory(String group) {
        adapter.setGroup(group);
        updateCategoryTabStyles();
        if (categoryScroll != null) categoryScroll.setVisibility(View.VISIBLE);
    }

    private void updateCategoryTabStyles() {
        if (categoryTabs == null) return;
        int accent = ThemeManager.getAccessibleThemeColor(this);
        String current = adapter.getGroup();
        for (int i = 0; i < categoryTabs.getChildCount(); i++) {
            View child = categoryTabs.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tab = (TextView) child;
            boolean active = current.equals(tab.getTag());
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(9));
            background.setColor(active ? withAlpha(accent, 0x46) : 0x00000000);
            if (active) {
                background.setStroke(dp(1), withAlpha(accent, 0xC0));
            }
            tab.setBackground(background);
            tab.setTextColor(active ? accent : 0xCCFFFFFF);
            tab.setTypeface(active
                    ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    private void showCenter(String text) {
        centerStatusText.setText(text);
        centerStatusText.setVisibility(View.VISIBLE);
    }

    /** 唤出控制浮层，并在数秒后自动隐藏 */
    /**
     * 窗口内任意触摸都算「有操作」：重置自动隐藏计时。
     *
     * 之前只监听了 RecyclerView 本身，子项（分类标题、频道行）会吃事件，
     * 所以点着列表也会被自动隐藏。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN
                || action == android.view.MotionEvent.ACTION_MOVE
                || action == android.view.MotionEvent.ACTION_UP) {
            postponeHide();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 只重置计时，不改变控制层可见性（避免每次触摸都强制呼出控制条） */
    private void postponeHide() {
        if (controlsVisible) {
            hideHandler.removeCallbacks(hideControlsRunnable);
            if (!channels.isEmpty()) {
                hideHandler.postDelayed(hideControlsRunnable,
                        channelPanel.getVisibility() == View.VISIBLE
                                ? CHANNEL_HIDE_DELAY_MS : CONTROLS_HIDE_DELAY_MS);
            }
        }
    }

    private void resetHideControls() {
        showControls();
    }

    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        controlsVisible = true;
        playerTopBar.setVisibility(View.VISIBLE);
        playerBottomBar.setVisibility(View.VISIBLE);
        // 中央播放/暂停按钮：有频道在播时显示
        if (currentChannel != null) {
            playPauseButton.setImageResource(isPaused ? R.drawable.ic_play : R.drawable.ic_pause);
            playPauseButton.setVisibility(View.VISIBLE);
        }
        hideHandler.removeCallbacks(hideControlsRunnable);
        // 列表为空（还没导入）时保持控制层常显，保证导入入口一直点得到
        if (channels.isEmpty()) return;
        // 频道抽屉打开时用 5 秒无操作自动收起，避免挡住画面
        hideHandler.postDelayed(hideControlsRunnable,
                channelPanel.getVisibility() == View.VISIBLE
                        ? CHANNEL_HIDE_DELAY_MS : CONTROLS_HIDE_DELAY_MS);
    }

    /** 单独控制左侧频道抽屉的显示/隐藏 */
    private void toggleChannelPanel() {
        // 没有频道列表时也要能打开抽屉，否则导入/云同步按钮点不到
        if (channelPanel.getVisibility() == View.VISIBLE) {
            channelPanel.setVisibility(View.GONE);
            setPanelResizeHandleVisible(false);
            resetHideControls();
        } else {
            openChannelPanel();
        }
    }

    /** 展开频道抽屉（列表为空时同样可用，用来导入或云同步） */
    private void openChannelPanel() {
        playerTopBar.setVisibility(View.VISIBLE);
        playerBottomBar.setVisibility(View.VISIBLE);
        controlsVisible = true;
        channelPanel.setVisibility(View.VISIBLE);
        setPanelResizeHandleVisible(true);
        hideHandler.removeCallbacks(hideControlsRunnable);
        // 列表为空时不自动收起，避免正在导入时抽屉自己收走
        if (!channels.isEmpty()) {
            hideHandler.postDelayed(hideControlsRunnable, CHANNEL_HIDE_DELAY_MS);
        }
    }

    private void hideControls() {
        controlsVisible = false;
        hideHandler.removeCallbacks(hideControlsRunnable);
        playerTopBar.setVisibility(View.GONE);
        playerBottomBar.setVisibility(View.GONE);
        channelPanel.setVisibility(View.GONE);
        setPanelResizeHandleVisible(false);
        playPauseButton.setVisibility(View.GONE);
    }

    /** 抽屉打开时显示右侧拖拽把手，关闭时隐藏 */
    private void setPanelResizeHandleVisible(boolean visible) {
        if (panelResizeHandle == null) return;
        panelResizeHandle.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 中央播放/暂停切换 */
    private void togglePlayPause() {
        if (player == null || currentChannel == null) return;
        if (isPaused) {
            player.start();
            isPaused = false;
            playPauseButton.setImageResource(R.drawable.ic_pause);
            streamStatusText.setText("正在播放");
        } else {
            player.pause();
            isPaused = true;
            playPauseButton.setImageResource(R.drawable.ic_play);
            streamStatusText.setText("已暂停");
        }
        // 保持控制层显示，方便连续操作
        showControls();
    }

    /** 播放设置：卡片式点选（解码 / 渲染 / 音量增强），横屏双列居中 */
    private void showDecodeDialog() {
        try {
            View view = getLayoutInflater().inflate(R.layout.dialog_player_settings, null);
            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setView(view)
                    .create();

            LinearLayout container = view.findViewById(R.id.settingsContent);
            int accent = ThemeManager.getAccessibleThemeColor(this);

            // 横屏用双列：左列解码 + 渲染，右列音量增强，充分利用宽度
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            columns.setBaselineAligned(false);
            container.addView(columns, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout leftColumn = new LinearLayout(this);
            leftColumn.setOrientation(LinearLayout.VERTICAL);
            columns.addView(leftColumn, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout rightColumn = new LinearLayout(this);
            rightColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            rightParams.leftMargin = dp(14);
            columns.addView(rightColumn, rightParams);

            float currentGain = prefs().getFloat(KEY_AUDIO_GAIN, 1f);
            String gainValue = currentGain < 1.25f ? "1.0"
                    : (currentGain < 1.75f ? "1.5" : (currentGain < 2.5f ? "2.0" : "3.0"));

            String currentDecode = player != null ? player.getHwdec()
                    : prefs().getString(KEY_HWDEC, "mediacodec");

            addSettingSection(leftColumn, "解码方式", accent);
            addSettingGroup(leftColumn, accent, true,
                    new String[]{"mediacodec", "no"},
                    new String[]{"硬件解码", "软件解码"},
                    new String[]{"MediaCodec 硬解，省电、流畅", "纯软件解码，兼容性最好"},
                    currentDecode,
                    value -> {
                        applyHwdec(value);
                        Toast.makeText(this, "解码方式已切换，重载后生效", Toast.LENGTH_SHORT).show();
                    });

            addSettingSection(leftColumn, "渲染方式", accent);
            addSettingGroup(leftColumn, accent, true,
                    new String[]{"textureview", "surfaceview"},
                    new String[]{"TextureView", "SurfaceView"},
                    new String[]{"默认，兼容性最好、画面可叠加动画", "性能更好，直出画面更省电"},
                    prefs().getString(KEY_RENDER_TYPE, "textureview"),
                    value -> {
                        applyRenderType(value);
                        Toast.makeText(this, "渲染方式已切换，重载后生效", Toast.LENGTH_SHORT).show();
                    });

            addSettingSection(rightColumn, "音量增强（直播源本身偏小时打开）", accent);
            addSettingGroup(rightColumn, accent, true,
                    new String[]{"1.0", "1.5", "2.0", "3.0"},
                    new String[]{"100%", "150%", "200%", "300%"},
                    new String[]{"原样，不做任何处理", "轻微提升，几乎无失真",
                            "推荐，明显变响", "最大，可能出现破音"},
                    gainValue,
                    value -> {
                        applyAudioGain(Float.parseFloat(value));
                        Toast.makeText(this, "音量增强：" + Math.round(Float.parseFloat(value) * 100)
                                + "%", Toast.LENGTH_SHORT).show();
                    });

            // 自动换源：整行铺开，最低 5 秒起
            int autoSwitch = prefs().getInt(KEY_AUTO_SWITCH, 0);
            addSettingSection(container, "自动换源（超过设定时间还没出画面，自动切下一条线路）", accent);
            addSettingGroup(container, accent, false,
                    new String[]{"0", "5", "8", "10", "15", "30"},
                    new String[]{"关闭", "5 秒", "8 秒", "10 秒", "15 秒", "30 秒"},
                    new String[]{"不自动换源", "最快", "", "", "", "最稳"},
                    String.valueOf(autoSwitch),
                    value -> {
                        int seconds = Integer.parseInt(value);
                        prefs().edit().putInt(KEY_AUTO_SWITCH, seconds).apply();
                        autoSwitchCount = 0;
                        playStartAt = seconds >= AUTO_SWITCH_MIN_SECONDS
                                ? System.currentTimeMillis() : 0L;
                        Toast.makeText(this, seconds >= AUTO_SWITCH_MIN_SECONDS
                                ? "自动换源：" + seconds + " 秒" : "已关闭自动换源",
                                Toast.LENGTH_SHORT).show();
                    });

            dialog.show();
            try {
                android.view.Window window = dialog.getWindow();
                if (window != null) {
                    // 去掉系统默认白边与阴影，只保留自定义圆角卡片
                    window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
                    window.setGravity(android.view.Gravity.CENTER);
                    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                    int width = Math.min(dp(860), Math.round(metrics.widthPixels * 0.8f));
                    window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "打开播放设置面板失败", e);
            Toast.makeText(this, "打开设置失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 设置面板的小节标题 */
    private void addSettingSection(LinearLayout parent, String title, int accent) {
        TextView textView = new TextView(this);
        textView.setText(title);
        textView.setTextSize(12);
        textView.setTextColor(accent);
        textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        parent.addView(textView, params);
    }

    /** 点选式选项卡片组：stacked=true 纵向排列，false 横向等分（适合百分比之类） */
    private void addSettingGroup(LinearLayout parent, int accent, boolean stacked,
                                 final String[] values, String[] titles, String[] hints,
                                 String current, final OnSettingSelected onSelected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        parent.addView(row, rowParams);

        final java.util.List<View> cards = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            View card = getLayoutInflater().inflate(R.layout.item_player_option, row, false);
            ((TextView) card.findViewById(R.id.optionTitle)).setText(titles[i]);
            TextView hintView = card.findViewById(R.id.optionHint);
            if (hints[i] == null || hints[i].isEmpty()) {
                hintView.setVisibility(View.GONE);
            } else {
                hintView.setText(hints[i]);
            }
            LinearLayout.LayoutParams params = stacked
                    ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0 && stacked) params.topMargin = dp(8);
            if (i > 0 && !stacked) params.leftMargin = dp(8);
            row.addView(card, params);
            cards.add(card);
        }

        final String[] selected = {current == null ? "" : current};
        final Runnable refresh = () -> {
            for (int i = 0; i < cards.size(); i++) {
                styleSettingCard(cards.get(i), accent, values[i].equals(selected[0]));
            }
        };
        for (int i = 0; i < cards.size(); i++) {
            final int index = i;
            cards.get(i).setOnClickListener(v -> {
                selected[0] = values[index];
                refresh.run();
                if (onSelected != null) onSelected.onSettingSelected(values[index]);
            });
        }
        refresh.run();
    }

    /** 选项卡片选中/未选中样式 */
    private void styleSettingCard(View card, int accent, boolean selected) {
        com.google.android.material.card.MaterialCardView materialCard =
                (com.google.android.material.card.MaterialCardView) card;
        TextView title = card.findViewById(R.id.optionTitle);
        TextView hint = card.findViewById(R.id.optionHint);
        if (selected) {
            materialCard.setStrokeColor(accent);
            materialCard.setStrokeWidth(dp(2));
            materialCard.setCardBackgroundColor(withAlpha(accent, 36));
            title.setTextColor(accent);
            hint.setTextColor(withAlpha(accent, 215));
        } else {
            materialCard.setStrokeColor(0x26FFFFFF);
            materialCard.setStrokeWidth(Math.max(1, dp(1)));
            materialCard.setCardBackgroundColor(0x14FFFFFF);
            title.setTextColor(0xFFECF3F8);
            hint.setTextColor(0xFF9FB2C4);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 设置项点选回调（避免使用 java.util.function，兼容 minSdk 21） */
    private interface OnSettingSelected {
        void onSettingSelected(String value);
    }

    private void applySavedHwdec() {
        SharedPreferences prefs = prefs();
        String saved = prefs.getString(KEY_HWDEC, "mediacodec");
        applyHwdec(saved);
        String savedRender = prefs.getString(KEY_RENDER_TYPE, "textureview");
        applyRenderType(savedRender);
        applyAudioGain(prefs.getFloat(KEY_AUDIO_GAIN, 1f));
    }

    /** 设置音量增强倍数（1.0 / 1.5 / 2.0 / 3.0），即时生效 */
    private void applyAudioGain(float gain) {
        prefs().edit().putFloat(KEY_AUDIO_GAIN, gain).apply();
        if (player != null) {
            player.setAudioGain(gain);
        }
    }

    private void applyHwdec(String hwdec) {
        if (player != null) player.setHwdec(hwdec);
        prefs().edit().putString(KEY_HWDEC, hwdec).apply();
    }

    private void applyRenderType(String renderType) {
        if (player != null) {
            player.setRenderType("textureview".equals(renderType)
                    ? RtspPlayerView.RenderType.TEXTUREVIEW
                    : RtspPlayerView.RenderType.SURFACEVIEW);
        }
        prefs().edit().putString(KEY_RENDER_TYPE, renderType).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 部分机型切回前台 / 下拉通知后系统栏会重新出现，这里再隐藏一次，避免全屏出现黑边
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        hideHandler.removeCallbacks(hideControlsRunnable);
        stopInfoUpdates();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (player != null && currentChannel != null) {
            player.start();
        }
    }

    /** 返回主页（而不是直接退出应用） */
    private void backToMain() {
        try {
            Intent intent = new Intent(this,
                    com.networktool.app.activity.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception e) {
            AppErrorLogger.logError(TAG, "返回主页失败", e);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (channelPanel != null && channelPanel.getVisibility() == View.VISIBLE) {
            channelPanel.setVisibility(View.GONE);
            setPanelResizeHandleVisible(false);
            resetHideControls();
            return;
        }
        backToMain();
    }

    @Override
    protected void onDestroy() {
        hideHandler.removeCallbacks(hideControlsRunnable);
        stopInfoUpdates();
        if (castDiscovery != null) {
            castDiscovery.stop();
            castDiscovery = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
