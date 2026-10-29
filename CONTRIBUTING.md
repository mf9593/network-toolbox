# 网络工具箱 — 贡献与发布说明

本项目基于 **Apache License 2.0** 开源。欢迎 **fork、二次开发、提交合并（Pull Request）**，
也允许在自己名下发布构建版本。本文说明参与贡献与自行发布的流程与注意事项。

- 仓库地址：<https://github.com/mf9593/network-toolbox>
- 开发约定与架构：[DEVELOPMENT.md](DEVELOPMENT.md)、[AGENTS.md](AGENTS.md)
- 开源许可：[LICENSE](LICENSE)、[NOTICE](NOTICE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 一、参与贡献（合并代码）

1. Fork 本仓库到自己的账号。
2. 新建分支修改，例如 `fix/rtsp-fullscreen`。
3. 本地确保能编译通过：

   ```bash
   gradle assembleDebug
   ```

4. 提交 Pull Request 到 `main`，说明：
   - 改了什么、为什么改
   - 怎么验证（设备、复现步骤、日志）

### 编码约定（提交前自查）

- 全中文注释与交流
- 颜色统一走 `ThemeManager`，禁止硬编码颜色值；支持深色模式
- 所有网络操作放在子线程（`ExecutorService` / Handler / 回调）
- 所有 Activity 加全局错误捕获（`try-catch` + `AppErrorLogger`）
- 可能为 null 的返回值必须判空（`getConnectionInfo()`、`getIntent().getStringExtra()`、
  `SharedPreferences.getString()`、`getCapabilities()` 等）
- Fragment 网络回调里要在 `runOnUiThread` 中加 `isAdded()` 检查
- 不要提交编译产物、签名文件与真实网络数据

## 二、在自己的仓库发布（首次搭建）

1. 在 GitHub 创建**空的公开仓库**（不要勾选自动生成 README）。
2. 推送代码：

   ```bash
   git push -u origin main
   ```

3. 按自己的仓库与证书做好对应配置（仓库信息、签名证书），否则「检查更新」会指向原仓库。

4. 在仓库 **Settings → Pages** 中选择 **GitHub Actions**，`pages.yml` 会自动把 `docs/`
   部署为宣传页。
5. 发布安装包使用 **Actions → Release APK → Run workflow**（填 tag），或推送版本标签：

   ```bash
   git tag v2.1.1 && git push origin v2.1.1
   ```

   工作流会构建 debug APK、校验签名并上传到对应 Release，全程只用 GitHub 自带的
   `GITHUB_TOKEN`，不需要手工创建 Personal Access Token。

### 签名（覆盖升级的前提）

- 发布用的 APK 需要用自己仓库中配置的证书签名，未配置时工作流会直接失败，避免推出签名不一致、无法覆盖安装的包。
- 换成不同证书后，已装旧版的用户需要先卸载再安装（签名不同不能覆盖）。

## 三、发布前注意

- 播放内核使用开源 libmpv（`dev.jdtech.mpv:libmpv` AAR），当前不包含 NodeMediaClient。
  RTSP、HTTP MPEG-TS、HLS 等能否播放取决于流的封装、编码与设备性能；libmpv 播放需要
  Android 8.0 及以上。
- libmpv 原生库启用了 GPL 特性（GPL-2.0-or-later），与 Apache-2.0 的 APK 分发存在许可冲突。
  对外发布前需统一采用 GPLv3 等兼容许可并履行义务，或改用 LGPL 构建。
- NodeMediaClient-Android 的上游明确要求商业授权。若没有可再分发的商业许可证，
  不建议把它的二进制重新加入公开发布的 APK。
- 电视直播默认读取设备内部存储根目录的 `44023.txt`，也可用页面左上角导入文件或手动输入
  路径；直播列表不会打包进 APK。
- 不要提交 `wifi/`、`.bak`、备份 `.zip`、聊天数据库、签名文件或真实网络数据。

## 四、免责声明

本工具仅用于合法的网络学习、维护和自有设备调试。请勿用于未授权的网络扫描、接入或攻击行为。