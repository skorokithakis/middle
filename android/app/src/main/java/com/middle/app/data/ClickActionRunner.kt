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
) {

    fun run(clickCount: Int) {
        // Settings reads can throw, so the lookup and the enabled check are
        // inside the boundary too: a click's failure must never escape into the
        // sync service's loop.
        var action: Action? = null
        try {
            val bound = clickActions()[clickCount] ?: return
            action = bound
            if (!bound.enabled) return

            when (bound.type) {
                ActionType.WEBHOOK -> runWebhook(bound)
                // A click has no transcript, so only the types that make sense
                // on their own are offered in the click-action UI. The others
                // are transcript-dependent and ignored if a hand-edited backup
                // supplies one.
                ActionType.FAKE_CALL, ActionType.MEDIA_KEY ->
                    runAction(ActionHit(bound, rest = "", index = 0))
                ActionType.ALARM, ActionType.CALENDAR, ActionType.PLAY_MEDIA ->
                    Log.w(TAG, "Ignoring ${bound.type} click action ${bound.id}: it needs a transcript")
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
