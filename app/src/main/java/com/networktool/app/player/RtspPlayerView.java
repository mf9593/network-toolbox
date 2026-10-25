package com.networktool.app.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ExoPlayer 的视频承载层。
 *
 * 支持两种渲染模式：
 * - SURFACEVIEW：SurfaceView（默认），独立窗口，性能更好但可能有 z-order 问题
 * - TEXTUREVIEW：TextureView，渲染在 Activity 窗口内，兼容性好但稍耗资源
 *
 * 用户可在直播页设置中切换。
 */
public class RtspPlayerView extends android.widget.FrameLayout {

    public enum RenderType { SURFACEVIEW, TEXTUREVIEW }
    public enum UIViewContentMode { ScaleToFill, ScaleAspectFit, ScaleAspectFill }

    // 默认 TextureView：SurfaceView 独立窗口在部分布局下会被遮挡
    private RenderType renderType = RenderType.TEXTUREVIEW;
    private SurfaceView surfaceView;
    private TextureView textureView;
    private SurfaceHolder.Callback surfaceCallback;
    private SurfaceHolder currentHolder;
    private TextureView.SurfaceTextureListener textureListener;

    /** 画面比例模式：默认按原始比例完整显示（不拉伸变形） */
    private UIViewContentMode contentMode = UIViewContentMode.ScaleAspectFit;
    private int videoWidth;
    private int videoHeight;

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
        setBackgroundColor(Color.BLACK);
        setKeepScreenOn(true);

