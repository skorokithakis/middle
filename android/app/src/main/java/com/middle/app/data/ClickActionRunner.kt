package com.middle.app.data

import android.util.Log

/**
 * Runs the action bound to a ring button click count.
 *
 * A ring click is a bare collection: it carries no audio and no transcript, so a
 * click action runs once, against an empty transcript, and is never queued or
 * retried. It is fire-and-forget — a webhook is a single best-effort POST and
 * the local actions are synchronous system calls. A click can arrive while the
 * app is backgrounded, so every failure is caught here and logged rather than
 * allowed to escape into the sync service's loop.
 *
 * The effects are injected so the dispatch can be unit tested off-device; the
 * production wiring lives in [com.middle.app.ble.SyncForegroundService].
 */
class ClickActionRunner(
    private val clickActions: () -> Map<Int, Action>,
    private val runAction: (ActionHit) -> Unit,
    private val postWebhook: (url: String, template: String) -> WebhookClient.Result,
    // The count whose click tries to end a call before its slot action; 0 is off.
    private val hangUpClickCount: () -> Int,
    // True when a call was actually ended.
    private val endCall: () -> Boolean,
) {

    fun run(clickCount: Int) {
        // Settings reads can throw, so the lookup and the enabled check are
        // inside the boundary too: a click's failure must never escape into the
        // sync service's loop.
        var action: Action? = null
        try {
            // The hang-up override is a separate setting, not a slot: it is
            // tried first and, when it ends a call, the slot's action is skipped.
            // A click with no call to end falls through to the slot as usual.
            val hangUpCount = hangUpClickCount()
            if (hangUpCount > 0 && clickCount == hangUpCount) {
                if (endCall()) {
                    WebhookLog.info("Ring hang-up: ended a call")
                    return
                }
                // False also covers a failed precondition, which ActionRunner
                // logs on its own line, so this does not claim there was no call.
                WebhookLog.info("Ring hang-up: no call ended")
            }

            val bound = clickActions()[clickCount]
            if (bound == null) {
                WebhookLog.info("Ring click $clickCount: no action bound")
                return
            }
            action = bound
            if (!bound.enabled) {
                WebhookLog.info("Ring click $clickCount: action ${bound.id} is disabled")
                return
            }

            when (bound.type) {
                ActionType.WEBHOOK -> {
                    WebhookLog.info("Ring click $clickCount: running WEBHOOK")
                    runWebhook(bound)
                }
                // A click has no transcript, so only the types that make sense
                // on their own are offered in the click-action UI.
                ActionType.FAKE_CALL -> {
                    WebhookLog.info("Ring click $clickCount: running FAKE_CALL")
                    runAction(ActionHit(bound, rest = "", index = 0))
                }
                ActionType.MEDIA_KEY -> {
                    WebhookLog.info(
                        "Ring click $clickCount: running MEDIA_KEY (${bound.mediaKey})",
                    )
                    runAction(ActionHit(bound, rest = "", index = 0))
                }
                ActionType.ALARM, ActionType.CALENDAR, ActionType.PLAY_MEDIA -> {
                    Log.w(TAG, "Ignoring ${bound.type} click action ${bound.id}: it needs a transcript")
                    WebhookLog.info(
                        "Ring click $clickCount: ignoring ${bound.type}: it needs a transcript",
                    )
                }
                // HANG_UP left a slot in an earlier build, when it was a click
                // choice; the setting above replaced it. A hand-edited backup
                // can still supply one, so it is logged and ignored.
                ActionType.HANG_UP -> {
                    Log.w(TAG, "Ignoring HANG_UP click action ${bound.id}: hang up is now a setting")
                    WebhookLog.info("Ring click $clickCount: ignoring HANG_UP: hang up is a setting")
                }
            }
        } catch (exception: Exception) {
            // One click's failure must never take the sync service down with it.
            Log.e(TAG, "Click action ${action?.id ?: "for click $clickCount"} failed", exception)
            WebhookLog.error("Click action failed: ${exception::class.simpleName}: ${exception.message}")
        }
    }

    private fun runWebhook(action: Action) {
        if (action.webhookUrl.isBlank()) {
            Log.w(TAG, "WEBHOOK click action ${action.id} has no URL; skipping")
            WebhookLog.error("Click webhook skipped: no URL")
            return
        }
        val template = action.webhookBodyTemplate.ifBlank { Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE }
        val result = postWebhook(action.webhookUrl, template)
        if (result.success) {
            WebhookLog.info("Click webhook sent (${result.code})")
        } else {
            WebhookLog.error("Click webhook failed (${result.code} ${result.message})")
        }
    }

    companion object {
        private const val TAG = "ClickActionRunner"
    }
}
