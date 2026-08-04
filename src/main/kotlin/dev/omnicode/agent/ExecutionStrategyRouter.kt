package dev.omnicode.agent

/** Fast, deterministic routing keeps small edits on one agent and reserves Team for evidence-heavy work. */
object ExecutionStrategyRouter {
    fun choose(message: String, mode: AgentMode, attachmentCount: Int = 0): AgentExecutionStrategy {
        val normalized = message.trim().lowercase()
        val crossModuleSignals = listOf(
            "跨模块", "全项目", "全仓库", "排障", "迁移", "重构", "架构", "研究", "科研", "论文",
            "综述", "实验", "benchmark", "benchmarking", "multi-module", "cross module", "root cause",
        )
        val explicitSingleSignals = listOf("小改", "改一处", "简单", "rename", "format", "typo", "one file")
        val score = buildList {
            if (normalized.length > 700) add(2)
            if (normalized.count { it == '\n' } >= 8) add(1)
            if (crossModuleSignals.any(normalized::contains)) add(3)
            if (attachmentCount >= 3) add(2)
            if (mode == AgentMode.RESEARCH) add(3)
            if (explicitSingleSignals.any(normalized::contains)) add(-2)
        }.sum()
        return if (score >= 3) AgentExecutionStrategy.TEAM else AgentExecutionStrategy.SINGLE
    }
}
