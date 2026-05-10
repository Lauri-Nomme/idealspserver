# PRD: Apply Edit — Server-Side File Modification via LSP

## Status

**Draft** — viability research complete

---

## 1. Problem

AI agents can **diagnose** problems and **propose** fixes, but cannot **apply** them without leaving the LSP workflow:

```
Agent workflow today (broken loop):
1. xlsp diagnostics → "Cannot resolve symbol 'Foo' at line 42"
2. xlsp code-action → "{ edits: [{range: {line:42}, newText: 'import foo.Foo;'}] }"
3. Agent manually edits file → uses edit tool with string match
4. Agent must read file, find line 42, apply edit manually
```

**What's missing**: The server can produce a `WorkspaceEdit` but the agent can't send it back. The LSP `workspace/applyEdit` method exists in the spec but isn't implemented.

## 2. Viability Research

### 2.1 Building Blocks — All Present ✅

| Component | Status | Location |
|-----------|--------|----------|
| `WriteCommandAction` | ✅ Used in `DocumentSymbolService.java:82` | Already in codebase |
| `PsiDocumentManager` | ✅ Available via IntelliJ API | Standard IntelliJ |
| `Document` (editing) | ✅ Used in `DiagnosticsTask`, `MiscUtil` | Standard IntelliJ |
| `TextEdit` → text conversion | ✅ `TestUtil.applyEdits()` (lines 88-147) | `TestUtil.java` |
| Incremental sync (didChange) | ✅ Already configured in `LspServer.java` | `TextDocumentSyncKind.Incremental` |
| `workspace/applyEdit` types | ✅ LSP4J has all classes | Standard LSP4J |
| `DocumentFormattingParams` / `FormattingCommand` | ✅ `MyTextDocumentService.java:236` | Already implemented |

### 2.2 Key APIs

**WriteCommandAction pattern** (already used in codebase):
```java
WriteCommandAction.runWriteCommandAction(project, null, null, () -> {
    // edit document here
    document.replaceString(startOffset, endOffset, newText);
});
```

**TestUtil.applyEdits()** — already converts `TextEdit[]` to string (reference implementation):
```java
// TestUtil.java:88 — assumes edits don't overlap
public static @NotNull String applyEdits(@NotNull String originalText, @NotNull Collection<TextEdit> edits) {
    // 1. Sort edits by line/character
    // 2. Convert LSP positions to offsets
    // 3. Apply from end to start (to preserve offsets)
    // 4. Return modified text
}
```

**DocumentSync already configured**:
```java
// LspServer.java:141-145
syncOptions.setChange(TextDocumentSyncKind.Incremental);  // already on
```

### 2.3 What Needs to Be Built

Minimal implementation:

```
LspServer.java (WorkspaceService):
  + applyEdit(ApplyWorkspaceEditParams) → CompletableFuture<ApplyWorkspaceEditResponse>

ApplyEditCommand.java (NEW):
  - Takes WorkspaceEdit
  - For each file URI → edits mapping:
    - Resolve PsiFile
    - Get Document
    - Apply TextEdits via WriteCommandAction
  - Return success/failure per file

MyTextDocumentService.java:
  + applyEdit() delegating to ApplyEditCommand
```

### 2.4 Effort Estimate

| Task | Effort | Notes |
|------|--------|-------|
| `ApplyEditCommand.java` | 2-3h | Core logic: resolve file → get document → apply edits |
| `LspServer` workspace service registration | 1h | Wire in applyEdit endpoint |
| `MyTextDocumentService.applyEdit()` | 0.5h | Delegate pattern |
| xlsp CLI `apply` operation | 1h | Accept JSON edits, send to server |
| Tests | 2h | Unit tests + Python integration test |
| **Total** | **~8-10h** | |

### 2.5 Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Concurrent edit conflicts | Low | IntelliJ's write action system serializes; edits to same file queue naturally |
| File not open in server | Low | `didOpen` manages documents; file may be open by client or by server's own file access |
| Large edit causing issues | Low | TextEdits are PSI-level; no parsing needed |
| Test coverage of edit correctness | Medium | Compare expected vs actual file content; use existing `TestUtil.applyEdits()` as oracle |

### 2.6 Alternative Approaches Considered

