# Fix References (Find Usages) in SDK 2026.1 - ✅ FIXED

## Status: COMPLETED

The `textDocument/references` LSP endpoint now works correctly in SDK 2026.1, including cross-file references.

## Problem (Original)

The `textDocument/references` LSP endpoint returned 0 results in SDK 2026.1 even when references existed in the code.

## Fix Applied

### Root Cause
`FindDefinitionCommandBase.execute()` was setting `targetElement = originalElem` (the element at cursor in the source file) without calling the `findDefinitions()` abstract method. Both origin and target resolved to the same element.

### Solution (commit: 330b910)
Fixed `FindDefinitionCommandBase` to actually call `findDefinitions(editor, offset)` method:

```java
// Before (broken):
var targetElement = originalElem;
return List.of(targetElement).stream().map(elem -> { ... });

// After (fixed):
return findDefinitions(editor, offset).map(targetElement -> { ... });
```

### Additional Fixes for Cross-File References (commits: 3a56e73, 0a5be30, a3a4c19)
1. Added source root protection against async wipe
2. Used `FindUsagesManager` approach for cross-file references
3. Added `FindUsagesCommand` with proper implementation

## Test Results (April 2026)

```
5. References: OK - Found 4 references
15. Cross-file References: OK - Found 5 references
```

## Reference Implementation Used

The fix was inspired by the reference server (Ruin0x11/intellij-lsp-server) which uses:

### Key Approach: Editor + TargetElementUtil
```kotlin
// In FindUsagesCommand.kt
fun findUsages(editor: Editor, cancelToken: CancelChecker?): List<Usage> {
    val project = editor.project ?: return listOf()
    val element = findTargetElement(editor) ?: return listOf()  // KEY!
    // element is the DECLARATION, not the reference
    
    val rawResults = ArrayList<Usage>()
    val manager = FindUsagesManager(project, UsageCollectingViewManager(...))
    TransactionGuard.getInstance().submitTransactionAndWait {
        manager.findUsages(element, null, null, false, null)
    }
    return rawResults
}
```

## Key Files Modified

- `server/src/main/java/org/rri/ideals/server/references/FindDefinitionCommandBase.java` - Fixed to call findDefinitions()
- `server/src/main/java/org/rri/ideals/server/references/FindUsagesCommand.java` - Added for cross-file support

## Related Tests

- `server/src/test/java/org/rri/ideals/server/lsp/ReferencesTest.java` - ✅ All tests pass
- `scripts/test_lsp_comprehensive.py` - ✅ References working

## Links

- Reference server: https://github.com/Ruin0x11/intellij-lsp-server
- SDK 2026.1: Compatible and working