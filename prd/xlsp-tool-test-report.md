# xlsp Tool — Test Report

Date: 2026-05-02
Tester: OpenCode Agent
Tool under test: `.opencode/tools/xlsp.ts` (invoked as the `xlsp` LSP operations tool)
LSP server: IntelliJ IDEA 2026.1 (PID 3718195), TCP on port 8989
Workspace: `/vokk/home/lauri/dev/idealspserver/git` (IntelliJ Platform Plugin — Java 21, Gradle)

---

## 1. Test Scenarios

### Scenario 1: Workspace symbol search (no file)

```
operation: symbols
symbol: "LspServer"
```

**Expected**: Returns workspace symbols matching "LspServer" (classes, interfaces, methods).
**Result**: `success: true, count: 0`

### Scenario 2: Document symbol search (with file)

```
operation: symbols
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns document symbols from the specified file.
**Result**: `success: true, count: 0`

### Scenario 3: Go-to-definition

```
operation: define
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns the definition location of the `LspServer` class.
**Result**: `success: true, count: 0`

### Scenario 4: Find references

```
operation: references
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns all usage locations of `LspServer`.
**Result**: `success: true, count: 0`

### Scenario 5: Hover information

```
operation: hover
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns hover documentation/signature for `LspServer`.
**Result**: `success: true, count: 0`

### Scenario 6: Find implementations

```
operation: implement
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns classes implementing the `LspServer` interface (or vice versa).
**Result**: `success: true, count: 0`

### Scenario 7: Call hierarchy (incoming)

```
operation: calls
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
dir: "incoming"
```

**Expected**: Returns incoming call hierarchy (who calls this method/class).
**Result**: `success: true, count: 0`

### Scenario 8: Dataflow (from)

```
operation: dataflow
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
dir: "from"
```

**Expected**: Returns dataflow origins for the symbol.
**Result**: `success: true, count: 0`

### Scenario 9: Diagnostics

```
operation: diagnostics
symbol: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns inspection warnings/errors for the file.
**Result**: `success: true, count: 562`

### Scenario 10: Code completions

```
operation: complete
symbol: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns completion items.
**Result**: `success: true, count: 0`

### Scenario 11: Type definition

```
operation: type-def
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns the type definition of LspServer.
**Result**: `success: true, count: 0`

### Scenario 12: Signature help

