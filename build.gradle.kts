import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.omnicode"
version = "0.14.2"

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
            <h3>0.14.2</h3>
            <ul>
              <li>Adds an Agent Harness preflight that binds identity, limits, tool surfaces and recovery-safe execution before provider I/O.</li>
              <li>Adds a Project Harness sidebar, strict optional .omnicode/harness.json discovery and the read-only inspect_project_harness tool.</li>
              <li>Dangerous tools that time out or fail after approval now stop immediately and retain a recoverable unknown-side-effect checkpoint.</li>
              <li>Valid multi-tool responses execute sequentially with independent approval, audit and checkpoint boundaries.</li>
              <li>Budget, repetition, truncated-response and malformed tool batches are closed with replay-safe results and complete audit events.</li>
              <li>The observation limit now applies to the complete provider tool batch instead of multiplying per call.</li>
              <li>Retained unknown-side-effect recovery points survive verification and block new dangerous actions across project tasks.</li>
              <li>Configured monetary limits fail closed before provider I/O when model pricing or a trustworthy resumed-cost baseline is unavailable.</li>
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
}
