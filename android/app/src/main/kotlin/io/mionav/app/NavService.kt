package io.mionav.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.mionav.hud.HudText
import io.mionav.nav.NavState
import io.mionav.proto.Flags
import io.mionav.proto.Frame
import io.mionav.proto.MsgType
import io.mionav.proto.NavFlags
import io.mionav.proto.NavFrame
import io.mionav.proto.StringTable
import io.mionav.proto.StringEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps navigation alive off-screen and mirrors the turn to the lock screen.
 * One NAV frame per second; strings only when a road name changes.
 */
class NavService : Service() {

    companion object {
        private const val CHANNEL = "mionav.nav"
        private const val ID = 10_001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: AppContainer
    private var lastRoadId = 0
    private val strings = StringTable()
    private var seq = 0

    override fun onCreate() {
        super.onCreate()
        container = (application as MioNavApp).container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification("准备导航", "")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(ID, notification)
        }
        container.link.start()

        scope.launch {
            container.nav.state.collect { state ->
                announce(state)
                send(state)
                if (state.active) updateNotification(state)
            }
        }
        return START_STICKY
    }

    private fun send(state: NavState) {
        val roadId = strings.intern(state.currentRoad)
        val nextId = strings.intern(state.nextRoad)
        if (strings.size > lastRoadId) {
            val delta = strings.entriesAfter(lastRoadId)
            lastRoadId = strings.size
            container.link.send(
                Frame(MsgType.STR, seq = nextSeq(), payload = StringTable.encode(delta)),
            )
        }

        var flags = 0
        if (state.active) flags = flags or NavFlags.NAVIGATING
        if (state.gpsWeak) flags = flags or NavFlags.GPS_WEAK
        if (state.recalculating) flags = flags or NavFlags.RECALCULATING
        if (state.arrived) flags = flags or NavFlags.ARRIVED
        if (state.serviceAreaDistanceM != null) flags = flags or NavFlags.HAS_SERVICE_AREA
        if (state.lanes != null) flags = flags or NavFlags.HAS_LANES

        val nav = NavFrame(
            iconType = state.iconType,
            flags = flags,
            stepDistM = state.stepDistanceM.coerceIn(0, 0xFFFF),
            remainDist10m = (state.remainDistanceM / 10).coerceIn(0, 0xFFFF),
            remainTimeMin = state.remainMinutes.coerceIn(0, 0xFFFF),
            speedKmh = state.speedKmh.coerceIn(0, 255),
            limitKmh = state.limitKmh.coerceIn(0, 255),
            svcDist100m = ((state.serviceAreaDistanceM ?: 0) / 100).coerceIn(0, 0xFFFF),
            roadId = roadId,
            nextRoadId = nextId,
            laneUsable = state.lanes?.usableMask ?: 0,
            laneRec = state.lanes?.recommendedMask ?: 0,
            cameraDistM = state.cameraDistanceM?.coerceIn(0, 0xFFFF) ?: 0,
            cameraType = state.cameraType.coerceIn(0, 255),
            crossFlag = if (state.hasJunctionView) 1 else 0,
            etaMin = state.etaEpochMin.coerceIn(0, 0xFFFF),
            sceneVersion = 0,
            latE7 = ((state.lat ?: 0.0) * 1e7).toInt(),
            lonE7 = ((state.lon ?: 0.0) * 1e7).toInt(),
            bearingDeg100 = (state.bearingDeg * 100).toInt().coerceIn(0, 35_999),
        )
        container.link.send(
            Frame(MsgType.NAV, seq = nextSeq(), payload = nav.encode()),
        )
    }

    private fun nextSeq(): Int = (seq++ and 0xFFFF)

    private fun notification(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "导航", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(state: NavState) {
        val title = state.currentRoad.ifBlank { "导航中" }
        val text = HudText.distance(state.stepDistanceM) + " · " +
            HudText.remaining(state.remainDistanceM, state.remainMinutes)
        getSystemService(NotificationManager::class.java).notify(ID, notification(title, text))
    }

    override fun onDestroy() {
        scope.cancel()
        container.link.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** One a second is enough: the planner dedupes, the announcer queues. */
    private fun announce(state: NavState) {
        val input = io.mionav.voice.VoiceInput(
            active = state.active,
            recalculating = state.recalculating,
            arrived = state.arrived,
            maneuver = state.maneuver,
            stepDistanceM = state.stepDistanceM,
            nextRoad = state.nextRoad,
        )
        container.planner.onState(input, System.currentTimeMillis())
            ?.let { container.voice.speak(it.text) }
    }
}
