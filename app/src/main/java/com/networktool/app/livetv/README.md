# 电视直播（livetv）模块

这个目录是「电视直播」功能的全部源码，可以整体拷出去做成独立的电视直播 App。

## 目录内容

| 文件 | 作用 |
| --- | --- |
| `LiveTvActivity.java` | 直播主页：播放页、顶部控制栏、左侧抽屉（分类标签栏 + 频道列表）、线路切换、GitHub 云同步、DLNA 投屏、播放设置弹窗、自动换源 |
| `LiveChannel.java` | 频道数据模型：名称 / 分组 / 多条线路（同一频道多个地址） |
| `LivePlaylistParser.java` | 直播源解析：M3U/M3U8、TXT（`分组,#genre#`）、JSON（DIYP / TVBox） |
| `LiveChannelAdapter.java` | 频道列表适配器：按分类标签筛选、主题色选中、线路数量徽标 |

## 支持的直播源格式

**M3U / M3U8**

```
#EXTM3U
#EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV-1 综合
http://example.com/cctv1.m3u8
#EXTGRP:卫视
#EXTINF:-1,湖南卫视
http://example.com/hunan.m3u8
```

**TXT（常见 IPTV 列表 / DIYP）**

```
央视,#genre#
CCTV-1 综合,http://a/1.m3u8#http://b/1.m3u8
CCTV-2 财经,http://a/2.m3u8
```

同一频道多地址用 `#` 分隔，会自动合并成多条线路。

**JSON（DIYP / TVBox 直播源）**

```json
{"lives":[{"group":"央视","channels":[{"name":"CCTV-1 综合","urls":["http://a/1.m3u8","http://b/1.m3u8"]}]}]}
{"urls":[{"name":"CCTV1","url":"http://a.m3u8","group":"央视"}]}
```

## 依赖（拷出去时需要一并带走 / 替换）

外部类：

- `com.networktool.app.player.RtspPlayer`、`RtspPlayerView`、`GainAudioProcessor` — ExoPlayer 播放内核（含 PCM 音量增强）
- `com.networktool.app.cast.*` — DLNA 投屏（`SsdpDiscovery`、`DlnaController`、`DlnaDevice`、`CastFileServer`）
- `com.networktool.app.util.ThemeManager` — 主题色（可换成自己的色值）
- `com.networktool.app.util.AppErrorLogger` — 错误日志（可换成 `Log.e`）
- `com.networktool.app.R` — 资源 ID

资源文件：

- 布局：`activity_live_tv.xml`、`item_live_channel.xml`、`dialog_player_settings.xml`、`item_player_option.xml`
- 背景：`bg_glass_panel.xml`、`bg_dialog_card.xml`、`bg_circle_button.xml`
- 图标：`ic_cloud_sync`、`ic_import`、`ic_cast`、`ic_play`、`ic_pause`、`ic_volume_up`、`ic_volume_off`、`ic_arrow_back`、`ic_live_tv`

## SharedPreferences（文件名 `live_tv`）

| 键 | 含义 |
| --- | --- |
| `last_playlist_path` | 上次使用的列表文件路径 |
| `hwdec_mode` | 解码方式（`mediacodec` / `no`） |
| `render_type` | 渲染方式（`textureview` / `surfaceview`） |
| `audio_gain` | 音量增强倍数（1.0 / 1.5 / 2.0 / 3.0） |
| `auto_switch_seconds` | 自动换源超时秒数，0 = 关闭，最小 5 秒 |
| `github_playlist_url` | GitHub 云同步地址 |
| `panel_width_ratio` | 抽屉宽度占屏幕比例（1/4 ~ 1/2） |
| `src_<hash>` | 每个频道上次使用的线路下标 |

## 入口

`AndroidManifest.xml` 中注册为 `com.networktool.app.livetv.LiveTvActivity`，
`ToolsFragment` 里通过 `open(LiveTvActivity.class)` 打开。
