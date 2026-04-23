# Diagnostics Implementation Status - 2026.1 API Changes

## Problem Statement

The IntelliJ 2026.1 SDK changed the daemon highlighting API. `runMainPasses()` returned 0 highlights. Both `testKotlinErrors` and `testGetQuickFixes` were failing.

## Root Cause Analysis

Three bugs were identified by derivinging the 2026.1 SDK classes with CFR.

### Bug #1: Missing `ProgressManager.runProcess()` wrapper

The old code created a `DaemonProgressIndicator` but never registered it as the current progress indicator via `ProgressManager.runProcess()`. Inside `HighlightingSessionImpl.runInsideHighlightingSession()` (line 290), it calls:

```java
DaemonProgressIndicator indicator = GlobalInspectionContextBase.assertUnderDaemonProgress();
```

This throws if no `DaemonProgressIndicator` is active. The exception was silently swallowed by a catch block, so `runMainPasses()` appeared to return 0 highlights.

**Fix**: Wrap the entire call in `ProgressManager.getInstance().runProcess(() -> { ... }, progress)`.

### Bug #2: Missing ReadAction for `CodeInsightContextUtil.getCodeInsightContext()`

`CodeInsightContextUtil.getCodeInsightContext(psiFile)` requires read access. Without it, it throws an exception that was also silently caught.

**Fix**: Wrap in `ReadAction.compute(() -> ...)`.

### Bug #3: Missing Kotlin plugin dependency (root cause of 0 highlights)

After fixing bugs #1 and #2, `runMainPasses()` executed without exceptions but still returned 0 highlights. Logging revealed the PsiFile was `PsiPlainTextFileImpl` instead of `KtFile`:

```
psiFile=PsiFile(plain text):test.kt class=com.intellij.psi.impl.source.PsiPlainTextFileImpl
```

The Kotlin plugin (`org.jetbrains.kotlin`) was not declared as a `bundledPlugin()` dependency in `build.gradle.kts`. Only `com.intellij.java` was listed. Without the Kotlin plugin loaded, `.kt` files are treated as plain text, so no syntax analysis runs and no errors are found.

**Fix**: Add `bundledPlugin("org.jetbrains.kotlin")` to `build.gradle.kts`.

### Additional: Test assertion update

The Kotlin compiler in 2026.1 changed error message format from:
- `[UNRESOLVED_REFERENCE] Unresolved reference: Test`

to:
- `[UNRESOLVED_REFERENCE] Unresolved reference 'Test'.`

Test expectations were updated to match.

## The Correct 2026.1 Calling Pattern

Decompiled from `DaemonCodeAnalyzerImpl`, `HighlightingSessionImpl`, and `ProgressableTextEditorHighlightingPass`:

```java
var context = ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile));
var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
var range = ProperTextRange.create(0, doc.getTextLength());

var progress = new DaemonProgressIndicator();
progress.start();

return ProgressManager.getInstance().runProcess(() -> {
    var result = new ArrayList<HighlightInfo>();
    HighlightingSessionImpl.runInsideHighlightingSession(psiFile, context, colorsScheme, range, false, session -> {
        var highlights = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
        if (highlights != null) result.addAll(highlights);
    });
    return result;
}, progress);
```

Critical requirements:
1. `progress.start()` must be called before `runProcess()`
2. `runProcess()` registers the `DaemonProgressIndicator` on the current thread
3. `runInsideHighlightingSession()` must run inside `runProcess()` -- it calls `assertUnderDaemonProgress()`
4. `runMainPasses()` must run inside the session callback -- the pass constructor retrieves the session from the indicator, and `doCollectInformation()` checks that `session.getProgressIndicator() == progress`

## Decompilation Call Chain (for reference)

1. `DaemonCodeAnalyzerImpl.runMainPasses()` (line 720) -- creates pass objects via `TextEditorHighlightingPassRegistrarEx.instantiateMainPasses()`, runs `pass.doCollectInformation(progress)`, returns `pass.getInfos()`
2. `TextEditorHighlightingPassRegistrarImpl.instantiateMainPasses()` (line 370) -- filters for `MainHighlightingPassFactory`, calls `factory.createMainHighlightingPass()`
3. `ProgressableTextEditorHighlightingPass` constructor (line 101) -- `this.myHighlightingSession = HighlightingSessionImpl.getFromCurrentIndicator(psiFile)` -- gets session from thread's DaemonProgressIndicator
4. `ProgressableTextEditorHighlightingPass.doCollectInformation()` (lines 134-139) -- **THE CRITICAL CHECK**: if `session.getProgressIndicator() != progress`, silently skips with `LOG.debug("Skipped running the 2nd copy...")`

## Files Changed

- `server/build.gradle.kts` -- added `bundledPlugin("org.jetbrains.kotlin")`
- `server/src/main/java/.../DiagnosticsTask.java` -- fixed `doHighlighting()` call pattern
- `server/src/test/java/.../DiagnosticsServiceTest.java` -- updated error message expectations

## Test Status

- `testKotlinErrors`: PASSING (returns 2 diagnostics as expected)
- `testGetQuickFixes`: FAILING (separate issue -- some quick fix actions return "(not initialized)" text from lazy initialization in 2026.1)
