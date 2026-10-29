package io.mionav.geo

/**
 * Debug helper for share links: `./gradlew :core:geo:probeShare -Plink="<share text>"`.
 *
 * Sharing to the phone is slow to iterate on; this runs the same parse and the same redirect
 * follower on the desktop so a bad link can be diagnosed without a device.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: probeShare -Plink=\"<share text or url>\"")
        return
    }
    val raw = args.joinToString(" ")

    println("input: $raw")
    val target = ShareLinkParser.parse(raw)
    println("parsed: ${target ?: "no amap link found"}")
    if (target == null) return

    if (target is ShareTarget.ShortLink) {
        when (val followed = LinkFollower().follow(target.url)) {
            is LinkFollower.Result.Failed -> {
                println("follow failed: ${followed.reason}")
                return
            }
            is LinkFollower.Result.Final -> {
                println("final url: ${followed.url}")
                val inner = ShareLinkParser.parse(followed.url)
                    ?: followed.body?.let { ShareLinkParser.parse(it) }
                println("inner: ${inner ?: "unparsed"}")
                if (inner is ShareTarget.ShortLink) println("verdict: redirect loop")
            }
        }
    }
}
