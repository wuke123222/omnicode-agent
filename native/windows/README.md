# Native Windows AppContainer host

`omnicode-appcontainer-host.exe` is the only Windows `workspace-write` backend accepted by
the plugin. It creates a per-run AppContainer with no network capabilities, grants that
container a bounded ACL transaction over the selected project, forwards the approved argv
without a shell, and restores the original descriptors before returning. The broker passes only
duplicated standard I/O handles through an explicit `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` and
places the complete child tree in a kill-on-close Job Object. Symlinks/reparse
points, oversized workspaces, failed ACL operations, failed child launch, and failed cleanup
are all hard failures, including a failed handle whitelist or Job Object setup.

Every transaction writes a bounded recovery journal under the host temp directory before the
first ACL change. A later helper invocation repairs an interrupted transaction only when the
recorded AppContainer ACE is still present, so a user's intervening ACL edit is not overwritten.

Each child receives an explicit minimal environment block containing only profile-local
`LOCALAPPDATA`, `TEMP`, `TMP`, `USERPROFILE`, and `HOME` paths plus the Windows loader roots.
The broker never inherits the IDE/JVM environment, so provider keys, proxy credentials, and
arbitrary user variables are not exposed to the sandboxed process.

The executable must be Authenticode-signed for distribution and its SHA-256 must be supplied
in one of these forms:

* `OMNICODE_APPCONTAINER_HOST_SHA256`, or
* a sidecar file next to the executable named `omnicode-appcontainer-host.exe.sha256`.

The plugin never accepts a project-local helper or silently falls back to `danger-full-access`.
The signed Windows build should be placed at `bin/windows-x64/omnicode-appcontainer-host.exe`
inside the plugin distribution. The Marketplace release workflow builds, Authenticode-signs, and
smoke-tests this binary on Windows; native signing uses a separate certificate from JetBrains
plugin ZIP signing. The workflow also runs a workspace transaction as a local standard
(non-administrator) user. Configure `APPCONTAINER_CERTIFICATE_BASE64` and
`APPCONTAINER_CERTIFICATE_PASSWORD` in the protected Marketplace environment before publishing.

Build and probe on a Windows developer machine:

```powershell
cmake -S native/windows -B native/windows/build -A x64
cmake --build native/windows/build --config Release
native/windows/build/bin/omnicode-appcontainer-host.exe --probe
Get-FileHash native/windows/build/bin/omnicode-appcontainer-host.exe -Algorithm SHA256
```
