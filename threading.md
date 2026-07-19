# IntelliJ Threading & Lock Model for IdeaLS

## Overview

IntelliJ Platform uses a custom read-write lock (`ReadAction` / `WriteAction`) layered on top of the AWT Event Dispatch Thread (EDT). This document explains how the locking works, what causes deadlocks, and how LSP commands should interact with the EDT and locks.

## Key Concepts

| Concept | Description |
|---------|-------------|
| **EDT** | AWT Event Dispatch Thread. All Swing UI operations must happen here. |
| **ReadAction** | Acquires the shared read lock. Multiple threads can hold it simultaneously. Blocks writes. |
| **WriteAction** | Acquires the exclusive write lock. Blocks all reads and other writes. |
| **WriteIntentReadAction** | A read action that can be upgraded to a write action. Used internally when `invokeAndWait` is called from a background thread — the runnable is wrapped in a write intent on the EDT, allowing inner `WriteAction.run()` to succeed. |
| **invokeAndWait** | Sends a runnable to the EDT and blocks the calling thread until it completes. On the background-thread path, checks `holdsReadLock()` and throws if the calling thread holds a read lock (to prevent deadlocks). On the EDT path, runs directly (no `LaterInvocator`). |
| **invokeAndWaitRelaxed** | Like `invokeAndWait` but `wrapWithLocks=false` — on the EDT path, runs the runnable directly without wrapping in a WriteIntentReadAction. |
| **holdsReadLock()** | Thread-local check via `ThreadingSupport.isReadLockedByThisThread()`. Returns true if the current thread holds a read action or is inside a write intent. |

## The `doInvokeAndWait` Decision Tree

From `ApplicationImpl.doInvokeAndWait()` (decompiled via CFR):

```
doInvokeAndWait(runnable, state, wrapWithLocks)
  │
  ├── EDT.isCurrentThreadEdt() == TRUE:
  │     ├── wrapWithLocks == TRUE  → runIntendedWriteActionOnCurrentThread(runnable)
  │     │                            (wraps in WriteIntentReadAction)
  │     └── wrapWithLocks == FALSE → runnable.run()  (direct, no lock upgrade)
  │     return
  │
  └── EDT.isCurrentThreadEdt() == FALSE:
        ├── holdsReadLock() == TRUE  → throw IllegalStateException("...deadlock.")
        └── holdsReadLock() == FALSE → LaterInvocator.invokeAndWait(state, wrapWithLocks, runnable)
```

## The Deadlock That Was Fixed

### Before fix: `EditorUtil.createEditor()` always called `invokeAndWait`

```
Thread: LSP AppExecutor (background)
  │
  ├── LspCommand.runAsync() → supplyAsync(getResult)
  │
  ├── getResult():
  │     invokeAndWait(() -> produceWithPsiFileInReadAction(...execute()...))
  │     │  └── doInvokeAndWait: not EDT, no read lock → LaterInvocator.invokeAndWait
  │     │       └── EDT runs: runIntendedWriteActionOnCurrentThread
  │     │            └── WriteIntentReadAction active
  │     │                 └── produceWithPsiFileInReadAction → runReadAction (nested, OK)
  │     │                      └── execute() runs inside read action on EDT
  │     │                           └── EditorUtil.createEditor()
  │     │                                └── invokeAndWait(createEditor)
  │     │                                     └── doInvokeAndWait: IS on EDT
  │     │                                          └── wrapWithLocks=true
  │     │                                               └── runIntendedWriteActionOnCurrentThread
  │     │                                                    └── WriteIntentReadAction (AGAIN!)
  │     │                                                         └── DEADLOCK: can't enter
  │     │                                                              write intent while
  │     │                                                              holding ReadAction
```

### Fix: EDT check in `EditorUtil.createEditor()`

```java
if (EDT.isCurrentThreadEdt()) {
    created = editorFactory.createEditor(doc, file.getProject());  // run directly
} else {
    // slower path: invokeAndWait for background threads
}
```

The fix breaks the chain: when already on the EDT (which is always the case when called from `LspCommand`), `createEditor()` runs directly instead of calling `invokeAndWait`. Since we're inside a `WriteIntentReadAction` on the EDT, calling `editorFactory.createEditor()` directly is safe — it just creates an editor object without needing additional locking.

## Rules for LSP Commands

1. **`LspCommand.execute()` always runs on the EDT, inside a `WriteIntentReadAction`** (with a nested `ReadAction` from `produceWithPsiFileInReadAction`).

2. **Do NOT call `invokeAndWait()` from `execute()`** — you're already on the EDT, use `EDT.isCurrentThreadEdt()` to check and run directly.

3. **Do NOT call `WriteAction.run()` directly** — the outer `WriteIntentReadAction` allows `WriteAction.run()` to upgrade, but only if IntelliJ's internal mechanisms handle it. For complex operations (refactoring, rename), use the existing processor APIs that manage locks internally.

4. **If you need to be on the EDT from a background thread and hold a read lock**, use `invokeAndWait` (it will transfer to EDT via `LaterInvocator`). But `EditorUtil.createEditor()` is the only place this is needed — avoid adding more.

5. **If you need to be on the EDT but don't need write intent** (read-only operations), consider using `invokeAndWaitRelaxed` with `wrapWithLocks=false` to avoid the `WriteIntentReadAction` overhead. But note that `invokeAndWaitRelaxed` still checks `holdsReadLock()` on the background-thread path.

6. **Never call `invokeAndWait` from a background thread that holds a read lock** — this throws `IllegalStateException` immediately. If you need to synchronize with the EDT while holding a read lock, restructure your code to release the read lock first, or use a mechanism that doesn't require `invokeAndWait`.

## How EditorUtil.createEditor() Works Now

```
LspCommand path (via invokeAndWait → EDT):
  EDT thread, inside WriteIntentReadAction + ReadAction
    → createEditor()
    → EDT.isCurrentThreadEdt() == TRUE
    → editorFactory.createEditor(doc, project)  // direct, no invokeAndWait
    → success

Background thread path (direct call, no read lock):
  Background thread, no locks held
    → createEditor()
    → EDT.isCurrentThreadEdt() == FALSE
    → invokeAndWait(createEditorTask)  // goes through LaterInvocator
    → EDT runs createEditorTask, background thread waits
    → success

Background thread path (inside ReadAction — ILLEGAL):
  Background thread, holds read lock
    → createEditor()
    → EDT.isCurrentThreadEdt() == FALSE
    → invokeAndWait(createEditorTask)
    → holdsReadLock() == TRUE
    → throws IllegalStateException
    → This path must never be reached — always ensure you're on EDT or
      not holding a read lock before calling createEditor.
```

## Relevant Decompiled Code Locations

| Class | Method | File |
|-------|--------|------|
| `ApplicationImpl` | `doInvokeAndWait` | `intellij.platform.ide.impl.jar` |
| `ApplicationImpl` | `invokeAndWait` | `intellij.platform.ide.impl.jar` |
| `ApplicationImpl` | `runIntendedWriteActionOnCurrentThread` | `intellij.platform.ide.impl.jar` |
| `ApplicationImpl` | `holdsReadLock` | `intellij.platform.ide.impl.jar` |
| `LaterInvocator` | `invokeAndWait` | `intellij.platform.ide.impl.jar` |
| `NestedLocksThreadingSupport` | `releaseTheAcquiredWriteIntentLockThenExecuteActionAndTakeWriteIntentLockBack` | `intellij.platform.locking.impl.jar` |

The class index is at `/tmp/class_index2.tsv` (format: `class_path\tjar_name`).
