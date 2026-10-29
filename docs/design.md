# 摩托导航 HUD 设计

**显示端**：M60T（展锐 W527，Android 13 系，MIMU UI 3.x）
**引擎端**：OPPO Find X8s（ColorOS 15 / Android 15）
**导航数据**：高德 Android 导航 SDK
**底图**：OpenStreetMap 矢量瓦片（MBTiles 离线包，预装进手表）+ 自写 style

---

## 0. 目标与非目标

目标：

1. 车把上 0.5 秒内读懂：下一步动作、还有多远、当前限速、下一个服务区。
2. 底图可换风格，改配色不改数据。
3. 手表表面温度可控，连续 2 小时不降频、不关机。
4. 断连 10 秒内自恢复，恢复后状态正确。

非目标：

1. 手表独立算路（不做，热与电都不划算）。
2. 手表在线拉瓦片（改成预装 MBTiles；在线的场景被离线包覆盖）。
3. 复刻高德全套 UI（只做骑行需要的子集）。
4. 红绿灯读秒（实验项，见 §11）。

---

## 1. 总体架构

```
┌─────────────── OPPO Find X8s ───────────────┐        ┌──────── M60T ────────┐
│ NavService (前台服务, type=location)         │        │ HudActivity (单 Activity)│
│   AMapNavi ──► NavState ──┐                  │  BLE   │   LinkClient          │
│   MapCore  ──► Scene ─────┼──► Framing ──────┼───────►│   SceneStore          │
│   RouteCore ─► Polyline ──┘                  │ GATT   │   HudRenderer (Canvas)│
│   Theme      ─────────────┘                  │        │   ThemeRegistry       │
└──────────────────────────────────────────────┘        └──────────────────────┘
                     ▲                                          │
                     └──────────── ACK / 能力与降级请求 ─────────┘
```

职责边界：

| 关注点 | 手机 | 手表 |
| --- | --- | --- |
| 定位 | 主定位源（GPS + 网络） | 不使用 |
| 算路与路况 | 高德导航 SDK | 不参与 |
| 转向/车道/服务区/限速 | 高德回调 → NavState | 只显示 |
| 底图几何 | PMTiles + MVT 解码 + 裁剪简化 | 只做仿射变换与描边 |
| 风格 | 提供调色板 ID | 本地渲染 |
| 保活 | 前台服务 + 电池优化白名单 | 前台服务 + 常亮 |
| 降级决策 | 执行 | 发起（温度/电量/带宽） |

**为什么这样切**：手表上唯一贵的东西是持续点亮的屏幕，其余都便宜。把 GLES、4G、GPS 全部移走，手表只剩「收包 + 画线 + 画字」。

---

## 2. 模块（深模块，窄接口）

### 2.1 手机侧

| 模块 | 接口 | 隐藏的实现 |
| --- | --- | --- |
| `NavSource` | `Flow<NavState>` | AMapNavi 单例、隐私合规、回调线程、字段兼容 |
| `RouteSource` | `Flow<Route>` | GCJ-02→WGS84、抽稀、分段 |
| `MapSource` | `Flow<Scene>` | PMTiles range 读、MVT 解码、裁剪、量化 |
| `Link` | `send(Frame)`, `Flow<LinkEvent>` | 分帧、CRC、窗口、重传、MTU 协商 |
| `Session` | `start/stop`, `Flow<SessionState>` | 前台服务、通知、断连恢复、降级 |

### 2.2 手表侧

| 模块 | 接口 | 隐藏的实现 |
| --- | --- | --- |
| `LinkClient` | `Flow<Frame>` | GATT 扫描/连接/重连、粘包重组 |
| `StateStore` | `snapshot()` | 最新 NavState、Scene、字符串表、时间戳 |
| `Renderer` | `draw(Canvas, snapshot)` | 布局、旋转、裁剪、字体、图标 |
| `Theme` | `palette(id)` | 颜色/线宽/字号表，随亮度切换 |
| `Guard` | `Flow<Hint>` | 温度、电量、帧率自适应 |

---

## 3. 数据来源

### 3.1 高德导航 SDK → NavState

