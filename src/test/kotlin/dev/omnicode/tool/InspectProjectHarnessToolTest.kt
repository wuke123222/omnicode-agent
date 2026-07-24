package dev.omnicode.tool

import dev.omnicode.service.HarnessConfigurationStatus
import dev.omnicode.service.HarnessEvidence
import dev.omnicode.service.HarnessEvidenceKind
import dev.omnicode.service.HarnessFeedbackLoop
import dev.omnicode.service.HarnessReadiness
import dev.omnicode.service.ProjectHarnessReport
import kotlin.test.Test
import kotlin.test.assertTrue

class InspectProjectHarnessToolTest {
    @Test
    fun `inspection leads with plain language no config guidance and folds jargon below it`() {
        val report = ProjectHarnessReport(
            score = 75,
            readiness = HarnessReadiness.READY,
            safeForModel = true,
            evidence = listOf(
                HarnessEvidence(HarnessEvidenceKind.BUILD, "build.gradle.kts", "Gradle 构建"),
            ),
            feedbackLoops = listOf(
                HarnessFeedbackLoop(
                    id = "gradle-test",
                    label = "Gradle 单元测试",
                    argv = listOf("./gradlew", "test"),
                    sourcePath = "build.gradle.kts",
                ),
            ),
            guardrails = emptyList(),
            runtimeControls = emptyList(),
            issues = emptyList(),
            configurationStatus = HarnessConfigurationStatus.ABSENT,
            truncated = false,
        )

        val output = renderProjectHarnessInspection(report)

        assertTrue(output.startsWith("项目准备情况\n可以直接使用，无需配置"))
        assertTrue(output.contains("配置：不需要"))
        assertTrue(output.contains("没有执行命令"))
        assertTrue(output.indexOf("推荐下一步") < output.indexOf("高级 Harness 详情"))
        assertTrue(output.contains("[\"./gradlew\", \"test\"]"))
    }
}
