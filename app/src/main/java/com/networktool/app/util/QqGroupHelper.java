package com.networktool.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * QQ 反馈群入口。
 *
 * <p>统一处理群号、QQ 群名片跳转和「未安装 QQ」时的兜底，
 * 避免设置页、关于页各写一份。</p>
 */
public final class QqGroupHelper {

    /** 反馈群群号 */
    public static final String GROUP_NUMBER = "559711374";

    private QqGroupHelper() {
    }

    /**
     * 打开 QQ 群名片；未安装 QQ 时把群号复制到剪贴板并提示。
     */
    public static void open(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin="
                    + GROUP_NUMBER + "&card_type=group&source=qrcode"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                ClipboardManager clipboard =
                        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", GROUP_NUMBER));
                }
                Toast.makeText(context, "未安装 QQ，群号已复制：" + GROUP_NUMBER,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
                Toast.makeText(context, "未安装 QQ，群号：" + GROUP_NUMBER,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