| 需求 | 回调 / 字段 |
| --- | --- |
| 转向类型 | `onNaviInfoUpdate(NaviInfo)` → `iconType` |
| 到动作距离 | `NaviInfo.curStepRetainDistance` |
| 当前路名 | `NaviInfo.currentRoadName` |
| 剩余里程/时间 | `NaviInfo.pathRetainDistance` / `pathRetainTime` |
| 车道 | `showLaneInfo(AMapLaneInfo[], ByteArray, ByteArray)` |
| 服务区 | `onServiceAreaUpdate(AMapServiceAreaInfo[])` |
| 限速/电子眼 | `updateCameraInfo(AMapNaviCameraInfo[])` |
| 交通设施 | `OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[])` |
| 路口放大图 | `showCross(AMapNaviCross)` |
| 播报文本 | `onGetNavigationText(int, String)` |
| 偏航/重算 | `onReCalculateRouteForYaw()` / `onNaviRouteNotify(...)` |

注意：字段随 SDK 版本有增减，先跑官方 NaviDemo 打印全量回调，再定结构体。`currentSpeed` 一类字段以 demo 实测为准，不预设。

### 3.2 OSM → Scene

```
china-latest.osm.pbf (1.62 GB, Geofabrik)
  → planetiler (z6–15, 只保留 highway/waterway/water/landuse/boundary)
  → china.pmtiles
  → 手机：按 (z, x, y) + HTTP range 取瓦片
  → MVT 解码 → 按 class 过滤（motorway/trunk/primary/secondary/tertiary/residential/service）
  → 视口裁剪（半径 800 m）+ Douglas-Peucker（1.5 m）+ int16 米量化
  → Scene
```

只保留 6 级道路 + 水面 + 水系，切片体积和解析成本都可控。建筑一律不要。

### 3.3 坐标

- 高德路线与控制点：GCJ-02。
- OSM：WGS84。
- 统一策略：进入 MapSource 前把高德几何转成 WGS84；Scene 与 Route 都在 WGS84 下计算；显示时不再转换。
- 反解用迭代法（3 次，误差 < 1 m），不要用单次近似。

### 3.4 二者关系

高德几何与 OSM 道路会有几十米差异（数据源不同）。**不要把路线吸附到 OSM 道路上**，路线独立绘制在最上层。

---

## 4. 传输契约

### 4.1 帧格式

```
+--------+-----+------+------+--------+---------+-------+
| MAGIC  | VER | TYPE | FLAG | SEQ    | LEN     | CRC16 |
| 2 B    | 1 B | 1 B  | 1 B  | 2 B    | 2 B     | 2 B   |
+--------+-----+------+------+--------+---------+-------+
| PAYLOAD (LEN bytes)                                   |
+-------------------------------------------------------+
```

- `MAGIC = 0x4D 0x48`（"MH"）
- `CRC16` 覆盖 TYPE..PAYLOAD，多项式 0x1021，初值 0xFFFF。
- 分片：单帧 payload ≤ MTU-3-9；超过则 `FLAG & 0x01` 置位，`LEN` 用 2 B 装分片序号+总数。

### 4.2 消息类型

| TYPE | 名称 | 方向 | 频率 | 说明 |
| --- | --- | --- | --- | --- |
| 0x01 | HELLO | 双向 | 连接时 | 版本、屏幕、dpi、能力位 |
| 0x02 | NAV | 手机→表 | 1 Hz | 定长状态 |
| 0x03 | STR | 手机→表 | 变化时 | 字符串表增量 |
| 0x04 | SCENE | 手机→表 | 30–60 s | 显示清单 |
| 0x05 | ACK | 双向 | 按需 | 序号确认 |
| 0x06 | HINT | 表→手机 | 按需 | 降级/升温/电量 |
| 0x07 | BYE | 双向 | 结束时 | 清理 |

### 4.3 NAV 载荷（定长 48 B）

