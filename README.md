# 网络工具箱

一款面向 Android 平台的本地网络调试与诊断工具，采用 Java + Material Design 3 开发。

> 当前版本：2.1.0 (210)

## 功能

- 网络信息与连接状态
- WiFi 扫描、连接、密码数据导入/导出
- 局域网设备发现、IPv4 / IPv6、协议筛选
- Ping、端口扫描、DNS、路由追踪、IP 扫描
- SADP / ONVIF 摄像头调试
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

项目使用 **GNU General Public License v3.0 (GPL-3.0)** 开源协议。

完整协议见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

仓库地址：<https://github.com/mf9593/network-toolbox>

宣传页：<https://mf9593.github.io/network-toolbox/>

## 免责声明

本工具仅用于合法的网络学习、维护和自有设备调试。请勿用于未授权的网络扫描、接入或攻击行为。
