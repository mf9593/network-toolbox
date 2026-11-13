package com.networktool.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 设备标题来源优先级配置。
 *
 * <p>设备扫描时同一台设备可能被多种协议发现（路由器、mDNS、NetBIOS、SADP……），
 * 每种协议给出的名称不一定相同。这里维护一份「越靠前越优先」的协议顺序，
 * 由用户决定最终用哪个名称当设备标题。</p>
 *
 * <p>顺序保存在 app_settings 里，扫描开始时由 {@code LanScanManager} 读取一次。</p>
 */
public final class TitlePriorityConfig {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_ORDER = "title_priority_order";

    /** 默认顺序：路由器品牌优先，避免被 NetBIOS 的共享服务名覆盖 */
    private static final List<String> DEFAULT_ORDER = Arrays.asList(
            "路由器", "mDNS", "NetBIOS", "SADP", "SSDP", "ARP表", "UDP广播",
            "HTTP", "Ping探测", "IPv6", "端口探测", "ONVIF", "WS-Discovery");

    private TitlePriorityConfig() {
    }

    /** 默认顺序（只读副本） */
    public static List<String> getDefaultOrder() {
        return new ArrayList<>(DEFAULT_ORDER);
    }

    /**
     * 读取当前顺序：先读用户配置，再补上默认顺序里缺失的协议
     * （新增协议或旧版本配置都能正常兼容）。
     */
    public static List<String> getOrder(Context context) {
        List<String> result = new ArrayList<>();
        String saved = null;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                saved = prefs.getString(KEY_ORDER, null);
            } catch (Exception ignored) {
            }
        }
        if (saved != null && !saved.isEmpty()) {
            for (String line : saved.split("\n")) {
                String label = line.trim();
                if (!label.isEmpty() && !result.contains(label)) {
                    result.add(label);
                }
            }
        }
        for (String label : DEFAULT_ORDER) {
            if (!result.contains(label)) {
                result.add(label);
            }
        }
        return result;
    }

    /** 保存顺序（每行一个协议名） */
    public static void saveOrder(Context context, List<String> order) {
        if (context == null || order == null) return;
        StringBuilder builder = new StringBuilder();
        for (String label : order) {
            if (label == null || label.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(label.trim());
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ORDER, builder.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 协议排序权重：数字越小越优先，未知协议排在最后 */
    public static int rank(Context context, String label) {
        List<String> order = getOrder(context);
        int index = order.indexOf(label);
        return index >= 0 ? index : order.size();
    }
}
