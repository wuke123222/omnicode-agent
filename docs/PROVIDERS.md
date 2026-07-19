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
