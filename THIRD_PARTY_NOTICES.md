# 第三方组件与许可

本项目的应用代码使用 Apache License 2.0。以下第三方组件分别遵循其上游许可，具体版本和完整条款以其官方发布为准。

| 组件 | 用途 | 许可 |
|------|------|------|
| AndroidX AppCompat / Core / RecyclerView / ViewPager2 / SwipeRefreshLayout / CoordinatorLayout / ConstraintLayout | Android UI 基础组件 | Apache License 2.0 |
| Material Components for Android | Material 3 UI 组件 | Apache License 2.0 |
| OkHttp / Okio | HTTP 客户端 | Apache License 2.0 |
| Gson | JSON 解析 | Apache License 2.0 |
| Jsoup | HTML 解析 | MIT License |
| AndroidSVG | SVG 解析与渲染 | Apache License 2.0 |
| Jaxen | XPath 引擎 | BSD 风格许可 |
| ZXing Core / ZXing Android Embedded | 二维码扫描 | Apache License 2.0 |
| libmpv（dev.jdtech.mpv:libmpv 0.4.1 AAR） | RTSP / HTTP 直播播放内核 | AAR 封装 MIT；libmpv 原生库 GPL-2.0-or-later |
| JUnit / AndroidX Test | 测试 | Eclipse Public License 1.0 / Apache License 2.0 |

AndroidX、Material、OkHttp、Gson 等组件的许可文本可在其 Maven 坐标对应的源码仓库中获取。分发本项目时，请保留本文件以及各依赖自带的 NOTICE / LICENSE 信息。

如果某个组件的上游许可与本说明不一致，以该组件官方发布内容为准。

> 播放内核使用 libmpv（基于 mpv-android 的 `dev.jdtech.mpv:libmpv` AAR）。支持 RTSP、HTTP MPEG-TS、HLS 等格式；实际能否播放取决于流的封装、编码和设备性能。项目当前不包含 NodeMediaClient；其上游要求商业授权，重新引入前需确认。

> **许可证注意**：AAR 的 Java 封装为 MIT License，但其中打包的 libmpv 原生库启用了 GPL 特性，按 GPL-2.0-or-later 分发。Apache-2.0 与 GPL-2.0-only 不兼容；本 APK 若对外分发，应选择 GPLv3 或其他兼容方式并履行对应源码提供义务。若必须保持纯 Apache-2.0 分发，请改用 LGPL 构建的 libmpv，或移除 libmpv 播放内核。

## 应用许可

```text
Apache License 2.0
Copyright 2026 mf9593 (Network Toolbox)
```

完整文本见项目根目录 `LICENSE` 和 `NOTICE`。