        // SurfaceView
        surfaceView = new SurfaceView(getContext());
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.setVisibility(GONE);
        addView(surfaceView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // TextureView
        textureView = new TextureView(getContext());
        textureView.setVisibility(VISIBLE);
        addView(textureView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // TextureView 监听器
        textureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface,
                    int width, int height) {
                Surface s = new Surface(surface);
                currentHolder = new SurfaceHolder() {
                    private final Surface surf = s;
                    private final int w = width, h = height;

                    @Override public void addCallback(SurfaceHolder.Callback callback) {}
                    @Override public void removeCallback(SurfaceHolder.Callback callback) {}
                    @Override public Surface getSurface() { return surf; }
                    @Override public void setFixedSize(int w, int h) {}
                    @Override public void setFormat(int format) {}
                    @Override public void setKeepScreenOn(boolean keepScreenOn) {}
                    @Override public android.graphics.Rect getSurfaceFrame() {
                        return new android.graphics.Rect(0, 0, w, h);
                    }
                    @Override public Canvas lockCanvas(android.graphics.Rect rect) { return null; }
                    @Override public Canvas lockCanvas() { return null; }
                    @Override public void unlockCanvasAndPost(Canvas canvas) {}
                    @Override public void setSizeFromLayout() {}
                    @Override public void setType(int type) {}
                    @Override public boolean isCreating() { return false; }
                };
                if (surfaceCallback != null) {
                    surfaceCallback.surfaceCreated(currentHolder);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface,
                    int width, int height) {
                if (surfaceCallback != null && currentHolder != null) {
                    surfaceCallback.surfaceChanged(currentHolder, 0, width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                if (surfaceCallback != null && currentHolder != null) {
                    surfaceCallback.surfaceDestroyed(currentHolder);
                }
                currentHolder = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {}
        };

        textureView.setSurfaceTextureListener(textureListener);

        // 默认使用 TextureView
        setRenderType(RenderType.TEXTUREVIEW);
    }

    /**
     * 切换渲染类型。
     * 只切换可见性，Surface 的创建/销毁由系统回调自然触发：
     * - SurfaceView GONE → surfaceDestroyed，VISIBLE → surfaceCreated
     * - TextureView GONE → onSurfaceTextureDestroyed，VISIBLE → onSurfaceTextureAvailable
     */
    public void setRenderType(RenderType type) {
        if (type == renderType) return;
        renderType = type;

        if (type == RenderType.SURFACEVIEW) {
            textureView.setVisibility(GONE);
            surfaceView.setVisibility(VISIBLE);
        } else {
            surfaceView.setVisibility(GONE);
            textureView.setVisibility(VISIBLE);
        }
    }

    public RenderType getRenderType() {
        return renderType;
    }

    /**
     * 获取当前有效的 SurfaceHolder。
     * SurfaceView 优先取 holder 的有效 Surface，TextureView 取缓存的包装 holder。
     */
    @Nullable
    public SurfaceHolder getCurrentHolder() {
        if (renderType == RenderType.SURFACEVIEW) {
            SurfaceHolder h = surfaceView.getHolder();
            Surface s = h.getSurface();
            if (s != null && s.isValid()) return h;
        }
        return currentHolder;
    }

    public void setUIViewContentMode(UIViewContentMode mode) {
        if (mode == null) {
            return;
        }
        if (mode == contentMode) {
            applyContentMode();
            return;
        }
        contentMode = mode;
        applyContentMode();
    }

    public UIViewContentMode getUIViewContentMode() {
        return contentMode;
    }

    /**
     * 设置视频原始尺寸（由播放器回调提供）。
     * 用于「适应 / 裁剪」比例计算，未设置时退化为铺满。
     */
    public void setVideoSize(int width, int height) {
        if (width == videoWidth && height == videoHeight) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        applyContentMode();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyContentMode();
    }

    /**
     * 按当前比例模式调整两个渲染视图的布局尺寸。
     * 用「视图尺寸」而非矩阵变换实现，SurfaceView / TextureView 都生效。
     */
    private void applyContentMode() {
        int parentW = getWidth();
        int parentH = getHeight();
        if (parentW <= 0 || parentH <= 0) {
            return;
        }
        int childW = parentW;
        int childH = parentH;

        if (videoWidth > 0 && videoHeight > 0 && contentMode != UIViewContentMode.ScaleToFill) {
            double videoAspect = (double) videoWidth / (double) videoHeight;
            double parentAspect = (double) parentW / (double) parentH;
            if (contentMode == UIViewContentMode.ScaleAspectFit) {
                if (videoAspect > parentAspect) {
                    childW = parentW;
                    childH = (int) (parentW / videoAspect);
                } else {
                    childH = parentH;
                    childW = (int) (parentH * videoAspect);
                }
            } else { // ScaleAspectFill：铺满并裁剪溢出部分
                if (videoAspect > parentAspect) {
                    childH = parentH;
                    childW = (int) (parentH * videoAspect);
                } else {
                    childW = parentW;
                    childH = (int) (parentW / videoAspect);
                }
            }
        }
        childW = Math.max(childW, 1);
        childH = Math.max(childH, 1);
        applyChildLayout(surfaceView, childW, childH);
        applyChildLayout(textureView, childW, childH);
    }

    private void applyChildLayout(View child, int width, int height) {
        if (child == null) {
            return;
        }
        android.view.ViewGroup.LayoutParams current = child.getLayoutParams();
        if (current instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) current;
            if (lp.width == width && lp.height == height) {
                return;
            }
            lp.width = width;
            lp.height = height;
            lp.gravity = android.view.Gravity.CENTER;
            child.setLayoutParams(lp);
        } else {
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(width, height);
            lp.gravity = android.view.Gravity.CENTER;
            child.setLayoutParams(lp);
        }
    }

    /**
     * 设置 Surface 生命周期回调。
     * 兼容 SurfaceView 和 TextureView。
     */
    public void setSurfaceCallback(@Nullable SurfaceHolder.Callback callback) {
        this.surfaceCallback = callback;

        // SurfaceView 回调
        surfaceView.getHolder().removeCallback(surfaceViewCallback);
        surfaceView.getHolder().addCallback(surfaceViewCallback);

        // 如果 Surface 已存在，立即通知
        if (callback != null) {
            Surface s = surfaceView.getHolder().getSurface();
            if (s != null && s.isValid() && renderType == RenderType.SURFACEVIEW) {
                callback.surfaceCreated(surfaceView.getHolder());
                currentHolder = surfaceView.getHolder();
            } else if (textureView.isAvailable() && renderType == RenderType.TEXTUREVIEW) {
                // TextureView 已可用，重新触发
                if (textureView.getSurfaceTexture() != null) {
                    Surface surface = new Surface(textureView.getSurfaceTexture());
                    textureListener.onSurfaceTextureAvailable(
                            textureView.getSurfaceTexture(),
                            getWidth(), getHeight());
                }
            }
        }
    }

    private final SurfaceHolder.Callback surfaceViewCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            currentHolder = holder;
            if (surfaceCallback != null) surfaceCallback.surfaceCreated(holder);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            if (surfaceCallback != null) surfaceCallback.surfaceChanged(holder, format, width, height);
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            if (currentHolder == holder) currentHolder = null;
            if (surfaceCallback != null) surfaceCallback.surfaceDestroyed(holder);
        }
    };
}
