package dev.omnicode.ui

import dev.omnicode.service.ReproducibleResearchPackage
import dev.omnicode.service.ReproducibleResearchPackageExporter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchPackageMarkdownWriterTest {
    @Test
    fun `create new atomically publishes a new file and removes its temporary link`() {
        val directory = Files.createTempDirectory("omnicode-research-create")
        try {
            val destination = directory.resolve("experiment.md")

            val result = ResearchPackageMarkdownWriter().writeAtomically(packageOf("# New\n"), destination)

            assertEquals(destination.toAbsolutePath().normalize(), result)
            assertEquals("# New\n", destination.readText())
            Files.list(directory).use { entries ->
                assertEquals(listOf("experiment.md"), entries.map { it.fileName.toString() }.sorted().toList())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `atomically replaces only the confirmed regular markdown file and leaves no temporary artifact`() {
        val directory = Files.createTempDirectory("omnicode-research-write")
        try {
            val destination = directory.resolve("experiment.md")
            Files.writeString(destination, "old")
            val writer = ResearchPackageMarkdownWriter()
            val confirmed = writer.captureTargetIdentity(destination)
            val result = writer.writeAtomically(
                packageOf("# New\n"),
                destination,
                ResearchPackageWritePolicy.REPLACE_MATCHING,
                confirmed,
            )

            assertEquals(destination.toAbsolutePath().normalize(), result)
            assertEquals("# New\n", destination.readText())
            Files.list(directory).use { entries ->
                assertEquals(listOf("experiment.md"), entries.map { it.fileName.toString() }.sorted().toList())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create new never replaces a file that appears after the temporary file is durable`() {
        val directory = Files.createTempDirectory("omnicode-research-create-race")
        try {
            val destination = directory.resolve("experiment.md")
            val writer = ResearchPackageMarkdownWriter(beforeCommit = { target ->
                Files.writeString(target, "created by another process", StandardOpenOption.CREATE_NEW)
            })

            val error = assertFailsWith<ResearchPackageWriteException> {
                writer.writeAtomically(packageOf("new export"), destination, ResearchPackageWritePolicy.CREATE_NEW)
            }

            assertTrue(error.message.orEmpty().contains("no file was overwritten"))
            assertEquals("created by another process", destination.readText())
            Files.list(directory).use { entries ->
                assertEquals(listOf("experiment.md"), entries.map { it.fileName.toString() }.sorted().toList())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create new refuses an already existing regular file without explicit replacement`() {
        val directory = Files.createTempDirectory("omnicode-research-create-existing")
        try {
            val destination = directory.resolve("experiment.md")
            Files.writeString(destination, "keep me")

            assertFailsWith<ResearchPackageWriteException> {
                ResearchPackageMarkdownWriter().writeAtomically(packageOf("replacement"), destination)
            }

            assertEquals("keep me", destination.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacement refuses content identity and type changes after confirmation`() {
        val directory = Files.createTempDirectory("omnicode-research-replace-race")
        try {
            val changed = directory.resolve("changed.md")
            Files.writeString(changed, "original")
            val baselineWriter = ResearchPackageMarkdownWriter()
            val changedIdentity = baselineWriter.captureTargetIdentity(changed)
            val changedWriter = ResearchPackageMarkdownWriter(beforeCommit = { target ->
                Files.writeString(target, "changed by another process", StandardOpenOption.TRUNCATE_EXISTING)
            })

            assertFailsWith<ResearchPackageWriteException> {
                changedWriter.writeAtomically(
                    packageOf("replacement"),
                    changed,
                    ResearchPackageWritePolicy.REPLACE_MATCHING,
                    changedIdentity,
                )
            }
            assertEquals("changed by another process", changed.readText())

            val typeChanged = directory.resolve("type-changed.md")
            Files.writeString(typeChanged, "original")
            val typeIdentity = baselineWriter.captureTargetIdentity(typeChanged)
            val typeWriter = ResearchPackageMarkdownWriter(beforeCommit = { target ->
                Files.delete(target)
                Files.createDirectory(target)
            })
            assertFailsWith<ResearchPackageWriteException> {
                typeWriter.writeAtomically(
                    packageOf("replacement"),
                    typeChanged,
                    ResearchPackageWritePolicy.REPLACE_MATCHING,
                    typeIdentity,
                )
            }
            assertTrue(Files.isDirectory(typeChanged))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacement detects a new inode even when size and mtime are restored`() {
        val directory = Files.createTempDirectory("omnicode-research-inode-race")
        try {
            val destination = directory.resolve("experiment.md")
            Files.writeString(destination, "aaaa")
            val baselineWriter = ResearchPackageMarkdownWriter()
            val confirmed = baselineWriter.captureTargetIdentity(destination)
            val racingWriter = ResearchPackageMarkdownWriter(beforeCommit = { target ->
                Files.delete(target)
                Files.writeString(target, "bbbb", StandardOpenOption.CREATE_NEW)
                Files.setLastModifiedTime(target, confirmed.lastModifiedTime)
            })

            assertFailsWith<ResearchPackageWriteException> {
                racingWriter.writeAtomically(
                    packageOf("replacement"),
                    destination,
                    ResearchPackageWritePolicy.REPLACE_MATCHING,
                    confirmed,
                )
            }

            assertEquals("bbbb", destination.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacement refuses a symlink swapped in after confirmation`() {
        val directory = Files.createTempDirectory("omnicode-research-symlink-race")
        try {
            val destination = directory.resolve("experiment.md")
            val real = directory.resolve("other.md")
            Files.writeString(destination, "confirmed")
            Files.writeString(real, "other content")
            val baselineWriter = ResearchPackageMarkdownWriter()
            val confirmed = baselineWriter.captureTargetIdentity(destination)
            val canCreateLink = runCatching {
                val probe = directory.resolve("probe.md")
                Files.createSymbolicLink(probe, real.fileName)
                Files.delete(probe)
            }.isSuccess
            if (!canCreateLink) return
            val racingWriter = ResearchPackageMarkdownWriter(beforeCommit = { target ->
                Files.delete(target)
                Files.createSymbolicLink(target, real.fileName)
            })

            assertFailsWith<ResearchPackageWriteException> {
                racingWriter.writeAtomically(
                    packageOf("replacement"),
                    destination,
                    ResearchPackageWritePolicy.REPLACE_MATCHING,
                    confirmed,
                )
            }

            assertTrue(Files.isSymbolicLink(destination))
            assertEquals("other content", real.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacement policy requires a matching snapshot for the same path`() {
        val directory = Files.createTempDirectory("omnicode-research-replace-contract")
        try {
            val first = directory.resolve("first.md")
            val second = directory.resolve("second.md")
            Files.writeString(first, "first")
            Files.writeString(second, "second")
            val writer = ResearchPackageMarkdownWriter()
            val firstIdentity = writer.captureTargetIdentity(first)

            assertFailsWith<ResearchPackageWriteException> {
                writer.writeAtomically(
                    packageOf("replacement"),
                    first,
                    ResearchPackageWritePolicy.REPLACE_MATCHING,
                )
            }
            assertFailsWith<ResearchPackageWriteException> {
                writer.writeAtomically(
                    packageOf("replacement"),
                    second,
                    ResearchPackageWritePolicy.REPLACE_MATCHING,
                    firstIdentity,
                )
            }
            assertEquals("first", first.readText())
            assertEquals("second", second.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects non markdown destinations symlinks and missing parents`() {
        val directory = Files.createTempDirectory("omnicode-research-reject")
        try {
            val writer = ResearchPackageMarkdownWriter()
            assertFailsWith<ResearchPackageWriteException> {
                writer.writeAtomically(packageOf("safe"), directory.resolve("experiment.txt"))
            }

            val real = directory.resolve("real.md")
            Files.writeString(real, "original")
            val link = directory.resolve("linked.md")
            if (runCatching { Files.createSymbolicLink(link, real.fileName) }.isSuccess) {
                assertFailsWith<ResearchPackageWriteException> {
                    writer.captureTargetIdentity(link)
                }
                assertFailsWith<ResearchPackageWriteException> {
                    writer.writeAtomically(packageOf("replacement"), link)
                }
                assertEquals("original", real.readText())
            }

            assertFailsWith<ResearchPackageWriteException> {
                writer.writeAtomically(packageOf("safe"), directory.resolve("missing/export.md"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects packages above the independent write limit`() {
        val directory = Files.createTempDirectory("omnicode-research-limit")
        try {
            val maxBytes = ReproducibleResearchPackageExporter.MIN_EXPORT_BYTES
            val destination = directory.resolve("oversized.md")
            val oversized = "x".repeat(maxBytes + 1)

            assertFailsWith<ResearchPackageWriteException> {
                ResearchPackageMarkdownWriter(maxBytes).writeAtomically(packageOf(oversized), destination)
            }
            assertFalse(destination.deleteIfExists())
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun packageOf(markdown: String): ReproducibleResearchPackage = ReproducibleResearchPackage(
        markdown = markdown,
        suggestedFileName = "experiment.md",
        generatedAt = Instant.EPOCH,
        sourceMessageCount = 0,
        exportedMessageCount = 0,
        evidenceCount = 0,
        truncated = false,
    )
}
