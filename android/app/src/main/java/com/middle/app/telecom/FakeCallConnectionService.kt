package com.middle.app.telecom

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Managed [ConnectionService] for fake calls. Telecom calls
 * [onCreateIncomingConnection] after [TelecomManager.addNewIncomingCall], and
 * the default dialer shows its own incoming-call screen from there.
 */
class FakeCallConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        val message = request.extras?.getString(FakeCallAccount.EXTRA_MESSAGE).orEmpty()
        val connection = FakeCallConnection(this, message)
        connection.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        val callerName = request.extras?.getString(FakeCallAccount.EXTRA_CALLER_NAME)
        if (!callerName.isNullOrEmpty()) {
            connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        }
        connection.setRinging()
        connection.startMissedCallTimeout()
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection = Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
}

/**
 * One ringing fake call. Answering activates it and speaks the message through
 * TTS, repeating until the call ends; rejecting, disconnecting or leaving it
 * ringing for 45 seconds ends it. A blank message stays silent. The engine is
 * built while ringing so it is ready to speak as soon as the call is answered.
 */
private class FakeCallConnection(
    context: Context,
    message: String,
) : Connection() {

    private val handler = Handler(Looper.getMainLooper())

    // Non-blank lines, in order; a blank message leaves this empty.
    private val lines = message.lines().map { it.trim() }.filter { it.isNotEmpty() }
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var answered = false

    private val missedCallTimeout = Runnable { disconnectWith(DisconnectCause.MISSED) }
    private val startSpeech = Runnable { speakCycle() }

    init {
        if (lines.isNotEmpty()) {
            textToSpeech = TextToSpeech(context, ::onTtsInit)
        } else {
            Log.d(TAG, "[action] fake call has no message; staying silent")
        }
    }

    fun startMissedCallTimeout() {
        handler.postDelayed(missedCallTimeout, MISSED_CALL_TIMEOUT_MILLIS)
    }

    override fun onAnswer() {
        handler.removeCallbacks(missedCallTimeout)
        setActive()
        if (lines.isEmpty()) return
        answered = true
        if (ttsReady) {
            handler.postDelayed(startSpeech, ANSWER_DELAY_MILLIS)
        }
        // While the engine is still initialising, onTtsInit starts speech.
    }

    override fun onReject() {
        disconnectWith(DisconnectCause.REJECTED)
    }

    // API 30+ dialers may reject with a reason or a quick-reply message instead
    // of the no-arg overload; route both to the same disconnect cause. They are
    // simply never called on older API levels.
    override fun onReject(rejectReason: Int) {
        disconnectWith(DisconnectCause.REJECTED)
    }

    override fun onReject(replyMessage: String) {
        disconnectWith(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        disconnectWith(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        disconnectWith(DisconnectCause.LOCAL)
    }

    private fun onTtsInit(status: Int) {
        val tts = textToSpeech
        if (status != TextToSpeech.SUCCESS || tts == null) {
            Log.w(TAG, "[action] fake call TTS init failed; staying silent")
            return
        }
        // Route speech over the call's voice-communication stream so it is
        // treated as call audio rather than a media playback.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onError(utteranceId: String?) {
                Log.w(TAG, "[action] fake call TTS error on $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                // The whole cycle is queued at once; the final pause's
                // completion queues the next one until the call ends.
                if (utteranceId == pauseId(lines.lastIndex)) {
                    handler.post(startSpeech)
                }
            }
        })
        ttsReady = true
        // The call was answered before the engine finished initialising.
        if (answered) {
            handler.postDelayed(startSpeech, ANSWER_DELAY_MILLIS)
        }
    }

    /**
     * Queues the message's lines with a silent pause after each — 2 s between
     * lines and 4 s after the last — and the final pause's completion queues the
     * next cycle. The whole loop is a single QUEUE_ADD batch, so no timer has to
     * guess how long a line takes to speak.
     */
    private fun speakCycle() {
        val tts = textToSpeech ?: return
        if (!ttsReady || lines.isEmpty()) return
        for ((index, line) in lines.withIndex()) {
            tts.speak(line, TextToSpeech.QUEUE_ADD, null, lineId(index))
            val pause = if (index == lines.lastIndex) PAUSE_AFTER_MILLIS else PAUSE_BETWEEN_MILLIS
            tts.playSilentUtterance(pause, TextToSpeech.QUEUE_ADD, pauseId(index))
        }
    }

    private fun disconnectWith(reason: Int) {
        handler.removeCallbacksAndMessages(null)
        textToSpeech?.setOnUtteranceProgressListener(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        setDisconnected(DisconnectCause(reason))
        destroy()
    }

    private companion object {
        const val TAG = "FakeCallConnection"
        const val MISSED_CALL_TIMEOUT_MILLIS = 45_000L
        const val ANSWER_DELAY_MILLIS = 1_000L
        const val PAUSE_BETWEEN_MILLIS = 2_000L
        const val PAUSE_AFTER_MILLIS = 4_000L

        fun lineId(index: Int) = "fake_call_line_$index"
        fun pauseId(index: Int) = "fake_call_pause_$index"
    }
}
