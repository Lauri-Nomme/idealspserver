# RCA: Document Symbols test hangs (Test 3) — IntelliJ read/write-lock starvation during project import/sync

## 1. Problem Statement

The very first comprehensive Python test that fails is **Test 3 — Document symbols**
(`textDocument/documentSymbol` against `LspServer.java`). It fails with a genuine
**hang**, not a wrong result:

```
2. Opened test file, ... (didOpen)  -> OK
3. Document symbols: FAILED - None
```

The same failure reproduces:

- Running the full suite (`scripts/test_lsp_comprehensive.py`): tests 1–2 pass, test 3
  fails first, and the rest of the suite stalls.
- In isolation: `python3 scripts/test_lsp_comprehensive.py --tests 3` -> `FAILED - None`.
- Through the CLI: `bun run tools/xlsp/cli.ts symbols --file .../LspServer.java`
  -> `{"success":false,"operation":"symbols","error":"timeout"}`.
- **After a clean `systemctl --user restart idealsp.service`** — so this is a real
  defect in the `documentSymbol` path, not a wedged/stale server.

### Symptoms observed

The client sends `textDocument/documentSymbol` but never receives a response within its
timeout, so it records `None`. Concurrently the server logs:

```
WARN - #c.i.o.a.ApplicationManager - Cannot execute background write action in 10 seconds.
      Thread dump is stored in .../log/bg-wa/thread-dump-1429394527.txt
WARN - #tf.locals.idealsp.server.LspServer - Could not get confirmation when creating
      work done progress; will act as if it's created
      java.util.concurrent.TimeoutException
```

## 2. Reproduction

```bash
# server must be running
systemctl --user status idealsp.service

# isolated failing test
python3 scripts/test_lsp_comprehensive.py --tests 3

# via CLI
bun run tools/xlsp/cli.ts symbols --file server/src/main/java/tf/locals/idealsp/server/LspServer.java
```

## 3. Root Cause (corrected)

### 3.1 Where the request thread is stuck

A live `jcmd <pid> Thread.print` captured while `documentSymbol` was pending shows the
request thread blocked **acquiring a read permit** — it cannot even start:

```
tf.locals.idealsp.server.symbol.DocumentSymbolService.computeDocumentSymbols(DocumentSymbolService.java:57)
tf.locals.idealsp.server.util.MiscUtil.resolvePsiFile(MiscUtil.java:59)
tf.locals.idealsp.server.util.MiscUtil.invokeWithPsiFileInReadAction(MiscUtil.java:86)
tf.locals.idealsp.server.util.MiscUtil.produceWithPsiFileInReadAction(MiscUtil.java:74)
com.intellij.openapi.application.impl.ApplicationImpl.runReadAction(ApplicationImpl.java:1104)
NestedLocksThreadingSupport.smartAcquireReadPermit(NestedLocksThreadingSupport.kt:801)
NestedLocksThreadingSupport.acquireReadPermit(NestedLocksThreadingSupport.kt:480)   // <- BLOCKED
```

### 3.2 The writer that starves the readers (fresh-start thread dump)

A thread dump taken **after a clean restart, before any other test interference**
(`/tmp/opencode/td_fail.txt`) shows **12 request threads parked at
`acquireReadPermit`** — documentSymbol, per-file diagnostics, etc. The lock is held up by
the external system sync that runs at project open:

- Thread at `td_fail.txt:2799` — a **Gradle sync coroutine**, SUSPENDED at
  `upgradeWritePermit` while **holding a read permit**:
  ```
  GradleSyncActionRunner.performSyncContributors(GradleSyncProjectConfigurator.kt:130)
  WorkspaceModelImpl.updateWithRetry(WorkspaceModelImpl.kt:268)
  CoroutinesKt.edtWriteAction(coroutines.kt:280)
  ApplicationImpl.runWriteAction / NestedLocksThreadingSupport.runWriteActionBlocking
  RWMutexIdea.acquireWriteActionPermit / ComputationState.upgradeWritePermit  // SUSPENDED
  ```
- Thread at `td_fail.txt:244` — EDT `NonBlockingFlushQueue.runNextEvent` flushing an
  `edtWriteAction` (`runWriteActionBlocking`).
- 12 LSP/diagnostic reader threads blocked at `NestedLocksThreadingSupport.acquireReadPermit`.

### 3.3 The mechanism

IntelliJ 2026.1 uses a **writer-preferring** read/write lock
(`com.intellij.platform.locking.impl.NestedLocksThreadingSupport`). When a write is
queued/pending, **all subsequently arriving readers are held off**. At cold start the
external build system (Gradle) import runs a workspace-model update
(`WorkspaceModelImpl.updateWithRetry`) that takes a write action while the coroutine still
holds a read permit (`upgradeWritePermit`). That pending write:

