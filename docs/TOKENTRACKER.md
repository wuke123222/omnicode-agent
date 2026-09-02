# Optional TokenTracker integration

OmniCode's **Usage Statistics** page connects to the local dashboard of the independently installed
[TokenTracker](https://github.com/xiufengsun/TokenTracker) CLI. TokenTracker is the sole user-facing
source for token usage, cost and trend data on that page; OmniCode does not render a second usage
dashboard or merge its own estimates into it. It remains an optional companion and is not an
OmniCode runtime dependency.

## Trust and privacy boundary

- OmniCode only looks for a `tokentracker` executable in absolute `PATH` entries and a small set of
  conventional user/system binary directories. Discovery never executes the file.
- Dashboard detection performs one bounded, no-redirect HTTP request to
  `http://127.0.0.1:7680/`, bypassing configured proxies. Only a page identified as TokenTracker
  can be opened. OmniCode opens that fixed loopback URL in the system browser; it does not embed
  arbitrary web content or accept a remote/user-configurable URL.
- OmniCode does not read TokenTracker's database, forward its own usage records, provide API keys,
  or enable TokenTracker cloud sync.
- Install and start actions copy commands to the clipboard. OmniCode never runs `npm`, `npx`, a
  remote script, or the discovered executable.

The copied start command sets `TOKENTRACKER_NO_TELEMETRY=1`. TokenTracker is a separate third-party
program with its own release and privacy policy. Its official documentation says that first launch
detects AI tools and may install or update their hooks, while its cloud features are optional. Users
should review those changes and the upstream documentation before running the command.

## User flow

1. Open **Usage Statistics** and click **复制启动命令**.
2. Review and run `npx tokentracker-cli` in a terminal. TokenTracker currently requires Node.js 20
   or newer and may configure hooks for detected AI tools on first launch.
3. Detect again. Once the fixed local endpoint is recognized, click **打开本地面板** to open it in
   the system browser. The Usage page remains usable when the service is stopped or when the IDE
   has no JCEF support; it shows the exact next action and lets you copy the platform-specific
   command again.

Connection failures and an unrelated service occupying port 7680 remain isolated to this card and
never make the built-in statistics page unavailable.
