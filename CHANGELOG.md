# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的格式，并使用语义化版本号。

## [0.13.0] - 2026-07-21

### Added

- 主智能体运行中在本机写入有界、脱敏的 workflow checkpoint；IDE 重启后会把未完成记录标记为中断，并在聊天中提供明确的“继续任务”或“放弃检查点”。
- 新增认证、权限、限流、网络/超时、模型能力、预算、沙箱、配置、取消和未知错误分类，并提供检查配置、切换模型、调整预算、打开沙箱或编辑重试等对应入口。

### Changed

- 上下文压缩现在同时保护系统约束、最初用户目标、当前运行的用户目标/验收条件/约束和最新消息；单个历史组超限时会继续尝试可容纳的其他组。
- Agent 在自身捕获的预算、取消和失败边界输出确定性的“已完成 / 证据 / 剩余 / 风险”部分结果，不为终止摘要额外调用模型或工具。
- OpenAI Chat/Responses、Gemini、Bedrock 与 MCP 客户端对流式或 JSON-RPC 响应中的 `null`、标量及无效数组项进行容错或返回明确协议错误，不再把 Gson 类型转换异常泄漏为 Agent 中止。
- 恢复图片只使用并消费检查点明确要求的数量；要求为零时不触碰当前草稿附件，草稿合并也不会超过附件上限或抢占仍在读取的文件。
- 插件版本升级为 `0.13.0`。

### Security

- checkpoint 中待执行或执行状态不确定的工具不会在恢复时自动重放；恢复先核对当前项目状态，模型再次提出副作用时仍走新的审批和工具校验。
- 危险工具在获批后、真正执行前必须先持久化 `executionStarted` 检查点；写入失败会阻止工具执行，等待审批期间取消不会被误报为副作用状态未知。
- checkpoint 仅保存有界的文本快照、工具观察、预算和执行元数据，并在落盘前脱敏；图片及其他附件二进制不会写入 checkpoint。
- 恢复只覆盖当前设备上的 lead workflow，不会启动无人值守后台任务，也不会恢复跨设备状态或重建完整多智能体任务板。

## [0.12.0] - 2026-07-21

### Added

- 新增按 Provider/模型动态筛选的推理强度：自动、关闭、最低、低、中、高、超高和全速；聊天底栏与侧栏供应商配置均可选择。
- 新增“全速项目预设”，经确认后把 workflow 累计输入/输出预算各提高到 `10,000,000,000` Token，并放宽到 128 轮、256 次工具调用和 1 小时运行时间。

### Changed

- OpenAI Responses/Chat、OpenRouter、Anthropic Messages、Gemini 2.5/3 和 Bedrock Claude/Nova 2 在已验证模型上使用各自原生推理字段；未知兼容模型改用不发送额外 wire 参数的 Agent 执行强度。
- 高推理档位同步提高单轮输出下限和 Provider 请求超时；视觉辅助与 Commit AI 保持 `Auto`，不会继承主模型全速设置。
- Gemini usage 计入思考 Token，Anthropic/Bedrock 工具续轮保留供应商签名的思考块。
- 插件版本升级为 `0.12.0`，MCP client info、Provider HTTP 与 OAuth User-Agent 同步更新。

### Security

- 百亿 Token 仅是 workflow 累计硬预算，不绕过供应商单次限制、费用上限、工具审批或进程沙箱；启用全速与百亿预设均需显式确认。
- 未知或无法验证推理协议的模型不发送猜测字段；仅开放可由本地 Agent 安全实现的低/中/高/全速，原生语义不可模拟的档位会隐藏。

## [0.11.0] - 2026-07-21

### Added

- 新增原创虚拟主唱 Lumi 与吉他手 Aster，使用宿主矢量绘制并响应待命、思考、工具、完成和失败状态。
- 创意工坊新增本地 PNG/JPG 虚拟偶像立绘导入、替换、移除，以及五种状态即时预览。

### Changed

- 插件版本升级为 `0.11.0`，MCP client info、Provider HTTP 与 OAuth User-Agent 同步更新。
- 自定义立绘最长边统一缩至 512 像素，加载后由内存缓存复用，避免工作台刷新重复读盘。

### Security

- 不捆绑或宣传未经授权的第三方角色素材；用户导入前必须确认拥有版权或使用授权。
- 导入图片限制为 8 MB、32–2048 像素且最多 419 万像素的真实 PNG/JPEG；拒绝符号链接、GIF、SVG 和伪格式。
- 图片在后台解码到全新 ARGB 缓冲区，剥离元数据并重新编码为固定位置 PNG；源路径不持久化、不进入项目、模型或 MCP，原子替换失败会保留旧立绘。

## [0.10.0] - 2026-07-21

### Added

- 新增独立 `Team` 执行策略：主智能体可并行委派 Explorer、Planner、Reviewer 完成代码事实调查、实施规划和风险评审。
- 新增 Team 进度卡、每个专家的状态/摘要/Token 展示，以及历史会话策略恢复。
- 新增 workflow 共享 Token/费用账本、聚合用量记录和按 agent ID 隔离的工具审计。

### Changed

- 插件版本升级为 `0.10.0`，MCP client info、Provider HTTP 与 OAuth User-Agent 同步更新。
- Agent / Plan / Research 与 Single / Team 正交组合；一次运行最多并行 2 个专家、共 2 轮 4 个专家。
- 视觉辅助模型也进入同一 workflow 用量账本；供应商缺少 usage 时会估算文本与结构化工具调用的完整输出。

### Security

- 专家使用独立空历史与新的 Provider/AgentEngine，只收到有界原始目标和自己的任务；不共享主智能体或兄弟专家上下文。
- 专家固定为 Plan 权限，只能使用只读项目/Skill 工具，不能写文件、运行命令、连接 MCP、发起审批或递归委派；所有副作用仍只由主智能体执行。
- 并发模型请求先预留共享预算，取消会传播至全部专家；专家事件和返回摘要均有长度上限。
- 共享账本分别执行输入、输出、总 Token 与费用硬限制，避免并发专家绕过单次运行预算。

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
