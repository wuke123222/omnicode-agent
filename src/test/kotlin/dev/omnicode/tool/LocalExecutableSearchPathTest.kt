package dev.omnicode.tool

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalExecutableSearchPathTest {
    @Test
    fun `windows bare commands probe launcher extensions while unix stays exact`() {
        assertEquals(
            listOf("npx", "npx.cmd", "npx.exe", "npx.bat"),
            LocalExecutableSearchPath.candidateNames("npx", windows = true),
        )
        assertEquals(listOf("npx"), LocalExecutableSearchPath.candidateNames("npx", windows = false))
        // An explicit extension is respected as-is on Windows.
        assertEquals(
            listOf("server.exe"),
            LocalExecutableSearchPath.candidateNames("server.exe", windows = true),
        )
    }

    @Test
    fun `search directories merge process path shell path and package manager homes`() {
        val home = Files.createTempDirectory("omnicode-home").toString()
        val directories = LocalExecutableSearchPath.directories(
            processPath = "/ide/bin${File.pathSeparator}/usr/bin",
            shellPath = "/shell/custom${File.pathSeparator}/usr/bin",
            home = home,
            windows = false,
            env = emptyMap(),
        )

        assertTrue(directories.indexOf("/ide/bin") < directories.indexOf("/shell/custom"))
        assertTrue("$home/.local/bin" in directories)
        assertTrue("$home/.volta/bin" in directories)
        assertTrue("/opt/homebrew/bin" in directories)
        assertEquals(1, directories.count { it == "/usr/bin" }, "duplicates are collapsed")
        assertTrue(directories.none(String::isBlank))
    }

    @Test
    fun `versioned nvm installations surface newest first and bounded`() {
        val home = Files.createTempDirectory("omnicode-home")
        val nvmRoot = home.resolve(".nvm/versions/node")
        listOf("v18.20.0", "v22.11.0", "v9.11.2", "v20.9.0").forEach { version ->
            Files.createDirectories(nvmRoot.resolve(version).resolve("bin"))
        }

        val directories = LocalExecutableSearchPath.directories(
            processPath = null,
            shellPath = null,
            home = home.toString(),
            windows = false,
            env = emptyMap(),
        )

        val nvmBins = directories.filter { it.contains(".nvm") }
        assertEquals(4, nvmBins.size)
        assertTrue(nvmBins.first().contains("v22.11.0"), "newest version should be searched first")
        assertTrue(
            nvmBins.indexOfFirst { it.contains("v9.11.2") } > nvmBins.indexOfFirst { it.contains("v20.9.0") },
            "numeric ordering must beat lexicographic ordering",
        )
    }

    @Test
    fun `launch path leads with the executable directory`() {
        val executableDirectory = Files.createTempDirectory("omnicode-node-bin")
        val value = LocalExecutableSearchPath.launchPathValue(executableDirectory)

        assertTrue(value.startsWith(executableDirectory.toString()))
        assertTrue(File.pathSeparator in value)
    }

    @Test
    fun `windows package locations come from the environment`() {
        val directories = LocalExecutableSearchPath.directories(
            processPath = null,
            shellPath = null,
            home = "",
            windows = true,
            env = mapOf("APPDATA" to "C:\\Users\\demo\\AppData\\Roaming"),
        )

        assertTrue("C:\\Users\\demo\\AppData\\Roaming\\npm" in directories)
        assertTrue(directories.none { it.contains("homebrew") })
    }
}
