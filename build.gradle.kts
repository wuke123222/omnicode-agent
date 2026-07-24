import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.omnicode"
version = "0.14.4"

// Keep local verification lightweight while allowing CI to fan out one IDE per matrix job.
val pluginVerifierTargets = linkedMapOf(
    "idea-253" to (IntelliJPlatformType.IntellijIdea to "2025.3.6"),
    "idea-261" to (IntelliJPlatformType.IntellijIdea to "2026.1.3"),
    "idea-262" to (IntelliJPlatformType.IntellijIdea to "2026.2"),
    "pycharm-253" to (IntelliJPlatformType.PyCharm to "2025.3.6"),
    "webstorm-253" to (IntelliJPlatformType.WebStorm to "2025.3.6"),
)

repositories {
    mavenCentral()
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
              <li>Local history, token and cost estimates, tool auditing, and reproducible research exports.</li>
            </ul>
            <p><a href="https://github.com/wuke123222/omnicode-agent">Source code</a> ·
            <a href="https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md">Privacy notice</a></p>
        """.trimIndent()
        changeNotes = """
            <h3>0.14.4</h3>
            <ul>
              <li>Adds an MCP marketplace with 27 offline presets plus 500 entries loaded from the official MCP Registry, with development and research prioritization.</li>
              <li>Adds clickable project file and line references in model and tool output.</li>
              <li>Shows editable Plan and Claude Plan approval directly in the conversation before execution.</li>
              <li>Raises Team collaboration to four concurrent specialists and eight specialists per task with graceful budget-aware reduction.</li>
              <li>Simplifies Project Harness into a beginner-friendly Project Context surface while keeping advanced safety details available.</li>
              <li>Batches independent read-only exploration and reduces oversized repository listings for faster agent work.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "253"
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
    }

    test {
        useJUnitPlatform()
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
