package com.networktool.app.util;

import android.content.pm.PackageManager;
import android.os.Process;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 接入层。
 *
 * <p>Shizuku 让我们以 <b>shell(uid 2000)</b> 的身份跑代码，系统在 Android 11 起给 shell 授予了
 * 「读取已保存 WiFi 网络」所需的权限，所以不需要 root 就能拿到已保存网络的明文密码。
 * 进程身份由 Shizuku 决定：无线调试 / adb 启动时是 uid 2000，root 启动时是 uid 0。</p>
 */
public final class ShizukuHelper {

    private ShizukuHelper() {
    }

    /** Shizuku 服务是否已启动（无线调试、USB 调试、root 任一方式启动均可） */
    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 是否已经拿到 Shizuku 的使用权限。
     *
     * <p>Shizuku 的授权结果用的是系统权限的常量值（授权=0），不是 Shizuku 自己定义的常量，
     * 所以这里跟 {@link PackageManager#PERMISSION_GRANTED} 比较。</p>
     */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 向 Shizuku 申请使用权限。
     *
     * <p>结果通过 {@link android.app.Activity#onRequestPermissionsResult} 回调，requestCode 用传入的值。</p>
     *
     * @return true 表示请求已发出（用户在弹窗里确认即可），false 表示 Shizuku 没启动或请求没发出去
     */
    public static boolean requestPermission(android.app.Activity activity, int requestCode) {
        if (!isRunning()) return false;
        try {
            Shizuku.requestPermission(requestCode);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Shizuku 服务进程的 uid：0 表示 root 模式启动，2000 表示 ADB / 无线调试启动 */
    public static int getUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable e) {
            return Process.SHELL_UID;
        }
    }

    /** 一条命令的执行结果：退出码 + 输出。退出码 -1 表示命令没能跑起来 */
    public static final class Result {
        public final int code;
        public final String output;

        public Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean isSuccess() {
            return code == 0;
        }
    }
}
