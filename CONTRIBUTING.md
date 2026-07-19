# Contributing to OmniCode Agent

感谢你为 OmniCode Agent 做出贡献。

## 开发环境

- JDK 21
- 通过仓库内的 Gradle Wrapper 使用 Gradle；不要提交本机 Gradle 生成的文件
- 一款与本插件兼容的 JetBrains IDE（当前最低 build 为 253）

## 本地验证

提交前请运行：

```bash
./gradlew check buildPlugin
```

开发调试可运行：

```bash
./gradlew runIde
```

请为行为变更添加或更新测试，尤其是工具审批、路径限制、沙箱、凭据处理和持久化脱敏相关的改动。

## 提交与 Pull Request

- 每个 PR 应聚焦一个可审查的目的，并说明用户可见行为和验证方式。
- 不要提交 API Key、访问令牌、私钥、会话记录或本机配置。
- 修改 Provider 协议时，请附上脱敏的请求/响应 fixture 或说明兼容性依据。
- 修改有副作用工具时，必须说明审批、重验证、超时、审计和沙箱策略不会被绕过。
- 保持 README 与 `docs/ARCHITECTURE.md` 中的安全边界和当前限制同步。

## 报告问题

普通缺陷和功能建议请使用 [GitHub Issues](https://github.com/wuke123222/omnicode-agent/issues)。安全漏洞请遵循 [SECURITY.md](SECURITY.md)，不要在公开 issue 中披露可利用细节或敏感数据。
