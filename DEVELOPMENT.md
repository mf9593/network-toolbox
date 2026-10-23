# 网络工具箱 — 开发文档

> 破晓 · Android 网络调试与诊断工具

---

## 一、项目概述

**网络工具箱**是一个基于 Android 平台的网络工具集合应用，采用纯 Java 语言开发。提供网络调试、WiFi管理、摄像头协议调试、局域网通信及设备扫描等功能。

### 技术栈

| 项目 | 版本 |
|------|------|
| 开发语言 | Java 21 |
| Android SDK | 34（编译），21+（运行） |
| 最低支持 | Android 5.0 (API 21) |
| UI 框架 | Material Design 3 |
| 网络库 | OkHttp 4.12.0 |
| RTSP 播放 | Media3 ExoPlayer 1.4.1 |
| HTML 解析 | Jsoup 1.15.3 |
| JSON 解析 | Gson 2.10.1 |
| 构建工具 | Gradle 8.13 |
| AGP | 8.7.3 |

### 应用信息

- **包名**：`com.networktool.app`
- **架构**：arm64-v8a, armeabi-v7a
- **License**：Apache License 2.0

---

## 二、项目结构

```
网络工具箱/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/networktool/app/
│   │   ├── NetworkToolApplication.java
│   │   ├── activity/
│   │   │   ├── MainActivity.java          # 主Activity（5 Tab底部导航）
│   │   │   ├── BaseToolActivity.java      # 工具页基类
│   │   │   ├── tool/                      # 各工具Activity
│   │   │   └── settings/                  # 设置页Activity
│   │   ├── fragment/                      # Fragment
│   │   ├── adapter/                       # 适配器
│   │   ├── model/                         # 数据模型
│   │   ├── service/                       # 后台服务
│   │   ├── widget/                        # 自定义控件
│   │   └── util/                          # 工具类
│   ├── res/                               # 资源文件
│   └── assets/                            # 资产文件（隐私政策、用户协议）
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── .gitignore
└── AGENTS.md
```

---

## 三、构建与运行

```bash
gradle clean           # 清理
gradle assembleDebug   # 构建 Debug APK
```

**注意**：必须用 `gradle`，不要用 `gradlew`。

- Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK 输出：`app/build/outputs/apk/release/app-release.apk`

---

## 四、核心功能模块

### 底部导航 Tab

| Tab | Fragment | 功能 |
|-----|----------|------|
| 概览 | InfoFragment | 当前网络、关键指标、信号曲线、网络详情、快捷工具 |
| 局域网 | DeviceFragment | 设备发现、扫描状态、IP 版本/协议筛选、设备详情 |
| WiFi | WirelessFragment | 当前连接、真实扫描、频段/标准筛选、网络详情 |
| 工具 | ToolsFragment | 分组工具、搜索、最近使用、收藏 |
| 设置 | SettingsFragment | 网络与扫描、外观、系统、隐私与关于 |

### 工具列表

**基础工具**：Ping、端口扫描、DNS查询、IP扫描、MAC查询、网页测速、网页调试、HTTP调试、内置浏览器、应用管理、路由跃点

**设备调试**：ONVIF调试、SADP调试、RTSP播放器、局域网聊天

**WiFi工具**：WiFi导入（支持文件/网络）、WiFi导出、WiFi数据库、已保存网络

---

## 五、开发约定

### 5.1 关键规则

1. **全中文交流**
2. **UI 改动必须先询问**
3. **应用 LOGO 资源**：桌面图标使用 `@drawable/icon`（AndroidManifest 声明），不是 `@mipmap/ic_launcher`
4. **所有颜色通过 `ThemeManager` 管理，禁止硬编码颜色值**
5. 所有网络操作必须在子线程
6. 所有 Activity 必须加全局错误捕获
7. ONVIF 与 SADP 协议必须独立实现，禁止混用
8. 支持深色模式
9. `usesCleartextTraffic=true`

### 5.2 主题色使用规范

