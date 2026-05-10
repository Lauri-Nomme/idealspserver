# PRD: Apply Edit — Server-Side File Modification via LSP

## Status

**Draft** — viability research complete, design revised

---

## 1. Problem

AI agents can **diagnose** problems and **propose** fixes, but cannot **apply** them without leaving the LSP workflow:

```
Agent workflow today (broken loop):
1. xlsp diagnostics → "Cannot resolve symbol 'Foo' at line 42"
2. xlsp code-action → [CodeAction{title: "Add import", data: {uri, range}}]
3. Agent manually edits file → uses edit tool with string match
4. Agent must read file, find line 42, apply edit manually
```

**What's missing**: After `codeAction()`, the agent needs a way to tell the server "apply this fix". Currently, there's no LSP method to do this.

## 2. Viability Research

### 2.1 Existing Apply Logic — Already in Codebase ✅

`CodeActionService.applyCodeAction()` (lines 197-341) already implements the full apply logic:

```java
// 1. Find the action by title (reflection-based lookup)
actionFound = Stream.of(quickFixes, errorFixes, ...)
    .filter(it -> codeAction.getTitle().equals(tryGetText(it)))
    .findFirst();

// 2. Invoke the action on real PSI
invokeMethod.invoke(actionFound, project, editor, psiFile);

// 3. Re-run diagnostics
diagnostics().haltDiagnostics(path);
diagnostics().launchDiagnostics(path);
```

This means the server **already knows how to apply code actions**. The missing piece is a clean LSP endpoint to expose this.

### 2.2 Key Insight

The copy-diff-restore pattern in `applyCodeAction()` was for extracting the `WorkspaceEdit` for the LSP response. If the goal is **just apply and report success** (not return the edit), the code is even simpler:

```java
// Direct apply — no copy, no diff, no restore
actionFound.invoke(project, editor, psiFile);  // ← on real PSI, immediately
diagnostics().haltDiagnostics(path);
diagnostics().launchDiagnostics(path);
return new ApplyWorkspaceEditResponse(true, null);
```

### 2.3 Effort Estimate

| Task | Effort | Notes |
|------|--------|-------|
| `codeAction/apply` custom LSP method | 2-3h | Reuses existing `applyCodeAction()` logic, stripped of copy-diff-restore |
| Registration in `IdeaLspServer` | 0.5h | `@JsonRequest("idealsp/codeActionApply")` |
| xlsp CLI `apply` operation | 1h | Accept `{title, uri, range}`, call server, return result |
| Tests | 2h | Unit + Python integration |
| **Total** | **~6-8h** | |

## 3. Design

### 3.1 Recommended: `codeAction/apply` Custom Method

A dedicated custom LSP method specifically for applying code actions:

```
idealsp/codeActionApply (custom)
  Params: {
    title: string,       // action title to match (from codeAction response)
    uri: string,         // file URI (from codeAction data)
    range: Range         // range (from codeAction data)
  }
  Result: ApplyWorkspaceEditResponse {
    applied: boolean
    failureReason?: string
  }
```

**Why custom, not standard?** Standard LSP has no "apply this code action" method. The closest is `workspace/applyEdit` which expects a `WorkspaceEdit`, not an action identifier. A custom method is the right tool for the job.

### 3.2 Server Implementation

