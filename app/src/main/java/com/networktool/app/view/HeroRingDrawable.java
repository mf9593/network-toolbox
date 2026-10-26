package com.networktool.app.view;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * 主页 Hero 卡片背景：左侧星空 + 右侧旋转星环 + 主题色渐变。
 *
 * <p>左侧是一组会呼吸闪烁的星点，右侧是几道缓慢自转的圆环弧段和一颗绕环运行的小星，
 * 颜色全部跟随主题色，不写死。动画由 {@link ValueAnimator} 驱动，随可见性自动启停。</p>
 */
public class HeroRingDrawable extends Drawable {

    /** 一圈动画时长：越长时间动得越慢越耐看 */
    private static final long LOOP_DURATION_MS = 16000L;
    /** 星点数量 */
    private static final int STAR_COUNT = 30;
    /** 星空每圈动画自转多少圈（与星环同向） */
    private static final float STAR_ROTATION_TURNS = 0.22f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final int startColor;
    private final int endColor;
    private final float cornerRadius;
    private final float density;

    /** 星点：归一化坐标（相对控件宽高）、半径、亮度、闪烁速度、相位 */
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starRadius = new float[STAR_COUNT];
    private final float[] starAlpha = new float[STAR_COUNT];
    private final float[] starSpeed = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];

    private ValueAnimator animator;
    /** 0..1 的循环进度 */
    private float phase;

    public HeroRingDrawable(int startColor, int endColor, float cornerRadiusPx, float density) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.cornerRadius = cornerRadiusPx;
        this.density = density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, density * 1.3f));
        ringPaint.setColor(0xFFFFFFFF);
        starPaint.setColor(0xFFFFFFFF);

        initStars();
    }

    private void initStars() {
        Random random = new Random(20260210L);
        for (int i = 0; i < STAR_COUNT; i++) {
            // 星星集中在左侧，右侧留给星环
            starX[i] = 0.04f + random.nextFloat() * 0.56f;
            starY[i] = 0.12f + random.nextFloat() * 0.76f;
            starRadius[i] = (0.6f + random.nextFloat() * 1.1f) * density;
            starAlpha[i] = 0.20f + random.nextFloat() * 0.55f;
            starSpeed[i] = 0.6f + random.nextFloat() * 1.6f;
            starPhase[i] = random.nextFloat();
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds);
        float w = Math.max(bounds.width(), 1);
        float h = Math.max(bounds.height(), 1);

        fillPaint.setShader(new LinearGradient(0, 0, w, h, startColor, endColor, Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(
                w * 0.88f, h * 0.04f, Math.max(w * 0.62f, 1f),
                new int[]{0x30FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        int save = canvas.save();
        canvas.clipRect(rect);

        // 渐变底 + 右上角光晕
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint);

        drawStars(canvas);
        drawRings(canvas);

        canvas.restoreToCount(save);
    }

    /**
     * 左侧星空：整片星空跟着右侧星环同向缓慢旋转（每颗星转速略有差异，产生视差），
     * 同时按自己的速度与相位呼吸闪烁。
     */
    private void drawStars(Canvas canvas) {
        float pivotX = rect.left + rect.width() * 0.34f;
        float pivotY = rect.top + rect.height() * 0.50f;

        for (int i = 0; i < STAR_COUNT; i++) {
            float wave = (float) Math.sin(2 * Math.PI * (phase * starSpeed[i] + starPhase[i]));
            float alpha = starAlpha[i] * (0.45f + 0.55f * (0.5f + 0.5f * wave));
            int alphaInt = (int) (alpha * 255);
            if (alphaInt <= 2) continue;

            // 与星环同向自转：相位差 0.7~1.15 倍，靠近中心的星转得慢一点
            float baseX = rect.left + rect.width() * starX[i];
            float baseY = rect.top + rect.height() * starY[i];
            float dx = baseX - pivotX;
            float dy = baseY - pivotY;
            double angle = 2 * Math.PI * phase * STAR_ROTATION_TURNS * (0.7f + 0.45f * starPhase[i]);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            float x = pivotX + (float) (dx * cos - dy * sin);
            float y = pivotY + (float) (dx * sin + dy * cos);

            starPaint.setAlpha(Math.min(alphaInt, 255));
            canvas.drawCircle(x, y, starRadius[i], starPaint);
        }
    }

    /** 右侧星环：几道围绕右上角自转的弧段 + 一颗绕环运行的小星 */
    private void drawRings(Canvas canvas) {
        float cx = rect.right - rect.width() * 0.04f;
        float cy = rect.top + rect.height() * 0.02f;
        float base = rect.height() * 0.58f;

        float[] scales = {0.72f, 1.06f, 1.44f};
        int[] alphas = {0x5C, 0x38, 0x20};
        float[] sweeps = {58f, 42f, 28f};
        float[] cycles = {1.15f, -1.75f, 0.75f};
        float[] origin = {20f, 150f, 265f};

        for (int i = 0; i < scales.length; i++) {
            ringPaint.setAlpha(alphas[i]);
            float radius = base * scales[i];
            float startAngle = origin[i] + 360f * cycles[i] * phase;
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                    startAngle, sweeps[i], false, ringPaint);
            // 对面对称再来一段，转起来更有「环」的层次
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                    startAngle + 180f, sweeps[i] * 0.6f, false, ringPaint);
        }

        // 绕环运行的小星 + 一圈柔光
        float orbitRadius = base * scales[1];
        double theta = 2 * Math.PI * phase;
        float px = cx + (float) (orbitRadius * Math.cos(theta));
        float py = cy + (float) (orbitRadius * Math.sin(theta));
        starPaint.setAlpha(0x2A);
        canvas.drawCircle(px, py, density * 5f, starPaint);
        starPaint.setAlpha(0xE6);
        canvas.drawCircle(px, py, density * 1.8f, starPaint);
    }

    // ==================== 动画 ====================

    private void ensureAnimator() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            Object value = animation.getAnimatedValue();
            phase = value instanceof Float ? (Float) value : 0f;
            invalidateSelf();
        });
    }

    /** 开始动画（页面可见时调用） */
    public void start() {
        ensureAnimator();
        if (!animator.isStarted()) {
            animator.start();
        }
    }

    /** 停止动画（页面不可见时调用，省电） */
    public void stop() {
        if (animator != null && animator.isStarted()) {
            animator.cancel();
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) {
            start();
        } else {
            stop();
        }
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {
        // 背景透明度由渐变与各元素自身 alpha 控制，这里忽略
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        // 不支持色彩滤镜
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
