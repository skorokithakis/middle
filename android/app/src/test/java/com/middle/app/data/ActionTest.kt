package com.middle.app.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTest {

    private val alarmAction = Action(
        id = "a1",
        enabled = true,
        type = ActionType.ALARM,
        pattern = Action.DEFAULT_ALARM_PATTERN,
        stop = true,
    )

    @Test
    fun listRoundTripsThroughJson() {
        val actions = listOf(
            alarmAction,
            Action(
                id = "a2",
                enabled = false,
                type = ActionType.CALENDAR,
                pattern = Action.DEFAULT_CALENDAR_PATTERN,
                stop = true,
            ),
            Action(
                id = "a3",
                enabled = true,
                type = ActionType.WEBHOOK,
                pattern = Action.DEFAULT_WEBHOOK_PATTERN,
                stop = false,
                webhookUrl = "https://example.com/hook",
                webhookBodyTemplate = """{"text": "${'$'}transcript"}""",
            ),
        )
        assertEquals(actions, Action.fromJson(Action.toJson(actions)))
    }

    @Test
    fun listOrderIsPreserved() {
        val actions = listOf(
            alarmAction.copy(id = "first"),
            alarmAction.copy(id = "second"),
            alarmAction.copy(id = "third"),
        )
        assertEquals(
            listOf("first", "second", "third"),
            Action.fromJson(Action.toJson(actions)).map { it.id },
        )
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals("[]", Action.toJson(emptyList()))
        assertTrue(Action.fromJson(Action.toJson(emptyList())).isEmpty())
    }

    @Test
    fun newFieldsAreSerializedAndLegacyKeyIsNot() {
        val json = JSONArray(Action.toJson(listOf(alarmAction))).getJSONObject(0)
        assertTrue(json.has("stop"))
        assertFalse(json.has("suppressWebhook"))
        assertTrue(json.has("webhookUrl"))
        assertTrue(json.has("webhookBodyTemplate"))
    }

    @Test
    fun legacySuppressWebhookKeyReadsAsStop() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","suppressWebhook":true}
            ]
        """.trimIndent()
        val action = Action.fromJson(json).single()
        assertTrue(action.stop)
    }

    @Test
    fun stopKeyWinsOverLegacySuppressWebhookKey() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","stop":false,"suppressWebhook":true}
            ]
        """.trimIndent()
        assertFalse(Action.fromJson(json).single().stop)
    }

    @Test
    fun absentWebhookFieldsReadAsEmptyDefaults() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","stop":true}
            ]
        """.trimIndent()
        val action = Action.fromJson(json).single()
        assertEquals("", action.webhookUrl)
        assertEquals("", action.webhookBodyTemplate)
    }

    @Test
    fun missingStopAndLegacyKeyIsMalformed() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x"}
            ]
        """.trimIndent()
        assertTrue(Action.fromJson(json).isEmpty())
    }

    @Test
    fun unknownTypeEntriesAreSkippedAndValidOnesKept() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","stop":true},
              {"id":"a2","enabled":true,"type":"SOMETHING_ELSE","pattern":"y","stop":false}
            ]
        """.trimIndent()
        val actions = Action.fromJson(json)
        assertEquals(listOf("a1"), actions.map { it.id })
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val json = """
            [
              "not an object",
              {"id":"a1","type":"ALARM"},
              {"id":"a2","enabled":true,"type":"ALARM","pattern":"x","stop":false}
            ]
        """.trimIndent()
        val actions = Action.fromJson(json)
        assertEquals(listOf("a2"), actions.map { it.id })
    }

    @Test
    fun malformedJsonReturnsEmptyList() {
        assertTrue(Action.fromJson("not json").isEmpty())
    }

    @Test
    fun fakeCallRoundTripsThroughJson() {
        val action = Action(
            id = "a4",
            enabled = true,
            type = ActionType.FAKE_CALL,
            pattern = Action.DEFAULT_FAKE_CALL_PATTERN,
            stop = true,
            callerName = "Ada Lovelace",
            callerNumber = "+15551234567",
        )
        assertEquals(listOf(action), Action.fromJson(Action.toJson(listOf(action))))
    }

    @Test
    fun absentCallerFieldsReadAsEmptyDefaults() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"FAKE_CALL","pattern":"x","stop":true}
            ]
        """.trimIndent()
        val action = Action.fromJson(json).single()
        assertEquals("", action.callerName)
        assertEquals("", action.callerNumber)
    }

    @Test
    fun parseJsonOrNullReturnsNullForNonArrayAndSkipsBadEntries() {
        assertNull(Action.parseJsonOrNull("not json"))
        assertNull(Action.parseJsonOrNull("""{"actions":[]}"""))
        val json = """
            [
              "not an object",
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","stop":true}
            ]
        """.trimIndent()
        assertEquals(listOf("a1"), Action.parseJsonOrNull(json)?.map { it.id })
    }
}
