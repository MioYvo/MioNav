package io.mionav.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.mionav.hud.HudText
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

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
