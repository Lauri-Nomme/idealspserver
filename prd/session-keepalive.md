# PRD: Server-Side LSP Session Keepalive

**Status:** Draft
**Date:** 2026-05-02
**Related:** [xlsp Tool Test Report](./xlsp-tool-test-report.md)

---

## 1. Problem

Each `xlsp` invocation creates a fresh LSP session: TCP connect → `initialize` → operation → `shutdown`/`exit` → force-close project. This takes ~3 seconds per call, and the project never reaches a usable state because:

- IntelliJ opens the project at `COMPONENT_CREATED` (not `COMPONENT_LOADED`)
- Gradle import starts but needs 10–60 seconds for an IntelliJ Platform Plugin project
- Scheduled source-root re-checks (5s, 15s, 30s) never fire — the project is already torn down
- All semantic LSP operations return empty results (confirmed in [test report](./xlsp-tool-test-report.md))

The only operation that works is diagnostics, because IntelliJ's inspection engine can analyze files with broken PSI.

**Root cause:** TCP session lifetime == project lifetime. Every disconnect destroys the project.

## 2. Solution

Decouple TCP session lifetime from project lifetime. The LSP server keeps the IntelliJ project alive across TCP disconnects, reusing it when a new client connects to the same workspace.

### 2.1 Session Lifecycle

```
                    Client A connects           Client B connects
                         │                           │
                         ▼                           ▼
              ┌─────────────────────┐   ┌─────────────────────┐
              │  LspServer instance │   │  LspServer instance │
              │  (per-connection)   │   │  (per-connection)   │
              └────────┬────────────┘   └────────┬────────────┘
                       │                         │
                       └────────┬────────────────┘
                                │
                   ┌────────────▼────────────┐
                   │   ProjectSessionRegistry │
                   │   ┌───────────────────┐  │
                   │   │  Project "git"     │  │
                   │   │  refcount: 2       │  │
                   │   │  opened at: 18:55  │  │
                   │   │  last release: N/A │  │
                   │   │  state: LOADED     │  │
                   │   └───────────────────┘  │
                   └─────────────────────────┘
```

### 2.2 Key Properties

| Property | Value |
|----------|-------|
| TTL after last client disconnect | **2 hours** |
| Maximum concurrent clients per project | unbounded (IntelliJ serializes internally) |
| Workspace matching key | `LspPath` (normalized filesystem path of workspace root) |
| Cleanup trigger | TTL expiry OR explicit `forceCloseProject` on JVM shutdown |

## 3. Architecture

### 3.1 New Component: `ProjectSessionRegistry`

A singleton that owns the project lifecycle independent of any TCP connection.

```java
public class ProjectSessionRegistry {
    // workspace root → session metadata + Project
    private final Map<LspPath, ProjectSession> sessions;

    // Called by LspServer.initialize():
    // Returns existing project if one exists for this workspace, or null.
    // Increments refcount.
    Project claimProject(LspPath workspaceRoot);

    // Called by LspServer.stop():
    // Decrements refcount. If refcount hits 0, starts TTL timer.
    // Returns true if project was released (caller should null their reference).
    void releaseProject(LspPath workspaceRoot, Project project);

    // Called by ProjectService (replaces direct open):
    // Opens a project or returns the one from the registry if already open.
    Project openOrClaimProject(LspPath workspaceRoot);
}
```

### 3.2 Changes to `LspServer`

**`initialize()` — claim, don't open:**
```java
// Before (current):
project = ProjectService.getInstance().resolveProjectFromRoot(projectRoot);

// After:
project = ProjectSessionRegistry.getInstance().openOrClaimProject(projectRoot);
```

**`stop()` — release, don't close:**
```java
// Before (current):
public void stop() {
    messageBusConnection.disconnect();
    ProjectService.getInstance().closeProject(project);  // forceClose
    this.project = null;
}

// After:
public void stop() {
    messageBusConnection.disconnect();
    ProjectSessionRegistry.getInstance().releaseProject(projectRoot, project);
    this.project = null;
}
```

### 3.3 Changes to `ProjectService`

- `resolveProjectFromRoot()` → delegates project opening to `ProjectSessionRegistry`
- `closeProject()` → called only by `ProjectSessionRegistry` on TTL expiry, NOT by `LspServer.stop()`
- Existing functionality (`ensureProject`, `waitUntilInitialized`, `ensureSourceRoots`, `registerSourceRootProtection`) is unchanged

