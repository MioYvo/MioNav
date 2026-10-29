# MioNav 交接文档

写给下一个会话。这里只记录**结论、状态、下一步**；细节见 `docs/design.md` 与 `docs/protocol.md`。
最后核对时间：2026-10-29，仓库 `/home/mio/Projects/MioNav`，HEAD `0271123`。

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
| `core/proto` | `Frame.kt` `NavFrame.kt` `Scene.kt` `Chunker.kt` `Crc16.kt` `Le.kt` `StringTable.kt` `HelloFrame.kt` | 完成，22 测试 |
| `core/geo` | `Gcj.kt` `Projection.kt`（含 Simplify）`ShareLink.kt` `LinkFollower.kt` `Mvt.kt` `Traffic.kt` | 完成，32 测试 |
| `core/hud` | `Maneuver.kt`（IconMapper）`HudText.kt` | 完成，7 测试 |
| `core/voice` | `VoicePlanner.kt` `Phrases.kt` `Announcement.kt` | 完成，8 测试 |
| `core/tilepack` | `PackServer.kt` `PackClient.kt` `PackIdentity.kt` `PackNegotiation.kt` `PackSidecar.kt` `PackVerifier.kt` | 完成，24 测试 |
| `android/nav` | `NavState.kt` `NavSource.kt` `AmapNavSource.kt` `FakeNavSource.kt` `ShareResolver.kt` | 算路/定位真机验证 |
| `android/link` | `LinkServer.kt`（GATT server + 广播 + 分片） | 编译通过，**未上机** |
| `android/pack` | `TileStore.kt`（读 MBTiles）`PackManifest.kt` | 导入与按需起服已接；同步在 `core/tilepack`，**未上机** |
| `android/watch` | `HudActivity.kt` `HudRenderer.kt` `WatchLink.kt` `Palette.kt` `HeadingProvider.kt` `TileSceneBuilder.kt` | APK 1.4 MB，未上表 |
| `android/app` | `MioNavApp.kt` `MainActivity.kt` `ShareReceiver.kt` `ConfirmActivity.kt` `MapPalette.kt` `NavService.kt` `TtsAnnouncer.kt` | 分享→算路→选路→导航服务真机验证 |

合计 **93 个 JVM 测试**（proto 22 / geo 32 / hud 7 / voice 8 / tilepack 24），`./gradlew test` 全绿。

### 关键实现细节

- `AmapNavSource` 的监听器是**动态代理**：`AMapNaviListener` 每个 SDK 版本都会加方法，直连会编译失败。
- 图标表是**从 jar 里读出来的**（`javap -v ... com.amap.api.navi.enums.IconType`）：`LEFT=2`、`RIGHT=3`、`UTURN_LEFT=8`、`STRAIGHT=9`、`ROUNDABOUT=11`、`EXIT_ROUNDABOUT=12`、`SERVICE_AREA=13`、`TOLLGATE=14`、`DESTINATION=15`。
- `strategyConvert` 是**实例方法**，不是静态方法。
- 起点来自静态 `AMapNavi.getNaviLocation()`，返回 `"lon,lat"` 字符串。
- 路径点来自 `AMapNaviPath.getCoordList()`，不是 `steps`。
- 航向：`courseTo()` 用相邻定位算对地航向，圆形平滑（0.7 旧 + 0.3 新），移动小于 3 m 保持原值。
- `HeadingProvider` 优先 `TYPE_ROTATION_VECTOR`，低于 `MOVING_KMH = 8` 冻结；`MOUNT_OFFSET_DEG` 是安装角度修正。
- `TileSceneBuilder` 固定 z14、9 块瓦片、半径 450 m、移动 60 m 才重建、DP 容差 4 m、上限 300 条线 / 每条 14 点。
- 选路页的三条渲染铁律：`AndroidView.update` 只画一遍（`post` 仅修首帧布局）、换选路线不重设镜头、
  进 `addPolyline` 前按 1.5 px 去重（1542 点 → 约 400 点）。箭头间距用 `AMap.projection` 换成屏幕坐标算，
  不能用 `cameraPosition.zoom`：`moveCamera` 之后立刻读能差一个层级以上。
- MVT 解码器只处理图层、字符串标签、线几何（`type == 2`）。

## 5. 已验证 / 未验证

**已验证**

- 93 个 JVM 测试（协议、CRC、分片乱序、场景预算、坐标转换、简化和量化、图标映射、MVT 解码、语音播报规划、
  包服务三组路由、续传与校验、握手判定、HELLO 编解码、gzip 瓦片、一片真实 planetiler 输出）