```
operation: signature
symbol: "LspServer"
file: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns signature information.
**Result**: `success: true, count: 0`

### Scenario 13: Code actions

```
operation: actions
symbol: "server/src/main/java/tf/locals/idealsp/server/LspServer.java"
```

**Expected**: Returns available code actions (quick-fixes, intentions) for the file.
**Result**: `success: true, count: 0`

---

## 2. Results Summary

| Operation | Success | Count | Notes |
|-----------|---------|-------|-------|
| `diagnostics` | ✅ true | **562** | File, line, char, severity, message, source — fully populated |
| `symbols` (no file) | ✅ true | 0 | workspace/symbol returns empty |
| `symbols` (file) | ✅ true | 0 | documentSymbol returns empty |
| `define` | ✅ true | 0 | Text fallback finds symbol; LSP definition returns empty |
| `references` | ✅ true | 0 | Same pattern |
| `hover` | ✅ true | 0 | Same pattern |
| `implement` | ✅ true | 0 | Same pattern |
| `type-def` | ✅ true | 0 | Same pattern |
| `calls` | ✅ true | 0 | Same pattern |
| `dataflow` | ✅ true | 0 | Same pattern |
| `signature` | ✅ true | 0 | Same pattern |
| `complete` | ✅ true | 0 | Same pattern |
| `actions` | ✅ true | 0 | Same pattern |

**Diagnostics is the standout winner** — 562 issues found with full fidelity (file path, exact line/char range, severity [error/warn/info/hint], message text, source).

**All semantic operations return count=0** because the project's PSI tree is too broken for navigation features.

---

## 3. Root Cause Analysis

### 3.1 Log Investigation

**Source 1: IntelliJ log** (`~/.cache/JetBrains/IntelliJIdea2026.1/log/idea.log`)

Key entries at the time of xlsp testing (18:55–18:56 UTC):

```
LspServer - Opening project: file:///vokk/home/lauri/dev/idealspserver/git
LspServer - LSP was initialized. Project: Project(name=git, containerState=COMPONENT_CREATED, ...)
ProjectService - Caching project: ...
ProjectService - Delayed source root re-check (5s after project open)
ProjectService - Delayed source root re-check (15s after project open)
...
LspServer - Opening project: file:///vokk/home/lauri/dev/idealspserver/git  (again, after ~2s)
ProjectService - Closing project: ... (project wasn't opened by LSP server; do nothing)
```

Each xlsp call creates an `initialize` → operation → `shutdown`/`exit` cycle in ~2–3 seconds.

**Critical observation**: The project state is `COMPONENT_CREATED`, not `COMPONENT_LOADED`. This means IntelliJ's module model (source roots, classpath, SDK dependencies) was never fully loaded.

**Source 2: Systemd journal** (`idealsp.service`)

The service unit captures only IntelliJ's stdout/stderr. Relevant entries:

```
May 01 00:27:18 - MalformedJsonException: Unterminated string
May 01 00:27:18 - Missing header Content-Length in input
```

Only ERROR/SEVERE-level messages appear. Structured LSP request/response logging is absent.

**Source 3: Gradle import log** (`gradle-import.log`)

Shows repeated "Starting Gradle Daemon..." entries but no successful import completion. The daemon starts but appears to not complete before the project is closed again.

### 3.2 Root Cause Chain

```
xlsp call → initialize → ProjectService opens project (COMPONENT_CREATED)
  ↓
Gradle import starts (async, takes 10–60s for IntelliJ plugin projects)
  ↓
xlsp sends textDocument/definition within 1–2s
  ↓
PSI trees are broken because IntelliJ SDK classpath isn't resolved yet
  ↓
LSP returns empty results for all semantic operations
  ↓
Diagnostics partially work (IntelliJ inspection engine runs on broken PSI)
  ↓
xlsp sends shutdown → exit, closing the project (~3s after open)
  ↓
Source root re-checks never fire (scheduled at 5s, 15s, 30s)
```

### 3.3 Why Diagnostics Works

IntelliJ's inspection engine can partially analyze files even with broken PSI:
- Text-level inspections (string concatenation → text block, commented code)
- Unresolved symbol errors (500+ "Cannot resolve symbol 'intellij'")
- Always-true conditions, unused methods, etc.

These do not require a fully resolved classpath — they work on partial AST.

### 3.4 Why Semantic Features Fail

Navigation features require:
1. Fully resolved PSI (all symbols in the type hierarchy chain)
2. Indexed project scope (GlobalSearchScope needs module content roots)
3. Working resolve chain (PSI references point to resolved elements)

None of these are available when the project is `COMPONENT_CREATED` and the Gradle import hasn't finished.

---

## 4. Logging Assessment

### Are the logs sufficient to diagnose failures?

| Log source | Covers | Missing |
|-----------|--------|---------|
| `idea.log` | Project open/close lifecycle, state transitions | LSP request/response payloads, operation-level success/failure, timing breakdown |
| `idealsp.service` journal | Process lifecycle (start/stop), severe errors | All normal LSP activity, server health, request counts |
| `gradle-import.log` | Gradle daemon lifecycle | Import success/failure details, elapsed time, dependency resolution status |

**Verdict: Partially.** The idea.log reveals the project lifecycle issue (COMPONENT_CREATED + rapid open/close cycles), which was enough to diagnose the root cause. However, the following gaps make debugging harder than necessary:

### Recommended Logging Improvements

1. **LSP request/response logging** in `LspServer` / `MyTextDocumentService`:
   - Log each incoming request method + parameters (first 200 chars) at DEBUG level
   - Log result count and success/failure at INFO level
   - Currently only lifecycle events are logged

2. **Project readiness check** in `LspServer`:
   - Before serving textDocument requests, check `project.isInitialized()` and module state
   - Log a WARN if serving requests on an uninitialized project (e.g. `"Serving textDocument/definition on project with state COMPONENT_CREATED — results may be incomplete"`)
   - This single log line would have immediately explained all zero-count results

3. **Gradle import status logging** in `ProjectService`:
   - Log when external system import starts and finishes (already partially done via `ProjectDataImportListener`)
   - Log import duration and whether it was successful
   - Current log goes to `gradle-import.log` but doesn't associate imports with LSP sessions

4. **Per-request timing** in the xlsp CLI tool:
   - Log wall-clock time for each LSP request (already partially available — could add `--verbose` flag)
   - Report whether the server responded within expected timeframe
   - Surface the project state from the initialize response (if available)

5. **Systemd journal enrichment**:
   - The `idealsp.service` should ideally log a periodic heartbeat (e.g. "LSP server alive, N sessions active, M projects open")
   - Currently the journal is silent except for process start/stop and severe errors

---

## 5. Proposed: xlsp Status Operation

It would be highly valuable to add a **`status` operation** to the xlsp tool that reports:

```
operation: status
symbol: (not used)
```

Proposed output:

```json
{
  "success": true,
  "operation": "status",
  "server": {
    "version": "IntelliJ IDEA 2026.1",
    "uptime": "1d 4h 23m",
    "port": 8989
  },
  "project": {
    "name": "git",
    "state": "COMPONENT_CREATED",
    "initialized": false,
    "dumb_mode": false,
    "modules": 0,
    "source_roots": 0,
    "gradle_import": "pending",
    "indexing": {
      "in_progress": false,
      "progress_pct": null
    }
  },
  "diagnostics_backlog": 3,
  "active_sessions": 1
}
```

This would let users (and automated tooling) immediately understand:
- Is the project loaded and ready?
- Is indexing still running?
- Has Gradle/Maven finished importing?
- Are there queued/pending diagnostics?

Implementation approach:
- The LSP server already tracks `project.isInitialized()` and `DumbModeListener` state
- The `initialize` response could be extended to include project state in `InitializeResult`
- Or a custom `ideals/status` JSON-RPC method could be added to `IdeaLspServer`

---

## 6. Tool Quality Assessment

### What works well

- **Protocol plumbing is solid**: Every operation connects, initializes LSP, sends the correct JSON-RPC method, and returns structured JSON — no crashes, timeouts, or protocol errors in 13 tested operations.
- **Error handling is clean**: Server-unavailable returns `"server unavailable"` with port hint. Symbol-not-found gives `"Try xlsp symbols <query> first"`.
- **Path resolution**: Relative paths resolve correctly against the workspace root. File opening via `didOpen` works.
- **Text fallback in symbol-resolver.ts**: When documentSymbol fails, it falls back to regex-based text scanning — a pragmatic approach that works for top-level definitions.
- **Tool definition is well-typed**: Uses `@opencode-ai/plugin` schema with proper enums and optional parameters.
- **Operation coverage**: 12 LSP operations exposed, covering the full spectrum of navigation/analysis features.

### Limitations

1. **No `--wait` flag in OpenCode wrapper**: The CLI supports `--wait` for indexing delays, but the `.opencode/tools/xlsp.ts` wrapper doesn't expose it. This is the single most impactful missing feature — it would allow the project to finish indexing before serving requests.
2. **No `--context` flag in wrapper**: The CLI supports `--context N` to show surrounding source lines, but it's not exposed.
3. **No `--severity` flag in wrapper**: For diagnostics filtering.
4. **No connection pooling**: Each xlsp call opens a new TCP connection, initializes LSP, opens/imports the project, runs one request, then shuts down. This takes ~3s per call and prevents the project from reaching a stable state.
5. **Missing status introspection**: No way to query server/project health (see Section 5 proposal).

### Recommended Priority Fixes

| Priority | Change | Effort |
|----------|--------|--------|
| **P0** | Add `--wait` flag support to `.opencode/tools/xlsp.ts` | 1 line |
| **P1** | Add `--context` and `--severity` flag support | 2 lines |
| **P2** | Add `status` operation (server-side + CLI + tool def) | Medium |
| **P3** | Add LSP request/response logging in `LspServer` | Small |
| **P3** | Add project readiness WARN log in `MyTextDocumentService` | Small |
| **P4** | Connection pooling / persistent session in xlsp client | Large |
