# Architecture

## Boundary

OmniCode 的运行边界是“单项目、每会话单 turn、每 turn 单主智能体”；不同会话可以独立在后台运行。3.0 Tool Window 只提供聊天、历史记录和设置三个视图；任务、计划、子代理、变更与错误诊断都作为当前对话内的结构化卡片呈现。UI 在发送时冻结本次权限模式和 `Single` / `Team` 协作策略；模型只产生文本或结构化工具请求；真实副作用只能由对应会话的主智能体经本地工具策略、进程沙箱和审批层产生。

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
The parent `turn/completed` event is the authoritative successful terminal signal. If the matching
`turn/start` JSON-RPC acknowledgement is lost, the provider waits two seconds for it, then closes
the turn from that terminal event instead of leaving the UI running until the total request timeout.
Terminal events naming another turn are ignored; cancellation still closes the reader channel and
reaps the App Server process tree.

设置页的本地 CLI 检查分为三层：版本探测、手动模型目录读取、用户显式确认后的一次真实短对话。前两层不发模型请求，不能据此声称已登录或能完成任务。第三层先验证可执行文件，再用 IDE 原生审批告知潜在模型费用和 CLI 自身工具风险；获批后只在新建的空临时目录调用该 CLI，不携带项目文件或 OmniCode 工具，也不写入当前会话。测试发送随机挑战码，仅在模型回复包含该码时标为已响应，避免把启动文字误判为答案；最多等待 180 秒，用户可以取消。终态不证明项目工具、MCP 或代码任务可用。临时目录在请求结束或取消后按不跟随符号链接的路径遍历清理。CLI 自身全局配置与供应商账单仍由用户控制；无法给不支持只读模式的 CLI 虚构只读保证。

