# Architecture

## Boundary

OmniCode 的运行边界是“单项目、单运行、单主智能体”。UI 在发送时分别冻结本次 `Agent` / `Plan` / `Research` 权限模式和 `Single` / `Team` 协作策略；模型只产生文本或结构化工具请求；真实副作用只能由主智能体经本地工具策略、进程沙箱和审批层产生。

```text
Tool Window ── @ 项目文件 / 拖拽附件 → bounded attachment intake
    ├── Research package exporter → redacted bounded Markdown
    ↓
Project Service ── cancellation / session / recovery / usage / history
    ↓
Lead Agent Engine ── context / budgets / stall detection
    ├── Provider Adapter ── HTTP / SSE
    ├── Team delegation ── up to 2 concurrent isolated read-only specialists
    │    └── fresh Agent Engine / fresh provider / PLAN registry / no MCP or delegation
    └── Tool Registry
         ├── read-only tools / Skill library → execute
         ├── exact patch / file replace → validate → preview → approve → revalidate → execute
         ├── commands → approve → revalidate → process sandbox → execute
         └── MCP tools → approve → stdio / Streamable HTTP JSON-RPC → execute

Local Store ── bounded JSONL / redaction / atomic compaction
    ├── usage and estimated cost
    ├── conversation snapshots
    ├── lead-workflow checkpoints
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

累计输入和输出预算可分别提升到 `10,000,000,000` Token；“全速项目预设”还会放宽轮次、工具调用和墙钟时间。这个数值只控制 workflow 共享账本，单轮仍受所选模型上下文窗口、供应商输出上限和 Provider 配置约束。预算是允许上限而非消耗目标，Agent 不会为凑 Token 做无关工作。

AgentEngine 在自身捕获的预算、取消和失败边界，从已有消息和工具结果生成确定性的部分结果，固定区分 Achieved、Evidence、Remaining 和 Risks。该摘要只引用有界的已成功/失败 observation、待执行工具和最新模型文本，不发起额外模型或工具调用，也不把未验证模型文本标成已完成事实。

## Durable lead-workflow recovery

主 AgentEngine 通过 `AgentCheckpointSink` 在运行开始、Provider 请求边界、模型返回工具请求、工具开始以及 observation 完成后提交执行快照。Project Service 把它转换为 `WorkflowCheckpoint` 并原子 upsert 到 JetBrains system path 下的 `omnicode/workflow-checkpoints.jsonl`。记录包含版本、workflow/conversation/agent 标识、轮次、模式与策略、有界消息和 observation、预算快照、待处理工具/审批及有界专家摘要；所有自由文本在落盘前经过与其他本地记录相同的脱敏和截断。附件二进制被排除，图片只会留下有界文本元数据，已提取的文本附件内容则可能进入消息快照。

打开聊天面板时，Project Service 把当前项目仍未终止的记录幂等标记为 `INTERRUPTED`，并展示最新的可恢复任务。只有用户点击“继续任务”才会恢复；“放弃检查点”只删除该条本地记录。继续会复用 workflow ID、会话创建时间、模式和 Single/Team 策略，把有界文本快照转换回消息，再添加一条恢复指令要求核对当前工作区。这是从安全文本检查点开始的新 Agent 执行，不恢复原协程、网络连接、Provider 的不透明延续状态、附件二进制或正在运行的专家。

checkpoint 中存在 `pendingTool` 表示该调用在中断时未得到可重放的完成证明，执行结果可能未知。恢复逻辑不把该调用重新注入为待执行动作，也不会自动沿用旧审批；恢复指令要求先读取或验证现状，模型若提出新的副作用，仍须重新经过模式过滤、哈希/路径校验、沙箱和审批。普通运行 checkpoint 为 best effort，写入失败会显示状态信息；但危险工具获批后必须在审批 gate 返回前成功保存 `executionStarted`，否则工具被阻止执行。即便如此，checkpoint 仍不能把文件、命令或远程服务变成事务。

用户可见错误由稳定分类器映射为认证、权限、限流、网络超时、网络、模型能力、预算、沙箱、配置、取消或未知错误，再提供配置 Provider、切换模型、调整预算、打开沙箱或编辑重试等入口。分类文本不复制可能包含凭据、Prompt 或本地路径的原始 Provider body。该机制只支持本机 lead workflow 的显式恢复；没有无人值守后台执行、跨设备同步或完整多智能体任务板。

## Reasoning controls

推理强度与 Agent / Plan 看板 / Claude Plan / Research 权限模式正交。UI 在每次运行开始前冻结当前 Provider 配置；`ReasoningEffort` 经 `ProviderReasoningPolicy` 做模型能力判定。已验证的模型映射为协议原生字段；兼容服务的未知模型对低/中/高/全速采用 `OMIT` wire format，只调整本地 Agent 执行约束、单轮输出余量和请求超时，不发送可能导致 400 的猜测字段。关闭、最低、超高等无法安全模拟的组合会在 UI 隐藏，并在网络请求前再次校验。这仍不绕过工具审批、沙箱、费用或 workflow 硬预算。

- OpenAI Responses 使用 `reasoning.effort`；支持的 GPT-5.6 全速路径还使用独立的 Pro 模式。OpenAI Chat/Azure 使用 `reasoning_effort`，OpenRouter 使用其 `reasoning` 对象。
- Anthropic Messages 使用 `output_config.effort`，并在工具续轮保留供应商返回的 thinking/signature block。
- Gemini 3 使用 `thinkingLevel`；Gemini 2.5 使用有界 `thinkingBudget`，二者不会同时发送。usage 统计包含思考 Token。
- Bedrock 按模型族写入 `additionalModelRequestFields`：Claude adaptive/budget thinking 或 Nova 2 `maxReasoningEffort`；无法确认能力的模型仅开放 `Auto`。

视觉辅助和 Commit AI 固定使用 `Auto`，避免主模型的全速设置意外放大 OCR/摘要等辅助调用。

一次模型轮次只执行一个原子工具调用。若供应商返回并行工具请求，首个进入执行，其余收到 `BATCH_NOT_SUPPORTED` 观察，让模型在下一轮重新规划。

## Team orchestration

- `Team` 与权限模式正交：Agent + Team 的主智能体可在审批后产生副作用；Plan 看板 / Claude Plan + Team 全程只读；Research + Team 仍只有主智能体能运行经过审批的实验命令。
- 只有主智能体拥有 `delegate_specialists`。每轮委派 1–2 个独立任务，最多 2 轮、4 个专家、并行度 2；专家角色限定为 Explorer、Planner、Reviewer，不能递归委派。
- 每个专家使用新的 Provider 实例、空历史和新的 `AgentEngine`，仅收到有界原始目标、自己的 objective 与角色约束。主智能体历史、兄弟专家上下文和其他专家的输出不会注入。
- 专家固定以 `PLAN` 运行，只注册内置工具与 Skills；Registry 在 schema 暴露和执行查找两层都只允许 `READ_ONLY`。不会连接 MCP，也不能写文件、运行命令或发起副作用审批。
- 专家输出作为有界、不可信证据返回主智能体；主智能体必须核验关键事实并独自生成最终答案。UI 不混入专家流式文本，只显示开始、完成、状态、摘要和 Token 的有界事件。
- 主智能体、视觉辅助模型和所有专家共享 workflow Token / 费用账本。输入、输出、总 Token 与费用分别执行硬限制；并发请求先预留预算、成功后按实际 usage 提交、失败或取消时释放。最终用量以确定性 run ID 聚合写入一次，工具审计按 workflow ID 与 agent ID 隔离；供应商缺少 usage 时会同时估算文本和结构化工具调用块。
- 取消 Project Service 的活动 Job 会通过结构化并发取消所有专家。部分专家失败不会丢弃成功结果；全部失败会把委派工具标记为失败，由主智能体决定降级或停止。

## Agent / Plan Board / Claude Plan / Research routing

- `Agent` 使用完整 ReAct 工具面；文件写入、命令和 MCP 调用仍经过各自审批与沙箱。
- `Plan 看板` 只允许显式标记为 `READ_ONLY` 的工具，并要求输出可解析的 Markdown checklist。
- `Claude Plan` 使用独立模式值，可调用 `READ_ONLY` 工具和内置 `run_command`；后者必须先通过纯 argv 只读策略，再强制使用无网络、工作区只读的 macOS sandbox-exec / Linux bubblewrap。未知、复合、可写或可扩展执行的命令失败关闭，文件修改与 MCP 在 schema 和执行查找两层仍不可用。
- `/plan <任务>` 是单轮 Claude Plan 覆盖，不污染常驻模式；`Shift+Tab` 在 Agent 与 Claude Plan 间切换。计划完成后，用户可继续规划、编辑当前修订、选择手动逐步确认，或批准后切换 Agent 连续执行。
- `Research` 只允许 `READ_ONLY` 与 `COMMAND`。它可以在逐次审批后运行受超时、输出边界、环境清理和所选进程沙箱约束的实验命令，但不能获得 `MUTATING` 或 `EXTERNAL` 工具。
- 未显式分类的新工具默认是 `EXTERNAL`。Registry 按模式过滤模型可见 schema，执行前再按相同策略查找工具；即使模型伪造调用，Plan/Claude Plan 与 Research 也返回稳定的模式阻断结果且不会触发审批。
- Project Service 只为 `Agent` 连接或启动 MCP Server；Plan 看板、Claude Plan 与 Research 在连接层即跳过 MCP，而不是只隐藏 schema。只读 Skill 工具仍可按模式加载。
- Research 的 SYSTEM 约束要求按研究问题、假设、方法、证据、结果、局限、复现清单和引用组织结论，只引用实际检查过的来源，明确区分观察、推断和未知信息，并禁止编造论文、作者、DOI、URL、测量值或实验结果。
- 每次运行都会移除旧 SYSTEM 消息并注入当前模式约束，因此同一对话可在四种模式之间切换而不会继承旧模式权限。运行模式随会话 checkpoint、用量记录和工具审计持久化，旧记录仍允许空模式迁移。

## Context

- Write：完整变更写入项目和 IDE Local History；工具结果保留在会话。
- Select：保留系统约束、最初用户目标、当前运行中最后一个非纯工具结果的用户请求，以及最新消息；当前目标中的验收条件和约束因此不会被尾部 ToolResult 挤出。选择可选历史时，单个组超限只跳过该组，仍会继续尝试其他可容纳组。
- Compress：达到字符预算后丢弃中段，并插入确定性的省略说明。
- Isolate：每个 JetBrains Project 拥有独立 Service 和协程生命周期；Team 专家拥有独立消息历史与身份。

项目规则与固定文件以 `TransientProjectContext` 专用 block 放在当前用户请求之前。它们按 `maxContextChars`、剩余累计输入预算、首个目标、当前目标和固定系统余量动态裁剪；Provider adapter 把该 block 序列化为普通请求文本，但持久化、checkpoint 与研究包路径显式丢弃。`.gitignore`、`.aiignore`、`.omnicodeignore`、显式排除和敏感路径由同一个 fail-closed policy 约束规则、Pinned Context、PSI/index 与通用文件工具。

`PlanBoardService` 在 project workspace state 中保存当前计划、修订号、审阅决定、执行策略和步骤状态。步骤文本、勾选、跳过或恢复都会推进修订并使旧审阅决定失效；只有绑定当前修订的批准才能执行。手动策略每次仅启动一个步骤并停下，连续策略才在成功后推进；重规划仍只在明确的同一 board ID 下保留已完成/已跳过步骤。

`TaskChangeReviewService` 以 workflow ID 在当前 IDE 会话内记录 `apply_patch` / `apply_change` 的 first-before/latest-after 与稳定 hunk ID。回退前复核路径、符号链接和当前哈希；整任务已记录修改先进行双重全量预检。该账本不宣称覆盖命令、MCP 或用户并发编辑，且暂不跨 IDE 重启持久化。

项目文件被视为不可信输入，其中的文本不能覆盖系统策略。

聊天附件按类型、大小、图片头和像素数做本地校验。图片以降采样方式生成有界本地缩略图，可由具备视觉能力的主模型直接接收，或在用户批准后交给配置的视觉辅助模型转写；Markdown、文本、日志、结构化数据、LaTeX/BibTeX、R/Julia/MATLAB 和常见源码以有界 UTF-8 文本块进入上下文，预览不超过 6000 字符/80 行。拖拽、文件选择、剪贴板和 `@` 文件引用共用同一校验路径。

`@` 引用在当前项目下执行有扫描数量上限的文件名/相对路径匹配，只返回 Attachment Intake 支持的普通文件，并跳过 `.git`、IDE/Gradle 元数据、依赖、虚拟环境和构建输出目录。选择结果不是给予模型任意文件访问权，而是作为普通附件再次执行扩展名、大小、UTF-8、控制字符和敏感文件规则。

PDF 通过 Apache PDFBox 3.0.8 在本地、内存型缓存中解析，先验证 `%PDF-` 签名，再限制为 10 MB、300 页和 48,000 个提取字符；输出带页标记。加密、损坏、超限或无可读文本的 PDF 会被拒绝，纯扫描文档不会自动 OCR 或把原始 PDF 发送给视觉辅助模型。PDFBox 的 Apache License 2.0 来源与声明记录在 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)，依赖 JAR 保留上游许可元数据。

Jupyter Notebook 使用严格 UTF-8 JSON 流式解析，限制为 2 MB、200 个 cell、单 cell 12,000 字符和总计 48,000 字符。只提取 Markdown 与代码 cell 的 `source`；outputs、富媒体附件和 metadata 通过流式跳过而不物化为完整 JSON 树。NUL、控制字符异常、畸形或结构过深的 Notebook 会被拒绝。

## Creative Workshop boundary

创意工坊与聊天、设置并列为 Tool Window 顶层目的地。目录由编译期 `WorkshopTheme` 和 `WorkshopPet` 数据组成，只允许经过格式与长度校验的 ID、颜色、普通显示文本、宿主渲染枚举和有界空闲提示；不存在脚本、命令、类名、反射、URL 或远程下载入口。持久化设置只保存已选主题 ID、桌宠 ID 和启用开关，加载时必须重新对照受信任目录解析；未知桌宠 ID 会回退并自动禁用。

`CustomPetAvatarStore` 是唯一的本地素材入口。它把用户选择的图片视为不可信输入：拒绝符号链接、伪图片、GIF/SVG、超 8 MB、宽高超 2048 或像素超 419 万的内容；先在后台通过 ImageIO 读取真实格式和尺寸，再渲染进新的 ARGB 缓冲区、最长边缩至 512 像素，并以临时文件原子发布为 PNG。源路径、EXIF、附加数据和原始字节均不持久化，目标固定在 IDE config 下的 OmniCode workshop 目录，不进入项目、Settings Sync、模型上下文或 MCP。导入失败保留上一份有效立绘，删除只移除规范化副本。

主题只改变 OmniCode Tool Window 的工作台表面和导航，不修改 JetBrains 全局 Look and Feel。桌宠和虚拟偶像是前台 UI 状态投影：它们只消费已有的运行、结构化工具和终态回调，不获得 Agent 工具、不触发模型调用，也不会使任务脱离 Project Service 生命周期在后台继续。图片加载后只作为宿主绘制数据；组件隐藏或释放时停止 Swing Timer。

## Research evidence and export

Research 模式采用受限 ReAct：每轮仍只执行一个原子工具，所有通用轮次、工具、Token、费用、时间、重复动作和连续失败边界继续生效。直接观察来自用户附件、只读项目工具或已执行命令的结构化结果；模型推断必须在最终报告中单独标记。`workspace-write` 命令默认不能访问网络或工作区外用户数据；显式选择 `danger-full-access` 会移除进程的 OS 级文件/网络隔离，但不会放开 Research 的文件修改、MCP 或 `EXTERNAL` 工具分类。

`ReproducibleResearchPackageExporter` 是纯转换层：从显式会话快照生成格式版本 1 的 Markdown，本身不读取环境、项目文件或 PasswordSafe。UI 在后台把所有已配置供应商/MCP 凭据暂时收集为只用于本次 `DefaultSensitiveDataRedactor` 的内存字典；值不会写入研究包。SYSTEM 消息在统计、图片扫描、研究问题和脱敏前完全剔除。导出包含 UTC 时间、项目、明确标为“导出时配置”的模式/供应商/模型、首个用户研究问题、选取后的会话、按调用 ID 配对的工具/命令证据、复现清单、限制和引用核对清单。自由文本在进入正则脱敏前先受单块 256,000 字符与总计 2,000,000 字符预算，再按 section/消息/字段和总字节预算截断；默认总上限 512 KiB，硬上限 2 MiB。图片只保留已脱敏的文件名、媒体类型和字节数，data URL、JSON 字段和常见裸 PNG/JPEG base64 均被省略。

`ResearchPackageMarkdownWriter` 只接受 `.md` 目标，拒绝符号链接父目录或目标文件，并在同目录创建权限收紧、已 fsync 的临时文件。`CREATE_NEW` 通过同目录原子硬链接发布并保证任何后来出现的目标都不被覆盖；`REPLACE_MATCHING` 必须携带用户确认时捕获的 NOFOLLOW fileKey/大小/mtime，提交前完整复核后才执行原子替换，身份变化即拒绝。导出包是便于人工复现与审计的证据清单，不是事实证明：脱敏无法替代分享前审查，模型/供应商非确定性、完整宿主环境、凭据和图片内容也不会被封装。

## Side effects

`apply_patch` 与 `apply_change` 都要求 `read_file` 返回的 SHA-256，审批前后各校验一次，并在 `WriteCommandAction` 内写入。精确 Patch 的每段旧上下文必须唯一匹配，歧义或过期内容会 fail closed；这两个工具仅 Agent 可见，直接以 Plan/Research 调用也会拒绝。`run_command` 使用 `GeneralCommandLine(List<String>)`，不拼接 shell 字符串，工作目录必须在项目内，仅 Agent/Research 可见且逐次审批。

默认 `workspace-write` 会先选择平台后端并执行真实能力探测。macOS 使用 `sandbox-exec` profile；Linux 使用 `bubblewrap` 的 mount/user/network namespace：宿主根只读、用户目录以私有 tmpfs 隐藏、工作区重新读写挂载、HOME/tmp 为进程私有目录，网络 namespace 仅保留 loopback 视图。探测会验证工作区内读写、工作区外秘密不可读、宿主外部路径不可写以及网络隔离，任一失败都 fail closed。Windows 宿主不会声称具备未实现的 AppContainer 能力；即使探测到 WSL2+bubblewrap，在无法证明 Windows 路径桥接前仍拒绝启动，并引导通过 JetBrains WSL/Remote Development 在 Linux 后端运行。`danger-full-access` 是显式用户设置；它移除 OS 级隔离，但不移除审批、argv 直执行、环境清理、超时和输出边界。

## Extension boundary

MCP Server 仅在 Agent 模式可通过已配置的 stdio 进程或 2025-11-25 Streamable HTTP 接入。stdio 初始化、工具发现和调用使用有界 JSON-RPC 行协议；进程启动前解析真实可执行文件和沙箱计划，并按服务器、项目、参数、工作目录、沙箱、环境变量名、可执行文件内容和后端身份生成指纹。HTTP 使用 JSON/SSE、有界响应、Session/Protocol headers、404 会话重建和关闭 DELETE；远程强制 HTTPS、禁止重定向，并明确绕过代理访问 loopback。OAuth 层解析 401/403 Bearer challenge，按 RFC 9728 发现受保护资源，再按 RFC 8414/OIDC 发现授权服务器；强制 PKCE S256、state 和 resource audience，支持公开客户端/动态注册、过期刷新及 401 单次刷新重试。OAuth 会话以规范化 Endpoint、认证模式、配置 Client ID 和排序 Scope 生成绑定指纹；跨 manager 的登录/刷新按 Server ID 单飞，logout 与永久 token 错误通过 generation 使所有在途结果失效。Bearer、OAuth Token 和客户端密钥均只从 PasswordSafe 读取。两种连接首次或指纹变化后都重新审批，每个 MCP tool 均标记为 dangerous 并逐次审批。Skill 来源只在用户配置的目录中发现 `SKILL.md`，由 `list_skills` / `load_skill` 按需加载，不会自动注入完整技能库。

用量、会话、workflow checkpoint 和工具审计写入 JetBrains system path，而非项目目录；自由文本在持久化前脱敏并截断，文件和记录数均有硬上限。checkpoint 默认最多 200 条、文件最多 256 MiB；恢复卡中的“放弃检查点”可删除对应记录，但不会回滚已发生的副作用。API Key 只进入 PasswordSafe，并按供应商与规范 Origin 绑定；远程地址必须使用 HTTPS，认证请求不自动跟随重定向。MCP 环境密钥同样只进入 PasswordSafe。

## Provider boundary

领域层使用 `Text`、`Image`、`ToolCall`、`ToolResult` 内容块，不假设所有服务都有 `role=tool`。每个协议适配器负责把它们映射成 Responses items、Anthropic content blocks、Gemini parts、OpenAI Chat messages 或 Bedrock Converse blocks。

Provider 传输层禁止携带凭据跨 Origin 重定向，并把可安全显示的请求 ID、网络失败状态和有界 `Retry-After` 传给 Agent 控制层。审批解析事件在危险工具执行前必须持久化成功；该审计写入失败时执行 fail closed。
