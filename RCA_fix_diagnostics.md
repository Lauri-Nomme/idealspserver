# RCA & Fix Plan: Diagnostics (HighlightingSession issue in IntelliJ 2026.1)

## Problem
Diagnostics are not working on IntelliJ 2026.1. The test shows "Basic" diagnostics working but full error retrieval fails.

## Root Cause
IntelliJ 2026.1 requires a `HighlightingSession` to be registered before calling `DaemonCodeAnalyzerEx.runMainPasses()`. Without this registration, the highlighting pass silently fails with:
```
"No HighlightingSession stored in <id>"
```
caught and swallowed, returning 0 diagnostics.

## Fix
In `server/src/main/java/org/rri/ideals/server/diagnostics/DiagnosticsTask.java`, before calling `runMainPasses()`, create and register a `HighlightingSession`:

```java
// Research needed: exact API for HighlightingSessionImpl in 2026.1
// Likely something like:
HighlightingSessionImpl.createHighlightingSession(
    psiFile, editor, daemonProgressIndicator, ...);
```

## Files to Modify
1. `server/src/main/java/org/rri/ideals/server/diagnostics/DiagnosticsTask.java` - Add HighlightingSession registration
2. May need to research: `HighlightingSessionImpl`, `DaemonCodeAnalyzerEx` API changes

## Verification
Run the test script after fix:
```bash
timeout 180 python3 scripts/test_lsp_comprehensive.py
```
Check that item 12 (Diagnostics) shows more than just "Found 3 diagnostics" - should include proper error/warning counts.
