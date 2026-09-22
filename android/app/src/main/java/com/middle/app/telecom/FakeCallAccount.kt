package com.middle.app.telecom

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.middle.app.R

/**
 * Owns Middle's calling account, the handle [TelecomManager.addNewIncomingCall]
 * raises fake calls against. The account is registered with Telecom but stays
 * disabled until the user enables it once in the system Calling accounts
 * settings.
 *
 * The enabled state is never read. [TelecomManager.getPhoneAccount] requires
 * READ_PHONE_NUMBERS for a non-dialer app targeting API 31+ (Telecom's
 * ENABLE_GET_PHONE_ACCOUNT_PERMISSION_PROTECTION compat change), even for
 * Middle's own account, and [TelecomManager.getCallCapablePhoneAccounts] needs
 * READ_PHONE_STATE. There is no permission-free way to read it and no runtime
 * phone permission is wanted, so the only signal is the [SecurityException]
 * [TelecomManager.addNewIncomingCall] throws while the account is not
 * registered/enabled.
 */
object FakeCallAccount {

    /** Extra key for the caller name shown on the incoming-call screen. */
    const val EXTRA_CALLER_NAME = "com.middle.app.extra.FAKE_CALL_CALLER_NAME"

    /** Extra key for the message the answered call speaks through TTS. */
    const val EXTRA_MESSAGE = "com.middle.app.extra.FAKE_CALL_MESSAGE"

    /** Extra key for the engine voice the message is spoken with. */
    const val EXTRA_VOICE_NAME = "com.middle.app.extra.FAKE_CALL_VOICE_NAME"

    private const val ACCOUNT_ID = "middle_fake_call"

    /** The stable handle identifying Middle's account inside Telecom. */
    fun handle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, FakeCallConnectionService::class.java),
        ACCOUNT_ID,
    )

    /**
     * Registers the account with Telecom. Registration is idempotent, so it is
     * safe to call before every fake call regardless of whether it already ran.
     */
    fun register(context: Context) {
        val manager = context.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(context), context.getString(R.string.app_name))
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .build()
        manager.registerPhoneAccount(account)
    }

    /**
     * Opens the system Calling accounts settings, preferring the screen scoped
     * to Middle's account and falling back to the general call settings when an
     * OEM does not resolve the former. Resolution is attempted by actually
     * starting the intent: Android 11+ package visibility can make
     * `resolveActivity` return null for an activity that does handle it. The
     * caller is an activity, so no `FLAG_ACTIVITY_NEW_TASK` is needed.
     */
    fun openSettings(context: Context) {
        val changeIntent = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            .putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
        try {
            context.startActivity(changeIntent)
            return
        } catch (exception: ActivityNotFoundException) {
            Log.d(TAG, "No activity for ACTION_CHANGE_PHONE_ACCOUNTS; trying call settings")
        }
        try {
            context.startActivity(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No activity for Calling accounts settings", exception)
        }
    }

    private const val TAG = "FakeCallAccount"
}