```java
// IdeaLspServer.java
@JsonRequest("idealsp/codeActionApply")
public CompletableFuture<ApplyWorkspaceEditResponse> codeActionApply(
        CodeActionApplyParams params) {
    var project = session.getProject();
    if (project == null) {
        return CompletableFuture.completedFuture(
            new ApplyWorkspaceEditResponse(false, "project not initialized"));
    }
    return new CodeActionApplyCommand(params.getTitle(), params.getUri(), params.getRange())
        .runAsync(project, null);
}

// CodeActionApplyParams.java
public record CodeActionApplyParams(
    @NotNull String title,
    @NotNull String uri,
    @NotNull Range range
) {}

// CodeActionApplyCommand.java
public class CodeActionApplyCommand extends LspCommand<ApplyWorkspaceEditResponse> {
    private final String title;
    private final LspPath path;
    private final Range range;

    @Override
    protected ApplyWorkspaceEditResponse execute(@NotNull ExecutorContext ctx) {
        var result = new Ref<ApplyWorkspaceEditResponse>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            MiscUtil.invokeWithPsiFileInReadAction(project, path, (psiFile) -> {
                var disposable = Disposer.newDisposable();
                try {
                    EditorUtil.withEditor(disposable, psiFile, range.getStart(), (editor) -> {
                        // 1. Get all available actions at this position (same as getCodeActions)
                        var actionInfo = ShowIntentionsPass.getActionsToShow(editor, psiFile, true);
                        var errorFixes = actionInfo.errorFixesToShow;
                        var inspectionFixes = actionInfo.inspectionFixesToShow;
                        var intentions = actionInfo.intentionsToShow;

                        // 2. Force-init lazy descriptors
                        Stream.of(errorFixes, inspectionFixes, intentions)
                            .flatMap(Collection::stream)
                            .forEach(d -> tryInitDescriptor(d, project, editor, psiFile));

                        // 3. Find matching action by title
                        var actionFound = Stream.of(errorFixes, inspectionFixes, intentions)
                            .flatMap(Collection::stream)
                            .map(it -> tryGetAction(it))
                            .filter(it -> title.equals(tryGetText(it)))
                            .findFirst()
                            .orElse(null);

                        if (actionFound == null) {
                            result.set(new ApplyWorkspaceEditResponse(false,
                                "No action found with title: " + title));
                            return;
                        }

                        // 4. Apply — directly on real PSI, no copy, no diff
                        CommandProcessor.getInstance().executeCommand(project, () -> {
                            try {
                                actionFound.invoke(project, editor, psiFile);
                            } catch (Exception e) {
                                result.set(new ApplyWorkspaceEditResponse(false,
                                    "Action invoke failed: " + e.getMessage()));
                                return;
                            }
                        }, title, null);

                        result.set(new ApplyWorkspaceEditResponse(true, null));
                    });
                } finally {
                    Disposer.dispose(disposable);
                }
            });
        });

        // 5. Refresh diagnostics
        diagnostics().haltDiagnostics(path);
        diagnostics().launchDiagnostics(path);

        return Optional.ofNullable(result.get())
            .orElse(new ApplyWorkspaceEditResponse(false, "Internal error"));
    }
}
```

### 3.3 xlsp CLI Integration

```bash
# Apply a code action by title
xlsp apply "Add import 'foo.Foo'" in Foo.java --line 42

# Or pass the full code action response (extracts title, uri, range)
xlsp apply '<code-action-json>'
```

Output:
```json
{"success": true, "applied": true, "operation": "apply"}
```

Error:
```json
{"success": false, "error": "No action found with title: Add import 'foo.Foo'"}
```

### 3.4 Full Agent Workflow

```
1. xlsp diagnostics --file Foo.java
   → {severity: "error", message: "Cannot resolve symbol 'Foo'", line: 42}

2. xlsp code-action --file Foo.java --line 42
   → [{title: "Add import 'foo.Foo'", kind: "quickfix", data: {uri, range}}]

3. xlsp apply "Add import 'foo.Foo'" in Foo.java --line 42
   → {applied: true}  // server finds action by title and invokes it directly

4. xlsp diagnostics --file Foo.java
   → (error resolved)
```

### 3.5 Comparison: Approaches for Applying Code Actions

| Aspect | workspace/applyEdit | codeAction/resolve + applyEdit | **codeAction/apply** |
|--------|--------------------|-------------------------------|----------------------|
| Edit format | Generic `WorkspaceEdit` | Generic `WorkspaceEdit` | Action identifier |
| Complexity | High (TextEdit parsing) | High (copy-diff-extract-apply) | **Low (direct invoke)** |
| Multi-file | ✅ Yes | ✅ Yes | ✅ Yes (action handles it) |
| PSI-correct | ✅ Yes | ✅ Yes | **✅ Yes (direct on real PSI)** |
| Preview before apply | ✅ Yes | ✅ Yes | ❌ No (agent must trust title) |
| No resolve round-trip | ❌ No | ❌ No | **✅ Yes** |
| Works for non-code-action edits | ✅ Yes | ✅ Yes | ❌ No |
| Offset calculation needed | Yes | Yes | **No** |

**Decision**: Use **`codeAction/apply`** as the primary mechanism. Simpler, lower effort, more powerful for the specific use case.

## 4. Relationship to `workspace/applyEdit`

### 4.1 When `workspace/applyEdit` Would Be Needed

