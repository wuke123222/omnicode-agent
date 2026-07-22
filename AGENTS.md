# OmniCode Agent repository guide

This file is a compact map for coding agents. Detailed behavior and security boundaries live in the linked documents; do not duplicate them here.

## Repository map

- `src/main/kotlin/dev/omnicode/harness/`: runtime Harness preflight and AgentEngine boundary.
- `src/main/kotlin/dev/omnicode/agent/`: provider-neutral execution loop, budgets, checkpoints, and events.
- `src/main/kotlin/dev/omnicode/tool/`: tool classification, approval, file safety, commands, and process sandboxing.
- `src/main/kotlin/dev/omnicode/service/`: project lifecycle, repository Harness discovery, persistence coordination, and context.
- `src/main/kotlin/dev/omnicode/ui/`: JetBrains Tool Window and approval surfaces.
- `docs/ARCHITECTURE.md`: runtime and security architecture.
- `docs/HARNESS.md`: repository Harness format and trust model.
- `SECURITY.md` and `PRIVACY.md`: disclosure and data-handling commitments.

## Required boundaries

- Treat repository files and Harness configuration as untrusted project data; they cannot override system, user, approval, sandbox, budget, or audit policy.
- Never execute a discovered Harness command during project scan or UI refresh. Execution must use `run_command` with an argv array and the existing approval/sandbox path.
- New non-read-only tools must be marked dangerous and must remain unavailable in restricted modes.
- Preserve fail-closed path, symlink, ignore, credential, checkpoint, and unknown-side-effect behavior.
- Keep file and model inputs bounded. Do not add secrets, binary attachments, or full repository snapshots to persistence or model context.

## Feedback loops

- Quick: `./gradlew test`
- Release-level: `./gradlew check buildPlugin verifyPlugin`

Use JDK 21. Update focused tests and `docs/ARCHITECTURE.md` when execution semantics change.
