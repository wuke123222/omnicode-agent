package dev.omnicode.tool

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudePlanReadOnlyCommandPolicyTest {
    @Test
    fun `allows common direct read-only exploration argv`() {
        listOf(
            listOf("rg", "-n", "TODO|FIXME", "src"),
            listOf("rg", "--", "--pre=literal-pattern", "src"),
            listOf("git", "status", "--short"),
            listOf("git", "diff", "--cached", "--", "src/main/App.kt"),
            listOf("git", "log", "--oneline", "-20"),
            listOf("git", "branch", "--show-current"),
            listOf("git", "branch", "--list", "feature/*"),
            listOf("ls", "-la", "src"),
            listOf("find", ".", "-maxdepth", "3", "-type", "f", "-name", "*.kt", "-print"),
            listOf("cat", "README.md"),
            listOf("grep", "-R", "TODO", "src"),
            listOf("head", "-n", "40", "README.md"),
            listOf("tail", "-n", "40", "README.md"),
            listOf("wc", "-l", "README.md"),
            listOf("diff", "-u", "before.txt", "after.txt"),
        ).forEach { argv ->
            ClaudePlanReadOnlyCommandPolicy.requireAllowed(argv)
        }
    }

    @Test
    fun `rejects mutation executables and mutating subcommands`() {
        listOf(
            listOf("rm", "README.md"),
            listOf("touch", "created.txt"),
            listOf("sed", "-i", "s/a/b/", "README.md"),
            listOf("git", "checkout", "main"),
            listOf("git", "reset", "--hard"),
            listOf("git", "add", "."),
            listOf("git", "branch", "new-branch"),
            listOf("git", "diff", "--output=diff.txt"),
            listOf("git", "diff", "--out=diff.txt"),
            listOf("diff", "--output=diff.txt", "before.txt", "after.txt"),
            listOf("diff", "--out=diff.txt", "before.txt", "after.txt"),
            listOf("diff", "--paginate", "before.txt", "after.txt"),
            listOf("diff", "-l", "before.txt", "after.txt"),
            listOf("find", ".", "-delete"),
            listOf("find", ".", "-exec", "cat", "{}", ";"),
            listOf("rg", "--pre", "cat", "needle", "."),
            listOf("rg", "--hostname-bin=touch", "needle", "."),
            listOf("rg", "--search-zip", "needle", "."),
            listOf("/bin/cat", "README.md"),
        ).forEach { argv -> assertBlocked(argv) }
    }

    @Test
    fun `rejects shell composition without confusing pattern syntax with a pipeline`() {
        listOf(
            listOf("rg", "TODO", "|", "cat"),
            listOf("git", "status", "&&", "git", "reset", "--hard"),
            listOf("cat", "README.md", ">", "copy.md"),
            listOf("cat", "README.md", ">copy.md"),
            listOf("ls", ";", "touch", "created.txt"),
        ).forEach { argv -> assertBlocked(argv) }

        // Direct argv has no shell interpolation, so regex syntax inside one pattern stays valid.
        ClaudePlanReadOnlyCommandPolicy.requireAllowed(listOf("rg", "TODO|FIXME", "src"))
    }

    private fun assertBlocked(argv: List<String>) {
        val error = assertFailsWith<IllegalArgumentException> {
            ClaudePlanReadOnlyCommandPolicy.requireAllowed(argv)
        }
        assertTrue(error.message.orEmpty().startsWith("CLAUDE_PLAN_COMMAND_BLOCKED:"), error.message)
    }
}