`codeAction/apply` is specific to code actions. `workspace/applyEdit` would be needed for:

- **Semantic search + replace** (future SSR replace) — produces a `WorkspaceEdit`, not a code action
- **Rename refactoring** — `textDocument/rename` returns `WorkspaceEdit`
- **Formatting** — `textDocument/formatting` returns `TextEdit[]`
- **Generic file modifications** from external tools

### 4.2 Architecture Decision

```
PRD Phase 1 (this document):
  codeAction/apply  →  apply code actions only, simple and powerful

Future PRD (separate):
  workspace/applyEdit  →  generic WorkspaceEdit application
    - TextEdit parsing + offset calculation
    - ApplyEditCommand with WriteCommandAction
    - Multi-file edit support
    - Composable with rename, formatting, semantic replace
```

This PRD covers only `codeAction/apply`. `workspace/applyEdit` is a future extension.

## 5. Eager CodeAction: Include Edit in Initial Response

### 5.1 Current State: Two Round-Trips

```
1. codeAction() → [CodeAction{title, data: {uri, range}}]     ← NO edit
2. codeAction/resolve() → CodeAction{edit: WorkspaceEdit}  ← edit extracted here
```

### 5.2 Option: Eager CodeAction (include edit in initial response)

```
codeAction() → [CodeAction{title, data: {uri, range}, edit: WorkspaceEdit}]
                                                        ↑ edit in first response
```

**How**: In `CodeActionService.getCodeActions()`, for each action:
1. Copy the PSI file
2. Run action on copy
3. Diff copy vs original → `WorkspaceEdit`
4. Restore original (no side effects)
5. Set `CodeAction.edit = WorkspaceEdit`

**Pros**: Agent sees the edit in one call. `codeAction/apply` becomes optional.

**Cons**: Each action runs on copy → may differ from real file state (see 5.3).

**Risk**: The copy might not reflect unsaved client changes. Mitigation: server manages its own document state via `ManagedDocuments`.

### 5.3 Recommendation

**Add eager CodeAction as an enhancement** but keep `codeAction/apply` for:
1. Agents that want explicit control ("apply this specific action")
2. Cases where the agent trusts the action title and just wants it applied
3. Fallback when eager CodeAction returns no edit (action not available at position)

The workflow becomes:
```
codeAction() → Agent sees [title, edit{changes}]  ← eager
codeAction/apply "Add import"  → applied          ← explicit apply
```

### 5.4 Eager CodeAction Effort

| Task | Effort | Notes |
|------|--------|-------|
| Extract `applyCodeAction()` logic into reusable method | 1h | Already exists, needs extraction |
| Run on copy in `getCodeActions()`, return edit | 2h | Per-action copy-diff-restore |
| Tests | 1h | |
| **Total** | **~4h** | |

## 6. Acceptance Criteria

1. **`idealsp/codeActionApply`**: Send `{title, uri, range}` → server applies action, returns `{applied: true/false}`
2. **Action matching by title**: Server finds the correct action among all available at that position
3. **Direct PSI invocation**: Action runs on real PSI (no copy), all PSI-aware fixes work correctly
4. **Diagnostic refresh**: After apply, diagnostics halt and re-launch for affected file(s)
5. **Error reporting**: Action not found → `failureReason` with message; partial failure → details
6. **Undo works**: IntelliJ's undo stack (Ctrl+Z) shows the edit with the action title
7. **xlsp integration**: `xlsp apply "<title>" in <file> --line N` → applied result
8. **Eager CodeAction** (enhancement): `codeAction()` returns `CodeAction{edit}` in first response
9. **No manual editing**: Full diagnose → apply → verify loop works without the agent touching file coordinates

## 7. Implementation Order

| Phase | Task | Dependencies |
|-------|------|---------------|
| 1 | `CodeActionApplyCommand.java` — direct invoke on real PSI | None |
| 2 | Register `idealsp/codeActionApply` in `IdeaLspServer` | Phase 1 |
| 3 | xlsp CLI `apply` operation | Phase 2 |
| 4 | Python integration test | Phase 3 |
| 5 | **Eager CodeAction**: extract apply logic, run on copy in `getCodeActions()` | Phase 2 |
| 6 | Chain test: diagnostics → code-action → apply → diagnostics | Phase 4 |
| Future | `workspace/applyEdit` — generic WorkspaceEdit application for rename, semantic replace, etc. | Separate PRD |
