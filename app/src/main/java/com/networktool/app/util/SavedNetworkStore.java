package com.networktool.app.util;

import android.content.Context;

import com.networktool.app.model.SavedNetworkModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 已保存网络的本地缓存。
 *
 * <p>读取已保存网络要借 Shizuku 的 shell 身份，每次都插一次很麻烦。这里把读到的一次结果
 * 落盘成 JSON，下次进页面先展示缓存，用户点「刷新」才重新读取。</p>
 *
 * <p>缓存写在应用私有目录（{@code filesDir}），不进外部存储：里面是明文密码，
 * 私有目录只有本应用能读，跟系统把密码存在自己数据目录里是同一档保护。</p>
 */
public final class SavedNetworkStore {

    private static final String TAG = "SavedNetworkStore";

    /** 缓存文件名；带 .tmp 后缀的是原子写的中转文件 */
    private static final String FILE_NAME = "saved_networks_cache.json";

    private final File file;

    public SavedNetworkStore(Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
    }

    /** 一次缓存快照：网络列表 + 落盘时间（毫秒；0 表示没有缓存） */
    public static final class Snapshot {
        public final long time;
        public final List<SavedNetworkModel> networks;

        public Snapshot(long time, List<SavedNetworkModel> networks) {
            this.time = time;
            this.networks = networks == null ? new ArrayList<>() : networks;
        }

        public boolean isEmpty() {
            return networks.isEmpty();
        }
    }

    /** 读缓存；文件不存在或损坏时返回空快照，不抛异常 */
    public Snapshot load() {
        if (!file.exists()) return new Snapshot(0, new ArrayList<>());
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (sb.length() == 0) return new Snapshot(0, new ArrayList<>());

            JSONObject root = new JSONObject(sb.toString());
            long time = root.optLong("time", 0);
            JSONArray arr = root.optJSONArray("networks");
            List<SavedNetworkModel> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    SavedNetworkModel m = new SavedNetworkModel();
                    m.setSsid(o.optString("ssid", ""));
                    m.setSecurity(o.optString("security", ""));
                    m.setNetworkId(o.optInt("networkId", -1));
                    m.setPriority(o.optInt("priority", 0));
                    m.setPreSharedKey(o.optString("preSharedKey", ""));
                    // 没名字的条目没法展示（系统里会有被遗忘名字的空配置）
                    if (!m.getSsid().isEmpty()) list.add(m);
                }
            }
            return new Snapshot(time, list);
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "读取已保存网络缓存失败", t);
            return new Snapshot(0, new ArrayList<>());
        }
    }

    /**
     * 写缓存。先写 {@code .tmp} 再原子重命名，避免写到一半被读到一个残缺文件。
     *
     * @param time     落盘时间（毫秒）
     * @param networks 要缓存的网络列表
     */
    public void save(long time, List<SavedNetworkModel> networks) {
        try {
            JSONObject root = new JSONObject();
            root.put("time", time);
            JSONArray arr = new JSONArray();
            if (networks != null) {
                for (SavedNetworkModel m : networks) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("ssid", m.getSsid() == null ? "" : m.getSsid());
                        o.put("security", m.getSecurity() == null ? "" : m.getSecurity());
                        o.put("networkId", m.getNetworkId());
                        o.put("priority", m.getPriority());
                        o.put("preSharedKey",
                                m.getPreSharedKey() == null ? "" : m.getPreSharedKey());
                        arr.put(o);
                    } catch (Throwable ignored) {
                        // 单条序列化失败只跳过这一条，不影响整份缓存
                    }
                }
            }
            root.put("networks", arr);

            File tmp = new File(file.getAbsolutePath() + ".tmp");
            try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
                w.write(root.toString());
            }
            if (!tmp.renameTo(file)) {
                // 个别场景 rename 可能失败，退回直接覆盖写
                try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
                    w.write(root.toString());
                }
                tmp.delete();
            }
        } catch (Throwable t) {
            AppErrorLogger.catchThrowable(TAG, "写入已保存网络缓存失败", t);
        }
    }

    /** 删除缓存文件 */
    public void clear() {
        try {
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }
}
