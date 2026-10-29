package io.mionav.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * Owns the phone's HTTP server. The server exists only while it has something to hand out:
 * a watch that reported a different pack, or a user who asked to share manually.
 */
class PackHost(private val store: PackStore) {

    companion object {
        const val IDLE_MS = 10 * 60 * 1000L
    }

    data class State(
        val identity: io.mionav.tilepack.PackIdentity? = null,
        val name: String = "",
        val url: String? = null,
        val serving: Boolean = false,
        val note: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var server: io.mionav.tilepack.PackServer? = null

    fun refresh() {
        val entry = store.current()
        _state.value = _state.value.copy(
            identity = entry?.identity,
            name = entry?.manifest?.name.orEmpty(),
        )
    }

    /** Returns the URL to send back, or null when the watch should keep the port closed. */
    fun onWatchHello(remote: io.mionav.tilepack.PackIdentity?): String? {
        val entry = store.current()
        if (entry == null) {
            stop()
            _state.value = _state.value.copy(note = "手机上还没有底图包")
            return null
        }
        return when (io.mionav.tilepack.PackNegotiation.decide(entry.identity, remote)) {
            io.mionav.tilepack.PackDecision.UpToDate -> {
                stop()
                _state.value = _state.value.copy(note = "手表已是最新，服务未开启")
                null
            }
            io.mionav.tilepack.PackDecision.NoLocalPack -> null
            io.mionav.tilepack.PackDecision.Serve -> startServing(entry)
        }
    }

    /** The manual switch: share even when no watch asked. */
    fun shareNow(): String? {
        val entry = store.current() ?: run {
            _state.value = _state.value.copy(note = "先导入一个底图包")
            return null
        }
        return startServing(entry)
    }

    private fun startServing(entry: PackEntry): String? {
        val host = LanAddress.current() ?: run {
            _state.value = _state.value.copy(note = "没有局域网地址：先开热点或连 Wi-Fi")
            return null
        }
        server?.takeIf { it.port > 0 }?.let { running ->
            return running.url(host)
        }
        val started = io.mionav.tilepack.PackServer(
            pack = entry.tiles,
            identity = entry.identity,
            manifest = entry.manifestFile,
            preferredPort = io.mionav.tilepack.PackServer.DEFAULT_PORT,
            idleTimeoutMs = IDLE_MS,
        )
        return try {
            started.start()
            server = started
            val url = started.url(host)
            _state.value = _state.value.copy(
                url = url,
                serving = true,
                note = "正在共享 ${entry.manifest.name}",
            )
            url
        } catch (e: IOException) {
            started.stop()
            _state.value = _state.value.copy(note = "无法启动服务：${e.message}")
            null
        }
    }

    fun stop() {
        server?.stop()
        server = null
        _state.value = _state.value.copy(url = null, serving = false)
    }
}
