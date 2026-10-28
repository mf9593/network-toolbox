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
 * 主页 Hero 卡片背景：星空 + 右侧旋转星环 + 主题色渐变。
 *
 * <p>星点按<b>极坐标</b>围绕右上角（星环圆心）排布，整片星空与星环一样以这个圆心
 * 顺时针绕转 <b>360°</b>（一圈动画正好一圈，循环衔接处位置完全一致，不会跳变）。
 * 星点转到卡片外用软边淡出代替硬切，所以画面里始终是「繁星点点」，不会越转越空。</p>
 *
 * <p>半径按 √ 分布偏向大值：多数星离圆心较远，落在卡片的左侧和中间，
 * 不会堆在右上角星环那一小块。颜色全部来自主题色，不写死。动画由
 * {@link ValueAnimator} 驱动，随可见性自动启停。</p>
 */
public class HeroRingDrawable extends Drawable {

    /** 一圈动画时长（星空绕右上角转一圈的时间：80 秒 ≈ 4.5°/s） */
    private static final long LOOP_DURATION_MS = 80000L;
    /**
     * 星点数量。
     *
     * <p>星空是绕右上角整圈排布的，任何时刻都有一大半在卡片外（只跳过不绘制，
     * 开销只是一次坐标计算），所以基数要放大，卡片里才能同时有一两百颗星。</p>
     */
    private static final int STAR_COUNT = 2200;
    /** 每圈动画绕角转几整圈（必须整数，循环接缝才不会跳） */
    private static final float STAR_ROTATION_TURNS = 1f;
    /** 每隔几颗星安排一颗带柔光的亮星 */
    private static final int BRIGHT_STAR_INTERVAL = 9;
    /** 卡片边缘多少 dp 内淡出，避免星点「凭空出现/消失」 */
    private static final float EDGE_FADE_DP = 10f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final int startColor;
    private final int endColor;
    private final float cornerRadius;
    private final float density;

    /** 星点极坐标：离右上角的距离（以卡片宽度为单位）、初始角度的 sin/cos */
    private final float[] starDist = new float[STAR_COUNT];
    private final float[] starCos = new float[STAR_COUNT];
    private final float[] starSin = new float[STAR_COUNT];
    /** 星点：半径、亮度、闪烁速度、相位 */
    private final float[] starRadius = new float[STAR_COUNT];
    private final float[] starAlpha = new float[STAR_COUNT];
    private final float[] starSpeed = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];
    /** 是否带柔光的亮星 */
    private final boolean[] starBright = new boolean[STAR_COUNT];

    /** 星环参数：越外层越淡越细 */
    private static final float[] RING_SCALES = {0.55f, 0.78f, 1.02f, 1.28f, 1.56f};
    private static final int[] RING_ALPHAS = {0x8C, 0x78, 0x62, 0x4A, 0x34};
    private static final float[] RING_SWEEPS = {66f, 58f, 48f, 38f, 28f};
    /** 转速（每圈动画转多少整圈，负数=反向），必须是整数，否则接缝处会跳 */
    private static final float[] RING_CYCLES = {5f, -10f, 5f, -5f, 5f};
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
            // 半径按 √ 分布偏大：星多在离右上角较远处（卡片左侧和中间），
            // 不会全挤在星环那一小块
            starDist[i] = 0.10f + 1.25f * (float) Math.sqrt(random.nextFloat());
            double angle = random.nextFloat() * 2 * Math.PI;
            starCos[i] = (float) Math.cos(angle);
            starSin[i] = (float) Math.sin(angle);
            // 小星为主：半径收小，避免变成「点点斑块」
            starRadius[i] = (0.30f + random.nextFloat() * 0.55f) * density;
            starAlpha[i] = 0.16f + random.nextFloat() * 0.64f;
            // 闪烁必须是整数圈，否则循环接缝处亮度会突然一跳
            starSpeed[i] = 10 + (int) (random.nextFloat() * 10f);
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
     * 星空：以右上角为圆心顺时针绕转（和星环同一个圆心、同一个方向）。
     *
     * <p>转角 = 整圈进度 × 360°（每圈动画正好转一圈），所以循环点前后位置完全一致；
     * 转到卡片外的星直接跳过，靠近边缘的按距离淡出，不会出现「啪」地凭空出现。</p>
     */
    private void drawStars(Canvas canvas) {
        float pivotX = ringCenterX();
        float pivotY = ringCenterY();
        float unit = rect.width();
        float fadeWidth = Math.max(density * EDGE_FADE_DP, 1f);

        double spin = 2 * Math.PI * phase * STAR_ROTATION_TURNS;
        float cosSpin = (float) Math.cos(spin);
        float sinSpin = (float) Math.sin(spin);

        for (int i = 0; i < STAR_COUNT; i++) {
            // 半径带 ±5% 的整数圈脉动，远近层次更自然
            float radius = unit * starDist[i]
                    * (1f + 0.05f * (float) Math.sin(2 * Math.PI * (phase * 8 + starPhase[i])));
            // 初始角度叠加整圈旋转
            float dirX = starCos[i] * cosSpin - starSin[i] * sinSpin;
            float dirY = starSin[i] * cosSpin + starCos[i] * sinSpin;
            float x = pivotX + radius * dirX;
            float y = pivotY + radius * dirY;

            // 卡片外的星不画；靠边的按距离淡出
            float edge = Math.min(Math.min(x - rect.left, rect.right - x),
                    Math.min(y - rect.top, rect.bottom - y));
            if (edge <= 0f) continue;
            float edgeAlpha = Math.min(1f, edge / fadeWidth);

            float wave = (float) Math.sin(2 * Math.PI * (phase * starSpeed[i] + starPhase[i]));
            float alpha = starAlpha[i] * (0.45f + 0.55f * (0.5f + 0.5f * wave)) * edgeAlpha;
            int alphaInt = (int) (alpha * 255);
            if (alphaInt <= 2) continue;

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
        double theta = 2 * Math.PI * phase * 4;
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
