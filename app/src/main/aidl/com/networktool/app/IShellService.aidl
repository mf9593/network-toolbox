package com.networktool.app;

// 在 Shizuku 进程里以 shell 身份执行命令的接口。
// 这个接口的 Stub 实现类由 Shizuku 在它自己的进程里反射实例化，
// 因此代码跑起来就是 shell 身份，能做 shell 能做的事。
interface IShellService {

    // Shizuku 服务要求的销毁回调，实现类必须提供。
    void destroy() = 16777114;

    // 执行命令，返回标准输出。
    // command: 完整命令与参数；resultCode: 退出码写进数组第 0 位，-1 表示命令没跑起来。
    String exec(in String[] command, out int[] resultCode) = 1;
}
