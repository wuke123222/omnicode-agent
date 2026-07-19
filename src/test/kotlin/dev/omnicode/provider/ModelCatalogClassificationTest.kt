package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogClassificationTest {
    @Test
    fun `clearly specialized model ids are not coding chat candidates`() {
        val expected = mapOf(
            "text-embedding-3-large" to ModelCatalogKind.EMBEDDING,
            "vendor/gpt-image-1" to ModelCatalogKind.IMAGE,
            "dall-e-3" to ModelCatalogKind.IMAGE,
            "dall_e_3" to ModelCatalogKind.IMAGE,
            "vendor-imagegen-v2" to ModelCatalogKind.IMAGE,
            "speech/tts-1" to ModelCatalogKind.AUDIO,
            "gpt-realtime-preview" to ModelCatalogKind.REALTIME,
            "omni-moderation-latest" to ModelCatalogKind.MODERATION,
            "video-generation-1" to ModelCatalogKind.VIDEO,
            "bge-reranker-v2" to ModelCatalogKind.RERANKING,
            "document-ocr-v1" to ModelCatalogKind.OCR,
        )

        expected.forEach { (id, kind) ->
            assertEquals(kind, classifyModelCatalogKind(id), id)
            assertFalse(kind.codingChatCandidate, id)
        }
    }

    @Test
    fun `unknown and multimodal chat names stay visible by default`() {
        listOf(
            "future-model-2030",
            "gpt-4o",
            "claude-vision-latest",
            "gemini-pro-vision",
            "my-live-coder",
            "image-understanding-chat",
            "audio-enabled-chat",
            "speech-language-chat",
            "video-understanding-model",
            "safety-coder",
        ).forEach { id ->
            assertEquals(ModelCatalogKind.CHAT_OR_UNKNOWN, classifyModelCatalogKind(id), id)
        }
    }

    @Test
    fun `default catalog view hides specialized models but preserves active manual model`() {
        val view = modelCatalogView(
            models = listOf(
                "future-coder",
                "text-embedding-3-small",
                "gpt-image-1",
                "CUSTOM-MODEL",
                "custom-model",
            ),
            activeModel = "gpt-image-1",
        )

        assertEquals(listOf("CUSTOM-MODEL", "future-coder", "gpt-image-1"), view.models)
        assertEquals(4, view.totalCount)
        assertEquals(2, view.codingChatCandidateCount)
        assertEquals(1, view.hiddenNonChatCount)
    }

    @Test
    fun `show all and search compose predictably`() {
        val models = listOf("coder-pro", "coder-mini", "text-embedding-3-small")

        val defaultSearch = modelCatalogView(models, query = "embedding")
        assertTrue(defaultSearch.models.isEmpty())
        assertEquals(1, defaultSearch.hiddenNonChatCount)

        val allSearch = modelCatalogView(models, query = "embedding", showAll = true)
        assertEquals(listOf("text-embedding-3-small"), allSearch.models)
        assertEquals(0, allSearch.hiddenNonChatCount)
    }

    @Test
    fun `empty provider catalog still exposes the active manual model`() {
        val configured = modelCatalogView(emptyList(), activeModel = "private-deployment")
        val unconfigured = modelCatalogView(emptyList())

        assertEquals(listOf("private-deployment"), configured.models)
        assertEquals(1, configured.totalCount)
        assertTrue(unconfigured.models.isEmpty())
    }
}
