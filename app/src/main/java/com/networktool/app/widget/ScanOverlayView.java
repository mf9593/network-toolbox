package com.networktool.app.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;

import com.journeyapps.barcodescanner.ViewfinderView;
import com.networktool.app.R;
import com.networktool.app.util.ThemeManager;

/**
 * 现代扫码取景框：半透明遮罩 + 圆角扫描框 + 四角高亮 + 动态扫描线 + 提示文字。
 */
public class ScanOverlayView extends ViewfinderView {

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path maskPath = new Path();
    private final RectF frameRect = new RectF();

    private int frameColor;
    private String hintText;
    private String subHintText;
    private boolean paused;

    public ScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        hintText = getResources().getString(R.string.scan_hint);
        subHintText = getResources().getString(R.string.scan_hint_sub);

        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(Color.argb(165, 0, 0, 0));

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(3.5f));
        framePaint.setStrokeCap(Paint.Cap.ROUND);
        framePaint.setStrokeJoin(Paint.Join.ROUND);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));

        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextSize(sp(13));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setShadowLayer(dp(2), 0, dp(1), 0xAA000000);

        subHintPaint.setColor(0xCCFFFFFF);
        subHintPaint.setTextSize(sp(11));
        subHintPaint.setTextAlign(Paint.Align.CENTER);
        subHintPaint.setShadowLayer(dp(2), 0, dp(1), 0xAA000000);

        updateFrameColor();
    }

    private void updateFrameColor() {
        int color = ThemeManager.getThemeColor();
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[2] < 0.55f) {
            hsv[2] = 1f;
        }
        frameColor = Color.HSVToColor(hsv);
        framePaint.setColor(frameColor);
        borderPaint.setColor((frameColor & 0x00FFFFFF) | 0x55000000);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            postInvalidate();
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float frameSize = Math.min(dp(250), Math.min(width * 0.78f, height * 0.52f));
        if (frameSize < dp(160)) {
            frameSize = Math.min(width, height) * 0.6f;
        }
        float left = (width - frameSize) / 2f;
        float top = (height - frameSize) / 2f;
        if (top < dp(96)) {
            top = dp(96);
        }
        if (top + frameSize > height - dp(130)) {
            top = height - dp(130) - frameSize;
        }
        float right = left + frameSize;
        float bottom = top + frameSize;
        frameRect.set(left, top, right, bottom);

        // 半透明遮罩，中间挖出扫描框
        maskPath.reset();
        maskPath.setFillType(Path.FillType.EVEN_ODD);
        maskPath.addRect(0, 0, width, height, Path.Direction.CW);
        maskPath.addRoundRect(frameRect, dp(16), dp(16), Path.Direction.CW);
        canvas.drawPath(maskPath, maskPaint);

        // 扫描框细边
        canvas.drawRoundRect(frameRect, dp(16), dp(16), borderPaint);

        drawCorners(canvas, left, top, right, bottom);

        // 动态扫描线
        float progress = (SystemClock.uptimeMillis() % 2200L) / 2200f;
        float lineY = top + dp(10) + (frameSize - dp(20)) * progress;
        Shader shader = new LinearGradient(left, lineY, right, lineY,
                new int[]{0x00FFFFFF, frameColor, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        linePaint.setShader(shader);
        canvas.drawLine(left + dp(12), lineY, right - dp(12), lineY, linePaint);

        // 提示文字
        float hintY = bottom + dp(38);
        if (hintY > height - dp(90)) {
            hintY = bottom + dp(26);
        }
        canvas.drawText(hintText, width / 2f, hintY, hintPaint);
        canvas.drawText(subHintText, width / 2f, hintY + dp(21), subHintPaint);

        if (!paused && isAttachedToWindow()) {
            postInvalidateDelayed(16);
        }
    }

    private void drawCorners(Canvas canvas, float left, float top, float right, float bottom) {
        float length = dp(26);
        float offset = dp(2);
        // 左上
        canvas.drawLine(left + offset, top + length, left + offset, top + offset, framePaint);
        canvas.drawLine(left + offset, top + offset, left + length, top + offset, framePaint);
        // 右上
        canvas.drawLine(right - length, top + offset, right - offset, top + offset, framePaint);
        canvas.drawLine(right - offset, top + offset, right - offset, top + length, framePaint);
        // 左下
        canvas.drawLine(left + offset, bottom - length, left + offset, bottom - offset, framePaint);
        canvas.drawLine(left + offset, bottom - offset, left + length, bottom - offset, framePaint);
        // 右下
        canvas.drawLine(right - length, bottom - offset, right - offset, bottom - offset, framePaint);
        canvas.drawLine(right - offset, bottom - offset, right - offset, bottom - length, framePaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