```text
Tool Window (JCEF + React) ── Chat / History / Settings
    ├── @ 项目文件 / HTML5 文件或 IDEA 项目树原生拖放 → bounded attachment intake
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

WebView 桥接中的每条命令都携带 schema、宿主页代次、前端页面实例 ID 和 request ID。只有当前页面实例通过 `frontend.ready` 握手后才能调用白名单命令；页面重载前的迟到点击会失败关闭。JBCef JavaScript 查询回调可能运行在 Chromium/remote-JCEF 线程，宿主先切换到 IDE EDT 再执行白名单命令，并在排队后重新验证页面代次和客户端身份，避免 Swing/IDE API 线程违规及重载竞态。宿主对同步接收成功返回 `command.accepted`，对校验或执行异常返回不包含请求 payload 的 `command.error`，前端在 8 秒未收到任一普通命令回执时显示可恢复错误，避免设置、MCP 或新会话按钮静默失效。IDEA 2026 remote JCEF 可能先载入文档、后建立 JS-query transport：宿主在当前页面代次内前 5 秒每 100ms 幂等重注入桥接，随后降到 1 秒；前端只对无副作用的 `frontend.ready` 保留 60 秒发现/回执重试，绝不自动重放设置、工具或任务命令。Native → JCEF 输出统一进入单消费者有界队列：保持入队顺序、合并设置/历史/运行状态刷新、批量执行 JavaScript，并为终态与命令回执保留不可丢弃容量，避免多个 `invokeLater` 互相超车后留下永久 spinner。ready watchdog 为 remote JCEF 保留 45 秒冷启动窗口：首次失败只重载页面，第二次重建 JCEF 并更换宿主页代次，仍失败才显示原生恢复面板；Project Service 中的会话、任务和检查点不随渲染器重建而销毁。页面仍使用 `default-src 'none'` CSP，宿主消息和待发送队列均有大小及数量上限。前端时间线 reducer 在进入 React state 前验证完整路由信封，并以 `sessionId + turnId + sequence` 维护游标；重复、乱序、终态后的迟到事件被拒绝，持久化快照只能补齐缺失块，不能覆盖序号更高的实时块。宿主明确报告会话不在运行时，重载和切页恢复会按同一 turn 的终态收敛遗留运行阶段：成功终态不误标失败，缺少终态的孤儿阶段则显示停止提示，不能继续转圈。

`session.send` 必须携带目标会话 ID；宿主在构造乐观消息和启动 Agent 前与当前已加载会话核对，不一致时返回 `send.rejected`，不把消息发到另一个会话。前端待发送队列每会话最多 20 条，只在成功终态和会话加载确认后继续；失败、发送被拒绝或用户停止当前任务时暂停该会话队列，不删除仍待发送的消息。被拒绝的消息重新标为待发送，用户检查后可显式重试，草稿达到队列上限时不会丢失。宿主另以项目级内存保存每个会话尚未被运行接受的纯文本队列，只有 `startRun` 接受后才按消息 ID 移除；JCEF 重建后以暂停状态恢复，由用户手动继续，绝不自动重放。附件字节和本地路径不进入宿主队列，相关消息重载后需回到输入框重新选取附件。队列不跨 IDE 重启持久化，也不代替会话历史。

终态事件携带有界 workflow ID，用于将本轮结果卡与对应的文件审阅记录关联；前端只统计同一 turn 已收到的可见回复和工具终态，以及同一 workflow 的变更文件。结果卡将可见回复、关联文件和结构化验证分开呈现；文件入口复用宿主导航校验，“审阅变更”只打开对应 workflow 的现有审阅面板，“草拟验证任务”只预填输入框，不擅自运行命令或发送模型请求。数据库与 MCP 的“实际调用”数量仅从同一 turn 的 `tool.completed` 事件按工具名统计，暴露能力或已保存设置不计入成功。`run_command` 仅对直接 argv 中可识别的 test/check/verify 命令附带固定类别、真实退出码和超时标志；不解析 shell 字符串，不传原始参数到摘要，不把模型自述或任意 stdout 当作验证证据。成功终态在 UI 称为“本轮运行结束”，不等同于用户任务已通过验收；即使验证命令退出码为 0，也不能推断完整测试集或用户需求均已通过。

用户显式触发的数据库与 MCP 连接测试只在项目内存中保存最近十分钟、配置指纹匹配的成功布尔值；指纹不包含明文凭据，配置或认证变更立即清除证据。任务工具目录分别报告“最近测试通过”和“本轮实际连接工具”，前者不提升后者状态，也不绕过查询审批或实时连接。任务终态仅在有结构化验证证据时列出验证结果；没有命令调用时标注“验证命令未执行”，调用过命令但没有可判定证据时明确标注“未记录可判定的验证结果”。终态只能结束遗留进度展示，不能把缺少独立完成事件的工具或子代理自动标记为成功；这种操作显示“未报告完成状态”，也不进入成功计数。
设置快照也只暴露每个已保存连接的最近测试布尔值，供 WebView 重建后恢复提示；本地正在编辑但尚未保存的草案不会继承该提示。失败原因、Endpoint 和密码不进入测试缓存。10 分钟证据仅证明先前一次用户触发的探测，不证明当前会话已连接或代理已调用工具。

设置快照不得在 EDT 同步查询 PasswordSafe：供应商、MCP 与数据库只向 WebView 暴露内存中短时缓存的“凭据是否已配置”布尔状态，由后台 IO 刷新；凭据写入同样在后台完成，回调必须重新验证宿主页代次和页面实例。凭据值从不进入缓存快照、日志或 WebView。

Composer 的模型菜单、模型收藏、引用草稿和权限选择都是本地交互状态：模型收藏按供应商保存在 WebView 本地偏好中，不进入模型上下文；引用只会在用户发送后成为普通可见文本。用户可以把不常用供应商从聊天选择器隐藏，供应商配置、凭据和设置页入口不会被删除，当前活动供应商始终保持可见。所有 Composer 菜单在打开和工具窗口缩放时重新计算固定视口位置，向上展开并同时约束四边，避免 JCEF 原生下拉层在窄侧栏或底部停靠时被裁切；IME composition 与其紧邻的提交键事件单独隔离，中文/日文候选提交不会误发任务。权限变更通过白名单桥接写入平台设置，`run_command` 在每次准备命令前重新读取当前沙箱模式，因此只影响尚未开始的命令；已启动进程不会被静默提升权限。`Claude Plan` 无论界面选择如何仍固定使用 `workspace-write` 和只读命令验证。切换到 `danger-full-access` 必须经过原生确认对话框，且不会绕过工具审批、argv 校验、审计或检查点。

任务终态可投递 JetBrains 原生通知，用户可在常规设置中持久关闭。通知正文只使用经过敏感数据脱敏和字符数限制的结果首行，不包含 Prompt、工具参数、命令输出、附件内容或凭据；WebView Toast 与系统通知是两条独立通道，关闭系统通知不影响任务状态落盘和对话内结果。

运行所有权按 conversation ID 隔离：同一会话一次只允许一个 turn，但新会话不会被其他会话的后台任务阻塞。Project Service 分别保存每个活动会话的 Job、run ID、回调、初始消息、模式和策略；取消、超时后 UI 隔离、终态持久化和运行状态通知都只作用于目标会话。聊天顶端最多保留八个可横向滚动的打开会话标签；关闭标签不停止后台任务，运行点和历史入口可直接切回。WebView 即使当前展示另一会话也继续按 session ID 归并后台事件，切回时先恢复 bounded live timeline，再复用实时块树，且后台终态不会清除当前会话的运行状态。历史消息按 60 条从尾部加载，用户显式请求时再向前分页；块 ID 使用绝对消息下标，因此较早页面只能补齐、不能覆盖实时块。完成后的消息始终写入原会话，即使它当时不在前台。输入草稿同样按 conversation ID 隔离，但只存在当前 WebView 内存中：页面/IDE 重启后不会持久化附件二进制，用户需重新选择附件。会话标题只保存最多 100 个字符，不含模型上下文或凭据，可在顶栏修改并与同项目历史记录同步。

已完成的对话回合保留一个有界的 `RecoverableSubmission` 快照（任务文本、模式、协作策略和附件引用），仅用于用户主动点击“重试”或“编辑重试”。快照不包含 API 凭据、完整仓库或二进制内容；重试仍重新进入发送、模型能力检查、审批、沙箱和检查点路径。输入框已有草稿时只恢复到编辑态，不会静默覆盖用户内容。

## Free distribution and legacy entitlement migration

3.0 保留的功能全部免费，`plugin.xml` 不声明 Marketplace 产品，也没有试用、购买、续费或许可证门槛。旧商业校验实现不进入 3.0 产物；`uiSchemaVersion=6` 的幂等迁移保留供应商、CLI、MCP、数据库连接元数据、Skills、提示词、权限和用量配置，并增加不含凭据的会话模板、MCP 引擎作用域和本机语言/字体/字号偏好，同时只按固定 PasswordSafe service/key 删除旧许可证值，并由一个不解释内容的 migration-only 持久化组件清空旧实验室状态。迁移不删除用户项目中的源码或已导出文件。数据库凭据与 API/MCP 凭据一样只保留在 PasswordSafe，不进入迁移状态。恢复历史会话时只读取同一项目指纹下的记录；跨项目记录即使 ID 被获知也会拒绝恢复。聊天消息和有界、脱敏的 workflow 可靠性账本共同重建时间线，供应商原始事件、隐藏推理、凭据与原始命令输出不会进入 WebView。

真实桌面 UI 流程在独立 Remote Robot 作业中打开仓库内无执行配置的固定 fixture 项目，通过 Tool Window 按钮打开 OmniCode，再依次点击历史、设置、新对话的原生标题动作；前端只在真正渲染新视图后回报有界页面标识，宿主通过无会话内容的 accessibility name 暴露给 Robot 断言，并保存整屏证据。这个握手不能证明 CLI、审批或 MCP 的端到端执行；这些流程仍需独立真实验收。React 层另用固定数据和 Chromium 覆盖 CLI 原生历史列出/导入桥接、输入菜单与固定截图，以及 320/480/800 宽度、深浅主题和 100%/125%/150% 缩放；普通单元测试保持无桌面依赖。

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

任务启动、继续和恢复列表读取前，宿主自动核对有界工具执行审计，修复“工具已完成落盘，但后续 checkpoint 未更新”的窗口。每次实际执行生成独立 execution ID，贯穿请求、审计和 pendingTool；工具完成事件用结构化 outcomeKnown 区分明确结果与异常、取消、超时等未知结果。核对要求项目、workflow、run、agent、conversation、工具及调用 ID 完全匹配，并验证执行 ID、审批和时间顺序；旧版 run:call ID 仅在完整作用域内接受明确完成记录，旧版失败及文本自称成功不能作为证明。最新记录存在冲突、证据丢失或未知时仍保持限制。更新在文件锁下比较完整快照，只清除已确认的 pending 状态并补回有界 observation，保留任务、历史和预算，不执行工具或复用审批。

能自动核实的任务不显示恢复操作，也不要求用户管理 checkpoint。仍有未知结果时，聊天的“本轮工具能力”卡片显示“上次操作待确认”和“查看待确认操作”入口，不随工具明细折叠。模型继续使用可用的只读工具；仅当后续动作确实需要被限制的工具时，简短说明需核实的具体操作，沿用对话已有的服务器、namespace、工作负载和时间范围。宿主确认界面展示任务、工具与更新时间；项目运行结束且用户核实后才按版本匹配处理该记录，不自动重放任务，也不放宽真正未知副作用的只读限制。

高频 workflow checkpoint 使用追加式 latest-wins 记录，每次追加仍执行 `fsync`；读取按 workflow ID 解析最新有效记录，达到有界版本数时原子压缩为每个 workflow 一条，下一次追加会越过文件字节上限时则先原子发布最新集合。恢复快照只保留有工具交换语义的 96 KiB / 160 条消息切片，完整对话仍只在内存和历史记录中保留；当最新替换明显变小时会立即压缩旧版本，避免大仓库任务把每个 Provider 边界变成数百 KiB 的重复序列化。内存只保留最多 8 条且合计不超过 8 MiB 的热记录；包含“危险工具已开始但结果未知”的恢复点受保护，容量不足时写入失败关闭而不会静默淘汰该证据。存储文件拒绝符号链接。这样避免在每个 Provider/工具边界全量重写整个 checkpoint 文件，同时保留崩溃恢复、旧时间戳拒绝、跨进程文件锁、损坏尾行清理和危险副作用前同步落盘语义。

每个 workflow 同时写入独立的有界可靠性 ledger：阶段开始/完成、模型请求、供应商重试、工具失败、状态和恢复点都带 workflow/run/agent ID、迭代、尝试次数、耗时与脱敏详情。状态/诊断事件通过有界单写入队列异步落盘，避免每个流式事件都在模型协程上执行 `fsync`；队列满时可丢弃状态提示，但失败、重试和阶段记录仍走独立 IO 兜底。任务中心按 workflow 聚合这条 ledger，直接显示最近仍未完成的阶段、阶段累计耗时、模型请求数、工具失败数、重试数和最近事件，即使聊天面板已关闭也能定位“卡在哪里”；失败或中断任务保留 checkpoint，继续动作复用已有证据并从恢复指令开始，不重复已确认完成的整段任务。

用户可见错误由稳定分类器映射为认证、权限、限流、网络超时、网络、模型能力、有限模式边界、沙箱、配置、取消或未知错误，并在当前对话中提供配置 Provider、切换模型、诊断、取消和编辑重试等动作。网络超时/连接失败不会自动重发未知请求。用户显式停止会协作取消模型、工具和 MCP，UI 立即显示“正在清理”；若底层连接超过取消宽限期仍未收尾，Project Service 隔离迟到事件，但保留该会话的 run 占用直到 Job 实际完成。清理期间同一会话拒绝新的运行、删除或审阅变更；其他会话仍可使用。本地 CLI 使用可取消的 stdout 轮询；文本事件立即流式显示，原始 stderr、提示词与工具参数不暴露给 UI。一次性 CLI 从进程创建后的首个可挂起操作起就进入进程树回收作用域，包括关闭 stdin 和发送首条进度；首条进度回调被取消也不能留下孤儿进程。进程启动/首个输出与总请求分别受限：完全没有 stdout 或协议进度的 CLI 会在首输出期限停止，OpenCode 已确认进入上游模型请求后则允许继续排队到总请求期限；两类超时都不会自动重放，避免重复费用。所有 CLI 都从规范化项目根目录启动，发现、版本探测和运行共用受控 PATH，并解析 Node/npm、pnpm/yarn/Bun、Hermes、nvm/fnm/nvmd、uv/uvx、OMP 及 Windows launcher/WinGet 路径；找不到必需解释器时显示对应操作系统的修复动作而不是把 launcher 误报为可运行。上下文窗口溢出会引导精简上下文；可恢复的流中断只续接已展示的有界文本尾部，不重放未知副作用。JCEF 只渲染统一事件信封，流式文本批量合并并保持与工具/状态事件顺序；长会话使用有界列表和虚拟滚动，不在 EDT 高频重建组件。Markdown 代码块使用本地语法高亮，公式与有界 Mermaid 图表只在 WebView 内渲染，不加载远程脚本；分类文本不复制可能包含凭据、Prompt 或本地路径的原始 Provider body。

OpenCode 使用用户原生配置、认证、模型缓存和会话数据库，但运行路径与 CCGUI 保持一致：每个 turn 直接启动 `opencode run --format json`，显式模型通过 `--model` 传递，恢复时只追加经过格式校验的 opaque `--session` ID。插件不会在 Prompt 前启动第二个 `opencode serve`、health probe 或 SSE 连接，因此本地 Host 初始化不会占用首字延迟。stdout 由独立读取线程通过可取消的有界 Channel 交付；消费者较慢时施加背压而不丢弃输出，取消会唤醒等待的读取线程；JSONL 只合并当前进程观察到的会话 ID、文本、usage、工具/权限提示和终态，完整响应、stderr、Prompt 与凭据不进入 UI 或持久化。`session.status=idle`、step-finish、message_end 等协议终态只在有可见输出或终态宽限后收口，避免尾部文本丢失；进程正常退出也会刷新残余行；非零退出即使已有部分文本也按失败处理，空答案且无已捕获工具调用不得标为完成。静默期间显示可取消的初始化、上游排队或模型处理心跳，并由供应商总请求时限兜底，不再使用 15/30/45 秒硬初始化截止。用户取消、超时、输出溢出和协议失败都会关闭 stdout/stderr 并回收完整进程树；未知请求不会自动重放，以避免重复费用。Plan、Claude Plan 与 Research 的 SYSTEM 策略随当前 turn 注入，本地 CLI 自身提出权限或交互问题时 fail closed，不能绕过 OmniCode 的工具审批。

DSH 使用独立的回环 Host RPC 适配器，而不是一次性 stdout 适配器。只接受 `http://127.0.0.1`、`localhost` 或 `::1`，固定 argv 启动 `dsh web` 前必须经过用户审批；随后通过 `workspace.create` 将会话绑定到规范化项目根目录，创建或恢复 opaque session，按 `provider/model` 选择模型，并在 `session.prompt` 之前完成 `/api/events.mux` 订阅。所有帧严格按 session ID 过滤，以 turn/goal 终态收口；取消会调用 `session.cancel`，传输故障保留 session。Host 发出的工具授权请求逐次进入 OmniCode `ApprovalGate`，未提供审批上下文时一律拒绝；当前无法表达的交互式问题返回空答案并显示明确状态，避免 Host 永久等待。RPC、事件、模型目录、错误文本和可见输出均有独立大小上限，远程 DSH Host 不受支持。

