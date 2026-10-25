# 隐私说明

网络工具箱秉持本地优先原则，不收集、不上传用户的 WiFi 密码、设备信息、聊天记录或文件。

## 本机保存的数据

- WiFi 密码数据：保存在应用私有目录 `files/wifi.json`，使用 AES 加密
- 局域网聊天记录：保存在应用私有 SQLite 数据库
- 应用设置、主题、权限状态：保存在应用 SharedPreferences
- 崩溃日志：仅保存在应用私有目录 `files/crashes/`
- 用户主动选择导入或导出的文件：由用户选择的位置保存

## 网络访问

应用在以下场景访问网络：

- 用户主动执行 Ping、端口扫描、DNS、HTTP 请求、网页调试
- 用户主动使用内置浏览器
- 局域网聊天与设备发现（仅局域网）
- 用户主动点击“检查更新”时请求 GitHub Releases API

以上请求不会上传 WiFi 密码、聊天内容或其他个人文件。应用不包含广告 SDK、统计 SDK 或用户画像服务。

## 权限

应用申请的权限用于对应功能，例如 WiFi 扫描/连接、局域网发现、扫码、文件导入导出。详细权限见应用内“权限管理”页面。

## 第三方

应用使用 AndroidX、Material Components、OkHttp、Jsoup、Gson、ZXing、AndroidSVG、Jaxen、libmpv 等开源组件。各组件的许可和用途见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 联系

如对隐私处理有疑问，请通过项目 GitHub Issues 联系维护者。
