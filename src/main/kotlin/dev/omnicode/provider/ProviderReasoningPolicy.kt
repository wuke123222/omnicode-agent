package dev.omnicode.provider

internal enum class ReasoningWireFormat {
    OMIT,
    CODEX_APP_SERVER,
    OPENAI_RESPONSES,
    OPENAI_CHAT,
    OPENROUTER,
    ANTHROPIC,
    GEMINI_LEVEL,
    GEMINI_BUDGET,
    BEDROCK_CLAUDE_ADAPTIVE,
    BEDROCK_CLAUDE_EFFORT,
    BEDROCK_CLAUDE_BUDGET,
    BEDROCK_NOVA,
    UNSUPPORTED,
}

internal data class ReasoningResolution(
    val requested: ReasoningEffort,
    val effective: ReasoningEffort,
    val wireFormat: ReasoningWireFormat,
    val wireValue: String? = null,
    val thinkingBudget: Int? = null,
    val openAiProMode: Boolean = false,
    val explanation: String,
) {
    val supported: Boolean get() = wireFormat != ReasoningWireFormat.UNSUPPORTED
}

internal fun ProviderConnection.reasoningResolution(): ReasoningResolution = resolveReasoningEffort(
    providerId = preset.id,
    protocol = preset.protocol,
    model = model,
    requested = reasoningEffort,
)

internal fun ProviderConnection.requireReasoningResolution(): ReasoningResolution =
    reasoningResolution().also { resolution ->
        if (!resolution.supported) {
            throw ProviderException(
                "${preset.displayName} model '$model' does not expose the selected " +
                    "${reasoningEffort.persistedValue} reasoning level. Choose Auto or a supported level.",
            )
        }
    }

internal fun resolveReasoningEffort(
    providerId: String,
    protocol: ProviderProtocol,
    model: String,
    requested: ReasoningEffort,
): ReasoningResolution {
    if (requested == ReasoningEffort.AUTO) {
        return ReasoningResolution(
            requested = requested,
            effective = requested,
            wireFormat = ReasoningWireFormat.OMIT,
            explanation = "使用模型默认推理强度",
        )
    }
    if (protocol == ProviderProtocol.OPENCODE_ZEN) {
        return when (openCodeZenAdapter(model)) {
            OpenCodeZenAdapter.OPENAI_RESPONSES -> resolveOpenAi(model, requested, responses = true)
            OpenCodeZenAdapter.OPENAI_CHAT -> resolveAgentOnly(requested, "OpenCode Zen 的该模型")
            OpenCodeZenAdapter.ANTHROPIC_MESSAGES -> resolveAnthropic(model, requested)
            OpenCodeZenAdapter.GEMINI -> resolveGemini(model, requested)
        }
    }
    return when (protocol) {
        ProviderProtocol.CODEX_APP_SERVER -> resolveCodexNative(requested)
        ProviderProtocol.OPENAI_RESPONSES -> if (providerId == "openai" || openAiEffortCapability(model) != null) {
            resolveOpenAi(model, requested, responses = true)
        } else {
            resolveAgentOnly(requested, "${providerId.ifBlank { "OpenAI-compatible" }} Responses")
        }
        ProviderProtocol.OPENAI_CHAT,
        ProviderProtocol.AZURE_OPENAI,
        -> when {
            providerId == "openrouter" -> resolveOpenRouter(model, requested)
            openAiEffortCapability(model) != null ->
                resolveOpenAiCompatible(model, requested, ReasoningWireFormat.OPENAI_CHAT)
            else -> resolveAgentOnly(requested, "${providerId.ifBlank { "OpenAI-compatible" }}")
        }
        ProviderProtocol.ANTHROPIC_MESSAGES -> resolveAnthropic(model, requested)
        ProviderProtocol.GEMINI -> resolveGemini(model, requested)
        ProviderProtocol.BEDROCK_CONVERSE -> resolveBedrock(model, requested)
        ProviderProtocol.OPENCODE_ZEN -> error("handled above")
        ProviderProtocol.CLI_OPENCODE,
        ProviderProtocol.CLI_CLAUDE,
        ProviderProtocol.CLI_CODEX,
        ProviderProtocol.CLI_KIMI,
        ProviderProtocol.CLI_GROK,
        ProviderProtocol.CLI_PI,
        ProviderProtocol.CLI_OMP,
        ProviderProtocol.CLI_DSH,
        ProviderProtocol.CLI_QODER,
        -> resolveCliTool(requested)
    }
}

