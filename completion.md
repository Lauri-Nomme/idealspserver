# IntelliJ Completion - Implementation & Findings

## Status: ALL 11 TESTS PASSING

Real IntelliJ completion works via `CompletionService.performCompletion()` with proper
dummy identifier insertion, icon path-based kind matching, and editor leak protection.

---

## Architecture

### Completion Flow (doComputeCompletions)

1. Create `VoidCompletionProcess` (implements `CompletionProcessEx`)
2. Disable deferred icon loading: `Registry.get("psi.deferIconLoading").setValue(false, process)`
3. Inside `ProgressManager.runProcess` + `invokeAndWait` (editor needs EDT):
   a. Create editor via `EditorUtil.withEditor()`
   b. Create `CompletionInfo(editor, project, process)` — this creates `CompletionParameters`
      with dummy identifier inserted (see below)
   c. Call `CompletionService.getCompletionService().performCompletion(params, consumer)`
   d. Collect results into `LookupArrangerImpl`, cache as `CompletionData`
4. Convert `LookupElementWithMatcher` list to LSP `CompletionItem` list

### Completion Resolve Flow (doResolve)

1. Create a PsiFile copy from cached `CompletionData.fileText`
2. Inside `ProgressManager.runProcess` with `LspProgressIndicator(cancelChecker)`:
   a. Create editor on the copy
   b. Create new `CompletionInfo` for the copy
   c. Get documentation via `IdeDocumentationTargetProvider.documentationTargets()` (plural)
   d. Handle insert: `CompletionUtil.createInsertionContext()` + `lookupElement.handleInsert()`
   e. Handle template/snippet expansion if `TemplateState` exists
3. Diff the copy-before vs copy-after to produce LSP `TextEdit`s

---

## Key Discoveries & Fixes

### 1. Dummy Identifier Insertion (Root Cause of Missing Keywords)

**Problem**: `CompletionInfo` was passing the original `OffsetsInFile` directly to
`createCompletionParameters()` without inserting the dummy identifier first. This meant
the completion position element was whitespace or wrong, so `CompletionContributor`s
(especially keyword contributors) didn't fire.

**Fix**: Call `CompletionInitializationUtil.insertDummyIdentifier(initContext, process)` to
get a `CopyFileUpdateTask`, then `ensureUpdatedAndGetNewOffsets()` to get the modified
`OffsetsInFile` with "IntellijIdeaRulezzz" inserted. Pass *that* to `createCompletionParameters()`.

**Prerequisite**: Must call `process.setHostOffsets(hostOffsets)` before `insertDummyIdentifier`,
because it internally calls `process.getHostOffsets()`.

```java
var hostOffsets = new OffsetsInFile(initContext.getFile(), initContext.getOffsetMap());
process.setHostOffsets(hostOffsets);
var copyTask = CompletionInitializationUtil.insertDummyIdentifier(initContext, process);
var offsetsWithDummy = copyTask.ensureUpdatedAndGetNewOffsets();
parameters = CompletionInitializationUtil.createCompletionParameters(initContext, process, offsetsWithDummy);
```

### 2. Java Plugin Not Loaded in Tests

**Problem**: `LspLightBasePlatformTestCase` extended `BasePlatformTestCase` which doesn't
load the Java plugin. Java completion contributors weren't registered.

**Fix**:
- Changed base class to `LightJavaCodeInsightFixtureTestCase`
- Added `bundledPlugin("com.intellij.java")` to `build.gradle.kts`

### 3. Icon Path-Based Kind Matching

**Problem**: `IconUtil.compareIcons()` fails in headless/test mode because SVG icons can't
be rasterized — both icons render as empty 1x1 images, making all comparisons return true.
This caused `kind` to always be set to the first match (Method).

**Fix**: Extract SVG path from `CachedImageIcon` via reflection (`getOriginalPath()`) and
look it up in a static `ICON_PATH_TO_KIND` map built from `AllIcons` constants at class init.
Falls back to pixel-based comparison for GUI environments.

```java
private static String extractIconPath(javax.swing.Icon icon) {
    var method = icon.getClass().getMethod("getOriginalPath");
    return (String) method.invoke(icon);
}
```

Why reflection: `CachedImageIcon` implements `IconPathProvider` but that interface is in
the `util-ui` module which isn't in our compile classpath.

