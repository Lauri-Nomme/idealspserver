# RCA & Fix Plan: Diagnostics (HighlightingSession issue in IntelliJ 2026.1)

## Status: ✅ FIXED

Diagnostics are now working on IntelliJ 2026.1. The comprehensive test shows proper error/warning counts.

## Problem (Original)
Diagnostics were not working on IntelliJ 2026.1. The test showed "Basic" diagnostics working but full error retrieval failed.

## Root Cause
IntelliJ 2026.1 requires a `HighlightingSession` to be registered before calling `DaemonCodeAnalyzerEx.runMainPasses()`. Without this registration, the highlighting pass silently fails with:
```
"No HighlightingSession stored in <id>"
```
caught and swallowed, returning 0 diagnostics.

## Fix Applied
In `server/src/main/java/org/rri/ideals/server/diagnostics/DiagnosticsTask.java`, the fix involved:

1. **Get CodeInsightContext inside ReadAction** (line 113):
   ```java
   var context = ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile));
   ```

2. **Call progress.start() before runProcess()** (line 118):
   ```java
   progress.start();
   ```

3. **Wrap in ProgressManager.runProcess()** (lines 120-133):
   ```java
   return ProgressManager.getInstance().runProcess(() -> {
       var result = new ArrayList<HighlightInfo>();
       HighlightingSessionImpl.runInsideHighlightingSession(psiFile, context, colorsScheme, range, false, session -> {
           var highlights = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
           if (highlights != null) {
               result.addAll(highlights);
           }
       });
       return result;
   }, progress);
   ```

4. **Additional fixes**:
   - Added `bundledPlugin("org.jetbrains.kotlin")` to `build.gradle.kts` (Kotlin plugin required for .kt file analysis)
   - Changed `createProgress().join()` to `.get(5, TimeUnit.SECONDS)` to avoid blocking forever in live server

## Files Modified
1. `server/src/main/java/org/rri/ideals/server/diagnostics/DiagnosticsTask.java` - Fixed call pattern
2. `server/build.gradle.kts` - Added Kotlin plugin dependency

## Verification
Run the test script:
```bash
timeout 180 python3 scripts/test_lsp_comprehensive.py
```

**Result (April 2026)**:
```
12. Diagnostics: OK - Found 3 diagnostics
    - [Warn] Field 'x' is never used
    - [Error] Cannot resolve symbol 'String'
    - [Error] Compact source file contains no 'main' method
```

Diagnostics are working with proper severity levels (Error/Warning).