private fun resolveCodexNative(requested: ReasoningEffort): ReasoningResolution = ReasoningResolution(
    requested = requested,
    effective = requested,
    wireFormat = ReasoningWireFormat.CODEX_APP_SERVER,
    wireValue = when (requested) {
        ReasoningEffort.AUTO -> null
        ReasoningEffort.MAX -> "ultra"
        else -> requested.persistedValue
    },
    explanation = when (requested) {
        ReasoningEffort.AUTO -> "使用 Codex 原生配置的默认推理强度"
        ReasoningEffort.MAX -> "Codex 原生 Ultra 推理"
        else -> "Codex 原生 ${requested.persistedValue} 推理"
    },
)

private fun resolveCliTool(requested: ReasoningEffort): ReasoningResolution = ReasoningResolution(
    requested = requested,
    effective = ReasoningEffort.AUTO,
    wireFormat = ReasoningWireFormat.OMIT,
    wireValue = null,
    explanation = "CLI 工具自行管理推理强度",
)

internal fun reasoningEffortOptions(
    providerId: String,
    protocol: ProviderProtocol,
    model: String,
): List<ReasoningEffort> = ReasoningEffort.entries.filter { effort ->
    effort == ReasoningEffort.AUTO || resolveReasoningEffort(providerId, protocol, model, effort).supported
}

internal fun ReasoningEffort.recommendedOutputTokenFloor(): Int = when (this) {
    ReasoningEffort.AUTO,
    ReasoningEffort.NONE,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    -> 8_192
    ReasoningEffort.MEDIUM -> 16_384
    ReasoningEffort.HIGH -> 32_768
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
    -> 65_536
}

internal fun ProviderConnection.recommendedOutputTokenFloor(resolution: ReasoningResolution): Int = when {
    reasoningEffort == ReasoningEffort.MAX && model.lowercase().contains("gpt-5.6") -> 131_072
    resolution.wireFormat == ReasoningWireFormat.BEDROCK_NOVA &&
        resolution.effective == ReasoningEffort.HIGH -> 131_072
    else -> reasoningEffort.recommendedOutputTokenFloor()
}

private fun resolveOpenAi(model: String, requested: ReasoningEffort, responses: Boolean): ReasoningResolution {
    val capability = openAiEffortCapability(model)
        ?: return resolveAgentOnly(requested, "该 OpenAI 模型")
    val effective = if (requested == ReasoningEffort.MAX) capability.fullSpeed else requested
    if (effective !in capability.levels) return unsupported(requested, "该 OpenAI 模型不支持此档位")
    return ReasoningResolution(
        requested = requested,
        effective = effective,
        wireFormat = if (responses) ReasoningWireFormat.OPENAI_RESPONSES else ReasoningWireFormat.OPENAI_CHAT,
        wireValue = effective.persistedValue,
        openAiProMode = requested == ReasoningEffort.MAX && capability.proMode && responses,
        explanation = if (requested == ReasoningEffort.MAX && effective != requested) {
            "全速映射为该模型最高档 ${effective.persistedValue}"
        } else if (requested == ReasoningEffort.MAX && capability.proMode) {
            "GPT-5.6 max + pro 质量优先模式"
        } else {
            "OpenAI ${effective.persistedValue}"
        },
    )
}

