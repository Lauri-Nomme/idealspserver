# Fix References (Find Usages) in SDK 2026.1

## Problem

The `textDocument/references` LSP endpoint returns 0 results in SDK 2026.1 even when references exist in the code.

## Current Behavior

1. LSP protocol test: "References: FAILED or no result"
2. Unit tests: fail - ReferencesSearch returns 0 results
3. Log shows element found but search returns 0

## What Was Tried

See git log for attempts in FindUsagesCommand.java.

### Attempt 1: ReferencesSearch.search(target)
```java
var results = ReferencesSearch.search(target).findAll();
// Returns 0 results
```

### Attempt 2: FindUsagesManager with UsageCollectingViewManager
```java
var manager = ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();
var handler = manager.getFindUsagesHandler(target, FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS);
// Handler is found but still returns 0 results
```

### Attempt 3: Various PSI tree walk strategies
- Walk to PsiClassImpl, PsiMethodImpl, PsiFieldImpl
- Use PsiNamedElement, PsiNameIdentifierOwner
- All failed - tree walk goes past the definition or reaches wrong element

## Reference Implementation

The reference server (Ruin0x11/intellij-lsp-server) works using:

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

### TargetElementUtil Usage
```kotlin
// EditorUtil.kt
fun findTargetElement(editor: Editor): PsiElement? =
    TargetElementUtil
        .findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)
```

### Editor Creation
```kotlin
// ProjectUtil.kt
fun createEditor(context: Disposable, file: PsiFile, position: Position): EditorEx {
    val doc = getDocument(file)!!
    val editorFactory = EditorFactory.getInstance()
    val created = editorFactory.createEditor(doc, file.project) as EditorEx
    created.caretModel.moveToLogicalPosition(LogicalPosition(position.line, position.character))
    Disposer.register(context, Disposable { editorFactory.releaseEditor(created) })
    return created
}
```

## SDK 2026.1 API Check

### FindUsagesManager (confirmed exists in IU-261.22158.277)
```java
public FindUsagesHandler getFindUsagesHandler(PsiElement, boolean);
public FindUsagesHandler getFindUsagesHandler(PsiElement, OperationMode);
public FindUsagesHandler getNewFindUsagesHandler(PsiElement, boolean);
public static UsageSearcher createUsageSearcher(FindUsagesHandlerBase, PsiElement[], PsiElement[], FindUsagesOptions);
public static ProgressIndicator startProcessUsages(...);
```

### ReferencesSearch (unchanged)
```java
public static Query<PsiReference> search(PsiElement element);
public static Query<PsiReference> search(PsiElement element, SearchScope scope);
public static Query<PsiReference> search(PsiElement element, SearchScope scope, boolean caseSensitive);
public static Query<PsiReference> search(SearchParameters params);
```

## Root Cause Hypothesis

When `file.findElementAt(offset)` is used with file offset, it returns the leaf element (e.g., `PsiIdentifier`) at that position. This is often a **reference** TO something, not the **declaration** itself.

Example: At "LOG" in line 30 `LOG.warn("...")`:
- file.findElementAt(offset) returns: PsiIdentifier:LOG (the reference usage)
- TargetElementUtil.findTargetElement(editor) returns: Logger class (the declaration)

ReferencesSearch.search() needs the DECLARATION element to find all USAGES.

## Test Setup

### Test File
- `server/src/main/java/org/rri/ideals/server/LspServer.java`
- Line 30 has `LOG` field reference
- Line 55 has `LOG.info()` usage

### Test Position
```python
# test_lsp_comprehensive.py line 30, character 30
{
    "textDocument": {"uri": f"file://{test_file}"},
    "position": {"line": 30, "character": 30},
    "context": {"includeDeclaration": True},
}
```

### Expected
- Find references to Logger field in LspServer.java
- Should return at least 2 locations (lines 30 and 55)

## Fix Strategy

### Option A: Use Editor + TargetElementUtil (with reference server approach)
1. Create Editor from PsiFile
2. Move caret to position
3. Use TargetElementUtil.findTargetElement(editor) - should resolve to declaration
4. Pass declaration to ReferencesSearch or FindUsagesManager

### Option B: Find target via PsiReference.resolve()
1. Get reference at position: `element.getReferences()`
2. Resolve to declaration: `ref.resolve()`
3. Pass resolved element to ReferencesSearch

### Option C: Use FindUsagesManager.startProcessUsages directly
```java
// Using static method
FindUsagesHandler handler = manager.getFindUsagesHandler(element, true);
UsageSearcher searcher = FindUsagesManager.createUsageSearcher(
    handler, 
    handler.getPrimaryElements(),
    handler.getSecondaryElements(),
    handler.getFindUsagesOptions()
);
searcher.generate(usage -> { 
    // collect usages 
});
```

## Key Files to Modify

- `server/src/main/java/org/rri/ideals/server/references/FindUsagesCommand.java`

## Logging

Add to FindUsagesCommand.execute():
```java
LOG.warn("FindUsagesCommand.execute: offset=" + offset);
LOG.warn("FindUsagesCommand.execute: element=" + element + ", class=" + (element != null ? element.getClass() : "null"));
LOG.warn("FindUsagesCommand.execute: resolved element=" + resolved + ", class=" + (resolved != null ? resolved.getClass() : "null"));
LOG.warn("FindUsagesCommand.execute: ReferencesSearch found " + results.size());
```

## Related Tests

- `server/src/test/java/org/rri/ideals/server/lsp/ReferencesTest.java`
- `scripts/test_lsp_comprehensive.py` (run to verify)

## Links

- Reference server: https://github.com/Ruin0x11/intellij-lsp-server
- FindUsagesCommand.kt: src/main/kotlin/com/ruin/lsp/commands/document/find/
- EditorUtil.kt: src/main/kotlin/com/ruin/lsp/util/
- SDK 2026.1: /data/idea/idea-IU-261.22158.277/