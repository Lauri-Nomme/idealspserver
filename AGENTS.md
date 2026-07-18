# Agent Guidelines for IdeaLS

## Key Files
- `iteration.md` - Development and testing workflow
- `scripts/test_lsp_comprehensive.py` - Main test suite
- `scripts/shell/install-plugin.sh` - Build and install plugin

## Always Read First
Before each session, read `iteration.md` to understand current workflow and any new conventions.

## Logging
- **ALWAYS use `LOG.warn()`** for logging - never `LOG.info()`. This ensures logs are visible in production.
- Check logs with: `journalctl --user -u idealsp.service --no-pager | grep -iE '(error|warn)'`

## Building and Testing
```bash
# Build and install plugin
bash scripts/shell/install-plugin.sh

# Run tests
python3 scripts/test_lsp_comprehensive.py
```

## IntelliJ Internal API Usage

This project uses reflection to call IntelliJ internal (non-public) APIs extensively. This is by design — the public API surface of IntelliJ does not expose the functionality needed for headless LSP operations (refactoring, code analysis, etc.).

### Rules

1. **Using internal APIs via reflection is the whole point.** Do not give up when an API is non-public. Find the correct internal class and method, access it via `setAccessible(true)` (on methods or fields), and call it.

2. **If a method doesn't exist, look for alternatives.** IntelliJ's internal APIs change between versions. Use `javap` or CFR to discover available methods. The class index at `/tmp/class_index2.tsv` maps all 180K+ IntelliJ classes to their source jars.

3. **Test-only methods are not real implementations.** If a method name contains "Test" (e.g., `doTestExtract`), it's for testing/validation only and does not actually modify state. Find the real counterpart.

4. **Kotlin coroutine methods are async.** IntelliJ 2026.1+ uses Kotlin coroutines internally. Methods like `MethodExtractor.doExtract()` launch a coroutine and return immediately. For synchronous operations, use the processor-based approach (`ExtractMethodProcessor` + `ExtractMethodHandler.extractMethod()`) or PSI manipulation directly.

5. **Header files are often wrong.** The method signatures in header files may not match the actual decompiled implementation. Always verify with `javap` or CFR.

6. **Prefer synchronous paths.** Dialog-based APIs (`invoke(Project, Editor, PsiFile, DataContext)`) show UI dialogs that may not work in headless/test mode. Use the lower-level processor APIs directly, or manipulate PSI directly.

## Service Management
```bash
# Restart service
systemctl --user restart idealsp.service

# Check status
systemctl --user status idealsp.service
```