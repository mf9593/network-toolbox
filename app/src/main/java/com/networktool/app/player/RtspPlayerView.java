package com.networktool.app.player;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * 兼容旧播放器视图调用方式的 Media3 RTSP 播放视图。
 */
public class RtspPlayerView extends PlayerView {

    public enum RenderType {
        SURFACEVIEW,
        TEXTUREVIEW
    }

    public enum UIViewContentMode {
        ScaleToFill,
        ScaleAspectFit,
        ScaleAspectFill
    }

    public RtspPlayerView(@NonNull Context context) {
        super(context);
        init();
    }

    public RtspPlayerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RtspPlayerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setUseController(false);
        setKeepContentOnPlayerReset(true);
        setShutterBackgroundColor(Color.BLACK);
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    /** 渲染类型由 Media3 自动选择，保留方法以兼容旧调用。 */
    public void setRenderType(RenderType renderType) {
        // Media3 PlayerView 默认使用 SurfaceView；需要时可切换为 TextureView。
    }

    public void setUIViewContentMode(UIViewContentMode mode) {
        int resizeMode = mode == UIViewContentMode.ScaleToFill
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT;
        setResizeMode(resizeMode);
    }
}