| 偏移 | 类型 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | u8 | iconType | 高德转向类型原值 |
| 1 | u8 | flags | bit0 导航中, bit1 GPS 弱, bit2 重算中, bit3 已到达, bit4 服务区提示, bit5 车道有效 |
| 2 | u16 | stepDist | 到下一步距离, m, 上限 65535 |
| 4 | u16 | remainDist | 剩余距离, 10 m 单位 |
| 6 | u16 | remainTime | 剩余时间, min |
| 8 | u8 | speed | 当前速度 km/h |
| 9 | u8 | limit | 限速 km/h，0=未知 |
| 10 | u16 | svcDist | 下一服务区距离, 100 m 单位 |
| 12 | u16 | roadStr | 当前路名在字符串表的 ID |
| 14 | u16 | nextRoadStr | 下一路名 ID |
| 16 | u8 | laneUsable | 车道可用位图（低 8 位） |
| 17 | u8 | laneRec | 推荐车道位图 |
| 18 | u16 | cameraDist | 下一电子眼距离, m |
| 20 | u8 | cameraType | 电子眼类型 |
| 21 | u8 | crossFlag | 有路口放大图 |
| 22 | u16 | etaMin | 预计到达的当日分钟数 |
| 24 | u16 | seqScene | 对应场景版本 |
| 26 | u32 | tsMs | 手机单调时钟低 32 位 |
| 30 | u16 | crcExtra | 预留 |
| 32 | u8[16] | reserved | 预留，全 0 |

（表按 48 B 设计，实际写成 32 B 也可，按需裁剪。）

### 4.4 SCENE 载荷

```
u8   version          // 场景版本号，递增
u8   themeId          // 主题提示
i32  originLatE7      // WGS84 原点
i32  originLonE7
u16  radiusM          // 覆盖半径
u8   count            // 折线条数
repeat count:
  u8   cls            // 0 主路 1 次干 2 支路 3 水系 4 水面 5 路线
  u8   nPts           // 顶点数
  [i16 dx, i16 dy] * nPts   // 相对原点，米
```

预算：300 条 × 6 点 × 4 B ≈ 7.2 KB。BLE 在 10 KB/s 量级下约 1 s 传完，按 3 s 超时重传。

### 4.5 可靠性

- NAV 是状态型数据：丢包不重传，等下一帧。
- SCENE 是快照型：分片 + 序号 + 校验，失败整包重传，最多 3 次后降级到 L1。
- 所有帧带 SEQ；手表用 SEQ 判断丢帧，连续丢 5 帧触发 HINT 降级。
- 心跳：手表每 5 s 发 PING（复用 HINT），手机 15 s 无响应则重连。

---

## 5. 手表端渲染

### 5.1 版式（410×502 为例）

```
┌──────────────────────────────┐
│  ↑ 320 m    G60 沪昆高速      │  转向卡：图标 + 距离 + 路名
├──────────────────────────────┤
│                              │
│      底图 / 路线带            │  地图区：可整块关闭
│                              │
├──────────────────────────────┤
│ 120   服务区 2.3km   12:40    │  状态条
└──────────────────────────────┘
```

- 转向卡高度固定为屏宽的 28%，保证一眼可读。
- 状态条只放 3 个元素，避免拥挤：限速圆牌、服务区、剩余时间。
- 触摸：单击切主题，长按切 L0/L1/L2，双击结束导航。

### 5.2 绘制顺序

1. 背景填充（主题底色）
2. 底图折线（按 class 从低到高）
3. 路线（宽 6 px，圆角，外层描边 2 px）
4. 转向卡与状态条（不透明底，避免与底图混色）
5. 连接/温度角标

### 5.3 性能预算

| 动作 | 预算 |
| --- | --- |
| 单帧绘制 | ≤ 8 ms（1 Hz 时占用 < 1%） |
| 折线条数 | ≤ 400 |
| 顶点总数 | ≤ 4000 |
| 文字 | ≤ 6 段 |

### 5.4 主题

一张表，6–8 个颜色 + 3 个线宽：

| 键 | 日间 | 夜间 |
| --- | --- | --- |
| bg | #F7F7F2 | #101216 |
| water | #CFE3F5 | #16202B |
| roadMajor | #FFFFFF（描边 #B9BEC6） | #2A2F38 |
| roadMinor | #FFFFFF（描边 #D6DAE0） | #22262E |
| route | #E8452C | #FF7A45 |
| text | #111111 | #E8EAEE |
| accent（限速牌） | #FFFFFF 底 + #D0021B 环 | 同 |

换主题只改这张表。瓦片、场景包、协议都不动。

---

## 6. 手机端要点