1. Blocks every new reader (documentSymbol, completion, hover, signature help,
   diagnostics, …) at `acquireReadPermit`.
2. Cannot itself complete promptly because of the mutual wait with the readers/EDT flush
   it is holding off — a self-reinforcing wedge.

The LSP futures are never completed; the client times out and the test records `None`.

### 3.4 What the earlier fix did and did not do

An earlier version of this RCA blamed `DocumentSymbolService`'s `finally` block, which
wrapped a trivial `Disposer.dispose(disposable)` in
`WriteCommandAction.runWriteCommandAction` — i.e. the `documentSymbol` request was *itself*
queuing a background write. That **was a real defect and has been removed** (see §5.1):
bytecode verification (javap of `util-8.jar`) proves `Disposer.dispose` is write-free
(`Disposer.dispose` → `ObjectTree.executeAll`, no `WriteAction`/`WriteCommandAction`
references anywhere), so it never needed a write action. The same write-wrapped disposal
was removed from `CompletionService.doResolve` and `SignatureHelpService`.

**However, removing it did not cure the hang on cold restart.** The thread dump after the
disposal fix shows the block still happening at the *start* of `computeDocumentSymbols`
(line 57, `resolvePsiFile`), before any disposal. The persistent writer at cold start is
the **Gradle/workspace-model sync**, not our code. The earlier "pass" was a warm session
where Gradle sync had already completed; every cold restart re-runs the sync and the wedge
returns. Conclusion: the disposal fix is correct hygiene but **insufficient**; the root
cause is the sync-window read/write starvation.

### 3.5 Key evidence artifacts

- Server log: `Cannot execute background write action in 10 seconds`.
- Thread dumps `bg-wa/thread-dump-1429394527.txt` and `/tmp/opencode/td_fail.txt`:
  request threads in `acquireReadPermit` inside `computeDocumentSymbols`; Gradle sync
  coroutine suspended in `upgradeWritePermit`/`WorkspaceModelImpl.updateWithRetry`;
  EDT `NonBlockingFlushQueue` write flush.
- `documentSymbol` handled via `MyTextDocumentService.documentSymbol` →
  `CompletableFutures.computeAsync(AppExecutorUtil.getAppExecutorService(), …)`
  (background executor, never cancelled on client timeout, so blocked threads accumulate).

## 4. Is Disposer.dispose really write-free? — VERIFIED

Bytecode inspection (javap) of `util-8.jar`:

- `Disposer.dispose(Disposable)` -> `Disposer.dispose(Disposable, boolean)` ->
  `ObjectTree.executeAll(...)`.
- Neither `Disposer` nor `ObjectTree` contain **any** reference to
  `WriteAction` / `runWriteAction` / `WriteCommandAction` / `acquireWrite`.

Conclusion: **`Disposer.dispose` neither requires nor acquires a write action.** Wrapping
it in a write action is unnecessary and only adds a queued write that can starve readers.

The codebase already had write-free disposal conventions elsewhere:
`CompletionService:430` and `CodeActionService:377` use
`ApplicationManager.getApplication().invokeAndWait(() -> Disposer.dispose(disposable))`,
and `DataFlowFromCommand:86`, `DataFlowToCommand:95`, `FindUsagesCommand:132`,
`RenameCommand:134`, `DocumentHighlightCommand:70`, `PrepareCallHierarchyCommand:82`,
`OnTypeFormattingCommand:86` call `Disposer.dispose(disposable)` directly.

## 5. Fixes

### 5.1 Applied: write-free disposal (necessary hygiene, not sufficient on its own)

**`DocumentSymbolService.computeDocumentSymbols`** — removed the write-wrapped disposal:

```java
} finally {
  ApplicationManager.getApplication().invokeAndWait(() -> Disposer.dispose(disposable));
}
```

The same audit found and fixed **two additional sites with the identical defect**:

- **`CompletionService.doResolve`** (line ~244):
  `invokeAndWait(() -> WriteCommandAction.runWriteCommandAction(project, () -> Disposer.dispose(disposable)))`
  -> `invokeAndWait(() -> Disposer.dispose(disposable))`.
- **`SignatureHelpService`** (line ~96):
  `WriteAction.runAndWait(() -> Disposer.dispose(disposable))`
  -> `ApplicationManager.getApplication().invokeAndWait(() -> Disposer.dispose(disposable))`.