- **气泡/卡片背景色**：`ThemeManager.getSurfaceColor(context)`
- **卡片容器**：`MaterialCardView` + `ThemeManager.setupResultCard(card, context)`
- **Chip背景色**：`ThemeManager.getChipSurfaceColor(context)`
- **按钮背景色**：`setBackgroundTintList(ColorStateList.valueOf(themeColor))` + `setTextColor(contrastColor)`
- **Fragment 中的按钮/文字高亮**：必须在 `initViews()` 中用 `ThemeManager.getThemeColor()` 代码设置
- **RadioButton/Switch/CheckBox 选中色**：XML 加 `android:buttonTint="?attr/colorPrimary"`
- **BottomSheetDialog 弹窗控件**：手动应用主题色
- **所有自定义控件选中/激活颜色**：从 ThemeManager 获取

### 5.3 防崩溃惯例

- `getConnectionInfo()`、`getIntent().getStringExtra()`、`SharedPreferences.getString()`、`getCapabilities()` 等可能返回 null，必须判 null 或用 `--` 占位
- Fragment 网络回调内必须在 `runOnUiThread` 中加 `isAdded()` 检查
- `setText()` 参数永远不要传可能为 null 的对象

### 5.4 添加新工具/设置页

1. 在 `activity/tool/` 或 `activity/settings/` 下创建 Activity
2. 工具页继承 `BaseToolActivity`
3. `AndroidManifest.xml` 注册 Activity
4. `ToolsFragment` 或 `SettingsFragment` 添加入口

---

## 六、局域网扫描架构

### 6.1 协议优先级

| 优先级 | 协议 | 标签 | 说明 |
|--------|------|------|------|
| 1 | ONVIF | `"ONVIF"` | 摄像头WS-Discovery |
| 2 | SADP | `"SADP"` | 海康威视设备发现 |
| 3 | 路由器 | `"路由器"` | 网关识别 |
| 4 | SSDP | `"SSDP"` | UPnP设备发现 |
| 5 | mDNS | `"mDNS"` | 组播DNS |
| 6 | NetBIOS | `"NetBIOS"` | Windows主机名 |
| 7 | ARP表 | `"ARP表"` | ARP缓存 |
| 8 | UDP广播 | `"UDP广播"` | 广播探测 |
| 9 | HTTP | `"HTTP"` | HTTP标题获取 |
| 10 | Ping | `"Ping探测"` | ICMP探测 |

数字越小优先级越高，同优先级后执行覆盖前者。

### 6.2 SSDP设备IP提取

SSDP的设备列表和广播日志IP优先从 LOCATION 头提取，回退到 senderIp。

### 6.3 ONVIF WS-Discovery

搜索响应中 `<wsdd:Scopes>` 包含：
- `onvif://www.onvif.org/name/设备名`
- `onvif://www.onvif.org/Hardware/硬件型号`
- `onvif://www.onvif.org/location/位置`

---

## 七、局域网聊天架构

### 7.1 核心组件

| 组件 | 职责 |
|------|------|
| LanChatManager | 设备发现、TCP连接管理、消息路由 |
| LanPeer | 远程聊天节点（IP、端口、用户名） |
| ChatMessage | 聊天消息数据结构 |

### 7.2 通信协议

| 功能 | 传输方式 | 说明 |
|------|----------|------|
| 用户发现 | UDP组播 | 局域网广播自身存在 |
| 好友聊天 | TCP单播 | 可靠、有序 |
| 房间聊天 | UDP组播 | 一对多 |
| 丢包恢复 | TCP单播NACK | 组播可靠性的补充 |

### 7.3 组播地址

- 公共发现：`239.255.0.1`
- 房间组播：`239.255.{high}.{low}:5001`

### 7.4 UID / DeviceId 约定

- `myUid`：持久化存储在 SharedPreferences，跨进程重启保持一致
- `deviceId`：**每次进程启动随机生成**（`UUID.randomUUID()`），不持久化
- **自过滤仅用 `deviceId`**，禁止用 UID+IP 组合过滤

### 7.5 同IP多开规则

- 两个实例各有不同 `deviceId`，通过 `deviceId` 区分自身
- 两个实例共享同一 `myUid`，**不能用 UID 判断是否是自己**
- Peer 查找优先用 UID 匹配
- 新建 Peer 时必须调用 `setUid()`

