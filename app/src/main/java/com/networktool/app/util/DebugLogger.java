package com.networktool.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.networktool.app.NetworkToolApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 开发者调试日志工具类
 *
 * 功能说明：
 * - 提供开发者调试开关，控制是否记录详细的调试信息
 * - 记录ONVIF、SADP、天地伟业设备发现的多播响应XML
 * - 持久化存储调试日志到文件
 * - 支持导出调试日志供分析
 *
 * 使用方式：
 * <pre>
 * // 检查调试模式是否开启
 * if (DebugLogger.isEnabled()) {
 *     DebugLogger.logDiscovery("ONVIF", "收到设备响应", xmlContent);
 * }
 * </pre>
 */
public class DebugLogger {
    private static final String TAG = "DebugLogger";
    private static final String PREFS_NAME = "debug_settings";
    private static final String KEY_DEBUG_MODE = "debug_mode_enabled";
    private static final String KEY_BROADCAST_LOG = "broadcast_log_enabled";
    private static final String DEBUG_DIR_NAME = "debug_logs";
    private static final String DISCOVERY_LOG_FILE = "discovery_log.txt";
    private static final String VIRTUAL_CAMERA_LOG_FILE = "virtual_camera_log.txt";
    private static final String BROADCAST_LOG_FILE = "broadcast_log.txt";
    private static final int MAX_LOG_LINES = 10000; // 最大保留日志行数

