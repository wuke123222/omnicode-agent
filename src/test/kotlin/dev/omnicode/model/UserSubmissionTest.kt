package dev.omnicode.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UserSubmissionTest {
    @Test
    fun `submission preserves image transport and scopes markdown as user context`() {
        val submission = UserSubmission(
            prompt = "Review these files",
            attachments = listOf(
                UserAttachment("screen.png", AttachmentKind.IMAGE, "image/png", 3, "AQID"),
                UserAttachment("notes.md", AttachmentKind.MARKDOWN, "text/markdown", 12, "# Notes"),
            ),
        )

        val message = submission.toMessage()
        assertEquals(MessageRole.USER, message.role)
        assertEquals("Review these files", assertIs<ContentBlock.Text>(message.blocks[0]).text)
        assertEquals("screen.png", assertIs<ContentBlock.Image>(message.blocks[1]).fileName)
        assertTrue(assertIs<ContentBlock.Text>(message.blocks[2]).text.contains("notes.md"))
        assertEquals("Review these files".length + "# Notes".length, submission.estimatedCharacterCount)
    }
}
