# Implementation Plan: Fix All Broken Tests for IntelliJ 2026.1

## Proposal

**Next item to develop: Fix the remaining 25 failing unit tests.**

All LSP features (code actions, rename, diagnostics, signature help, document
highlight, formatting) are already fully implemented but broken on IntelliJ
2026.1 due to API changes. Rather than building new features on a broken
foundation, we should fix the existing ones first.

**Impact: 25 tests failing → 0. Takes test pass rate from 61% to ~100%.**

---

## Current Test Results: 39 pass, 25 fail, 1 skip (64 total)

### Passing (no changes needed)
- CompletionServiceTest (11) - already fixed
- BasicCompletionServiceTest (2)
- TextEditRearrangerTest (1)
- LspPathTest (4)
- OnTypeFormattingCommandTest (5)
- FindUsagesTest (1)
- FindUsagesCommandTest (1)
- DocumentHighlightCommandTest (1)
- RenameCommandJavaTest (4)
- SignatureHelpServiceTest (4)
- RenameTest (1)
- ServerInitializationTest (1)

---

## Root Causes and Fixes (5 groups, ordered by impact)

### Fix 1: FindDefinitionCommandBase — `findDefinitions()` never called (7 tests)

**Tests:** DefinitionCommandTest, DefinitionFromLibrarySourcesTest,
TypeDefinitionCommandTest, GotoDefinitionTest, GotoTypeDefinitionTest,
ReferencesTest (2 of 4)

**Root cause:** In `FindDefinitionCommandBase.execute()`, `targetElement` is
set to `originalElem` (the element at cursor in the **source** file), and the
`findDefinitions()` abstract method is never called. So both origin and target
resolve to the same element — the call site instead of the declaration site.

**File:** `server/src/main/java/org/rri/ideals/server/references/FindDefinitionCommandBase.java`

**Fix:** Replace the hardcoded `var targetElement = originalElem;` with actual
invocation of `findDefinitions(editor, offset)` to resolve target elements.

```java
// Before (broken):
var targetElement = originalElem;
return List.of(targetElement).stream().map(elem -> { ... });

// After (fixed):
return findDefinitions(editor, offset).map(targetElement -> { ... });
```

**Complexity:** Easy — one-line fix in the base class.

---

### Fix 2: Diagnostics — HighlightingSession not registered (4-7 tests)

**Tests:** DiagnosticsServiceTest (2), DiagnosticsTest (2), possibly
CodeActionServiceTest/CodeActionsTest (3) since code actions depend on
diagnostics for quick fixes

**Root cause:** IntelliJ 2026.1 requires a `HighlightingSession` to be
registered before calling `DaemonCodeAnalyzerEx.runMainPasses()`. Without it,
the highlighting pass throws `"No HighlightingSession stored in <id>"` which
is caught silently and returns 0 diagnostics.

**File:** `server/src/main/java/org/rri/ideals/server/diagnostics/DiagnosticsTask.java`

**Fix:** Before `runMainPasses()`, create and register a HighlightingSession:

```java
// Research needed: exact API for HighlightingSessionImpl in 2026.1
// Likely something like:
HighlightingSessionImpl.createHighlightingSession(
    psiFile, editor, daemonProgressIndicator, ...);
```

**Research steps:**
1. Derive `HighlightingSessionImpl` from 2026.1 JARs to find the factory
   method signature
2. Check what parameters it needs (EditorColorsScheme? ProperTextRange?)
3. Check if `DaemonCodeAnalyzerEx` API changed (new overloads?)

**Complexity:** Medium — need to research the new API, but fix is localized
to DiagnosticsTask.

---

### Fix 3: Code Actions — lazy IntentionAction initialization (3 tests)

**Tests:** CodeActionServiceTest (2), CodeActionsTest (1)

**Root cause:** IntelliJ 2026.1 lazily initializes `IntentionAction` instances.
`action.getText()` returns `"(not initialized) class <FQN>"` before
`isAvailable()` is called.

**File:** `server/src/main/java/org/rri/ideals/server/codeactions/CodeActionService.java`

**Fix:** Call `isAvailable(editor, psiFile)` on each IntentionAction before
reading its text:

```java
// Before:
var text = action.getText();

// After:
if (action.isAvailable(project, editor, psiFile)) {
    var text = action.getText();
    // ... use text
}
```

**Note:** This may partially depend on Fix 2 (diagnostics), since some code
actions come from diagnostic quick fixes. Fix diagnostics first.

