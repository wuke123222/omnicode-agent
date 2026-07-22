# Project Harness

OmniCode 的 Project Harness 把“仓库是否容易被 Agent 正确理解和验证”变成可见的本地预检。它由两层组成：

1. 运行时 Harness 在所包装 `AgentEngine` 的主 Provider 请求前绑定模式、有效工具面、预算 limits、恢复降级和运行面摘要，再委托经过测试的执行循环；视觉辅助预处理与 MCP 发现仍使用各自独立的预检边界。
2. 仓库 Harness 只读发现项目规则、知识文档、构建/测试/质量/CI 证据和可选反馈回路，并把有界地图作为不可信项目数据提供给模型。

侧栏“项目 Harness”展示的是启发式成熟度，不是测试成功证明。刷新面板、打开项目或调用 `inspect_project_harness` 都不会启动进程、访问网络或调用模型。“验证反馈”中的命令在实际运行前仍必须由 Agent 通过 `run_command` 提出，并经过当前模式、用户审批、进程沙箱、超时、预算、checkpoint 和工具审计。

## 配置格式

共享配置位于 `.omnicode/harness.json`：

```json
{
  "version": 1,
  "knowledge": ["AGENTS.md", "docs/ARCHITECTURE.md"],
  "feedbackLoops": [
    {
      "id": "quick",
      "label": "快速测试",
      "argv": ["./gradlew", "test"]
    }
  ],
  "guardrails": [
    {
      "label": "架构边界",
      "path": "docs/ARCHITECTURE.md"
    }
  ]
}
```

约束：

- `version` 当前必须为 `1`。
- `knowledge` 和 `guardrails.path` 只能是项目内、未被 AI ignore 排除且不超过 512 KiB 的普通 UTF-8 文件；拒绝绝对路径、目录穿越和符号链接。
- `feedbackLoops` 只接受有界 argv 数组，不接受 shell 字符串、环境变量、密钥、内联解释器、免审批或沙箱降级字段。
- 配置最多 64 KiB；数组、路径、参数和错误数量都有固定上限。未知顶层字段或条目字段会使整份配置失败，避免 `autoRun`、`env`、`sandbox` 等无效字段看起来已经生效。
- `.gitignore`、`.aiignore` 或 `.omnicodeignore` 无法安全解析时，详细 Harness 元数据失败关闭，不会注入模型。

自动发现支持常见 Gradle、Maven、Package、Python、Cargo、Go、测试目录、静态质量配置和 CI 文件。自动建议只是一份执行草案；项目可用显式配置覆盖或补充非标准布局。

## Trust and recovery

Harness 配置与文档都属于 repository-authored data。它们不能扩大 Agent 权限，也不能把 `danger-full-access`、MCP、网络或宿主凭据带入任务。存在未解除的未知副作用恢复点时，运行时 Harness 会向模型暴露降级后的非危险工具面；用户核对状态并明确处理恢复点前，不会开始新的危险动作。
