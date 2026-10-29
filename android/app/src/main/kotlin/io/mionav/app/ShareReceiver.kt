package io.mionav.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mionav.geo.ShareLinkParser
import io.mionav.geo.ShareTarget

/**
 * Amap's share sheet lands here. Parsing only; the user confirms on the map before anything moves.
 */
class ShareReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val target = ShareLinkParser.parse(text) ?: return
        val app = context.applicationContext as MioNavApp
        app.container.pendingTarget = target

        val open = Intent(context, ConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when (target) {
            is ShareTarget.ShortLink -> open.putExtra(ConfirmActivity.EXTRA_SHORT_LINK, text)
            else -> Unit
        }
        context.startActivity(open)
    }
}