本地 CLI 的 npm 启动器在 macOS/Linux 上会直接调用自动解析出的 Node 解释器，避免 Finder、Toolbox 或 Windows 对应启动入口与用户终端具有不同 PATH 时出现 `env: node`。CLI 已在规范化项目根目录运行，因此只传递有界的当前 SYSTEM 运行策略以及至多 8 条、12,000 字符的可见对话；Harness 清单和 OmniCode 工具记录不会再次传入并拖慢首个输出。SYSTEM 策略每轮重新生成并包含冻结后的 Agent/Plan/Claude Plan/Research 模式，避免本地引擎只收到用户文本而丢失安全边界。用户保存的 CLI Key 只会写入所选模型供应商的环境变量名（例如 Pi 的 `openai/...` 使用 `OPENAI_API_KEY`），退出时只按有界 stderr 分类登录、模型权限、地区和运行时问题，不持久化或展示原始 stderr。

依赖页在首次 WebView 握手后自动对各 CLI 运行一次只读版本探测，并分别展示可执行文件、运行依赖、认证、模型目录和真实回复的证据状态；未做过的检查显示“未验证”，绝不把版本命令成功当成完整可用。CLI 启动器解析失败会返回“缺少运行依赖”而非吞掉状态事件，版本命令超时则回收子进程树。CLI 就绪状态分为版本探测、只读模型目录检查和真实会话验证。版本探测只证明可执行文件及解释器能启动；用户可逐个检查模型目录，这不会切换当前供应商、发送模型提示词或产生推理费用。模型目录可读取也不证明登录或指定模型具有会话权限，只有用户发起的真实请求才能验证完整链路。选择已安装引擎只激活它原有的独立 profile，不会把模型、推理等级或凭据覆盖成 `default`；手填模型不冒充已验证目录，空目录明确报错。

