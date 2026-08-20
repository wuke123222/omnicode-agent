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
version = "2.0.23"

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
            <p>OmniCode Agent is a provider-neutral AI coding and research agent that runs inside JetBrains IDEs.</p>
            <ul>
              <li>Agent, editable Plan Board, read-only Claude Plan, and Research workflows with optional bounded Team collaboration.</li>
              <li>Model-aware reasoning levels from Auto through Full Speed, using native controls when verified and safe Agent-only controls otherwise.</li>
              <li>Bring-your-own-key support for major model APIs and OpenAI-compatible services.</li>
              <li>Reviewed code edits, approved commands, workspace sandboxing, MCP, Skills, and prompt templates.</li>
              <li>Agent Harness preflight plus a project Harness for rules, knowledge maps, argv feedback loops, recovery-safe tool surfaces, and indexed large-repository context.</li>
              <li>One-click credential-presence, network, model, MCP OAuth and sandbox diagnostics with redacted export.</li>
              <li>Project and desktop attachments including images, Markdown, PDF, and Jupyter notebooks.</li>
              <li>A dedicated Semi Design image-to-code workflow with bounded React/package preflight, configurable TSX/JSX output, and reviewed Agent execution.</li>
              <li>Local, redacted lead-workflow checkpoints with explicit resume or discard after an IDE restart.</li>
              <li>A local Creative Workshop with workspace skins, original virtual idols, and safe local avatar import.</li>
              <li>Local history, TokenTracker-powered usage dashboard, tool auditing, and reproducible research exports.</li>
              <li>A project-local A/B Test laboratory with deterministic assignments, bounded outcome metrics, and restart-safe experiment state.</li>
              <li>Research connector templates for Crossref, OpenAlex, PubMed, arXiv, Semantic Scholar, Science, Nature, and CNKI with explicit authorization boundaries.</li>
              <li>完全免费发布：项目档案、批量任务配方、工程周报和研究实验包均无需付费、试用或许可证。</li>
            </ul>
            <p><a href="https://github.com/wuke123222/omnicode-agent">Source code</a> ·
            <a href="https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md">Privacy notice</a></p>
        """.trimIndent()
        changeNotes = """
            <h3>2.0.23</h3>
            <ul>
              <li>CLI 供应商支持流式输出：OpenCode 等 CLI 的回复边生成边显示，不再等进程结束才出现整段文本。</li>
              <li>任务运行中被锁定的按钮现在有原因提示；运行中点“新建对话”会弹出“停止并新建”确认，不再没有反应。</li>
              <li>供应商页新增搜索框（按名称/协议过滤 25 家供应商）；“Claude Code”标签页更名为“API 供应商”，消除误导。</li>
              <li>移除“免费能力”冗余设置页与遗留死代码，侧栏更聚焦。</li>
            </ul>
            <h3>2.0.22</h3>
            <ul>
              <li>对话内直接查看行级 diff：变更卡片每个文件可展开着色差异，无需切到审阅栏；新增“在 IDE 差异视图中对比”入口，审阅中心继续负责保留/回退。</li>
              <li>MCP 更好用：列表持久显示每个服务器的最近连接结果，新增“测试全部已启用”，OAuth 登录前自动保存当前服务器配置，不再要求先手动保存。</li>
              <li>回复中的代码块支持语法高亮（按围栏语言）与一键复制。</li>
              <li>自动识别游戏项目（Unity/Unreal/Godot/Cocos Creator）并注入引擎与资产目录约定，游戏仓库上下文更干净。</li>
            </ul>
            <h3>2.0.21</h3>
            <ul>
              <li>重做供应商卡片：显示协议类型副标签，当前使用的供应商高亮选中，不再是一片相同的按钮。</li>
              <li>Codex 标签页与 CLI 对齐：自动检测本机 codex 可执行文件并显示版本与路径，支持重新检测、安装指引和 OMNICODE_CODEX_PATH 提示。</li>
              <li>CLI 卡片改为两行布局：名称/状态一行，模型选择与操作按钮一行，当前使用的 CLI 高亮边框。</li>
            </ul>
            <h3>2.0.20</h3>
            <ul>
              <li>失败提示不再吞掉真实原因：CLI 子进程错误、视觉辅助模型缺失等本地异常现在原样显示可操作的错误信息，而不是笼统的“运行过程中发生异常”。远程响应内容仍保持脱敏。</li>
              <li>其余未识别异常至少显示异常类型；CLI 看门狗超时归类为连接超时并提供诊断入口。</li>
            </ul>
            <h3>2.0.19</h3>
            <ul>
              <li>修复 CLI 对话真正的卡死根因：子进程 stdin 现在启动后立即关闭。opencode 等支持管道输入的 CLI 之前会一直等待 stdin 结束，导致请求永远“正在请求模型”且没有任何输出。</li>
              <li>超时/停止现在销毁整棵进程树：CLI 派生的子进程（如 node 服务）之前不会被杀掉并持续占住输出管道，导致超时和停止按钮都无法解除卡死。</li>
            </ul>
            <h3>2.0.18</h3>
            <ul>
              <li>修复 CLI 请求可能无限“正在请求模型”的问题：超时现在由看门狗强制终止 CLI 子进程（默认 10 分钟，可在供应商设置调整），停止按钮也会立即杀掉子进程，不再把会话卡死导致无法新建对话。</li>
              <li>CLI 子进程现在在当前项目根目录运行，而不是 IDE 进程目录；OpenCode 等会对工作目录做快照的 CLI 不再因目录过大而卡住。</li>
            </ul>
            <h3>2.0.17</h3>
            <ul>
              <li>修复 OpenCode CLI 一直报“退出码 1，未产生输出”的问题：改用 opencode 实际支持的 --format json 参数（旧的 --output-format 会被拒绝），并按真实事件结构解析回复文本和 Token 用量。</li>
              <li>CLI 启动失败时附带错误输出摘要，不再只显示退出码。</li>
            </ul>
            <h3>2.0.16</h3>
            <ul>
              <li>OpenCode CLI 现在支持真实模型列表：通过本地 “opencode models” 命令读取可用模型，CLI 卡片下拉框和聊天模型列表都能直接选择。</li>
            </ul>
            <h3>2.0.15</h3>
            <ul>
              <li>“使用此 CLI”点击后立即保存并生效，卡片显示“当前使用”标记和切换结果，不再没有任何反馈。</li>
              <li>OpenCode/Grok/Qoder CLI 卡片新增模型选择框，可直接填写要传给 CLI 的模型；Kimi/Pi 模型由 CLI 自身配置。</li>
              <li>CLI 供应商在配置表单中不再显示 Base URL 和 API Key 字段。</li>
              <li>修复 IDE 启动环境缺少 node 路径导致 CLI 显示 “env: node: No such file or directory” 且无法运行的问题：检测和运行子进程都会补全 PATH。</li>
            </ul>
            <h3>2.0.14</h3>
            <ul>
              <li>修复选择过本地 CLI 供应商后，保存任何供应商配置都报“Base URL 必须以 https:// 开头”的问题；cli://local 现在是合法的本地 CLI 地址。</li>
              <li>保存校验失败时错误信息会标明出错的供应商名称，便于定位非当前页的配置问题。</li>
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
            jvmArgumentProviders += CommandLineArgumentProvider {
                listOf(
                    "-Drobot-server.port=8082",
                    "-Dide.mac.message.dialogs.as.sheets=false",
                    "-Djb.privacy.policy.text=<!--999.999-->",
                    "-Djb.consents.confirmation.enabled=false",
                )
            }
        }
        plugins {
            robotServerPlugin("0.11.23")
        }
    }
}

// Local-only preview: the sandbox IDE can show every commercial screen without changing the
// signed plugin artifact or Marketplace entitlement behavior.
tasks.withType<RunIdeTask>().configureEach {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Domnicode.localPreview=true",
            "-Domnicode.preview.commercial=true",
        )
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    processResources {
        from("LICENSE") {
            into("META-INF")
        }
        from("PRIVACY.md") {
            into("META-INF")
        }
        from("THIRD_PARTY_NOTICES.md") {
            into("META-INF")
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
