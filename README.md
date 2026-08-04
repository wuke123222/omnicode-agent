# OmniCode Agent

[![Verify](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml/badge.svg)](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

OmniCode Agent 是一个面向 JetBrains IDE 的开源代码智能体插件。它提供“读取项目 → 调用工具 → 观察结果 → 继续执行”的智能体工作流，同时把模型供应商、智能体循环和有副作用的工具严格分离。

## 当前能力

- JetBrains Tool Window 对话与流式输出
- `Agent` / `Plan 看板` / `Claude Plan` / `Research` 四模式：Agent 落实变更；Claude Plan 可读取文件、使用 PSI/索引并在只读沙箱中运行探索命令，计划获批前不能修改源码；Research 可运行经过审批的沙箱实验命令
- 单项目、单运行、可取消的 ReAct 循环；可选 `Team` 协作，由主智能体并行委派 Explorer / Planner / Reviewer
- 浏览目录、读取文件、全文搜索
- 带 SHA-256 冲突检测和审批预览的精确 Patch / 整文件修改
- 读取 JetBrains Problems 索引，并可从编辑器选区或项目树右键发送上下文
- 支持从桌面或项目拖拽图片、PDF、Markdown、Jupyter Notebook、LaTeX/BibTeX、数据、日志和常见源码文件，也可直接粘贴剪贴板截图；图片有安全缩略图，文本在本地有界提取和预览
- 输入 `@` 可搜索项目内受支持文件，并按附件加入当前任务；无需复制长路径或整段内容
- 可切换的 `workspace-write` / `danger-full-access` 进程沙箱
- 每个供应商独立保存地址、模型与凭据；保存 API Key 后立即验证并从供应商接口发现模型，支持搜索并默认隐藏明确的非对话模型
- 模型级推理强度：自动、关闭、最低、低、中、高、超高与全速；按所选 Provider/模型只展示可用档位，能验证时写入供应商原生字段，否则只增强本地 Agent 轮次、输出余量和超时，不向 API 伪造参数
- Token、估算费用、每日趋势、工具审计和本地会话历史
- 统一任务中心：运行、待恢复、失败与完成任务集中展示，支持继续、补图后重试、复制和按 workflow 回到安全检查点
- Plan → Agent 看板：编辑步骤、部分批准、继续规划、跳过、暂停和重试；可选择每步手动确认或批准后由 Agent 连续执行
- 任务变更审阅：对 Agent 的 `apply_patch` / `apply_change` 直接修改逐文件、逐块保留或哈希保护回退
- 项目 Harness：运行前固定工具/运行保护/恢复策略；自动发现规则、知识文档、构建/测试/CI 与 argv 反馈回路；侧栏展示成熟度、缺口、固定/排除文件和 PSI/符号索引，支持 `.omnicode/harness.json`
- 一键连接诊断：检查凭据存在性、代理/DNS/TLS、本地模型能力推测、视觉辅助、MCP OAuth 与沙箱，并导出脱敏诊断 ZIP
- 内置 Token/费用/趋势统计之外，可选检测本机 `tokentracker-cli` 并打开其固定回环仪表盘；安装与启动命令先复制给用户审阅，插件不会静默执行第三方包或脚本
- MCP 市场与 2025-11-25 stdio / Streamable HTTP 服务器管理：先展示 27 个离线精选，再从官方 MCP Registry 有界加载至少 500 个最新版条目，优先呈现开发、数据分析、论文与科研工具；可按来源和分类搜索，Registry 条目明确标为“未审阅”，安装只生成停用草稿；支持 Bearer Token，以及经用户确认的 OAuth 2.1 自动发现/Scope 填充、PKCE、动态注册和 Token 刷新
- Commit AI、`!` 提示词库和 `SKILL.md` 技能库
- 顶层“创意工坊”：提供跟随 JetBrains 的默认外观和多套工作台皮肤，并持久化每位用户的选择
- 可选动画桌宠：除 Pixel Cat 等伙伴外，内置原创虚拟主唱 Lumi 与吉他手 Aster，并联动待命、思考、工具、完成和失败状态；可在工具窗口内拖动，或浮动到桌面并记忆多屏位置
- 本地虚拟偶像立绘：可从桌面或项目选择 PNG/JPG；后台解码、移除元数据并缩放后重新编码为本机 PNG，不上传模型或远程服务
- API Key 使用 JetBrains PasswordSafe，不写入项目或配置 XML
- Provider 可插拔；支持原生协议和任意 OpenAI-compatible 地址
- 可在侧边栏配置最大轮次、工具次数、时间和 Provider 重试；任务累计 Token 与费用不设本地硬上限，用量和估算费用仍完整统计
- 运行中本地脱敏 checkpoint 与 IDE 重启后的显式继续/放弃；失败、取消和触发运行保护时会保留确定性部分结果并提供分类自救入口
- 同一 IDE 进程中的附件草稿恢复，以及 Plan 确认后一键切换 Agent 执行
- 可将当前会话导出为有界、脱敏的可复现实验 Markdown 研究包，包含元数据、研究问题、工具/命令证据、复现与引用核对清单

## Provider

原生协议：

- OpenAI Responses API
- Anthropic Messages API
- Google Gemini API
- Azure OpenAI
- AWS Bedrock Converse

内置 OpenCode Zen（按模型自动路由 Responses / Messages / Gemini / Chat），以及 DeepSeek、Groq、xAI、Mistral、OpenRouter、Together AI、Cerebras、Qwen/DashScope、Moonshot/Kimi、SiliconFlow、GLM、百度千帆/ERNIE、腾讯混元、火山方舟/Doubao、NVIDIA NIM、Fireworks、Ollama、LM Studio 和自定义 OpenAI-compatible 地址。兼容服务的工具调用能力仍取决于所选模型。

## 构建与安装

要求 JDK 21。Gradle Wrapper 会自动下载 Gradle 9.5。

```bash
./gradlew buildPlugin
```

构建产物位于 `build/distributions/`。在 JetBrains IDE 中打开 **Settings → Plugins → ⚙ → Install Plugin from Disk** 并选择 ZIP。

## IDE 与平台兼容性

- 当前构建目标为 IntelliJ Platform `2025.3.6`，最低支持 build 为 `253`；运行 IDE 还必须提供 Java 21 运行时。
- CI 使用 IntelliJ Platform Plugin Verifier 分别检查 IntelliJ IDEA 2025.3 / 2026.1 / 2026.2、PyCharm 2025.3 和 WebStorm 2025.3；每个矩阵任务只解析一个 IDE，避免本地默认一次下载整套产品。二进制验证不能替代各产品中的核心流程 smoke test。
- `workspace-write` 在 macOS 使用 `sandbox-exec`，在 Linux 使用经过能力探测的 `bubblewrap`（`bwrap`）。两者探测失败都会拒绝启动，不会降级为未隔离执行。
- Windows 宿主进程不会伪装 AppContainer 能力：请在 WSL2 安装 `bubblewrap` 并通过 JetBrains WSL/Remote Development 打开项目。直接从 Windows IDE 启动时保持 fail closed。

安装后：

1. 打开右侧 **OmniCode** ToolWindow，在常驻侧栏选择 **API 与模型**，选择 Provider 并填写 API Key。
2. 点击 **保存并加载模型**，插件会先写入 Password Safe，再从供应商 API 获取当前账号可用的模型。
3. 选择模型后点 **Apply**；后续切换供应商会恢复各自上次使用的地址与模型。
4. 在侧栏 **项目上下文** 查看规则、固定文件和代码搜索；大多数项目无需 Harness 配置，高级详情中可查看反馈回路与安全边界，所有真实验证仍经过正常审批和沙箱。
5. 直接在 OmniCode 常驻侧栏配置运行控制、沙箱、MCP、Commit AI、提示词和 Skill 来源，无需跳转 IDEA Settings。
6. 在同一侧栏查看 Token、费用、趋势、历史与工具审计。
   如需跨 AI 工具汇总，可在用量页审阅并复制 TokenTracker 安装/启动命令；只有本地面板被正确识别后才会启用打开按钮。
7. 打开侧栏顶层 **创意工坊**，选择工作台皮肤、原创虚拟偶像或导入您有权使用的 PNG/JPG 立绘；可直接预览五种 Agent 状态。
8. 打开右侧 **OmniCode** Tool Window，按任务选择 **Agent**、**Plan 看板**、**Claude Plan** 或 **Research**；复杂任务可额外开启 **Team**，有副作用的工具仍只由主智能体执行并先展示审批对话框。
9. 在聊天底栏选择 **思考** 档位。全速会使用当前模型可验证的最高推理能力，并同步增加单轮输出余量与请求超时；**运行控制** 默认开启持续执行，不会因累计时长、轮次或工具调用数终止长任务。

## Team 多智能体协作

`Team` 是独立于 Agent / Plan 看板 / Claude Plan / Research 的执行策略。开启后，主智能体可按需在一批中并行委派最多 4 个只读专家；一次运行最多 3 轮、8 个专家。Explorer 负责代码事实，Planner 负责实施路径，Reviewer 负责风险与验证。每个专家只收到原始目标与自己的窄任务，不共享主智能体或其他专家的隐藏上下文，也不能写文件、运行命令、调用 MCP、发起审批或继续委派。

所有模型请求共享同一用量与费用统计账本，但不设置本地任务硬额度；并发请求仍先登记在途用量，取消主任务会取消仍在运行的专家。聊天中会把专家状态、摘要与 Token 聚合在同一张 Team 卡片里，最终答案仍由主智能体统一输出。用量只按整次 workflow 聚合记录一次，工具审计则保留 agent ID 以便追踪。

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

- PDF：仅在本地使用 PDFBox 提取可定位到页的文本，限制为 10 MB、300 页和 48,000 字符；拒绝加密、损坏或无可读文本的文档。扫描论文请上传关键页面截图并使用视觉辅助模型。
- Jupyter Notebook：严格解析 UTF-8 JSON，仅提取 Markdown/代码 cell；限制为 2 MB、200 个 cell、单 cell 12,000 字符、合计 48,000 字符，忽略 outputs、附件和 metadata。
- 科研文本：支持 `.tex`、`.bib`、`.r`、`.jl`、`.m`，以及 CSV/TSV/JSON/YAML 等常见数据和配置文本；所有文本仍受 UTF-8、控制字符和总体附件数量限制。

点击“设计可复现实验”或“分析论文与资料”会真实切换到 Research，而不是只修改提示词。Research 完成卡片、聊天更多菜单和 Tool Window 齿轮均可进入 **导出可复现实验研究包…**，输入框聚焦时也可按 `Cmd/Ctrl+Shift+E`。保存器会先展示消息/证据/截断摘要。导出文件是有界 Markdown，默认上限 512 KiB；SYSTEM Prompt 在任何处理前排除，模式/供应商/模型明确标记为“导出时配置”，并包含首个研究问题、经过截断和脱敏的会话、工具与命令证据、复现清单、限制和引用核对清单。图片只记录文件名、类型和大小，不导出二进制/base64；研究包不是事实证明或完整环境快照，分享前必须人工复核。

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

项目源码托管于 [GitHub](https://github.com/wuke123222/omnicode-agent)。详细边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，Harness 格式见 [docs/HARNESS.md](docs/HARNESS.md)，虚拟偶像立绘规则见 [docs/PET_AVATARS.md](docs/PET_AVATARS.md)，新增供应商见 [docs/PROVIDERS.md](docs/PROVIDERS.md)。贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，隐私说明见 [PRIVACY.md](PRIVACY.md)，安全漏洞报告请遵循 [SECURITY.md](SECURITY.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)，第三方组件许可与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。PDF 文本提取使用 Apache PDFBox 3.0.8（Apache License 2.0）；其依赖 JAR 保留上游许可元数据。

维护者发布 Marketplace 版本前还应遵循 [签名与发布手册](docs/RELEASING.md)；普通 push/PR 不读取发布 secrets，只有版本匹配的 `v*` tag 在多产品验证通过并获得受保护环境批准后才会签名和上传。

## 当前限制

- Team 当前是有界的主从协作：角色固定为 Explorer / Planner / Reviewer，最多并行 2 个、每次运行最多 4 个，不支持递归委派、自定义 Agent、跨运行长期记忆或多个 Agent 同时写文件。
- 0.14 的变更审阅账本仅覆盖当前 IDE 会话中经 `apply_patch` / `apply_change` 产生的直接修改；命令、MCP 或用户并发编辑不纳入“全部已记录修改”回退，冲突时操作会失败关闭。
- 支持当前设备上的 lead workflow 安全恢复，但暂不支持无人值守后台任务、跨设备恢复或完整多智能体任务板；二进制附件不会进入 checkpoint，恢复后需要重新附加。
- 暂不包含 Agent 浏览器自动化或 Git push/PR。
- 不提供交互式 PTY、shell pipeline、`sudo`、删除/移动文件。
- 不预先上传整个仓库；模型需要通过工具按需读取。
- PDF 当前只做文本提取，不含 OCR；加密 PDF 和纯扫描 PDF 需先转换为可信文本，或上传关键页面截图。Notebook 不导入 outputs、附件和 metadata。
- 可复现实验研究包是脱敏、有界的会话证据清单，不包含 SYSTEM Prompt、API 凭据、完整进程环境、图片二进制或宿主机状态，也不能消除模型与外部服务的非确定性；导出时配置不代表每一轮历史运行配置。
- OpenAI-compatible 只保证协议适配，具体模型可能不支持流式工具调用。
- `Auto` 保留模型默认行为。无法验证原生 effort 的兼容模型仍可使用低/中/高/全速的 Agent 执行强度，但不会收到伪造的推理字段；关闭、最低、超高等依赖原生语义的档位会隐藏。任务累计 Token 与费用没有本地硬上限，但单次请求仍受模型上下文、供应商输出上限、账户额度和限流约束。
- 动态模型发现覆盖 OpenAI-compatible、Gemini 和 Anthropic；Azure 使用部署名、Bedrock 使用模型 ID，因此保留手动配置入口。
- MCP OAuth 暂不支持 Client ID Metadata Documents、DPoP、`private_key_jwt`、`client_secret_basic`、多授权服务器交互选择，也不兼容旧版双端点 HTTP+SSE。
- `workspace-write` 当前在 macOS 与具备可用 `bubblewrap` 的 Linux 上提供 OS 级强制隔离；Windows 宿主和能力探测失败的平台会 fail closed。
- 金额为根据用量和用户配置的每百万 Token 单价计算的估算值，并非供应商账单。
- Bedrock 首版使用同步 Converse；凭据来自设置或 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`，暂不包含 AWS SSO/Profile credential chain。
- Gemini 首版使用可重放完整可见历史的 `streamGenerateContent`；Interactions 的 opaque continuation state 留待会话模型升级后接入。
- 创意工坊不加载第三方脚本、类、命令、音频、SVG、动画文件或远程资源。自定义桌宠首版仅接受 8 MB 内、32–2048 像素且不超过 419 万像素的 PNG/JPG，最长边会缩至 512 像素并重新编码；仅支持一个本地自定义立绘槽位。

## 许可证

OmniCode Agent 依据 [Apache License 2.0](LICENSE) 开源发布。