| Approach | Why Not |
|----------|---------|
| **Agent edits via `bash` / `sed`** | No PSI awareness, breaks formatting, no undo support |
| **`textDocument/didChange` (client-initiated)** | Client would need to manage file content; server loses control; duplicate document tracking |
| **Custom HTTP endpoint** | Outside LSP spec; breaks protocol layering |
| **Execute command (`workspace/executeCommand`)** | Intended for named commands; `applyEdit` is the semantically correct method |

**Decision**: Use standard `workspace/applyEdit` — it's the correct LSP method, LSP4J provides all types, and IntelliJ provides all editing primitives.

## 3. Design

### 3.1 LSP Protocol

```
workspace/applyEdit (LSP 3.0)
  Params: ApplyWorkspaceEditParams {
    edit: WorkspaceEdit {
      changes?: { [uri]: TextEdit[] }      // file URI → edits (incremental)
      documentChanges?: (TextEdit | CreateFile | RenameFile | DeleteFile)[]  // full
    },
    label?: string                         // "Add import" for undo
  }
  Result: ApplyWorkspaceEditResponse {
    applied: boolean
    failureReason?: string
  }
```

### 3.2 Server-Side Implementation

```java
// MyTextDocumentService.java
@Override
public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(
        ApplyWorkspaceEditParams params) {
    var project = session.getProject();
    if (project == null) {
        return CompletableFuture.completedFuture(
            new ApplyWorkspaceEditResponse(false, "project not initialized"));
    }
    return new ApplyEditCommand(params.getEdit())
        .runAsync(project, null);
}

// ApplyEditCommand.java (extends LspCommand<ApplyWorkspaceEditResponse>)
public class ApplyEditCommand extends LspCommand<ApplyWorkspaceEditResponse> {
    private final WorkspaceEdit edit;

    @Override
    protected ApplyWorkspaceEditResponse execute(@NotNull ExecutorContext ctx) {
        var failures = new ArrayList<String>();

        // 1. Handle documentChanges (full document replace / create / rename / delete)
        if (edit.getDocumentChanges() != null) {
            for (var change : edit.getDocumentChanges()) {
                if (change instanceof TextEdit docChange) {
                    applyTextEdit(docChange);
                }
                // CreateFile, RenameFile, DeleteFile handled separately
            }
        }

        // 2. Handle changes (incremental per-file edits)
        if (edit.getChanges() != null) {
            for (var entry : edit.getChanges().entrySet()) {
                var uri = entry.getKey();
                var edits = entry.getValue();
                applyEditsToFile(uri, edits);
            }
        }

        return new ApplyWorkspaceEditResponse(failures.isEmpty(), failures.isEmpty() ? null : String.join("; ", failures));
    }

    private void applyEditsToFile(String uri, List<TextEdit> edits) {
        var path = LspPath.fromLspUri(uri);
        MiscUtil.produceWithPsiFileInReadAction(project, path, psiFile -> {
            var document = MiscUtil.getDocument(psiFile);
            if (document == null) return null;

            ApplicationManager.getApplication().runWriteAction(() -> {
                // Sort edits: later offsets first (apply end-to-start)
                var sortedEdits = edits.stream()
                    .sorted(byOffsetDescending(document))
                    .toList();

                for (var edit : sortedEdits) {
                    var start = positionToOffset(document, edit.getRange().getStart());
                    var end = positionToOffset(document, edit.getRange().getEnd());
                    document.replaceString(start, end, edit.getNewText());
                }
            });
            return null;
        });
    }
}
```

### 3.3 CodeAction → Edit: Current State vs Options

#### Current behavior: two-round-trip

```
Client                    Server
  |                           |
  |-- codeAction() ------------→|
  |    (uri, range)            | getCodeActions() → CodeAction{title, data{uri,range}}
  |←- [CodeAction{title}] -----|  NO edit field
  |                           |
  |-- codeAction/resolve() ----→|
  |    (unresolved CodeAction)  | applyCodeAction():
  |                            |   1. psiFile.copy() → oldCopy
  |                            |   2. action.invoke() on real psiFile
  |                            |   3. diff oldCopy vs newCopy → TextEdit[]
  |                            |   4. restore original (setText back)
  |                            |   5. return WorkspaceEdit
  |←- CodeAction{edit} --------|  edit set in resolve response
```

