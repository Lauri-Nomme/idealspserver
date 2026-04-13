## Platform/Build Limitations: IntelliJ Platform 2025.2+ and 2026

As of 2026, the IdeaLS plugin relies on core IntelliJ APIs for language server support. Due to platform changes in 2025.2+ (branch 252+) and 2026 (branch 261+), plugin developers cannot access the `com.intellij.platform.backend.workspace.virtual` package or its classes (such as `ClientProjectSession`/`ClientProjectSessionUtils`). These APIs are essential to implement and invoke code completion via LSP, which is central to plugin/client functionality.

### Key Facts
- **Workspace Virtual APIs are NOT exposed in the official plugin SDKs (2025.2+, 2026).**
  - Classes like `ClientProjectSession`/`Utils` are present only for JetBrains' internal use.
  - Attempts to add or reference these modules fail at compile time for 2025.2+ builds.
- **Builds for 2025.2+ (and 2026) will fail with `package ... does not exist` errors** at all usages of these APIs.
- **No supported plugin mechanism exists to access these APIs or otherwise implement LSP completion.**
  - Reflection, shadow copy, or inappropriate dependency hacks are NOT allowed; such plugins will be rejected by Marketplace and are unsupported.

### What this means for IdeaLS / this plugin
- The plugin CANNOT build or run with completion support on IntelliJ IDEA 2025.2+ or 2026 due to the locked APIs.
- Completion/LSP server features DO NOT WORK and cannot be enabled for these target platforms.
- This is a hard platform limitation. You **must build and run the plugin using an older supported IntelliJ IDEA target** (2025.1 or earlier—branch 251 or below).

### Current status
- As of April 2026, plugin builds targeting 2025.2+ or 2026 will fail due to missing workspace virtual APIs, halting completion support.
- For latest supported platforms:
  - Use `intellijIdea("2025.1")` and `sinceBuild = "251"` for IntelliJ 2025.1 or lower.
  - 2025.2+ or later: Not supported due to locked APIs.

### Summary
If your workflow requires completion support via this plugin, DO NOT upgrade to or target IDEA 2025.2+ or 2026. You must either:
- Use/build against IntelliJ 2025.1 or earlier (Java 21 compatible).
- OR wait for JetBrains to add proper public support for LSP/completion APIs in a future SDK.

**If you attempt to build for 2025.2+ or 2026, the build will fail and runtime operation will not be possible.**
