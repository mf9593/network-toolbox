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

- 在线地址：<https://mf9593.github.io/network-toolbox/>

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

## 隐私

应用不上传 WiFi 密码、设备信息、聊天内容或其他个人数据。所有业务数据保存在本机应用私有目录中。

- 隐私说明：[PRIVACY.md](PRIVACY.md)

## 开源协议

项目使用 **Apache License 2.0** 开源协议。

完整协议见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

欢迎 fork、二次开发与提交合并，贡献与自行发布流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

仓库地址：<https://github.com/mf9593/network-toolbox>

宣传页：<https://mf9593.github.io/network-toolbox/>

## 免责声明

本工具仅用于合法的网络学习、维护和自有设备调试。请勿用于未授权的网络扫描、接入或攻击行为。
