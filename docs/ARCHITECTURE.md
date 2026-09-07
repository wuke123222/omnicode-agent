# Architecture

## Boundary

OmniCode 的运行边界是“单项目、单运行、单主智能体”。3.0 Tool Window 只提供聊天、历史记录和设置三个视图；任务、计划、子代理、变更与错误诊断都作为当前对话内的结构化卡片呈现。UI 在发送时冻结本次权限模式和 `Single` / `Team` 协作策略；模型只产生文本或结构化工具请求；真实副作用只能由主智能体经本地工具策略、进程沙箱和审批层产生。

### Local CLI turn lifecycle

OpenCode follows the direct-stream contract used by the CCGUI reference adapter: each turn starts
`opencode run --format json` in the opened project directory and consumes one bounded JSONL stream.
The IDE does not start a second `opencode serve` process or wait for a health/SSE handshake before
sending the prompt. Model discovery is an explicit settings action, never a hidden per-turn
preflight. This keeps the first model request independent of local server startup and lets
cancellation terminate the exact child process tree.

For a project-scoped Codex turn, the provider uses Codex's native `app-server --stdio` JSON-RPC
session (the same session-oriented boundary used by CCGUI) instead of starting a second text-mode
`codex exec` process. Settings and diagnostics without a project/approval context keep the bounded
JSONL fallback so they remain lightweight and cannot create a hidden workspace session.

```text
Tool Window (JCEF + React) ── Chat / History / Settings
    ├── @ 项目文件 / 拖拽附件 → bounded attachment intake
    ├── Tasks / Plan / Subagents / Edits → inline conversation cards
    ├── ChatEventEnvelopeV1 → live stream and history normalization
    └── Swing host → approval / file chooser / editor navigation / JCEF fallback
    ↓
Project Service ── cancellation / session / recovery / usage / history
    ↓
Agent Harness ── preflight / effective tool surface / recovery degradation / run-surface digest
    ↓
Lead Agent Engine ── context / budgets / stall detection
    ├── Provider Adapter ── HTTP / SSE
    ├── Team delegation ── up to 4 concurrent isolated read-only specialists
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
    └── workflow reliability ledger (stages / requests / retries / failures / recovery points)

Free distribution (never receives project/model context)
    JetBrains Marketplace listing → plugin download → all features available
    Legacy license credential key → deleted directly by the v3 migration
```

WebView 桥接中的每条命令都携带 schema、宿主页代次、前端页面实例 ID 和 request ID。只有当前页面实例通过 `frontend.ready` 握手后才能调用白名单命令；页面重载前的迟到点击会失败关闭。宿主对同步接收成功返回 `command.accepted`，对校验或执行异常返回不包含请求 payload 的 `command.error`，前端在 8 秒未收到任一回执时显示可恢复错误，避免设置、MCP 或新会话按钮静默失效。页面仍使用 `default-src 'none'` CSP，宿主消息和待发送队列均有大小及数量上限。前端时间线 reducer 在进入 React state 前验证完整路由信封，并以 `sessionId + turnId + sequence` 维护游标；重复、乱序、终态后的迟到事件被拒绝，持久化快照只能补齐缺失块，不能覆盖序号更高的实时块。

Composer 的模型菜单、模型收藏、引用草稿和权限选择都是本地交互状态：模型收藏按供应商保存在 WebView 本地偏好中，不进入模型上下文；引用只会在用户发送后成为普通可见文本。所有 Composer 菜单在打开和工具窗口缩放时重新计算固定视口位置，向上展开并同时约束四边，避免 JCEF 原生下拉层在窄侧栏或底部停靠时被裁切。权限变更通过白名单桥接写入平台设置，`run_command` 在每次准备命令前重新读取当前沙箱模式，因此只影响尚未开始的命令；已启动进程不会被静默提升权限。`Claude Plan` 无论界面选择如何仍固定使用 `workspace-write` 和只读命令验证。切换到 `danger-full-access` 必须经过原生确认对话框，且不会绕过工具审批、argv 校验、审计或检查点。

任务终态可投递 JetBrains 原生通知，用户可在常规设置中持久关闭。通知正文只使用经过敏感数据脱敏和字符数限制的结果首行，不包含 Prompt、工具参数、命令输出、附件内容或凭据；WebView Toast 与系统通知是两条独立通道，关闭系统通知不影响任务状态落盘和对话内结果。

运行所有权按 conversation ID 隔离：同一会话一次只允许一个 turn，但新会话不会被其他会话的后台任务阻塞。Project Service 分别保存每个活动会话的 Job、run ID、回调、初始消息、模式和策略；取消、强制释放、终态持久化和运行状态通知都只作用于目标会话。历史列表把仍活动的会话标为“运行中”，允许直接切回；WebView 即使当前展示另一会话也继续按 session ID 归并后台事件，切回时复用实时块树，且后台终态不会清除当前会话的运行状态。完成后的消息始终写入原会话，即使它当时不在前台。输入草稿同样按 conversation ID 隔离，但只存在当前 WebView 内存中：页面/IDE 重启后不会持久化附件二进制，用户需重新选择附件。会话标题只保存最多 100 个字符，不含模型上下文或凭据，可在顶栏修改并与同项目历史记录同步。

已完成的对话回合保留一个有界的 `RecoverableSubmission` 快照（任务文本、模式、协作策略和附件引用），仅用于用户主动点击“重试”或“编辑重试”。快照不包含 API 凭据、完整仓库或二进制内容；重试仍重新进入发送、模型能力检查、审批、沙箱和检查点路径。输入框已有草稿时只恢复到编辑态，不会静默覆盖用户内容。

## Free distribution and legacy entitlement migration

3.0 保留的功能全部免费，`plugin.xml` 不声明 Marketplace 产品，也没有试用、购买、续费或许可证门槛。旧商业校验实现不进入 3.0 产物；`uiSchemaVersion=4` 的幂等迁移保留供应商、CLI、MCP、数据库连接元数据、Skills、提示词、权限和用量配置，同时只按固定 PasswordSafe service/key 删除旧许可证值，并由一个不解释内容的 migration-only 持久化组件清空旧实验室状态。迁移不删除用户项目中的源码或已导出文件。数据库凭据与 API/MCP 凭据一样只保留在 PasswordSafe，不进入迁移状态。恢复历史会话时只读取同一项目指纹下的记录；跨项目记录即使 ID 被获知也会拒绝恢复。聊天消息和有界、脱敏的 workflow 可靠性账本共同重建时间线，供应商原始事件、隐藏推理、凭据与原始命令输出不会进入 WebView。

真实桌面 UI 流程在独立 Remote Robot 作业中打开仓库内无执行配置的固定 fixture 项目，通过 Tool Window 按钮打开 OmniCode，再依次点击历史、新对话和设置的原生标题动作并保存整屏证据。React 层另用固定数据和 Chromium 覆盖 CLI 原生历史列出/导入桥接、输入菜单与固定截图，以及 320/480/800 宽度、深浅主题和 100%/125%/150% 缩放；普通单元测试保持无桌面依赖。Remote Robot 或截图差异都会阻止拉取请求通过，而不是以“能连接 Robot Server”代替真实交互。

## ReAct controls

默认限制：

- 24 个模型轮次
- 32 个工具调用
- 连续 3 次工具失败即停止
- 相同工具参数重复超过 2 次即停止
- 单次运行 10 分钟
- 单个 observation 注入 Prompt 时最多 24k 字符

