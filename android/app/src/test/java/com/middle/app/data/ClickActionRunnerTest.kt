package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickActionRunnerTest {

    private var runCalls = 0
    private var capturedHit: ActionHit? = null
    private var webhookCalls = 0
    private var capturedUrl: String? = null
    private var capturedTemplate: String? = null

    private fun success() = WebhookClient.Result(success = true, code = 200, message = "OK", body = "")

    private fun runner(actions: Map<Int, Action>): ClickActionRunner = ClickActionRunner(
        clickActions = { actions },
        runAction = { hit ->
            runCalls++
            capturedHit = hit
        },
        postWebhook = { url, template ->
            webhookCalls++
            capturedUrl = url
            capturedTemplate = template
            success()
        },
    )

    private fun action(
        id: String,
        type: ActionType,
        enabled: Boolean = true,
        webhookUrl: String = "",
        webhookBodyTemplate: String = "",
    ) = Action(
        id = id,
        enabled = enabled,
        type = type,
        pattern = "",
        stop = false,
        webhookUrl = webhookUrl,
        webhookBodyTemplate = webhookBodyTemplate,
    )

    @Test
    fun unboundClickCountRunsNothing() {
        runner(emptyMap()).run(2)

        assertEquals(0, runCalls)
        assertEquals(0, webhookCalls)
    }

    @Test
    fun disabledActionRunsNothing() {
        val actions = mapOf(1 to action("a1", ActionType.MEDIA_KEY, enabled = false))

        runner(actions).run(1)

        assertEquals(0, runCalls)
        assertEquals(0, webhookCalls)
    }

    @Test
    fun fakeCallRunsThroughActionRunnerWithEmptyTranscript() {
        val action = action("a1", ActionType.FAKE_CALL)

        runner(mapOf(1 to action)).run(1)

        assertEquals(1, runCalls)
        assertEquals(action, capturedHit?.action)
        assertEquals("", capturedHit?.rest)
        assertEquals(0, capturedHit?.index)
    }

    @Test
    fun mediaKeyRunsThroughActionRunner() {
        val action = action("a1", ActionType.MEDIA_KEY)

        runner(mapOf(3 to action)).run(3)

        assertEquals(1, runCalls)
        assertEquals(action, capturedHit?.action)
    }

    @Test
    fun webhookPostsOnceWithTheConfiguredTemplate() {
        val actions = mapOf(
            1 to action(
                "a1",
                ActionType.WEBHOOK,
                webhookUrl = "https://example.com/hook",
                webhookBodyTemplate = """{"phrase": "${'$'}transcript"}""",
            ),
        )

        runner(actions).run(1)

        assertEquals(1, webhookCalls)
        assertEquals("https://example.com/hook", capturedUrl)
        assertEquals("""{"phrase": "${'$'}transcript"}""", capturedTemplate)
    }

    @Test
    fun webhookFallsBackToTheDefaultTemplateWhenBlank() {
        val actions = mapOf(
            1 to action("a1", ActionType.WEBHOOK, webhookUrl = "https://example.com/hook"),
        )

        runner(actions).run(1)

        assertEquals(1, webhookCalls)
        assertEquals(Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE, capturedTemplate)
    }

    @Test
    fun webhookWithBlankUrlIsSkipped() {
        val actions = mapOf(1 to action("a1", ActionType.WEBHOOK, webhookUrl = "   "))

        runner(actions).run(1)

        assertEquals(0, webhookCalls)
    }

    @Test
    fun transcriptDependentTypesAreIgnored() {
        val actions = mapOf(
            1 to action("a1", ActionType.ALARM),
            2 to action("a2", ActionType.CALENDAR),
            3 to action("a3", ActionType.PLAY_MEDIA),
        )
        val runner = runner(actions)

        runner.run(1)
        runner.run(2)
        runner.run(3)

        assertEquals(0, runCalls)
        assertEquals(0, webhookCalls)
    }

    @Test
    fun aThrowingClickActionsProviderIsSwallowed() {
        val runner = ClickActionRunner(
            clickActions = { throw IllegalStateException("settings unreadable") },
            runAction = { runCalls++ },
            postWebhook = { _, _ -> success() },
        )

        runner.run(1)

        assertEquals(0, runCalls)
        assertEquals(0, webhookCalls)
    }

    @Test
    fun anActionFailureIsSwallowed() {
        val runner = ClickActionRunner(
            clickActions = { mapOf(1 to action("a1", ActionType.MEDIA_KEY)) },
            runAction = { throw SecurityException("no media session") },
            postWebhook = { _, _ -> success() },
        )

        runner.run(1)
    }

    @Test
    fun aWebhookFailureIsSwallowed() {
        val runner = ClickActionRunner(
            clickActions = { mapOf(1 to action("a1", ActionType.WEBHOOK, webhookUrl = "not a url")) },
            runAction = { },
            postWebhook = { _, _ -> throw IllegalArgumentException("bad url") },
        )

        runner.run(1)
    }

    @Test
    fun theClickCountSelectsItsOwnAction() {
        val one = action("one", ActionType.MEDIA_KEY)
        val three = action("three", ActionType.MEDIA_KEY)
        val runner = runner(mapOf(1 to one, 3 to three))

        runner.run(3)

        assertEquals(1, runCalls)
        assertEquals(three, capturedHit?.action)
    }
}