- 前台服务：`foregroundServiceType="location"`，通知常驻，带结束按钮。
- 隐私合规：`updatePrivacyShow` / `updatePrivacyAgree` 必须在任何 SDK 调用前。
- 定位：`FusedLocationProvider` 或高德定位，1 Hz，精度 > 50 m 时标记 stale，不上屏。
- 瓦片缓存：LRU 128 MB，按走廊预取（当前位置往前 3 km）。
- 场景生成：位置每移动 200 m 或航向变化 > 45° 触发，最小间隔 20 s。
- 断连恢复：缓存最近 1 个 Scene + 最近 30 个 NAV，重连后先发 Scene 再发最新 NAV。

## 7. 状态机

手机：

```
IDLE ──start──► WAIT_GPS ──fix──► NAVI ──arrive/stop──► IDLE
                   │                 │
                   └──timeout───────┴──link lost──► HOLD ──reconnect──► NAVI
```

手表：

```
BOOT ──HELLO──► SYNC ──scene ok──► RUN ──frames missing──► STALE
                                     ▲                       │
                                     └──── reconnect ────────┘
```

- `STALE` 时保留最后一帧画面，角标变黄，30 s 后变红并只显示「已断连」。
- 重连后先校时（用手机 tsMs 差值），再补状态。

## 8. 热与功耗

| 项 | 策略 |
| --- | --- |
| 4G | 飞行模式 + 仅蓝牙 |
| GPS | 关闭 |
| 屏幕 | 常亮，亮度 30%，跟手调 |
| 刷新 | NAV 1 Hz；Scene 30–60 s；无变化不重绘 |
| 温度 | > 42 °C 降亮度 10%；> 45 °C 强制 L0；> 48 °C 熄屏 + 震动提醒 |
| 电量 | < 20% 强制 L0；< 10% 只显示转向 |

温度取自 `BatteryManager.EXTRA_TEMPERATURE`（电池温度）与 `PowerManager.currentThermalStatus`（Android 10+），两者取大。

## 9. 工程结构

```
moto-hud/
├── phone/
│   └── app/src/main/java/io/motohud/phone/
│       ├── nav/        AMapNaviAdapter.kt, NavState.kt
│       ├── map/        PmTilesSource.kt, MvtDecoder.kt, SceneBuilder.kt
│       ├── route/      RouteSource.kt, Gcj.kt
│       ├── link/       Framing.kt, GattServer.kt
│       └── session/    NavService.kt
├── watch/
│   └── app/src/main/java/io/motohud/watch/
│       ├── link/       GattClient.kt, FrameParser.kt
│       ├── hud/        HudActivity.kt, Renderer.kt, Theme.kt
│       └── guard/      ThermalGuard.kt
├── shared/
│   └── protocol/       motohud-frame-v1.md, fixtures/
└── tools/
    ├── tiles.sh        pbf → pmtiles → serve
    └── bench.sh        热预算与带宽实测
```

## 10. 里程碑与验收

| 周 | 交付 | 验收 |
| --- | --- | --- |
| W1 | 手机假数据 → 手表 12 字段 | 端到端延迟 < 300 ms |
| W2 | 真实高德回调接入 | 转向/距离/路名与高德 App 一致 |
| W3 | PMTiles → Scene → Canvas（已被 §12 取代：场景改在手表本地从 MBTiles 生成） | 场景包 < 12 KB，传输 < 3 s |
| W4 | 服务区/限速/车道/断连 | 断连 10 s 内恢复，状态正确 |
| W5 | 热预算实测 + 主题 + 打包 | 正午 2 h 表面 < 45 °C，无降频 |

这张表写于离线包方案之前。W3 与 §12 冲突时，以 §12 为准；「把包送进手表」不在本表里，见 §14.6 的 W3。

## 11. 风险与未决

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 高德 Key 与授权 | 起不了导航 | 先接 Web 服务跑通链路，再补 SDK Key |
| BLE 带宽不稳 | 场景包超时 | 分片 + 增量 + 降级 L1 |
| M60T 屏幕参数虚标 | 布局崩 | 运行时按 `wm size`/`wm density` 自适应 |
| OSM 数据缺口 | 底图空 | 高速与国省道优先，城市补 z15 |
| 高德与 OSM 几何不一致 | 视觉错位 | 路线独立绘制，不吸附 |
| 红绿灯读秒 | 暂无 | 作为实验项，用 MotoNav 的 provider 思路另评 |

