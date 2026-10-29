# 05: build-pack.sh 出包与自检

**What to build:** 一条命令从小块 OSM 数据出一个可用的包：默认 z12–14、自动生成 manifest、`--verify` 能抓出坏包。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `--pbf --bbox --out` 一条命令产出 `region.mbtiles` 与 `manifest.json`
- [ ] manifest 的 bbox、zoom、瓦片数、字节数、sha256 与文件实际一致
- [ ] `--verify` 对被改过的文件返回非零，并指出是哪一项不符
- [ ] 默认层级 z12–14，`--zoom` 可以覆盖
- [ ] 用马恩岛级别的小区域跑通，不改动机器上的全局配置
- [ ] 中途失败时保留上一次成功的产物
