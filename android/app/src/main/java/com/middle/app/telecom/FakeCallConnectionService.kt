package com.middle.app.telecom

import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

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
        val connection = FakeCallConnection()
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
 * One ringing fake call. Answering activates it; rejecting, disconnecting or
 * leaving it ringing for 45 seconds ends it. There is no audio: the call exists
 * only for as long as the dialer shows its UI.
 */
private class FakeCallConnection : Connection() {

    private val handler = Handler(Looper.getMainLooper())

    private val missedCallTimeout = Runnable { disconnectWith(DisconnectCause.MISSED) }

    fun startMissedCallTimeout() {
        handler.postDelayed(missedCallTimeout, MISSED_CALL_TIMEOUT_MILLIS)
    }

    override fun onAnswer() {
        handler.removeCallbacks(missedCallTimeout)
        setActive()
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

    private fun disconnectWith(reason: Int) {
        handler.removeCallbacks(missedCallTimeout)
        setDisconnected(DisconnectCause(reason))
        destroy()
    }

    private companion object {
        const val MISSED_CALL_TIMEOUT_MILLIS = 45_000L
    }
}
