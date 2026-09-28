package com.middle.app.ui

import com.middle.app.data.ActionType
import com.middle.app.data.MediaKey
import com.middle.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickActionChoiceTest {

    @Test
    fun nothingUnbindsTheClick() {
        assertNull(actionForClickChoice(null, ClickChoice.NONE))
    }

    @Test
    fun eachMediaChoiceStoresItsKey() {
        val cases = mapOf(
            ClickChoice.PLAY_PAUSE to MediaKey.PLAY_PAUSE,
            ClickChoice.NEXT to MediaKey.NEXT,
            ClickChoice.PREVIOUS to MediaKey.PREVIOUS,
        )
        for ((choice, mediaKey) in cases) {
            val action = requireNotNull(actionForClickChoice(null, choice))
            assertEquals(ActionType.MEDIA_KEY, action.type)
            assertEquals(mediaKey, action.mediaKey)
        }
    }

    @Test
    fun typedChoicesStoreTheirTypeWithTheDefaultBody() {
        val cases = mapOf(
            ClickChoice.FAKE_CALL to ActionType.FAKE_CALL,
            ClickChoice.WEBHOOK to ActionType.WEBHOOK,
        )
        for ((choice, type) in cases) {
            val action = requireNotNull(actionForClickChoice(null, choice))
            assertEquals(type, action.type)
            assertEquals(Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE, action.webhookBodyTemplate)
        }
    }

    @Test
    fun everyClickActionIsEnabledAndHasNoPatternOrStop() {
        val actions = ClickChoice.entries.mapNotNull { actionForClickChoice(null, it) }
        assertEquals(5, actions.size)
        for (action in actions) {
            assertTrue(action.enabled)
            assertEquals("", action.pattern)
            assertFalse(action.stop)
            assertTrue(action.id.isNotEmpty())
        }
    }

    @Test
    fun eachChoiceGetsItsOwnId() {
        val ids = ClickChoice.entries.mapNotNull { actionForClickChoice(null, it) }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun selectingTheCurrentChoiceKeepsTheActionAndItsEditedFields() {
        // Re-picking a webhook must not drop the URL or body just edited.
        val webhook = requireNotNull(actionForClickChoice(null, ClickChoice.WEBHOOK))
            .copy(webhookUrl = "https://example.com/hook", webhookBodyTemplate = "custom body")
        assertSame(webhook, actionForClickChoice(webhook, ClickChoice.WEBHOOK))

        val fakeCall = requireNotNull(actionForClickChoice(null, ClickChoice.FAKE_CALL))
            .copy(
                callerName = "Ada Lovelace",
                callerNumber = "+15551234567",
                message = "Custom message",
            )
        assertSame(fakeCall, actionForClickChoice(fakeCall, ClickChoice.FAKE_CALL))

        val playPause = requireNotNull(actionForClickChoice(null, ClickChoice.PLAY_PAUSE))
        assertSame(playPause, actionForClickChoice(playPause, ClickChoice.PLAY_PAUSE))
    }

    @Test
    fun fakeCallClickSlotUsesTheGivenMessageAndOthersStayEmpty() {
        val fakeCall = requireNotNull(
            actionForClickChoice(null, ClickChoice.FAKE_CALL, fakeCallMessage = "Filler line"),
        )
        assertEquals("Filler line", fakeCall.message)

        val webhook = requireNotNull(
            actionForClickChoice(null, ClickChoice.WEBHOOK, fakeCallMessage = "Filler line"),
        )
        assertEquals("", webhook.message)
    }

    @Test
    fun selectingADifferentChoiceReplacesTheAction() {
        val playPause = requireNotNull(actionForClickChoice(null, ClickChoice.PLAY_PAUSE))

        val next = requireNotNull(actionForClickChoice(playPause, ClickChoice.NEXT))
        assertEquals(MediaKey.NEXT, next.mediaKey)
        assertNotEquals(playPause.id, next.id)
    }
}
