# Amap SDK

Not committed. Download **Android 导航 SDK** from <https://lbs.amap.com/api/android-navi-sdk/download>
and drop the files here:

```
libs/
├── AMap3DMap_*_AMapNavi_*_AMapSearch_*_AMapLocation_*.jar
├── libAMapSDK_NAVI_*.so            -> src/main/jniLibs/{arm64-v8a,armeabi-v7a}/
├── libneonui_shared.so             -> src/main/jniLibs/{arm64-v8a,armeabi-v7a}/
└── libneonuijni_public.so          -> src/main/jniLibs/{arm64-v8a,armeabi-v7a}/
```

The `.so` files must sit under `src/main/jniLibs/<abi>/`, not next to the jar.

Key registration needs the final `applicationId` and the signing SHA1:

| field | value |
| --- | --- |
| Package | `io.mionav.app` |
| SHA1 | `./gradlew :android:app:signingReport` |

MIO-1: the icon table in `core/hud/Maneuver.kt` is provisional; dump `NaviInfo.iconType`
from a real session and correct it there.