这些运行保护可从设置的“权限与运行”分组调整。Provider 的 429、5xx 与网络故障遵循有界 `Retry-After` / 指数退避；一旦已收到流式输出便不自动重放。

workflow 累计输入、输出 Token 与估算费用只记录用量，不设置本地任务硬上限。共享账本继续负责并发预留、幂等提交、恢复基线和按 Agent 聚合；单轮仍受所选模型上下文窗口、供应商输出上限、账户额度和 Provider 配置约束。“全速项目预设”只放宽轮次、工具调用和墙钟时间，不改变这一策略。

AgentEngine 在自身捕获的预算、取消和失败边界，从已有消息和工具结果生成确定性的部分结果，固定区分 Achieved、Evidence、Remaining 和 Risks。该摘要只引用有界的已成功/失败 observation、待执行工具和最新模型文本，不发起额外模型或工具调用，也不把未验证模型文本标成已完成事实。枚举型 `list_files` observation 在该终态摘要中只保留路径、返回数量与截断状态；嵌套的专家委派保留结果数量、状态分布和少量带行号的可核验文件引用，避免把机器边界报告和大段目录清单再次回显给用户。未综合的模型目录复述也会被替换为明确的省略说明。原始有界 observation 仍保留在执行消息中供主智能体核验。

## Durable lead-workflow recovery

主 AgentEngine 通过 `AgentCheckpointSink` 在运行开始、Provider 请求边界、模型返回工具请求、工具开始以及 observation 完成后提交执行快照。Project Service 把它转换为 `WorkflowCheckpoint` 并原子 upsert 到 JetBrains system path 下的 `omnicode/workflow-checkpoints.jsonl`。记录包含版本、workflow/conversation/agent 标识、轮次、模式与策略、有界消息和 observation、预算快照、待处理工具/审批及有界专家摘要；所有自由文本在落盘前经过与其他本地记录相同的脱敏和截断。附件二进制被排除，图片只会留下有界文本元数据，已提取的文本附件内容则可能进入消息快照。

打开聊天面板时，Project Service 把当前项目仍未终止的记录幂等标记为 `INTERRUPTED`，并展示最新的可恢复任务。旧版本写入的 `BUDGET_EXHAUSTED` 也按可恢复暂停处理，因此升级后可从已经保存的阶段证据继续，而不是重跑整个目标。只有用户点击“继续任务”才会恢复；“放弃检查点”只删除该条本地记录。继续会复用 workflow ID、会话创建时间、模式和 Single/Team 策略，把有界文本快照转换回消息，再添加一条恢复指令要求核对当前工作区。这是从安全文本检查点开始的新 Agent 执行，不恢复原协程、网络连接、Provider 的不透明延续状态、附件二进制或正在运行的专家。

checkpoint 中存在 `pendingTool` 表示该调用在中断时未得到可重放的完成证明，执行结果可能未知。恢复逻辑不把该调用重新注入为待执行动作，也不会自动沿用旧审批；恢复指令要求先读取或验证现状，模型若提出新的副作用，仍须重新经过模式过滤、哈希/路径校验、沙箱和审批。普通运行 checkpoint 为 best effort，写入失败会显示状态信息；但危险工具获批后必须在审批 gate 返回前成功保存 `executionStarted`，否则工具被阻止执行。即便如此，checkpoint 仍不能把文件、命令或远程服务变成事务。

高频 workflow checkpoint 使用追加式 latest-wins 记录，每次追加仍执行 `fsync`；读取按 workflow ID 解析最新有效记录，达到有界版本数时原子压缩为每个 workflow 一条，下一次追加会越过文件字节上限时则先原子发布最新集合。恢复快照只保留有工具交换语义的 96 KiB / 160 条消息切片，完整对话仍只在内存和历史记录中保留；当最新替换明显变小时会立即压缩旧版本，避免大仓库任务把每个 Provider 边界变成数百 KiB 的重复序列化。内存只保留最多 8 条且合计不超过 8 MiB 的热记录；包含“危险工具已开始但结果未知”的恢复点受保护，容量不足时写入失败关闭而不会静默淘汰该证据。存储文件拒绝符号链接。这样避免在每个 Provider/工具边界全量重写整个 checkpoint 文件，同时保留崩溃恢复、旧时间戳拒绝、跨进程文件锁、损坏尾行清理和危险副作用前同步落盘语义。

每个 workflow 同时写入独立的有界可靠性 ledger：阶段开始/完成、模型请求、供应商重试、工具失败、状态和恢复点都带 workflow/run/agent ID、迭代、尝试次数、耗时与脱敏详情。状态/诊断事件通过有界单写入队列异步落盘，避免每个流式事件都在模型协程上执行 `fsync`；队列满时可丢弃状态提示，但失败、重试和阶段记录仍走独立 IO 兜底。任务中心按 workflow 聚合这条 ledger，直接显示最近仍未完成的阶段、阶段累计耗时、模型请求数、工具失败数、重试数和最近事件，即使聊天面板已关闭也能定位“卡在哪里”；失败或中断任务保留 checkpoint，继续动作复用已有证据并从恢复指令开始，不重复已确认完成的整段任务。

用户可见错误由稳定分类器映射为认证、权限、限流、网络超时、网络、模型能力、有限模式边界、沙箱、配置、取消或未知错误，并在当前对话中提供配置 Provider、切换模型、诊断、取消和编辑重试等动作。网络超时/连接失败不会自动重发未知请求。用户显式停止会协作取消模型、工具和 MCP；若底层连接未及时收尾，Project Service 释放当前 run、保留恢复点并隔离迟到事件，使新会话立即可用。本地 CLI 使用可取消的 stdout 轮询；文本事件立即流式显示，原始 stderr、提示词与工具参数不暴露给 UI。静默进程不再被固定首输出截止时间误杀，而是显示有界的初始化、项目快照、上游排队或生成阶段，并受供应商总请求时限与用户取消控制；超时不会自动重放，避免重复费用。所有 CLI 都从规范化项目根目录启动，发现、版本探测和运行共用受控 PATH，并解析 Node/npm、uv/uvx 及 Windows launcher；找不到必需解释器时显示对应操作系统的修复动作而不是把 launcher 误报为可运行。上下文窗口溢出会引导精简上下文；可恢复的流中断只续接已展示的有界文本尾部，不重放未知副作用。JCEF 只渲染统一事件信封，流式文本批量合并并保持与工具/状态事件顺序；长会话使用有界列表和虚拟滚动，不在 EDT 高频重建组件。分类文本不复制可能包含凭据、Prompt 或本地路径的原始 Provider body。

OpenCode 使用用户原生配置、认证、模型缓存和会话数据库，但运行路径与 CCGUI 保持一致：每个 turn 直接启动 `opencode run --format json`，显式模型通过 `--model` 传递，恢复时只追加经过格式校验的 opaque `--session` ID。插件不会在 Prompt 前启动第二个 `opencode serve`、health probe 或 SSE 连接，因此本地 Host 初始化不会占用首字延迟。stdout 由独立阻塞读取线程写入有界、非阻塞 Channel；JSONL 只合并当前进程观察到的会话 ID、文本、usage、工具/权限提示和终态，完整响应、stderr、Prompt 与凭据不进入 UI 或持久化。`session.status=idle`、step-finish、message_end 等协议终态只在有可见输出或终态宽限后收口，避免尾部文本丢失；进程正常退出也会刷新残余行。静默期间显示可取消的初始化、上游排队或模型处理心跳，并由供应商总请求时限兜底，不再使用 15/30/45 秒硬初始化截止。用户取消、超时、输出溢出和协议失败都会关闭 stdout/stderr 并回收完整进程树；未知请求不会自动重放，以避免重复费用。Plan、Claude Plan 与 Research 的 SYSTEM 策略随当前 turn 注入，本地 CLI 自身提出权限或交互问题时 fail closed，不能绕过 OmniCode 的工具审批。

