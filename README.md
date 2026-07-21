# OmniCode Agent

[![Verify](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml/badge.svg)](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

OmniCode Agent 是一个面向 JetBrains IDE 的开源代码智能体插件。它提供“读取项目 → 调用工具 → 观察结果 → 继续执行”的智能体工作流，同时把模型供应商、智能体循环和有副作用的工具严格分离。

## 当前能力

- JetBrains Tool Window 对话与流式输出
- `Agent` / `Plan` / `Research` 三模式：Agent 落实变更，Plan 只读规划，Research 只读取证据并可运行经过审批的沙箱实验命令
- 单项目、单运行、可取消的 ReAct 循环；可选 `Team` 协作，由主智能体并行委派 Explorer / Planner / Reviewer
- 浏览目录、读取文件、全文搜索
- 带 SHA-256 冲突检测和审批预览的精确 Patch / 整文件修改
- 读取 JetBrains Problems 索引，并可从编辑器选区或项目树右键发送上下文
- 支持从桌面或项目拖拽图片、PDF、Markdown、Jupyter Notebook、LaTeX/BibTeX、数据、日志和常见源码文件，也可直接粘贴剪贴板截图；图片有安全缩略图，文本在本地有界提取和预览
- 输入 `@` 可搜索项目内受支持文件，并按附件加入当前任务；无需复制长路径或整段内容
- 可切换的 `workspace-write` / `danger-full-access` 进程沙箱
- 每个供应商独立保存地址、模型与凭据；保存 API Key 后立即验证并从供应商接口发现模型，支持搜索并默认隐藏明确的非对话模型
- Token、估算费用、每日趋势、工具审计和本地会话历史
- MCP 2025-11-25 stdio / Streamable HTTP 服务器管理，支持 Bearer Token 以及 OAuth 2.1 发现、PKCE、动态注册和 Token 刷新
- Commit AI、`!` 提示词库和 `SKILL.md` 技能库
- 顶层“创意工坊”：提供跟随 JetBrains 的默认外观和多套工作台皮肤，并持久化每位用户的选择
- 可选动画桌宠：在聊天工作台显示思考、工具调用、完成和失败状态；关闭或移除组件后动画计时器立即释放
- API Key 使用 JetBrains PasswordSafe，不写入项目或配置 XML
- Provider 可插拔；支持原生协议和任意 OpenAI-compatible 地址
- 可在侧边栏配置最大轮次、工具次数、Token、时间、Provider 重试和单次费用上限
- 失败/取消/预算耗尽 checkpoint、附件草稿恢复，以及 Plan 确认后一键切换 Agent 执行
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
- 发布前应在最低支持版本和当前目标版本执行安装与核心流程 smoke test。不同 JetBrains IDE 产品的兼容性取决于其平台 build 和已用 API，尚未形成完整认证矩阵。
- `workspace-write` 在 macOS 使用 `sandbox-exec`，在 Linux 使用经过能力探测的 `bubblewrap`（`bwrap`）。两者探测失败都会拒绝启动，不会降级为未隔离执行。
- Windows 宿主进程不会伪装 AppContainer 能力：请在 WSL2 安装 `bubblewrap` 并通过 JetBrains WSL/Remote Development 打开项目。直接从 Windows IDE 启动时保持 fail closed。

安装后：

1. 打开右侧 **OmniCode** ToolWindow，在常驻侧栏选择 **API 与模型**，选择 Provider 并填写 API Key。
2. 点击 **保存并加载模型**，插件会先写入 Password Safe，再从供应商 API 获取当前账号可用的模型。
3. 选择模型后点 **Apply**；后续切换供应商会恢复各自上次使用的地址与模型。
4. 直接在 OmniCode 常驻侧栏配置运行控制、沙箱、MCP、Commit AI、提示词和 Skill 来源，无需跳转 IDEA Settings。
5. 在同一侧栏查看 Token、费用、趋势、历史与工具审计。
6. 打开侧栏顶层 **创意工坊**，选择工作台皮肤与桌宠；选择会立即保存，仅影响 OmniCode，不修改 IDE 全局主题。
7. 打开右侧 **OmniCode** Tool Window，按任务选择 **Agent**、**Plan** 或 **Research**；复杂任务可额外开启 **Team**，有副作用的工具仍只由主智能体执行并先展示审批对话框。

## Team 多智能体协作

`Team` 是独立于 Agent / Plan / Research 的执行策略。开启后，主智能体可按需并行委派最多 2 个只读专家；一次运行最多 2 轮、4 个专家。Explorer 负责代码事实，Planner 负责实施路径，Reviewer 负责风险与验证。每个专家只收到原始目标与自己的窄任务，不共享主智能体或其他专家的隐藏上下文，也不能写文件、运行命令、调用 MCP、发起审批或继续委派。

所有模型请求共享同一运行 Token / 费用硬预算；取消主任务会取消仍在运行的专家。聊天中会把专家状态、摘要与 Token 聚合在同一张 Team 卡片里，最终答案仍由主智能体统一输出。用量只按整次 workflow 聚合记录一次，工具审计则保留 agent ID 以便追踪。

开发时可运行沙箱 IDE：

```bash
./gradlew runIde
```

## Research 工作流

Research 面向代码调查、论文/资料分析和可复现实验记录。它不是 Agent 的高权限别名，权限在 Tool Registry 和执行层独立收紧：

| 模式 | 读取项目与 Skills | 运行命令 | 修改文件 | MCP / 外部工具 |
| --- | --- | --- | --- | --- |
| Agent | 允许 | 逐次审批并应用所选沙箱 | 审批、哈希复核后允许 | 连接及调用均需审批 |
| Plan | 允许 | 禁止 | 禁止 | 禁止且不建立连接 |
| Research | 允许 | 逐次审批并应用所选沙箱 | 禁止 | 禁止且不建立连接 |

Research 最终报告要求覆盖研究问题、假设、方法、证据、结果、局限、复现清单和引用，只引用实际检查过的资料，并明确区分直接观察、推断和未验证信息。未知或第三方工具默认归类为 `EXTERNAL`，不会进入 Plan 或 Research 工具面。`workspace-write` 下实验命令默认断网且仅能写工作区；若用户显式切换 `danger-full-access`，Research 仍不能调用文件修改或 MCP 工具，但命令进程本身不再具有 OS 级文件/网络隔离。

附件可通过 `+`、拖拽、剪贴板或输入 `@路径片段` 加入。`@` 只搜索当前项目内可安全读取的受支持文件，忽略 `.git`、构建输出、依赖目录和虚拟环境，并复用统一的大小、格式与敏感文件校验；弹层支持方向键选择、Enter/Tab 确认和 Esc 关闭。主要科研格式包括：

- PDF：仅在本地使用 PDFBox 提取可定位到页的文本，限制为 10 MB、300 页和 48,000 字符；拒绝加密、损坏或无可读文本的文档。扫描论文请上传关键页面截图并使用视觉辅助模型。
- Jupyter Notebook：严格解析 UTF-8 JSON，仅提取 Markdown/代码 cell；限制为 2 MB、200 个 cell、单 cell 12,000 字符、合计 48,000 字符，忽略 outputs、附件和 metadata。
- 科研文本：支持 `.tex`、`.bib`、`.r`、`.jl`、`.m`，以及 CSV/TSV/JSON/YAML 等常见数据和配置文本；所有文本仍受 UTF-8、控制字符和总体附件数量限制。

点击“设计可复现实验”或“分析论文与资料”会真实切换到 Research，而不是只修改提示词。Research 完成卡片、聊天更多菜单和 Tool Window 齿轮均可进入 **导出可复现实验研究包…**，输入框聚焦时也可按 `Cmd/Ctrl+Shift+E`。保存器会先展示消息/证据/截断摘要。导出文件是有界 Markdown，默认上限 512 KiB；SYSTEM Prompt 在任何处理前排除，模式/供应商/模型明确标记为“导出时配置”，并包含首个研究问题、经过截断和脱敏的会话、工具与命令证据、复现清单、限制和引用核对清单。图片只记录文件名、类型和大小，不导出二进制/base64；研究包不是事实证明或完整环境快照，分享前必须人工复核。

## 安全边界

- 模型只能提出工具请求，不能直接访问本机。
- `Plan` 只暴露只读工具；`Research` 只暴露只读与命令工具。两者都不会启动 MCP 或获得文件修改工具；Research 命令仍逐次审批并服从所选沙箱。
- 所有路径必须位于当前项目；同时检查规范路径和符号链接逃逸。
- `.env`、SSH/AWS 凭据和常见私钥路径默认禁止读取。
- 文件变更必须携带最近一次读取得到的 SHA-256；审批后再次校验。
- 命令不经过 shell 插值，禁用 shell、`sudo` 和脚本求值器；每次执行都需要审批。
- 子进程仅继承少量基础环境变量，不继承 API Key、Token、Secret 或 Password。
- macOS 的 `workspace-write` 使用经过读写探测的 `/usr/bin/sandbox-exec`：仅允许工作区读写，并阻止网络和外部用户目录访问；探测失败时拒绝执行。
- Linux 的 `workspace-write` 使用 `bubblewrap`：宿主运行时只读挂载、工作区读写挂载、用户目录隐藏、私有 HOME/tmp，并隔离网络命名空间。插件会实际验证文件边界和网络视图后才启用。
- `danger-full-access` 关闭 OS 隔离，适合经用户确认后执行 `adb`、`docker` 等系统级命令；审批、超时、输出边界和环境清理仍保留。

MCP Server 是外部系统。本地 stdio 首次启动或配置、项目、沙箱、环境变量名及可执行文件内容发生变化时必须审批；远程 Streamable HTTP 首次连接或 Endpoint/认证指纹变化时也必须审批。远程地址强制 HTTPS（字面 loopback 可使用 HTTP）、禁止重定向。OAuth 强制 PKCE S256/state/resource audience，回调只监听随机 `127.0.0.1` 路径；会话绑定已保存的 Endpoint、Client ID 和 Scope，跨连接刷新单飞，Bearer、Access/Refresh Token 和动态客户端密钥仅存 IDE PasswordSafe。每次 MCP 工具调用仍需用户批准。

## 项目结构

```text
src/main/kotlin/dev/omnicode/
  agent/       ReAct 循环、上下文和预算
  model/       Provider 无关的消息与工具块
  provider/    各协议适配器与预设
  mcp/         MCP stdio / Streamable HTTP 客户端和工具桥接
  persistence/ 用量、历史和工具审计的本地有界存储
  settings/    普通配置与 PasswordSafe
  tool/        文件、搜索、命令、进程沙箱和审批边界
  service/     项目生命周期与运行控制
  ui/          Tool Window、聊天和审批 UI
  workshop/    纯数据皮肤/桌宠目录与本地选择持久化
```

项目源码托管于 [GitHub](https://github.com/wuke123222/omnicode-agent)。详细边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，新增供应商见 [docs/PROVIDERS.md](docs/PROVIDERS.md)。贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，隐私说明见 [PRIVACY.md](PRIVACY.md)，安全漏洞报告请遵循 [SECURITY.md](SECURITY.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)，第三方组件许可与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。PDF 文本提取使用 Apache PDFBox 3.0.8（Apache License 2.0）；其依赖 JAR 保留上游许可元数据。

## 当前限制

- Team 当前是有界的主从协作：角色固定为 Explorer / Planner / Reviewer，最多并行 2 个、每次运行最多 4 个，不支持递归委派、自定义 Agent、跨运行长期记忆或多个 Agent 同时写文件。
- 暂不包含 Agent 浏览器自动化、Git push/PR 或无人值守后台任务。
- 不提供交互式 PTY、shell pipeline、`sudo`、删除/移动文件。
- 不预先上传整个仓库；模型需要通过工具按需读取。
- PDF 当前只做文本提取，不含 OCR；加密 PDF 和纯扫描 PDF 需先转换为可信文本，或上传关键页面截图。Notebook 不导入 outputs、附件和 metadata。
- 可复现实验研究包是脱敏、有界的会话证据清单，不包含 SYSTEM Prompt、API 凭据、完整进程环境、图片二进制或宿主机状态，也不能消除模型与外部服务的非确定性；导出时配置不代表每一轮历史运行配置。
- OpenAI-compatible 只保证协议适配，具体模型可能不支持流式工具调用。
- 动态模型发现覆盖 OpenAI-compatible、Gemini 和 Anthropic；Azure 使用部署名、Bedrock 使用模型 ID，因此保留手动配置入口。
- MCP OAuth 暂不支持 Client ID Metadata Documents、DPoP、`private_key_jwt`、`client_secret_basic`、多授权服务器交互选择，也不兼容旧版双端点 HTTP+SSE。
- `workspace-write` 当前在 macOS 与具备可用 `bubblewrap` 的 Linux 上提供 OS 级强制隔离；Windows 宿主和能力探测失败的平台会 fail closed。
- 金额为根据用量和用户配置的每百万 Token 单价计算的估算值，并非供应商账单。
- Bedrock 首版使用同步 Converse；凭据来自设置或 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`，暂不包含 AWS SSO/Profile credential chain。
- Gemini 首版使用可重放完整可见历史的 `streamGenerateContent`；Interactions 的 opaque continuation state 留待会话模型升级后接入。
- 创意工坊当前只提供内置、纯数据的皮肤与桌宠，不加载第三方脚本、类、命令或远程资源；皮肤范围仅限 OmniCode Tool Window。

## 许可证

OmniCode Agent 依据 [Apache License 2.0](LICENSE) 开源发布。
