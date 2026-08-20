package dev.omnicode.service

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameEngineDetectionTest {
    @Test
    fun `detects Unity with the editor version from the marker file`() {
        val base = createTempDirectory("omnicode-unity")
        try {
            Files.createDirectories(base.resolve("ProjectSettings"))
            Files.createDirectories(base.resolve("Assets"))
            Files.writeString(
                base.resolve("ProjectSettings/ProjectVersion.txt"),
                "m_EditorVersion: 2022.3.10f1\nm_EditorVersionWithRevision: 2022.3.10f1 (ff3792e53c62)\n",
            )

            val engine = GameEngineDetection.detect(base)

            assertEquals("Unity", engine?.name)
            assertEquals("2022.3.10f1", engine?.detail)
            assertTrue(GameEngineDetection.contextLine(base).orEmpty().contains("Library/Temp"))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Unreal and Godot from their project markers`() {
        val unreal = createTempDirectory("omnicode-unreal")
        val godot = createTempDirectory("omnicode-godot")
        try {
            Files.writeString(unreal.resolve("MyGame.uproject"), "{}")
            Files.writeString(godot.resolve("project.godot"), "config_version=5\n")

            assertEquals("Unreal Engine", GameEngineDetection.detect(unreal)?.name)
            assertEquals("MyGame.uproject", GameEngineDetection.detect(unreal)?.detail)
            assertEquals("Godot", GameEngineDetection.detect(godot)?.name)
        } finally {
            unreal.toFile().deleteRecursively()
            godot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `regular projects are not misclassified as game projects`() {
        val base = createTempDirectory("omnicode-plain")
        try {
            Files.writeString(base.resolve("build.gradle.kts"), "plugins {}\n")
            Files.createDirectories(base.resolve("src"))

            assertNull(GameEngineDetection.detect(base))
            assertNull(GameEngineDetection.contextLine(base))
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
