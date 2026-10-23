package com.networktool.app.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.networktool.app.NetworkToolApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用错误日志工具类
 *
 * 功能说明：
 * - 提供统一的错误日志记录接口
 * - 所有页面的错误都使用统一的 TAG "NetworkTool" 记录
 * - 支持记录异常堆栈
 * - 普通错误只写入 logcat
 *
 * 使用方式：
 * <pre>
 * // 记录错误
 * AppErrorLogger.logError(TAG, "操作失败", exception);
 *
 * // 记录警告
 * AppErrorLogger.logWarning(TAG, "警告信息");
 *
 * // 记录信息
 * AppErrorLogger.logInfo(TAG, "操作成功");
 *
 * // 捕获异常并记录
 * try {
 *     // 可能出错的代码
 * } catch (Exception e) {
 *     AppErrorLogger.catchError(TAG, "执行操作时出错", e);
 * }
 * </pre>
 *
 * 注意：
 * - 所有日志都使用 "NetworkTool" TAG
 * - 崩溃由 GlobalExceptionHandler 单独写入 crash 文件
 * - 崩溃日志保存在应用私有 files/crashes 目录
 */
public class AppErrorLogger {

    /**
     * 统一错误日志 TAG
     * 所有错误都使用此 TAG 记录，方便 LogCollector 统一收集
     */
    public static final String TAG = "NetworkTool";

    /**
     * 错误日志文件名
     */
    private static final String ERROR_LOG_FILE = "error_log.txt";

    /**
     * 最大保留错误日志行数
     */
    private static final int MAX_LOG_LINES = 2000;

    /**
     * 记录错误日志（带异常堆栈）
     * 同时输出到logcat和文件
     *
     * @param component 组件名称（如 "WebDebugActivity"）
     * @param message 错误描述
     * @param throwable 异常对象
     */
    public static void logError(String component, String message, Throwable throwable) {
        String fullMessage = "[" + component + "] " + message;
        Log.e(TAG, fullMessage, throwable);

        // 仅输出 logcat；崩溃由 GlobalExceptionHandler 单独落盘
    }

    /**
     * 记录错误日志（不带异常堆栈）
     * 同时输出到logcat和文件
     *
     * @param component 组件名称
     * @param message 错误描述
     */
    public static void logError(String component, String message) {
        String fullMessage = "[" + component + "] " + message;
        Log.e(TAG, fullMessage);

        // 仅输出 logcat；崩溃由 GlobalExceptionHandler 单独落盘
    }

    /**
     * 捕获异常并记录
     * 同时输出到logcat和文件
     *
     * @param component 组件名称
     * @param message 错误描述
     * @param e 捕获的异常
     */
    public static void catchError(String component, String message, Exception e) {
        String fullMessage = "[" + component + "] " + message;
        Log.e(TAG, fullMessage, e);

        // 仅输出 logcat；崩溃由 GlobalExceptionHandler 单独落盘
    }

    /**
     * 捕获 Throwable 并记录
     * 同时输出到logcat和文件
     *
     * @param component 组件名称
     * @param message 错误描述
     * @param t 捕获的 Throwable
     */
    public static void catchThrowable(String component, String message, Throwable t) {
        String fullMessage = "[" + component + "] " + message;
        Log.e(TAG, fullMessage, t);

        // 仅输出 logcat；崩溃由 GlobalExceptionHandler 单独落盘
    }

    /**
     * 记录警告日志
     *
     * @param component 组件名称
     * @param message 警告描述
     */
    public static void logWarning(String component, String message) {
        String fullMessage = "[" + component + "] " + message;
        Log.w(TAG, fullMessage);
    }

    /**
     * 记录信息日志
     *
     * @param component 组件名称
     * @param message 信息描述
     */
    public static void logInfo(String component, String message) {
        String fullMessage = "[" + component + "] " + message;
        Log.i(TAG, fullMessage);
    }

    /**
     * 记录调试日志
     *
     * @param component 组件名称
     * @param message 调试信息
     */
    public static void logDebug(String component, String message) {
        String fullMessage = "[" + component + "] " + message;
        Log.d(TAG, fullMessage);
    }

    /**
     * 记录操作失败（带 Toast 提示）
     *
     * 同时记录日志并返回错误信息，适合在 UI 线程使用
     *
     * @param component 组件名称
     * @param operation 操作名称（如 "播放视频"、"扫描设备"）
     * @param e 异常对象
     * @return 格式化的错误信息，可直接用于 Toast 显示
     */
    public static String logAndReturnError(String component, String operation, Exception e) {
        String errorMessage = operation + "失败: " + e.getMessage();
        logError(component, errorMessage, e);
        return errorMessage;
    }

    /**
     * 获取异常堆栈字符串
     *
     * @param throwable 异常对象
     * @return 异常堆栈字符串
     */
    public static String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * 获取带时间戳的错误信息
     *
     * @param message 错误描述
     * @return 带时间戳的错误信息
     */
    public static String getTimestampedError(String message) {
        String timestamp = getCurrentTime();
        return "[" + timestamp + "] " + message;
    }

