package com.networktool.app.widget;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.networktool.app.util.ThemeManager;

/**
 * iOS风格的开关控件
 * 
 * 视觉特点：
 * - 圆角矩形轨道
 * - 圆形滑块
 * - 开启时轨道为绿色主题色
 * - 关闭时轨道为灰色
 * - 滑块带有阴影效果
 */
public class IosSwitch extends View {

    private boolean isChecked = false;
    private float animProgress = 0f; // 动画进度 0=关闭, 1=开启
    
    private Paint trackPaint;  // 轨道画笔
    private Paint thumbPaint;  // 滑块画笔
    private Paint shadowPaint; // 阴影画笔
    
    private float thumbRadius = 13.5f;    // 滑块半径
    private float trackHeight = 31f;      // 轨道高度
    private float trackWidth = 51f;       // 轨道宽度
    private float thumbOffset = 0f;       // 滑块偏移量
    
    private OnCheckedChangeListener listener;
    
    // 颜色
    private int trackOnColor;
    private int trackOffColor;
    private int thumbColor = Color.WHITE;
    
    public interface OnCheckedChangeListener {
        void onCheckedChanged(IosSwitch switchView, boolean isChecked);
    }
    
    public IosSwitch(Context context) {
        super(context);
        init();
    }
    
    public IosSwitch(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public IosSwitch(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // 获取主题色
        int themeColor = ThemeManager.getThemeColor();
        trackOnColor = themeColor;
        trackOffColor = Color.parseColor("#E9E9EA");
        
        // 轨道画笔
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.FILL);
        
        // 滑块画笔
        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(thumbColor);
        thumbPaint.setStyle(Paint.Style.FILL);
        
        // 阴影画笔
        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(0x30000000); // 半透明黑色
        shadowPaint.setStyle(Paint.Style.FILL);
        
        // 允许点击
        setClickable(true);
        setFocusable(true);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 固定尺寸
        setMeasuredDimension((int) trackWidth, (int) trackHeight);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 计算当前轨道颜色（根据动画进度混合）
        float currentProgress = animProgress;
        if (currentProgress == 0 && isChecked) currentProgress = 1f;
        if (currentProgress == 1 && !isChecked) currentProgress = 0f;
        
        int currentTrackColor = blendColors(trackOffColor, trackOnColor, currentProgress);
        trackPaint.setColor(currentTrackColor);
        
        // 绘制轨道（圆角矩形）
        float radius = trackHeight / 2f;
        canvas.drawRoundRect(0, 0, trackWidth, trackHeight, radius, radius, trackPaint);
        
        // 计算滑块位置
        float maxOffset = trackWidth - thumbRadius * 2 - 4f; // 4px = 左右各2px边距
        thumbOffset = maxOffset * currentProgress;
        
        float thumbX = thumbRadius + 2f + thumbOffset;
        float thumbY = trackHeight / 2f;
        
        // 绘制滑块阴影
        shadowPaint.setAlpha((int) (0x30 * (0.3f + 0.2f * currentProgress)));
        canvas.drawCircle(thumbX, thumbY + 1f, thumbRadius + 1f, shadowPaint);
        
        // 绘制滑块
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            toggle();
            return true;
        }
        return super.onTouchEvent(event);
    }
    
    /**
     * 切换开关状态
     */
    public void toggle() {
        setChecked(!isChecked, true);
    }
    
    /**
     * 设置开关状态（带动画）
     */
    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }
    
    /**
     * 设置开关状态
     * @param checked 是否选中
     * @param animate 是否显示动画
     */
    public void setChecked(boolean checked, boolean animate) {
        if (this.isChecked == checked) return;
        
        this.isChecked = checked;
        
        if (animate) {
            ValueAnimator animator = ValueAnimator.ofFloat(
                checked ? 0f : 1f, 
                checked ? 1f : 0f
            );
            animator.setDuration(200);
            animator.addUpdateListener(animation -> {
                animProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            animProgress = checked ? 1f : 0f;
            invalidate();
        }
        
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }
    
    /**
     * 获取当前开关状态
     * @return true表示开启，false表示关闭
     */
    public boolean isChecked() {
        return isChecked;
    }
    
    /**
     * 设置状态变化监听器
     * @param listener 监听器实例
     */
    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }
    
    /**
     * 混合两种颜色
     */
    private int blendColors(int color1, int color2, float ratio) {
        int r1 = Color.red(color1);
        int g1 = Color.green(color1);
        int b1 = Color.blue(color1);
        int a1 = Color.alpha(color1);
        
        int r2 = Color.red(color2);
        int g2 = Color.green(color2);
        int b2 = Color.blue(color2);
        int a2 = Color.alpha(color2);
        
        int r = (int) (r1 + (r2 - r1) * ratio);
        int g = (int) (g1 + (g2 - g1) * ratio);
        int b = (int) (b1 + (b2 - b1) * ratio);
        int a = (int) (a1 + (a2 - a1) * ratio);
        
        return Color.argb(a, r, g, b);
    }
}