未决：

1. 服务区数据在非高速路段的可用性（高德回调是否为空）。
2. 路口放大图是否值得做（带宽与收益）。

---

## 12. 离线地图：预装进手表

### 12.1 结论先给

可以，而且比在线方案更简单。

- 瓦片预装到手表本地，`SceneBuilder` 从手机搬到手表，直接读本地 MBTiles。
- 手机只发 NAV（48 B/s）与 ROUTE（一次几 KB）。BLE 带宽问题消失。
- 断网、隧道、山区都不影响底图。手机没电时，手表仍能显示最后一条路线。

### 12.2 实测锚点

用 planetiler + OpenMapTiles 全图层，z0–14，gzip，本机实测：

| 区域 | 面积 | 瓦片数 | 总大小 | z14 均瓦片 |
| --- | --- | --- | --- | --- |
| 摩纳哥（城区） | 2.08 km² | 246 | 415 KB | 1.7 KB |
| 马恩岛（乡村+城镇） | 572 km² | 8,078 | 6.0 MB | 633 B |

马恩岛的数字包含周边海面瓦片（水面多边形），陆地部分占比更小。

### 12.3 容量外推（估算）

中国陆地 960 万 km²，z14 瓦片约 4.9 km²/块（北纬 35°），约 200 万块。
按实测均值 0.6–1.7 KB/块：

| 范围 | 图层 | 估算大小 |
| --- | --- | --- |
| 全国 z6–14 | 全图层 | 1.5–4 GB |
| 全国 z6–14 | 六级道路 + 水面 | 0.6–1.5 GB |
| 单省 z6–14（约 10 万 km²） | 六级道路 + 水面 | 40–120 MB |
| 单城市域 z6–15（约 3000 km²） | 六级道路 + 水面 | 30–80 MB |
| 2000 km 走廊 ±20 km，z6–15 | 六级道路 + 水面 | 100–300 MB |

裁剪到六级道路 + 水面，去掉 POI、建筑、土地利用、注记，体积约剩 40–60%。
以上是外推值，落包前用 `motohud-pack size` 实测校准。上表按全层级区间估算，实际出包默认 z12–14，见 §12.8。

### 12.4 格式：MBTiles，不是 PMTiles

| 维度 | MBTiles | PMTiles |
| --- | --- | --- |
| 手表读取 | SQLite 直读，Android 原生 | 需自写 range 与目录解析 |
| 局部更新 | 一行 `INSERT OR REPLACE` | 要重写目录，工具链更重 |
| 增量包 | 可直接做 patch 库 | 只能整文件替换 |
| HTTP 直服 | 需服务端 | 原生 range |

手表端和分发都用 MBTiles。PMTiles 只作为「手机当瓦片服务器」的可选变体，不进主链路。

### 12.5 包结构

```
pack/
├── manifest.json      # 区域、bbox、zoom、版本、sha256、瓦片数
├── region.mbtiles     # gzip MVT，默认 z12–14
└── NOTICE.txt         # 数据日期、© OpenStreetMap contributors
```

```json
{"id":"zj","name":"浙江","bbox":[118.0,27.0,123.0,31.5],
 "zoom":[12,14],"version":"2026.10.28","tiles":184320,
 "bytes":92431123,"sha256":"…","style":"motohud-6class-v1"}
```

示例里的瓦片数与字节数只说明格式，不是实测值。

### 12.6 下发与更新

| 路径 | 实测速度量级 | 用途 |
| --- | --- | --- |
| 手机热点 + 本地 HTTP 上传 | 2–5 MB/s | 日常更新，1 GB 约 4–8 分钟 |
| adb push（USB / 无线调试） | 5–20 MB/s | 首次灌包、开发 |
| 手表 4G 直接下载 | 1–5 MB/s | 身边没有电脑时 |
| BLE | 5–10 KB/s | 只传 manifest 与控制 |

增量流程：

```
motohud-pack diff old.mbtiles new.mbtiles -o patch.mbtiles
# 输出变更瓦片 + delta manifest，通常只有全量的 2–10%
```

手表端应用 patch：`ATTACH patch` → `INSERT OR REPLACE` → 校验瓦片数 → 切换版本指针。
失败保留旧文件，回滚只需还原指针。

