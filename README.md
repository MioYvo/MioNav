# MioNav

Motorcycle navigation HUD. The phone computes and navigates with Amap; the watch (M60T) only displays.

```
Phone (OPPO Find X8s)                        Watch (M60T)
Amap Navi SDK ──► NavState ──► BLE GATT ──►  Canvas HUD
MBTiles (offline) ──────────► (later)        local tiles + palette
```

## Status

| Module | What it is | State |
| --- | --- | --- |
| `core/proto` | wire format, NAV/SCENE/STR, chunking | done, 16 tests |
| `core/geo` | GCJ-02 ↔ WGS84, simplify, quantise, share-link parser | done, 13 tests |
| `core/hud` | maneuver mapping, display strings | done, 7 tests |
| `android/nav` | Amap adapter, share-link resolver (POI id + redirect), fake source | compiles |
| `android/link` | GATT server, chunk queue, ACK | compiles |
| `android/pack` | MBTiles reader, manifest | compiles |
| `core/geo` (MVT) | vector-tile decoder for the scene builder | 3 tests |
| `android/watch` | Canvas HUD, GATT client, compass, MBTiles→SCENE builder | APK 1.4 MB |
| `android/app` | share receiver, confirm screen, foreground service | APK 131 MB (arm64, Amap .so) |

39 JVM tests pass. Android modules compile only with SDK 35 and the Amap SDK in `android/nav/libs`.

## Build

```bash
# JVM core, no Android SDK needed
./gradlew test

# one-time: vendor SDKs (jars + arm64 .so, both gitignored)
tools/fetch-amap-sdk.sh

# phones/watch
./gradlew :android:app:assembleDebug :android:watch:assembleDebug

# Android (needs SDK 35 + Amap SDK, see android/nav/libs/README.md)
gradle :android:app:assembleDebug :android:watch:assembleDebug
```

Android modules are included automatically when `ANDROID_HOME`/`ANDROID_SDK_ROOT` is set or
`local.properties` exists. Without them the build stays green for the JVM core.

Amap SDK binaries come from `tools/fetch-amap-sdk.sh` (jars 38 MB, `jniLibs` 151 MB for arm64-v8a +
armeabi-v7a; both are gitignored). Installed set: AMap3DMap 11.3.100, AMapNavi 11.3.100,
AMapSearch 9.8.1, AMapLocation 11.3.101.

Amap key: `gradle :android:app:assembleDebug -Pmionav.amapKey=<32 hex>`. Without a key the app
falls back to `FakeNavSource` so the watch and the link can be developed end to end.

## Layout

```
core/proto   Frame, NavFrame, Scene, StringTable, Chunker
core/geo     Gcj, LocalProjection, Simplify, ShareLinkParser, Mvt
core/hud     ManeuverKind, IconMapper, HudText
android/nav  NavState, NavSource, AmapNavSource, ShareResolver, FakeNavSource
android/link LinkServer (GATT)
android/pack PackManifest, TileStore
android/app  MioNavApp, ShareReceiver, MainActivity, ConfirmActivity, NavService
android/watch HudActivity, HudRenderer, WatchLink, Palette, HeadingProvider, TileSceneBuilder
docs/        design.md, protocol.md
```

## Open items

- `MIO-2` Expose route strategy flags (toll/highway/congestion) in settings.
- `MIO-9` Heading needs a real ride: check compass drift next to the engine and retune
  `MOVING_KMH`/`MOUNT_OFFSET_DEG` in `HeadingProvider`.
- `MIO-10` `TileSceneBuilder` has no on-device test yet; only the MVT decoder is unit-tested.
- `MIO-11` Video/photo capture inside `AmapNaviView` is untested on the Find X8s.

Decided (no longer open):

- Motorcycle routing (`AMapCarInfo` + `carType=11`) is a **paid** Amap interface. We do not apply;
  routing stays on the free driving/riding strategies and `VehicleProfile` stays opt-in/unused.
- Background map: OSM MBTiles on the watch, decoded by `core/geo/Mvt` and built into a SCENE by
  `TileSceneBuilder`. No phone involvement, works offline.
- Heading: phone sends course-over-ground in NAV (offset 38); the watch overrides it with its own
  rotation-vector compass while moving. Stationary, the course is held.
- Share links: coordinates are GCJ-02 and converted at the boundary; `poiid` goes through
  `PoiSearch.searchPOIIdAsyn`; `surl.amap.com` short links follow one redirect.

Closed: `MIO-1`, `MIO-3` (icon table and signatures read from the jar with `javap`),
`MIO-4`, `MIO-5`, `MIO-6`, `MIO-7`.

## Licences

- Code, protocol spec and docs: Apache-2.0. See `LICENSE` and `NOTICE`.
- Map data: © OpenStreetMap contributors, ODbL 1.0. Tiles built with planetiler and
  OpenMapTiles.
- Amap SDK, its key and the `.so` files: proprietary, not redistributed.
  `tools/fetch-amap-sdk.sh` downloads them under Amap's own terms. See `docs/design.md` §12–14.