**Problem**: Agent needs two calls and must parse `data` → construct resolve request → parse edit → apply.

#### Option A: Eager CodeAction (recommended for applyEdit integration)

```
|-- codeAction() ------------→|
  |    (uri, range)            | getCodeActions():
  |                            |   for each action:
  |                            |     psiFile.copy() → oldCopy
  |                            |     action.invoke() on copy  ← runs on PSI copy
  |                            |     diff → WorkspaceEdit
  |                            |     restore original (no side effects)
  |                            |     CodeAction{title, edit, data}
  |←- [CodeAction{title,edit}]|
```

**Pros**: Agent gets edit in one call, can immediately applyEdit. Safe — copy-diff-restore means no permanent changes.

**Cons**: Each action runs twice (once in list, once in resolve if client calls it). Slower response for large action lists. Action that has side effects (writes external state) could behave differently on copy vs original.

**Risk assessment**: The copy-diff-restore pattern (`CodeActionService:214, 326-329`) is already used in `applyCodeAction()`. It works because:
1. Action runs on real PSI (all PSI-aware fixes work)
2. File is restored after (`newDoc.setText(oldDoc.getText())`)
3. Diff is extracted from the side effects that occurred during the action

The risk is that some actions might rely on actual file state. E.g., "Optimize imports" on a copy sees different imports than on the real file. For most quick-fixes (add import, add semicolon), this is fine.

#### Option B: Direct Apply (apply code action on server without extract-then-apply)

Instead of `codeAction → extract WorkspaceEdit → applyEdit`, skip the extract step:

```
|-- applyCodeAction() --------→|
  |    (CodeAction{title,data}) | applyCodeAction():
  |                            |   action.invoke() on REAL psiFile  ← applies immediately
  |                            |   (no copy, no restore)
  |                            |   halt diagnostics, re-launch
  |←- {applied: true} ---------|  return success
```

**Pros**: Single call. No diff computation. No copy overhead.

**Cons**:
1. **Irreversible from client perspective**: Agent can't preview the edit before it's applied. LSP spec says client should apply edits from `WorkspaceEdit` — this inverts that contract.
2. **No edit to share with client**: If client needs the edit for its own state (buffer sync), it's not returned.
3. **Error handling is harder**: If the action partially fails, the file is already modified. With extract-then-apply, you can retry.
4. **Undo stack**: IntelliJ's undo shows the edit correctly, but the LSP client has no knowledge of it.

**Viability**: Technically works but breaks the LSP edit contract. The LSP design assumes:
- Server returns `WorkspaceEdit` to client
- Client applies edits (knows buffer state, can sync)
- Client reports `applied: true/false`

The server applying directly bypasses this. It's more like `workspace/executeCommand` than `workspace/applyEdit`.

#### Option C: ExecuteCommand for code actions (bypass applyEdit entirely)

Register code action titles as named commands:

```
|-- executeCommand() ----------→|
  |    (command: "idealsp.applyQuickFix", arguments: [title, uri, range])
  |                            | action.invoke() on real psiFile
  |                            |   halt diagnostics, re-launch
  |←- {applied: true} ---------|  return success
```

**Pros**: No new LSP method needed. `executeCommand` already exists in capability.

**Cons**:
1. Commands must be registered in `ExecuteCommandOptions` upfront — dynamic registration of code-action names is complex
2. Client doesn't know which commands exist (no discovery)
3. Same issue as Option B — no preview, no edit to share

### 3.4 Recommendation

**Option A (eager CodeAction) + Option B (applyEdit) together:**

1. Make `getCodeActions()` return `CodeAction{edit: WorkspaceEdit}` directly (eager). No resolve round-trip needed. The agent gets the edit immediately.

2. Implement `workspace/applyEdit` as a **generic edit application** mechanism that:
   - Applies any `WorkspaceEdit` (from code-action, rename, or future semantic-replace)
   - Provides an explicit, auditable apply mechanism
   - Allows the agent to preview the edit before applying (see edit in JSON, then decide to apply)
   - Enables error recovery (applyEdit can fail, agent retries)

**Why both?**

- **Option B alone** (direct apply without applyEdit) means: `codeAction → server applies immediately → done`. No preview, no explicit apply step.
- **Option A + applyEdit** means: `codeAction → see the exact edits → decide to apply → applyEdit`. More steps, but agent has full visibility and control.