    /**
     * 获取当前时间字符串
     */
    private static String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 获取当前完整时间戳
     */
    private static String getFullTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 获取错误日志目录
     */
    private static File getErrorLogDir() {
        try {
            Context context = NetworkToolApplication.getInstance();
            if (context == null) {
                return null;
            }

            // 优先使用外部存储目录
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                File errorDir = new File(externalDir, "error_logs");
                if (!errorDir.exists()) {
                    errorDir.mkdirs();
                }
                return errorDir;
            }

            // 回退到内部存储
            File internalDir = new File(context.getFilesDir(), "error_logs");
            if (!internalDir.exists()) {
                internalDir.mkdirs();
            }
            return internalDir;
        } catch (Exception e) {
            Log.e(TAG, "获取错误日志目录失败", e);
            return null;
        }
    }

    /**
     * 写入错误到文件
     */
    private static void writeErrorToFile(String component, String message, Throwable throwable) {
        try {
            File errorDir = getErrorLogDir();
            if (errorDir == null) {
                return;
            }

            File logFile = new File(errorDir, ERROR_LOG_FILE);

            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write("[" + getFullTimestamp() + "] [" + component + "] " + message + "\n");

                if (throwable != null) {
                    writer.write(getStackTrace(throwable));
                    writer.write("\n");
                }

                writer.write("-".repeat(80) + "\n");
            }

            // 截断过大的日志文件
            trimErrorLogFile(logFile);
        } catch (Exception e) {
            Log.e(TAG, "写入错误日志文件失败", e);
        }
    }

    /**
     * 截断错误日志文件，保留最后MAX_LOG_LINES行
     */
    private static void trimErrorLogFile(File logFile) {
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

                try (FileWriter writer = new FileWriter(logFile, false)) {
                    for (String trimmedLine : trimmedLines) {
                        writer.write(trimmedLine + "\n");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "截断错误日志文件失败", e);
        }
    }

    /**
     * 获取错误日志文件内容
     *
     * @return 错误日志内容
     */
    public static String getErrorLogs() {
        try {
            File errorDir = getErrorLogDir();
            if (errorDir == null) {
                return "无法获取日志目录";
            }

            File logFile = new File(errorDir, ERROR_LOG_FILE);
            if (!logFile.exists()) {
                return "暂无错误日志记录";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== 应用错误日志 ===\n");
            sb.append("采集时间: ").append(getFullTimestamp()).append("\n");
            sb.append("========================\n\n");

            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                List<String> lines = new ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }

                // 只保留最后MAX_LOG_LINES行
                int startIndex = Math.max(0, lines.size() - MAX_LOG_LINES);
                for (int i = startIndex; i < lines.size(); i++) {
                    sb.append(lines.get(i)).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "读取错误日志失败", e);
            return "读取错误日志失败: " + e.getMessage();
        }
    }

    /**
     * 清除错误日志文件
     */
    public static void clearErrorLogs() {
        try {
            File errorDir = getErrorLogDir();
            if (errorDir != null) {
                File logFile = new File(errorDir, ERROR_LOG_FILE);
                if (logFile.exists()) {
                    logFile.delete();
                    Log.i(TAG, "错误日志已清除");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "清除错误日志失败", e);
        }
    }

    /**
     * 全局未捕获异常处理器
     *
     * 捕获所有未处理的异常，防止应用崩溃，并记录到日志
     */
    public static class GlobalExceptionHandler implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler defaultHandler;
        private final Context context;

        public GlobalExceptionHandler(Context context) {
            this.context = context.getApplicationContext();
            this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            // 记录崩溃信息
            String message = "应用崩溃 - 线程: " + thread.getName() +
                           ", 异常: " + throwable.getClass().getSimpleName() +
                           ", 消息: " + throwable.getMessage();

            Log.e(TAG, "==================== 崩溃日志 ====================");
            Log.e(TAG, message, throwable);
            Log.e(TAG, "设备信息: " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
            Log.e(TAG, "================================================");

            // 保存崩溃日志到文件
            saveCrashLog(throwable);

            // 交给默认处理器处理（通常是系统崩溃对话框）
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        }

        /**
         * 保存崩溃日志到文件
         */
        private void saveCrashLog(Throwable throwable) {
            try {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                        .format(new Date());
                String fileName = "crash_" + timestamp + ".log";

                java.io.File crashDir = new java.io.File(context.getFilesDir(), "crashes");
                if (!crashDir.exists()) {
                    crashDir.mkdirs();
                }

                java.io.File crashFile = new java.io.File(crashDir, fileName);

                try (java.io.FileWriter writer = new java.io.FileWriter(crashFile)) {
                    writer.write("崩溃时间: " + getCurrentTime() + "\n");
                    writer.write("设备信息: " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n");
                    writer.write("================================================\n\n");
                    writer.write(getStackTrace(throwable));
                }

                Log.d(TAG, "崩溃日志已保存: " + crashFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "保存崩溃日志失败", e);
            }
        }
    }
}