DSH 使用独立的回环 Host RPC 适配器，而不是一次性 stdout 适配器。只接受 `http://127.0.0.1`、`localhost` 或 `::1`，固定 argv 启动 `dsh web` 前必须经过用户审批；随后通过 `workspace.create` 将会话绑定到规范化项目根目录，创建或恢复 opaque session，按 `provider/model` 选择模型，并在 `session.prompt` 之前完成 `/api/events.mux` 订阅。所有帧严格按 session ID 过滤，以 turn/goal 终态收口；取消会调用 `session.cancel`，传输故障保留 session。Host 发出的工具授权请求逐次进入 OmniCode `ApprovalGate`，未提供审批上下文时一律拒绝；当前无法表达的交互式问题返回空答案并显示明确状态，避免 Host 永久等待。RPC、事件、模型目录、错误文本和可见输出均有独立大小上限，远程 DSH Host 不受支持。

本地 CLI 的 npm 启动器在 macOS/Linux 上会直接调用自动解析出的 Node 解释器，避免 Finder、Toolbox 或 Windows 对应启动入口与用户终端具有不同 PATH 时出现 `env: node`。CLI 已在规范化项目根目录运行，因此只传递有界的当前 SYSTEM 运行策略以及至多 8 条、12,000 字符的可见对话；Harness 清单和 OmniCode 工具记录不会再次传入并拖慢首个输出。SYSTEM 策略每轮重新生成并包含冻结后的 Agent/Plan/Claude Plan/Research 模式，避免本地引擎只收到用户文本而丢失安全边界。用户保存的 CLI Key 只会写入所选模型供应商的环境变量名（例如 Pi 的 `openai/...` 使用 `OPENAI_API_KEY`），退出时只按有界 stderr 分类登录、模型权限、地区和运行时问题，不持久化或展示原始 stderr。

依赖页的版本探测只证明可执行文件及其解释器能够启动，不把它等同于登录完成或模型授权。选择已安装引擎只激活它原有的独立 profile，再触发该引擎支持的模型发现；不会把用户此前选择的模型、推理等级或凭据覆盖成 `default`。认证和模型权限由模型发现或首次真实请求明确分类，UI 会分别显示“版本检测通过”和后续验证结果。

项目服务创建后会在后台预热一次有界、只读的规则、Harness 与固定文件上下文快照；这不会执行 Harness argv，也不会读取凭据。连续对话会在 15 秒内复用一次快照，并按本轮上下文预算截断；TTL 到期后重新读取项目数据。首轮只等待最多 1.2 秒的软预算，且自动上下文按推理档位限制在 24–96 KiB；冷仓库扫描超时就先发送模型请求，预热结果留在后台供后续轮次复用。MCP 初次连接按推理档位只等待 1.5–5 秒，超时不会阻塞模型请求；预热失败不会阻断聊天。新任务的首个恢复点使用“仅首次写入”并行落盘，若运行时检查点先到达则保留运行时快照；恢复任务仍先同步重建安全基线。

任务中心支持将脱敏 checkpoint 以 PBKDF2 + AES-GCM 加密为 `.omnitask`，跨设备导入生成新的 workflow/run ID 并强制进入 `INTERRUPTED`，避免覆盖本地任务或自动执行未知副作用。可选云端客户端只向用户自建 HTTPS relay 上传密文；插件不提供托管账号、无人值守后台或默认云同步。

## Reasoning controls

推理强度与 Agent / Plan 看板 / Claude Plan / Research 权限模式正交。UI 在每次运行开始前冻结当前 Provider 配置；`ReasoningEffort` 经 `ProviderReasoningPolicy` 做模型能力判定。已验证的模型映射为协议原生字段；兼容服务的未知模型对低/中/高/全速采用 `OMIT` wire format，只调整本地 Agent 执行约束、单轮输出余量和请求超时，不发送可能导致 400 的猜测字段。关闭、最低、超高等无法安全模拟的组合会在 UI 隐藏，并在网络请求前再次校验。这仍不绕过工具审批、沙箱、上下文窗口、供应商额度、单次操作超时或无进展保护。

- OpenAI Responses 使用 `reasoning.effort`；支持的 GPT-5.6 全速路径还使用独立的 Pro 模式。OpenAI Chat/Azure 使用 `reasoning_effort`，OpenRouter 使用其 `reasoning` 对象。
- Anthropic Messages 使用 `output_config.effort`，并在工具续轮保留供应商返回的 thinking/signature block。
- Gemini 3 使用 `thinkingLevel`；Gemini 2.5 使用有界 `thinkingBudget`，二者不会同时发送。usage 统计包含思考 Token。
- Bedrock 按模型族写入 `additionalModelRequestFields`：Claude adaptive/budget thinking 或 Nova 2 `maxReasoningEffort`；无法确认能力的模型仅开放 `Auto`。

视觉辅助和 Commit AI 固定使用 `Auto`，避免主模型的全速设置意外放大 OCR/摘要等辅助调用。

Provider 单轮可返回多个结构化工具请求。SYSTEM 约束只鼓励把彼此独立的只读探索合并到小批次，依赖动作、修改、危险命令和外部副作用仍保持逐项有序。AgentEngine 先对整批做调用预算预检，再按供应商顺序逐项执行；每项拥有独立审批、审计和 checkpoint，拒绝、未知副作用、超时或执行异常会停止同批后续副作用。整批 observation 共享同一个字符上限，不能按调用数放大上下文预算；`list_files` 另有 20–300 项的调用级上限，默认 160 项。

## Agent Harness

`AgentHarness` 是主智能体与专家智能体进入 `AgentEngine` 前的正式运行边界。`HarnessRunSpec` 绑定 workflow/attempt/agent、模式、协作策略、limits、恢复计数和未知副作用降级状态；`HarnessPreflight` 在所包装 AgentEngine 的主 Provider I/O 前验证绑定关系、有效工具面与副作用分类，并生成不含凭据的运行与工具面摘要。持续执行是默认生产策略：Harness 仍记录累计轮次和工具数，但不把旧的有限值当作恢复拒绝条件，也不以累计墙钟包裹整个 `runLoop`。活动消息日志在每次 Provider 边界前滚动压缩到最多 240 条且受字符上下文上限约束，固定保留系统指令、最初目标、当前用户目标、最新消息和完整工具调用/结果组；被裁剪的中间段只留下不含原始工具输出的结构化执行记忆。用户取消、IDE 生命周期取消、单次 Provider/工具超时、有限重试、连续相同动作、窗口内重复相同只读观察、连续失败、审批、沙箱及未知副作用门禁继续生效。显式关闭持续执行后，轮次、工具数和墙钟按每次恢复 attempt 重新获得一段额度，而历史累计计数继续用于审计。该 digest 不表示沙箱、费用或共享账本的完整审计指纹。视觉辅助预处理和 MCP 发现仍使用各自已有的独立预检边界。Tool Registry 拒绝空名称和重复名称；Harness 预检拒绝有效工具面中未标记 dangerous 的非只读工具，避免 schema 与实际执行映射分裂。

