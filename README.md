# 网络工具箱

一款面向 Android 平台的本地网络调试与诊断工具，采用 Java + Material Design 3 开发。

> 当前版本：2.1.0 (210)

## 功能

- 网络信息与连接状态
- WiFi 扫描、连接、密码数据导入/导出
- 局域网设备发现、IPv4 / IPv6、协议筛选
- Ping、端口扫描、DNS、路由追踪、IP 扫描
- SADP / ONVIF 摄像头调试
- 电视直播：左侧竖排分类 + 右侧频道列表，支持 M3U / M3U8 / TXT(`#genre#`) / JSON(DIYP、TVBox) 直播源
  - 同一频道多条地址自动合并为多线路，播放页可切换并记忆
  - 播放超时自动换源（可设 5/8/10/15/30 秒）
  - GitHub 云同步直播源（支持镜像加速与本地缓存）
  - 音量增强（PCM 软件增益 100%~300%）、解码方式 / 渲染方式切换
- 投屏到电视：内置 DLNA / UPnP（SSDP 发现 + SOAP 控制），可投本地图片、视频与直播链接
- 局域网聊天
- 内置浏览器、HTTP / 网页调试
- MAC 厂商查询、图标转换等工具箱

## 宣传页

项目包含一个响应式宣传页，源码位于 `docs/`：

- 在线地址：<https://mf9593.github.io/network-toolbox/>
- 本地预览：`python3 -m http.server 8080 --directory docs`
- GitHub Pages 通过 `.github/workflows/pages.yml` 自动部署

## 构建

环境：

- JDK 21
- Android SDK 34
- Gradle 8.13 / Android Gradle Plugin 8.7.3

```bash
./gradlew assembleDebug
```

生成：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub 仓库配置

开源仓库地址和“检查更新”功能通过 `gradle.properties` 配置：

```properties
github.owner=mf9593
github.repo=network-toolbox
```

配置后重新构建，关于页会显示仓库地址，设置页的“检查更新”会请求：

```text
https://api.github.com/repos/<owner>/<repo>/releases/latest
```

发布 Release 时请使用语义化版本标签，例如 `v2.1.1`。

## 隐私

应用不上传 WiFi 密码、设备信息、聊天内容或其他个人数据。所有业务数据保存在本机应用私有目录中。

- 隐私说明：[PRIVACY.md](PRIVACY.md)
- 应用内隐私政策：`app/src/main/assets/privacyPolicy.md`
- 用户协议：`app/src/main/assets/userAgreement.md`

请勿将以下内容提交到 GitHub：

- WiFi 备份样本（`.bak`、备份 `.zip`、`wifi/` 目录）
- 实际 WiFi 配置、聊天数据库、崩溃日志
- `local.properties`、签名文件、密钥、`.env`

项目 `.gitignore` 已默认排除这些文件。

## 开源协议

项目使用 **Apache License 2.0** 开源协议。

完整协议见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

仓库地址：<https://github.com/mf9593/network-toolbox>

宣传页：<https://mf9593.github.io/network-toolbox/>

### 首次发布

1. 在 GitHub 创建空的公开仓库 `network-toolbox`（不要勾选自动生成 README）。
2. 推送本地代码：

```bash
git push -u origin main
```

3. 在仓库 Settings → Pages 中选择 GitHub Actions，`pages.yml` 会自动部署 `docs/`。
4. 创建版本标签并发布 Release，附上 `app-debug.apk` 或签名 APK，检查更新功能即可使用。

### 发布前注意

- 播放内核使用开源 libmpv（`dev.jdtech.mpv:libmpv` AAR），当前不包含 NodeMediaClient。RTSP、HTTP MPEG-TS、HLS 等能否播放取决于流的封装、编码和设备性能；libmpv 播放需要 Android 8.0 及以上。
- libmpv 原生库启用了 GPL 特性（GPL-2.0-or-later），与 Apache-2.0 的 APK 分发存在许可冲突。对外发布前需统一采用 GPLv3 等兼容许可并履行义务，或改用 LGPL 构建。
- 电视直播默认读取内部存储根目录的 `44023.txt`，也可通过页面左上角导入文件或手动输入路径；列表不会打包进 APK。
- NodeMediaClient-Android 的上游明确要求商业授权。若没有可再分发的商业许可证，不建议将它的二进制重新加入公开发布的 APK。
- 不要提交 `wifi/`、`.bak`、备份 `.zip`、聊天数据库、签名文件或真实网络数据。

## 免责声明

本工具仅用于合法的网络学习、维护和自有设备调试。请勿用于未授权的网络扫描、接入或攻击行为。
