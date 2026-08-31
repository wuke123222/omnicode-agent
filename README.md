# OmniCode Agent

[![Verify](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml/badge.svg)](https://github.com/wuke123222/omnicode-agent/actions/workflows/verify.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

OmniCode Agent 是面向 JetBrains IDE 的开源代码智能体。3.0 主界面参考 CCGUI 的信息架构，固定为 **聊天、历史记录、设置** 三个视图；任务、子代理、计划和变更审阅都留在当前对话中，不再用常驻功能侧栏打断工作流。

## 3.0 工作区

- JCEF + React 主界面；切换历史或设置时不卸载当前聊天。
- 用户消息、助手 Markdown、代码块、可点击 `文件:行号`、工具过程和错误提示分层展示。
- 长会话使用虚拟滚动；实时输出和历史恢复共用 `ChatEventEnvelopeV1` 归一化管线。
- 对话内 Tasks / Subagents / Edits 抽屉，以及可编辑、逐步批准、跳过、暂停、重试的 Plan 卡片。
- 输入框支持桌面/项目文件拖拽、图片、Markdown、安全的 `@文件` 引用、`/` 命令、模式、协作策略、引擎、模型和取消。
- 设置中心集中管理供应商、依赖、用量、权限、提示增强、Commit AI、MCP、Agents、提示词、Skills、主题和桌宠。
- 所有保留功能免费；3.0 不声明 Marketplace 付费商品，也没有许可证或试用门槛。

## Agent 与 Plan

- `Agent` 可以提出项目修改和命令；所有副作用仍经过 OmniCode 的审批、路径校验、哈希复核、沙箱和审计边界。
- `Plan` 只能读取文件、搜索代码和使用 IDE 索引，不能修改项目或启动 MCP。
- `Claude Plan` 可在强制只读、无网络的工作区沙箱中执行经策略证明为只读的探索命令。
- `/plan <任务>` 与 `/claude-plan <任务>` 生成对话内计划；批准前不会转入执行。
- `/review [要求]` 使用内部只读审阅约束，不新增第四个主界面模式。
- `Single`、`自动协作` 和 `Team` 与权限模式正交；专家只读且不能递归委派，最终结果由主智能体综合。

常用命令：

| 命令 | 行为 |
| --- | --- |
| `/agent <任务>` | 使用 Agent 模式执行 |
| `/plan <任务>` | 只读探索并生成 Plan 卡 |
| `/claude-plan <任务>` | Claude Code 风格只读规划 |
| `/review [要求]` | 只读审阅当前项目与差异 |

## 模型与本地引擎

普通 API 支持 OpenAI Responses / Chat、Anthropic Messages、Gemini、Azure OpenAI、AWS Bedrock Converse、OpenCode Zen，以及 OpenRouter、DeepSeek、Groq、xAI、Mistral、Together、Cerebras、Qwen、Kimi、SiliconFlow、GLM、Ollama、LM Studio 和自定义 OpenAI-compatible 服务。

每个供应商保存独立的地址、模型、代理和超时配置；API Key 只写入 JetBrains PasswordSafe。保存凭据后可从供应商接口发现当前账号可用模型；加载失败时保留最近一次成功缓存，并区分网络、凭据与模型权限错误。

本地引擎注册表覆盖：

- Claude Code
- Codex
- Grok CLI
- Kimi CLI
- OpenCode
- Pi CLI
- OMP CLI
- DSH

检测和启动共用跨平台路径解析。Finder / Toolbox 启动的 IDE 会补全常见 Node、uv、npm、nvm、fnm、Volta、mise 和 Windows 安装目录；POSIX npm launcher 会直接使用已发现的 Node，Windows `.cmd/.bat` 只解析受控的本地 npm Node 入口，不把用户 Prompt 拼入 `cmd.exe`。

OpenCode 使用官方 headless Server：插件在随机回环端口启动受 Basic Auth 保护的本地服务，先建立 SSE 订阅，再发送异步 Prompt，只接收当前 Session 的事件，并以 `session.status=idle` 完成任务；不会再依赖常驻进程退出或用 15/30/45 秒初始化硬截止误判失败。取消会调用 Session abort，传输错误保留会话，危险操作只允许逐次审批。OpenCode、OMP 与 DSH 的不透明原生 Session ID 会按 OmniCode 会话保存，提示词、输出和凭据不会进入该状态。DSH 通过本机 `dsh web` 的 workspace/session RPC 与事件流运行，危险工具请求仍逐次进入 OmniCode 审批。

## 项目上下文与附件

OmniCode 按需读取项目，不预先上传整个仓库。上下文遵守 `.gitignore`、`.aiignore`、`.omnicodeignore`、敏感路径禁令、符号链接边界和文件/字符上限。支持项目规则、`AGENTS.md`、`CLAUDE.md`、`.omnicode/rules/*.md`、Pinned Context、PSI/符号索引和关键词检索。

附件可通过 `+`、拖拽、剪贴板或 `@路径` 加入：

- 图片：有界解码和预览；主模型无视觉能力时可使用同一供应商的视觉辅助模型生成说明。
- Markdown 与常见源码/文本：UTF-8、有界读取，不执行文件内容。
- PDF：PDFBox 本地提取带页码文本；可选本地 Tesseract 对少量扫描页 OCR；拒绝加密或损坏文件。
- Notebook：只提取有界 Markdown/代码单元，不导入二进制输出、附件和元数据。
- CSV/TSV：生成有界列信息和摘要；原始数据仍受附件限制。

旧版独立“实验与科研”和 Semi Design 页面不再暴露。论文、OCR、数据分析和图片转代码都作为普通聊天任务，复用同一附件、安全和审阅边界。

## MCP、Skills 与提示词

设置中的 MCP 管理器支持 stdio 和 Streamable HTTP。市场先展示离线精选，再有界读取官方 Registry；添加只创建默认停用草案，用户审阅并启用后才会连接或启动进程。

- stdio：argv 解析、项目目录、环境变量名、进程沙箱和首次启动审批。
- HTTP：HTTPS/loopback 校验、禁止重定向、Bearer PasswordSafe 存储。
- OAuth 2.1：资源/授权服务器发现、PKCE S256、state、resource audience、动态注册和刷新；登录前必须由用户确认。
- 每次 MCP 工具调用仍受 OmniCode 工具审批，不因市场安装获得额外权限。

提示词模板可通过 `!快捷名` 插入；Skills 只从用户配置的本机目录读取。仓库中的 Skill、规则和 Harness 始终是不可信项目数据，不能覆盖系统、权限、沙箱或审计策略。

## 可靠性与变更审阅

- 每个 workflow 持久化阶段、模型请求、重试原因、工具失败、恢复点和脱敏诊断。
- 失败或中断后可从最近安全检查点继续；未知副作用不会自动重放。
- `apply_patch` / `apply_change` 使用最近读取内容的 SHA-256，审批前后都复核。
- 对话内 Edits 面板支持逐变更块、逐文件保留或回退，以及确认后的整任务回退；重启后仍可恢复审阅账本。
- 取消会终止当前模型请求、插件启动的 CLI 进程树和 MCP 连接收尾，并隔离迟到事件。

## 构建与安装

要求 JDK 21。Gradle Wrapper 会自动下载构建工具。

```bash
./gradlew test
./gradlew check buildPlugin verifyPlugin
```

插件 ZIP 位于 `build/distributions/`。在 JetBrains IDE 中选择 **Settings → Plugins → ⚙ → Install Plugin from Disk** 安装。开发预览使用：

```bash
./gradlew runIde
```

## 平台与安全边界

- 当前最低 IDE build 为 `253`；CI 验证 IntelliJ IDEA、PyCharm 和 WebStorm 目标。
- `workspace-write` 在 macOS 使用 `sandbox-exec`，Linux 使用经过能力探测的 `bubblewrap`，Windows 使用经过哈希/签名门禁的 AppContainer helper；后端不可用时 fail closed。
- `danger-full-access` 仅放宽进程 OS 隔离，不取消工具审批、超时、输出边界、凭据清理和审计。
- 命令以 argv 数组执行，不经过 shell 插值；不提供交互 PTY、pipeline、`sudo`、删除或移动文件。
- API、CLI 和 MCP 的凭据不发送给 WebView，不写入项目，不进入 checkpoint、历史导出或诊断包。
- OpenAI-compatible 只保证协议形状；模型是否支持视觉、流式工具调用和推理等级取决于实际服务。
- DSH 使用持久化 `dsh web` Host RPC，不允许退回到猜测的一次性命令；Host、事件订阅和审批桥必须同时可用才会标记为可执行。

## 项目结构

```text
src/main/kotlin/dev/omnicode/
  agent/       执行循环、事件、检查点与协作
  harness/     运行前预检和有效工具面
  mcp/         MCP 客户端、OAuth 与工具桥
  provider/    API 与本地引擎适配器
  service/     项目生命周期、上下文与持久化协调
  settings/    普通配置与 PasswordSafe
  tool/        文件、命令、沙箱和审批边界
  ui/          JetBrains 原生审批与 JCEF 宿主
webview/       React 三视图工作区
```

详细设计见 [架构说明](docs/ARCHITECTURE.md)、[Harness 格式](docs/HARNESS.md)、[隐私说明](PRIVACY.md)、[安全策略](SECURITY.md)、[第三方声明](THIRD_PARTY_NOTICES.md)和[发布手册](docs/RELEASING.md)。源码托管于 [GitHub](https://github.com/wuke123222/omnicode-agent)。

## 许可证

OmniCode Agent 依据 [Apache License 2.0](LICENSE) 开源发布。复用的 CCGUI MIT 组件与固定来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
