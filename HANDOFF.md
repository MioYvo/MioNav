# MioNav 交接文档

写给下一个会话。这里只记录**结论、状态、下一步**；细节见 `docs/design.md` 与 `docs/protocol.md`。
最后核对时间：2026-10-29，仓库 `/home/mio/Projects/MioNav`，HEAD `c5369ac`。

---

## 1. 目标

摩托骑行时，手腕上的安卓手表（M60T）显示高德导航的要点：转向箭头、距下一步距离、路名、限速、服务区、岔路口；
背景是**可定制、离线可用**的地图，而不是高德截图。

手机（OPPO Find X8s）负责算路与定位，手表只负责显示。手表跑 Android 13（MIMU UI），屏幕 368×448 / 410×502 / 480×480 不一。

## 2. 已定决策（不要再重新讨论）

| 决策 | 理由 |
| --- | --- |
| **不使用摩托车路径规划**（`AMapCarInfo` + `carType=11` 是付费接口） | 用户明确不提交工单；路由走免费驾车/骑行策略。`AmapNavSource.VehicleProfile` 保留但永远不启用 |
| 手表只显示，不参与算路 | 手表 SoC 弱、无网络依赖、省电 |
| 背景地图用 OSM（WGS84），高德只提供导航数据（GCJ-02） | 自定义样式与离线；坐标在 `AmapNavSource` 边界处转换 |
| 地图以 **MBTiles** 分发 | 就是 SQLite，手表无需自写瓦片解析器 |
| 场景在地**手表本地**生成 | 手机只发 48 字节 NAV；丢包无代价，离线也能画图 |
| 航向：手机发对地航迹，手表骑行时用自己的罗盘覆盖 | 车把上磁力计受发动机干扰；静止时航迹更稳 |
| 分享链接坐标是 GCJ-02，在边界处转 WGS84 | 避免 300–500 m 偏移 |

## 3. 架构

```
手机 Amap Navi SDK ──► NavState ──► 48B NAV 帧 ──BLE──► 手表 HUD
                                        │
手表 MBTiles ──► Mvt 解码 ──► TileSceneBuilder ──► HudRenderer
```

## 4. 现有实现

| 模块 | 文件 | 状态 |
| --- | --- | --- |
| `core/proto` | `Frame.kt` `NavFrame.kt` `Scene.kt` `Chunker.kt` `Crc16.kt` `Le.kt` `StringTable.kt` | 完成，16 测试 |
| `core/geo` | `Gcj.kt` `Projection.kt`（含 Simplify）`ShareLink.kt` `Mvt.kt` | 完成，16 测试 |
| `core/hud` | `Maneuver.kt`（IconMapper）`HudText.kt` | 完成，7 测试 |
| `android/nav` | `NavState.kt` `NavSource.kt` `AmapNavSource.kt` `FakeNavSource.kt` `ShareResolver.kt` | 编译通过，未真机验证 |
| `android/link` | `LinkServer.kt`（GATT server + 广播 + 分片） | 编译通过，未真机验证 |
| `android/pack` | `TileStore.kt`（读 MBTiles）`PackManifest.kt` | 只有读，**没有传输** |
| `android/watch` | `HudActivity.kt` `HudRenderer.kt` `WatchLink.kt` `Palette.kt` `HeadingProvider.kt` `TileSceneBuilder.kt` | APK 1.4 MB，未上表 |
| `android/app` | `MioNavApp.kt` `MainActivity.kt` `ShareReceiver.kt` `ConfirmActivity.kt` `NavService.kt` | APK 131 MB（高德 .so），未真机验证 |

合计 **39 个 JVM 测试**，`./gradlew test` 全绿。

### 关键实现细节

- `AmapNavSource` 的监听器是**动态代理**：`AMapNaviListener` 每个 SDK 版本都会加方法，直连会编译失败。
- 图标表是**从 jar 里读出来的**（`javap -v ... com.amap.api.navi.enums.IconType`）：`LEFT=2`、`RIGHT=3`、`UTURN_LEFT=8`、`STRAIGHT=9`、`ROUNDABOUT=11`、`EXIT_ROUNDABOUT=12`、`SERVICE_AREA=13`、`TOLLGATE=14`、`DESTINATION=15`。
- `strategyConvert` 是**实例方法**，不是静态方法。
- 起点来自静态 `AMapNavi.getNaviLocation()`，返回 `"lon,lat"` 字符串。
- 路径点来自 `AMapNaviPath.getCoordList()`，不是 `steps`。
- 航向：`courseTo()` 用相邻定位算对地航向，圆形平滑（0.7 旧 + 0.3 新），移动小于 3 m 保持原值。
- `HeadingProvider` 优先 `TYPE_ROTATION_VECTOR`，低于 `MOVING_KMH = 8` 冻结；`MOUNT_OFFSET_DEG` 是安装角度修正。
- `TileSceneBuilder` 固定 z14、9 块瓦片、半径 450 m、移动 60 m 才重建、DP 容差 4 m、上限 300 条线 / 每条 14 点。
- MVT 解码器只处理图层、字符串标签、线几何（`type == 2`）。

## 5. 已验证 / 未验证

