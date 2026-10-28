package com.networktool.app.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.networktool.app.R;

/**
 * 「扫描结果」底部弹窗的公共处理。
 *
 * <p>扫一扫页和历史记录页共用 {@code bottomsheet_scan_result}，两处都要：</p>
 * <ul>
 *   <li>把二维码内容整段显示出来（内容常带换行：WiFi 多字段、vCard、多行纯文本），
 *       以前按 8 行截断，换行多的时候后面几行直接看不到；</li>
 *   <li>内容超过一屏时给结果区定高、内部滚动，避免把下面的按钮顶出屏幕；</li>
 *   <li>复制按钮是强调（tonal）按钮，默认取主题里静态的 colorSecondaryContainer，
 *       换主题色后底不会跟着变，这里按当前主题色重刷。</li>
 * </ul>
 */
public final class ScanResultSheet {

    /** 结果区最多显示多少行，超出后内部滚动（内容不截断） */
    private static final int MAX_VISIBLE_LINES = 12;

    private ScanResultSheet() {
    }

    /** 填入识别结果并按主题上色 */
    public static void bind(View sheetView, String result) {
        if (sheetView == null) return;
        TextView resultText = sheetView.findViewById(R.id.resultText);
        if (resultText != null) {
            // 统一换行符：Windows 风格的 \r\n 在部分控件里会多出空行
            resultText.setText(result == null ? "" : result.replace("\r\n", "\n"));
            capScrollHeight(sheetView, resultText);
        }
        MaterialButton copyButton = sheetView.findViewById(R.id.copyButton);
        ThemeManager.applyTonalButton(copyButton);
        MaterialButton closeButton = sheetView.findViewById(R.id.closeButton);
        if (closeButton != null) {
            closeButton.setTextColor(ThemeManager.getAccessibleThemeColor(closeButton.getContext()));
        }
    }

    /**
     * 内容超过一屏就给滚动区定高。
     *
     * <p>行数要等排版完成才拿得到，所以放在 post 里，并在还没排版时重试几次。</p>
     */
    private static void capScrollHeight(View sheetView, TextView resultText) {
        View scroll = sheetView.findViewById(R.id.resultScroll);
        if (scroll == null) return;
        resultText.post(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (resultText.getLayout() == null) {
                    if (++attempts < 4) resultText.post(this);
                    return;
                }
                if (resultText.getLineCount() <= MAX_VISIBLE_LINES) return;
                int padding = resultText.getPaddingTop() + resultText.getPaddingBottom();
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                params.height = resultText.getLineHeight() * MAX_VISIBLE_LINES + padding;
                scroll.setLayoutParams(params);
            }
        });
    }
}