Project Harness 是互补的仓库可读性层。`ProjectHarnessService` 只读、有界地发现规则、知识文档、构建/测试/质量/CI 证据与 `.omnicode/harness.json` argv 反馈回路；它绝不启动进程。侧栏以“项目上下文”和白话就绪建议为默认入口，成熟度、精确 argv 与运行边界默认折叠；安全配置示例只写入剪贴板，受 60 KiB 硬上限约束。摘要作为 `TransientProjectContext` 中的不可信项目数据注入，历史、checkpoint 和研究包继续丢弃该 block。模型也可调用只读的 `inspect_project_harness` 刷新地图；实际验证仍只能通过正常 `run_command` 审批和沙箱路径。

存在未解除的未知副作用时，Harness 预检状态为 `DEGRADED_READ_ONLY`，危险工具 schema 不再暴露给模型；执行层的恢复门禁仍作为第二道独立保护。侧栏显示的分数只是规则、文档、反馈、测试、质量和 CI 的启发式成熟度，不代表命令已成功运行。

## Team orchestration

- `Team` 与权限模式正交：Agent + Team 的主智能体可在审批后产生副作用；Plan 看板 / Claude Plan + Team 全程只读；Research + Team 仍只有主智能体能运行经过审批的实验命令。专家失败、取消或边界结果作为 `DELEGATION_FALLBACK` 证据返回且不递增主 Agent 的连续工具失败计数，主 Agent 仍需自行核验；委派结果后若主 Agent 返回空文本，执行环会追加一次无工具的 lead synthesis 请求，避免把“已委派但未综合”误报为终态。
- 只有主智能体拥有 `delegate_specialists`。Single / Team 可由确定性的目标路由器自动选择：短小、单文件目标保持 Single，跨模块、科研综述、附件分析和复杂排障才建议 Team；用户仍可覆盖。每轮委派 1–4 个独立任务，最多 3 轮、8 个专家、并行度 4；角色可使用 `specialist:<name>` 动态命名，但不能递归委派。专家继承主任务的持续执行策略，不再分配更小的局部 Token、轮次、工具或墙钟额度。委派协调器保留独立的长时单工具超时，并始终由父任务取消统一收口；持续模式下不会再被旧的主任务墙钟提前截断。
- 无法发出结构化工具调用的 headless CLI（例如 OpenCode、Kimi 或 Pi）仍支持显式 `Team`：执行层会在 lead CLI 请求前自动调用同一个本机 Codex App Server 协作入口，按 explorer、reviewer、planner 生成最多三个互不重叠的只读任务，并把有界结果注入 lead 的系统上下文。这样 CLI lead 不会因为协议只返回文本而静默显示“子代理 0”。该预委派只在 `Team` 生效，`Single` 不启动 Codex；Codex 不可用时仍显示每个子代理的失败卡片和可操作原因，不伪造成功或偷偷切换到其他供应商。
- 每个专家使用新的 Provider 实例、空历史和新的 `AgentEngine`，仅收到有界原始目标、自己的 objective 与角色约束。主智能体历史、兄弟专家上下文和其他专家的输出不会注入。专家在显式有限模式、供应商输出/上下文边界或异常中止时，工具结果和阶段分析会重新压缩为给主智能体的可核验证据；UI 折叠行使用独立的人类可读摘要，展开“查看处理内容”时可滚动查看事件携带的完整有界 detail，不展示隐藏提示词或密钥。有可用证据的阶段结果不会误报为全量完成，无可用结论时仍显示失败。
- 专家以 `PLAN` 运行，只注册内置工具与 Skills；Registry 在 schema 暴露和执行查找两层都只允许 `READ_ONLY`。不会连接 MCP，也不能写文件、运行命令或发起副作用审批。
- 专家输出作为有界、不可信证据返回主智能体；主智能体必须核验关键事实并独自生成最终答案。UI 不混入专家未授权的隐藏上下文，只显示开始、完成、状态、摘要和 Token 的有界事件，并允许用户展开事件 detail。
- 主智能体、视觉辅助模型和所有专家共享 workflow Token / 费用统计账本。并发请求先登记预计用量、成功后按实际 usage 提交、失败或取消时释放，但不执行本地任务硬额度或委派预算预检。最终用量以确定性 run ID 聚合写入一次，工具审计按 workflow ID 与 agent ID 隔离；供应商缺少 usage 时会同时估算文本和结构化工具调用块。
- 取消 Project Service 的活动 Job 会通过结构化并发取消所有专家。部分专家失败不会丢弃成功结果；全部失败会把委派工具标记为失败，由主智能体决定降级或停止。

## Agent / Plan and review routing

- `Agent` 使用完整 ReAct 工具面；文件写入、命令和 MCP 调用仍经过各自审批与沙箱。
- `Plan` 只允许显式标记为 `READ_ONLY` 的工具，并要求输出可解析的 Markdown checklist。
- `Claude Plan` 使用独立模式值，可调用 `READ_ONLY` 工具和内置 `run_command`；后者必须先通过纯 argv 只读策略，再强制使用无网络、工作区只读的 macOS sandbox-exec / Linux bubblewrap。未知、复合、可写或可扩展执行的命令失败关闭，文件修改与 MCP 在 schema 和执行查找两层仍不可用。
- `/plan <任务>` 是单轮 Claude Plan 覆盖，不污染常驻模式；`Shift+Tab` 在 Agent 与 Claude Plan 间切换。计划完成后不跳转页面，而是在聊天流内展示绑定当前修订的可编辑审批卡；用户可继续规划、勾选、跳过、撤销、逐步批准或批准后切换 Agent 执行。不存在独立计划页面。
- 输入框同时提供本地路由命令：`/status`、`/model`、`/permissions`、`/mcp`、`/tasks`、`/new` 和 `/help` 不创建模型请求；`/review [要求]` 复用内部只读审阅策略并要求证据化文件/行号引用，但不形成第四个 UI 模式或页面。命令解析只接受完整 token 或空白边界。
- `Agent`、Plan 和只读审阅均严格遵循用户选择的轮次、工具次数、墙钟、Provider 超时和输出设置；本层不新增累计 Token/费用硬上限。
- 未显式分类的新工具默认是 `EXTERNAL`。Registry 在 schema 和执行查找两层执行同一模式策略；即使模型伪造调用，只读模式也不会触发审批或副作用。
- Project Service 只为 `Agent` 连接或启动 MCP Server；Plan 和只读审阅在连接层即跳过 MCP。只读 Skill 工具仍可按模式加载。
- 每次运行都会移除旧 SYSTEM 消息并注入当前模式约束，因此同一对话可在四种模式之间切换而不会继承旧模式权限。运行模式随会话 checkpoint、用量记录和工具审计持久化，旧记录仍允许空模式迁移。

## Context

- Write：完整变更写入项目和 IDE Local History；工具结果保留在会话。
- Select：保留系统约束、最初用户目标、当前运行中最后一个非纯工具结果的用户请求，以及最新消息；当前目标中的验收条件和约束因此不会被尾部 ToolResult 挤出。选择可选历史时，单个组超限只跳过该组，仍会继续尝试其他可容纳组。
- Compress：达到字符预算后丢弃中段，并插入确定性的省略说明。
- Isolate：每个 JetBrains Project 拥有独立 Service 和协程生命周期；Team 专家拥有独立消息历史与身份。

