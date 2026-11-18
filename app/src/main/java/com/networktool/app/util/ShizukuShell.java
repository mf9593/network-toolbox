package com.networktool.app.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.networktool.app.IShellService;

import rikka.shizuku.Shizuku;

/**
 * Shizuku UserService 的连接管理：把 shell 执行器拉起来，并在上面跑命令。
 *
 * <p>绑定成功后 {@link ShellUserService} 就跑在 Shizuku 的进程里，uid 是 shell，
 * 所以这里执行的命令具备 shell 的权限与可见范围。</p>
 */
public final class ShizukuShell {

    private static ComponentName component;
    private static IShellService service;
    /** 正在等 Shizuku 的绑定回调，避免重复发起绑定 */
    private static boolean binding;

    private ShizukuShell() {
    }

    /** 是否已经连上 Shizuku 进程里的 shell 执行器 */
    public static boolean isReady() {
        return service != null;
    }

    /** 绑定到 Shizuku 进程里的 shell 执行器；已连上或正在绑定时直接返回 */
    public static void bind(Context context) {
        if (service != null || binding) return;
        if (!ShizukuHelper.isRunning()) return;

        component = new ComponentName(context, ShellUserService.class.getName());
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = IShellService.Stub.asInterface(binder);
                binding = false;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                service = null;
            }
        };

        binding = true;
        Shizuku.bindUserService(new Shizuku.UserServiceArgs(component), connection);
    }

    /** 解绑，结束 Shizuku 进程里那个执行器 */
    public static void unbind(Context context) {
        binding = false;
        if (component == null) return;
        try {
            Shizuku.unbindUserService(new Shizuku.UserServiceArgs(component), null, true);
        } catch (Throwable e) {
            // 服务已经不在了也没关系，本地状态照样清掉
        }
        component = null;
        service = null;
    }

    /** 执行一条 shell 命令并读回标准输出 */
    public static ShizukuHelper.Result exec(String command) throws Exception {
        IShellService svc = awaitReady();
        int[] code = new int[1];
        String output = svc.exec(new String[]{"sh", "-c", command}, code);
        return new ShizukuHelper.Result(code[0], output);
    }

    /**
     * 等 shell 执行器连上。
     *
     * <p>绑定是异步的，读当前连接、删网络这些命令必须等它就绪才能跑；等不到就抛异常，
     * 由调用方决定怎么降级（比如当前连接的标记拿不到，就只是不显示「当前连接」而已）。</p>
     */
    private static IShellService awaitReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (service == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Shizuku Shell 未连接，请先完成授权");
            }
            Thread.sleep(50L);
        }
        return service;
    }
}
