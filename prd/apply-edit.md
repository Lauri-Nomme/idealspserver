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

### 3.3 xlsp CLI Integration

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

## 5. Implementation Order

| Phase | Task | Dependencies |
|-------|------|-------------|
| 1 | `ApplyEditCommand.java` — core logic | None |
| 2 | Register in `LspServer` workspace service | Phase 1 |
| 3 | Wire `applyEdit()` in `MyTextDocumentService` | Phase 2 |
| 4 | xlsp CLI `apply` operation | Phase 3 |
| 5 | Python integration test | Phase 4 |
| 6 | Chain test: diagnostics → code-action → apply → diagnostics | Phase 5 |
