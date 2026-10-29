package io.mionav.nav

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiSearch
import io.mionav.geo.Gcj
import io.mionav.geo.Point
import io.mionav.geo.ShareLinkParser
import io.mionav.geo.ShareTarget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
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
        is ShareTarget.ShortLink -> {
            val final = followRedirect(target.url) ?: return Result.Failed("short link did not redirect")
            val inner = ShareLinkParser.parse(final) ?: return Result.Failed("redirect target unparsed: $final")
            if (inner is ShareTarget.ShortLink) Result.Failed("redirect loop") else resolve(inner)
        }
    }

    /** `surl.amap.com/xyz` answers with a 302 to a full `uri.amap.com` URL. */
    private fun followRedirect(link: String): String? = runCatching {
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        conn.connect()
        val finalUrl = conn.url.toString()
        conn.disconnect()
        finalUrl
    }.getOrNull()

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