项目规则、Harness 仓库地图与固定文件以 `TransientProjectContext` 专用 block 放在当前用户请求之前。它们按 `maxContextChars`、模型上下文余量、首个目标、当前目标和固定系统余量动态裁剪；Provider adapter 把该 block 序列化为普通请求文本，但持久化、checkpoint 与研究包路径显式丢弃。`.gitignore`、`.aiignore`、`.omnicodeignore`、显式排除和敏感路径由同一个 fail-closed policy 约束规则、Harness、Pinned Context、PSI/index 与通用文件工具。

`/context` 只复用下一轮将使用的有界、只读上下文快照并返回分类计数，不调用 Provider、不启动 Harness 命令，也不把文件内容传给 WebView。规则、Harness、固定文件的字符数分别换算为近似 Token；段落分隔和元数据单独显示为结构开销，避免用固定百分比伪造上下文分布。

`PlanBoardService` 在 project workspace state 中按 conversation ID 隔离并保存最多 100 个计划及其修订号、审阅决定、执行策略和步骤状态。切换历史或新建对话只激活对应看板，不会把旧会话的计划投影到当前聊天；分离任务完成时也只更新它捕获的原会话。步骤文本、勾选、跳过或恢复都会推进修订并使旧审阅决定失效；只有绑定当前修订的批准才能执行。手动策略每次仅启动一个步骤并停下，连续策略才在成功后推进；重规划仍只在明确的同一 board ID 下保留已完成/已跳过步骤。旧版单看板字段首次加载时幂等绑定到首个可见会话。

聊天 Markdown 只把通过严格语法校验的项目相对 `path:line` / `path:start-end` / `#L` 引用标为可点击；绝对路径、URL、路径穿越、无效行号和代码块内容不会获得链接。打开时再次进行工作区、普通文件、符号链接和真实路径校验；仅文件名引用通过 PSI 文件索引唯一匹配，重名时失败关闭。历史消息复用同一回调与校验路径。

`TaskChangeReviewService` 以 workflow ID 记录 `apply_patch` / `apply_change` 的 first-before/latest-after 与稳定 hunk ID，并将有界快照写入 IDE system path；重启后会恢复账本。对话内 Edits 可按稳定 hunk ID 或整文件保留/回退；每次操作都会刷新同一 workflow 的审阅快照。用户可显式导入当前 Git 已跟踪差异，导入项带 external 标识且只允许逐文件审阅。回退前复核路径、符号链接和当前哈希；命令、MCP 或用户并发编辑不被伪装成 Agent 直接修改。

`git_workflow` 是 Agent-only 的高风险工具：Worktree 只能位于项目下 `.omnicode-worktrees`，分支名经过 Git 语法白名单校验；`pr_create` 只调用 tokenized `gh pr create`，网络沙箱拒绝时不会降级。`browser_automation` 只接受无凭据的 HTTP(S) URL，使用本地 Playwright CLI 做版本检查、打开或截图，输出只能写入 `.omnicode-browser`；两者都先显示完整 argv 和风险，再复用 `run_command` 的路径复核、环境清理、超时、输出边界、审计和检查点。

项目文件被视为不可信输入，其中的文本不能覆盖系统策略。

聊天附件按类型、大小、图片头和像素数做本地校验。图片以降采样方式生成有界本地缩略图，可由具备视觉能力的主模型直接接收，或在用户批准后交给配置的视觉辅助模型转写；Markdown、文本、日志、结构化数据、LaTeX/BibTeX、R/Julia/MATLAB 和常见源码以有界 UTF-8 文本块进入上下文，预览不超过 6000 字符/80 行。BibTeX 另外经过有界离线条目、重复 key/DOI 和 DOI 格式检查，网络解析状态默认保持“未验证”。拖拽、文件选择、剪贴板和 `@` 文件引用共用同一校验路径。

旧版 Semi Design 专用工作流不再从 3.0 UI 暴露。图片转代码作为普通聊天任务使用同一附件、视觉辅助、审批、沙箱、checkpoint 与变更审阅边界，不再维护产品专属入口或持久化状态。

`@` 引用在当前项目下执行有扫描数量上限的文件名/相对路径匹配，只返回 Attachment Intake 支持的普通文件，并跳过 `.git`、IDE/Gradle 元数据、依赖、虚拟环境和构建输出目录。选择结果不是给予模型任意文件访问权，而是作为普通附件再次执行扩展名、大小、UTF-8、控制字符和敏感文件规则。

PDF 通过 Apache PDFBox 3.0.8 在本地、内存型缓存中解析，先验证 `%PDF-` 签名，再限制为 10 MB、300 页和 48,000 个提取字符；输出带页标记及稳定页码偏移，可供研究报告引用。加密或损坏的 PDF 仍拒绝；无可读文本时，仅当系统 PATH 存在 Tesseract 才对明确选择的本地 PDF 渲染最多 4 页、单页 2 秒，并保留 `[local OCR]` 页标记，否则提示关键页截图/视觉辅助。原始 PDF 不上传。PDFBox 的 Apache License 2.0 来源与声明记录在 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)，依赖 JAR 保留上游许可元数据。

Jupyter Notebook 使用严格 UTF-8 JSON 流式解析，限制为 2 MB、200 个 cell、单 cell 12,000 字符和总计 48,000 字符。默认只提取 Markdown 与代码 cell 的 `source`；研究工具可显式启用 8,000 字符的纯文本 output preview，outputs 中的图像、HTML 富媒体、附件和 metadata 仍通过流式跳过而不物化为完整 JSON 树。NUL、控制字符异常、畸形或结构过深的 Notebook 会被拒绝。

## Theme and pet settings boundary

主题和桌宠位于设置中，不是顶层目的地。目录由编译期 `WorkshopTheme` 和 `WorkshopPet` 数据组成，只允许经过格式与长度校验的 ID、颜色、普通显示文本、宿主渲染枚举和有界空闲提示；不存在脚本、命令、类名、反射、URL 或远程下载入口。持久化设置只保存已选主题 ID、桌宠 ID、启用开关及 0..10000 的工具窗口内归一化位置，加载时必须重新对照受信任目录解析；未知桌宠 ID 会回退并自动禁用。

`CustomPetAvatarStore` 是唯一的本地素材入口。它把用户选择的图片视为不可信输入：拒绝符号链接、伪图片、GIF/SVG、超 8 MB、宽高超 2048 或像素超 419 万的内容；先在后台通过 ImageIO 读取真实格式和尺寸，再渲染进新的 ARGB 缓冲区、最长边缩至 512 像素，并以临时文件原子发布为 PNG。源路径、EXIF、附加数据和原始字节均不持久化，目标固定在 IDE config 下的 OmniCode workshop 目录，不进入项目、Settings Sync、模型上下文或 MCP。导入失败保留上一份有效立绘，删除只移除规范化副本。

主题只改变 OmniCode Tool Window 的 React 工作台表面，不修改 JetBrains 全局 Look and Feel。桌宠由插件内编译的原创 SVG 图元绘制，目录只传递受信任的视觉/动作枚举、配色和有界提示文案；WebView 不加载远程角色、脚本或社区资源。设置页角色画廊可本地预览待机、工作和完成状态，聊天中的角色支持眨眼、挥手、完成庆祝、空闲气泡、拖拽贴边与 `prefers-reduced-motion`。它只消费已有运行状态，不获得 Agent 工具、不触发模型调用，也不会使任务脱离 Project Service 生命周期在后台继续；进入历史或设置后立即卸载聊天投影，但保留规范化位置。

