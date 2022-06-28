# Agent Guidelines for IdeaLSP

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

# Run unit tests (individual test class)
cd server && ./gradlew test --tests "tf.locals.idealsp.server.lsp.RefactoringCommandTest" --no-daemon

# Run comprehensive integration tests
python3 scripts/test_lsp_comprehensive.py

# Run specific comprehensive tests
python3 scripts/test_lsp_comprehensive.py --tests 52,53,54
```

## xlsp CLI
The `xlsp` tool (Bun/TypeScript) provides CLI access to all LSP operations:
```bash
# Refactoring — --pos=line:col or --pos=line:col-endLine:endCol
bun run tools/xlsp/cli.ts refactor move MyClass --target=src/movepkg in src/Foo.java
bun run tools/xlsp/cli.ts refactor safe-delete unusedMethod in src/Foo.java
bun run tools/xlsp/cli.ts refactor extract-method newMethod --pos=5:4 in src/Foo.java
bun run tools/xlsp/cli.ts refactor extract-method newMethod --pos=5:4-10:20 in src/Foo.java
bun run tools/xlsp/cli.ts refactor introduce-variable --pos=10:8 in src/Foo.java
bun run tools/xlsp/cli.ts refactor inline someMethod in src/Foo.java

# Code actions
bun run tools/xlsp/cli.ts actions in src/Foo.java
bun run tools/xlsp/cli.ts apply "Change variable 'a' type to 'String'" in src/Foo.java
```

All refactoring commands use PSI manipulation directly (file create/delete with package declaration rewrite for move, `SafeDeleteProcessor` reflection for safe-delete) to avoid dialog/context requirements.

## IntelliJ Internal API Usage

This project uses reflection to call IntelliJ internal (non-public) APIs extensively. This is by design — the public API surface of IntelliJ does not expose the functionality needed for headless LSP operations (refactoring, code analysis, etc.).

### Rules

1. **Using internal APIs via reflection is the whole point.** Do not give up when an API is non-public. Find the correct internal class and method, access it via `setAccessible(true)` (on methods or fields), and call it.

2. **If a method doesn't exist, look for alternatives.** IntelliJ's internal APIs change between versions. Use `javap` to discover available methods. The class index at `/tmp/class_index2.tsv` maps all 180K+ IntelliJ classes to their source jars.

3. **Test-only methods are not real implementations.** If a method name contains "Test" (e.g., `doTestExtract`), it's for testing/validation only and does not actually modify state. Find the real counterpart.

4. **Kotlin coroutine methods are async.** IntelliJ 2026.1+ uses Kotlin coroutines internally. Methods like `MethodExtractor.doExtract()` launch a coroutine and return immediately. For synchronous operations, use the processor-based approach (`ExtractMethodProcessor` + `ExtractMethodHandler.extractMethod()`) or PSI manipulation directly.

5. **Header files are often wrong.** The method signatures in header files may not match the actual implementation. Always verify with `javap`.

6. **Prefer synchronous paths.** Dialog-based APIs (`invoke(Project, Editor, PsiFile, DataContext)`) show UI dialogs that may not work in headless/test mode. Use the lower-level processor APIs directly, or manipulate PSI directly.

## Service Management
```bash
# Restart service
systemctl --user restart idealsp.service

# Check status
systemctl --user status idealsp.service
```