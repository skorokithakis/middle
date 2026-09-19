package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTest {

    private val alarmAction = Action(
        id = "a1",
        enabled = true,
        type = ActionType.ALARM,
        pattern = Action.DEFAULT_ALARM_PATTERN,
        suppressWebhook = true,
    )

    @Test
    fun listRoundTripsThroughJson() {
        val actions = listOf(
            alarmAction,
            alarmAction.copy(id = "a2", enabled = false, suppressWebhook = false),
        )
        assertEquals(actions, Action.fromJson(Action.toJson(actions)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals("[]", Action.toJson(emptyList()))
        assertTrue(Action.fromJson(Action.toJson(emptyList())).isEmpty())
    }

    @Test
    fun unknownTypeEntriesAreSkippedAndValidOnesKept() {
        val json = """
            [
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","suppressWebhook":true},
              {"id":"a2","enabled":true,"type":"SOMETHING_ELSE","pattern":"y","suppressWebhook":false}
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
              {"id":"a2","enabled":true,"type":"ALARM","pattern":"x","suppressWebhook":false}
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
    fun parseJsonOrNullReturnsNullForNonArrayAndSkipsBadEntries() {
        assertNull(Action.parseJsonOrNull("not json"))
        assertNull(Action.parseJsonOrNull("""{"actions":[]}"""))
        val json = """
            [
              "not an object",
              {"id":"a1","enabled":true,"type":"ALARM","pattern":"x","suppressWebhook":true}
            ]
        """.trimIndent()
        assertEquals(listOf("a1"), Action.parseJsonOrNull(json)?.map { it.id })
    }
}
