# IntelliJ 2026.1 API Changes - Diagnostics & Code Actions

## Root Cause

IntelliJ 2026.1 introduced a new context-based highlighting architecture. The
daemon highlighting system now requires `HighlightingSession` and `CodeInsightContext`
to be properly set up before any highlighting or intention pass APIs can work.

Without these contexts, `DaemonCodeAnalyzerEx.runMainPasses()` returns empty results and
`ShowIntentionsPass.getActionsToShow()` also returns empty.

---

## Key API Changes (IntelliJ 2026.1)

### 1. HighlightingSession Required

`HighlightingSessionImpl.getFromCurrentIndicator(psiFile)` must return a session
before calling any daemon APIs. Without it:

- `ShowIntentionsPass` constructor throws `IllegalStateException` (line 145):
  ```
  this.myVisibleRange = HighlightingSessionImpl.getFromCurrentIndicator(psiFile).getVisibleRange();
  ```

### 2. CodeInsightContext Parameter

`DaemonCodeAnalyzerEx.processHighlights()` signature changed to require `CodeInsightContext`:

```java
// NEW signature (2026.1+):
DaemonCodeAnalyzerEx.processHighlights(
    MarkupModelEx model,
    Project project,
    HighlightSeverity minSeverity,
    int startOffset,
    int endOffset,
    CodeInsightContext context,  // NEW PARAMETER
    Processor<? super HighlightInfo> processor
)
```

`ShowIntentionsPass.getActionsToShow()` internally uses this (line 345):
```java
CodeInsightContext context = EditorContextManager.getEditorContext(hostEditor, project);
```

### 3. EditorContextManager

Creates `CodeInsightContext` from an Editor:

```java
// In app.jar:
public interface EditorContextManager {
    static CodeInsightContext getEditorContext(@NotNull Editor editor, @NotNull Project project) {
        // Line 165: checks CodeInsightContexts.isSharedSourceSupportEnabled()
        // Falls back to CodeInsightContexts.defaultContext() if disabled
        return editorContextManager.getEditorContexts(editor).getMainContext();
    }
}
```

### 4. HighlightingSession Creation Methods

```java
// From HighlightingSessionImpl.java:

// Get existing or create new session (line 186-215):
public static HighlightingSession getOrCreateHighlightingSession(
    PsiFile psiFile,
    CodeInsightContext codeInsightContext,
    DaemonProgressIndicator progressIndicator,
    ProperTextRange visibleRange
)

// Create with Editor (line 220-238):
public static HighlightingSessionImpl createHighlightingSession(
    PsiFile psiFile,
    CodeInsightContext codeInsightContext,
    Editor editor,                      // nullable
    EditorColorsScheme editorColorsScheme, // nullable
    DaemonProgressIndicator progressIndicator,
    Number daemonCancelEventCount
)

// Run code inside a session (line 275-299):
public static void runInsideHighlightingSession(
    PsiFile psiFile,
    CodeInsightContext codeInsightContext,
    EditorColorsScheme editorColorsScheme,
    ProperTextRange visibleRange,
    boolean canChangeFileSilently,
    Consumer<? super HighlightingSession> runnable
)
```

### 5. ShowIntentionsPass Flow

```java
// getActionsToShow() calls (line 326):
getActionsToShow(hostEditor, hostFile, intentions, passId, true)
  -> Creates CodeInsightContext via EditorContextManager (line 345)
  -> Calls DaemonCodeAnalyzerEx.processHighlights() with context (line 354)
  -> Iterates infos and calls addAvailableFixesForGroups() (line 362-365)
  -> Calls getRegisteredIntentionActions() for intention actions (line 388)
```

Key fields in IntentionsInfo:
```java
public static final class IntentionsInfo {
    public List<HighlightInfo.IntentionActionDescriptor> intentionsToShow;
    public List<HighlightInfo.IntentionActionDescriptor> errorFixesToShow;
    public List<HighlightInfo.IntentionActionDescriptor> inspectionFixesToShow;
    public List<AnAction> guttersToShow;
    public List<HighlightInfo.IntentionActionDescriptor> notificationActionsToShow;
}
```

### 6. IntentionAction Lazy Initialization

`IntentionAction.getText()` returns `"(not initialized) class <FQN>"` before
`isAvailable()` is called. Call `isAvailable(project, editor, psiFile)` first.

---

## Required Fix for DiagnosticsTask

The fix pattern for both DiagnosticsTask and CodeActionService:

```java
// 1. Create Editor via EditorUtil
var disposable = Disposer.newDisposable();
try {
    EditorUtil.withEditor(disposable, psiFile, position, editor -> {
        // 2. Get CodeInsightContext
        var context = EditorContextManager.getInstance(project)
            .getEditorContext(editor, project);

        // 3. Create HighlightingSession via runInsideHighlightingSession
        var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
        var visibleRange = new ProperTextRange(0, doc.getTextLength());

        HighlightingSessionImpl.runInsideHighlightingSession(
            psiFile, context, colorsScheme, visibleRange, false,
            session -> {
                // 4. Now safe to call daemon APIs
                var infos = DaemonCodeAnalyzerEx.getInstanceEx(project)
                    .runMainPasses(psiFile, doc, progress);
                // Also safe: ShowIntentionsPass.getActionsToShow(editor, psiFile)
            }
        );
    });
} finally {
    Disposer.dispose(disposable);
}
```

---

## Classes Involved

| Class | JAR | Key method |
|-------|-----|-----------|
| `HighlightingSessionImpl` | `intellij.platform.analysis.impl` | `runInsideHighlightingSession()`, `createHighlightingSession()` |
| `EditorContextManager` | `app.jar` | `getEditorContext(Editor, Project)` |
| `DaemonCodeAnalyzerEx` | `intellij.platform.lang.impl` | `processHighlights()` now takes context |
| `ShowIntentionsPass` | `intellij.platform.lang.impl` | `getActionsToShow()` uses context internally |
| `IntentionAction` | platform | `isAvailable()` before `getText()` for 2026.1+ |

---

## Test Impact

- `DiagnosticsServiceTest` (2 tests): Need session context
- `DiagnosticsTest` (2 tests): Need session context
- `CodeActionServiceTest` (2 tests): Depends on diagnostics, also needs context
- `CodeActionsTest` (1 test): Depends on ShowIntentionsPass

---

## References

- `HighlightingSessionImpl.java` (line 275): `runInsideHighlightingSession()`
- `ShowIntentionsPass.java` (line 354): `processHighlights()` with context
- `EditorContextManager.java` (line 82): `getEditorContext()`
- `ShowIntentionsPass.IntentionsInfo` (line 691): action lists