package io.mionav.nav

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiSearch
import io.mionav.geo.Gcj
import io.mionav.geo.LinkFollower
import io.mionav.geo.Point
import io.mionav.geo.ShareLinkParser
import io.mionav.geo.ShareTarget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Turns an Amap share link into a routing destination.
 *
 * Coordinates in a share link are GCJ-02, so they are converted here, at the Amap boundary; the
 * rest of the app only ever sees WGS84. A bare `poiid` has no coordinates at all, which is what
 * the search SDK is for: `PoiSearch.searchPOIIdAsyn` resolves the id into a [PoiItem].
 */
class ShareResolver(private val context: Context) {

    sealed interface Result {
        data class Resolved(val point: Point, val name: String?) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun resolve(raw: String): Result {
        val target = ShareLinkParser.parse(raw) ?: return Result.Failed("no Amap link in the text")
        return resolve(target)
    }

    suspend fun resolve(target: ShareTarget): Result = when (target) {
        is ShareTarget.Coordinates -> resolved(target.lat, target.lon, target.name)
        is ShareTarget.Poi -> byId(target.poiId, target.name)
        is ShareTarget.ShortLink -> when (val followed = follower.follow(target.url)) {
            is LinkFollower.Result.Failed -> {
                io.mionav.link.Diag.event("share.follow.failed", followed.reason)
                Result.Failed(followed.reason)
            }
            is LinkFollower.Result.Final -> {
                // The destination can sit in the final URL or, on a JS bounce page, in the body.
                val inner = ShareLinkParser.parse(followed.url)
                    ?: followed.body?.let { ShareLinkParser.parse(it) }
                    ?: return Result.Failed("redirect target unparsed: ${followed.url}")
                if (inner is ShareTarget.ShortLink) Result.Failed("redirect loop") else resolve(inner)
            }
        }
    }

    /** `surl.amap.com/xyz` answers with a 302 to a full `uri.amap.com` URL. */
    private val follower = LinkFollower(onHop = { io.mionav.link.Diag.event("share.hop", it) })

    private suspend fun byId(poiId: String, name: String?): Result = withTimeoutOrNull(6000) {
        suspendCancellableCoroutine { cont ->
            val search = runCatching {
                PoiSearch(context, PoiSearch.Query("", "", "")).apply {
                    setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: com.amap.api.services.poisearch.PoiResult?, code: Int) = Unit

                        override fun onPoiItemSearched(item: PoiItem?, code: Int) {
                            val point = item?.latLonPoint
                            val outcome = if (code == AMapException.CODE_AMAP_SUCCESS && point != null) {
                                val (lat, lon) = Gcj.gcjToWgs84(point.latitude, point.longitude)
                                Result.Resolved(Point(lat, lon), item.title ?: name)
                            } else {
                                Result.Failed("poi search failed: $code")
                            }
                            if (cont.isActive) cont.resume(outcome)
                        }
                    })
                }
            }.getOrElse { return@suspendCancellableCoroutine cont.resume(Result.Failed(it.message ?: "search init failed")) }

            runCatching { search.searchPOIIdAsyn(poiId) }
                .onFailure { if (cont.isActive) cont.resume(Result.Failed(it.message ?: "search call failed")) }
        }
    } ?: Result.Failed("poi search timed out")

    private fun resolved(lat: Double, lon: Double, name: String?): Result {
        if (lat !in 3.0..54.0 || lon !in 73.0..136.0) return Result.Failed("outside China: $lat,$lon")
        val (wLat, wLon) = Gcj.gcjToWgs84(lat, lon)
        return Result.Resolved(Point(wLat, wLon), name)
    }
}
