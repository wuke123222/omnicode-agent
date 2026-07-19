import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.omnicode"
version = "0.8.0"

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
              <li>Agent, Plan, and read-only Research workflows.</li>
              <li>Bring-your-own-key support for major model APIs and OpenAI-compatible services.</li>
              <li>Reviewed code edits, approved commands, workspace sandboxing, MCP, Skills, and prompt templates.</li>
              <li>Project and desktop attachments including images, Markdown, PDF, and Jupyter notebooks.</li>
              <li>Local history, token and cost estimates, tool auditing, and reproducible research exports.</li>
            </ul>
            <p><a href="https://github.com/wuke123222/omnicode-agent">Source code</a> ·
            <a href="https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md">Privacy notice</a></p>
        """.trimIndent()
        changeNotes = """
            <h3>0.8.0</h3>
            <ul>
              <li>Added Research mode with read-only inspection plus explicitly approved sandboxed commands; file mutation, MCP, and external tools remain unavailable.</li>
              <li>Added bounded local extraction for PDF papers and Jupyter notebooks, plus LaTeX, BibTeX, R, Julia, and MATLAB-oriented research attachments.</li>
              <li>Added project-file @ references and bounded, redacted reproducible research-package export with evidence and verification checklists.</li>
              <li>Added Apache PDFBox 3.0.8 for local PDF text extraction; licensing details are recorded in THIRD_PARTY_NOTICES.md.</li>
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