Audited and **left unchanged** (genuine state modifications requiring a write action):
`OnTypeFormattingCommand:102-106` (`deleteSelectedText`), `RefactoringHandler:253`
(`MoveClassesOrPackagesUtil.doMoveClass`), `CodeActionService:367-370`
(`setText` + `commitDocument`), `SignatureHelpService:79/89` (editor creation /
`commitAllDocuments`).

### 5.2 Applied: import/sync gate — fail fast with a clear error instead of hanging

The sync-window starvation is an IntelliJ-internal lock behavior; we make the LSP server
robust against it by **detecting the import/sync state and rejecting requests with a clear
exception** rather than blocking until the client times out.

**New file `ProjectImportInProgressException.java`** — extends
`org.eclipse.lsp4j.jsonrpc.ResponseErrorException` carrying a `ResponseError(code=-32603,
message=…)` whose message names the project and tells the client to retry once import
finishes. Bytecode inspection (javap of the installed lsp4j 0.17.0 jar) confirmed that
LSP4J's default exception handler unwraps a `ResponseErrorException` — even inside a
`CompletionException` — and sends its `ResponseError` **verbatim** (message only, no stack),
instead of the generic `"Internal error"`. So the client sees the clean, retryable message.

**`ProjectService`** now tracks which projects are importing/syncing, using **three signals**
(only the last one is actually operative in this environment):

1. **`ProjectDataImportListener.TOPIC`** (per project) — `onImportStarted` -> add,
   `onImportFinished`/`onImportFailed` -> remove. **Never fires in this environment**
   (0 events across a full boot; journalctl), so it is kept only as a best-effort fallback.
2. **`ApplicationImpl.isWriteActionPending()`** — the *actual* starvation condition: while
   the workspace-model update has queued its write, `isWriteActionPending()` is true and
   readers would be held off by the writer-preferring lock. This method is **not** on the
   `Application` interface, so it is invoked reflectively
   (`app.getClass().getMethod("isWriteActionPending")`); javap confirms
   `NestedLocksThreadingSupport.myWriteActionPending` (AtomicReference, `last > 0`) backs it.
   If reflection fails, `LOG.warn` and treat as "not pending" (gate degrades).
3. **`GradleSyncListener.TOPIC`** (`org.jetbrains.plugins.gradle.service.syncAction`) —
   subscribed **reflectively** (the Gradle plugin is a bundled plugin, not on the compile
   classpath). The class is loaded at runtime from the `com.intellij.gradle` plugin's own
   classloader via `PluginManagerCore.getPlugins()`; a `<depends>com.intellij.gradle</depends>`
   declaration is deliberately NOT used because it breaks the light test framework's
   `codeInsight.parameterInfo.listener` extension-point registration (SignatureHelpServiceTest
   times out waiting for `MyParameterInfoListener` events).
   `onModelFetchPhaseCompleted`/`onModelFetchCompleted` -> add,
   `onProjectLoadedActionCompleted`/`onModelFetchFailed` -> remove. This is the **whole-sync**
   signal: it marks the project as importing from the *start of model fetch* (when NO write
   is pending yet) until the sync fully completes, closing the gap where only signal #2 is
   silent. Subscribed at **`projectOpened`** (application-level `ProjectManager.TOPIC`
   listener registered in the `ProjectService` constructor) so the *initial* sync is caught —
   subscribing later (after `openProject`+`waitUntilInitialized`+`ensureSourceRoots` in
   `getProject`) misses the early model-fetch events and leaves the gate blind for the whole
   first sync.

Exposed API:
- `boolean isImportInProgress(Project)`
- `boolean isImportInProgressOrWritePending(Project)` — fast, non-blocking; used by
  diagnostics gating.
- `boolean waitForProjectStability(Project, long timeoutMs)` — blocks (500 ms poll, no locks
  held) until neither signal is set; returns false on timeout.
- `void ensureImportFinished(Project)` — if the project is in `importingProjects`, throw
  immediately; otherwise wait up to `PENDING_WRITE_GRACE_MS = 3000` for a pending write to
  clear, then throw `ProjectImportInProgressException`.

**Guard call sites** — every read-lock-hungry query entry point:

- `LspCommand.runAsyncWithCancel`/`getResult` (covers definition, type definition,
  implementation, references, hover, document highlight, call/type hierarchy, rename,
  prepare-rename, formatting, refactoring): guard checked **before** `invokeAndWait`, so
  commands never enter the read-lock wait while a sync is pending.
- `DocumentSymbolService.computeDocumentSymbols`.
- `CompletionService.computeCompletions` and `resolveCompletion`.
- `SignatureHelpService.computeSignatureHelp`.
- `WorkspaceSymbolService.runSearch`.

