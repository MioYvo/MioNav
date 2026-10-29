# 01: core/pack 与三条路由

**What to build:** 一个纯 JVM 的新模块，能在临时目录上起一个只读 HTTP 服务。用 `curl` 就能拉到一个假包并读到 identity 头。握手判定函数与它同住，因为两者共用同一组测试夹具。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `HEAD /pack.mbtiles` 返回长度与 `X-Mionav-{Pack,Version,Sha256,Tiles}` 四个头，并声明 `Accept-Ranges: bytes`
- [ ] `GET /pack.mbtiles` 的字节与源文件逐字节一致
- [ ] `Range: bytes=N-` 返回 206，内容是文件后缀，`Content-Range` 与总长对得上
- [ ] 未知路径与目录穿越一律 404，任何情况下不做目录列举
- [ ] 首选端口被占用时改绑系统临时端口，暴露的 URL 里是实际端口
- [ ] `stop()` 之后端口立刻能被重新绑定，`start()` 可以反复调用
- [ ] 握手判定：两端 identity 一致返回 UpToDate，不一致返回 Serve
- [ ] 没有 Android SDK 的机器上 `./gradlew test` 仍然全绿