- `:android:app:assembleDebug` 与 `:android:watch:assembleDebug` 构建通过
- 高德 SDK 签名逐个用 `javap` 核对过
- `tools/build-pack.sh verify`：干净的包返回 0，改一行的包返回 1 并指出 sha256 与瓦片数不符
- 切片跑通了，实测（z12–14，`tools/build-pack.sh`）：
  | 区域 | 瓦片数 | 全图层 | 只要道路与水面 |
  | --- | --- | --- | --- |
  | 马恩岛 | 1,317 | 5.0 MB | 1.1 MB |
  | 浙江 | 52,890 | 167 MB | **59 MB** |
  浙江切片 36 秒（`-Xmx8g`）。省域包只有 59 MB，手表存储不是问题
- 这份包的一片能被 `Mvt.decode` 解出 `transportation` 图层（测试夹具 `core/geo/src/test/resources/mvt/`）
- 层过滤是最大的一根体积杠杆：`--layers transportation,water` 把浙江从 167 MB 压到 59 MB（35%），
  比 design §12.3 原先估的 40–60% 更狠，已在文档里改成实测值

**手机侧已打通（2026-10-29，真机验证）**

- 高德分享 → MioNav：`surl.amap.com` 短链三跳后解析坐标，算路出多条候选，地图画出整条路线
  （起终点标记 + 布局后重新 fit）
- 定位：先用 `AMapNavi` 的 GPS 解，8 秒没有就用 `AMapLocationClient` 的 last known（只信 10 分钟内的），
  再没有才报可操作的错误
- 调试：`adb logcat -s MioNav` 一条 tag 串起分享→跳转→解析→算路；`tools/device.sh`
  封装 install/logcat/screencap/模拟分享；debug 包可用 `--es start "lat,lon"` 指定起点
- 冷启动一律 `tools/device.sh confirm [START DEST]`：内部 `am start -S` 先强制停止。普通 `am start`
  会复用运行中的实例、参数走 `onNewIntent`，界面停在「没有可用的分享目标」；脚本在 debug 钩子
  没落上时自动重试一次
- 确认页地图：多路线叠加（选中 26 px 描边 + 路况分段上色，未选整条画在描边下面），方向箭头按
  **屏幕距离** 110 px 排、下限 650 m（Amap 的 `setRotateAngle` 是逆时针，要传 `360 - bearing`），
  起终点带名字气泡，右上角指南针按钮一键回到正北
- 选路性能（真机实测，176 km / 3 条候选）：一次点击 ≈ 70 ms 主线程，重画一遍；改前是 150 ms × 2
  加两次镜头跳。`MioNav: map.draw` 打印 `points/drawn/arrows/zoom/ms` 与
  `phases(clear/fit/alt/slices/route/arrows)`，退步可量
- 路线卡片就是按钮：没有「已选／选它」，选中态靠 `primaryContainer` 底色 + 2 dp 主色描边

**未验证（都是真机项）**
- BLE 连接、MTU 提升、断线重连
- 手表罗盘在发动机旁的漂移（`MOVING_KMH`、`MOUNT_OFFSET_DEG` 待调）
- `AmapNaviView` 内拍照取证未测
- 表上真机的 `TileSceneBuilder` 仍未验证（只有喂真瓦片的 decoder 单测）

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

如果 `assembleDebug` 报 `The given file '/home/mio/pi-cwd/.../Le.class' is located outside the root
directory`，那是 Gradle daemon 从另一个工作目录起的，与代码无关：`./gradlew --stop`，然后加
`--no-daemon` 重跑。
- 高德 SDK：AMap3DMap 11.3.100 / AMapNavi 11.3.100 / AMapSearch 9.8.1 / AMapLocation 11.3.101
- 正式签名库：`/home/mio/mionav-keys/mionav-release.jks`（别名 `mionav`，PKCS12，口令在同目录
  `mionav-release.credentials`）。发布版 SHA1 `EB:5A:2B:ED:…:2A:38`。丢了就签不出可覆盖的包
- 构建用的秘密都走 `local.properties`（已 gitignore）：`mionav.amapKey`、`mionav.releaseStore`、
  `mionav.releaseStorePassword`、`mionav.releaseKeyAlias`、`mionav.releaseKeyPassword`
- Gradle 8.9、AGP 8.5.2、Kotlin 2.0.21、JVM 21
- 代理曾让 Gradle 的 TLS 握手失败：**不要**给 Gradle 配 `systemProp.https.proxyHost`，直连可用

## 7. 下一步计划

### M1 地图包可达（先做，纯软件、可离线验证）

票据在 `.scratch/m1-pack/issues/`：01 模块与 HTTP 服务、02 下载器、03 手机导入与按需起服、
04 手表握手同步、05 打包脚本，这五张全部完成（05 连马恩岛切片都跑过了）；06 是真机验收，等人上机。

打包时需要两样东西，都已缓存在 `~/.cache/mionav`：planetiler 本体，以及它的 1.3 GB 辅助源
（湖心线、水域多边形、自然地球）。后者是下载瓶颈，用 `curl -C -` 直接拉比让 planetiler 自己下快。

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