### 4. Editor Leak on ProcessCanceledException

**Problem**: In `testResolveCancellation`, `EditorFactory.createEditor()` internally creates
and registers an `EditorImpl` with `EditorFactory`, but then `EditorImpl`'s constructor calls
`ProgressManager.checkCanceled()` and throws `ProcessCanceledException` *before* returning
the editor object. This leaves the editor registered with `EditorFactory` but not in our
disposable tree → leak detected by test teardown.

**Fix**: Try-catch in `EditorUtil.createEditor()` around `editorFactory.createEditor()`.
On exception, iterate `editorFactory.getAllEditors()`, find any editor with matching document
that isn't disposed, and release it.

```java
try {
    created = editorFactory.createEditor(doc, file.getProject());
} catch (Exception e) {
    for (Editor editor : editorFactory.getAllEditors()) {
        if (editor.getDocument() == doc && !editor.isDisposed()) {
            editorFactory.releaseEditor(editor);
        }
    }
    throw e;
}
```

### 5. documentationTargets API Change (IntelliJ 2026.1)

**Problem**: `IdeDocumentationTargetProvider.documentationTarget()` (singular) now throws
`IllegalStateException: Override this or documentationTargets(...)`.

**Fix**: Switch to `documentationTargets()` (plural) which returns a `List<DocumentationTarget>`.

### 6. CompletionInfo Disposal

**Problem**: `CompletionInfo` creates an internal `VoidCompletionProcess`, and
`insertDummyIdentifier` registers child disposables on it. If the process isn't disposed
through a parent chain, these leak.

**Fix**: `CompletionInfo` constructor now takes `Disposable parentDisposable` and registers
its internal process with it via `Disposer.register(parentDisposable, process)`.

### 7. doResolve Disposal Must Use invokeAndWait

**Problem**: The `finally` block in `doResolve` called `WriteCommandAction.runWriteCommandAction`
directly, but disposal of editors requires being on EDT.

**Fix**: Wrap in `ApplicationManager.getApplication().invokeAndWait()`.

### 8. Test Expectation Updates for IntelliJ 2026.1

**`@Contract` annotation rendering**: Now rendered as markdown link
`@[`Contract`](psi_element://org.jetbrains.annotations.Contract)` instead of plain `@Contract`.

**`main` method template**: Java 21+ implicit main — template now generates
`static void main()` instead of `public static void main(String[] args)`.

---

## Key Classes

| Class | Location | Role |
|-------|----------|------|
| `CompletionService` (ours) | `server/.../completions/CompletionService.java` | LSP completion orchestration |
| `CompletionInfo` | `server/.../completions/CompletionInfo.java` | Creates `CompletionParameters` with dummy ID |
| `VoidCompletionProcess` | `server/.../completions/VoidCompletionProcess.java` | `CompletionProcessEx` stub |
| `EditorUtil` | `server/.../util/EditorUtil.java` | Editor creation with leak protection |
| `CompletionService` (IJ) | `intellij.platform.analysis` | `performCompletion()` |
| `CompletionInitializationUtil` | `intellij.platform.analysis.impl` | `createCompletionInitializationContext`, `insertDummyIdentifier`, `createCompletionParameters` |
| `BaseCompletionService` | `intellij.platform.analysis.impl` | Implements `performCompletion` |
| `IdeDocumentationTargetProvider` | `intellij.platform.lang.impl` | `documentationTargets()` |

---

## Test Coverage (CompletionServiceTest)

All 11 tests pass:

| Test | What it covers |
|------|---------------|
| `testCompletionForKeywordAndFunctionJava` | Keywords (`for`) and methods (`formula`) returned with correct kinds |
| `testCompletionForImportedClass` | Class completion from imports |
| `testCompletionForStaticImport` | Static import completion + `@Contract` documentation |
| `testJavaItarWithLookupItem` | `itar` live template with lookup |
| `testJavaItcoLiveTemplate` | `itco` live template |
| `testJavaLambdaPostfixTemplate` | Lambda postfix template |
| `testJavaForiLiveTemplate` | `fori` live template |
| `testJavaItli` | `itli` live template |
| `testJavaIterWithLookupItemLiveTemplate` | `iter` live template with lookup |
| `testTemplateCompletion` | `main` method template (Java 21+ signature) |
| `testResolveCancellation` | Cancellation during resolve doesn't leak editors |
