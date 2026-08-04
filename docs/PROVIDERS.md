# Provider development

## 使用自定义兼容服务

在 OmniCode ToolWindow 侧栏的 **API 与模型** 中选择 **Custom OpenAI-compatible**，然后填写：

- Base URL：包含版本前缀，例如 `http://localhost:8000/v1`
- Model：服务端模型 ID
- API Key：可留空（适用于本地服务）

插件会请求 `${baseUrl}/chat/completions`，使用 Bearer 鉴权、SSE 流式输出和 OpenAI Chat tools 格式。

## 新增预设

如果服务完全兼容 OpenAI Chat，只需在 `ProviderPresets` 中增加一项，不要复制新的 Provider 类：

```kotlin
ProviderPreset(
    id = "vendor",
    displayName = "Vendor",
    protocol = ProviderProtocol.OPENAI_CHAT,
    defaultBaseUrl = "https://api.vendor.example/v1",
    defaultModel = "model-id",
)
```

只有 wire protocol 不同才应新增适配器。适配器必须：

1. 把领域消息和工具 schema 映射为供应商请求。
2. 容忍未知流事件，并累积被分片的工具参数。
3. 将文本、工具调用、usage 和 stop reason 规范化。
4. 对非 2xx 和流内错误抛出不包含凭据的 `ProviderException`。
5. 不把 API Key、完整请求或环境变量写入日志。

最后在 `ProviderFactory` 按 `ProviderProtocol` 注册实现，并添加请求映射与流解析测试。

## 协议差异

- OpenAI Responses：工具调用和结果是独立 item；流为命名事件。
- OpenAI Chat/Azure：assistant `tool_calls` + `role=tool`；流为 data-only SSE。
- Anthropic：`tool_use` 在 assistant block，`tool_result` 必须位于下一条 user content。
- Gemini：消息使用 `parts`，工具调用和结果分别是 `functionCall` / `functionResponse`。
- Bedrock Converse：工具使用 `toolUse` / `toolResult` block；Converse 同步响应由 SigV4 签名。

## 推理强度适配

Provider 适配器不得把统一档位名称直接发送给未知服务。先通过 `ProviderReasoningPolicy` 解析模型能力，再使用对应 wire format：

| Provider 路径 | 原生字段 | 备注 |
| --- | --- | --- |
| OpenAI Responses | `reasoning.effort`、可选 `reasoning.mode=pro` | Pro 与 effort 独立，仅在已知支持的模型启用 |
| OpenAI Chat / Azure | `reasoning_effort` | 推理模型使用 `max_completion_tokens` |
| OpenRouter | `reasoning.effort` | 其余 OpenAI-compatible 只有明确支持时才开放档位 |
| Anthropic Messages | `output_config.effort` | 工具续轮必须原样回放 thinking/signature block |
| Gemini 3 | `generationConfig.thinkingConfig.thinkingLevel` | 支持级别依模型而异 |
| Gemini 2.5 | `generationConfig.thinkingConfig.thinkingBudget` | 不与 `thinkingLevel` 同时发送 |
| Bedrock Claude / Nova 2 | `additionalModelRequestFields` | 按模型族生成 adaptive thinking、budget thinking 或 `reasoningConfig` |

`Auto` 总是省略显式推理字段并保留模型默认行为。新增模型族时应补能力映射与请求 JSON 测试。不能确认原生字段时，低/中/高/全速只作为 Agent 执行强度并省略 wire 参数；关闭、最低、超高则 fail closed。推理档位会改变延迟和费用，但不会放宽 Agent 的审批、沙箱、上下文窗口、单次操作超时或无进展保护；全速会开启持续执行，用户仍可随时取消。
