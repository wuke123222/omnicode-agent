import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
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
version = "1.8.1"

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
              <li>Local, redacted lead-workflow checkpoints with explicit resume or discard after an IDE restart.</li>
              <li>A local Creative Workshop with workspace skins, original virtual idols, and safe local avatar import.</li>
              <li>Local history, TokenTracker-powered usage dashboard, tool auditing, and reproducible research exports.</li>
              <li>Commercial entitlement foundation: signed Pro/Research licenses stored in Password Safe; all core coding, Team, MCP, Git/browser tools, task transfer and reliability reports remain free, while optional project dossiers, batch recipes and engineering digests are paid add-ons.</li>
            </ul>
            <p><a href="https://github.com/wuke123222/omnicode-agent">Source code</a> ·
            <a href="https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md">Privacy notice</a></p>
        """.trimIndent()
        changeNotes = """
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
