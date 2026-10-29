package io.mionav.app

import android.app.Application
import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNavi
import io.mionav.link.LinkServer
import io.mionav.tilepack.PackIdentity
import io.mionav.proto.HelloFrame
import io.mionav.proto.MsgType
import io.mionav.nav.AmapNavSource
import io.mionav.nav.FakeNavSource
import io.mionav.nav.NavSource
import io.mionav.nav.RoutePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MioNavApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Amap requires the privacy contract before any SDK call, including setApiKey.
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        if (BuildConfig.AMAP_KEY.isNotBlank()) {
            AMapNavi.setApiKey(this, BuildConfig.AMAP_KEY)
        }

        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Falls back to the synthetic source so the watch and the link can be developed without a key. */
    val nav: NavSource =
        if (BuildConfig.AMAP_KEY.isNotBlank()) AmapNavSource(context) else FakeNavSource(scope)

    val link = LinkServer(context)

    var pendingTarget: io.mionav.geo.ShareTarget? = null

    /** The share body as it arrived, shown when parsing fails so it can be reported verbatim. */
    var lastShareText: String? = null
    var plan: RoutePlan? = null
    var destinationName: String? = null

    /** Share links carry GCJ-02 coordinates and bare POI ids; both need the Amap SDK. */
    val resolver = io.mionav.nav.ShareResolver(context)

    val packs = PackStore(context)
    val packHost = PackHost(packs)

    private var helloSeq = 0

    init {
        packHost.refresh()
        // The watch opens with which pack it holds; the answer decides whether a server opens at all.
        link.onFrame = { frame ->
            if (frame.type == MsgType.HELLO) {
                val hello = HelloFrame.tryDecode(frame.payload)
                val remote = hello?.takeIf { it.hasPack }?.let {
                    PackIdentity(it.packId, it.packVersion, it.packBytes, it.packSha, it.packTiles)
                }
                val url = packHost.onWatchHello(remote)
                if (link.connected) {
                    link.send(
                        MsgType.HELLO,
                        helloSeq++ and 0xFFFF,
                        HelloFrame(
                            flags = if (url == null) 0 else HelloFrame.FLAG_SERVING,
                            url = url.orEmpty(),
                        ).encode(),
                    )
                }
            }
        }
        link.onConnected = { packHost.refresh() }
    }
}
