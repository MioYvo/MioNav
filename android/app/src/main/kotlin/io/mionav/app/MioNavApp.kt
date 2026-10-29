package io.mionav.app

import android.app.Application
import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNavi
import io.mionav.link.LinkServer
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
    var plan: RoutePlan? = null
    var destinationName: String? = null

    /** Share links carry GCJ-02 coordinates and bare POI ids; both need the Amap SDK. */
    val resolver = io.mionav.nav.ShareResolver(context)
}