private fun resolveOpenRouter(model: String, requested: ReasoningEffort): ReasoningResolution {
    val normalized = model.substringAfter('/').lowercase()
    val native = when {
        openAiEffortCapability(normalized) != null -> resolveOpenAi(normalized, requested, responses = false)
        anthropicEffortCapability(normalized) != null -> resolveAnthropic(normalized, requested)
        else -> return resolveAgentOnly(requested, "OpenRouter 的该模型")
    }
    if (!native.supported || native.wireFormat == ReasoningWireFormat.OMIT) return native
    return native.copy(
        wireFormat = ReasoningWireFormat.OPENROUTER,
        openAiProMode = false,
        explanation = "OpenRouter reasoning.effort=${native.wireValue}",
    )
}

private fun resolveOpenAiCompatible(
    model: String,
    requested: ReasoningEffort,
    format: ReasoningWireFormat,
): ReasoningResolution {
    val exact = resolveOpenAi(model, requested, responses = false)
    if (!exact.supported) return exact
    return exact.copy(
        wireFormat = format,
        openAiProMode = false,
        explanation = if (format == ReasoningWireFormat.OPENROUTER) {
            "OpenRouter reasoning.effort=${exact.wireValue}"
        } else {
            "OpenAI-compatible reasoning_effort=${exact.wireValue}"
        },
    )
}

private data class EffortCapability(
    val levels: Set<ReasoningEffort>,
    val fullSpeed: ReasoningEffort,
    val proMode: Boolean = false,
)

private val BASIC_REASONING_LEVELS = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

private fun openAiEffortCapability(model: String): EffortCapability? {
    val normalized = model.substringAfter('/').lowercase()
    return when {
        "gpt-5.6" in normalized -> EffortCapability(
            BASIC_REASONING_LEVELS + setOf(ReasoningEffort.NONE, ReasoningEffort.XHIGH, ReasoningEffort.MAX),
            ReasoningEffort.MAX,
            proMode = true,
        )
        normalized.contains("gpt-5-pro") || normalized.contains("o1-pro") ->
            EffortCapability(setOf(ReasoningEffort.HIGH), ReasoningEffort.HIGH)
        Regex("gpt-5\\.[45]").containsMatchIn(normalized) -> EffortCapability(
            BASIC_REASONING_LEVELS + setOf(ReasoningEffort.NONE, ReasoningEffort.XHIGH),
            ReasoningEffort.XHIGH,
        )
        normalized.contains("gpt-5.3") || normalized.contains("codex-max") -> EffortCapability(
            BASIC_REASONING_LEVELS + ReasoningEffort.XHIGH,
            ReasoningEffort.XHIGH,
        )
        normalized.contains("gpt-5.2") || normalized.contains("gpt-5.1") -> EffortCapability(
            BASIC_REASONING_LEVELS + ReasoningEffort.NONE,
            ReasoningEffort.HIGH,
        )
        normalized == "gpt-5" || normalized.startsWith("gpt-5-") || normalized.contains("gpt-5-codex") ->
            EffortCapability(BASIC_REASONING_LEVELS + ReasoningEffort.MINIMAL, ReasoningEffort.HIGH)
        normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4") ||
            normalized.startsWith("codex-mini") || normalized.contains("gpt-oss") ->
            EffortCapability(BASIC_REASONING_LEVELS, ReasoningEffort.HIGH)
        else -> null
    }
}

private fun resolveAnthropic(model: String, requested: ReasoningEffort): ReasoningResolution {
    if (requested == ReasoningEffort.NONE || requested == ReasoningEffort.MINIMAL) {
        return unsupported(requested, "Anthropic effort 不支持 none/minimal")
    }
    val capability = anthropicEffortCapability(model)
        ?: return resolveAgentOnly(requested, "该 Claude 模型")
    val effective = when (requested) {
        ReasoningEffort.MAX -> capability.fullSpeed
        else -> requested
    }
    if (effective !in capability.levels) return unsupported(requested, "该 Claude 模型不支持此档位")
    return ReasoningResolution(
        requested = requested,
        effective = effective,
        wireFormat = ReasoningWireFormat.ANTHROPIC,
        wireValue = effective.persistedValue,
        explanation = if (requested != effective) {
            "全速映射为该 Claude 模型最高档 ${effective.persistedValue}"
        } else {
            "Anthropic output_config.effort=${effective.persistedValue}"
        },
    )
}

