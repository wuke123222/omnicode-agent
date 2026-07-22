# Release and Marketplace publishing

OmniCode uses the IntelliJ Platform Gradle Plugin 2.x signing and publishing tasks. Ordinary pushes and pull requests never receive or require release credentials. Only a pushed `v*` tag starts [the Marketplace workflow](../.github/workflows/release.yml).

## One-time GitHub configuration

Create a GitHub Actions environment named `jetbrains-marketplace` and configure required reviewers plus a deployment rule that only permits release tags. Store these values as **environment secrets**, not repository files or workflow inputs:

- `CERTIFICATE_CHAIN`: PEM certificate chain. Store the multiline PEM value directly, not a Base64 wrapper.
- `PRIVATE_KEY`: encrypted PEM private key. Store the multiline PEM value directly, not a Base64 wrapper.
- `PRIVATE_KEY_PASSWORD`: password for the private key.
- `PUBLISH_TOKEN`: JetBrains Marketplace permanent token scoped to this plugin.

Do not put secret values in `gradle.properties`, command arguments, artifacts, issues, or logs. The workflow only supplies each value as a step-level environment variable and never prints it. Before signing, it writes the certificate chain and private key to mode-`0600` files under `RUNNER_TEMP`, then passes only their paths as `CERTIFICATE_CHAIN_FILE` and `PRIVATE_KEY_FILE`; this follows the Gradle plugin's supported file inputs and keeps multiline PEM data out of command arguments. Signing and publishing explicitly disable Gradle configuration and build caches so credential-bearing task inputs are not persisted in reusable caches, and an `always()` cleanup step removes both temporary files.

References: [JetBrains plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html), [publishing to Marketplace](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html), and [GitHub deployment environments](https://docs.github.com/actions/deployment/targeting-different-environments/using-environments-for-deployment).

## Verification matrix

`verifyPlugin` defaults to the minimum supported IntelliJ IDEA line so local development downloads one IDE only:

```bash
./gradlew verifyPlugin
```

CI fans out independent jobs for the minimum, current, and forward IntelliJ IDEA lines plus representative PyCharm and WebStorm products. Run one target locally with:

```bash
./gradlew verifyPlugin -PpluginVerifierIde=idea-261
```

Supported target names are `idea-253`, `idea-261`, `idea-262`, `pycharm-253`, and `webstorm-253`. `-PpluginVerifierIde=all` is available for deliberate release diagnostics but downloads every configured IDE and is therefore not the default.

## Release procedure

1. Update the Gradle plugin version and release notes, then merge the exact source to publish.
2. Run `./gradlew check buildPlugin verifyPlugin` and smoke-test the generated ZIP.
3. Create and push an annotated tag whose version exactly matches Gradle, for example `v0.15.0` for version `0.15.0`.
4. Review the GitHub Actions `Publish to JetBrains Marketplace` run. It must finish tests, packaging, and every Plugin Verifier matrix job before requesting approval for `jetbrains-marketplace`.
5. Approve the protected environment deployment. The final job checks that all four secrets exist, runs `signPlugin` and `verifyPluginSignature`, stores the signed ZIP as an artifact, and only then runs `publishPlugin`.
6. Confirm the uploaded version and review status in the Marketplace vendor console. A successful upload can still require JetBrains review before it becomes public.

If any quality, compatibility, tag-version, secret, signing, or signature check fails, publishing does not run. Retrying a publish after an uncertain Marketplace response requires checking the vendor console first to avoid a duplicate upload.