### 3.4 TTL Cleanup

```java
// In ProjectSessionRegistry.releaseProject():
if (session.refcount.decrementAndGet() == 0) {
    LOG.info("All clients disconnected from workspace " + workspaceRoot 
             + ". Project will be kept alive for " + TTL_HOURS + " hours.");
    
    scheduler.schedule(() -> {
        ProjectSession stale = sessions.remove(workspaceRoot);
        if (stale != null && !stale.project.isDisposed()) {
            LOG.info("TTL expired for workspace " + workspaceRoot + ". Closing project.");
            ProjectService.getInstance().closeProject(stale.project);
        }
    }, TTL_HOURS, TimeUnit.HOURS);
}
```

Shutdown hook ensures all remaining projects are force-closed on JVM exit.

## 4. Multi-Client Session Sharing

Multiple TCP connections can share the same IntelliJ project. This section analyzes feasibility.

### 4.1 Architecture Per-Connection

Each TCP connection creates a separate stack:

```
TCP conn #1:  LspServer#1 → MyTextDocumentService#1 → LspSession#1
TCP conn #2:  LspServer#2 → MyTextDocumentService#2 → LspSession#2
                                         ↓                        ↓
                                  Both delegate to shared Project object
```

`LspSession` is an interface already implemented by `LspServer`. The only method is `getProject()`, which returns the shared project reference.

### 4.2 Concurrency Model

| Concern | Handling |
|---------|----------|
| **PSI access** | IntelliJ's read/write action system serializes all Project access. Commands call `runReadAction()` or `runWriteAction()` internally. |
| **Document open/close** | Each connection manages its own `didOpen`/`didClose` independently. The underlying document is shared but per-connection lifecycle is tracked via `ManagedDocuments`. |
| **Diagnostics publishing** | Each `LspServer` instance has its own `client`. Diagnostics are published per-client notification. Redundant computations are acceptable (and already happen on single-connection reconnect). |
| **Dumb mode notifications** | Each `LspServer` subscribes to `DumbService.DUMB_MODE`. All instances get notified — each notifies its own client. No conflict. |
| **Progress reporting** | `WorkDoneProgressReporter` is per-`LspServer`. Multiple clients get duplicate progress. Acceptable — progress is advisory. |
| **Capabilities negotiation** | `InitializeParams.capabilities` are per-session. The first client that opens the project defines the capabilities used for project-level features. Subsequent clients' capabilities are handled per-session by each `LspServer`. In practice, xlsp always sends identical capabilities. |

### 4.3 Reference Counting States

```
State: EMPTY (no project)
  │
  │ Client A: initialize → openOrClaimProject()
  ▼
State: ACTIVE (refcount=1, project loaded)
  │
  │ Client B: initialize → openOrClaimProject()
  ▼
State: ACTIVE (refcount=2, same project)
  │
  │ Client A: shutdown/exit → releaseProject()
  ▼
State: ACTIVE (refcount=1)     ← Client B still connected
  │
  │ Client B: shutdown/exit → releaseProject()
  ▼
State: ORPHANED (refcount=0, TTL timer running)
  │
  │ After 2 hours:
  ▼
State: CLOSED (project disposed, entry removed from registry)
  │
  │ New client: initialize → fresh open
  ▼
State: ACTIVE (refcount=1, new project)
```

### 4.4 Edge Cases

| Scenario | Behavior |
|----------|----------|
| **Different workspace root** | New `initialize` with a different workspace → close old project (if refcount=0), open new one. Existing clients on old workspace are unaffected. |
| **Client A disconnects during operation** | Operation completes on IntelliJ's thread pool, result is discarded (no client to receive it). No impact on other clients. |
| **Project becomes disposed externally** | `ProjectSessionRegistry` checks `project.isDisposed()` before reuse. If disposed, removes entry and opens fresh. |
| **TTL reset on reconnect** | Each `releaseProject()` resets the TTL timer. A project actively used every 30 minutes by xlsp calls stays alive indefinitely. |
| **JVM shutdown** | Shutdown hook iterates all sessions and calls `forceCloseProject`. No memory leaks on exit. |

## 5. xlsp Tool Impact

### 5.1 No Changes Required

