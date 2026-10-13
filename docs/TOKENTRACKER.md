# Optional TokenTracker integration

OmniCode's **Usage Statistics** page connects to the local dashboard of the independently installed
[TokenTracker](https://github.com/xiufengsun/TokenTracker) CLI. TokenTracker remains the source of
truth for token usage and price estimates, while OmniCode renders a native, bounded summary of its
read-only local aggregate API. It remains an optional companion and is not an OmniCode runtime
dependency.

## Trust and privacy boundary

- OmniCode only looks for a `tokentracker` executable in absolute `PATH` entries and a small set of
  conventional user/system binary directories. Discovery never executes the file.
- Dashboard detection and aggregate reads perform bounded, no-redirect HTTP requests to
  `http://127.0.0.1:7680/`, bypassing configured proxies. Only a page identified as TokenTracker
  is queried. The Usage page fetches only 30-day summary, daily, model and project aggregates from
  the fixed `/functions/tokentracker-*` endpoints. Each response is size/time bounded; project
  repository URLs are discarded and only the final project name reaches the WebView. No iframe,
  remote/user-configurable URL or TokenTracker auth token crosses the bridge.
- OmniCode does not read TokenTracker's database directly, forward its own usage records, provide
  API keys, invoke mutation endpoints, or enable TokenTracker cloud sync.
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
3. Detect again. Once the fixed local endpoint is recognized, OmniCode displays total tokens,
   estimated cost, daily trend, model ranking and project ranking directly in **Usage Statistics**.
   **打开完整面板** opens TokenTracker's own `/dashboard` route in the system browser for features
   beyond the bounded embedded summary.

Connection failures and an unrelated service occupying port 7680 remain isolated to this card and
never make the built-in statistics page unavailable.
