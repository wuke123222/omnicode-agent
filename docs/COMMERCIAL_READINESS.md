# Commercial readiness plan

这份清单用于每个 Marketplace 版本的发布前验收。它把“功能存在”与“用户可稳定完成任务”分开，所有 P0 项必须有自动化证据或明确的 fail-closed 行为。

## P0：发布阻塞项

| 领域 | 交付物 | 验收证据 |
| --- | --- | --- |
| 安装兼容 | IntelliJ IDEA、PyCharm、WebStorm 的目标 build 通过 Plugin Verifier；plugin.xml 的 since/until-build 与 CI 矩阵一致 | `verifyPlugin` 报告全部 Compatible |
| 签名发布 | Marketplace 发布只允许受保护 environment；证书、私钥和 token 不进入日志；签名包先验证再上传 | `signPlugin verifyPluginSignature publishPlugin` 日志无 secret；签名 ZIP 可复核 |
| 运行可靠性 | 每个 workflow 记录阶段、模型请求、重试、工具失败、恢复点；失败任务可继续，不重放未知副作用 | ledger 查询、故障注入测试、恢复后审计记录 |
| 变更安全 | Agent 修改前后哈希复核；审阅账本跨重启；Git 外部差异只能逐文件确认 | 重启恢复测试、冲突测试、Git diff 导入测试 |
| 沙箱安全 | workspace-write 探测失败即拒绝；Windows 使用签名/哈希门禁的原生 AppContainer host，ACL 授权与恢复失败即拒绝；危险命令仍需审批 | macOS/Linux 边界测试、Windows runner 的 AppContainer/ACL smoke 与 fail-closed 测试 |
| 凭据边界 | API Key、OAuth token、MCP secret 只进入 PasswordSafe；诊断和研究包脱敏 | secret redaction 测试、ZIP 内容扫描、日志扫描 |

## P1：用户完成任务的体验

### 首次打开

1. 自动运行无凭据诊断，显示 API、模型、视觉、代理/DNS/TLS、MCP OAuth 和沙箱状态。
2. 每个失败项必须提供“打开配置 / 重试 / 导出脱敏诊断”动作；不能只显示错误字符串。
3. 首次发送前检查 Provider、模型能力和附件限制，给出可恢复的修复路径。

### 对话与任务

1. Single / 自动路由 / Team 在输入栏可见；自动路由对小任务保持 Single，跨模块、科研和复杂排障才启用专家。
2. Plan 模式在主聊天中弹出可编辑审批卡；用户可以继续规划、批准部分步骤、跳过、暂停或切换 Agent。
3. 任务中心统一显示运行、待批准、待恢复、失败和完成；失败项提供继续、重试、复制、回到检查点和可靠性详情。
4. 所有文件引用使用 `path:line` 或 `path:start-end` 可点击跳转，并在打开前重新验证项目边界和行号。
5. 窄侧栏、缩放和主题切换不能让输入框、工具栏或操作按钮重叠；操作不可用时必须有原因 tooltip。

### 审阅与恢复

1. “放弃检查点”必须二次确认，并在短窗口内可撤销；撤销不能覆盖更新的 checkpoint。
2. 恢复前显示缺失图片、未知副作用工具和当前工作区核对结果。
3. 可靠性中心显示总耗时、阶段耗时、模型请求、工具失败、重试原因和最近恢复点；任务中心列表也直接显示当前未完成阶段、阶段耗时、失败/重试计数和最近事件，失败阶段可明确定位。
4. 任务中心支持 `.omnitask` 加密导出/导入；导入任务必须显式继续，不自动重放未知副作用。

## P1：科研与附件

| 能力 | 安全边界 | 验收标准 |
| --- | --- | --- |
| PDF | 本地解析、有界页数/字符数；加密 PDF 仍拒绝；纯扫描尝试本机 Tesseract | 每段文本可关联页码；导出报告保留引用核对清单 |
| OCR | 可选本地 Tesseract（无引擎则明确回退视觉模型/关键页截图）；默认不上传原始 PDF | 最多 4 页、每页 4,000 字符和 2 秒超时；不把二进制写入 checkpoint |
| Notebook | 严格 UTF-8 流式解析；默认只读 source，可选纯文本 output preview | 富媒体、附件、metadata 永不物化；输出有独立字符上限 |
| 实验锁定 | 记录命令 argv、工作区、沙箱、依赖摘要和随机种子 | 运行前可审阅；导出包标记“导出时配置”，不伪装完整环境快照 |
| 数据分析 | CSV/TSV 有界解析、列类型推断、数值范围/均值、文本趋势和本地折线图 | 大文件截断有明确提示；图表、摘要和预览不把原始数据上传到非用户选择的服务 |
| 引用 | BibTeX/DOI 格式校验与重复检测 | 网络校验必须显式触发；失败显示未验证，不编造 DOI/作者 |

## P1：平台与供应商

- Windows：优先使用签名 native AppContainer host；未安装、探测失败或 ACL 事务无法证明完整恢复时通过 JetBrains WSL/Remote Development 使用 Linux bubblewrap，并保持 fail closed。
- Provider：保存 API Key 后动态发现模型，保留 Azure deployment name、Bedrock model ID 等手动配置；模型能力不确定时不猜测 wire 字段。
- 模型目录：1.10.0 将最后已知良好的模型 ID 以不含密钥的有界记录保存到 IDE 配置；网络刷新失败时保留并标注缓存列表，不覆盖用户当前选择。
- MCP：市场元数据只作未审阅目录；安装生成停用草稿，OAuth discovery、登录、刷新和每次工具调用都经过用户确认。1.10.0 增加安装前安全扫描，会拒绝任意 URL/Git 包源并标注未签名来源、latest 标签、远程凭据和本地可执行文件；真正的注册表签名证明、漏洞数据库和一键更新仍需外部供应链服务。
- TokenTracker：用量页只嵌入第三方本地仪表盘；OmniCode 不读取其数据库，不启动未经用户审阅的命令。
- Git/浏览器：`git_workflow` 和 `browser_automation` 只在 Agent 模式暴露；Worktree、PR、外部 URL、截图路径和网络动作均通过显式审批，Playwright/`gh` 由用户自行提供。
- 云端迁移：`WorkflowCloudSyncClient` 是对用户自建 HTTPS relay 的窄适配器，只传输客户端已加密包，不提供 OmniCode 托管服务或后台执行。