### 12.7 存储预算

M60T 常见 4+32 GB。系统与预装占 10–14 GB，可用约 16–20 GB。
建议离线包上限 8 GB，留 25% 余量给 SQLite 与 eMMC 寿命。
按上面的表，全国精简包 1.5 GB，加两条长走廊也进得去。

### 12.8 层级约定

2026-10-29 定，用来收掉文档里 z6–14 与 z12–15 两种写法：

| 角色 | 层级 | 说明 |
| --- | --- | --- |
| 手表读瓦片 | 固定 z14 | `TileSceneBuilder` 的常量，不随包变 |
| 默认包 | z12–14 | `tools/build-pack.sh` 不带参数时的范围 |
| 按需 z15 | 仅城市密集区 | 只在需要时加，manifest 的 `zoom` 写实际范围 |

manifest 的 `zoom` 是契约的一部分：手表只读取落在自己范围内的瓦片，缺层就画得粗一点，不报错。

§12.3 的表是按全区间估算的参考量；实际包按本节的约定出，体积差异按比例折算。

---

## 13. 最终产物

| # | 产物 | 形态 | 内容 | 验收 |
| --- | --- | --- | --- | --- |
| 1 | 手机端 | `motohud-phone.apk` | 高德导航服务、路线下发、离线包管理、设置 | 真机连续导航 2 h |
| 2 | 手表端 | `motohud-watch.apk` | HUD、MBTiles 读瓦片、MVT 解码、Canvas 渲染 | 冷启动 < 2 s，单帧 < 8 ms |
| 3 | 打包工具 | `motohud-pack`（CLI） | pbf→mbtiles、裁剪、diff、manifest、校验 | 一条命令出包 |
| 4 | 离线数据包 | `region.mbtiles` + `manifest.json` | 按省与走廊发布 | 大小与实测偏差 < 15% |
| 5 | 协议规范 | `motohud-frame-v1.md` + golden fixtures | 帧、NAV、SCENE、ACK、HINT | 双端单测同源通过 |
| 6 | 主题包 | `theme-day.json` / `theme-night.json` | 手表调色板 + 手机预览 style | 换主题不改代码 |
| 7 | 安装与使用手册 | Markdown | Key 申请、侧载、配对、导包、故障排查 | 新人 30 分钟装好 |
| 8 | 测试资产 | 脚本 + 录制数据 | 导航会话、基准脚本、存储校验 | 可回归 |
| 9 | 发布包 | GitHub Release | APK + 数据包 + SHA256 + 兼容矩阵 | 可复现构建 |

配套的三个版本号必须同时记录：`appVersion`、`protocolVersion`、`dataSchemaVersion`。
升级任一项，兼容矩阵里标出可混用的组合。

不交付：高德 Key、个人签名证书、第三方地图数据的再分发授权。

---

## 14. 手机端 APK 怎么做

### 14.1 定位

一个 APK，没有后端，没有账号，不依赖 GMS。

它做四件事：

1. 用高德导航 SDK 算路并导航，输出 `NavState`。
2. 把状态与路线通过 BLE 发给手表。
3. 管理离线包，并用手表能连的方式把包送过去。
4. 保活，并把转向信息镜像到锁屏通知。

目的地从哪来：**高德 App 分享链接**。手机端不写搜索 UI。
用户在高德里搜好地点，分享给本 App，解析出经纬度与名称，再算路。
这样 POI 与地址库直接复用高德 App，准确率最高。

### 14.2 技术选型

| 项 | 选择 | 理由 |
| --- | --- | --- |
| 语言 | Kotlin | 与手表端同构，协议代码可共享 |
| UI | Jetpack Compose | 只有一个设置页与一个状态页 |
| 状态 | Coroutines + StateFlow | 回调线程与 UI 线程解耦 |
| 导航 | 高德 Android 导航 SDK（headless） | 不要它的 UI，只要回调 |
| 本地存储 | DataStore + SQLite（包索引） | 够用，不引 Room |
| 传输 | BLE GATT Server + 分片队列 | 手表做 client，省手表功耗 |
| 大文件 | 本机 HTTP（Ktor server）+ 手机热点 | 1 GB 级包唯一现实路径 |
| 构建 | Gradle 8.x / AGP 8.5 / JDK 17 | AGP 8.5 不接受 JDK 21+ |
| 最低版本 | minSdk 26，targetSdk 35 | 覆盖 Find X8s（Android 15） |

