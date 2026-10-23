package com.networktool.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

public class SignalStrengthView extends View {

    private static final int MAX_POINTS = 20;
    private static final int MIN_DBM = -127;
    private static final int MAX_DBM = 0;
    private static final int Y_TICK_COUNT = 4;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Integer> data = new ArrayList<>();
    private int themeColor;
    private int textColor = Color.GRAY;

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
        linePaint.setStrokeWidth(3f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextSize(28f);

        currentValuePaint.setTextSize(36f);
        currentValuePaint.setFakeBoldText(true);

        updateThemeColors();
    }

    /**
     * 主题色/深色模式变化后刷新曲线配色
     */
    public void updateThemeColors() {
        themeColor = ThemeManager.getAccessibleThemeColor(getContext());
        textColor = ThemeManager.getOnSurfaceVariantColor(getContext());

        gridPaint.setColor(ThemeManager.getOutlineVariantColor(getContext()));
        gridPaint.setStrokeWidth(1f);

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
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        int left = getPaddingLeft() + 80;
        int top = getPaddingTop() + 40;
        int right = left + width - 80;
        int bottom = top + height - 60;

        if (data.isEmpty()) {
            textPaint.setTextSize(32f);
            textPaint.setColor(textColor);
            canvas.drawText("等待信号数据...", left, (top + bottom) / 2f, textPaint);
            return;
        }

        drawGrid(canvas, left, top, right, bottom);
        drawDataLine(canvas, left, top, right, bottom);
    }

    private void drawGrid(Canvas canvas, int left, int top, int right, int bottom) {
        float yRange = MAX_DBM - MIN_DBM;
        for (int i = 0; i <= Y_TICK_COUNT; i++) {
            int dbm = MAX_DBM - (int) (i * yRange / Y_TICK_COUNT);
            float y = top + (bottom - top) * i / Y_TICK_COUNT;
            canvas.drawLine(left, y, right, y, gridPaint);
            textPaint.setColor(textColor);
            textPaint.setTextSize(28f);
            canvas.drawText(dbm + "dBm", left - 75, y + 10, textPaint);
        }
    }

    private void drawDataLine(Canvas canvas, int left, int top, int right, int bottom) {
        int n = data.size();
        float yRange = MAX_DBM - MIN_DBM;
        float xStep = (right - left) / (float) (MAX_POINTS - 1);
        int startX = right - (n - 1) * (int) xStep;
        if (startX < left) startX = left;

        Path linePath = new Path();
        Path fillPath = new Path();
        boolean first = true;

        for (int i = 0; i < n; i++) {
            int rssi = data.get(i);
            float x = startX + i * xStep;
            float y = bottom - (bottom - top) * (rssi - MIN_DBM) / yRange;
            y = Math.max(top, Math.min(bottom, y));

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
            float lastY = bottom - (bottom - top) * (data.get(n - 1) - MIN_DBM) / yRange;
            lastY = Math.max(top, Math.min(bottom, lastY));
            fillPath.lineTo(lastX, bottom);
            fillPath.close();
        }

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        if (n > 0) {
            float lastX = startX + (n - 1) * xStep;
            float lastY = bottom - (bottom - top) * (data.get(n - 1) - MIN_DBM) / yRange;
            lastY = Math.max(top, Math.min(bottom, lastY));
            canvas.drawCircle(lastX, lastY, 8, dotPaint);

            String val = data.get(n - 1) + " dBm";
            float textWidth = currentValuePaint.measureText(val);
            float textX = lastX - textWidth - 16;
            if (textX < left) textX = left;
            canvas.drawText(val, textX, lastY - 16, currentValuePaint);
        }
    }
}