## QA 矩阵

每个候选版本至少覆盖：

- IntelliJ IDEA 2025.3 / 2026.1 / 2026.2，PyCharm 和 WebStorm；
- macOS、Linux bubblewrap、Windows 原生 fail-closed、Windows WSL Remote Development；
- 深色/浅色主题、100%/125%/150% 缩放、窄侧栏、单屏/多屏；
- 文件拖拽、剪贴板图片、Markdown/PDF/Notebook、`@` 文件引用；
- Agent、Plan 审批、失败恢复、变更审阅、MCP OAuth、TokenTracker 内嵌/外部兜底；
- 网络超时、429、TLS/DNS 失败、模型返回空内容、工具超时、IDE 重启和并发取消。

自动化分层：

1. 单元/服务测试：解析、策略、持久化、沙箱和脱敏。
2. Swing smoke：真实 EDT 点击关键按钮，验证可见性、启用状态和无重叠布局。
3. UI：`ui-regression.yml` 的手动入口已启动真实 IDE + Robot Server 并运行 `RemoteRobotSmokeTest`；普通 PR 使用无磁盘组件截图快速门禁。拖拽、Plan 批准、MCP OAuth 和多屏金标准仍需在受控桌面 runner 上继续扩展。
4. 发布门禁：`check buildPlugin verifyPlugin supplyChainSbom`，签名发布单独在受保护 environment 执行。

## 性能目标

- 打开 Tool Window 首次可交互时间不依赖网络，目标 < 1 秒；诊断、模型目录和 MCP 市场均后台执行。
- 输入响应、附件预览和滚动不在 EDT 读取文件或解码大图；流式文本合并后再刷新 UI。
- 可靠性 ledger、审阅账本和 checkpoint 都是有界追加/压缩，单次写入失败不得阻断只读聊天，但危险副作用前持久化失败必须 fail closed。
- 任何单个模型/工具失败都显示分类错误、重试原因和下一步动作，不让用户只能重新发送整段任务。冷仓库自动上下文预热最多占用 1.2 秒首请求预算，超时先发模型请求。

## 当前明确未完成

### 商业权益切片（2.0.0）

- 商业边界是“基础能力全免费”：Agent/Team、Git Worktree/PR、浏览器自动化、跨设备加密任务迁移、MCP、科研附件、可靠性中心和任务报告均不依赖许可证。Pro 只在用户主动触发时提供项目智能档案、批量任务配方、工程进展周报和带实验锁定信息的研究包；所有导出均有界并在后台线程执行。
- `plugin.xml` 已声明 `POMNICODE` Freemium product descriptor（`optional=true`），Pro 页调用 IDE 原生试用/购买入口；`OmniCodeMarketplaceLicense` 读取固定产品 confirmation stamp，`JetBrainsLicenseStampVerifier` 按官方证书链校验在线/离线 key 与 License Server stamp，检查结果有界缓存且支持显式刷新。
- `LicensingFacade` 未初始化时不会被误判为无许可证；无 stamp、无效签名、未知格式和证书链失败均回到 Free。旧版 `OmniCodeLicenseVerifier` 与 Password Safe token 只保留给已有用户迁移，不再承接新购买。
- 仍需外部上线条件：在 JetBrains Marketplace Vendor 组织中确认 `POMNICODE` 可用并完成注册，提交 Banking Information、Sales Info、价格、试用期和 Developer EULA，通过 Freemium 商业审核。源码与本地 Demo 只能验证技术接入，不能代替 JetBrains 创建真实商品或结算关系。

- 原生 Windows AppContainer host 已实现并接入 Windows runner：`native/windows/omnicode-appcontainer-host.cpp` 使用 per-run profile、无 network capability、有界 ACL 恢复和显式最小子进程环境（不继承 IDE/JVM 密钥）；1.10.0 补充 profile 临时目录 ACL、环境泄漏 smoke 以及普通用户 smoke。Marketplace 发布前仍需在受信任的 Windows 证书环境完成 Authenticode 签名，并把签名后二进制及 `.sha256` 放入插件 ZIP；缺失签名材料时插件继续 fail closed。真实 Runner 仍必须通过这条门禁才能宣布闭环。
- Remote Robot 已有真实 IDE/Robot Server 手动 CI smoke；完整截图金标准、多屏和拖拽回归仍需额外桌面 runner 配置，当前 `UiScreenshotRegressionTest` 继续覆盖确定性组件。
- Git Worktree/PR、Playwright 浏览器自动化和加密任务包已实现本地边界；PR 网络、浏览器运行时和跨设备 relay 仍需要用户安装/提供外部服务凭据。
- OCR 使用可选本地 Tesseract；CSV/TSV 已提供有界本地统计、文本趋势和数值折线图。BibTeX/DOI 网络校验仍保持离线边界；`.bib` 已提供有界格式/重复检查，但不声称 DOI 可解析。当前实验锁定已记录相对工作区、沙箱、可选依赖摘要/随机种子及成功 argv，但不会自动采集完整依赖环境或随机种子。
