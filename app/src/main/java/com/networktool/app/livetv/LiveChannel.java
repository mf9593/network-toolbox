package com.networktool.app.livetv;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播源列表中的频道或分组标题。
 *
 * <p>同一分组下「同标题」的多条地址会合并成同一个频道，作为多个信号源（线路）保存，
 * 播放时可在线路之间切换。
 */
public class LiveChannel {

    private final String name;
    private final String group;
    private final boolean header;
    /** 该频道的所有信号源地址，至少 1 条（分组标题为空） */
    private final List<String> sources = new ArrayList<>();
    /** 当前使用的信号源下标 */
    private int selectedSourceIndex = 0;

    public LiveChannel(String name, String url, String group, boolean header) {
        this.name = name;
        this.group = group;
        this.header = header;
        if (url != null && !url.isEmpty()) {
            sources.add(url);
        }
    }

    public static LiveChannel header(String group) {
        return new LiveChannel(group, "", group, true);
    }

    public static LiveChannel channel(String name, String url, String group) {
        return new LiveChannel(name, url, group, false);
    }

    public String getName() {
        return name;
    }

    /** 当前选中线路的地址 */
    public String getUrl() {
        if (sources.isEmpty()) {
            return "";
        }
        int index = Math.max(0, Math.min(selectedSourceIndex, sources.size() - 1));
        return sources.get(index);
    }

    public String getGroup() {
        return group;
    }

    public boolean isHeader() {
        return header;
    }

    /* ==================== 多信号源 ==================== */

    /** 追加一个信号源（重复地址自动忽略） */
    public void addSource(String url) {
        if (url == null || url.isEmpty() || sources.contains(url)) {
            return;
        }
        sources.add(url);
    }

    public List<String> getSources() {
        return sources;
    }

    public int getSourceCount() {
        return sources.size();
    }

    public boolean hasMultipleSources() {
        return sources.size() > 1;
    }

    public String getUrlAt(int index) {
        if (index < 0 || index >= sources.size()) {
            return "";
        }
        return sources.get(index);
    }

    public int getSelectedSourceIndex() {
        return Math.max(0, Math.min(selectedSourceIndex, Math.max(0, sources.size() - 1)));
    }

    /** 设置当前线路，越界自动收敛 */
    public void setSelectedSourceIndex(int index) {
        if (sources.isEmpty()) {
            selectedSourceIndex = 0;
            return;
        }
        selectedSourceIndex = Math.max(0, Math.min(index, sources.size() - 1));
    }

    /** 切到下一条线路，返回是否成功 */
    public boolean nextSource() {
        if (sources.size() <= 1) {
            return false;
        }
        selectedSourceIndex = (getSelectedSourceIndex() + 1) % sources.size();
        return true;
    }

    /** 形如「线路 2/3」的展示文本 */
    public String getSourceLabel() {
        int total = Math.max(1, sources.size());
        return "线路 " + (getSelectedSourceIndex() + 1) + "/" + total;
    }
}