### 14.3 模块与数据流

```
:app            Compose：状态页、包管理页、设置、日志
:core-nav       AMapNavi 适配 → NavState（纯数据，无 Android 依赖）
:core-route     GCJ-02 ↔ WGS84、抽稀、路线落库
:core-link      帧编解码、GATT Server、分片、重传、ACK
:core-pack      manifest、导入导出、diff 应用、热点 HTTP
:core-proto     帧常量与 golden fixtures（JVM 单测）
```

数据流单向：`AMapNavi → NavState → Link → 手表`。
反方向只有 `ACK / HINT / manifest 请求`。

### 14.4 关键实现

**高德接入顺序不能错**：

```kotlin
// Application.onCreate
MapsInitializer.updatePrivacyShow(this, true, true)
MapsInitializer.updatePrivacyAgree(this, true)

// 必须在 getInstance 之前
AMapNavi.setApiKey(this, BuildConfig.AMAP_KEY)
val navi = AMapNavi.getInstance(this)
navi.addAMapNaviListener(listener)
navi.setUseInnerVoice(true)   // 语音交给高德，别自己做
navi.startGPS()
navi.calculateRideRoute(NaviLatLng(dest.lat, dest.lon))
// onCalculateRouteSuccess → navi.startNavi(NaviType.GPS)
```

- 监听器只做一件事：把回调写进 `MutableStateFlow<NavState>`，用 `conflate()` 压到 1 Hz。
- 路名等字符串放进字符串表，按 ID 增量下发。
- 3 秒没有 `onNaviInfoUpdate` 就把 `flags` 的 GPS 弱位置 1。

**算路策略（摩托车）**：SDK 是否暴露摩托车参数要实测。
先做两套：`calculateRideRoute`（骑行，避开高速）与 `calculateDriveRoute`（驾车）。
需要还原高德 App 的摩托车路线时，用分享链接里的终点加手动途经点逼近。
算不出来的路段，提供「跟随模式」：只显示高德 App 的通知文本，不自己导航。

**BLE GATT Server**：

| 特征 | 属性 | 用途 |
| --- | --- | --- |
| TX | Notify | HELLO / NAV / SCENE / STR |
| RX | Write | ACK / HINT / PING |
| CTRL | Write + Read | 版本、MTU、包请求 |

- 广播带 service UUID 与短名，手表按 UUID 过滤。
- 手表请求 MTU 517，手机按 `onMtuChanged` 分片。
- 发送队列单线程串行，整包不交织；每帧带 seq，SCENE 需要 ACK。
- 断连只清发送队列，不清状态；重连先发 HELLO_ACK，再补最新 NAV。

**离线包与热点 HTTP**：

1. PC 出包，手机通过系统文件选择器导入，落到 `files/packs/`。
2. 手机起 Ktor，端点 `/manifest`、`/pack/{id}`，支持 Range 与 sha256。
3. 手表连手机热点，拉 manifest 比对版本，只拉缺失分片或增量包。
4. 导入后校验：瓦片数、总字节、sha256 三项一致才算成功。

**保活**：前台服务 `foregroundServiceType="location"`，通知里显示当前转向。
启动时引导用户关闭电池优化、允许自启动（ColorOS 必需）。
服务被系统回收后，`START_STICKY` 重启并从最近一次导航状态恢复。

**调试**：

- 模拟导航：`startNavi(NaviType.EMULATOR)`，不出门也能跑全链路。
- 录制会话：把 NAV 帧写文件，可在桌面回放，用于手表端回归。
- 自检页：Key 是否匹配、BLE 是否在广播、包版本、最近 50 条日志。

### 14.5 权限

| 权限 | 时机 |
| --- | --- |
| ACCESS_FINE_LOCATION / COARSE | 开始导航 |
| POST_NOTIFICATIONS | 首次启动 |
| FOREGROUND_SERVICE_LOCATION | 前台服务 |
| BLUETOOTH_CONNECT / ADVERTISE | 连接手表 |
| BLUETOOTH_SCAN（neverForLocation） | 仅调试扫描 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 引导页 |