新会话欢迎区可直达依赖检查；工具能力卡中的数据库与 MCP 配置动作分别直达对应设置页。桥接层只接受白名单设置页标识，不能通过 WebView 导航命令触发任意宿主动作。

聊天顶栏在没有任务执行时显示“空闲”，只描述当前会话的运行占用，不再使用可能被误解为 CLI/模型已验证的“就绪”。

项目服务创建后会在后台预热一次有界、只读的规则、Harness 与固定文件上下文快照；这不会执行 Harness argv，也不会读取凭据。连续对话会在 15 秒内复用一次快照，并按本轮上下文预算截断；TTL 到期后重新读取项目数据。首轮只等待最多 1.2 秒的软预算，且自动上下文按推理档位限制在 24–96 KiB；冷仓库扫描超时就先发送模型请求，预热结果留在后台供后续轮次复用。MCP 初次连接按推理档位只等待 1.5–5 秒，超时不会阻塞模型请求；预热失败不会阻断聊天。新任务的首个恢复点使用“仅首次写入”并行落盘，若运行时检查点先到达则保留运行时快照；恢复任务仍先同步重建安全基线。

任务中心支持将脱敏 checkpoint 以 PBKDF2 + AES-GCM 加密为 `.omnitask`，跨设备导入生成新的 workflow/run ID 并强制进入 `INTERRUPTED`，避免覆盖本地任务或自动执行未知副作用。可选云端客户端只向用户自建 HTTPS relay 上传密文；插件不提供托管账号、无人值守后台或默认云同步。

## Reasoning controls

聊天界面不显示模型内部思维链：Provider 仅将可见回复、工具与运行状态送入事件管线，WebView 还会拒绝私有推理事件。可见回复默认按增量流式显示；用户可在「设置 → 常规 → 回答显示」关闭流式呈现，改为本轮终态后一次性显示。此开关只改变展示方式，事件仍实时接收以维持取消、状态和会话恢复，不改变模型推理强度或工具审批。

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

存在未解除的未知副作用时，Harness 预检状态为 `DEGRADED_READ_ONLY`，任意命令、修改、数据库和 MCP schema 不再暴露给模型；执行层的恢复门禁仍作为第二道独立保护。Agent 模式允许宿主内置的固定远程诊断实例继续经审批读取，网络授权与可能修改业务数据不再共用一个恢复判定。该能力按 ToolRegistry 中的对象身份授予，不能靠工具名称、描述、项目配置或第三方工具自报获得。侧栏分数不代表命令或连接已成功。

