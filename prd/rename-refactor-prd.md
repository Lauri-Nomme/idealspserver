# PRD: Rename & Refactoring for xlsp + IdeaLS

## 1. Problem Statement

The competitive analysis (Section 4.2) identified **refactoring** as the single biggest gap: xlsp scores 0/4 on refactoring capability, while competitors like MCP Steroid and Serena JB support rename, extract method, introduce variable, inline, safe delete, and move.

The server (`IdeaLS`) already has a `RenameCommand` wired via `textDocument/rename`, but:
- **No `prepareRename`** — clients can't validate symbol before rename
- **No xlsp CLI `rename` command** — the agent has no structured access to rename
- **No refactoring commands** — extract method, introduce variable, inline don't exist

## 2. Goals

| Capability | Priority | Server | xlsp CLI |
|-----------|----------|--------|----------|
| `textDocument/prepareRename` | P0 | NEW | NEW (`prepare-rename`) |
| `textDocument/rename` | P0 | EXISTS | NEW (`rename`) |
| Extract method | P0 | NEW | NEW (`refactor extract-method`) |
| Introduce variable | P1 | NEW | NEW (`refactor introduce-variable`) |
| Inline | P1 | NEW | NEW (`refactor inline`) |

## 3. Architecture

```
LLM / Agent
  │
  ├── bash: xlsp rename <symbol> to <newName> in <file>
  │     └── LSP: textDocument/rename → WorkspaceEdit
  │
  ├── bash: xlsp prepare-rename <symbol> in <file>
  │     └── LSP: textDocument/prepareRename → Range
  │
  └── bash: xlsp refactor <type> <args> in <file>
        └── LSP: idealsp/refactor → ApplyWorkspaceEditResponse
```

All refactoring operations are **applied server-side** (like `idealsp/codeActionApply`), not returned as edits. This matches how IntelliJ refactoring works — it handles cross-file consistency, undo, and write lock management.

## 4. Server Implementation Plan

### 4.1 `textDocument/prepareRename`

**File:** `server/src/main/java/tf/locals/idealsp/server/rename/PrepareRenameCommand.java`

- Extends `LspCommand<PrepareRenameResult>`
- Uses `TargetElementUtil.findTargetElement()` + `RenamePsiElementProcessor` (same as `RenameCommand`)
- Returns the element's range and a placeholder string (the current name)
- On failure (no element found), returns null

**Wiring:**
- `MyTextDocumentService.prepareRename()` → `PrepareRenameCommand`
- `LspServer.defaultServerCapabilities()` → enable `RenameOptions.prepareProvider`

### 4.2 Refactoring — Custom LSP Methods

**New package:** `server/src/main/java/tf/locals/idealsp/server/refactoring/`

**Files:**
- `RefactorParams.java` — operation type + parameters (file URI, range/position, args)
- `RefactorCommand.java` — dispatches to IntelliJ refactoring handlers
- `RefactorResult.java` — operation type + status

**Protocol:** Custom LSP method `idealsp/refactor`

| Operation | Params | IntelliJ API |
|-----------|--------|-------------|
| `extract-method` | range, name? | `ExtractMethodHandler` |
| `introduce-variable` | position, name? | `IntroduceVariableHandler` |
| `inline` | position | `InlineHandler` |

**Wiring:**
- `IdeaLspServer` interface → `@JsonRequest("idealsp/refactor") RefactorResult refactor(RefactorParams)`
- `LspServer.refactor()` → `RefactorCommand`

## 5. xlsp Client Implementation Plan

### 5.1 `rename` Operation

**File:** `tools/xlsp/operations/rename.ts`

```
xlsp rename <symbol> to <newName> in <file>
```

Flow:
1. Resolve symbol → position (via `resolveSymbolPosition`)
2. Call `textDocument/rename` with position + newName
3. Parse `WorkspaceEdit` → flat list of edits
4. Output: one JSON line per edit `{ file, line, character, endLine, endCharacter, newText }`

### 5.2 `prepare-rename` Operation

```
xlsp prepare-rename <symbol> in <file>
```

Flow:
1. Resolve symbol → position
2. Call `textDocument/prepareRename`
3. Output: `{ range: { start, end }, placeholder }`

### 5.3 `refactor` Operation

```
xlsp refactor <type> [args] in <file>
```

Where type is one of: `extract-method`, `introduce-variable`, `inline`

Flow:
1. Parse type + type-specific args
2. Call `idealsp/refactor` with params
3. Output: `{ applied, operation, ... }`

## 6. Implementation Order

```
[Phase 1] Server: prepareRename         → rebuild, test
[Phase 2] Client: rename operation       → rebuild, test
[Phase 3] Server: refactoring handlers   → rebuild, test
[Phase 4] Client: refactor operation     → rebuild, test
[Phase 5] Integration test               → verify end-to-end
```

## 7. Testing

### Unit Tests (Java)
- `PrepareRenameCommandTest` — verify range + placeholder
- `RefactorCommandTest` — verify extract-method, introduce-variable, inline

### Integration Tests (Python)
- Add rename test to `test_lsp_comprehensive.py`
- Add refactoring test cases

### Manual Verification
```
# Rename a variable
xlsp rename "count" to "total" in "src/Main.java"

# Prepare rename (validate + get range)
xlsp prepare-rename "count" in "src/Main.java"

# Extract method
xlsp refactor extract-method "computeTotal" in "src/Main.java"
```
