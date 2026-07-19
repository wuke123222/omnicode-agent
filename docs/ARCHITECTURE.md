# Architecture

## Boundary

OmniCode 的运行边界是“单项目、单运行、单智能体”。UI 在发送时冻结本次 `Agent` / `Plan` / `Research` 模式，只提交命令与显示事件；模型只产生文本或结构化工具请求；真实副作用只能由本地工具策略、进程沙箱和审批层产生。

```text
Tool Window ── @ 项目文件 / 拖拽附件 → bounded attachment intake
    ├── Research package exporter → redacted bounded Markdown
    ↓
Project Service ── cancellation / session / usage / history
    ↓
Agent Engine ── context / budgets / stall detection
    ├── Provider Adapter ── HTTP / SSE
    └── Tool Registry
         ├── read-only tools / Skill library → execute
         ├── exact patch / file replace → validate → preview → approve → revalidate → execute
         ├── commands → approve → revalidate → process sandbox → execute
         └── MCP tools → approve → stdio / Streamable HTTP JSON-RPC → execute

Local Store ── bounded JSONL / redaction / atomic compaction
    ├── usage and estimated cost
    ├── conversation snapshots
    └── tool execution audit
```

## ReAct controls

默认限制：

- 24 个模型轮次
- 32 个工具调用
- 连续 3 次工具失败即停止
- 相同工具参数重复超过 2 次即停止
- 单次运行 10 分钟
- 输入 250k、输出 32k Token（供应商不返回 usage 时本地估算）
- 单个 observation 注入 Prompt 时最多 24k 字符

这些边界可从 OmniCode 侧边栏“运行控制”调整。Provider 的 429、5xx 与网络故障遵循有界 `Retry-After` / 指数退避；一旦已收到流式输出便不自动重放。若为当前模型配置了价格，可同时设置单次运行美元硬上限和预警比例。

一次模型轮次只执行一个原子工具调用。若供应商返回并行工具请求，首个进入执行，其余收到 `BATCH_NOT_SUPPORTED` 观察，让模型在下一轮重新规划。

## Agent / Plan / Research routing

- `Agent` 使用完整 ReAct 工具面；文件写入、命令和 MCP 调用仍经过各自审批与沙箱。
- `Plan` 只允许显式标记为 `READ_ONLY` 的工具。未知或第三方工具默认归为外部副作用，因而不会进入 Plan 工具面。
- `Research` 只允许 `READ_ONLY` 与 `COMMAND`。它可以在逐次审批后运行受超时、输出边界、环境清理和所选进程沙箱约束的实验命令，但不能获得 `MUTATING` 或 `EXTERNAL` 工具。
- 未显式分类的新工具默认是 `EXTERNAL`。Registry 按模式过滤模型可见 schema，执行前再按相同策略查找工具；即使模型伪造调用，Plan 返回 `PLAN_MODE_BLOCKED`，Research 返回 `RESEARCH_MODE_BLOCKED`，且不会触发审批。
- Project Service 只为 `Agent` 连接或启动 MCP Server；Plan 与 Research 在连接层即跳过 MCP，而不是只隐藏 schema。只读 Skill 工具仍可在三种模式按需加载。
- Research 的 SYSTEM 约束要求按研究问题、假设、方法、证据、结果、局限、复现清单和引用组织结论，只引用实际检查过的来源，明确区分观察、推断和未知信息，并禁止编造论文、作者、DOI、URL、测量值或实验结果。
- 每次运行都会移除旧 SYSTEM 消息并注入当前模式约束，因此同一对话可在三种模式之间切换而不会继承旧模式权限。运行模式随会话 checkpoint、用量记录和工具审计持久化，旧记录仍允许空模式迁移。

## Context

- Write：完整变更写入项目和 IDE Local History；工具结果保留在会话。
- Select：保留系统约束、最初目标与最近消息。
- Compress：达到字符预算后丢弃中段，并插入确定性的省略说明。
- Isolate：每个 JetBrains Project 拥有独立 Service 和协程生命周期。