    /**
     * 获取Context（通过Application实例）
     */
    private static Context getContext() {
        try {
            return NetworkToolApplication.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "无法获取Application Context", e);
            return null;
        }
    }
    
    /**
     * 获取调试目录
     * 优先使用外部存储目录，方便真机访问
     */
    private static File getDebugDir(Context context) {
        // 尝试使用外部存储目录 /storage/emulated/0/Android/data/<package>/debug_logs
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir != null) {
            File debugDir = new File(externalDir, DEBUG_DIR_NAME);
            if (!debugDir.exists()) {
                debugDir.mkdirs();
            }
            return debugDir;
        }
        
        // 回退到内部存储目录
        File internalDir = new File(context.getFilesDir(), DEBUG_DIR_NAME);
        if (!internalDir.exists()) {
            internalDir.mkdirs();
        }
        return internalDir;
    }
    
    /**
     * 获取调试目录（使用懒加载Context）
     */
    private static File getDebugDir() {
        Context context = getContext();
        if (context == null) {
            // 如果无法获取Context，使用一个默认路径
            Log.e(TAG, "Cannot get Context, using fallback");
            return new File("/storage/emulated/0/Android/data/com.networktool.app/files", DEBUG_DIR_NAME);
        }
        return getDebugDir(context);
    }

    /**
     * 检查开发者调试模式是否开启
     *
     * @return true 表示调试模式已开启
     */
    public static boolean isEnabled() {
        // 日志查看功能已移除，仅保留崩溃日志；调试日志不再落盘
        return false;
    }

    /**
     * 设置开发者调试模式
     *
     * @param enabled 是否启用调试模式
     */
    public static void setEnabled(boolean enabled) {
        Context context = getContext();
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply();
        Log.d(TAG, "调试模式已" + (enabled ? "开启" : "关闭"));
    }

    /**
     * 检查广播日志模式是否开启
     */
    public static boolean isBroadcastLogEnabled() {
        // 日志查看功能已移除，广播日志不再落盘
        return false;
    }

    /**
     * 设置广播日志模式
     */
    public static void setBroadcastLogEnabled(boolean enabled) {
        Context context = getContext();
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BROADCAST_LOG, enabled).apply();
        Log.d(TAG, "广播日志已" + (enabled ? "开启" : "关闭"));
    }

    /**
     * 记录局域网广播/多播消息响应
     */
    public static void logBroadcast(String protocol, String senderIp, String rawData) {
        // 无论是否落盘，都把原始报文记入内存日志，供「设置 → 运行日志」查看
        RuntimeLogStore.record(protocol, senderIp, "广播/多播", rawData);
        if (!isBroadcastLogEnabled()) return;

        String timestamp = getCurrentTime();
        String logEntry = String.format("[%s] [%s] 来自 %s\n%s\n%s\n",
                timestamp, protocol, senderIp,
                rawData != null ? rawData : "(空)",
                "-".repeat(80));

        Log.d(TAG, "[Broadcast][" + protocol + "] 来自 " + senderIp);
        writeToFile(logEntry, BROADCAST_LOG_FILE);
    }

    /**
     * 获取广播日志内容
     */
    public static String getBroadcastLogs() {
        Context context = getContext();
        if (context == null) return "无法获取Context";

        File logFile = new File(getDebugDir(context), BROADCAST_LOG_FILE);
        if (!logFile.exists()) {
            return "暂无广播日志记录。\n请在「设置 → 广播日志」中开启后重新扫描。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 局域网广播日志 ===\n");
        sb.append("采集时间: ").append(getCurrentTime()).append("\n");
        sb.append("广播日志: 已开启\n");
        sb.append("========================\n\n");

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            List<String> lines = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            int startIndex = Math.max(0, lines.size() - MAX_LOG_LINES);
            for (int i = startIndex; i < lines.size(); i++) {
                sb.append(lines.get(i)).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "读取广播日志失败: " + e.getMessage());
            sb.append("\n读取广播日志失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 清除广播日志
     */
    public static void clearBroadcastLogs() {
        Context context = getContext();
        if (context == null) return;
        File logFile = new File(getDebugDir(context), BROADCAST_LOG_FILE);
        if (logFile.exists()) {
            logFile.delete();
            Log.d(TAG, "广播日志已清除");
        }
    }

    /**
     * 记录设备发现调试日志
     *
     * 记录WS-Discovery/SADP等多播协议的响应XML
     *
     * @param protocol 协议名称（如 "ONVIF", "SADP", "Tiandy", "LanChat"）
     * @param event 事件描述（如 "收到设备响应", "发送Probe消息"）
     * @param detail 详细信息（如XML内容、IP地址等）
     */
    public static void logDiscovery(String protocol, String event, String detail) {
        // 事件 + 详情记入内存日志（不落盘）
        RuntimeLogStore.record(protocol, "", event, detail);
        if (!isEnabled()) {
            return;
        }

        String timestamp = getCurrentTime();
        String logEntry = String.format("[%s] [%s] %s\n%s\n%s\n",
                timestamp, protocol, event,
                detail != null ? detail : "",
                "-".repeat(80));

        // 同时输出到logcat，方便实时查看
        Log.d(TAG, "[" + protocol + "] " + event);
        if (detail != null && !detail.isEmpty()) {
            Log.d(TAG, detail);
        }

        // 写入文件
        writeToFile(logEntry);
    }

    /**
     * 记录设备发现调试日志（简化版本，仅记录事件）
     */
    public static void logDiscovery(String protocol, String event) {
        logDiscovery(protocol, event, null);
    }

    /**
     * 记录XML响应
     *
     * @param protocol 协议名称
     * @param senderIp 发送者IP
     * @param xmlContent XML内容
     */
    public static void logXmlResponse(String protocol, String senderIp, String xmlContent) {
        // XML 响应记入内存日志（不落盘）
        RuntimeLogStore.record(protocol, senderIp, "XML响应", xmlContent);
        if (!isEnabled()) {
            return;
        }

        String timestamp = getCurrentTime();
        String logEntry = String.format("[%s] [%s] 收到来自 %s 的XML响应\n%s\n%s\n",
                timestamp, protocol, senderIp,
                xmlContent != null ? xmlContent : "(空)",
                "-".repeat(80));

        Log.d(TAG, "[" + protocol + "] XML响应 from " + senderIp);
        writeToFile(logEntry);
    }

    /**
     * 获取调试日志内容
     *
     * @return 日志内容
     */
    public static String getDebugLogs() {
        Context context = getContext();
        if (context == null) return "无法获取Context";
        
        File logFile = getDebugLogFile(context);
        if (!logFile.exists()) {
            return "暂无调试日志记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 开发者调试日志 ===\n");
        sb.append("采集时间: ").append(getCurrentTime()).append("\n");
        sb.append("调试模式: 已开启\n");
        sb.append("========================\n\n");

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            int lineCount = 0;
            List<String> lines = new ArrayList<>();

            // 读取所有行
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

            // 只保留最后MAX_LOG_LINES行
            int startIndex = Math.max(0, lines.size() - MAX_LOG_LINES);
            for (int i = startIndex; i < lines.size(); i++) {
                sb.append(lines.get(i)).append("\n");
            }

        } catch (IOException e) {
            Log.e(TAG, "读取调试日志失败: " + e.getMessage());
            sb.append("\n读取调试日志失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 清除调试日志
     */
    public static void clearDebugLogs() {
        Context context = getContext();
        if (context == null) return;
        
        File logFile = getDebugLogFile(context);
        if (logFile.exists()) {
            logFile.delete();
            Log.d(TAG, "调试日志已清除");
        }
    }

    /**
     * 导出调试日志到指定路径
     *
     * @param targetPath 目标文件路径
     * @return 导出是否成功
     */
    public static boolean exportDebugLogs(String targetPath) {
        Context context = getContext();
        if (context == null) return false;
        
        File sourceFile = getDebugLogFile(context);
        if (!sourceFile.exists()) {
            Log.e(TAG, "调试日志文件不存在");
            return false;
        }

        try {
            File targetFile = new File(targetPath);
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Log.d(TAG, "调试日志已导出: " + targetFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "导出调试日志失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 导出调试日志到Download目录
     */
    public static File exportToDownload() {
        Context context = getContext();
        if (context == null) return null;
        
        File sourceFile = getDebugLogFile(context);
        if (!sourceFile.exists()) {
            return null;
        }

        try {
            File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }

            String fileName = "网络工具箱_调试日志_" + System.currentTimeMillis() + ".txt";
            File targetFile = new File(downloadDir, fileName);

            java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Log.d(TAG, "调试日志已导出到Download: " + targetFile.getAbsolutePath());
            return targetFile;

        } catch (Exception e) {
            Log.e(TAG, "导出调试日志到Download失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 记录虚拟摄像头调试日志
     *
     * 记录虚拟摄像头服务的所有SOAP请求和响应
     *
     * @param message 日志消息
     */
    public static void logVirtualCamera(String message) {
        if (!isEnabled()) {
            return;
        }

        String timestamp = getCurrentTime();
        String logEntry = String.format("[%s] [VirtualCamera] %s",
                timestamp, message);

        // 同时输出到logcat，方便实时查看
        Log.d(TAG, "[VirtualCamera] " + message);

        // 写入文件
        writeToFile(logEntry, VIRTUAL_CAMERA_LOG_FILE);
    }

    /**
     * 记录LanChat调试日志（始终记录）
     *
     * @param message 日志消息
     */
    public static void logLanChat(String message) {
        if (!isEnabled()) return;

        String timestamp = getCurrentTime();
        String logEntry = String.format("[%s] [LanChat] %s",
                timestamp, message);

        Log.d(TAG, "[LanChat] " + message);
        writeToFile(logEntry);
    }

    /**
     * 获取虚拟摄像头日志内容
     *
     * @return 日志内容
     */
    public static String getVirtualCameraLogs() {
        Context context = getContext();
        if (context == null) return "无法获取Context";
        
        File logFile = new File(getDebugDir(context), VIRTUAL_CAMERA_LOG_FILE);
        if (!logFile.exists()) {
            return "暂无虚拟摄像头调试日志记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 虚拟摄像头调试日志 ===\n");
        sb.append("采集时间: ").append(getCurrentTime()).append("\n");
        sb.append("调试模式: 已开启\n");
        sb.append("========================\n\n");

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            int lineCount = 0;
            List<String> lines = new ArrayList<>();

            // 读取所有行
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

            // 只保留最后MAX_LOG_LINES行
            int startIndex = Math.max(0, lines.size() - MAX_LOG_LINES);
            for (int i = startIndex; i < lines.size(); i++) {
                sb.append(lines.get(i)).append("\n");
            }

        } catch (IOException e) {
            Log.e(TAG, "读取虚拟摄像头日志失败: " + e.getMessage());
            sb.append("\n读取虚拟摄像头日志失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 清除虚拟摄像头调试日志
     */
    public static void clearVirtualCameraLogs() {
        Context context = getContext();
        if (context == null) return;
        
        File logFile = new File(getDebugDir(context), VIRTUAL_CAMERA_LOG_FILE);
        if (logFile.exists()) {
            logFile.delete();
            Log.d(TAG, "虚拟摄像头调试日志已清除");
        }
    }

    /**
     * 写入日志到文件（指定文件名）
     */
    private static void writeToFile(String logEntry, String fileName) {
        Context context = getContext();
        if (context == null) return;
        
        File logFile = new File(getDebugDir(context), fileName);
        try {
            // 确保目录存在
            File parentDir = logFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 追加写入
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write((logEntry + "\n").getBytes());
            }

            // 如果文件太大，截断旧日志
            trimLogFile(logFile);

        } catch (IOException e) {
            Log.e(TAG, "写入调试日志失败: " + e.getMessage());
        }
    }

    /**
     * 写入日志到文件（默认设备发现日志）
     */
    private static void writeToFile(String logEntry) {
        writeToFile(logEntry, DISCOVERY_LOG_FILE);
    }

    /**
     * 截断日志文件，保留最后MAX_LOG_LINES行
     */
    private static void trimLogFile(File logFile) {
        if (!logFile.exists()) {
            return;
        }

        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            if (lines.size() > MAX_LOG_LINES * 2) {
                // 保留最后MAX_LOG_LINES行
                int startIndex = Math.max(0, lines.size() - MAX_LOG_LINES);
                List<String> trimmedLines = lines.subList(startIndex, lines.size());

                try (FileOutputStream fos = new FileOutputStream(logFile, false)) {
                    for (String trimmedLine : trimmedLines) {
                        fos.write((trimmedLine + "\n").getBytes());
                    }
                }

                Log.d(TAG, "调试日志已截断，保留最后" + MAX_LOG_LINES + "行");
            }
        } catch (IOException e) {
            Log.e(TAG, "截断日志文件失败: " + e.getMessage());
        }
    }

    /**
     * 获取调试日志文件
     */
    private static File getDebugLogFile(Context context) {
        return new File(getDebugDir(context), DISCOVERY_LOG_FILE);
    }

    /**
     * 获取当前时间字符串
     */
    private static String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }
}