**已验证**

- 39 个 JVM 测试（协议、CRC、分片乱序、场景预算、坐标转换、简化和量化、图标映射、MVT 解码）
- `:android:app:assembleDebug` 与 `:android:watch:assembleDebug` 构建通过
- 高德 SDK 签名逐个用 `javap` 核对过

**未验证（都是真机项）**

- 高德 key 注册与首次算路（key 需要包名 `io.mionav.app` + debug SHA1）
- BLE 连接、MTU 提升、断线重连
- 手表罗盘在发动机旁的漂移（`MOVING_KMH`、`MOUNT_OFFSET_DEG` 待调）
- `TileSceneBuilder` 没有端上测试，只有 MVT decoder 单测
- `AmapNaviView` 内拍照取证未测

## 6. 环境与命令

```bash
cd /home/mio/Projects/MioNav

# 测试（无需 Android SDK）
./gradlew test

# 高德 SDK 二进制（已装好，且不进 git；换机器重跑）
tools/fetch-amap-sdk.sh

# 构建
./gradlew :android:app:assembleDebug :android:watch:assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk （131 MB，仅 arm64）
#       android/watch/build/outputs/apk/debug/watch-debug.apk （1.4 MB）
```

- Android SDK：`/home/mio/android-sdk`（platform 35、build-tools 35.0.0），`local.properties` 已指向它
- 高德 SDK：AMap3DMap 11.3.100 / AMapNavi 11.3.100 / AMapSearch 9.8.1 / AMapLocation 11.3.101
- Gradle 8.9、AGP 8.5.2、Kotlin 2.0.21、JVM 21
- 代理曾让 Gradle 的 TLS 握手失败：**不要**给 Gradle 配 `systemProp.https.proxyHost`，直连可用

## 7. 下一步计划

### M1 地图包可达（先做，纯软件、可离线验证）

1. 手机侧只读 HTTP 服务：`GET /pack.mbtiles` + `HEAD` 给长度，走热点或局域网
2. 手表侧下载器：落到 `filesDir/tiles.mbtiles`，整包校验和比对
3. `tools/build-pack.sh`：planetiler 抽省域或 200 km 半径，默认 z12–14（z15 可选），产出 MBTiles + manifest
4. **验收**：手表装好包后，关掉手机蓝牙，仍能画出 450 m 路网

### M2 真机可跑（手工为主）

1. 注册高德 key，首次算路成功
2. BLE：确认服务 UUID 能被扫到、MTU 到 512、断线自动重连
3. 罗盘调参、渲染可读性（字号/对比度/夜间）、30 分钟骑行功耗与发热
4. **验收**：一段 30 分钟真实骑行不掉线、不烫、耗电可接受

### M3 巡航模式（最小版，`docs/design.md` §15）

只在系统定位本来就开着时进入；手表不会自己去开定位。

0. 硬件 spike（30 分钟）：`dumpsys location` 确认有没有定位服务、冷启动多久、巡航占空比下的电流
1. 位置来源抽成接口：来自 NAV 帧的、来自手表定位的；切换规则放纯 JVM 模块，可单测
2. 手表加定位权限、巡航角标、占空比（2 s / 5 s / 静止停）
3. **验收**：关蓝牙且系统定位开着时 30 秒内进入巡航，底图随自己位置走；定位关着时冻结不清空

依赖：M1 的 04（手表得先有本地包）。有硬件数据后再开 spec 与票据。

### 待办小项

- `MIO-2` 把五个策略开关（避堵/避收费/避高速…）暴露到设置
- `MIO-11` 事故拍照取证（`AmapNaviView` 截图）
- 夜间模式目前靠点屏幕切换，应改为按时间或光照自动

### 建议的技能路径

设计已定稿，**不要**回到 `/grill-with-docs`。按 ask-matt 的主流程：

```
/to-spec  →  /to-tickets  →  /implement（每票 /tdd，票间 /clear）  →  /code-review  →  /retro
```

- `/to-spec` 与 `/to-tickets` 必须在**同一个未压缩窗口**里跑完，入口材料就是本文件 + `docs/design.md`
- 票据默认落在 `.scratch/<feature>/issues/`（本仓库还没有该目录）
- M2 的硬件项写进票据验收清单即可，不需要专门技能

## 8. 未完成的仓库配置

`setup-matt-pocock-skills` 的提问还没回答。扫描结论与推荐值：

| 项 | 推荐 |
| --- | --- |
| Issue tracker | 本地 markdown（票据进 `.scratch/<feature>/issues/`，已配置） |
| 编辑文件 | `AGENTS.md`（已建，含 `## Agent skills` 三段） |
| Triage 标签 | 保留默认五个：`needs-triage` `needs-info` `ready-for-agent` `ready-for-human` `wontfix` |
| 领域文档 | 单上下文：根 `GLOSSARY.md` + `docs/adr/`（无 monorepo 信号） |

## 9. 新会话开场建议

1. 读 `HANDOFF.md`（本文件）→ `docs/design.md` → `docs/protocol.md`
2. 运行 `./gradlew test` 确认基线绿
3. 从 `/to-spec` 开始，范围就是上面的 M1