For an AI agent, **Option A + applyEdit is better** because:
1. Agent can examine the edit before applying ("This will add 3 imports and change 2 lines")
2. Agent can apply multiple edits together (`xlsp apply <combined-edit>`)
3. Error recovery works (if applyEdit fails, retry the codeAction)
4. Composable: `xlsp semantic search → applyEdit` doesn't need codeAction at all

**Implementation notes:**

The eager CodeAction approach has one subtle risk: **the copy might not reflect the real file state** at action time. E.g., if the client has unsaved changes (via `didChange`) that IntelliJ's PSI hasn't seen yet, the action on the copy runs on stale PSI.

Mitigation: The server already manages its own document state via `ManagedDocuments`. The copy is from the server's PSI view, which is consistent. `didChange` from the client updates the server's document first.

### 3.5 xlsp CLI Integration

```bash
# Apply workspace edit from JSON
xlsp apply '{"changes": {"file:///path/Foo.java": [{"range": {"start": {"line": 5, "character": 0}, "end": {"line": 5, "character": 0}}, "newText": "import foo.Foo;\n"}]}}'

# Apply with label (for undo history)
xlsp apply '<json>' --label "Add missing import"
```

```typescript
// .opencode/tools/xlsp.ts
case "apply": {
    const edits = JSON.parse(args.edits as string)
    // Pass to server via workspace/applyEdit
    break
}
```

### 3.4 Chain with code-action

The full agent workflow becomes:

```
1. xlsp diagnostics --file Foo.java
   → {severity: "error", message: "Cannot resolve symbol 'Foo'", line: 42}

2. xlsp code-action --file Foo.java --line 42
   → [{title: "Add import 'foo.Foo'", edit: WorkspaceEdit}]

3. xlsp apply '<workspace-edit-json>'
   → {applied: true}  // server applies edit directly

4. xlsp diagnostics --file Foo.java
   → (error resolved)  // no more error at line 42
```

### 3.5 Relationship to Other Features

| Feature | How it works with applyEdit |
|---------|---------------------------|
| **code-action** | Returns `WorkspaceEdit` → agent extracts and applies |
| **rename** | `textDocument/rename` returns `WorkspaceEdit` → same flow |
| **semantic search + replace** | Future SSR replace returns `WorkspaceEdit` → same flow |
| **formatting** | `documentFormatting` returns `TextEdit[]` → could use applyEdit too |

ApplyEdit is the **universal edit primitive** that unifies all edit-producing LSP operations.

## 4. Acceptance Criteria

1. **`workspace/applyEdit` accepts incremental edits**: Send `{changes: {uri: [TextEdit...]}}` → file modified
2. **Correct offset calculation**: Edits at line 5 character 10 replace exactly those characters
3. **Multiple edits per file**: All edits in the list are applied atomically (WriteCommandAction)
4. **Multiple files**: All files modified in one call
5. **Undo works**: IntelliJ's undo stack (Ctrl+Z) shows edit as single action with label
6. **Error reporting**: Applied partially → `failureReason` reports which files failed
7. **xlsp integration**: `xlsp apply <json>` → server applies, returns `{applied: true/false}`
8. **Chain with code-action**: Full diagnose → fix → verify loop works without manual editing
9. **Eager CodeAction**: `codeAction()` returns `CodeAction{edit: WorkspaceEdit}` directly (no resolve round-trip needed)
10. **Preview before apply**: Agent sees exact edit content before calling `applyEdit`

## 5. Implementation Order

| Phase | Task | Dependencies |
|-------|------|-------------|
| 1 | `ApplyEditCommand.java` — core logic | None |
| 2 | Register in `LspServer` workspace service | Phase 1 |
| 3 | Wire `applyEdit()` in `MyTextDocumentService` | Phase 2 |
| 4 | **Eager CodeAction**: make `getCodeActions()` return edit directly (copy-diff-restore per action) | Phase 3 |
| 5 | xlsp CLI `apply` operation | Phase 4 |
| 6 | Python integration test: applyEdit standalone | Phase 5 |
| 7 | Chain test: diagnostics → code-action (eager) → applyEdit → diagnostics | Phase 6 |
