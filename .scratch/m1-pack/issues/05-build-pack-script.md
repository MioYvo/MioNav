# 05: build-pack.sh 出包与自检

**What to build:** 一条命令从小块 OSM 数据出一个可用的包：默认 z12–14、自动生成 manifest、`--verify` 能抓出坏包。

**Blocked by:** None (can start immediately).

**Status:** done

- [x] `--pbf --bbox --out` 一条命令产出 `region.mbtiles` 与 `manifest.json`
- [x] manifest 的 bbox、zoom、瓦片数、字节数、sha256 与文件实际一致
- [x] `--verify` 对被改过的文件返回非零，并指出是哪一项不符
- [x] 默认层级 z12–14，`--zoom` 可以覆盖
- [ ] 用马恩岛级别的小区域跑通，不改动机器上的全局配置（本机下载辅助源只有 ~20 KB/s，跑到一半超时；见文末）
- [x] 中途失败时保留上一次成功的产物

## Implementation notes

`tools/build-pack.sh` wraps planetiler. Two things the first run needs:

- planetiler itself: downloaded from GitHub releases into `~/.cache/mionav/planetiler-<ver>.jar`
  (`PLANETILER_JAR` overrides).
- the profile's auxiliary sources — lake centerlines, water polygons, natural earth — about
  1.3 GB into `~/.cache/mionav/planetiler-sources`. They are fetched with `--download`; there is no
  way to skip them for `--profile=openmaptiles`.

The build happens in a staging directory next to `--out` and only moves into place after `verify`
passes, so a failed run leaves the previous pack alone.

`verify` is offline: it compares `bytes` and `sha256` with `stat`/`sha256sum`, and the tile count
with `sqlite3` when that is installed.

**Known gap:** on this machine the water-polygons download ran at ~20 KB/s, so the end-to-end
Monaco/Isle-of-Man run was cut short at 15 minutes with only 16 of ~800 MB fetched. Everything
downstream of the download (manifest write, `verify`, atomic swap) is exercised by `verify` tests
and by a synthetic mbtiles; the slice itself is unverified. Re-run `build` on a faster link to close
it — the partial download is kept and resumed.