项目文件被视为不可信输入，其中的文本不能覆盖系统策略。

聊天附件按类型、大小、图片头和像素数做本地校验。图片以降采样方式生成有界本地缩略图，可由具备视觉能力的主模型直接接收，或在用户批准后交给配置的视觉辅助模型转写；Markdown、文本、日志、结构化数据、LaTeX/BibTeX、R/Julia/MATLAB 和常见源码以有界 UTF-8 文本块进入上下文，预览不超过 6000 字符/80 行。拖拽、文件选择、剪贴板和 `@` 文件引用共用同一校验路径。

`@` 引用在当前项目下执行有扫描数量上限的文件名/相对路径匹配，只返回 Attachment Intake 支持的普通文件，并跳过 `.git`、IDE/Gradle 元数据、依赖、虚拟环境和构建输出目录。选择结果不是给予模型任意文件访问权，而是作为普通附件再次执行扩展名、大小、UTF-8、控制字符和敏感文件规则。

PDF 通过 Apache PDFBox 3.0.8 在本地、内存型缓存中解析，先验证 `%PDF-` 签名，再限制为 10 MB、300 页和 48,000 个提取字符；输出带页标记。加密、损坏、超限或无可读文本的 PDF 会被拒绝，纯扫描文档不会自动 OCR 或把原始 PDF 发送给视觉辅助模型。PDFBox 的 Apache License 2.0 来源与声明记录在 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)，依赖 JAR 保留上游许可元数据。

Jupyter Notebook 使用严格 UTF-8 JSON 流式解析，限制为 2 MB、200 个 cell、单 cell 12,000 字符和总计 48,000 字符。只提取 Markdown 与代码 cell 的 `source`；outputs、富媒体附件和 metadata 通过流式跳过而不物化为完整 JSON 树。NUL、控制字符异常、畸形或结构过深的 Notebook 会被拒绝。

## Research evidence and export

Research 模式采用受限 ReAct：每轮仍只执行一个原子工具，所有通用轮次、工具、Token、费用、时间、重复动作和连续失败边界继续生效。直接观察来自用户附件、只读项目工具或已执行命令的结构化结果；模型推断必须在最终报告中单独标记。`workspace-write` 命令默认不能访问网络或工作区外用户数据；显式选择 `danger-full-access` 会移除进程的 OS 级文件/网络隔离，但不会放开 Research 的文件修改、MCP 或 `EXTERNAL` 工具分类。

`ReproducibleResearchPackageExporter` 是纯转换层：从显式会话快照生成格式版本 1 的 Markdown，本身不读取环境、项目文件或 PasswordSafe。UI 在后台把所有已配置供应商/MCP 凭据暂时收集为只用于本次 `DefaultSensitiveDataRedactor` 的内存字典；值不会写入研究包。SYSTEM 消息在统计、图片扫描、研究问题和脱敏前完全剔除。导出包含 UTC 时间、项目、明确标为“导出时配置”的模式/供应商/模型、首个用户研究问题、选取后的会话、按调用 ID 配对的工具/命令证据、复现清单、限制和引用核对清单。自由文本在进入正则脱敏前先受单块 256,000 字符与总计 2,000,000 字符预算，再按 section/消息/字段和总字节预算截断；默认总上限 512 KiB，硬上限 2 MiB。图片只保留已脱敏的文件名、媒体类型和字节数，data URL、JSON 字段和常见裸 PNG/JPEG base64 均被省略。

`ResearchPackageMarkdownWriter` 只接受 `.md` 目标，拒绝符号链接父目录或目标文件，并在同目录创建权限收紧、已 fsync 的临时文件。`CREATE_NEW` 通过同目录原子硬链接发布并保证任何后来出现的目标都不被覆盖；`REPLACE_MATCHING` 必须携带用户确认时捕获的 NOFOLLOW fileKey/大小/mtime，提交前完整复核后才执行原子替换，身份变化即拒绝。导出包是便于人工复现与审计的证据清单，不是事实证明：脱敏无法替代分享前审查，模型/供应商非确定性、完整宿主环境、凭据和图片内容也不会被封装。

