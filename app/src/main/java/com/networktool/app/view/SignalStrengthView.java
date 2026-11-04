package com.networktool.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页 WiFi 信号强度曲线。
 *
 * <p>纵轴按 20 dBm 一档，共 7 条参考线（-120 ~ 0 dBm）。横屏时控件高度被压扁，
 * 参考线间距不够放文字就自动隔一条标一次，再不够就只画线不标字，避免文字重叠。</p>
 *
 * <p>所有尺寸都按屏幕密度/系统字号换算，不写死像素，横竖屏观感一致。</p>
 */
public class SignalStrengthView extends View {

    private static final int MAX_POINTS = 20;
    /** 纵轴下限，低于它的 RSSI 画在最底部 */
    private static final int MIN_DBM = -120;
    private static final int MAX_DBM = 0;
    /** 每一档 20 dBm，0 ~ -120 共 7 条参考线 */
    private static final int TICK_STEP_DBM = 20;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Integer> data = new ArrayList<>();
    private int themeColor;
    private int textColor = Color.GRAY;

    private float labelTextSize;
    private float valueTextSize;
    private float dotRadius;
    private float lineWidth;

    public SignalStrengthView(Context context) {
        super(context);
        init();
    }

    public SignalStrengthView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SignalStrengthView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);

        currentValuePaint.setFakeBoldText(true);

        updateThemeColors();
    }

    /**
     * 文字/线宽按屏幕密度和系统字号换算。
     * 以前写死 28/36 像素，高密度屏上字偏大，横屏空间一挤就叠在一起。
     */
    private void applyScaledSizes() {
        float density = getResources().getDisplayMetrics().density;
        labelTextSize = sp(10f);
        valueTextSize = sp(13f);
        lineWidth = Math.max(2f, dp(1.5f));
        dotRadius = Math.max(3f, dp(3f));

        textPaint.setTextSize(labelTextSize);
        currentValuePaint.setTextSize(valueTextSize);
        linePaint.setStrokeWidth(lineWidth);
        gridPaint.setStrokeWidth(Math.max(1f, density));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    /**
     * 主题色/深色模式变化后刷新曲线配色
     */
    public void updateThemeColors() {
        themeColor = ThemeManager.getAccessibleThemeColor(getContext());
        textColor = ThemeManager.getOnSurfaceVariantColor(getContext());

        gridPaint.setColor(ThemeManager.getOutlineVariantColor(getContext()));

        linePaint.setColor(themeColor);
        fillPaint.setColor((themeColor & 0x00FFFFFF) | 0x20000000);
        textPaint.setColor(textColor);
        dotPaint.setColor(themeColor);
        currentValuePaint.setColor(themeColor);
        currentValuePaint.setFakeBoldText(true);
        invalidate();
    }

    public void setData(List<Integer> newData) {
        data.clear();
        data.addAll(newData);
        while (data.size() > MAX_POINTS) {
            data.remove(0);
        }
        invalidate();
    }

    public void addValue(int rssi) {
        data.add(rssi);
        while (data.size() > MAX_POINTS) {
            data.remove(0);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        applyScaledSizes();

        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;

        // 左侧留给纵轴文字。按「最长的那条标签」测量，不然 -120dBm 会被裁掉左边
        String widestLabel = formatTick(MIN_DBM);
        float labelGutter = textPaint.measureText(widestLabel) + dp(8);

        // 上下按字体实际高度留白，最上面/最下面那条标签也不会被裁掉
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float halfTextHeight = (fm.descent - fm.ascent) / 2f;

        float left = getPaddingLeft() + labelGutter;
        float right = getPaddingLeft() + width - dp(4);
        float top = getPaddingTop() + halfTextHeight + dp(2);
        float bottom = getPaddingTop() + height - halfTextHeight - dp(2);
        if (right <= left || bottom <= top) return;

        if (data.isEmpty()) {
            textPaint.setTextSize(sp(12f));
            textPaint.setColor(textColor);
            canvas.drawText("等待信号数据...", left, (top + bottom) / 2f, textPaint);
            return;
        }

        drawGrid(canvas, left, top, right, bottom);
        drawDataLine(canvas, left, top, right, bottom);
    }

    private String formatTick(int dbm) {
        return dbm + "dBm";
    }

    private void drawGrid(Canvas canvas, float left, float top, float right, float bottom) {
        int tickCount = (MAX_DBM - MIN_DBM) / TICK_STEP_DBM; // 6 段，7 条线
        float step = (bottom - top) / tickCount;
        float labelHeight = textPaint.getTextSize();
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        // 让文字垂直居中于参考线
        float baselineOffset = (fm.descent - fm.ascent) / 2f - fm.descent;

        // 间距够就每档都标，只够两倍间距就隔一档标，实在挤不下就只画线不标字
        boolean everyLabel = step >= labelHeight * 1.7f;
        boolean everyOtherLabel = step * 2 >= labelHeight * 1.7f;

        textPaint.setColor(textColor);
        for (int i = 0; i <= tickCount; i++) {
            int dbm = MAX_DBM - i * TICK_STEP_DBM;
            float y = top + step * i;
            canvas.drawLine(left, y, right, y, gridPaint);

            boolean drawLabel = everyLabel || (everyOtherLabel && i % 2 == 0);
            if (!drawLabel) continue;

            String label = formatTick(dbm);
            float labelX = left - dp(6) - textPaint.measureText(label);
            canvas.drawText(label, labelX, y + baselineOffset, textPaint);
        }
    }

    private void drawDataLine(Canvas canvas, float left, float top, float right, float bottom) {
        int n = data.size();
        float yRange = MAX_DBM - MIN_DBM;
        float xStep = (right - left) / (float) (MAX_POINTS - 1);
        float startX = right - (n - 1) * xStep;
        if (startX < left) startX = left;

        Path linePath = new Path();
        Path fillPath = new Path();
        boolean first = true;

        for (int i = 0; i < n; i++) {
            int rssi = data.get(i);
            float x = startX + i * xStep;
            float y = valueToY(rssi, top, bottom, yRange);

            if (first) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
                first = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }

        if (n > 0) {
            float lastX = startX + (n - 1) * xStep;
            fillPath.lineTo(lastX, bottom);
            fillPath.close();
        }

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        if (n > 0) {
            float lastX = startX + (n - 1) * xStep;
            float lastY = valueToY(data.get(n - 1), top, bottom, yRange);
            canvas.drawCircle(lastX, lastY, dotRadius, dotPaint);

            String val = data.get(n - 1) + " dBm";
            float textWidth = currentValuePaint.measureText(val);
            float textX = lastX - textWidth - dp(6);
            if (textX < left) textX = left;
            float textY = lastY - dp(6);
            // 别画到图外
            if (textY < top + valueTextSize) textY = lastY + valueTextSize + dp(2);
            canvas.drawText(val, textX, textY, currentValuePaint);
        }
    }

    private float valueToY(int rssi, float top, float bottom, float yRange) {
        int clamped = Math.max(MIN_DBM, Math.min(MAX_DBM, rssi));
        float y = bottom - (bottom - top) * (clamped - MIN_DBM) / yRange;
        return Math.max(top, Math.min(bottom, y));
    }
}