`inspect_remote_kubernetes` 只支持 SSH 连接检查、指定 namespace 的 Pod 列表和单个 Pod 的有界日志读取；不接受任意命令、脚本、exec、文件写入、删除、部署、用户自定义 SSH 参数或私钥内容。通过现有 `run_command` argv、审批、可执行文件身份验证、沙箱、超时及有界输出执行，仍标记 dangerous + EXTERNAL，仅 Agent 可用。参数先按白名单验证，远端 shell 字符串逐参数引用；SSH 禁用用户/系统配置、Match exec、代理命令、转发、连接复用和交互输入，保持严格主机密钥校验且不更新 known_hosts。已有 SSH agent socket 只在该宿主入口、SSH 可执行文件和用户已选择完全访问时传入，不向模型读取私钥；网络受限时明确返回 `SSH_NETWORK_BLOCKED`，不自动提升权限。连接依赖真实网络、已信任的主机密钥和已有密钥/agent 认证，不自动安装软件、注册主机信任或填入密码。实现参考 [OpenSSH](https://man.openbsd.org/ssh) 与 [kubectl logs](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_logs/) 参数契约。

该专用入口执行、拒绝、失败或取消时均保留旧写操作的 pendingTool，包括审批后的关键落盘边界；读操作不能证明旧写操作完成，也不以读失败制造新的未知写操作。执行器仍逐次返回真实结果，模型不得仅凭工具目录宣称已登录或已读日志。日志在返回模型前经过现有敏感字段脱敏及 Cookie 头过滤；审计和 checkpoint 继续使用有界脱敏存储。工具同时进入结构化 API、CLI 与原生宿主桥接，界面按本轮实际暴露的工具显示远程读取能力。

工具可用性卡片使用自动核对后的恢复降级过滤条件，不能仅按界面选择的 Agent 模式宣称命令、数据库或 MCP 已开放。每轮 SYSTEM 上下文说明实际工具能力，要求后台处理恢复细节，不把 checkpoint 术语和清理工作转交用户，也不能把限制解释成未安装 SSH/kubectl 或 API 不支持执行。

降级卡片的“查看待确认操作”入口仅处理自动核对后仍无法确定结果的操作：宿主重新查询当前项目，原生对话框展示具体任务和工具，仅在用户确认已核实状态后处理该记录。页面不能指定待删除的检查点；项目有活动任务时拒绝操作，存储层以 workflow/run/更新时间进行原子校验，过期确认不能删除已变化的检查点。操作不回滚副作用、不自动重试任务，也不批量删除其他恢复记录。

`run_command` 在解析可执行文件时确认文件不存在，且尚未激活沙箱或启动进程，会返回 `COMMAND_NOT_STARTED` 错误结果。即使外层 `browser_automation` 已获批，该确定的启动前失败也不会产生未知副作用恢复点；浏览器截图目录若已创建，其完成的目录创建不被回滚。进程启动边界及之后抛出的异常、取消、超时仍使用 fail-closed 恢复保护；命令返回的超时或未知退出状态也通过结构化 sideEffectStateUnknown 保留限制，终止本地进程不等于证明远程操作已结束。自动核对不根据错误文本推断安全。

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

`/map` 仅在用户显式触发时遍历 IntelliJ 内容索引中的路径元数据，按固定文件数、目录数、语言数和入口数上限聚合项目地图；它不读取正文、不执行 Harness，也不扫描敏感文件名。项目记忆由用户维护，保存前经过通用敏感数据脱敏并限制长度，在模型上下文中明确标记为不可信事实而非策略。`Cmd/Ctrl+K` 全局搜索只查询已载入的有界 UI 数据，并通过现有安全文件跳转桥接打开结果。会话模板只保存路由偏好，不保存凭据、附件、Prompt 或项目内容；应用时仍受当前审批、沙箱和 Harness 边界约束。

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

主题只改变 OmniCode Tool Window 的 React 工作台表面，不修改 JetBrains 全局 Look and Feel。语言、字体族和 11–20px 字号同样只保存为经过白名单/范围校验的本机外观偏好；`auto` 语言使用宿主系统 locale，未知值回退为安全默认。桌宠由插件内编译的原创 SVG 图元绘制，目录只传递受信任的视觉/动作枚举、配色和有界提示文案；WebView 不加载远程角色、脚本或社区资源。设置页角色画廊可本地预览待机、工作和完成状态，聊天中的角色支持眨眼、挥手、完成庆祝、空闲气泡、拖拽贴边与 `prefers-reduced-motion`。它只消费已有运行状态，不获得 Agent 工具、不触发模型调用，也不会使任务脱离 Project Service 生命周期在后台继续；进入历史或设置后立即卸载聊天投影，但保留规范化位置。

## Side effects

`apply_patch` 与 `apply_change` 都要求 `read_file` 返回的 SHA-256，审批前后各校验一次，并在 `WriteCommandAction` 内写入。精确 Patch 的每段旧上下文必须唯一匹配，歧义或过期内容会 fail closed；这两个工具仅 Agent 可见，直接以 Plan/Research 调用也会拒绝。`run_command` 使用 `GeneralCommandLine(List<String>)`，不拼接 shell 字符串，工作目录必须在项目内，仅 Agent/Research 可见且逐次审批。

默认 `workspace-write` 会先选择平台后端并执行真实能力探测。macOS 使用 `sandbox-exec` profile；Linux 使用 `bubblewrap` 的 mount/user/network namespace：宿主根只读、用户目录以私有 tmpfs 隐藏、工作区重新读写挂载、HOME/tmp 为进程私有目录，网络 namespace 仅保留 loopback 视图。Windows 使用随插件分发且经 SHA-256/签名门禁的 native `omnicode-appcontainer-host.exe`：helper 通过 `CreateAppContainerProfile` 和 `PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES` 创建无 network capability 的 per-run token，在有界 ACL 事务中授予工作区 SID，拒绝重解析点/超大目录；子进程通过 `PROC_THREAD_ATTRIBUTE_JOB_LIST` 在创建时加入 kill-on-close Job Object，并在 suspended 状态验证归属后才恢复执行，同时通过 `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` 只继承标准流或 NUL 句柄。进程树结束后恢复原始 DACL；授予、作业关联或清理失败都会返回非零并保持 fail closed。探测会验证后端身份、profile/stdio 启动能力和稳定性，任一失败都不降级。Windows helper 不存在时仍可通过 JetBrains WSL/Remote Development 在 Linux 后端运行。`danger-full-access` 是显式用户设置；它移除 OS 级隔离，但不移除审批、argv 直执行、环境清理、超时和输出边界。

## Extension boundary

每轮在实际 `ToolRegistry`、模式、CLI 桥接和 MCP 连接结果确定后，向会话时间线发送有界的“本轮工具能力”快照。它区分宿主已准备的工具、仍需审批、缺少数据库凭据和当前引擎不可桥接的 MCP。Agent 模式下，Codex App Server 的新 thread 可注册当前轮次的有界动态宿主工具，恢复旧 thread 保留文本回退；DSH 使用有界文本桥接；其他本地 CLI 使用受限宿主桥接。结构化工具引擎和能消费有界宿主工具的 CLI 都会连接适用当前作用域的 OmniCode MCP，不为无法消费这些工具的引擎启动服务。数据库只读取 PasswordSafe 中“是否存在密码”的布尔值，不传输 URL、用户名、密码或原始连接错误。此快照不是工具调用成功证明：数据库网络连接、模型对工具的实际选择、MCP 授权及危险操作审批仍在执行时重新验证。脱敏摘要进入 workflow 账本，以便切换页面后恢复；不得用“已保存配置”冒充“当前会话可调用”。

MCP stdio 启动器在不执行 Shell、也不读取项目配置的前提下合并 IDE 环境 PATH、已解析可执行文件的父目录和受限的跨平台用户运行时目录（nvm/fnm、Volta、asdf/mise、pyenv/rye、npm/pnpm/yarn/bun 以及 Windows Node/npm 目录），并在 Windows 上按 PATHEXT 规则识别 `.exe`、`.cmd` 和 `.bat` 入口。这样从 Finder/Toolbox 启动 IntelliJ 时，已安装的 `node`、`npx` 或 `uvx` 不会因为 GUI PATH 不完整而被误报为缺失；对于位于用户目录的解释器，MCP 计划还会把经过规范化的运行时目录以只读方式重新暴露给 macOS sandbox-exec / Linux bubblewrap，避免 `#!/usr/bin/env node` 在沙箱内变成 `No such file or directory`。该补全不放宽现有审批、路径复核或沙箱边界，任意 PATH 条目不会被挂载。

MCP Server 仅在 Agent 模式可通过已配置的 stdio 进程或 2025-11-25 Streamable HTTP 接入。每个配置显式属于通用、Claude Code 或 Codex 作用域；当前 Provider 只连接通用与自身作用域，切换引擎不会把专属服务注入其他 Provider。stdio 初始化、工具发现和调用使用有界 JSON-RPC 行协议；进程启动前解析真实可执行文件和沙箱计划，并按服务器、项目、参数、工作目录、沙箱、环境变量名、可执行文件内容和后端身份生成指纹。HTTP 使用 JSON/SSE、有界响应、Session/Protocol headers、404 会话重建和关闭 DELETE；远程强制 HTTPS、禁止重定向，并明确绕过代理访问 loopback。OAuth 层解析 401/403 Bearer challenge，按 RFC 9728 发现受保护资源，再按 RFC 8414/OIDC 发现授权服务器；配置页只在用户确认联网后执行发现，challenge 的 resource metadata URL 必须与 MCP resource 同源，authorization-server metadata URL 只从校验后的 HTTPS issuer 派生，所有响应、URL、数组与 Scope 均有界。发现预览展示授权/Token/注册端点和客户端注册能力，仅在 Scope 为空时填充，不把远端端点持久化为信任配置；登录和刷新会重新发现并校验。无 Client ID 时优先 RFC 7591 动态注册，不可用时在打开浏览器前提示用户填入服务商 Client ID。OAuth 强制 PKCE S256、state 和 resource audience，支持公开客户端/动态注册、过期刷新及 401 单次刷新重试。OAuth 会话以规范化 Endpoint、认证模式、配置 Client ID 和排序 Scope 生成绑定指纹；跨 manager 的登录/刷新按 Server ID 单飞，logout 与永久 token 错误通过 generation 使所有在途结果失效。Bearer、OAuth Token 和动态客户端凭据均只从 PasswordSafe 读取。两种连接首次或指纹变化后都重新审批，每个 MCP tool 均标记为 dangerous 并逐次审批。Skill 来源只在用户配置的目录中发现 `SKILL.md`，由 `list_skills` / `load_skill` 按需加载，不会自动注入完整技能库。

远程数据库是独立的受控外部工具边界，不依赖本机数据库服务。设置只持久化连接 ID、名称、数据库类型、规范化网络 JDBC URL、用户名和超时/行数策略；输入层也接受 `postgresql://`、`postgres://`、`mysql://` 和 `mariadb://` 常用形式并在保存时规范化。数据库名称可选：MySQL/MariaDB 可建立服务器级连接，PostgreSQL 无路径 URL 会被规范化为驱动可接受的末尾 `/`。“保存配置并设置密码”使用 JetBrains 原生密码对话框，密码按连接 ID 与精确 URL 摘要绑定到 PasswordSafe，写入后立即回读确认；Endpoint 修改后旧密码不会被复用。只分发受审阅的 PostgreSQL 与 MariaDB JDBC 驱动，后者同时支持显式 MySQL URL；不加载项目提供的任意 JDBC 驱动。URL 必须包含网络主机，禁止 user-info、密码/Token/API Key 参数、fragment、文件型连接，以及可加载自定义 Socket/认证/日志/属性转换类或切换本地 Socket 的参数。`query_database` 标记为 dangerous + EXTERNAL，因此只在 Agent 模式可见且每条 SQL 都经原生审批；只有一个启用连接时可省略 ID，多个连接时需使用设置中的名称或 ID，连接目录不会作为普通只读工具暴露给 Plan。执行层仅接受一条 SELECT/SHOW/DESCRIBE/DESC，拒绝注释、批处理和 INTO，设置 JDBC read-only 与只读事务，并限制连接/查询超时、返回行列和文本大小。JDBC 阻塞调用通过可中断 IO 边界运行；重复测试会取消并取代上一任务，面板关闭或 Agent 取消也不会接受迟到结果。探测连接可以在启用前进行，但不会执行用户 SQL；查询结果作为有界 observation 进入当前对话和本地恢复检查点，凭据不会持久化到任务数据。

一次性本地 CLI 不直接理解 provider-neutral `ModelRequest.tools`。OpenCode 每个 **Agent** turn 会获得一个随机名称和随机高熵路径的临时 loopback Streamable HTTP MCP：CLI Provider 通过 `OPENCODE_CONFIG_CONTENT` 将它注册到当前子进程，桥接同时实现 OpenCode 发现工具所需的 JSON-RPC POST 与长连接 SSE GET，并只暴露本轮 AgentEngine 已注册的有界工具：内置的 `apply_patch`、`apply_change`、`run_command`、`query_database`、`inspect_remote_kubernetes`，以及已启用、适用当前引擎作用域且成功连接的 MCP 工具（合计最多 64 项）。目录超出上限时，保留宿主内置工具，并仅根据当前用户文本与已注册 MCP 工具名称优先放入相关服务器工具；设置页能力卡与实际桥接使用相同选择规则，MCP 描述不会改变选择或授权。设置页测试发现工具不会自动启用或注入聊天；每轮须重新连接，连接失败时不得宣称模型可调用。MCP handler 自身不执行文件、命令或 SQL；它只捕获本轮第一个通过名称、参数键、必填字段和 schema 类型校验的调用，随后立即结束当前 OpenCode 模型步并转回正常 AgentEngine `ToolCall` 路径。审批、审计、超时、检查点、PasswordSafe 凭据读取、项目路径保护和进程沙箱都仍由 IDE 完成，OpenCode 不会收到密码。其他一次性 CLI 使用同一有界工具列表的严格标记回退，同样由 AgentEngine 执行；Plan、Claude Plan 与 Research 仍只获得其模式允许的工具表面，OpenCode 不启动本地 MCP。Git 与浏览器工作流尚不在该桥接白名单内。MCP 仅绑定 `127.0.0.1`、校验 Host/Content-Type/请求大小、限制单个事件流和固定线程数，并在 turn 结束、超时或取消时连同 CLI 进程一起关闭。

其他仍以一次性 headless 进程运行的 CLI 使用严格标记、单次、纯 JSON 的兼容桥接作为降级路径；Codex 项目任务优先使用原生 App Server 结构化工具面。所有路径都只接受当前 Agent 模式真实提供的白名单工具，未知、畸形或重复调用 fail closed；结果以有界 `ToolResult` 回送同一会话后再生成最终回答，私有兼容标记不会出现在流式聊天中。Plan、Claude Plan 与 Research 仍不会获得该 EXTERNAL 工具。

3.0 的 JCEF 设置页只传递 `bearerConfigured`、`oauthConfigured` 和 `oauthUsable` 等布尔状态。Bearer 输入使用原生密码对话框并直接写入 PasswordSafe；OAuth 发现先经用户确认，登录使用系统浏览器和原生完成确认。Token、动态客户端密钥及原始认证响应不会进入 JavaScript、WebView state、历史或事件信封。

同一任务的独立 MCP 初始化与工具发现最多四路并行，结果和名称冲突仍按用户配置顺序合并；首次连接审批按顺序显示，但已信任连接仍可并发。启动预算按会话推理等级在 10–25 秒之间；到期只取消未完成的服务，保留已完成的工具和客户端，并逐个报告超时，避免一个慢服务清空全部引擎的工具目录。审批使用可取消对话框，任务或项目取消会拒绝并关闭仍显示的授权界面。每台服务器继续独立执行原有信任、审批、沙箱、凭据和失败隔离。取消发现会在 IO dispatcher 并行关闭所有已经建立的客户端，调用方从获取阶段起即用 `finally` 接管资源；任务结束时独立 HTTP/stdio 会话并行关闭，避免离线服务器把首字延迟和收尾延迟线性叠加。

TokenTracker 是完全可选的第三方 companion，而不是 Agent 运行依赖。使用统计页只在绝对 PATH 和少量固定系统目录中发现可执行文件，不执行它；服务探测与聚合数据读取固定使用 `http://127.0.0.1:7680/`、绕过代理、禁止重定向并限制连接时间、响应时间和响应字节。验证通过后，插件从 TokenTracker 的只读 `/functions/tokentracker-*` 接口读取最近 30 天 summary、daily、model 和 project 聚合，在 OmniCode 自己的 React 设置页渲染指标、趋势和排行，不加载第三方 iframe 或第二个 JCEF 窗口。64 位计数以字符串跨 JVM/JCEF 边界，前端数组和标签继续二次限界；项目仓库 URL 被 JVM 丢弃，只传末级项目名。用户点击“安装并启动”后必须先在 JetBrains 原生确认框批准固定 argv `npx --yes tokentracker-cli`；插件不经 shell 启动、设置关闭遥测的环境变量、等待有界的 loopback 就绪时间，并且只允许停止由当前面板启动的进程树。该动作不暴露给 Agent，也不从项目或 Harness 读取命令。插件不直接读取 TokenTracker 数据库、不请求或传递本地认证 Token、不调用变更接口、不共享 API Key，也不接管其 hooks、更新或云同步。完整 TokenTracker 功能可显式在系统浏览器打开固定 `/dashboard` 路由。

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
Claude、Codex、Gemini、Grok、Kimi、OpenCode、Pi、OMP 或 DSH 执行 DNS/TLS 探测，也不会用模型名启发式
误判工具能力；这些检查显示为跳过，CLI 可执行文件由设置页的依赖诊断单独确认。远程 API 仍按真实 Base URL 执行有界的
DNS 与 TLS/HTTP 检查，并在界面将失败原因和恢复建议本地化。

本地 CLI 统一声明其会话连续性：`BOUNDED_REPLAY` 只重放有界可见历史，
`NATIVE_SESSION_ID` 使用 CLI 自身的 session/thread ID 恢复，`PERSISTENT_HOST` 复用受控宿主。
Claude Code、Codex、Gemini、Kimi、OpenCode、Pi 与 OMP 采用原生 ID 恢复；Grok 仅做有界重放，DSH
由持久宿主负责连续性。每次调用仍是非交互 argv 进程，stdin 会立即关闭，stdout/stderr 分离并
有界读取，取消总是回收完整进程树。Codex 模型发现通过本机已认证 App Server 的 `model/list`
读取实际选择器并支持分页；Kimi 与 Pi 的模型发现只执行固定只读 argv，不启动登录流程、
不转发密钥；发现到的模型列表为空时要求用户先在对应 CLI 完成认证。`auto` 是“不传模型参数、
使用 CLI 当前默认值”的控制项，不作为模型 ID 缓存或展示；旧版 `default`/`kimi-k2` 占位值会
幂等迁移为 `auto`。模型列表若确认当前模型已
下线，界面将其标为不可用而不继续伪装成已验证选项，也不会静默切换到可能产生不同费用的模型。

原生历史导入是用户在依赖页显式触发的只读能力，不属于项目扫描或启动探测。Codex 使用已登录 App Server 的 `thread/list` / `thread/read`，OpenCode 使用 `session list --format json` / `export`；两者均只接受与规范化当前项目目录匹配的会话。Gemini 使用官方 `--list-sessions` / `--resume`，只保存经过格式校验的不透明恢复 ID；因其没有稳定的有界 transcript export，插件不会扫描 `~/.gemini` 私有文件。每次调用限制时长、条目数、JSON 字节、消息数和可见字符，并在取消时回收完整进程树。Codex 历史进程通过仅作用于本进程的 CLI override 禁用全局 MCP，避免无关服务器拖慢初始化，不修改用户配置。导入器丢弃 reasoning、工具结果、图片和系统消息，只保存可见用户/助手文本和不透明原生 session ID。其他 CLI 在没有稳定只读目录协议前明确显示不支持，不扫描私有数据库或猜测文件格式。

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
轮询期间每 5 秒最多产生一条有界的等待进度，并从原生 provider 传到当前任务的 UI；取消会立即关闭读取通道并回收进程；该心跳只表示
仍在等待 App Server 事件，不会伪造模型完成。真实终态仍必须来自 `turn/completed`、明确错误或
用户取消，App Server 本地登录、网络和上游模型未就绪时仍需要诊断并不会被 UI 隐藏。
原生 Codex 新建 thread 把本轮 Agent 模式明确注册的 `query_database`、`inspect_remote_kubernetes` 和 MCP 工具作为 App Server
`dynamicTools` 提供给模型，最多 32 个。Codex 保留 `mcp__` 名称空间，因此协议层使用当前轮次有界的
`omnicode_host_tool_N` 私有别名；只有匹配到已暴露工具的别名才映射回原工具名。收到 `item/tool/call` 时必须匹配当前 thread、turn、调用 ID、
工具白名单和有界 JSON 对象参数；provider 先向 App Server 返回无副作用的转交响应并中断原生 turn，
只将调用提案转换为 `ModelResponse.ToolCall`。实际执行仍由 AgentEngine 负责模式、预算、审批、
审计、超时与检查点，工具结果在下一轮以有界文本返回。交接最多等待 2 秒观察中断终态，
随后回收当前 App Server 进程树。只有 App Server 明确拒绝 `dynamicTools` 字段时，
新 thread 才退回严格文本协议；恢复旧 thread 和 DSH 也保留文本回退。认证、模型或传输错误
不会触发兼容回退。未注册、迟到、跨 thread/turn 或格式错误的调用按协议拒绝，不执行工具。
目录不证明模型已调用；非 Agent 模式不启动插件 MCP。其他未实现的 Server Request 返回
JSON-RPC 方法错误，不会被误答为命令审批的 `decision`。
命令和文件变更审批仅在当前 Agent 模式、当前原生 thread/turn ID 均匹配时进入
`ApprovalGate`；缺失或交叉的 ID、交互式 stdin、额外权限、远程环境及会话级写入根目录
请求直接拒绝。`turn/start` 回复与 `turn/started` 通知均可建立当前 turn ID，但不能
用尚未确认的 ID 预先放行审批。
Codex App Server 的 `thread/tokenUsage/updated.tokenUsage.total` 是 thread 累计值，`last`
仅是最近一次模型增量；原生适配器只接受当前 thread/turn 的通知，并用本轮首个累计值减去
对应的 `last` 建立基线，后续使用累计值减基线得到本轮用量。重复或回退的通知不能重复计费；
`turn/completed` 的泛型 usage 不能覆盖已收到的结构化累计通知。

变更审阅卡同时传递 hunk 的起止行和行数。文件打开操作会把结束行传入 IDE，因此
`path:start-end` 能选中完整范围，而不仅是跳到起始行；历史恢复和实时审阅继续使用同一导航校验。
聊天区任意位置可拖入有界附件，悬停时显示明确的接收状态；IDEA 项目树原生拖放先校验项目边界、符号链接、敏感文件与附件类型，再将项目相对路径统一用 `/` 分隔交给聊天草稿（包括 Windows），不传文件字节。拖入仅更新当前会话草稿，不自动发送，异步读取完成后仍写回发起拖拽的会话。审阅文件的 `review.openDiff` 只传 workflow ID 和项目相对路径，宿主从本机有界审阅账本取快照并再次校验项目路径，再在 IDEA 原生编辑器标签打开左右 Diff；左侧为修改前快照，右侧为当前项目文件（存在时可由用户在 IDE 中编辑）。WebView 不传完整文件内容，也不创建额外的审阅弹窗；保留/回退仍走原有审阅锁、冲突检测和审批边界。

历史消息上的“从此处分叉”是非破坏性的恢复边界：服务从当前会话的有界消息快照，或持久化
历史中复制到指定消息，生成新的 conversation ID；源会话的运行、检查点和后台任务不会被取消或
覆盖。未提供有效消息索引时不会创建分叉，避免把正在流式更新的临时块误当成可恢复历史。
