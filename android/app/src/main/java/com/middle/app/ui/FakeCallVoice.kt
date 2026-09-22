package com.middle.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

private const val TAG = "FakeCallVoice"

/** The dropdown choice for the engine's default voice; a blank name means it. */
internal const val DEFAULT_VOICE = ""

/**
 * A TTS voice reduced to the fields the picker filters on, so the choice can be
 * tested on the JVM without an Android [TextToSpeech] engine.
 */
internal data class VoiceCandidate(
    val name: String,
    val language: String,
    val networkRequired: Boolean,
    val notInstalled: Boolean,
)

/**
 * The installed, offline [candidates] names for [deviceLanguage], sorted by
 * name. Other languages, network voices and voices the engine reports as not
 * installed are left out.
 */
internal fun installedVoiceNames(
    candidates: List<VoiceCandidate>,
    deviceLanguage: String,
): List<String> = candidates
    .filter { it.language == deviceLanguage && !it.networkRequired && !it.notInstalled }
    .map { it.name }
    .sorted()

/**
 * The dropdown's choices: "Default" first, then [installed] voices, then
 * [saved] when it is not installed. Keeping a saved-but-missing name visible
 * means a voice that was uninstalled after the action was saved is not silently
 * lost.
 */
internal fun fakeCallVoiceOptions(installed: List<String>, saved: String): List<String> {
    val options = mutableListOf(DEFAULT_VOICE)
    options.addAll(installed)
    if (saved.isNotEmpty() && saved !in options) options.add(saved)
    return options
}

/**
 * Owns the Actions screen's single [TextToSpeech] instance, used to list the
 * installed voices and to preview a fake call's message. It is created when the
 * screen opens and shut down when it leaves.
 */
internal class FakeCallVoicePreview(context: Context) {

    private var textToSpeech: TextToSpeech? = null

    /** True once the engine is initialised; until then only "Default" is offered. */
    var ready by mutableStateOf(false)
        private set

    /** Installed voices for the device language, sorted by name. */
    var voiceNames by mutableStateOf(emptyList<String>())
        private set

    init {
        textToSpeech = TextToSpeech(context) { status ->
            val tts = textToSpeech
            if (status != TextToSpeech.SUCCESS || tts == null) {
                Log.w(TAG, "Fake call voice list unavailable; TTS init failed")
                return@TextToSpeech
            }
            voiceNames = installedVoiceNames(
                candidates = tts.voices.orEmpty().map { it.toCandidate() },
                deviceLanguage = Locale.getDefault().language,
            )
            ready = true
        }
    }

    /**
     * Speaks [text] with [voiceName], or the engine's default voice when it is
     * blank or not installed. QUEUE_FLUSH replaces any preview still playing.
     */
    fun speak(text: String, voiceName: String) {
        val tts = textToSpeech ?: return
        if (!ready) return
        val voice = if (voiceName.isEmpty()) {
            tts.defaultVoice
        } else {
            tts.voices.orEmpty().firstOrNull { it.name == voiceName }
        }
        if (voice != null) tts.voice = voice
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE_ID)
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
        voiceNames = emptyList()
    }

    private fun Voice.toCandidate(): VoiceCandidate = VoiceCandidate(
        name = name,
        language = locale.language,
        networkRequired = isNetworkConnectionRequired,
        notInstalled = features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
    )

    private companion object {
        const val PREVIEW_UTTERANCE_ID = "fake_call_voice_preview"
    }
}
