package io.mionav.link

import android.util.Log

/**
 * One tag for everything, plus a ring buffer the UI can show.
 *
 * Debugging by asking "what happened on screen?" is slow; `adb logcat -s MioNav` should tell the
 * whole story of a share attempt, a pack sync or a hello, in order.
 */
object Diag {

    const val TAG = "MioNav"
    private const val KEEP = 60

    private val ring = ArrayDeque<String>()

    fun event(name: String, detail: Any? = null) {
        val line = if (detail == null) name else "$name ${detail.toString().take(300)}"
        Log.i(TAG, line)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > KEEP) ring.removeFirst()
        }
    }

    /** The last events, oldest first, for the on-screen debug panel. */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }
}
