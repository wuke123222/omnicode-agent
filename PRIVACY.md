# OmniCode Agent Privacy Notice

Effective date: July 21, 2026

OmniCode Agent is a local JetBrains IDE plugin. This notice explains what data the plugin handles and where that data goes.

## Data sent to services you configure

OmniCode Agent does not provide its own model hosting service. When you send a request, the plugin sends the prompt and the context you selected—such as code, file excerpts, images, documents, and tool results—to the model provider you configured. Those providers process data under their own terms and privacy policies.

The plugin connects to MCP servers only when you configure and approve them. Data sent to an MCP server is processed by that server under the policies of its operator.

Review the destination, requested context, and provider policies before sending confidential, personal, regulated, or proprietary information.

## Credentials

API keys and supported MCP credentials are stored through JetBrains Password Safe. They are not intentionally written to the project or ordinary plugin configuration files. Credentials are sent only to the corresponding provider or server as needed to perform an approved request.

## Local data

The plugin stores bounded conversation history, usage estimates, checkpoints, settings, and tool audit records on your device. If you import a custom desktop-pet avatar, the plugin decodes it locally, removes source metadata, downscales it, and stores one re-encoded PNG under the IDE configuration directory. The source path is not retained, and the image is not sent to model providers, MCP servers, OmniCode services, or the project. Research-package exports are created only when you request them. You control these files through your operating system and IDE profile.

The plugin does not intentionally collect vendor-operated analytics or advertising identifiers. Provider and JetBrains platform telemetry, if any, is governed by those products' settings and policies.

## Retention and deletion

Local records remain until you remove them through the plugin, uninstall data, delete the relevant IDE system/configuration directory, or your configured retention limits remove them. A custom avatar can be deleted from Creative Workshop; deleting it does not delete the original source image. Remote providers and MCP servers may retain request data according to their own policies.

## Security

The plugin applies local approval, path, redaction, and sandbox controls, but no control eliminates all risk. Do not place secrets directly in prompts or exported reports. See [SECURITY.md](SECURITY.md) for vulnerability reporting and the documented security boundaries.

## Changes and contact

This notice may change when the plugin's data flows change. Material changes will be documented in the repository. Questions can be sent to `liuhaoyu327@gmail.com`.