**Complexity:** Easy — add `isAvailable()` call before `getText()`.

---

### Fix 4: DocumentSymbolService — write lock + modal progress (4 tests)

**Tests:** DocumentSymbolServiceTest (2), WorkspaceSymbolServiceTest (1),
SymbolTest (1)

**Root cause:** `DocumentSymbolService.getViewTreeElement()` calls
`TextEditorProvider.getInstance().createEditor()` inside a
`WriteCommandAction`. In 2026.1, `PsiAwareTextEditorProvider.createEditor()`
now triggers `AsyncEditorLoader.start()` → `runWithModalProgressBlocking()`.
Modal progress inside a write lock is now forbidden.

**File:** `server/src/main/java/org/rri/ideals/server/symbol/DocumentSymbolService.java`

**Fix:** Move the `createEditor()` call outside the write action:

```java
// Before:
WriteCommandAction.runWriteCommandAction(project, () -> {
    // ... file creation ...
    var editor = TextEditorProvider.getInstance().createEditor(project, file);
    // ... use editor ...
});

// After:
WriteCommandAction.runWriteCommandAction(project, () -> {
    // ... file creation only ...
});
var editor = TextEditorProvider.getInstance().createEditor(project, file);
// ... use editor ...
```

**Complexity:** Medium — need to understand which operations actually need
the write lock and restructure accordingly.

---

### Fix 5: Formatting + other test expectation updates (4+ tests)

**Tests:** FormattingTest (3), FormattingCommandTest (1),
CompletionTest (1), SignatureHelpTest (1), WorkspaceSymbolServiceTest (1)

**Sub-issues:**

**5a. FormattingTest (3 tests):** IntelliJ 2026.1 changed default formatting
rules. Expected text edits are hardcoded to old output. Need to run tests,
capture actual output, verify it's correct, update expected values.

**5b. FormattingCommandTest (1 test):** "No runnable methods" — test class
has wrong runner/annotations. Likely needs `@Test` annotation or base class
change similar to what we did for CompletionServiceTest
(`LightJavaCodeInsightFixtureTestCase`).

**5c. CompletionTest (1 test):** "item wasn't found" — completion item
name changed in 2026.1. Update expected item name.

**5d. SignatureHelpTest (1 test):** `PsiInvalidElementAccessException` — PSI
element accessed after invalidation. Threading/lifecycle issue, possibly
needs same `invokeAndWait` pattern used in completion fix.

**5e. WorkspaceSymbolServiceTest (1 test):** Symbol search results differ
from expected (ordering or content change in 2026.1).

**Complexity:** Mixed — easy for expectation updates, medium for
threading/PSI lifecycle fixes.

---

## Recommended Execution Order

```
Fix 1 (definition/references, 7 tests) — easy, highest test count
  ↓
Fix 2 (diagnostics, 4+ tests) — medium, unlocks Fix 3
  ↓
Fix 3 (code actions, 3 tests) — easy, depends on Fix 2
  ↓
Fix 4 (symbol services, 4 tests) — medium, independent
  ↓
Fix 5 (formatting/misc, 4+ tests) — mixed, independent
```

Total estimated: ~5 distinct changes across ~8 files.

---

## Files to Modify

| File | Fixes |
|------|-------|
| `references/FindDefinitionCommandBase.java` | Fix 1 |
| `diagnostics/DiagnosticsTask.java` | Fix 2 |
| `codeactions/CodeActionService.java` | Fix 3 |
| `symbol/DocumentSymbolService.java` | Fix 4 |
| Various test files | Fix 5 (update expectations) |

## Files to Research (derive from IntelliJ 2026.1 JARs)

| Class | Why |
|-------|-----|
| `HighlightingSessionImpl` | Fix 2 — factory method signature |
| `DaemonCodeAnalyzerEx` | Fix 2 — `runMainPasses` new requirements |
| `PsiAwareTextEditorProvider` | Fix 4 — understand modal progress trigger |
| `IntentionAction` / `IntentionActionDelegate` | Fix 3 — lazy init API |

## Testing Strategy

After each fix, run the affected test group to verify. After all fixes,
run full `./gradlew test` to confirm 0 failures.

## What NOT To Do

- Don't add new LSP features until existing ones pass
- Don't skip/ignore failing tests
- Don't change test infrastructure (base classes) unless necessary for a fix
- Don't add debug logging that isn't cleaned up
