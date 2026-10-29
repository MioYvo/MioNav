#!/usr/bin/env bash
# Fetches the Amap Android SDKs and lays them out for the build.
# Binaries are never committed (.gitignore); re-run this on a fresh clone.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/amapsdk"
mkdir -p "$WORK"

MAP_URL="https://a.amap.com/lbs/static/zip/AMap_Android_SDK_All.zip"
NAVI_URL="https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/zip/AMap_Android_Navi_SDK_All.zip"

echo "==> downloading"
[ -f "$WORK/all.zip" ] || curl -fsSL -o "$WORK/all.zip" "$MAP_URL"
[ -f "$WORK/navi.zip" ] || curl -fsSL -o "$WORK/navi.zip" "$NAVI_URL"

echo "==> unpacking"
rm -rf "$WORK/map" "$WORK/navi"
mkdir -p "$WORK/map" "$WORK/navi"
unzip -oq "$WORK/all.zip" -d "$WORK/map"
unzip -oq "$WORK/navi.zip" -d "$WORK/navi"
unzip -oq "$WORK/map/AMap3DMap_AMapSearch_AMapLocation.zip" -d "$WORK/map/sdk"
unzip -oq "$WORK/navi/AMap3DMap_AMapNavi_AMapSearch_AMapLocation.zip" -d "$WORK/navi/sdk"

echo "==> installing jars"
mkdir -p "$ROOT/android/nav/libs"
cp "$WORK/navi/sdk/"*.jar "$ROOT/android/nav/libs/"
# The all-in-one navi jar already contains map, search and location.

echo "==> installing native libs (arm64-v8a, armeabi-v7a)"
for abi in arm64-v8a armeabi-v7a; do
  dest="$ROOT/android/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  cp "$WORK/navi/sdk/$abi/"*.so "$dest/"
done

echo "==> done"
ls -1 "$ROOT/android/nav/libs" | sed 's/^/  libs: /'
du -sh "$ROOT/android/app/src/main/jniLibs" | sed 's/^/  jniLibs: /'
echo
echo "Key registration: package io.mionav.app, SHA1 from ./gradlew :android:app:signingReport"
echo "Motorcycle routing (CarType 11) is a paid Amap interface: apply through the ticket system."