## Side effects

`apply_patch` 与 `apply_change` 都要求 `read_file` 返回的 SHA-256，审批前后各校验一次，并在 `WriteCommandAction` 内写入。精确 Patch 的每段旧上下文必须唯一匹配，歧义或过期内容会 fail closed；这两个工具仅 Agent 可见，直接以 Plan/Research 调用也会拒绝。`run_command` 使用 `GeneralCommandLine(List<String>)`，不拼接 shell 字符串，工作目录必须在项目内，仅 Agent/Research 可见且逐次审批。

默认 `workspace-write` 会先选择平台后端并执行真实能力探测。macOS 使用 `sandbox-exec` profile；Linux 使用 `bubblewrap` 的 mount/user/network namespace：宿主根只读、用户目录以私有 tmpfs 隐藏、工作区重新读写挂载、HOME/tmp 为进程私有目录，网络 namespace 仅保留 loopback 视图。Windows 使用随插件分发且经 SHA-256/签名门禁的 native `omnicode-appcontainer-host.exe`：helper 通过 `CreateAppContainerProfile` 和 `PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES` 创建无 network capability 的 per-run token，在有界 ACL 事务中授予工作区 SID，拒绝重解析点/超大目录，进程结束后恢复原始 DACL；授予或清理失败都会返回非零并保持 fail closed。探测会验证后端身份、profile/stdio 启动能力和稳定性，任一失败都不降级。Windows helper 不存在时仍可通过 JetBrains WSL/Remote Development 在 Linux 后端运行。`danger-full-access` 是显式用户设置；它移除 OS 级隔离，但不移除审批、argv 直执行、环境清理、超时和输出边界。

## Extension boundary

MCP stdio 启动器在不执行 Shell、也不读取项目配置的前提下合并 IDE 环境 PATH、已解析可执行文件的父目录和受限的跨平台用户运行时目录（nvm/fnm、Volta、asdf/mise、pyenv/rye、npm/pnpm/yarn/bun 以及 Windows Node/npm 目录），并在 Windows 上按 PATHEXT 规则识别 `.exe`、`.cmd` 和 `.bat` 入口。这样从 Finder/Toolbox 启动 IntelliJ 时，已安装的 `node`、`npx` 或 `uvx` 不会因为 GUI PATH 不完整而被误报为缺失；对于位于用户目录的解释器，MCP 计划还会把经过规范化的运行时目录以只读方式重新暴露给 macOS sandbox-exec / Linux bubblewrap，避免 `#!/usr/bin/env node` 在沙箱内变成 `No such file or directory`。该补全不放宽现有审批、路径复核或沙箱边界，任意 PATH 条目不会被挂载。

MCP Server 仅在 Agent 模式可通过已配置的 stdio 进程或 2025-11-25 Streamable HTTP 接入。stdio 初始化、工具发现和调用使用有界 JSON-RPC 行协议；进程启动前解析真实可执行文件和沙箱计划，并按服务器、项目、参数、工作目录、沙箱、环境变量名、可执行文件内容和后端身份生成指纹。HTTP 使用 JSON/SSE、有界响应、Session/Protocol headers、404 会话重建和关闭 DELETE；远程强制 HTTPS、禁止重定向，并明确绕过代理访问 loopback。OAuth 层解析 401/403 Bearer challenge，按 RFC 9728 发现受保护资源，再按 RFC 8414/OIDC 发现授权服务器；配置页只在用户确认联网后执行发现，challenge 的 resource metadata URL 必须与 MCP resource 同源，authorization-server metadata URL 只从校验后的 HTTPS issuer 派生，所有响应、URL、数组与 Scope 均有界。发现预览展示授权/Token/注册端点和客户端注册能力，仅在 Scope 为空时填充，不把远端端点持久化为信任配置；登录和刷新会重新发现并校验。无 Client ID 时优先 RFC 7591 动态注册，不可用时在打开浏览器前提示用户填入服务商 Client ID。OAuth 强制 PKCE S256、state 和 resource audience，支持公开客户端/动态注册、过期刷新及 401 单次刷新重试。OAuth 会话以规范化 Endpoint、认证模式、配置 Client ID 和排序 Scope 生成绑定指纹；跨 manager 的登录/刷新按 Server ID 单飞，logout 与永久 token 错误通过 generation 使所有在途结果失效。Bearer、OAuth Token 和动态客户端凭据均只从 PasswordSafe 读取。两种连接首次或指纹变化后都重新审批，每个 MCP tool 均标记为 dangerous 并逐次审批。Skill 来源只在用户配置的目录中发现 `SKILL.md`，由 `list_skills` / `load_skill` 按需加载，不会自动注入完整技能库。

远程数据库是独立的受控外部工具边界，不依赖本机数据库服务。设置只持久化连接 ID、名称、数据库类型、规范化网络 JDBC URL、用户名和超时/行数策略；输入层也接受 `postgresql://`、`postgres://`、`mysql://` 和 `mariadb://` 常用形式并在保存时规范化。数据库名称可选：MySQL/MariaDB 可建立服务器级连接，PostgreSQL 无路径 URL 会被规范化为驱动可接受的末尾 `/`。“保存配置并设置密码”使用 JetBrains 原生密码对话框，密码按连接 ID 与精确 URL 摘要绑定到 PasswordSafe，写入后立即回读确认；Endpoint 修改后旧密码不会被复用。只分发受审阅的 PostgreSQL 与 MariaDB JDBC 驱动，后者同时支持显式 MySQL URL；不加载项目提供的任意 JDBC 驱动。URL 必须包含网络主机，禁止 user-info、密码/Token/API Key 参数、fragment、文件型连接，以及可加载自定义 Socket/认证/日志/属性转换类或切换本地 Socket 的参数。`query_database` 标记为 dangerous + EXTERNAL，因此只在 Agent 模式可见且每条 SQL 都经原生审批；只有一个启用连接时可省略 ID，多个连接时需使用设置中的名称或 ID，连接目录不会作为普通只读工具暴露给 Plan。执行层仅接受一条 SELECT/SHOW/DESCRIBE/DESC，拒绝注释、批处理和 INTO，设置 JDBC read-only 与只读事务，并限制连接/查询超时、返回行列和文本大小。JDBC 阻塞调用通过可中断 IO 边界运行；重复测试会取消并取代上一任务，面板关闭或 Agent 取消也不会接受迟到结果。探测连接可以在启用前进行，但不会执行用户 SQL；查询结果作为有界 observation 进入当前对话和本地恢复检查点，凭据不会持久化到任务数据。

3.0 的 JCEF 设置页只传递 `bearerConfigured`、`oauthConfigured` 和 `oauthUsable` 等布尔状态。Bearer 输入使用原生密码对话框并直接写入 PasswordSafe；OAuth 发现先经用户确认，登录使用系统浏览器和原生完成确认。Token、动态客户端密钥及原始认证响应不会进入 JavaScript、WebView state、历史或事件信封。

同一任务的独立 MCP 初始化与工具发现最多四路并行，结果和名称冲突仍按用户配置顺序合并；首次连接审批按顺序显示，但已信任连接仍可并发。审批使用可取消对话框，任务或项目取消会拒绝并关闭仍显示的授权界面。每台服务器继续独立执行原有信任、审批、沙箱、凭据和失败隔离。取消发现会在 IO dispatcher 并行关闭所有已经建立的客户端，调用方从获取阶段起即用 `finally` 接管资源；任务结束时独立 HTTP/stdio 会话并行关闭，避免离线服务器把首字延迟和收尾延迟线性叠加。