### 14.6 里程碑

| 周 | 交付 |
| --- | --- |
| W1 | 分享链接解析、headless 导航、模拟导航跑通 |
| W2 | 帧编解码 + GATT Server，手表回显 12 字段 |
| W3 | 离线包导入、热点 HTTP、增量拉取 |
| W4 | 录制回放、自检页、签名与打包 |

工作量约 15–20 人日，不含手表端。

### 14.7 风险

| 风险 | 缓解 |
| --- | --- |
| Key 与包名/SHA1 不匹配（INVALID_USER_SCODE） | 自检页直接显示三项指纹 |
| 导航 SDK 与地图 SDK 版本冲突 | 锁版本，不混用不同版本 .so |
| 摩托车专属算路参数不可用 | 骑行/驾车 + 途经点，或跟随模式 |
| ColorOS 杀后台 | 引导页 + 前台服务 + 电池白名单 |
| BLE 广播被限流 | 只在服务启动时广播 30 s，之后靠重连 |
| 高德分享链接格式变化 | 解析器多格式匹配 + 失败时手动输入坐标 |

### 14.8 确认页：算完先看，再出发

分享链接只给终点，不能证明路线正确。开导航前必须有一屏给用户确认。

**用什么渲染**：高德地图 SDK 的 `MapView`，不用 OSM。
理由只有一条：路线是 GCJ-02，高德底图也是 GCJ-02，天然对齐，零转换。
OSM 是手表端的离线底图，和确认页互不影响。两个坐标系各自闭环，谁也不用迁就谁。

**流程**：

```
分享链接 / 剪贴板
  → 解析 dlat/dlon/dname（短链先 follow redirect；有 poiid 就用搜索 SDK 反查）
  → 起点 = 当前定位（可改）
  → 算路 ×3 策略（推荐 / 不走高速 / 少收费）
  → 地图预览 + 路线卡片
  → 用户换路线、加途经点、拖动终点
  → 确认 → startNavi(GPS) → NAV 流开始
```

**页面骨架**：

```
┌────────────────────────────────┐
│ 目的地  西湖景区南门        ✎  │
├────────────────────────────────┤
│                                │
│          MapView               │  选中路线加粗，其余细灰
│      起终点与途经点 marker      │
│                                │
├────────────────────────────────┤
│ ① 42.6 km · 58 min · 高速优先  │  横滑卡片，最多 3 条
│ ② 38.1 km · 64 min · 不走高速  │
├────────────────────────────────┤
│ 途经点 2 · 添加        [ 开始 ] │
└────────────────────────────────┘
```

**关键 API**：

| 能力 | 接口 |
| --- | --- |
| 多路线结果 | `AMapNavi.getNaviPaths()` → `Map<Int, AMapNaviPath>` |
| 切换主路线 | `selectMainPathID(id)`（部分版本为 `selectRouteId`，反射兜底） |
| 路线几何 | `AMapNaviPath.getStepList()` → `AMapNaviStep.getCoordList()` |
| 视野适配 | `CameraUpdateFactory.newLatLngBoundsRect(path.getBoundsForPath(), l, t, r, b)` |
| 画线 | `AMap.addPolyline(PolylineOptions().addAll(coords).width(px).color(argb))` |
| 途经点 | `calculateDriveRoute(from, to, waypoints, strategy)` |
| 策略 | `AMapNavi.strategyConvert(躲避拥堵, 避免收费, 不走高速, 高速优先, 多路径)` |
| 路线标签 | `AMapNaviPath.getLabels()`、`getAllLength()`、`getAllTime()` |

**交互规则**：

- 点卡片切换路线，地图高亮跟随，同时 `selectMainPathID` 对齐导航引擎。
- 长按地图添加途经点，保持当前策略重算；最多 3 个。
- 拖动终点 marker 微调，解决坐标落在建筑内或路口另一侧。
- 顶部可改起点（地图选点），解决地库、定位漂移。
- 确认前手表显示「等待路线」，确认后才开始发 NAV。

**为什么值得做**：摩托车走错一次就是几十公里。确认页是唯一低成本纠错点。

**成本**：3–4 人日。风险两条：分享链接不含途经点（只能手加），多路径 API 版本差异（反射兜底）。
