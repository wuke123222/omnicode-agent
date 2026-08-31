package dev.omnicode.provider

import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class OpenCodeHostProviderTest {
    @Test
    fun `factory routes OpenCode to persistent host provider`() {
        val provider = ProviderFactory.create(
            ProviderConnection(
                preset = ProviderPresets.byId("cli-opencode"),
                baseUrl = "cli://local",
                model = "default",
                apiKey = "",
            ),
        )

        assertTrue(provider is OpenCodeHostProvider)
    }

    @Test
    fun `SSE parser joins data lines and skips comments`() = runBlocking {
        val stream = OpenCodeSseStream(
            ByteArrayInputStream(
                (": heartbeat\n" +
                    "data: {\"type\":\"server.connected\",\n" +
                    "data: \"properties\":{}}\n\n").toByteArray(StandardCharsets.UTF_8),
            ),
        )

        stream.use {
            assertEquals("server.connected", it.nextJson()?.stringOrNull("type"))
            assertNull(it.nextJson())
        }
    }

    @Test
    fun `event projector accepts exact session only and tolerates JsonNull`() {
        val exact = JsonObject().apply {
            addProperty("type", "session.status")
            add("properties", JsonObject().apply { addProperty("sessionID", "ses_expected") })
        }
        val nested = JsonObject().apply {
            addProperty("type", "message.updated")
            add("properties", JsonObject().apply {
                add("sessionID", com.google.gson.JsonNull.INSTANCE)
                add("info", JsonObject().apply { addProperty("sessionID", "ses_nested") })
            })
        }
        val missing = JsonObject().apply {
            addProperty("type", "session.status")
            add("properties", com.google.gson.JsonNull.INSTANCE)
        }

        assertEquals("ses_expected", openCodeHostEventSessionId(exact))
        assertEquals("ses_nested", openCodeHostEventSessionId(nested))
        assertNull(openCodeHostEventSessionId(missing))
    }

    @Test
    fun `read only modes reject local host approvals before prompting the user`() {
        assertTrue(localHostApprovalAllowed(AgentMode.AGENT))
        assertFalse(localHostApprovalAllowed(AgentMode.PLAN))
        assertFalse(localHostApprovalAllowed(AgentMode.CLAUDE_PLAN))
        assertFalse(localHostApprovalAllowed(AgentMode.RESEARCH))
    }

    @Test
    fun `plan prompt carries the system policy and selects the native plan agent`() {
        val body = openCodePromptBody(
            ModelRequest(
                messages = listOf(
                    ConversationMessage(MessageRole.SYSTEM, "Never edit files in this run."),
                    ConversationMessage(MessageRole.USER, "Inspect the project."),
                ),
                tools = emptyList(),
                maxOutputTokens = 1_024,
            ),
            ProviderConnection(
                preset = ProviderPresets.byId("cli-opencode"),
                baseUrl = "cli://local",
                model = "openai/test-model",
                apiKey = "",
            ),
            AgentMode.PLAN,
        )

        assertEquals("plan", body.get("agent").asString)
        val prompt = body.getAsJsonArray("parts").first().asJsonObject.get("text").asString
        assertTrue(prompt.contains("强制运行模式：PLAN"))
        assertTrue(prompt.contains("Never edit files in this run."))
        assertTrue(prompt.contains("Inspect the project."))
    }
}
