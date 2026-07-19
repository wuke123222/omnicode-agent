package dev.omnicode.provider

import java.util.Locale

/**
 * Coarse, conservative model categories used only to improve catalog presentation.
 *
 * Model-list APIs rarely expose a portable capability schema. Unknown names therefore remain
 * selectable as coding/chat candidates; we only de-emphasize IDs that clearly describe a
 * non-chat workload. This must never be used as an authorization or request validation boundary.
 */
internal enum class ModelCatalogKind(
    val codingChatCandidate: Boolean,
    val displayName: String,
) {
    CHAT_OR_UNKNOWN(true, "对话"),
    EMBEDDING(false, "Embedding"),
    IMAGE(false, "图片"),
    AUDIO(false, "音频"),
    REALTIME(false, "实时"),
    MODERATION(false, "审核/安全"),
    VIDEO(false, "视频"),
    RERANKING(false, "重排序"),
    OCR(false, "OCR"),
}

internal data class ModelCatalogView(
    val models: List<String>,
    val totalCount: Int,
    val codingChatCandidateCount: Int,
    /** Number actually suppressed by the coding/chat filter. The active model is never suppressed. */
    val hiddenNonChatCount: Int,
)

internal fun classifyModelCatalogKind(modelId: String): ModelCatalogKind {
    val normalized = modelId.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return ModelCatalogKind.CHAT_OR_UNKNOWN
    val tokens = normalized.split(MODEL_ID_SEPARATORS).filter(String::isNotBlank)

    fun hasToken(vararg values: String): Boolean = tokens.any { token -> values.any(token::equals) }
    fun hasTokenPrefix(vararg prefixes: String): Boolean =
        tokens.any { token -> prefixes.any(token::startsWith) }
    val describesConversationalCapability = hasToken(
        "assistant",
        "chat",
        "code",
        "coder",
        "language",
        "multimodal",
        "understanding",
        "vision",
        "vlm",
    )
    val describesGeneration = hasToken("gen", "generation", "generator") ||
        hasTokenPrefix("imagegen", "videogen")
    val dallEStyleId = hasToken("dalle") || tokens.zipWithNext().any { (first, second) ->
        first == "dall" && second == "e"
    }

    return when {
        // Realtime is an explicit serving mode. A bare "live" token is too ambiguous to hide.
        hasToken("realtime") -> ModelCatalogKind.REALTIME
        hasToken("embed", "embedding", "embeddings") || hasTokenPrefix("embedding") ->
            ModelCatalogKind.EMBEDDING
        hasToken("imagen") || dallEStyleId ||
            ((hasToken("image", "images") || hasTokenPrefix("imagegen")) &&
                (describesGeneration || !describesConversationalCapability)) -> ModelCatalogKind.IMAGE
        hasToken("tts", "whisper") || hasTokenPrefix("transcrib") ||
            (hasToken("audio", "speech") && !describesConversationalCapability) -> ModelCatalogKind.AUDIO
        hasToken("moderation", "moderator") ||
            (hasToken("guard", "safety") && !describesConversationalCapability) -> ModelCatalogKind.MODERATION
        (hasToken("video", "videos") || hasTokenPrefix("videogen")) &&
            (describesGeneration || !describesConversationalCapability) -> ModelCatalogKind.VIDEO
        hasTokenPrefix("rerank") -> ModelCatalogKind.RERANKING
        hasToken("ocr") -> ModelCatalogKind.OCR
        else -> ModelCatalogKind.CHAT_OR_UNKNOWN
    }
}

/**
 * Produces the list shown by model selectors while preserving a manually configured active ID.
 * Search is deliberately applied after capability filtering so hidden specialized models do not
 * unexpectedly reappear merely because a user starts typing.
 */
internal fun modelCatalogView(
    models: Collection<String>,
    activeModel: String = "",
    query: String = "",
    showAll: Boolean = false,
): ModelCatalogView {
    val normalizedActive = activeModel.trim()
    val normalizedModels = buildList {
        if (normalizedActive.isNotEmpty()) add(normalizedActive)
        addAll(models)
    }.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()

    fun isActive(model: String): Boolean =
        normalizedActive.isNotEmpty() && model.equals(normalizedActive, ignoreCase = true)

    val hiddenNonChat = normalizedModels.count { model ->
        !classifyModelCatalogKind(model).codingChatCandidate && !isActive(model)
    }
    val filtered = normalizedModels.asSequence()
        .filter { model ->
            showAll || classifyModelCatalogKind(model).codingChatCandidate || isActive(model)
        }
        .filter { model -> query.isBlank() || model.contains(query.trim(), ignoreCase = true) }
        .toList()

    return ModelCatalogView(
        models = filtered,
        totalCount = normalizedModels.size,
        codingChatCandidateCount = normalizedModels.count {
            classifyModelCatalogKind(it).codingChatCandidate
        },
        hiddenNonChatCount = if (showAll) 0 else hiddenNonChat,
    )
}

private val MODEL_ID_SEPARATORS = Regex("[^a-z0-9]+")
