package dev.omnicode.settings

/**
 * Process isolation policy for agent-initiated commands.
 *
 * This is deliberately not model-selectable. A trusted caller may opt into
 * [DANGER_FULL_ACCESS], while every ordinary command defaults to [WORKSPACE_WRITE].
 */
enum class SandboxMode {
    WORKSPACE_WRITE,
    DANGER_FULL_ACCESS,
    ;

    companion object {
        val DEFAULT: SandboxMode = WORKSPACE_WRITE
    }
}
