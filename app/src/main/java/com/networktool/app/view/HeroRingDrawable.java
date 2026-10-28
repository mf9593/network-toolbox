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
 * 主页 Hero 卡片背景：整片星空 + 右侧旋转星环 + 主题色渐变。
 *
 * <p>星空是均匀铺满整张卡片的星点（小星为主、每隔几颗来一颗带柔光的亮星），
 * 整片星空<b>以右上角为圆心</b>缓慢往复微旋（和星环同一个圆心，方向一致），
 * 每颗星幅度略有差异产生视差，同时按自己的速度与相位呼吸闪烁，
 * 所有周期量都取整数圈，循环衔接处不会跳变，
 * 形成「繁星点点」的效果。颜色全部跟随主题色，不写死。动画由 {@link ValueAnimator}
 * 驱动，随可见性自动启停。</p>
 */
public class HeroRingDrawable extends Drawable {

    /** 一圈动画时长：越长时间动得越慢越耐看 */
    private static final long LOOP_DURATION_MS = 16000L;
    /** 星点数量（繁星点点，太少了看不出星空） */
    private static final int STAR_COUNT = 150;
    /**
     * 星空绕右上角的微旋幅度（度）。
     *
     * <p>必须做成「一圈动画往复一次」的正弦摆动：星环圆心在卡片右上角，
     * 卡片只覆盖它的左下两个象限，整片星空真转满 360° 会有一大半时间飞出画面；
     * 而正弦在相位 0 和 1 处取值与斜率都相等，循环衔接处不会出现跳变（重置感）。</p>
     */
    private static final float STAR_SWING_DEG = 16f;
    /** 每隔几颗星安排一颗带柔光的亮星 */
    private static final int BRIGHT_STAR_INTERVAL = 7;

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
    /** 是否带柔光的亮星 */
    private final boolean[] starBright = new boolean[STAR_COUNT];

    /** 星环参数：越外层越淡越细，方向统一（与星空同向） */
    private static final float[] RING_SCALES = {0.55f, 0.78f, 1.02f, 1.28f, 1.56f};
    private static final int[] RING_ALPHAS = {0x8C, 0x78, 0x62, 0x4A, 0x34};
    private static final float[] RING_SWEEPS = {66f, 58f, 48f, 38f, 28f};
    /**
     * 转速（每圈动画转多少整圈，负数=反向）。
     *
     * <p>必须是整数：非整数转速在一圈结束时会跳回起点，看起来就是「动画重置」。</p>
     */
    private static final float[] RING_CYCLES = {1f, -2f, 1f, -1f, 1f};
    private static final float[] RING_STROKES = {3.4f, 3.0f, 2.6f, 2.2f, 1.9f};
    private static final float[] RING_ORIGINS = {10f, 92f, 168f, 246f, 318f};

    private ValueAnimator animator;
    /** 0..1 的循环进度 */
    private float phase;

    public HeroRingDrawable(int startColor, int endColor, float cornerRadiusPx, float density) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.cornerRadius = cornerRadiusPx;
        this.density = density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeWidth(Math.max(1f, density * 2.6f));
        ringPaint.setColor(0xFFFFFFFF);
        starPaint.setColor(0xFFFFFFFF);

        initStars();
    }

    private void initStars() {
        Random random = new Random(20260210L);
        for (int i = 0; i < STAR_COUNT; i++) {
            // 分散在左侧和中间（右侧留给星环，星星不挤过去）
            starX[i] = 0.02f + random.nextFloat() * 0.56f;
            starY[i] = 0.05f + random.nextFloat() * 0.90f;
            // 小星为主：半径收小，左边的星也不能显大
            starRadius[i] = (0.30f + random.nextFloat() * 0.55f) * density;
            starAlpha[i] = 0.16f + random.nextFloat() * 0.64f;
            // 闪烁也必须是整数圈，否则循环接缝处亮度会突然一跳
            starSpeed[i] = 3 + (int) (random.nextFloat() * 4f);
            starPhase[i] = random.nextFloat();
            starBright[i] = i % BRIGHT_STAR_INTERVAL == 0;
            if (starBright[i]) {
                starRadius[i] *= 1.5f;
                starAlpha[i] = Math.min(1f, starAlpha[i] + 0.2f);
            }
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
     * 整片星空：与右侧星环同向缓慢自转（每颗星转速略有差异，产生视差），
     * 同时按自己的速度与相位呼吸闪烁；亮星额外带一圈柔光。
     */
    private void drawStars(Canvas canvas) {
        // 圆心和星环完全一致：整片星空绕着右上角转，看起来是一个整体
        float pivotX = ringCenterX();
        float pivotY = ringCenterY();

        for (int i = 0; i < STAR_COUNT; i++) {
            float wave = (float) Math.sin(2 * Math.PI * (phase * starSpeed[i] + starPhase[i]));
            float alpha = starAlpha[i] * (0.45f + 0.55f * (0.5f + 0.5f * wave));
            int alphaInt = (int) (alpha * 255);
            if (alphaInt <= 2) continue;

            // 绕右上角微旋：方向跟星环一致，每颗星幅度略有差异（近角的转得少）
            float baseX = rect.left + rect.width() * starX[i];
            float baseY = rect.top + rect.height() * starY[i];
            float dx = baseX - pivotX;
            float dy = baseY - pivotY;
            double angle = Math.toRadians(STAR_SWING_DEG
                    * (0.6f + 0.6f * starPhase[i])
                    * Math.sin(2 * Math.PI * phase));
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            float x = pivotX + (float) (dx * cos - dy * sin);
            float y = pivotY + (float) (dx * sin + dy * cos);

            if (starBright[i]) {
                // 亮星先铺一圈很淡的柔光，看着更「星」
                starPaint.setAlpha(Math.min(alphaInt / 3, 88));
                canvas.drawCircle(x, y, starRadius[i] * 3.4f, starPaint);
            }
            starPaint.setAlpha(Math.min(alphaInt, 255));
            canvas.drawCircle(x, y, starRadius[i], starPaint);
        }
    }

    /** 星环圆心（右上角），星空也绕它转 */
    private float ringCenterX() {
        return rect.right - rect.width() * 0.04f;
    }

    private float ringCenterY() {
        return rect.top + rect.height() * 0.02f;
    }

    /** 右侧星环：多道自转的弧段 + 一颗绕环运行的小星 */
    private void drawRings(Canvas canvas) {
        float cx = ringCenterX();
        float cy = ringCenterY();
        float base = rect.height() * 0.58f;

        for (int i = 0; i < RING_SCALES.length; i++) {
            ringPaint.setAlpha(RING_ALPHAS[i]);
            ringPaint.setStrokeWidth(Math.max(1.2f, density * RING_STROKES[i]));
            float radius = base * RING_SCALES[i];
            float startAngle = RING_ORIGINS[i] + 360f * RING_CYCLES[i] * phase;
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                    startAngle, RING_SWEEPS[i], false, ringPaint);
            // 对面对称再来一段，转起来更有「环」的层次
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                    startAngle + 180f, RING_SWEEPS[i] * 0.6f, false, ringPaint);
        }

        // 绕环运行的小星 + 一圈柔光
        float orbitRadius = base * RING_SCALES[1];
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
