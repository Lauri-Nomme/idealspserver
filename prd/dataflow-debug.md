# Data Flow - Why `processChildren` returns 0 usages

## Verified (fixed)
- [x] NPE: `ProgressIndicator.checkCanceled()` with null indicator — *fixed via `ProgressManager.runProcess`*
- [x] Deadlock: `ReadAction.nonBlocking` inside read lock — *not the issue; we're on a worker thread without read lock*
- [x] Wrong traversal: `SliceRootNode.getChildren()` returns 1 wrapper node — *investigated; wrapper has 0 inner children*

## Hypotheses — ordered by likelihood

### H1: Index not ready for the file
`ReferencesSearch.search(element)` inside `processUsagesFlownFromThe` requires the PSI index to be built. When a file is opened via `didOpen`, the index may not include its usages yet.
- **Test:** Call a reference search first, wait for it to complete, then call dataflow.
- **Test:** Run dataflow on a file that was opened 30s earlier (after indexing completes).
- **Test:** Use `ProjectService.refreshAllFiles()` or similar to force indexing before analysis.

### H2: File must be committed to index (not just opened)
`didOpen` creates a PSI file in memory but doesn't commit it to the searchable index. The file must go through `PsiManager` with proper VFS handling.
- **Test:** Open the file, make an edit, format it, then try dataflow.
- **Test:** Use a file that's already been fully committed (e.g., one that diagnostics ran on).

### H3: `processChildren` must run on EDT with proper reader context
`SliceNode.doGetChildren()` calls `executeByImpatientReader()` which handles read-lock acquisition differently than `ProgressManager.runProcess`. On a worker thread without a read lock, `ReadAction.nonBlocking` inside `processChildren` may silently fail.
- **Test:** Use `ApplicationManager.getApplication().invokeAndWait(() -> { ... })` to run on EDT.
- **Test:** Use `ReadAction.run(() -> { ... })` instead of `ProgressManager.runProcess`.

### H4: Analysis scope excludes the file
`params.scope = new AnalysisScope(project)` creates a project-wide scope. But the test file may not be included if it's outside the project content roots.
- **Test:** Verify `scope.contains(element)` returns true.
- **Test:** Create scope explicitly from the file's PsiFile.

### H5: Element type isn't supported by Java slice provider
The Java `processUsagesFlownFromThe` handles `PsiLocalVariable`, `PsiParameter`, `PsiField`, `PsiMethod`. For `PsiParameter` (constructor param), it calls `searchReferencesAndProcessAssignmentTarget` which searches references to the parameter. Constructor params may be handled differently.
- **Test:** Try with a `PsiField` (field declaration with initializer).
- **Test:** Try with a `PsiLocalVariable` (local var assigned from method call).
- **Test:** Try with a `PsiMethod` (method that returns a value).

### H6: Concurrency issue with result collection
`allUsages` is a local list captured by the processor lambda. The `processChildren` callback runs inside `ReadAction.nonBlocking().executeSynchronously()` which executes on a separate thread or blocks until complete. There may be a visibility issue.
- **Test:** Use `Collections.synchronizedList()` for allUsages.
- **Test:** Add a latch/sleep after `runProcess` to ensure completion.

## Next steps
1. Test H3 first — run the processChildren on EDT via `invokeAndWait`
2. Test H1 — add a wait/delay before calling dataflow to give index time
3. Test H2 — force file commit before analysis
4. Test H5 — try different element types