The xlsp CLI and `.opencode/tools/xlsp.ts` work unchanged. The CLI still sends `initialize` → operation → `shutdown`/`exit`. The difference is server-side only — `shutdown` no longer destroys the project.

### 5.2 Bonus: Natural Indexing

Because the project survives between calls:
- Gradle import completes in the background
- Indexing finishes
- Source roots stabilize via delayed re-checks
- Next xlsp call connects to a fully-loaded project

### 5.3 Recommended Addition: `status` Operation

A new xlsp operation to query server/project health. See [test report §5](./xlsp-tool-test-report.md#5-proposed-xlsp-status-operation) for full specification.

```json
{
  "success": true,
  "operation": "status",
  "project": {
    "name": "git",
    "state": "COMPONENT_LOADED",
    "initialized": true,
    "dumb_mode": false,
    "modules": 3,
    "connected_clients": 2,
    "idle_seconds": 15
  }
}
```

## 6. Implementation Plan

| Phase | Task | Effort | Dependencies |
|-------|------|--------|-------------|
| 1 | Create `ProjectSessionRegistry` class | Small | None |
| 2 | Add refcount + TTL logic to registry | Small | Phase 1 |
| 3 | Modify `LspServer.initialize()` to use `openOrClaimProject()` | 1 line | Phase 2 |
| 4 | Modify `LspServer.stop()` to use `releaseProject()` | 3 lines | Phase 2 |
| 5 | Update `ProjectService.resolveProjectFromRoot()` to delegate | Medium | Phase 3 |
| 6 | Add shutdown hook for cleanup | Small | Phase 2 |
| 7 | Add INFO/DEBUG logging for session lifecycle | Small | Phase 2 |
| 8 | Add `idealsp/status` custom JSON-RPC method | Medium | None |
| 9 | Add `status` operation to xlsp CLI + tool definition | Small | Phase 8 |
| 10 | Integration test: serial xlsp invocations share project | Medium | Phase 5 |
| 11 | Integration test: multiple concurrent clients | Medium | Phase 5 |

**Total estimated effort:** 2–3 days

## 7. Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| **Memory pressure** — IntelliJ project objects are large (~100MB–500MB) | Medium | 2-hour TTL; only one project per workspace; existing GC handles memory |
| **Stale PSI** — files changed between xlsp calls | Low | IntelliJ VFS detects filesystem changes automatically; PSI refreshes on next access |
| **Thread leak** — scheduled re-checks create background threads per open | Low | Existing behavior; `AppExecutorUtil.getAppScheduledExecutorService()` uses a shared pool |
| **`forceCloseProject` hangs** — known IntelliJ issue with project disposal | Medium | Wrap in timeout (e.g., 10s) + `LOG.warn`; move to background thread |
| **Registering DumbModeListener multiple times** — each `LspServer` subscribes | Low | `project.getMessageBus().connect()` creates a disposable connection; each instance gets its own subscription — no conflict |
| **Workspace root collision** — two different physical paths that resolve to the same canonical path | Low | `LspPath` already normalizes paths; use `LspPath.equals()` for map key matching |

## 8. Success Criteria

1. **First xlsp call after cold start**: Same behavior as today (project opens, returns results if project loaded in time, or empty if not)
2. **Second xlsp call (within TTL)**: Connects to existing project; INSTANT (no Gradle import, no delayed re-checks); returns real results for all LSP operations
3. **After 2 hours idle**: Project is force-closed; next call starts fresh
4. **Two concurrent xlsp clients**: Both connect, share the same project, receive independent diagnostic streams
5. **JVM shutdown**: All projects cleanly closed; registry emptied; no error logs

## 9. Open Questions

1. **Should the TTL be configurable?** — Yes, via system property `ideals.lsp.session.ttl.hours` with default 2.
2. **Should `shutdown` vs `exit` have different semantics?** — Currently both call `stop()`. We could make `shutdown` = release session and `exit` = release + don't reconnect. For simplicity, treat them identically.
3. **What if a client sends `shutdown` but never disconnects?** — TCP connection timeout naturally cleans up. If needed, add heartbeat-based detection (future enhancement).
4. **Should `idealsp/status` be in the standard LSP namespace or custom?** — Custom `idealsp/status` to avoid spec violation. Standard LSP has no status/health check method.
