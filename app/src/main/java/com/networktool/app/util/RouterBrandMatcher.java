package com.networktool.app.util;

import java.util.Locale;

/**
 * 路由器品牌识别。
 *
 * <p>路由器后台标题五花八门（"TL-WR886N"、"MIWIFI"、"天翼网关"……），
 * 直接用品牌中文名去匹配标题命中率很低。这里维护一份品牌 → 特征关键词表，
 * 用标题 / HTTP 头 / 页面特征串去匹配，尽量让每台路由器都能显示对应品牌。</p>
 */
public final class RouterBrandMatcher {

    /** 品牌 → 特征关键词（全部小写匹配） */
    private static final String[][] SIGNATURES = {
            {"TP-Link", "tp-link", "tplink", "tplogin", "tl-wr", "tl-r", "archer"},
            {"水星路由", "mercury", "melogin", "水星"},
            {"FAST迅捷", "falogin", "迅捷"},
            {"小米路由", "miwifi", "xiaomi", "小米", "redmi"},
            {"华为路由", "huawei", "华为"},
            {"荣耀路由", "honor", "荣耀"},
            {"ZTE中兴", "zte", "中兴"},
            {"Tenda腾达", "tenda", "腾达"},
            {"ASUS华硕", "asus", "华硕"},
            {"Ruijie睿易", "ruijie", "锐捷", "睿易", "ruiyi"},
            {"H3C华三", "h3c", "华三", "moshujia"},
            {"360路由", "luyou.360", "360wifi", "360路由", "360安全路由"},
            {"移动路由", "cmcc", "chinamobile", "中国移动"},
            {"电信路由", "chinanet", "中国电信", "天翼", "router.ctc"},
            {"斐讯路由", "phicomm", "斐讯"},
            {"极路由", "hiwifi", "极路由"},
            {"蒲公英路由", "oray", "蒲公英"},
            {"飞邑路由", "feiyi", "飞邑"},
            {"海康路由", "hikvision", "海康"},
            {"优酷路由", "youku", "优酷"}
    };

    private RouterBrandMatcher() {
    }

    /**
     * 从标题 / 页面特征里匹配路由器品牌。
     *
     * @param text 路由器后台标题、HTTP Server 头或页面特征串
     * @return 品牌名（与 routerList.json 里的 brand 一致），匹配不到返回 null
     */
    public static String match(String text) {
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.US);
        for (String[] signature : SIGNATURES) {
            String brand = signature[0];
            for (int i = 1; i < signature.length; i++) {
                if (lower.contains(signature[i])) {
                    return brand;
                }
            }
        }
        return null;
    }
}