private fun anthropicEffortCapability(model: String): EffortCapability? {
    val normalized = model.substringAfter('/').lowercase().replace('.', '-').replace('_', '-')
    val supportsXHigh = listOf("fable-5", "mythos-5", "opus-4-8", "opus-4-7", "sonnet-5")
        .any(normalized::contains)
    val supportsMax = supportsXHigh || listOf(
        "mythos-preview",
        "opus-4-6",
        "sonnet-4-6",
    ).any(normalized::contains)
    val supportsBase = supportsMax || normalized.contains("opus-4-5")
    if (!supportsBase) return null
    val levels = BASIC_REASONING_LEVELS.toMutableSet()
    if (supportsXHigh) levels += ReasoningEffort.XHIGH
    if (supportsMax) levels += ReasoningEffort.MAX
    return EffortCapability(
        levels = levels,
        fullSpeed = if (supportsMax) ReasoningEffort.MAX else ReasoningEffort.HIGH,
    )
}

private fun resolveGemini(model: String, requested: ReasoningEffort): ReasoningResolution {
    val normalized = model.lowercase().removePrefix("models/")
    val effective = when (requested) {
        ReasoningEffort.NONE -> ReasoningEffort.NONE
        ReasoningEffort.MINIMAL -> ReasoningEffort.MINIMAL
        ReasoningEffort.LOW -> ReasoningEffort.LOW
        ReasoningEffort.MEDIUM -> ReasoningEffort.MEDIUM
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
        -> ReasoningEffort.HIGH
        ReasoningEffort.AUTO -> error("handled above")
    }
    if (normalized.contains("gemini-2.5")) {
        val pro = normalized.contains("pro")
        val budget = when (effective) {
            ReasoningEffort.NONE -> if (pro) return unsupported(requested, "Gemini 2.5 Pro 不能关闭思考") else 0
            ReasoningEffort.MINIMAL,
            ReasoningEffort.LOW,
            -> 1_024
            ReasoningEffort.MEDIUM -> 8_192
            ReasoningEffort.HIGH -> if (pro && requested == ReasoningEffort.MAX) 32_768 else 24_576
            else -> error("unexpected Gemini budget effort")
        }
        return ReasoningResolution(
            requested = requested,
            effective = effective,
            wireFormat = ReasoningWireFormat.GEMINI_BUDGET,
            thinkingBudget = budget,
            explanation = "Gemini 2.5 thinkingBudget=$budget",
        )
    }
    val supportedLevels = gemini3Levels(normalized)
        ?: return resolveAgentOnly(requested, "该 Gemini 模型")
    if (effective == ReasoningEffort.NONE) return unsupported(requested, "Gemini 3 不支持 none 档位")
    if (requested == ReasoningEffort.XHIGH) return unsupported(requested, "Gemini 没有 xhigh 档位")
    val level = if (requested == ReasoningEffort.MAX) "high" else effective.persistedValue
    if (level !in supportedLevels) return unsupported(requested, "该 Gemini 型号不支持 $level thinkingLevel")
    return ReasoningResolution(
        requested = requested,
        effective = ReasoningEffort.entries.first { it.persistedValue == level },
        wireFormat = ReasoningWireFormat.GEMINI_LEVEL,
        wireValue = level,
        explanation = "Gemini thinkingLevel=$level",
    )
}

private fun gemini3Levels(model: String): Set<String>? = when {
    model.contains("gemini-3.1-flash-lite-image") -> setOf("minimal", "high")
    model.contains("gemini-3.1-flash-lite") -> setOf("minimal", "low", "medium", "high")
    model.contains("gemini-3.1-pro") -> setOf("low", "medium", "high")
    model.contains("gemini-3.5-flash") || model.contains("gemini-3-flash") ->
        setOf("minimal", "low", "medium", "high")
    else -> null
}