**Effect:** while the project is importing/syncing, LSP queries fail fast with a clear
`-32603` message instead of starving on the writer-preferring lock and timing out.
`didOpen`/`didChange`/`initialize` are **not** gated — only queries, so project
lifecycle/notifications keep working.

### 5.3 Diagnostics: the reader that actually wedges the lock

Queries are short reads; the long-running reader that collides with the sync's
read→write upgrade is **daemon highlighting** (per-file diagnostics). Three layers keep it
off the lock during the sync window:

- **`DiagnosticsService.launchDiagnostics`** fast-skips via
  `isImportInProgressOrWritePending` before entering `invokeWithPsiFileInReadAction`.
- **`DiagnosticsTask.run`** calls `waitForProjectStability(project, 60_000)`; if the
  project is still busy after a minute it skips with a warning (a later
  `didOpen`/`didChange`/`didSave` re-triggers diagnostics). This prevents highlighting from
  *starting* during the sync, including the model-fetch phase where no write is pending yet.
- **`DiagnosticsTask.doHighlighting`** runs a **watchdog** (scheduled, 500 ms) that cancels
  the `DaemonProgressIndicator` if the project becomes busy *mid-highlight*, so a re-sync
  (auto-import triggered by VCS/file changes) that starts after the task passed its gates
  aborts `runMainPasses` instead of wedging it. A self-cancelling custom indicator is not
  possible here: `HighlightingSessionImpl` **asserts** the running indicator is a
  `DaemonProgressIndicator` (`GlobalInspectionContextBase.assertUnderDaemonProgress`).

### 5.4 Why this resolves the failure

- The client-visible symptom (timeout after a hang) becomes a fast, clear error response
  the client can retry — exactly the requested behavior:
  ```
  Project is still importing/syncing its build system (Gradle/Maven) or a write action is
  pending: <root>. Request rejected instead of waiting on the read/write lock; retry once
  the import finishes.
  ```
- No request or diagnostics thread is parked at `acquireReadPermit` during the sync window,
  so the blocked-thread pile-up that exacerbated the wedge is prevented.
- Once the Gradle sync finishes (`onProjectLoadedActionCompleted` fires), the gate opens and
  queries and diagnostics proceed normally — matching the previously-observed "warm session"
  success.

## 6. Verification

1. `bash scripts/shell/install-plugin.sh` (rebuilds, restarts the service).
2. Cold-start stress (restart, query immediately):
   - T+5s during the sync window -> clean JSON-RPC error, no timeout:
     `{'code': -32603, 'message': 'Project is still importing/syncing its build system …'}`.
   - T+40s after sync settles -> `3. Document symbols: OK - Found 1 symbols`.
   - `jcmd <pid> Thread.print | grep -c acquireReadPermit` -> `0` (no wedge).
   - 3/3 consecutive cold-start cycles: OK at T+6s and T+45s, 0 blocked readers.
3. Regression batch `--tests 3,7,8,34` -> all pass (1 symbol / 571 completions / hover /
   no signatures); `--tests 12,13,14,17,35,37` -> all pass (2 diagnostics, organize-imports,
   call hierarchy, formatting 377 edits, rename 15 changes). `--tests 43` (extract-method)
   passes.
4. **Pre-existing, unrelated failures** (not caused by this fix): `--tests 44`
   (introduce-variable) and `--tests 45` (inline) fail with
   `IncorrectOperationException: Must not change PSI outside command or undo-transparent
   action` in `RefactoringHandler.applyIntroduceVariable` — the refactor commands mutate PSI
   outside a `WriteCommandAction`, a separate defect in the refactoring implementation.

## 7. Out of scope / follow-ups

- The Gradle/workspace-model read→write upgrade deadlock is an IntelliJ 2026.1 internal
  behavior; the gate works around it server-side. Consider a retry loop on the client
  (test harness / xlsp CLI) so a clear "import in progress" error is retried a few times
  before failing.
- Residual risk: a re-sync (auto-import) that starts *after* the stability gate cleared and
  races the diagnostics watchdog's 500 ms poll. The watchdog makes the window
  self-healing (cancels highlighting) rather than a permanent wedge; widening the poll to a
  self-abort on write-intent is possible but IntelliJ's lock API does not expose the
  upgrade directly.
- Introduce-variable / inline refactoring (`RefactoringHandler`) still fail because they
  mutate PSI outside a write command — tracked as a separate fix.
- The Git/VCS background refresh after the `origin-new` force-push was an aggravating
  factor; it is external to this fix.
