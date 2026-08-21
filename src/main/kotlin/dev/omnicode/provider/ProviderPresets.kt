package dev.omnicode.provider

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("openai", "OpenAI", ProviderProtocol.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6-sol"),
        ProviderPreset("opencode", "OpenCode Zen", ProviderProtocol.OPENCODE_ZEN, "https://opencode.ai/zen/v1", "big-pickle"),
        ProviderPreset("anthropic", "Anthropic", ProviderProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1", "claude-sonnet-4-5"),
        ProviderPreset("gemini", "Google Gemini", ProviderProtocol.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-pro"),
        ProviderPreset("azure", "Azure OpenAI", ProviderProtocol.AZURE_OPENAI, "https://YOUR-RESOURCE.openai.azure.com", "YOUR-DEPLOYMENT"),
        ProviderPreset("bedrock", "AWS Bedrock", ProviderProtocol.BEDROCK_CONVERSE, "https://bedrock-runtime.{region}.amazonaws.com", "anthropic.claude-sonnet-4-20250514-v1:0", true),
        ProviderPreset("deepseek", "DeepSeek", ProviderProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1", "deepseek-chat"),
        ProviderPreset("groq", "Groq", ProviderProtocol.OPENAI_CHAT, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        ProviderPreset("xai", "xAI", ProviderProtocol.OPENAI_CHAT, "https://api.x.ai/v1", "grok-4"),
        ProviderPreset("mistral", "Mistral AI", ProviderProtocol.OPENAI_CHAT, "https://api.mistral.ai/v1", "mistral-large-latest"),
        ProviderPreset("openrouter", "OpenRouter", ProviderProtocol.OPENAI_CHAT, "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4"),
        ProviderPreset("together", "Together AI", ProviderProtocol.OPENAI_CHAT, "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        ProviderPreset("cerebras", "Cerebras", ProviderProtocol.OPENAI_CHAT, "https://api.cerebras.ai/v1", "llama-3.3-70b"),
        ProviderPreset("qwen", "Alibaba DashScope / Qwen", ProviderProtocol.OPENAI_CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-coder-plus"),
        ProviderPreset("moonshot", "Moonshot / Kimi", ProviderProtocol.OPENAI_CHAT, "https://api.moonshot.cn/v1", "kimi-k2-0711-preview"),
        ProviderPreset("siliconflow", "SiliconFlow", ProviderProtocol.OPENAI_CHAT, "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        ProviderPreset("zhipu", "Zhipu AI / GLM", ProviderProtocol.OPENAI_CHAT, "https://open.bigmodel.cn/api/paas/v4", "glm-5.2"),
        ProviderPreset("qianfan", "Baidu Qianfan / ERNIE", ProviderProtocol.OPENAI_CHAT, "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-20260402"),
        ProviderPreset("hunyuan", "Tencent Hunyuan", ProviderProtocol.OPENAI_CHAT, "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest"),
        ProviderPreset("volcengine", "Volcengine Ark / Doubao", ProviderProtocol.OPENAI_RESPONSES, "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-0-lite-260215"),
        ProviderPreset("nvidia", "NVIDIA NIM", ProviderProtocol.OPENAI_CHAT, "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"),
        ProviderPreset("fireworks", "Fireworks AI", ProviderProtocol.OPENAI_CHAT, "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct"),
        ProviderPreset("ollama", "Ollama (Local)", ProviderProtocol.OPENAI_CHAT, "http://localhost:11434/v1", "qwen3-coder", true),
        ProviderPreset("lmstudio", "LM Studio (Local)", ProviderProtocol.OPENAI_CHAT, "http://localhost:1234/v1", "local-model", true),
        ProviderPreset("custom", "Custom OpenAI-compatible", ProviderProtocol.OPENAI_CHAT, "http://localhost:8000/v1", "model", true),
        ProviderPreset("cli-opencode", "OpenCode CLI", ProviderProtocol.CLI_OPENCODE, "cli://local", "default", apiKeyOptional = true),
        ProviderPreset("cli-kimi", "Kimi CLI", ProviderProtocol.CLI_KIMI, "cli://local", "kimi-k2", apiKeyOptional = true),
        ProviderPreset("cli-grok", "Grok Build CLI", ProviderProtocol.CLI_GROK, "cli://local", "grok-build-0.1", apiKeyOptional = true),
        ProviderPreset("cli-pi", "Pi CLI", ProviderProtocol.CLI_PI, "cli://local", "default", apiKeyOptional = true),
        ProviderPreset("cli-qoder", "Qoder CLI", ProviderProtocol.CLI_QODER, "cli://local", "default", apiKeyOptional = true),
    )

    /**
     * Codex is intentionally not a user-selectable provider. It is an execution backend for
     * Team specialists, so the lead conversation keeps using the provider the user configured.
     * The legacy id remains readable to avoid breaking persisted settings from 1.6.0.
     */
    internal val legacyCodexNative: ProviderPreset = ProviderPreset(
        "codex-native",
        "Codex 原生子智能体（本机 App Server）",
        ProviderProtocol.CODEX_APP_SERVER,
        "codex://local",
        "codex-default",
        apiKeyOptional = true,
    )

    internal val codexNativeSubagent: ProviderPreset = legacyCodexNative.copy(
        id = "codex-native-subagent",
        displayName = "Codex 原生子智能体",
    )

    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id }
        ?: when (id) {
            legacyCodexNative.id -> legacyCodexNative
            codexNativeSubagent.id -> codexNativeSubagent
            else -> all.first()
        }
}