private fun resolveBedrock(model: String, requested: ReasoningEffort): ReasoningResolution {
    val normalized = model.lowercase()
    if (normalized.contains("nova-2-lite")) {
        if (requested in setOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.XHIGH)) {
            return unsupported(requested, "Nova 2 仅支持 low/medium/high")
        }
        val effective = if (requested == ReasoningEffort.MAX) ReasoningEffort.HIGH else requested
        return ReasoningResolution(
            requested = requested,
            effective = effective,
            wireFormat = ReasoningWireFormat.BEDROCK_NOVA,
            wireValue = effective.persistedValue,
            explanation = "Bedrock Nova 2 maxReasoningEffort=${effective.persistedValue}",
        )
    }
    if (!normalized.contains("anthropic.claude")) {
        return resolveAgentOnly(requested, "该 Bedrock 模型")
    }
    if (requested == ReasoningEffort.NONE || requested == ReasoningEffort.MINIMAL) {
        return unsupported(requested, "该 Bedrock Claude 路径不支持此档位")
    }
    val nativeEffort = anthropicEffortCapability(normalized)
    if (nativeEffort != null) {
        val resolved = resolveAnthropic(normalized, requested)
        if (!resolved.supported) return resolved
        val explicitAdaptive = listOf("opus-4-6", "opus-4-7", "opus-4-8", "sonnet-4-6")
            .any(normalized.replace('.', '-')::contains)
        return resolved.copy(
            wireFormat = if (explicitAdaptive) {
                ReasoningWireFormat.BEDROCK_CLAUDE_ADAPTIVE
            } else {
                ReasoningWireFormat.BEDROCK_CLAUDE_EFFORT
            },
            explanation = "Bedrock Claude effort=${resolved.wireValue}",
        )
    }
    val supportsManualThinking = listOf("claude-3-7-sonnet", "claude-sonnet-4", "claude-opus-4")
        .any(normalized.replace('.', '-')::contains)
    if (!supportsManualThinking) return resolveAgentOnly(requested, "该 Bedrock Claude 模型")
    if (requested == ReasoningEffort.XHIGH) {
        return unsupported(requested, "该 Bedrock Claude 手动 thinking 路径不支持 xhigh")
    }
    val effective = when (requested) {
        ReasoningEffort.MAX -> ReasoningEffort.HIGH
        else -> requested
    }
    val budget = when (effective) {
        ReasoningEffort.LOW -> 1_024
        ReasoningEffort.MEDIUM -> 4_096
        ReasoningEffort.HIGH,
        ReasoningEffort.MAX,
        -> 8_192
        else -> error("unexpected Bedrock Claude effort")
    }
    return ReasoningResolution(
        requested = requested,
        effective = effective,
        wireFormat = ReasoningWireFormat.BEDROCK_CLAUDE_BUDGET,
        thinkingBudget = budget,
        explanation = "Bedrock Claude thinking budget=$budget",
    )
}

private fun resolveAgentOnly(requested: ReasoningEffort, provider: String): ReasoningResolution {
    if (requested in setOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.XHIGH)) {
        return unsupported(requested, "$provider 没有可验证的原生档位")
    }
    val effective = if (requested == ReasoningEffort.MAX) ReasoningEffort.HIGH else requested
    return ReasoningResolution(
        requested = requested,
        effective = effective,
        wireFormat = ReasoningWireFormat.OMIT,
        explanation = "$provider 未暴露可验证的原生推理字段；仅应用 Agent 执行强度、输出余量和超时",
    )
}

private fun unsupported(requested: ReasoningEffort, explanation: String): ReasoningResolution = ReasoningResolution(
    requested = requested,
    effective = ReasoningEffort.AUTO,
    wireFormat = ReasoningWireFormat.UNSUPPORTED,
    explanation = explanation,
)