TokenTracker 是完全可选的第三方 companion，而不是 Agent 运行依赖。使用统计页只在绝对 PATH 和少量固定系统目录中发现可执行文件，不执行它；服务探测与聚合数据读取固定使用 `http://127.0.0.1:7680/`、绕过代理、禁止重定向并限制连接时间、响应时间和响应字节。验证通过后，插件从 TokenTracker 的只读 `/functions/tokentracker-*` 接口读取最近 30 天 summary、daily、model 和 project 聚合，在 OmniCode 自己的 React 设置页渲染指标、趋势和排行，不加载第三方 iframe 或第二个 JCEF 窗口。64 位计数以字符串跨 JVM/JCEF 边界，前端数组和标签继续二次限界；项目仓库 URL 被 JVM 丢弃，只传末级项目名。安装/启动操作仍只复制明确命令；插件不直接读取 TokenTracker 数据库、不请求或传递本地认证 Token、不调用变更接口、不共享 API Key，也不接管其 hooks、更新或云同步。完整 TokenTracker 功能可显式在系统浏览器打开固定 `/dashboard` 路由。

`McpMarketplaceCatalog` 保留 27 个编译期精选和六个稳定分类，并负责有界搜索、相关度排序、可添加/仅浏览筛选与默认禁用草案转换。“Built-in Presets”只表示随插件审阅和发布的配置示例，不代表供应商官方认证。市场打开后可在后台从固定 HTTPS 主机 `registry.modelcontextprotocol.io` 的只读 `GET /v0.1/servers?version=latest` 接口按不透明游标加载至少 500 个元数据条目；客户端限制连接/请求时间、响应字节、JSON 深度/节点、页数、条目数、字段长度和重复游标，结果在当前设置会话内缓存一小时并可手动强刷，同时以不含凭据和命令输出的有界 JSON 保存最近一次成功目录。六小时内重启优先复用本机脱敏目录并明确标注“本机缓存”，过期后尝试刷新；刷新失败仍保留旧目录，只有显式刷新成功才替换它。Registry 数据仅作未审阅的内存目录，不把发布者声明当作信任证明，不下载图标或包、不运行命令、不写配置或凭据。

用户可显式导入 GitHub Copilot / Claude Desktop 风格的 `servers` / `mcpServers` JSON。导入器限制文档大小、服务器数、参数数和字段长度，只接受结构化 command/args、受安全 URL 校验的 HTTP Endpoint、工作目录、OAuth 元数据和环境变量名；Header、Bearer Token、环境变量值及其他秘密一律丢弃。所有条目以禁用草案写入，后续启用、首次连接、凭据保存、审批、沙箱和风险扫描与手工配置完全相同；导入本身不会启动进程、联网或执行项目文件。

UI 验证分为两层：普通 PR 运行无磁盘、确定性 `UiScreenshotRegressionTest`；手动触发 `.github/workflows/ui-regression.yml` 时，IntelliJ Platform Testing 启动真实 IDE 和 Robot Server，`RemoteRobotSmokeTest` 通过 loopback HTTP 请求并取得真实桌面截图。Robot Server、IDE 桌面、Xvfb 和 JDK 21 都是 CI 外部运行环境，插件本地测试不会偷偷启动它们。

Registry 的 npm/PyPI 或直接 Streamable HTTP 声明只有在能转换成单一 argv/固定 HTTPS Endpoint、且参数与凭据键通过现有字段策略时才显示为可添加方式；带完整性哈希而当前无法在包管理器路径中验证、使用自定义 Registry、动态参数/请求头、模板 URL 或 SSE 的声明仅展示元数据。添加操作复用现有 `McpServerConfig` 生成默认禁用草稿，后续保存、PasswordSafe 录入、启用、首次连接审批、进程沙箱与逐工具审批仍由原有边界处理。

用量、会话、workflow checkpoint 和工具审计写入 JetBrains system path，而非项目目录；自由文本在持久化前脱敏并截断，文件和记录数均有硬上限。checkpoint 默认最多 200 条、文件最多 256 MiB；恢复卡中的“放弃检查点”可删除对应记录，但不会回滚已发生的副作用。API Key 只进入 PasswordSafe，并按供应商与规范 Origin 绑定；远程地址必须使用 HTTPS，认证请求不自动跟随重定向。MCP 环境密钥同样只进入 PasswordSafe。

## Provider boundary

领域层使用 `Text`、`Image`、`ToolCall`、`ToolResult` 内容块，不假设所有服务都有 `role=tool`。每个协议适配器负责把它们映射成 Responses items、Anthropic content blocks、Gemini parts、OpenAI Chat messages 或 Bedrock Converse blocks。

Codex 原生 App Server 是一个内部的只读子智能体后端：Team/自动路由需要专家时，它启动 `codex app-server --stdio`，按当前 CLI schema 的 JSON-RPC 能力握手创建一个协作期可发现的父 thread，再把有界专家目标作为 `turn/start` 输入。父 thread 不能是 ephemeral，否则原生 spawn 无法从 Codex thread store 解析父子关系；本轮终止、失败或取消后通过 `turn/interrupt`（若仍活动）与 `thread/delete` 递归清理父 thread 及其后代，旧 CLI 的本地状态库无法执行 delete 时回退到 `thread/archive`，进程树回收作为最终 fail-closed 边界。App Server 在同一 stdout 中复用父、子通知，因此 `turn/completed`、token usage 和最终文本严格按父 thread ID 收口，子 thread 的完成、错误和重连只更新对应子代理卡片，不能提前结束或污染主回复。主对话仍使用用户配置的 Provider。命令与文件变更审批请求映射到现有 `ApprovalGate`，专家固定使用 `never` 与 `read-only`，工作目录固定为当前 JetBrains 项目。该适配器不执行 `codex exec`、不复用远程 API Key，也不把本机 Codex 会话内容写入 OmniCode；本机可执行文件缺失、协议错误或进程提前退出均只使专家失败，不静默回退到主 Provider。图片输入仅以有界 data URL 传给原生 turn，其他附件先经过既有本地边界。

原生子线程的多个 `item/*` 生命周期事件会按 thread ID 合并，最后一条状态作为权威结果；中间的有界摘要以瞬态 `DelegatedAgentProgress` 事件更新 Team 卡片，供用户看到当前处理阶段。该进度不会写入 workflow ledger，也不会把隐藏提示词、原始 reasoning blocks、凭据或未授权上下文带入主对话；完成时仍以 `DelegatedAgentCompleted` 的有界证据为准。

Provider 传输层禁止携带凭据跨 Origin 重定向，并把可安全显示的请求 ID、网络失败状态和有界 `Retry-After` 传给 Agent 控制层。审批解析事件在危险工具执行前必须持久化成功；该审计写入失败时执行 fail closed。
### Provider transport failures

HTTP transport failures preserve a bounded, redacted cause for diagnostics. TLS handshake failures
are presented separately from HTTP status failures and are not retried blindly: a request that
never established TLS cannot be made reliable by repeating it, and repeated attempts make a proxy
or certificate problem look like a slow model. The connection-diagnostics surface remains the
recovery path; certificate verification is never disabled.

