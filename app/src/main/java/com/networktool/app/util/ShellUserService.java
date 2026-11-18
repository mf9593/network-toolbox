package com.networktool.app.util;

import androidx.annotation.Keep;

import com.networktool.app.IShellService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 跑在 Shizuku 进程里的 shell 执行器。
 *
 * <p>Shizuku 的 UserService 不是 Android Service：Shizuku 服务在它自己的进程里按类名反射
 * 实例化这个类，所以这段代码的进程就是 Shizuku 的进程，uid 是 shell(2000)。
 * 正因为是进程身份生效，这里才能用 {@code cmd wifi} 这类只有 shell 能跑的命令。</p>
 *
 * <p>必须提供无参构造方法——Shizuku 只能用反射 new 出来。</p>
 */
@Keep
public class ShellUserService extends IShellService.Stub {

    public ShellUserService() {
    }

    @Override
    public String exec(String[] command, int[] resultCode) {
        if (resultCode != null) {
            resultCode[0] = -1;
        }
        if (command == null || command.length == 0) {
            return "";
        }

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
            String output = readAll(process.getInputStream());
            int code = process.waitFor();
            if (resultCode != null) {
                resultCode[0] = code;
            }
            return output;
        } catch (Throwable e) {
            // 命令没跑起来，退出码已经记为 -1，返回空输出让调用方判断
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public void destroy() {
        // Shizuku 结束 UserService 时会调到这里，直接退出进程释放资源
        System.exit(0);
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
