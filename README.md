# OmniCode Agent

[![Verify](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml/badge.svg)](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

OmniCode Agent 是一个面向 JetBrains IDE 的开源代码智能体插件。它提供“读取项目 → 调用工具 → 观察结果 → 继续执行”的智能体工作流，同时把模型供应商、智能体循环和有副作用的工具严格分离。

## 当前能力

- JetBrains Tool Window 对话与流式输出
- `Agent` / `Plan 看板` / `Claude Plan` / `Research` 四模式：Agent 落实变更；Claude Plan 可读取文件、使用 PSI/索引并在只读沙箱中运行探索命令，计划获批前不能修改源码；Research 可运行经过审批的沙箱实验命令
- 单项目、单运行、可取消的 ReAct 循环；可选 `Single` / `自动路由` / `Team`，复杂任务才并行委派动态命名的只读专家
- 浏览目录、读取文件、全文搜索
- 带 SHA-256 冲突检测和审批预览的精确 Patch / 整文件修改
- 读取 JetBrains Problems 索引，并可从编辑器选区或项目树右键发送上下文
- 支持从桌面或项目拖拽图片、PDF、Markdown、Jupyter Notebook、LaTeX/BibTeX、数据、日志和常见源码文件，也可直接粘贴剪贴板截图；图片有安全缩略图，文本在本地有界提取和预览
- CSV/TSV 附件会在本地生成有界的列类型、缺失量、数值范围、均值和趋势摘要；原始数据仍按附件上限读取，不执行文件内容，也不上传第三方服务
- 输入 `@` 可搜索项目内受支持文件，并按附件加入当前任务；无需复制长路径或整段内容
- 可切换的 `workspace-write` / `danger-full-access` 进程沙箱
- 每个供应商独立保存地址、模型与凭据；保存 API Key 后立即验证并从供应商接口发现模型，支持搜索并默认隐藏明确的非对话模型
- 模型级推理强度：自动、关闭、最低、低、中、高、超高与全速；按所选 Provider/模型只展示可用档位，能验证时写入供应商原生字段，否则只增强本地 Agent 轮次、输出余量和超时，不向 API 伪造参数
- TokenTracker 提供用量、费用与每日趋势；OmniCode 保留工具审计和本地会话历史
- 统一任务中心：运行、待恢复、失败与完成任务集中展示，支持继续、补图后重试、复制和按 workflow 回到安全检查点
- 任务中心会聚合可靠性 ledger，直接显示当前阶段、阶段耗时、模型请求、工具失败、重试计数和最近事件；即使聊天窗口已关闭也能快速定位失败步骤
- 任务级可靠性中心：记录阶段耗时、模型请求、工具失败、重试原因和恢复点；失败任务可从最近失败步骤继续，不必整任务重跑
- Plan → Agent 看板：编辑步骤、部分批准、继续规划、跳过、暂停和重试；可选择每步手动确认或批准后由 Agent 连续执行
- 任务变更审阅：对 Agent 的 `apply_patch` / `apply_change` 直接修改逐文件、逐块保留或哈希保护回退
- 持久化变更审阅：重启后恢复审阅账本，并可导入当前 Git 已跟踪差异；命令产生的差异会被标为外部变更，只允许逐文件审阅
- 项目 Harness：运行前固定工具/运行保护/恢复策略；自动发现规则、知识文档、构建/测试/CI 与 argv 反馈回路；侧栏展示成熟度、缺口、固定/排除文件和 PSI/符号索引，支持 `.omnicode/harness.json`
- 一键连接诊断：检查凭据存在性、代理/DNS/TLS、本地模型能力推测、视觉辅助、MCP OAuth 与沙箱，并导出脱敏诊断 ZIP
- 使用统计页嵌入本机 `tokentracker-cli` 的固定回环仪表盘；启动命令先复制给用户审阅，插件不会静默执行第三方包或脚本
- MCP 市场与 2025-11-25 stdio / Streamable HTTP 服务器管理：先展示 27 个离线精选，再从官方 MCP Registry 有界加载至少 500 个最新版条目，优先呈现开发、数据分析、论文与科研工具；可按来源和分类搜索，Registry 条目明确标为“未审阅”，安装只生成停用草稿；支持 Bearer Token，以及经用户确认的 OAuth 2.1 自动发现/Scope 填充、PKCE、动态注册和 Token 刷新
- Commit AI、`!` 提示词库和 `SKILL.md` 技能库
- 顶层“创意工坊”：提供跟随 JetBrains 的默认外观和多套工作台皮肤，并持久化每位用户的选择
- 可选动画桌宠：除 Pixel Cat 等伙伴外，内置原创虚拟主唱 Lumi 与吉他手 Aster，并联动待命、思考、工具、完成和失败状态；可在工具窗口内拖动，或浮动到桌面并记忆多屏位置
- 本地虚拟偶像立绘：可从桌面或项目选择 PNG/JPG；后台解码、移除元数据并缩放后重新编码为本机 PNG，不上传模型或远程服务
- API Key 使用 JetBrains PasswordSafe，不写入项目或配置 XML
- Provider 可插拔；支持原生协议和任意 OpenAI-compatible 地址
- 可在侧边栏配置最大轮次、工具次数、时间和 Provider 重试；任务累计 Token 与费用不设本地硬上限，内部账本仅用于运行恢复与审计，不作为使用统计页的数据源
- 运行中本地脱敏 checkpoint 与 IDE 重启后的显式继续/放弃；失败、取消和触发运行保护时会保留确定性部分结果并提供分类自救入口。首轮项目上下文预热为软预算，冷仓库扫描超过 1.2 秒时先发起模型请求，首轮自动上下文按推理档位限制为 24–96 KiB，后续轮次复用后台快照；MCP 初次连接按档位使用 1.5–5 秒软等待
- 同一 IDE 进程中的附件草稿恢复，以及 Plan 确认后一键切换 Agent 执行
- 可将当前会话导出为有界、脱敏的可复现实验 Markdown 研究包，包含元数据、研究问题、工具/命令证据、复现与引用核对清单
- 侧边栏“Pro 权益”提供签名许可证激活和权益说明；现有 Agent/Team、Git Worktree/PR、浏览器自动化、跨设备任务包、可靠性中心、MCP 与科研附件全部免费；Pro 只增加项目智能档案、批量任务配方和工程进展周报，Research 额外增加实验锁定信息；许可证缺失不会降级或隐藏任何基础功能

### 商业化能力边界

OmniCode 的付费切片采用服务端签发的 Ed25519 许可证：token 只保存在 JetBrains Password Safe，校验失败、篡改或过期时 fail-closed 回到 Free。Free 已包含完整的编码与研究工作流，包括可靠性报告；Pro 的价值是额外的团队交接产物（项目智能档案）、批量任务配方和工程进展周报（自动合并本地 Git 版本差异与任务进度），Research 再增加可选实验锁定信息。许可证只影响用户主动点击的新增导出/配方功能，不影响 Team、Git、浏览器、MCP、云端任务包或任何基础 Agent 工具。所有产物均有界且脱敏，不包含完整提示词、密钥、二进制附件或环境快照。购买、席位、发票、退款和跨设备账户仍需要独立商业后端，插件不会伪造付款状态，也不会因许可证缺失阻断核心编码流程。

## Provider

原生协议：

- OpenAI Responses API
- Anthropic Messages API
- Google Gemini API
- Azure OpenAI
- AWS Bedrock Converse

内置 OpenCode Zen（按模型自动路由 Responses / Messages / Gemini / Chat），以及 DeepSeek、Groq、xAI、Mistral、OpenRouter、Together AI、Cerebras、Qwen/DashScope、Moonshot/Kimi、SiliconFlow、GLM、百度千帆/ERNIE、腾讯混元、火山方舟/Doubao、NVIDIA NIM、Fireworks、Ollama、LM Studio 和自定义 OpenAI-compatible 地址。兼容服务的工具调用能力仍取决于所选模型。

启用 Team 或自动路由时，OmniCode 的只读子智能体会在需要时使用本机 `codex app-server --stdio`。主对话仍使用你在“API 与模型”中配置的供应商；Codex 不会作为可选模型或供应商出现，也不需要在 OmniCode 中填写 API Key。若 Codex 可执行文件不在 PATH，可设置 `OMNICODE_CODEX_PATH` 指向它。Codex 子智能体启动失败会作为该专家的失败证据返回，不会静默改用主模型。

## 构建与安装

要求 JDK 21。Gradle Wrapper 会自动下载 Gradle 9.5。

```bash
./gradlew buildPlugin
# 可选：生成 build/reports/supply-chain/omnicode-sbom.json
./gradlew --no-configuration-cache supplyChainSbom
```

构建产物位于 `build/distributions/`。在 JetBrains IDE 中打开 **Settings → Plugins → ⚙ → Install Plugin from Disk** 并选择 ZIP。

## IDE 与平台兼容性

- 当前构建目标为 IntelliJ Platform `2025.3.6`，最低支持 build 为 `253`；运行 IDE 还必须提供 Java 21 运行时。
- CI 使用 IntelliJ Platform Plugin Verifier 分别检查 IntelliJ IDEA 2025.3 / 2026.1 / 2026.2、PyCharm 2025.3 和 WebStorm 2025.3；每个矩阵任务只解析一个 IDE，避免本地默认一次下载整套产品。二进制验证不能替代各产品中的核心流程 smoke test。
- `workspace-write` 在 macOS 使用 `sandbox-exec`，在 Linux 使用经过能力探测的 `bubblewrap`（`bwrap`），在 Windows 使用随插件分发、经过 SHA-256/签名门禁的原生 AppContainer host。任一后端探测、ACL 授权或清理失败都会拒绝启动，不会降级为未隔离执行。
- Windows host 使用无网络 capability 的 per-run AppContainer，并对项目执行有界 ACL 事务；命令结束后恢复原始 ACL。helper 缺失、哈希/签名不匹配、符号链接/重解析点或超出事务上限时保持 fail closed，可改用 JetBrains WSL/Remote Development。

安装后：

1. 打开右侧 **OmniCode** ToolWindow，在常驻侧栏选择 **API 与模型**，选择 Provider 并填写 API Key。
2. 点击 **保存并加载模型**，插件会先写入 Password Safe，再从供应商 API 获取当前账号可用的模型。
3. 选择模型后点 **Apply**；后续切换供应商会恢复各自上次使用的地址与模型。
4. 在侧栏 **项目上下文** 查看规则、固定文件和代码搜索；大多数项目无需 Harness 配置，高级详情中可查看反馈回路与安全边界，所有真实验证仍经过正常审批和沙箱。
5. 直接在 OmniCode 常驻侧栏配置运行控制、沙箱、MCP、Commit AI、提示词和 Skill 来源，无需跳转 IDEA Settings。
6. 在同一侧栏查看历史与工具审计；**使用统计**页直接嵌入第三方 TokenTracker 仪表盘。OmniCode 只负责检测固定的 `127.0.0.1:7680`、复制启动命令和提供外部打开兜底，不再在该页面展示自己的 Token/费用趋势统计。
7. 打开侧栏顶层 **创意工坊**，选择工作台皮肤、原创虚拟偶像或导入您有权使用的 PNG/JPG 立绘；可直接预览五种 Agent 状态。
8. 打开右侧 **OmniCode** Tool Window，按任务选择 **Agent**、**Plan 看板**、**Claude Plan** 或 **Research**；复杂任务可额外开启 **Team**，有副作用的工具仍只由主智能体执行并先展示审批对话框。
9. 在聊天底栏选择 **思考** 档位。全速会使用当前模型可验证的最高推理能力，并同步增加单轮输出余量与请求超时；**运行控制** 默认开启持续执行，不会因累计时长、轮次或工具调用数终止长任务。

### Codex 风格聊天命令

输入框支持一组不依赖模型请求的本地命令，发送后立即打开对应侧栏功能；因此即使尚未配置 API Key，也可以完成首启诊断和配置：

| 命令 | 行为 | 是否请求模型 |
| --- | --- | --- |
| `/plan <任务>` | 本轮切换为 Claude Plan，只读探索并生成可审批计划 | 是 |
| `/review [要求]` | 审阅当前 Git 差异，输出带文件/行号证据的报告 | 是 |
| `/status` | 打开连接诊断 | 否 |
| `/model` | 打开模型选择器 | 否 |
| `/permissions` | 打开沙箱与权限设置 | 否 |
| `/mcp` | 打开 MCP 服务与市场 | 否 |
| `/tasks` | 打开任务中心和可恢复检查点 | 否 |
| `/new` / `/help` | 新建会话 / 查看命令帮助 | 否 |

`/plan` 是单轮覆盖，不会改变常驻模式；`/review` 以只读 Research 约束运行，绝不自动修改源码。文件引用统一使用 `relative/path.ext:line` 或 `relative/path.ext:start-end`，点击后在 IDE 中打开并选中对应范围。

聊天流采用画布式助手排版：用户请求保持右侧气泡，助手正文不再包裹一层大卡片，工具、变更、审批和恢复操作仍以可折叠卡片显示；处理耗时固定在助手标题行，较早的过程行和流式文本只在界面保留有界窗口，完整历史与审计仍持久化。

## Team 多智能体协作

`Team` 是独立于 Agent / Plan 看板 / Claude Plan / Research 的执行策略。选择“自动路由”时，短小单文件目标保持 Single，跨模块、科研综述、附件分析和复杂排障才启用 Team；用户也可以手动覆盖。主智能体可按需在一批中并行委派最多 4 个只读专家；一次运行最多 3 轮、8 个专家，角色名称可按目标动态生成。每个专家只收到原始目标与自己的窄任务，不共享主智能体或其他专家的隐藏上下文，也不能写文件、运行命令、调用 MCP、发起审批或继续委派。

所有模型请求共享同一用量与费用统计账本，但不设置本地任务硬额度；并发请求仍先登记在途用量，取消主任务会取消仍在运行的专家。聊天中会把专家状态、摘要与 Token 聚合在同一张 Team 卡片里；摘要行可展开并复制每个专家的有界目标与阶段处理内容，且不会展示隐藏提示词、密钥或未授权上下文。最终答案仍由主智能体统一输出。用量只按整次 workflow 聚合记录一次，工具审计则保留 agent ID 以便追踪。

## 安全恢复与错误自救

主智能体会在运行开始、模型/工具边界和工具观察完成后，把最新的有界文本状态写入 JetBrains system path。IDE 异常退出或重启后，OmniCode 会把未完成记录显示为“可恢复的中断任务”，由用户选择继续或放弃；不会静默恢复。继续任务会沿用原 workflow、模式和执行策略，恢复已保存的目标、约束与工具观察，并先核对当前项目状态。它不是对原协程或供应商隐式会话的逐字节恢复。

如果中断点存在尚未确认完成的工具，尤其是可能产生副作用的工具，OmniCode 不会自动重放。恢复后会先读取或验证现状；模型若再次提出文件修改、命令或外部调用，仍须经过新的工具校验和审批。“放弃检查点”只删除本地恢复记录，不会撤销已经发生的文件变更或外部操作。

上下文受限时，选择器会保护当前运行的用户目标、验收条件和约束，而不会把纯工具结果误当成新的用户请求。持续执行仍可由用户随时取消，并保留重复无进展、连续工具失败、单工具超时、审批、沙箱和未知副作用保护；只有显式关闭持续执行时，累计轮次、工具数和总时长才作为有限模式边界。取消或 Agent 内部失败会生成有界、确定性的“已完成 / 证据 / 剩余 / 风险”部分结果，不额外消耗模型或工具调用。图片及其他附件二进制不写入 checkpoint；已经提取为消息文本的 Markdown、PDF 或其他文本内容可能作为脱敏、有界文本快照保留。

开发时可运行沙箱 IDE：

```bash
./gradlew runIde
```

## Research 工作流

Research 面向代码调查、论文/资料分析和可复现实验记录。它不是 Agent 的高权限别名，权限在 Tool Registry 和执行层独立收紧：

| 模式 | 读取项目与 Skills | 运行命令 | 修改文件 | MCP / 外部工具 |
| --- | --- | --- | --- | --- |
| Agent | 允许 | 逐次审批并应用所选沙箱 | 审批、哈希复核后允许 | 连接及调用均需审批 |
| Plan 看板 | 允许 | 禁止 | 禁止 | 禁止且不建立连接 |
| Claude Plan | 允许（含 PSI/索引探索） | 仅严格验证的本地只读命令；免审批但强制只读沙箱 | 禁止 | 禁止且不建立连接 |
| Research | 允许 | 逐次审批并应用所选沙箱 | 禁止 | 禁止且不建立连接 |

Research 最终报告要求覆盖研究问题、假设、方法、证据、结果、局限、复现清单和引用，只引用实际检查过的资料，并明确区分直接观察、推断和未验证信息。未知或第三方工具默认归类为 `EXTERNAL`，不会进入 Plan 或 Research 工具面。`workspace-write` 下实验命令默认断网且仅能写工作区；若用户显式切换 `danger-full-access`，Research 仍不能调用文件修改或 MCP 工具，但命令进程本身不再具有 OS 级文件/网络隔离。

附件可通过 `+`、拖拽、剪贴板或输入 `@路径片段` 加入。`@` 只搜索当前项目内可安全读取的受支持文件，忽略 `.git`、构建输出、依赖目录和虚拟环境，并复用统一的大小、格式与敏感文件校验；弹层支持方向键选择、Enter/Tab 确认和 Esc 关闭。主要科研格式包括：

- PDF：仅在本地使用 PDFBox 提取可定位到页的文本，限制为 10 MB、300 页和 48,000 字符；拒绝加密、损坏的文档。纯扫描 PDF 会在明确选择的本地文件上尝试 Tesseract OCR（最多 4 页、每页 4,000 字符、单页 2 秒），未安装引擎时回退为关键页截图/视觉辅助提示；原始 PDF 不会上传给第三方。
- Jupyter Notebook：严格解析 UTF-8 JSON，仅提取 Markdown/代码 cell；限制为 2 MB、200 个 cell、单 cell 12,000 字符、合计 48,000 字符，忽略 outputs、附件和 metadata。
- 科研文本：支持 `.tex`、`.bib`、`.r`、`.jl`、`.m`，以及 CSV/TSV/JSON/YAML 等常见数据和配置文本；所有文本仍受 UTF-8、控制字符和总体附件数量限制。BibTeX 附件会在有界内容内本地离线检查条目、重复 key/DOI 和 DOI 格式，并明确显示“未做网络验证”。

点击“设计可复现实验”或“分析论文与资料”会真实切换到 Research，而不是只修改提示词。Research 完成卡片、聊天更多菜单和 Tool Window 齿轮均可进入 **导出可复现实验研究包…**，输入框聚焦时也可按 `Cmd/Ctrl+Shift+E`。保存器会先展示消息/证据/截断摘要。导出文件是有界 Markdown，默认上限 512 KiB；SYSTEM Prompt 在任何处理前排除，模式/供应商/模型明确标记为“导出时配置”，并包含首个研究问题、经过截断和脱敏的会话、工具与命令证据、复现清单、限制和引用核对清单。启用实验锁定时还会记录相对工作区、进程沙箱、用户提供的依赖摘要/随机种子，以及成功 `run_command` 的有界 argv；拒绝或失败命令不会伪装成已批准实验。图片只记录文件名、类型和大小，不导出二进制/base64；研究包不是事实证明或完整环境快照，分享前必须人工复核。

## 安全边界

- 模型只能提出工具请求，不能直接访问本机。
- `Plan 看板` 只暴露只读工具；Claude Plan 额外暴露内置 `run_command`，但只接受可证明只读的 argv 并强制无网络、工作区只读沙箱；`Research` 只暴露只读与命令工具。三者都不会启动 MCP 或获得文件修改工具。
- 项目规则、Pinned Context 和模型可见文件工具统一服从 `.gitignore`、`.aiignore`、`.omnicodeignore`、显式排除及敏感路径硬禁令；Ignore 策略损坏或超限时 fail closed。
- 所有路径必须位于当前项目；同时检查规范路径和符号链接逃逸。
- `.env`、SSH/AWS 凭据和常见私钥路径默认禁止读取。
- 文件变更必须携带最近一次读取得到的 SHA-256；审批后再次校验。
- 命令不经过 shell 插值，禁用 shell、`sudo` 和脚本求值器；Agent/Research 命令逐次审批，Claude Plan 只有通过只读策略且被 OS 只读沙箱兜底的探索命令可免审批。
- 子进程仅继承少量基础环境变量，不继承 API Key、Token、Secret 或 Password。
- macOS 的 `workspace-write` 使用经过读写探测的 `/usr/bin/sandbox-exec`：仅允许工作区读写，并阻止网络和外部用户目录访问；探测失败时拒绝执行。
- Linux 的 `workspace-write` 使用 `bubblewrap`：宿主运行时只读挂载、工作区读写挂载、用户目录隐藏、私有 HOME/tmp，并隔离网络命名空间。插件会实际验证文件边界和网络视图后才启用。
- `danger-full-access` 关闭 OS 隔离，适合经用户确认后执行 `adb`、`docker` 等系统级命令；审批、超时、输出边界和环境清理仍保留。

MCP Server 是外部系统。本地 stdio 首次启动或配置、项目、沙箱、环境变量名及可执行文件内容发生变化时必须审批；远程 Streamable HTTP 首次连接或 Endpoint/认证指纹变化时也必须审批。远程地址强制 HTTPS（字面 loopback 可使用 HTTP）、禁止重定向。OAuth 元数据发现需要显式确认；challenge 给出的 resource metadata 必须与 MCP Endpoint 同源，授权服务器 metadata 只从校验后的 HTTPS issuer 生成，并受超时、响应大小、URL、字段和数组上限约束。OAuth 强制 PKCE S256/state/resource audience，回调只监听随机 `127.0.0.1` 路径；UI 发现结果不持久化为受信端点，登录/刷新会重新发现和校验。会话绑定已保存的 Endpoint、Client ID 和 Scope，跨连接刷新单飞，Bearer、Access/Refresh Token 和动态客户端密钥仅存 IDE PasswordSafe。每次 MCP 工具调用仍需用户批准。

## 项目结构

```text
src/main/kotlin/dev/omnicode/
  harness/     运行前预检、有效工具面与策略摘要
  agent/       ReAct 循环、上下文和预算
  model/       Provider 无关的消息与工具块
  provider/    各协议适配器与预设
  mcp/         MCP stdio / Streamable HTTP 客户端和工具桥接
  persistence/ 用量、历史、workflow checkpoint 和工具审计的本地有界存储
  settings/    普通配置与 PasswordSafe
  tool/        文件、搜索、命令、进程沙箱和审批边界
  service/     项目生命周期与运行控制
  ui/          Tool Window、聊天和审批 UI
  workshop/    受信任的皮肤/桌宠目录、本地选择与安全立绘净化存储
```

项目源码托管于 [GitHub](https://github.com/wuke123222/omnicode-agent)。详细边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，商用验收矩阵见 [docs/COMMERCIAL_READINESS.md](docs/COMMERCIAL_READINESS.md)，任务 relay 接口见 [docs/WORKFLOW_CLOUD_RELAY.md](docs/WORKFLOW_CLOUD_RELAY.md)，Harness 格式见 [docs/HARNESS.md](docs/HARNESS.md)，虚拟偶像立绘规则见 [docs/PET_AVATARS.md](docs/PET_AVATARS.md)，新增供应商见 [docs/PROVIDERS.md](docs/PROVIDERS.md)。贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，隐私说明见 [PRIVACY.md](PRIVACY.md)，安全漏洞报告请遵循 [SECURITY.md](SECURITY.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)，第三方组件许可与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。PDF 文本提取使用 Apache PDFBox 3.0.8（Apache License 2.0）；其依赖 JAR 保留上游许可元数据。

维护者发布 Marketplace 版本前还应遵循 [签名与发布手册](docs/RELEASING.md)；普通 push/PR 不读取发布 secrets，只有版本匹配的 `v*` tag 在多产品验证通过并获得受保护环境批准后才会签名和上传。

## 当前限制

- Team 是有界的主从协作：简单目标自动走 Single，跨模块/科研/复杂排障才路由到 Team；专家角色可由主智能体生成名称，但仍最多并行 4 个、不能递归委派或同时写文件。专家失败、超时或达到自身边界会作为可恢复证据交回主 Agent，不再把整个任务标成工具失败；若主 Agent 在收到委派结果后返回空内容，会自动追加一次无工具的最终综合请求。
- 变更审阅已跨 IDE 重启持久化，并支持导入当前 Git 已跟踪差异；命令、MCP 或用户并发编辑仍不会被伪装成 Agent 直接修改，回退前始终做哈希和路径复核。
- 支持当前设备上的 lead workflow 安全恢复；任务中心可将脱敏 checkpoint 加密导出为 `.omnitask`，跨设备导入会生成新的 workflow/run ID 并保持待恢复状态。二进制附件不会进入 checkpoint，恢复后需要重新附加。
- Agent 提供受审批的 `browser_automation`（Playwright 检查/打开/截图）和 `git_workflow`（项目内 `.omnicode-worktrees`、状态、`gh pr create`）工具；两者仅在 Agent 模式可用，Playwright、`gh` 登录和网络权限需要用户自行安装/授权。
- 不提供交互式 PTY、shell pipeline、`sudo`、删除/移动文件。
- 不预先上传整个仓库；模型需要通过工具按需读取。
- PDF 默认本地提取文本；纯扫描 PDF 仅在本机存在 Tesseract 时启用有界 OCR，仍不读取加密 PDF。提取结果带稳定页码偏移，可供报告引用。Notebook 默认不导入 outputs、附件和 metadata；研究工具可选择性提取有界纯文本输出预览，二进制富媒体始终跳过。
- 可复现实验研究包是脱敏、有界的会话证据清单，不包含 SYSTEM Prompt、API 凭据、完整进程环境、图片二进制或宿主机状态，也不能消除模型与外部服务的非确定性；导出时配置不代表每一轮历史运行配置。
- OpenAI-compatible 只保证协议适配，具体模型可能不支持流式工具调用。
- `Auto` 保留模型默认行为。无法验证原生 effort 的兼容模型仍可使用低/中/高/全速的 Agent 执行强度，但不会收到伪造的推理字段；关闭、最低、超高等依赖原生语义的档位会隐藏。任务累计 Token 与费用没有本地硬上限，但单次请求仍受模型上下文、供应商输出上限、账户额度和限流约束。
- 动态模型发现覆盖 OpenAI-compatible、Gemini 和 Anthropic；Azure 使用部署名、Bedrock 使用模型 ID，因此保留手动配置入口。
- MCP OAuth 暂不支持 Client ID Metadata Documents、DPoP、`private_key_jwt`、`client_secret_basic`、多授权服务器交互选择，也不兼容旧版双端点 HTTP+SSE。
- `workspace-write` 当前在 macOS、具备可用 `bubblewrap` 的 Linux，以及安装了签名 native AppContainer host 的 Windows 上提供 OS 级强制隔离；能力探测失败的平台会 fail closed。
- Windows 原生 host 会拒绝重解析点和超大 ACL 事务，并在清理失败时返回失败；未安装或未通过哈希/签名门禁时，沙箱页提供 WSL2 + bubblewrap + JetBrains Remote Development 的不自动执行指引。
- 可选 `WorkflowCloudSyncClient` 对接用户自建 HTTPS relay，relay 只保存已加密的不透明任务包；插件不提供托管账号或无人值守后台服务。CI 会生成 CycloneDX 运行时 SBOM 并上传，GitHub Dependabot 每周检查 Gradle 与 Actions 依赖；`ui-regression.yml` 的手动入口会启动真实 IDE + Robot Server 并执行远程截图 smoke，普通 PR 仍走无磁盘组件门禁。原生 Windows AppContainer 由 `windows-sandbox.yml` 在 Windows runner 编译、探测并执行 ACL smoke；正式发布仍必须使用受信任的 Authenticode 证书签 native helper。
- 金额为根据用量和用户配置的每百万 Token 单价计算的估算值，并非供应商账单。
- Bedrock 首版使用同步 Converse；凭据来自设置或 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`，暂不包含 AWS SSO/Profile credential chain。
- Gemini 首版使用可重放完整可见历史的 `streamGenerateContent`；Interactions 的 opaque continuation state 留待会话模型升级后接入。
- 创意工坊不加载第三方脚本、类、命令、音频、SVG、动画文件或远程资源。自定义桌宠首版仅接受 8 MB 内、32–2048 像素且不超过 419 万像素的 PNG/JPG，最长边会缩至 512 像素并重新编码；仅支持一个本地自定义立绘槽位。

## 许可证

OmniCode Agent 依据 [Apache License 2.0](LICENSE) 开源发布。
