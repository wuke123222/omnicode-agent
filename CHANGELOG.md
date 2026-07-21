# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的格式，并使用语义化版本号。

## [0.9.0] - 2026-07-21

### Added

- 新增侧边栏顶层“创意工坊”，提供 JetBrains Native、Graphite Night、Aurora Night、Forest Terminal 和 Paper Studio 工作台皮肤。
- 新增 Pixel Cat、Code Owl、Rubber Duck 和 Tiny Robot 动画桌宠，并与 Agent 思考、工具调用、完成和失败状态联动。

### Changed

- 插件版本升级为 `0.9.0`，MCP client info、Provider HTTP 与 OAuth User-Agent 同步更新。

### Security

- 创意工坊目录仅接受编译期纯数据，拒绝未知 ID、标记文本、路径与非标准颜色；桌宠包不能注册脚本、命令、类、URL 或反射入口。
- 损坏或已移除的桌宠 ID 会回退到默认项并自动禁用，避免持久化状态意外启用新行为。

## [0.8.0] - 2026-07-19

### Added

- 新增 `Research` 模式：按“研究问题、假设、方法、证据、结果、局限、复现清单、引用”组织研究报告，并要求区分直接观察与推断；不得编造论文、作者、DOI、URL、测量值或实验结果。
- 支持本地有界提取 PDF 论文文本（最大 10 MB、300 页、48,000 字符）和 Jupyter Notebook 的 Markdown/代码 cell（最大 2 MB、200 个 cell、48,000 字符）；Notebook 输出、附件和元数据不会注入上下文。
- 科研附件补充 LaTeX、BibTeX、R、Julia 和 MATLAB 等安全文本格式；扫描型 PDF 可改为上传关键页面截图并使用视觉辅助模型。
- 聊天输入框支持输入 `@` 搜索项目内受支持文件，并通过与拖拽/文件选择相同的附件校验链加入任务。
- 新增可复现实验研究包导出：生成有界、脱敏的 Markdown，记录模式、供应商、模型、研究问题、会话、工具/命令证据、复现清单、局限和引用核对清单。
- 科研空状态卡片会原子切换到 `Research` 再填入任务；`@`/`!` 弹层支持方向键、Enter/Tab 与 Esc，附件托盘在窄窗口中改为单行横向滚动。

### Changed

- 模式选择扩展为 `Agent` / `Plan` / `Research`；用量趋势、工具审计、会话持久化和助手消息标签均记录 Research。
- 插件、MCP client info、Provider HTTP 和 MCP OAuth 的运行时版本标识统一为 `0.8.0`。

### Security

- Research 仅暴露 `READ_ONLY` 与 `COMMAND` 工具；文件修改、未知/第三方外部工具和 MCP 均 fail closed。命令仍逐次审批，并保留 argv 直执行、超时、输出边界、环境清理和所选进程沙箱。
- PDF 使用内存型、有页数/字符上限的本地解析；加密、损坏、超限或无可读文本的 PDF 会被拒绝，不会静默上传原始文档到辅助模型。
- Notebook 使用严格 UTF-8 JSON 流式解析，只保留有界的 Markdown/代码 cell，并拒绝 NUL 或过多控制字符。
- 研究包在任何统计和脱敏前彻底排除 SYSTEM 消息；后台只读取已配置的供应商/MCP 凭据作为内存脱敏字典，凭据本身不会写入导出。自由文本具有脱敏前单块/总输入预算，图片 base64 被省略。
- 新建导出使用无覆盖发布；覆盖已有文件必须确认并绑定目标的 NOFOLLOW 文件身份，写入前若路径、类型、fileKey、大小或修改时间发生变化会 fail closed。

### Licensing

- 引入 Apache PDFBox 3.0.8（Apache License 2.0）用于本地 PDF 文本提取；组件来源和许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## [0.7.0] - 2026-07-19

### Added

- MCP OAuth 2.1：401/403 challenge、RFC 9728/RFC 8414/OIDC 发现、PKCE S256、state、resource audience、动态客户端注册和 Token 刷新。
- MCP 侧边栏新增无认证/Bearer/OAuth 模式、OAuth 登录与退出。
- 图片附件安全缩略图与尺寸信息，Markdown、源码、日志和文本附件支持本地有界预览。
- 模型列表支持搜索、显示全部和用途标签，仍可手动输入任意 Model ID。

### Changed

- 默认隐藏明确用于 Embedding、图片、音频、Realtime、审核、视频、重排序和 OCR 的非对话模型，未知新模型保守保留。
- 模型发现请求相互隔离，刷新失效后必定恢复 UI；空目录仍保留当前模型和手动 Model ID 入口。
- 剪贴板图片采用单并发、有界 PNG 编码和 800 万像素上限；文本预览严格限制扫描范围。
- MCP/OAuth 网络请求共享 Provider 代理策略。

### Security

- OAuth 回调仅监听 `127.0.0.1`，使用随机路径、一次性 state、严格 Host 校验、3 分钟超时和无缓存/CSP 响应。
- Access/Refresh Token 与动态客户端密钥仅存 PasswordSafe，OAuth 错误不回显验证码、密钥或 Token。
- OAuth 会话绑定已保存的 Endpoint、认证模式、Client ID 与 Scope；跨连接刷新单飞，退出登录或永久授权失败后在途结果不能恢复旧凭据。
- 删除 MCP Server 只在配置成功保存后清理 PasswordSafe，取消或重置不会误删凭据。
- 远程 MCP 不允许通过 challenge 将 resource metadata 发现重定向本机 loopback；loopback 请求永不经过代理。

## [0.6.0] - 2026-07-19

### Added

- MCP 2025-11-25 Streamable HTTP：JSON/SSE、Session 恢复、PasswordSafe Bearer Token、连接审批和安全工具发现。
- 侧边栏“运行控制”：可配置 Agent 轮次、工具、时间、Token、Provider 重试和单次费用上限。
- Linux `workspace-write` 新增经过文件与网络能力探测的 bubblewrap 后端，提供私有 HOME/tmp、只读宿主运行时与默认断网。
- 支持拖拽上传 Markdown、文本、日志、数据和常见源码文件，并可直接粘贴剪贴板截图。
- 贡献规范、安全漏洞报告政策和持续集成构建检查。

### Changed

- Provider 对 429、5xx 和网络故障支持有界指数退避与 `Retry-After`，流式输出开始后不自动重放。
- 沙箱页新增本机能力检测和跨平台安装指引。

### Security

- Windows 宿主在没有可证明的 AppContainer/WSL 路径边界时保持 fail closed，并提供 WSL Remote Development 迁移指引。
- 远程 MCP 强制 HTTPS（loopback 除外）、拒绝重定向并对返回文本中的 Bearer Token 脱敏。
- 危险工具的审批结果必须在执行前成功持久化；审计失败时拒绝执行。

## [0.5.0] - 2026-07-19

### Added

- 精确上下文 Patch 工具，支持审批前后哈希校验和单次 IDE Undo。
- Plan 完成后可确认并一键切换 Agent 执行。
- 编辑器与项目树右键菜单可将当前文件或选区发送到 OmniCode。
- 失败、取消及预算耗尽任务支持 checkpoint 和“编辑后重发”。

### Security

- API 凭据按供应商与 Origin 隔离，远程地址强制 HTTPS，并禁止自动重定向。
- MCP 启动新增项目级配置指纹、显式审批、持久信任撤销和工具审计。

## [0.4.0]

当前开发版本。功能概览和已知限制见 [README.md](README.md)。
