package dev.omnicode.ui

import dev.omnicode.service.ConnectionDiagnosticsExport
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectionDiagnosticsPackageWriterTest {
    @Test
    fun `writes deterministic two-file create-only zip`() {
        val root = createTempDirectory("omnicode-diagnostics")
        val target = root.resolve("report.zip")

        val written = ConnectionDiagnosticsPackageWriter().write(
            ConnectionDiagnosticsExport("# report", "{\"ok\":true}"),
            target,
        )

        assertEquals(target, written)
        ZipFile(written.toFile()).use { zip ->
            assertEquals(setOf("diagnostics.md", "diagnostics.json"), zip.entries().asSequence().map { it.name }.toSet())
            assertEquals("# report", zip.getInputStream(zip.getEntry("diagnostics.md")).reader().readText())
        }
    }

    @Test
    fun `never overwrites an existing export`() {
        val root = createTempDirectory("omnicode-diagnostics-existing")
        val target = root.resolve("report.zip")
        Files.writeString(target, "keep")

        assertFailsWith<ConnectionDiagnosticsWriteException> {
            ConnectionDiagnosticsPackageWriter().write(ConnectionDiagnosticsExport("m", "j"), target)
        }
        assertEquals("keep", Files.readString(target))
    }
}