### 7.6 通知生命周期

- Activity/Fragment 在 `onPause()` 必须 `removePeerChangeListener(this)`
- Activity/Fragment 在 `onResume()` 必须 `addPeerChangeListener(this)`

### 7.7 聊天消息渲染

- 气泡用 `GradientDrawable` 编程绘制，不要用 `setBackgroundResource()` + `setBackgroundColor()`
- 发送消息：`LayoutParams.gravity = Gravity.END` 靠右
- 接收消息：`LayoutParams.gravity = Gravity.START` 靠左

---

## 八、数据导出复用规范

- WiFi数据的JSON构建、加密、解析逻辑统一由 `WifiDataManager` 负责
- `WifiDataManager.exportToString()` 导出全量，`exportSelectedToString(List)` 导出选中项
- 聊天室发送WiFi数据时必须调用 `WifiDataManager.exportSelectedToString()` + `AESUtil.encrypt()`
- WiFi数据导入统一用 `WifiDataManager.parseWifiData()` + `importData()`
- `AESUtil.encrypt()`/`decrypt()` 是唯一的加密入口

---

## 九、常用类说明

### Activity

| 类名 | 说明 |
|------|------|
| `MainActivity` | 主Activity，底部导航+ViewPager2 |
| `BaseToolActivity` | 工具页基类（标题栏、状态栏沉浸、返回按钮） |
| `PingActivity` | Ping工具 |
| `PortScanActivity` | 端口扫描 |
| `DnsActivity` | DNS查询 |
| `IpScanActivity` | IP扫描 |
| `OnvifActivity` | ONVIF设备调试 |
| `RtspPlayerActivity` | RTSP流播放器 |
| `WebViewActivity` | 内置浏览器 |
| `HttpDebugActivity` | HTTP调试 |
| `AboutActivity` | 关于软件 |

### Fragment

| 类名 | 说明 |
|------|------|
| `InfoFragment` | 网络信息页面 |
| `DeviceFragment` | 设备扫描页面 |
| `WirelessFragment` | WiFi信息页面 |
| `ToolsFragment` | 工具列表页面 |
| `SettingsFragment` | 设置页面 |

### 工具类

| 类名 | 说明 |
|------|------|
| `ThemeManager` | 主题颜色管理 |
| `NetworkUtils` | 网络工具类 |
| `AppErrorLogger` | 错误日志记录器 |
| `LanScanManager` | 局域网扫描管理器 |
| `OnvifUtil` | ONVIF协议工具类 |
| `WifiDataManager` | WiFi数据管理（单例） |
| `ScanSpeedConfig` | 扫描速度配置 |
| `DebugLogger` | 调试日志管理 |
| `LanChatManager` | 局域网聊天管理（单例） |

---

## 十、预设主题颜色

| 名称 | 颜色值 |
|------|--------|
| 经典绿（默认） | `#00DAC5` |
| 蓝色 | `#2196F3` |
| 紫色 | `#9C28B1` |
| 红色 | `#FE5722` |
| 粉色 | `#FF8880` |
| 绿色 | `#8BC24A` |
| 青色 | `#00BCD4` |

---

## 十一、应用权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络访问 |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | WiFi状态管理 |
| `ACCESS_NETWORK_STATE` | 网络状态查看 |
| `WRITE/READ_EXTERNAL_STORAGE` | 存储访问 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 位置信息（WiFi扫描需要） |
| `CAMERA` | 虚拟摄像头 |
| `VIBRATE` | 振动控制 |

---

## 十二、行为准则

### 编码前思考

- 明确陈述假设，不确定就询问
- 存在多种解释时提出来，不要默默选择
- 有更简单的方法要说出来

### 简洁优先

- 不要实现超出要求的功能
- 不要为只使用一次的代码创建抽象
- 不要添加未被要求的"灵活性"

### 精准修改

- 只修改必须修改的内容
- 匹配现有风格
- 删除因更改而变得无用的导入/变量/函数

### 目标驱动

- 将任务转化为可验证的目标
- 多步骤任务声明简要计划
- 循环验证直到达成目标