Streaming requests use both a total request deadline and an absolute first-token deadline. A
provider that accepts the request but never emits usable SSE data therefore fails promptly while
normal long-running streams remain governed by the total deadline.

Provider profiles persist an independent connection mode (system/IDE proxy or direct) and an
optional request timeout. Model discovery and inference use the selected profile rather than a
single global networking choice; switching the system proxy only rebuilds clients for profiles
that follow it.

本地 CLI 供应商的 `cli://local` 只是路由标记，不是可解析的 HTTP 地址。连接诊断不会对
Claude、Codex、Grok、Kimi、OpenCode、Pi、OMP 或 DSH 执行 DNS/TLS 探测，也不会用模型名启发式
误判工具能力；这些检查显示为跳过，CLI 可执行文件由设置页的依赖诊断单独确认。远程 API 仍按真实 Base URL 执行有界的
DNS 与 TLS/HTTP 检查，并在界面将失败原因和恢复建议本地化。

本地 CLI 统一声明其会话连续性：`BOUNDED_REPLAY` 只重放有界可见历史，
`NATIVE_SESSION_ID` 使用 CLI 自身的 session/thread ID 恢复，`PERSISTENT_HOST` 复用受控宿主。
Claude Code、Codex、Kimi、OpenCode、Pi 与 OMP 采用原生 ID 恢复；Grok 仅做有界重放，DSH
由持久宿主负责连续性。每次调用仍是非交互 argv 进程，stdin 会立即关闭，stdout/stderr 分离并
有界读取，取消总是回收完整进程树。Codex 模型发现通过本机已认证 App Server 的 `model/list`
读取实际选择器并支持分页；Kimi 与 Pi 的模型发现只执行固定只读 argv，不启动登录流程、
不转发密钥；发现到的模型列表为空时要求用户先在对应 CLI 完成认证。`auto` 是“不传模型参数、
使用 CLI 当前默认值”的控制项，不作为模型 ID 缓存或展示；旧版 `default`/`kimi-k2` 占位值会
幂等迁移为 `auto`。模型列表若确认当前模型已
下线，界面将其标为不可用而不继续伪装成已验证选项，也不会静默切换到可能产生不同费用的模型。

原生历史导入是用户在依赖页显式触发的只读能力，不属于项目扫描或启动探测。Codex 使用已登录 App Server 的 `thread/list` / `thread/read`，OpenCode 使用 `session list --format json` / `export`；两者均只接受与规范化当前项目目录匹配的会话。每次调用限制时长、条目数、JSON 字节、消息数和可见字符，并在取消时回收完整进程树。Codex 历史进程通过仅作用于本进程的 CLI override 禁用全局 MCP，避免无关服务器拖慢初始化，不修改用户配置。导入器丢弃 reasoning、工具结果、图片和系统消息，只保存可见用户/助手文本和不透明原生 session ID。其他 CLI 在没有稳定只读目录协议前明确显示不支持，不扫描私有数据库或猜测文件格式。

JCEF bootstrap 只传递 IDE Locale 标签，不传系统环境或设备信息。高频导航、历史与输入控件通过有类型的前端词典本地化；未知/缺失 Locale 回退中文，普通模型输出保持原样，不对用户或供应商文本做不透明机器翻译。

OpenCode 的 JSON 事件以 `session.status=idle` 或最终 `step_finish` 为完成边界，工具调用中的
`step_finish` 不会提前结束 turn；进程尚在收尾时也不会让已完成回答继续旋转。发送前使用当前
会话 ID，恢复时仍按事件 session ID 隔离。错误文本只从受支持的有界 JSON 字段提取，包括
`error.data.message`，不会把 provider reference、原始响应或其他诊断元数据泄露到对话。

Codex 原生 JSON-RPC 的 stdout 由独立、有限容量的读取通道消费，模型请求协程不再同步阻塞在
`readLine()`；取消、超时和进程关闭会关闭通道、销毁进程并等待读取线程收尾。MCP 市场目录的
后台刷新与当前查询代次绑定，新的搜索/刷新会取消旧请求，避免离线 Registry 在页面关闭后继续
占用连接。对话中的错误卡可直接启动脱敏连接诊断或跳转设置；诊断只返回分类、耗时和修复建议，
不返回密钥、原始响应或命令输出。

Codex App Server 的 `error` 通知按协议 v2 从外层读取必填的 `willRetry`，并从内层
`codexErrorInfo` 区分响应流断开、首次连接失败和重试耗尽；不会再把有效登录误报为认证失败。
当 `willRetry=true` 时，当前 turn 会继续消费同一进程和 thread 的阶段、模型及子代理事件，
由 App Server 自己的有界重试和 Provider 总请求超时共同收口，避免客户端提前 15 秒终止、重复
创建 thread、重复扣费或重复执行副作用。旧版缺少 `willRetry` 的通知只获得 30 秒兼容窗口；
明确 `willRetry=false`、进程退出或总请求超时才产生可重试的传输失败。事件通道保留容量与单条
大小上限，关闭原因会进入脱敏诊断而不伪装成上游重连失败；用户取消始终优先且不会重放未知副作用。

原生只读协作启动 App Server 时，会从用户 Codex 配置中只读取 MCP server 的段名，并通过
一次性 `-c mcp_servers.<name>.enabled=false` 进程参数禁用这些启动项；不改写配置、不复制凭据，
也不影响用户在普通 Codex 会话中使用 MCP。这样 Reviewer/Explorer 不会因无关远程 MCP 的启动
或重连而延迟首个 turn。

原生 Team 连接不会继承主供应商 Auto/Low 常用的 120 秒请求上限。它固定使用 Ultra 推理并把
总时限提升到可取消的 30 分钟安全边界；期间 stdout 轮询继续每 5 秒发送心跳，用户停止和项目
关闭仍会立即关闭通道并回收 App Server 进程树。该边界只延长同一原生 turn，不创建重复 thread、
不重放工具，也不会改变主对话供应商的超时设置。

### Session-scoped execution and review recovery

运行状态、停止按钮和计划审批均按 `conversationId` 作用域计算。切换到另一个会话不会因为
当前会话仍在请求模型而被拒绝；原会话继续在项目级生命周期中运行，并可从历史列表重新打开。
启动计划或继续计划前只检查当前会话是否有运行中的 turn，避免项目级全局锁把无关会话误判为忙碌。

Codex 原生请求使用独立 stdout reader 和有界轮询，而不是在模型协程中同步调用 `readLine()`。
轮询期间每 5 秒最多产生一条有界的等待进度，取消会立即关闭读取通道并回收进程；该心跳只表示
仍在等待 App Server 事件，不会伪造模型完成。真实终态仍必须来自 `turn/completed`、明确错误或
用户取消，App Server 本地登录、网络和上游模型未就绪时仍需要诊断并不会被 UI 隐藏。

变更审阅卡同时传递 hunk 的起止行和行数。文件打开操作会把结束行传入 IDE，因此
`path:start-end` 能选中完整范围，而不仅是跳到起始行；历史恢复和实时审阅继续使用同一导航校验。

历史消息上的“从此处分叉”是非破坏性的恢复边界：服务从当前会话的有界消息快照，或持久化
历史中复制到指定消息，生成新的 conversation ID；源会话的运行、检查点和后台任务不会被取消或
覆盖。未提供有效消息索引时不会创建分叉，避免把正在流式更新的临时块误当成可恢复历史。
