package io.mionav.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.mionav.geo.ShareLinkParser
import io.mionav.hud.HudText
import io.mionav.link.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val pickPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val picked = uri ?: return@registerForActivityResult
        val container = (application as MioNavApp).container
        lifecycleScope.launch(Dispatchers.IO) {
            val name = contentResolver
                .query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            container.packs.import(picked, name).onSuccess { container.packHost.refresh() }
        }
    }

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MioNavApp).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by container.nav.state.collectAsState()
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("MioNav", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (state.active) {
                                "${state.currentRoad} · ${HudText.distance(state.stepDistanceM)} · " +
                                    HudText.remaining(state.remainDistanceM, state.remainMinutes)
                            } else {
                                "未导航。在高德地图里分享地点给 MioNav。"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (BuildConfig.AMAP_KEY.isBlank()) "未配置高德 Key，使用模拟导航" else "高德 Key 已配置",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { requestPermissions() },
                        ) { Text("授权") }
                        var voiceOn by remember { mutableStateOf(container.voice.enabled) }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                container.voice.enabled = !container.voice.enabled
                                voiceOn = container.voice.enabled
                            },
                        ) { Text(if (voiceOn) "语音播报：开" else "语音播报：关") }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { container.voice.speak("前方 500 米右转，进入科技路") },
                        ) { Text("试听语音") }

                        var shareNote by remember { mutableStateOf("") }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                                val text = clip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(this@MainActivity)
                                    ?.toString()
                                val target = text?.let { ShareLinkParser.parse(it) }
                                if (target == null) {
                                    shareNote = "剪贴板里没有高德链接。在高德里选「复制链接」再回来点这里。"
                                } else {
                                    container.pendingTarget = target
                                    shareNote = ""
                                    startActivity(Intent(this@MainActivity, ConfirmActivity::class.java))
                                }
                            },
                        ) { Text("粘贴高德链接") }
                        if (shareNote.isNotEmpty()) {
                            Text(shareNote, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                startForegroundService(Intent(this@MainActivity, NavService::class.java))
                            },
                        ) { Text("启动导航服务") }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { stopService(Intent(this@MainActivity, NavService::class.java)) },
                        ) { Text("停止") }

                        val pack by container.packHost.state.collectAsState()
                        Text("底图", style = MaterialTheme.typography.titleMedium)
                        Text(
                            pack.identity?.let {
                                "${pack.name} · ${it.version} · ${it.tiles} 瓦片 · ${it.bytes / (1024 * 1024)} MB"
                            } ?: "还没有底图包",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (pack.note.isNotEmpty()) {
                            Text(pack.note, style = MaterialTheme.typography.bodySmall)
                        }
                        pack.url?.let { url -> Text(url, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { pickPack.launch(arrayOf("*/*")) },
                        ) { Text("导入底图包") }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { container.packHost.shareNow() },
                        ) { Text("共享底图") }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { container.packHost.stop() },
                        ) { Text("停止共享") }

                        // Everything the agent needs to see is also in `adb logcat -s MioNav`.
                        var events by remember { mutableStateOf(Diag.snapshot()) }
                        Text("调试", style = MaterialTheme.typography.titleMedium)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { events = Diag.snapshot() },
                        ) { Text("刷新日志") }
                        events.takeLast(10).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (requiredPermissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions()
        }
        lifecycleScope.launch { container.link.start() }
    }

    private fun requestPermissions() = permissions.launch(requiredPermissions.toTypedArray())
}
