# Optional TokenTracker integration

OmniCode's **Usage Statistics** page can discover an independently installed
[TokenTracker](https://github.com/xiufengsun/TokenTracker) CLI and open its local dashboard. This is
an optional companion, not an OmniCode runtime dependency. OmniCode's built-in usage, price
estimates, retention, and audit views continue to work when TokenTracker is absent or stopped.

## Trust and privacy boundary

- OmniCode only looks for a `tokentracker` executable in absolute `PATH` entries and a small set of
  conventional user/system binary directories. Discovery never executes the file.
- Dashboard detection performs one bounded, no-redirect HTTP request to
  `http://127.0.0.1:7680/`, bypassing configured proxies. The Open button is enabled only when the
  returned page identifies itself as TokenTracker. No remote host or user-configurable URL is used.
- OmniCode does not read TokenTracker's database, forward its own usage records, provide API keys,
  or enable TokenTracker cloud sync.
- Install and start actions copy commands to the clipboard. OmniCode never runs `npm`, `npx`, a
  remote script, or the discovered executable.

The copied start command sets `TOKENTRACKER_NO_TELEMETRY=1`. TokenTracker is a separate third-party
program with its own release and privacy policy. Its official documentation says that first launch
detects AI tools and may install or update their hooks, while its cloud features are optional. Users
should review those changes and the upstream documentation before running the command.

## User flow

1. Open **Usage Statistics** and check the optional TokenTracker card.
2. If no CLI is found, copy `npm install --global tokentracker-cli`, review it in a terminal, and run
   it manually if desired. TokenTracker currently requires Node.js 20 or newer.
3. Detect again, then copy the platform-specific start command. Review the upstream hook changes
   before first launch.
4. Once the fixed local endpoint is recognized, use **Open local dashboard**.

Connection failures and an unrelated service occupying port 7680 remain isolated to this card and
never make the built-in statistics page unavailable.
