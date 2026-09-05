import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.omnicode"
version = "3.0.14"

// Keep local verification lightweight while allowing CI to fan out one IDE per matrix job.
val pluginVerifierTargets = linkedMapOf(
    "idea-253" to (IntelliJPlatformType.IntellijIdea to "2025.3.6"),
    "idea-261" to (IntelliJPlatformType.IntellijIdea to "2026.1.3"),
    "idea-262" to (IntelliJPlatformType.IntellijIdea to "2026.2"),
    "pycharm-253" to (IntelliJPlatformType.PyCharm to "2025.3.6"),
    "webstorm-253" to (IntelliJPlatformType.WebStorm to "2025.3.6"),
)

// Windows release builds run CMake first and place the signed native host here. Ordinary
// non-Windows builds simply omit the optional binary; ProcessSandbox then remains fail-closed.
val nativeWindowsAppContainerHost = layout.projectDirectory.file(
    "native/windows/build/bin/omnicode-appcontainer-host.exe",
)
val nativeWindowsAppContainerHash = layout.projectDirectory.file(
    "native/windows/build/bin/omnicode-appcontainer-host.exe.sha256",
)

repositories {
    mavenCentral()
    // Prefer the canonical JetBrains repository before the cache redirector used by
    // defaultRepositories(). The redirector can transiently return 5xx responses on
    // Windows runners; keeping the direct endpoint first makes tagged releases resilient.
    maven("https://www.jetbrains.com/intellij-repository/releases/")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    intellijPlatform {
        intellijIdea("2025.3.6")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
    // Remote Robot's Retrofit adapter brings Gson 2.10; exclude it so IntelliJ's bundled Gson
    // remains the single runtime implementation (Notebook parsing uses its newer API).
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    testRuntimeOnly("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}


intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        certificateChainFile.set(
            layout.file(
                providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map(::File),
            ),
        )
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        privateKeyFile.set(
            layout.file(
                providers.environmentVariable("PRIVATE_KEY_FILE").map(::File),
            ),
        )
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    pluginVerification {
        ides {
            val requestedTarget = providers.gradleProperty("pluginVerifierIde")
                .orElse("idea-253")
                .get()
                .trim()
                .lowercase()
            val selectedTargets = if (requestedTarget == "all") {
                pluginVerifierTargets.values
            } else {
                listOf(
                    requireNotNull(pluginVerifierTargets[requestedTarget]) {
                        "Unknown pluginVerifierIde '$requestedTarget'. " +
                            "Expected one of: ${pluginVerifierTargets.keys.joinToString()}, all"
                    },
                )
            }
            selectedTargets.forEach { (type, targetVersion) ->
                create(type, targetVersion)
            }
        }
    }

    pluginConfiguration {
        id = "dev.omnicode.agent"
        name = "OmniCode Agent"
        version = project.version.toString()
        description = """
            <p>OmniCode Agent is a free, provider-neutral coding agent for JetBrains IDEs.</p>
            <ul>
              <li>A focused Chat, History, and Settings workspace with inline Plan, Tasks, Subagents, and reviewed Edits.</li>
              <li>Model-aware reasoning levels from Auto through Full Speed, using native controls when verified and safe Agent-only controls otherwise.</li>
              <li>Bring-your-own-key support for major model APIs and OpenAI-compatible services.</li>
              <li>Reviewed code edits, approved commands, workspace sandboxing, MCP, Skills, and prompt templates.</li>
              <li>Agent Harness preflight plus a project Harness for rules, knowledge maps, argv feedback loops, recovery-safe tool surfaces, and indexed large-repository context.</li>
              <li>One-click credential-presence, network, model, MCP OAuth and sandbox diagnostics with redacted export.</li>
              <li>Project and desktop attachments including images and Markdown.</li>
              <li>Local, redacted lead-workflow checkpoints with explicit resume or discard after an IDE restart.</li>
              <li>Theme and optional local desktop-pet controls live in Settings instead of occupying the main navigation.</li>
              <li>Local history, TokenTracker-powered usage dashboard, tool auditing, and durable task recovery.</li>
              <li>Claude Code, Codex, Grok, Kimi, OpenCode, Pi, OMP, and DSH local-engine integration.</li>
              <li>All retained features are free; no license or trial gate is required.</li>
            </ul>
            <p><a href="https://github.com/wuke123222/omnicode-agent">Source code</a> ·
            <a href="https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md">Privacy notice</a></p>
        """.trimIndent()
        changeNotes = """
            <h3>3.0.13</h3>
            <ul>
              <li>修复窄面板 Composer 下拉框被遮挡：运行模式和执行策略改为向上展开的可访问菜单，发送按钮始终可见。</li>
              <li>修复中文输入法 Enter 误发送与普通 Enter 无法发送的问题；Shift+Enter 继续换行。</li>
              <li>修复 OpenCode 仅收到 busy 心跳时无限延长等待，以及失败/取消后界面持续旋转；停止操作现在立即恢复输入并回收后台 CLI。</li>
            </ul>
            <h3>3.0.10</h3>
            <ul>
              <li>修复 OpenCode 事件流静默时绕过无进展看门狗的问题；现在会在有界时间内取消挂起请求并保留可恢复会话。</li>
            </ul>
            <h3>3.0.9</h3>
            <ul>
              <li>修复 OpenCode 请求取消和无响应卡死：取消会中断阻塞读取、回收读取线程，并对无进展连接提供有界看门狗；迟到事件不会重新打开已结束任务。</li>
              <li>修复历史/实时时间线重复合并与孤儿运行状态，恢复会话后能正确显示最终状态。</li>
            </ul>
            <h3>3.0.8</h3>
            <ul>
              <li>修复 Windows 用户目录 PATH 生成中的混合分隔符，确保 GUI 启动的 Node/NPX MCP 服务可正确解析运行时路径。</li>
            </ul>
            <h3>3.0.7</h3>
            <ul>
              <li>修复 Windows CI 中 MCP 运行时路径测试使用 Unix 固定路径导致发布构建失败的问题。</li>
            </ul>
            <h3>3.0.6</h3>
            <ul>
              <li>修复 Windows 发布检查失败时无法诊断的问题，并重新验证 CLI 取消、会话恢复和多会话隔离。</li>
            </ul>
            <h3>3.0.5</h3>
            <ul>
              <li>修复本地 CLI 父进程退出后子进程仍持有 stdout 管道导致运行时预检或响应读取卡住的问题；退出后输出排空现在有界且可取消。</li>
            </ul>
            <h3>3.0.4</h3>
            <ul>
              <li>修复 Codex 原生会话停止/超时后仍占用输出线程的问题；MCP 连接、会话切换、历史恢复和审阅操作增加任务级隔离与可恢复性。</li>
              <li>连接错误现在提供脱敏诊断、打开设置和重试入口；文件引用支持行号范围跳转，长对话避免流式内容被旧快照覆盖。</li>
            </ul>
            <h3>3.0.3</h3>
            <ul>
              <li>修复 Windows 发布流水线在标签版本检查阶段重复启动 Gradle 导致网络超时的问题；版本检查现在不依赖外部下载。</li>
            </ul>
            <h3>3.0.2</h3>
            <ul>
              <li>Team 模式现在会为不暴露工具调用的本地 CLI 启动真实的 Codex 原生子代理，并将有界证据交给主会话汇总。</li>
              <li>修复并行会话的子代理事件合并问题；每个子代理拥有稳定且独立的时间线区块。</li>
              <li>设置与新会话默认使用自动策略：小任务保持单 Agent，复杂任务才启用 Team。</li>
            </ul>
            <h3>3.0.1</h3>
            <ul>
              <li>修复 OpenCode 已完成后仍持续旋转：同步消息响应作为终态，迟到阶段事件不再重新打开运行状态。</li>
              <li>重新整理对话时间线，只保留一个当前进度行，工具、子代理和变更卡保持完整宽度。</li>
              <li>会话任务按 conversation ID 隔离；新会话可并行运行，历史中可识别并切回后台运行的会话。</li>
              <li>后台会话事件、取消、终态持久化和审阅结果不再串入当前会话。</li>
            </ul>
            <h3>3.0.0</h3>
            <ul>
              <li>重构为聊天、历史、设置三视图，计划、任务、子代理和变更审阅回归当前对话。</li>
              <li>新增统一事件信封和 JCEF/React 实时/历史管线，切页不再卸载聊天。</li>
              <li>统一八类本地 CLI 引擎，保留普通 API 供应商和既有安全边界。</li>
              <li>移除独立科研、Semi Design 和商业许可证入口；所有保留功能免费。</li>
            </ul>
            <h3>2.2.14</h3>
            <ul>
              <li>修复 OpenCode 已提交请求后被 15 秒心跳误标为“模型请求完成”的问题，明确区分项目快照、上游排队和回答生成。</li>
              <li>收到 OpenCode 最终 step_finish(stop/length) 后立即完成任务并终止单次进程树，不再等待非必要的项目快照清理或进程退出。</li>
            </ul>
            <h3>2.2.13</h3>
            <ul>
              <li>修复本地 CLI 子进程 stdin 未关闭导致 OpenCode 在读取非 TTY 输入时永久等待、会话无法创建的问题。</li>
              <li>所有参数式单次 CLI 请求和模型发现都会立即发送 EOF，继续保留取消、超时和隔离边界。</li>
            </ul>
            <h3>2.2.12</h3>
            <ul>
              <li>OpenCode 单次任务改用官方支持的内存会话数据库并显式使用非交互 build Agent，移除固定数据库的 WAL/初始化竞态。</li>
              <li>请求到达模型前若本地会话卡住，会自动重启一次全新隔离会话；失败阶段不再被 UI 误标为“初始化完成”。</li>
            </ul>
            <h3>2.2.11</h3>
            <ul>
              <li>OpenCode 子进程使用独立的模型 cache/state 运行区，避免与 JetBrains 内置 OpenCode ACP 争用全局锁；继续复用用户现有登录与供应商配置。</li>
            </ul>
            <h3>2.2.10</h3>
            <ul>
              <li>OpenCode 任务使用 OmniCode 专用会话数据库，避免与 JetBrains 内置 ACP 或用户终端 CLI 的版本、迁移和 WAL 锁互相阻塞。</li>
              <li>主时间线区分本地初始化、会话创建、项目快照和模型连接；本地会话 30 秒未创建会明确停止，不再伪装成长时间模型推理。</li>
            </ul>
            <h3>2.2.9</h3>
            <ul>
              <li>移除本地 CLI 固定 45 秒首输出中止；排队或初始化期间按总请求时限等待，并持续显示可取消的进度。</li>
              <li>OpenCode 跳过无关的标题模型调用及请求级目录刷新/维护，并实时显示脱敏的过载、限流、连接中断和超时状态。</li>
            </ul>
            <h3>2.2.8</h3>
            <ul>
              <li>所有本地 CLI 现在在当前项目目录启动；增加运行时预检和 45 秒无输出保护，避免错误扫描 Home 目录后无限等待。</li>
              <li>修复 Kimi/Pi 模型参数转发与 Qoder 非交互启动参数；不再隐式启用 Qoder 的 <code>--yolo</code>。</li>
            </ul>
            <h3>2.2.7</h3>
            <ul>
              <li>修复 OpenCode CLI 本地模型发现的编译问题，恢复 Windows 发布验证。</li>
            </ul>
            <h3>2.2.6</h3>
            <ul>
              <li>OpenCode CLI 可直接读取本机已登录账户的模型列表并在模型菜单中切换，不需要 API Key。</li>
            </ul>
            <h3>2.2.5</h3>
            <ul>
              <li>统一 CLI 检测与运行时 PATH，npm CLI 不再因 IDE 缺少 Node 而显示可用却无法启动。</li>
              <li>CLI 页面仅将真实可运行的工具计入统计，并保留当前选择状态与修复指引。</li>
            </ul>
            <h3>2.2.4</h3>
            <ul>
              <li>修复本地 CLI 请求看似卡住、取消无法及时生效：输出改为增量显示，取消或超时会终止插件启动的整个 CLI 进程树。</li>
            </ul>
            <h3>2.2.3</h3>
            <ul>
              <li>修复停止任务仍卡在“正在安全停止”：取消等待 5 秒后释放 IDE 调度器，隔离迟到事件和结果，并保留恢复点。</li>
            </ul>
            <h3>2.2.2</h3>
            <ul>
              <li>修复任务取消卡住：MCP 客户端关闭增加 3 秒硬截止时间，超时后立即完成取消和恢复点收尾。</li>
            </ul>
            <h3>2.2.1</h3>
            <ul>
              <li>修复 MCP 子进程找不到 node/python：将增强后的安全运行时 PATH 传递给 npx/uvx。</li>
            </ul>
            <h3>2.2.0</h3>
            <ul>
              <li>新增安全独立 Session：运行中可新建对话，后台任务继续使用原会话历史，避免上下文串线。</li>
            </ul>
            <h3>2.1.9</h3>
            <ul>
              <li>稳定 MCP 运行时路径探测，兼容 Windows 构建与 GUI 启动的 IDE 环境。</li>
            </ul>
            <h3>2.1.8</h3>
            <ul>
              <li>修复 OpenCode CLI 退出码 1：使用 OpenCode 1.18.x 支持的 --format json 参数。</li>
            </ul>
            <h3>2.1.7</h3>
            <ul>
              <li>修复 MCP 在 GUI 启动的 IDE 中找不到 npx/uvx：补充常见 Node、uv 和用户 bin 路径探测。</li>
            </ul>
            <h3>2.1.6</h3>
            <ul>
              <li>修复 CLI 按钮点击无反馈：选择成功后立即显示“已选择”，失败时保留重试入口。</li>
            </ul>
            <h3>2.1.5</h3>
            <ul>
              <li>改进 MCP Registry 一键添加：带 Authorization 声明的远程服务自动映射为 Bearer 配置，不再误显示为仅浏览。</li>
            </ul>
            <h3>2.1.4</h3>
            <ul>
              <li>修复 CLI 使用失败：允许并规范化合法的 cli://local 地址，点击“使用此 CLI”可正常保存。</li>
            </ul>
            <h3>2.1.3</h3>
            <ul>
              <li>修复“使用此 CLI”无反馈问题：现在立即保存供应商选择并切换到 CLI 标签页。</li>
            </ul>
            <h3>2.1.2</h3>
            <ul>
              <li>深度优化对话时间线：MCP 警告改为带背景、边框和自动换行的诊断卡片。</li>
            </ul>
            <h3>2.1.1</h3>
            <ul>
              <li>增强 MCP 不可用提示：按超时、认证、命令和协议错误给出修复指引，支持换行显示完整信息。</li>
            </ul>
            <h3>2.0.13</h3>
            <ul>
              <li>修复“使用此 CLI”跳回普通 API 表单的问题，CLI 供应商现在会停留在 CLI 标签页。</li>
            </ul>
            <h3>2.0.12</h3>
            <ul>
              <li>重做普通 API 供应商页：增加可点击供应商卡片，详细凭据和模型配置收进独立区域。</li>
            </ul>
            <h3>2.0.11</h3>
            <ul>
              <li>检测到本地 CLI 后可直接点击“使用此 CLI”切换供应商，不再只有静态安装状态。</li>
            </ul>
            <h3>2.0.10</h3>
            <ul>
              <li>修复 IntelliJ 启动环境 PATH 不完整导致已安装 CLI 检测不到的问题，覆盖常见用户目录和包管理器路径。</li>
            </ul>
            <h3>2.0.9</h3>
            <ul>
              <li>普通 API 供应商在 Claude Code 标签页中独立展示，并保留完整凭据、模型和网络配置。</li>
            </ul>
            <h3>2.0.8</h3>
            <ul>
              <li>修复 CLI“查看安装方式”按钮无响应问题，按工具显示安全的终端安装说明。</li>
            </ul>
            <h3>2.0.7</h3>
            <ul>
              <li>修复 Marketplace 发布链路：WebStorm 验证器的环境性失败不再阻塞其他平台已通过的发布。</li>
            </ul>
            <h3>2.0.6</h3>
            <ul>
              <li>重做供应商管理页：新增 Claude Code、Codex、CLI 三栏切换。</li>
              <li>CLI 页展示本地工具安装状态、版本和路径，并支持重新检测，不会自动安装命令。</li>
            </ul>
            <h3>2.0.5</h3>
            <ul>
              <li>移除 Marketplace Freemium 产品描述和付费门槛，所有编码、协作、科研、报告与导出功能均免费开放。</li>
              <li>侧边栏不再显示购买/激活入口；旧版许可证代码仅保留兼容读取，不影响任何功能。</li>
            </ul>
            <h3>2.0.4</h3>
            <ul>
              <li>新增 5 个 CLI 工具供应商：OpenCode CLI、Kimi CLI、Grok Build CLI、Pi CLI、Qoder CLI。</li>
              <li>自动发现本地 CLI 可执行文件，支持自定义路径配置。</li>
              <li>CLI 供应商在供应商选择器中显示 "CLI" 标签，区分于 API 和本地供应商。</li>
              <li>API Key 通过环境变量自动传递给 CLI 子进程。</li>
            </ul>
            <h3>2.0.3</h3>
            <ul>
              <li>新增独立 Semi Design 图转码入口：选择或复用 UI 截图后，配置前端包、页面/组件、TSX/JSX、目标路径、样式、响应式和可访问性。</li>
              <li>有界预检 monorepo 内的 React、TypeScript、Semi 依赖和包管理器；React 19 使用 @douyinfe/semi-ui-19，React 16–18 使用 @douyinfe/semi-ui。</li>
              <li>图转码复用主视觉/视觉辅助、Agent、审批、沙箱、变更审阅和回退；不会静默安装依赖或持久化图片二进制。</li>
            </ul>
            <h3>2.0.0</h3>
            <ul>
              <li>切换为 JetBrains Marketplace Freemium：直接使用 IDE 的试用、购买、续费和许可证管理，不再要求用户配置外部收银地址。</li>
              <li>本地验证固定 POMNICODEAGENT 产品的 JetBrains confirmation stamp；核心 Agent、Team、MCP、Git/浏览器、科研附件和可靠性功能继续免费。</li>
            </ul>
            <h3>1.10.0</h3>
            <ul>
              <li>模型目录增加跨 IDE 重启的脱敏最后已知良好缓存，网络失败时保留可选模型并明确标注陈旧状态。</li>
              <li>模型请求显示排队、连接/推理、首 Token 和完成阶段，保留流式输出与取消/恢复边界。</li>
              <li>MCP 市场增加安装前安全审阅：公开 Registry、未签名来源、可变版本、远程凭据和本地可执行文件都会明确提示；任意 URL/Git 包源直接拒绝。</li>
              <li>Windows AppContainer helper 补齐标准用户 ACL smoke、系统环境隔离和 Authenticode 发布门禁。</li>
              <li>统一拒绝供应商返回的 JSON null/数组并保留可诊断错误；Remote Robot CI 保存真实 IDE 桌面截图证据。</li>
            </ul>
            <h3>1.9.4</h3>
            <ul>
              <li>活动实验自动接收任务成功率、延迟和 Token 结果，使用 workflow 幂等键避免恢复或重试重复计数。</li>
            </ul>
            <h3>1.9.3</h3>
            <ul>
              <li>实验样本记录增加幂等键，重试不会重复计数。</li>
            </ul>
            <h3>1.9.2</h3>
            <ul>
              <li>实验卡片增加脱敏样本记录入口，科研来源卡片可直接跳转 MCP 配置页。</li>
            </ul>
            <h3>1.9.1</h3>
            <ul>
              <li>将 A/B 实验测试改为无 IDE 运行时依赖的项目代理，降低发布环境初始化失败风险。</li>
            </ul>
            <h3>1.9.0</h3>
            <ul>
              <li>新增项目级 A/B Test 实验室：稳定分流、成功率/延迟/Token 计数与有界持久化，不保存提示词或模型输出。</li>
              <li>新增科研连接器目录：Crossref、OpenAlex、PubMed、arXiv、Semantic Scholar、Science、Nature、知网；授权来源只提供模板，不绕过登录或付费墙。</li>
            </ul>
            <h3>1.8.1</h3>
            <ul>
              <li>修复 TLS 诊断测试引用错误，恢复 Windows 发布流水线的完整校验、签名和 Marketplace 发布链路。</li>
            </ul>
            <h3>1.8.0</h3>
            <ul>
              <li>增强连接可靠性：按供应商保存直连/系统代理和请求超时，支持代理热刷新、TLS 诊断和首 Token 超时。</li>
              <li>模型发现失败时保留最近一次可用列表，并补齐跨平台与 Remote Robot 发布前验证。</li>
            </ul>
            <h3>1.7.3</h3>
            <ul>
              <li>修复篡改许可证在部分 JDK 上抛出底层 SignatureException、导致 CI 测试失败的问题，统一按无效许可证安全拒绝。</li>
            </ul>
            <h3>1.7.2</h3>
            <ul>
              <li>修复部分 JetBrains IDE 在打开 OmniCode Tool Window 时因无障碍上下文尚未初始化而导致面板空白或初始化失败的问题。</li>
            </ul>
            <h3>1.7.1</h3>
            <ul>
              <li>新增侧边栏“Pro 权益”页：签名许可证使用 Ed25519 校验并只保存到 IDE Password Safe，非法、篡改或过期 token 自动回到 Free。</li>
              <li>明确基础能力全免费：Agent/Team、Git Worktree/PR、浏览器自动化、MCP、跨设备任务包和科研附件不再由许可证隐藏或降级。</li>
              <li>新增 Pro 项目智能档案、批量任务配方和工程进展周报（本地 Git 版本差异 + OmniCode 任务账本）；任务可靠性报告保持免费，Research 可选增加实验锁定信息，权益只在用户主动触发新增产物时校验。</li>
              <li>聊天交互升级：新增可切换的流式回答、固定执行进度条、阶段耗时以及工具/子代理/修改计数；仅展示安全可审计的执行状态，不暴露模型隐藏思维链。</li>
            </ul>
            <h3>1.5.0</h3>
            <ul>
              <li>任务中心聚合可靠性事件，显示当前阶段、阶段耗时、模型请求、工具失败、重试次数和最近事件，失败任务更容易从具体步骤继续。</li>
              <li>变更审阅中心的文件与变更块增加 IDE 跳转入口，可直接打开对应文件和行号；跳转继续复用项目路径与敏感文件边界。</li>
              <li>冷仓库项目上下文预热改为 1.2 秒软预算，超时先请求模型，后台快照完成后供后续轮次复用，减少首响应等待。</li>
              <li>首轮自动上下文按推理档位限流，MCP 首次连接采用 1.5–5 秒软等待，低延迟档位不会被慢服务阻塞。</li>
              <li>CSV/TSV 附件增加本地有界统计、数值折线图和文本趋势摘要；扫描型 PDF 在本机有 Tesseract 时启用有界 OCR，原始数据仍不离开本地解析边界。</li>
              <li>任务中心支持加密任务包导入/导出与自建 relay；新增审批边界内的 Git worktree/PR、Playwright 浏览器工具，并接入手动 Remote Robot 桌面 smoke CI。</li>
            </ul>
            <h3>1.4.1</h3>
            <ul>
              <li>Made completed-turn actions wrap inside narrow JetBrains tool windows so retry, edit, task details, copy, and timing never overflow the chat boundary.</li>
            </ul>
            <h3>1.4.0</h3>
            <ul>
              <li>Added Codex-style completed-turn actions: retry the exact submission, edit and retry it in the composer, or open task reliability details.</li>
              <li>Retry actions preserve the original prompt and bounded attachment set, never overwrite an existing draft, and continue to use the existing provider, approval, sandbox, and checkpoint boundaries.</li>
            </ul>
            <h3>1.3.0</h3>
            <ul>
              <li>Warmed the bounded, read-only project context map when the project service opens, reducing first-request latency for large repositories without executing Harness commands or changing permissions.</li>
              <li>Completed assistant summaries now reconcile terminal provider usage, including providers that only return token counts in the final response.</li>
            </ul>
            <h3>1.2.1</h3>
            <ul>
              <li>Added a Codex-style completion summary with total duration, time to first response, tool count, and consumed tokens, so long runs remain understandable instead of appearing frozen.</li>
              <li>Kept the completed-reply copy action independent from checkpoint recovery controls; disabling recovery actions no longer disables unrelated reply actions.</li>
            </ul>
            <h3>1.2.0</h3>
            <ul>
              <li>Added an inline Codex-style change summary after Agent edits: files, added/removed line counts, direct file/range links, and a one-click route to the persistent review center.</li>
              <li>Kept file references workspace-relative and fail-closed, supporting <code>path:148-169</code>, <code>path 148-169</code>, and GitHub <code>path#L148-L169</code> forms with mouse and keyboard activation.</li>
              <li>Made <code>list_files</code> results navigable: project-relative file paths are rendered as compact links while directories and truncation notices stay plain text.</li>
              <li>Added a one-click copy action to completed assistant replies, preserving the original Markdown/code text for use outside the IDE.</li>
              <li>Reduced planning noise with targeted Plan/Claude Plan exploration guidance while preserving the user's configured execution boundaries.</li>
              <li>Kept Usage Statistics embedded in the third-party TokenTracker local dashboard with an external-browser fallback when JCEF is unavailable.</li>
              <li>Fixed compact timeline rendering for full-width Chinese stage separators in the chat transcript.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "253"
        }
    }
}

// The desktop workflow starts this task on a real display runner. It is deliberately not part
// of `test` so ordinary builds stay headless and deterministic.
intellijPlatformTesting {
    val runIdeForUiTests by runIde.registering {
        task {
            // Open a deterministic, non-sensitive fixture project so Remote Robot exercises the
            // actual Tool Window instead of taking a screenshot of the IDE welcome screen.
            args(layout.projectDirectory.dir("src/test/resources/ui-fixture").asFile.absolutePath)
            jvmArgumentProviders += CommandLineArgumentProvider {
                listOf(
                    "-Drobot-server.port=8082",
                    "-Dide.mac.message.dialogs.as.sheets=false",
                    "-Djb.privacy.policy.text=<!--999.999-->",
                    "-Djb.consents.confirmation.enabled=false",
                    "-Didea.trust.all.projects=true",
                    "-Dide.show.tips.on.startup.default.value=false",
                )
            }
        }
        plugins {
            robotServerPlugin("0.11.23")
        }
    }
}

val npmExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"
val webInstall = tasks.register<Exec>("webInstall") {
    group = "build"
    description = "Install the pinned JCEF/React workspace dependencies"
    workingDir("webview")
    commandLine(npmExecutable, "ci")
    inputs.files("webview/package.json", "webview/package-lock.json")
    outputs.dir("webview/node_modules")
}
val webTypecheck = tasks.register<Exec>("webTypecheck") {
    group = "verification"
    description = "Type-check the JCEF/React workspace"
    dependsOn(webInstall)
    workingDir("webview")
    commandLine(npmExecutable, "run", "typecheck")
    inputs.dir("webview/src")
    inputs.file("webview/tsconfig.json")
}
val webTest = tasks.register<Exec>("webTest") {
    group = "verification"
    description = "Run the JCEF/React unit tests"
    dependsOn(webInstall)
    workingDir("webview")
    commandLine(npmExecutable, "test", "--", "--run")
    inputs.dir("webview/src")
    inputs.file("webview/vite.config.ts")
}
val webBuild = tasks.register<Exec>("webBuild") {
    group = "build"
    description = "Build the single-file JCEF application"
    dependsOn(webInstall)
    workingDir("webview")
    commandLine(npmExecutable, "run", "build")
    inputs.dir("webview/src")
    inputs.files("webview/index.html", "webview/vite.config.ts", "webview/tsconfig.json")
    outputs.file("src/main/resources/webview/index.html")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    processResources {
        dependsOn(webBuild)
        from("LICENSE") {
            into("META-INF")
        }
        from("PRIVACY.md") {
            into("META-INF")
        }
        from("THIRD_PARTY_NOTICES.md") {
            into("META-INF")
        }
        from("licenses") {
            into("META-INF/licenses")
        }
        if (nativeWindowsAppContainerHost.asFile.isFile && nativeWindowsAppContainerHash.asFile.isFile) {
            from(nativeWindowsAppContainerHost) {
                into("bin/windows-x64")
            }
            from(nativeWindowsAppContainerHash) {
                into("bin/windows-x64")
            }
        }
    }

    test {
        useJUnitPlatform()
    }

    check {
        dependsOn(webTypecheck, webTest)
    }

    // Produce a deterministic CycloneDX inventory without downloading an executable scanner or
    // serialising source files. CI uploads this artifact for review; GitHub Dependabot performs
    // the vulnerability alerting separately.
    register("supplyChainSbom") {
        group = "verification"
        description = "Write a bounded CycloneDX SBOM for resolved runtime libraries"
        dependsOn("classes")
        notCompatibleWithConfigurationCache("Resolves runtime dependencies while writing the inventory")
        doLast {
            val runtime = project.configurations.getByName("runtimeClasspath")
            val components = runtime.incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                    Triple(id.group, id.module, id.version)
                }
                .distinct()
                .sortedWith(compareBy<Triple<String, String, String>> { it.first }.thenBy { it.second }.thenBy { it.third })

            fun json(value: String): String = buildString {
                append('"')
                value.forEach { character ->
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(character)
                    }
                }
                append('"')
            }

            val output = project.layout.buildDirectory.file("reports/supply-chain/omnicode-sbom.json").get().asFile
            output.parentFile.mkdirs()
            output.writeText(buildString {
                appendLine("{")
                appendLine("  \"bomFormat\": \"CycloneDX\",")
                appendLine("  \"specVersion\": \"1.5\",")
                appendLine("  \"version\": 1,")
                appendLine("  \"metadata\": {\"component\": {\"type\": \"application\", \"name\": \"omnicode-agent\", \"version\": ${json(project.version.toString())}}},")
                appendLine("  \"components\": [")
                components.forEachIndexed { index, (group, module, version) ->
                    val purl = "pkg:maven/$group/$module@$version"
                    append("    {\"type\":\"library\",\"group\":${json(group)},\"name\":${json(module)},\"version\":${json(version)},\"purl\":${json(purl)}}")
                    if (index != components.lastIndex) append(',')
                    appendLine()
                }
                appendLine("  ]")
                appendLine("}")
            })
            logger.lifecycle("Wrote ${components.size} runtime components to ${output.absolutePath}")
        }
    }

    val signPluginTask = named<SignPluginTask>("signPlugin")
    named<VerifyPluginSignatureTask>("verifyPluginSignature") {
        dependsOn(signPluginTask)
    }
    named<PublishPluginTask>("publishPlugin") {
        dependsOn("verifyPluginSignature")
        // Never let a second Gradle invocation fall back to the unsigned build artifact.
        archiveFile.set(signPluginTask.flatMap { it.signedArchiveFile })
    }
}
