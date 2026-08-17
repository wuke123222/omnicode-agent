# OmniCode Agent Privacy Notice

Effective date: August 8, 2026

OmniCode Agent is a local JetBrains IDE plugin. This notice explains what data the plugin handles and where that data goes.

## Data sent to services you configure

OmniCode Agent does not provide its own model hosting service. When you send a request, the plugin sends the prompt and the selected or automatically prepared project context—such as code, file excerpts, project rules, pinned files, images, documents, and tool results—to the model provider you configured. Automatically prepared context is bounded and follows project AI-ignore and sensitive-path rules. Those providers process data under their own terms and privacy policies.

The plugin connects to MCP servers only when you configure and approve them. Data sent to an MCP server is processed by that server under the policies of its operator.

The optional TokenTracker card checks only the fixed loopback URL `http://127.0.0.1:7680/` and a bounded set of local executable paths. OmniCode does not read TokenTracker's usage database, start or install the CLI, send it API keys, or enable its cloud features. If you choose to run TokenTracker or open its documentation, that separate project and your browser operate under their own terms and privacy settings.

Review the destination, requested context, and provider policies before sending confidential, personal, regulated, or proprietary information.

## Free distribution

The current Marketplace build is fully free. It does not start trials, purchases, renewals, refunds, checkout, or license checks, and it does not collect payment, account, tax, invoice, or transaction data. All coding, collaboration, research, report, and export features are available without an entitlement.

An old vendor-signed license token may remain in Password Safe for users migrating from a pre-free release; it is not required and is not sent anywhere or used to gate current features.

## Credentials

API keys, supported MCP credentials, and legacy migration tokens are stored through JetBrains Password Safe. They are not intentionally written to the project or ordinary plugin configuration files. Credentials are sent only to the corresponding provider or server as needed to perform an approved request.

## Local data

The plugin stores bounded conversation history, usage estimates, workflow checkpoints, settings, project-relative pinned/excluded path choices, and tool audit records on your device. Project-rule and pinned-file contents are transient request context and are not copied into conversation history or workflow checkpoints. The MCP marketplace also keeps a bounded, secret-free copy of the last successful public Registry metadata directory under the IDE configuration directory so the catalog remains usable after a restart or temporary network failure; it contains no credentials, headers, package output, or project files. Task-change review before/after content is currently kept only in memory for the active IDE session. If you import a custom desktop-pet avatar, the plugin decodes it locally, removes source metadata, downscales it, and stores one re-encoded PNG under the IDE configuration directory. The source path is not retained, and the image is not sent to model providers, MCP servers, OmniCode services, or the project. Research-package and redacted diagnostics exports are created only when you request them. You control these files through your operating system and IDE profile.

### Workflow checkpoints

While a lead workflow is active, the plugin writes its latest bounded recovery state to `<JetBrains system path>/omnicode/workflow-checkpoints.jsonl`. A record can contain workflow, conversation, project, and agent identifiers; mode and execution strategy; iteration and timestamps; bounded textual message snapshots (including prompts, model text, and locally extracted attachment text); tool-call arguments and observations; budget counters and limits; pending tool or approval metadata; and bounded delegate summaries. The generated system prompt is not persisted and is rebuilt for the selected mode on resume. Free-form text is redacted and truncated before persistence. Password Safe credentials and binary attachment payloads are not written to this file. An image may leave only bounded textual metadata such as its redacted file name, media type, and byte size.

Redaction reduces accidental persistence of common credential formats but cannot guarantee that every secret or personal identifier is recognized. Do not place credentials directly in prompts, attachment text, tool arguments, or project files that you ask the plugin to read.

The plugin does not intentionally collect vendor-operated analytics or advertising identifiers. Provider and JetBrains platform telemetry, if any, is governed by those products' settings and policies.

## Retention and deletion

Workflow checkpoints retain at most 200 records and the checkpoint file is capped at 256 MiB; older records are removed by bounded-store compaction. Terminal records can remain until a retention boundary or local-data removal deletes them. For a recoverable interrupted workflow, the **Discard checkpoint** action removes that workflow's checkpoint in one click. It does not undo file changes, commands, or external side effects that may already have occurred. Clearing usage statistics does not clear workflow checkpoints.

Other local records remain until you remove them through the plugin, uninstall data, delete the relevant IDE system/configuration directory, or their configured retention limits remove them. A custom avatar can be deleted from Creative Workshop; deleting it does not delete the original source image. Remote providers and MCP servers may retain request data according to their own policies.

## Security

The plugin applies local approval, path, redaction, and sandbox controls, but no control eliminates all risk. Do not place secrets directly in prompts or exported reports. See [SECURITY.md](SECURITY.md) for vulnerability reporting and the documented security boundaries.

## Changes and contact

This notice may change when the plugin's data flows change. Material changes will be documented in the repository. Questions can be sent to `liuhaoyu327@gmail.com`.
