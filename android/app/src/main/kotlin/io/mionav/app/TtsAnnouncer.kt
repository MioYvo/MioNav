package io.mionav.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.mionav.link.Diag
import java.util.Locale

/**
 * Speaks the next instruction, borrowing the audio briefly and giving it back.
 *
 * The focus request is `GAIN_TRANSIENT_MAY_DUCK` with `setWillPauseWhenDucked(false)`: players
 * that honour ducking turn down instead of stopping, and the ones that do not simply keep playing.
 * Guidance uses the media stream so a Bluetooth headset stays on A2DP — switching to SCO would cut
 * the music and sound like a phone call.
 */
class TtsAnnouncer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { }
        .build()

    private val params = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    private var engine: TextToSpeech? = null
    private var ready = false
    private var speaking = 0
    private var utterance = 0
    private val queued = ArrayDeque<String>()

    /** Off means the planner still runs; it just does not play. */
    @Volatile
    var enabled: Boolean = true

    init {
        engine = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Diag.event("voice.init.failed", status)
            return
        }
        val tts = engine ?: return
        // Guidance, not notification: Do Not Disturb silences notifications but not navigation,
        // and media/guidance is what keeps a Bluetooth headset on A2DP.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.language = Locale.SIMPLIFIED_CHINESE
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = release()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = release()

            override fun onError(utteranceId: String?, errorCode: Int) = release()
        })
        ready = true
        val voiceName = runCatching {
            tts.voices?.firstOrNull { it.locale.language == "zh" }?.name
        }.getOrNull()
        Diag.event("voice.init.ok", voiceName ?: "system default")
        while (queued.isNotEmpty()) play(queued.removeFirst())
    }

    fun speak(text: String) {
        if (!enabled) {
            Diag.event("voice.muted", text)
            return
        }
        if (text.isBlank()) return
        if (!ready) {
            // The engine takes a moment to warm up; keep the latest instruction only.
            queued.clear()
            queued.addLast(text)
            return
        }
        play(text)
    }

    private fun play(text: String) {
        val tts = engine ?: return
        val focus = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        Diag.event("voice.speak", "focus=$focus $text")
        speaking += 1
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, params, "mionav-${utterance++}")
        if (result != TextToSpeech.SUCCESS) {
            release()
        }
    }

    /** One call per finished utterance; the last one hands the audio back. */
    private fun release() {
        if (speaking > 0) speaking -= 1
        if (speaking > 0) return
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
        Diag.event("voice.done")
    }

    fun shutdown() {
        ready = false
        queued.clear()
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        release()
    }
}