## Side effects

`apply_patch` 与 `apply_change` 都要求 `read_file` 返回的 SHA-256，审批前后各校验一次，并在 `WriteCommandAction` 内写入。精确 Patch 的每段旧上下文必须唯一匹配，歧义或过期内容会 fail closed；这两个工具仅 Agent 可见，直接以 Plan/Research 调用也会拒绝。`run_command` 使用 `GeneralCommandLine(List<String>)`，不拼接 shell 字符串，工作目录必须在项目内，仅 Agent/Research 可见且逐次审批。

默认 `workspace-write` 会先选择平台后端并执行真实能力探测。macOS 使用 `sandbox-exec` profile；Linux 使用 `bubblewrap` 的 mount/user/network namespace：宿主根只读、用户目录以私有 tmpfs 隐藏、工作区重新读写挂载、HOME/tmp 为进程私有目录，网络 namespace 仅保留 loopback 视图。探测会验证工作区内读写、工作区外秘密不可读、宿主外部路径不可写以及网络隔离，任一失败都 fail closed。Windows 宿主不会声称具备未实现的 AppContainer 能力；即使探测到 WSL2+bubblewrap，在无法证明 Windows 路径桥接前仍拒绝启动，并引导通过 JetBrains WSL/Remote Development 在 Linux 后端运行。`danger-full-access` 是显式用户设置；它移除 OS 级隔离，但不移除审批、argv 直执行、环境清理、超时和输出边界。

## Extension boundary

MCP Server 仅在 Agent 模式可通过已配置的 stdio 进程或 2025-11-25 Streamable HTTP 接入。stdio 初始化、工具发现和调用使用有界 JSON-RPC 行协议；进程启动前解析真实可执行文件和沙箱计划，并按服务器、项目、参数、工作目录、沙箱、环境变量名、可执行文件内容和后端身份生成指纹。HTTP 使用 JSON/SSE、有界响应、Session/Protocol headers、404 会话重建和关闭 DELETE；远程强制 HTTPS、禁止重定向，并明确绕过代理访问 loopback。OAuth 层解析 401/403 Bearer challenge，按 RFC 9728 发现受保护资源，再按 RFC 8414/OIDC 发现授权服务器；强制 PKCE S256、state 和 resource audience，支持公开客户端/动态注册、过期刷新及 401 单次刷新重试。OAuth 会话以规范化 Endpoint、认证模式、配置 Client ID 和排序 Scope 生成绑定指纹；跨 manager 的登录/刷新按 Server ID 单飞，logout 与永久 token 错误通过 generation 使所有在途结果失效。Bearer、OAuth Token 和客户端密钥均只从 PasswordSafe 读取。两种连接首次或指纹变化后都重新审批，每个 MCP tool 均标记为 dangerous 并逐次审批。Skill 来源只在用户配置的目录中发现 `SKILL.md`，由 `list_skills` / `load_skill` 按需加载，不会自动注入完整技能库。

用量、会话和工具审计写入 JetBrains system path，而非项目目录；自由文本在持久化前脱敏并截断，文件和记录数均有硬上限。API Key 只进入 PasswordSafe，并按供应商与规范 Origin 绑定；远程地址必须使用 HTTPS，认证请求不自动跟随重定向。MCP 环境密钥同样只进入 PasswordSafe。

## Provider boundary

领域层使用 `Text`、`Image`、`ToolCall`、`ToolResult` 内容块，不假设所有服务都有 `role=tool`。每个协议适配器负责把它们映射成 Responses items、Anthropic content blocks、Gemini parts、OpenAI Chat messages 或 Bedrock Converse blocks。

Provider 传输层禁止携带凭据跨 Origin 重定向，并把可安全显示的请求 ID、网络失败状态和有界 `Retry-After` 传给 Agent 控制层。审批解析事件在危险工具执行前必须持久化成功；该审计写入失败时执行 fail closed。
