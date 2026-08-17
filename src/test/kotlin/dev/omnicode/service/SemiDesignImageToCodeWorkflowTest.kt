package dev.omnicode.service

import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemiDesignImageToCodeWorkflowTest {
    @Test
    fun `react 19 preflight recommends the official compatibility package`() {
        val root = Files.createTempDirectory("semi-react-19")
        root.resolve("package.json").writeText(
            """
            {
              "name": "console-web",
              "dependencies": {
                "react": "^19.1.0",
                "vite": "^7.0.0",
                "typescript": "^5.9.0"
              }
            }
            """.trimIndent(),
        )

        val result = SemiDesignProjectInspector.inspect(root)

        assertEquals(19, result.selectedPackage.reactMajor)
        assertEquals("React + Vite", result.selectedPackage.framework)
        assertEquals("@douyinfe/semi-ui-19", result.selectedPackage.recommendedSemiPackage)
        assertTrue(result.selectedPackage.typeScript)
        assertFalse(result.selectedPackage.semiInstalled)
        assertFailsWith<IllegalArgumentException> {
            SemiDesignImageToCodeWorkflow.validateOptions(
                root,
                defaultOptions(result).copy(semiPackage = "@douyinfe/semi-ui"),
            )
        }
    }

    @Test
    fun `monorepo preflight skips dependency directories and selects a react package`() {
        val root = Files.createTempDirectory("semi-monorepo")
        root.resolve("node_modules/ignored").createDirectories()
        root.resolve("node_modules/ignored/package.json").writeText("{\"dependencies\":{\"react\":\"19.0.0\"}}")
        root.resolve("apps/web").createDirectories()
        root.resolve("apps/web/package.json").writeText(
            """
            {
              "name": "web",
              "dependencies": {
                "react": "18.3.1",
                "@douyinfe/semi-ui": "^2.80.0",
                "@douyinfe/semi-icons": "^2.80.0"
              }
            }
            """.trimIndent(),
        )

        val result = SemiDesignProjectInspector.inspect(root)

        assertEquals(1, result.scannedPackageFiles)
        assertEquals("apps/web/package.json", result.selectedPackage.packageJsonPath)
        assertEquals("@douyinfe/semi-ui", result.selectedPackage.recommendedSemiPackage)
        assertTrue(result.selectedPackage.semiInstalled)
        assertTrue(result.selectedPackage.semiIconsInstalled)
    }

    @Test
    fun `workflow creates an agent submission with images only and exact target`() {
        val root = Files.createTempDirectory("semi-workflow")
        root.resolve("package.json").writeText(
            """{"name":"web","dependencies":{"react":"18.3.1","@douyinfe/semi-ui":"^2.80.0"}}""",
        )
        val preflight = SemiDesignProjectInspector.inspect(root)
        val image = UserAttachment("reference.png", AttachmentKind.IMAGE, "image/png", 10, "base64")
        val notes = UserAttachment("notes.md", AttachmentKind.MARKDOWN, "text/markdown", 10, "ignored")
        val options = defaultOptions(preflight).copy(
            componentName = "CampaignDashboard",
            targetPath = "src/pages/CampaignDashboard/index.tsx",
            targetKind = SemiDesignTargetKind.DASHBOARD,
            additionalInstructions = "保留筛选区和分页交互。",
        )

        val prepared = SemiDesignImageToCodeWorkflow.prepare(preflight, options, listOf(image, notes))

        assertEquals(listOf(image), prepared.submission.attachments)
        assertEquals(listOf(image), prepared.consumedImages)
        assertTrue("[OmniCode workflow: semi-design-image-to-code/v1]" in prepared.submission.prompt)
        assertTrue("src/pages/CampaignDashboard/index.tsx" in prepared.submission.prompt)
        assertTrue("@douyinfe/semi-ui" in prepared.submission.prompt)
        assertTrue("禁止自动运行 install" in prepared.submission.prompt)
        assertTrue("保留筛选区和分页交互" in prepared.submission.prompt)
        assertTrue("1 张参考图" in prepared.transcriptText)
        assertFalse("ignored" in prepared.submission.prompt)
    }

    @Test
    fun `target validation rejects traversal build output and a different package`() {
        val root = Files.createTempDirectory("semi-target")
        root.resolve("apps/web").createDirectories()
        root.resolve("apps/web/package.json").writeText("{\"dependencies\":{\"react\":\"18.2.0\"}}")
        val preflight = SemiDesignProjectInspector.inspect(root)
        val base = defaultOptions(preflight)

        assertFailsWith<IllegalArgumentException> {
            SemiDesignImageToCodeWorkflow.validateOptions(root, base.copy(targetPath = "../outside.tsx"))
        }
        assertFailsWith<IllegalArgumentException> {
            SemiDesignImageToCodeWorkflow.validateOptions(root, base.copy(targetPath = "apps/web/dist/View.tsx"))
        }
        assertFailsWith<IllegalArgumentException> {
            SemiDesignImageToCodeWorkflow.validateOptions(root, base.copy(targetPath = "other/View.tsx"))
        }
    }

    @Test
    fun `workflow refuses to guess without an image`() {
        val root = Files.createTempDirectory("semi-no-image")
        root.resolve("package.json").writeText("{\"dependencies\":{\"react\":\"18.2.0\"}}")
        val preflight = SemiDesignProjectInspector.inspect(root)

        assertFailsWith<IllegalArgumentException> {
            SemiDesignImageToCodeWorkflow.prepare(preflight, defaultOptions(preflight), emptyList())
        }
    }

    private fun defaultOptions(preflight: SemiDesignProjectPreflight) = SemiDesignImageToCodeOptions(
        packageContext = preflight.selectedPackage,
        semiPackage = preflight.selectedPackage.recommendedSemiPackage,
        componentName = "GeneratedView",
        targetPath = SemiDesignImageToCodeWorkflow.suggestedTargetPath(
            preflight.selectedPackage,
            SemiDesignTargetKind.COMPONENT,
            "GeneratedView",
            SemiDesignCodeLanguage.TYPESCRIPT,
        ),
        targetKind = SemiDesignTargetKind.COMPONENT,
        language = SemiDesignCodeLanguage.TYPESCRIPT,
        styleStrategy = SemiDesignStyleStrategy.CSS_MODULE,
        addMissingDependencies = true,
        responsive = true,
        accessibility = true,
        additionalInstructions = "",
    )
}
