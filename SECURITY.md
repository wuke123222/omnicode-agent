# Security Policy

## Supported versions

安全修复优先面向当前 `main` 分支和最新发布版本。旧版本可能不会收到修复；升级前请先阅读发布说明和兼容性要求。

## Reporting a vulnerability

请不要在公开 issue、讨论区、日志或截图中披露安全漏洞、API Key、访问令牌、私钥、用户路径或可识别的项目内容。

请使用 GitHub 的 [私密漏洞报告](https://github.com/wuke123222/omnicode-agent/security/advisories/new)；如果该入口暂时不可用，请发送邮件至 `liuhaoyu327@gmail.com`。报告应包含：

- 漏洞影响和复现步骤；
- 受影响的插件版本、IDE 版本和操作系统；
- 所需的配置或权限；
- 最小化、脱敏的概念验证；
- 可能的缓解方案（如有）。

维护者会确认收到报告、评估影响，并在修复准备好后协调披露。请给维护者合理的修复时间，不要在修复发布前公开漏洞细节。

## Security boundaries

OmniCode 将模型输出、项目文本和 MCP Server 输出均视为不可信输入。文件变更、命令和 MCP 调用必须通过本地审批与工具策略；`danger-full-access` 会移除 OS 级进程隔离，只有在用户理解风险时才应启用。

完整边界和当前平台限制见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与 README 的“安全边界”。
