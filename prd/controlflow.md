# Control Flow (Call Hierarchy) - PRD

## 1. Overview

Call Hierarchy is an LSP feature (added in LSP 3.16) that allows clients to navigate the call relationships between functions/methods. It consists of three protocol methods:

1. **textDocument/prepareCallHierarchy** - Prepare hierarchy items from a position
2. **callHierarchy/incomingCalls** - Resolve incoming calls to a hierarchy item
3. **callHierarchy/outgoingCalls** - Resolve outgoing calls from a hierarchy item

The protocol is designed for lazy evaluation - `prepareCallHierarchy` returns root items, then `incomingCalls`/`outgoingCalls` can be called repeatedly to traverse the call tree.

---

## 2. LSP Specification Excerpts

### 2.1 Server Capability

```typescript
// Server capabilities property path
capabilities.textDocument.callHierarchyProvider?: boolean | CallHierarchyOptions | CallHierarchyRegistrationOptions
```

The `callHierarchy/incomingCalls` and `callHierarchy/outgoingCalls` requests do not define their own capabilities. They are only issued if the server registers for `textDocument/prepareCallHierarchy`.

### 2.2 Prepare Call Hierarchy Request

**Method:** `textDocument/prepareCallHierarchy`

**Params:** `CallHierarchyPrepareParams`

```typescript
export interface CallHierarchyPrepareParams extends TextDocumentPositionParams,
    WorkDoneProgressParams {
}
```

**Result:** `CallHierarchyItem[] | null`

```typescript
export interface CallHierarchyItem {
    /**
     * The name of this item.
     */
    name: string;

    /**
     * The kind of this item.
     */
    kind: SymbolKind;

    /**
     * Tags for this item.
     */
    tags?: SymbolTag[];

    /**
     * More detail for this item, e.g. the signature of a function.
     */
    detail?: string;

    /**
     * The resource identifier of this item.
     */
    uri: DocumentUri;

    /**
     * The range enclosing this symbol not including leading/trailing whitespace
     * but everything else, e.g. comments and code.
     */
    range: Range;

    /**
     * The range that should be selected and revealed when this symbol is being
     * picked, e.g. the name of a function. Must be contained by the range.
     */
    selectionRange: Range;

    /**
     * A data entry field that is preserved between a call hierarchy prepare and
     * incoming calls or outgoing calls requests.
     */
    data?: LSPAny;
}
```

### 2.3 Call Hierarchy Incoming Calls

**Method:** `callHierarchy/incomingCalls`

**Params:** `CallHierarchyIncomingCallsParams`

```typescript
export interface CallHierarchyIncomingCallsParams extends
    WorkDoneProgressParams, PartialResultParams {
    item: CallHierarchyItem;
}
```

**Result:** `CallHierarchyIncomingCall[] | null`

```typescript
export interface CallHierarchyIncomingCall {
    /**
     * The item that makes the call.
     */
    from: CallHierarchyItem;

    /**
     * The ranges at which the calls appear. This is relative to the caller
     * denoted by from.
     */
    fromRanges: Range[];
}
```

### 2.4 Call Hierarchy Outgoing Calls

**Method:** `callHierarchy/outgoingCalls`

**Params:** `CallHierarchyOutgoingCallsParams`

```typescript
export interface CallHierarchyOutgoingCallsParams extends
    WorkDoneProgressParams, PartialResultParams {
    item: CallHierarchyItem;
}
```

**Result:** `CallHierarchyOutgoingCall[] | null`

```typescript
export interface CallHierarchyOutgoingCall {
    /**
     * The item that is called.
     */
    to: CallHierarchyItem;

    /**
     * The range at which this item is called. This is the range relative to
     * the caller, e.g the item passed to `callHierarchy/outgoingCalls` request.
     */
    fromRanges: Range[];
}
```

---

## 3. Protocol-Level API Summary

| Aspect | prepareCallHierarchy | incomingCalls | outgoingCalls |
|--------|---------------------|---------------|---------------|
| **Method** | `textDocument/prepareCallHierarchy` | `callHierarchy/incomingCalls` | `callHierarchy/outgoingCalls` |
| **Direction** | Client → Server | Client → Server | Client → Server |
| **Input** | `TextDocumentPositionParams + WorkDoneProgressParams` | `CallHierarchyItem + WorkDoneProgressParams + PartialResultParams` | `CallHierarchyItem + WorkDoneProgressParams + PartialResultParams` |
| **Output** | `CallHierarchyItem[] \| null` | `CallHierarchyIncomingCall[] \| null` | `CallHierarchyOutgoingCall[] \| null` |
| **Semantics** | Returns hierarchy root items at cursor position | Given an item, returns who calls it | Given an item, returns who it calls |
| **Supports Progress** | Yes | Yes | Yes |
| **Supports Partial Result** | No | Yes | Yes |

### JSON-RPC Request Examples

**prepareCallHierarchy:**
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "textDocument/prepareCallHierarchy",
    "params": {
        "textDocument": { "uri": "file:///path/to/file.java" },
        "position": { "line": 10, "character": 5 }
    }
}
```

**incomingCalls:**
```json
{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "callHierarchy/incomingCalls",
    "params": {
        "item": {
            "name": "myMethod",
            "kind": 6,
            "uri": "file:///path/to/file.java",
            "range": { "start": { "line": 10, "character": 0 }, "end": { "line": 20, "character": 1 } },
            "selectionRange": { "start": { "line": 10, "character": 5 }, "end": { "line": 10, "character": 13 } },
            "data": { "psiPointer": "..." }
        }
    }
}
```

**outgoingCalls:**
```json
{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "callHierarchy/outgoingCalls",
    "params": {
        "item": { "name": "callerMethod", "kind": 6, ... }
    }
}
```

---

## 4. Test Cases for test_lsp_comprehensive.py

### 4.1 Test Data Setup

Create a dedicated test file at `server/test-data/callhierarchy/TestCalls.java`:

```java
package callhierarchy;

public class TestCalls {
    private String name;

    public TestCalls(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public void printName() {
        System.out.println(getName());
    }

    public void process() {
        String n = getName();
        printName();
        new TestCalls("test");
    }

    public static void main(String[] args) {
        TestCalls tc = new TestCalls("hello");
        tc.process();
    }
}
```

**Expected call relationships:**
- `getName()` at line 10:
  - Outgoing: none (returns field)
  - Incoming: `printName()` (line 14), `process()` (line 18)
- `printName()` at line 13:
  - Outgoing: `getName()` (line 14)
  - Incoming: `process()` (line 15)
- `process()` at line 17:
  - Outgoing: `getName()` (line 18), `printName()` (line 19), `TestCalls()` constructor (line 20)
  - Incoming: `main()` (line 25)
- `main()` at line 23:
  - Outgoing: `TestCalls()` constructor (line 24), `process()` (line 25)
  - Incoming: none (entry point)

### 4.2 Test Code

Add the following test cases to `/vokk/home/lauri/dev/idealspserver/git/scripts/test_lsp_comprehensive.py`:

```python
    # Setup: Open the test file with known call relationships
    test_calls_file = f"{PROJECT_PATH}/callhierarchy/TestCalls.java"
    with open(test_calls_file) as f:
        test_calls_text = f.read()

    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{test_calls_file}",
                "languageId": "java",
                "version": 1,
                "text": test_calls_text,
            }
        },
    )
    drain_notifications(sock, seconds=5)

    # Test 10: prepareCallHierarchy on getName() method (line 10, char 17)
    resp = send_and_recv(
        sock,
        "textDocument/prepareCallHierarchy",
        {
            "textDocument": {"uri": f"file://{test_calls_file}"},
            "position": {"line": 10, "character": 17},
        },
        9,
    )
    assert resp and "result" in resp and resp["result"], \
        f"prepareCallHierarchy FAILED: {resp}"
    items = resp["result"]
    assert len(items) == 1, f"Expected 1 item, got {len(items)}"
    assert items[0]["name"] == "getName", f"Expected 'getName', got {items[0]['name']}"
    assert items[0]["kind"] == 6, f"Expected kind 6 (Method), got {items[0]['kind']}"  # SymbolKind.Method = 6
    print(f"10. PrepareCallHierarchy: OK - Got '{items[0]['name']}'")

    # Store for subsequent tests
    call_hierarchy_item = items[0]

    # Test 11: incomingCalls to getName() - should find printName() and process()
    resp = send_and_recv(
        sock,
        "callHierarchy/incomingCalls",
        {"item": call_hierarchy_item},
        10,
    )
    assert resp and "result" in resp and resp["result"], \
        f"incomingCalls FAILED: {resp}"
    calls = resp["result"]
    incoming_names = sorted([c["from"]["name"] for c in calls])
    assert "printName" in incoming_names, f"Expected 'printName' in incoming, got {incoming_names}"
    assert "process" in incoming_names, f"Expected 'process' in incoming, got {incoming_names}"
    assert len(calls) == 2, f"Expected 2 incoming calls to getName(), got {len(calls)}"
    print(f"11. IncomingCalls: OK - Got {incoming_names}")

    # Test 12: outgoingCalls from getName() - should be empty (no calls made by getName)
    resp = send_and_recv(
        sock,
        "callHierarchy/outgoingCalls",
        {"item": call_hierarchy_item},
        11,
    )
    assert resp and "result" in resp, f"outgoingCalls FAILED: {resp}"
    calls = resp["result"]
    assert calls is not None, "outgoingCalls returned null"
    # getName() makes no outgoing calls (just returns this.name)
    print(f"12. OutgoingCalls from getName(): OK - Got {len(calls)} call(s) (expected 0)")

    # Test 13: prepareCallHierarchy on process() method (line 17, char 17)
    resp = send_and_recv(
        sock,
        "textDocument/prepareCallHierarchy",
        {
            "textDocument": {"uri": f"file://{test_calls_file}"},
            "position": {"line": 17, "character": 17},
        },
        12,
    )
    assert resp and "result" in resp and resp["result"], \
        f"prepareCallHierarchy FAILED: {resp}"
    process_item = resp["result"][0]
    assert process_item["name"] == "process", f"Expected 'process', got {process_item['name']}"
    print(f"13. PrepareCallHierarchy on process(): OK")

    # Test 14: outgoingCalls from process() - should find getName(), printName(), TestCalls constructor
    resp = send_and_recv(
        sock,
        "callHierarchy/outgoingCalls",
        {"item": process_item},
        13,
    )
    assert resp and "result" in resp and resp["result"], \
        f"outgoingCalls FAILED: {resp}"
    calls = resp["result"]
    outgoing_names = sorted([c["to"]["name"] for c in calls])
    assert "getName" in outgoing_names, f"Expected 'getName' in outgoing, got {outgoing_names}"
    assert "printName" in outgoing_names, f"Expected 'printName' in outgoing, got {outgoing_names}"
    print(f"14. OutgoingCalls from process(): OK - Got {outgoing_names}")

    # Test 15: incomingCalls to process() - should find main()
    resp = send_and_recv(
        sock,
        "callHierarchy/incomingCalls",
        {"item": process_item},
        14,
    )
    assert resp and "result" in resp and resp["result"], \
        f"incomingCalls FAILED: {resp}"
    calls = resp["result"]
    incoming_names = sorted([c["from"]["name"] for c in calls])
    assert "main" in incoming_names, f"Expected 'main' in incoming, got {incoming_names}"
    print(f"15. IncomingCalls to process(): OK - Got {incoming_names}")

    # Test 16: prepareCallHierarchy on non-callable (a field declaration) - should return null or empty
    resp = send_and_recv(
        sock,
        "textDocument/prepareCallHierarchy",
        {
            "textDocument": {"uri": f"file://{test_calls_file}"},
            "position": {"line": 4, "character": 20},  # 'name' field
        },
        15,
    )
    if resp and "result" in resp:
        result = resp["result"]
        assert result is None or len(result) == 0, \
            f"Expected null/empty for field, got {result}"
        print(f"16. PrepareCallHierarchy on field: OK - Got expected null/empty")
    else:
        print(f"16. PrepareCallHierarchy on field: FAILED")

    # Cleanup: close test file
    send_notification(
        sock,
        "textDocument/didClose",
        {"textDocument": {"uri": f"file://{test_calls_file}"}},
    )
```

---

## 5. Server Implementation Design

### 5.1 Architecture Overview

Following the existing patterns in IdeaLS:

```
Client Request
    ↓
MyTextDocumentService.java (LSP4J endpoint)
    ↓
CallHierarchyCommand.java (extends LspCommand)
    ↓
IntelliJ PSI APIs
    ↓
Response (CallHierarchyItem[] or CallHierarchyCall[])
```

### 5.2 Files to Create/Modify

#### New Files:
- `server/src/main/java/org/rri/ideals/server/callhierarchy/PrepareCallHierarchyCommand.java`
- `server/src/main/java/org/rri/ideals/server/callhierarchy/IncomingCallsCommand.java`
- `server/src/main/java/org/rri/ideals/server/callhierarchy/OutgoingCallsCommand.java`
- `server/src/main/java/org/rri/ideals/server/callhierarchy/CallHierarchyUtil.java` (shared utilities)

#### Files to Modify:
- `server/src/main/java/org/rri/ideals/server/MyTextDocumentService.java` - Add LSP endpoints
- `server/src/main/java/org/rri/ideals/server/LspServer.java` - Register capability

### 5.3 MyTextDocumentService.java Changes

Add these methods following the existing pattern (e.g., `definition()`, `references()`):

```java
// In MyTextDocumentService.java

@Override
public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
        CallHierarchyPrepareParams params) {
    try {
        var project = session.getProject();
        if (project == null) {
            LOG.warn("prepareCallHierarchy() called but project is not yet initialized");
            return CompletableFuture.completedFuture(List.of());
        }
        return new PrepareCallHierarchyCommand(params.getPosition())
                .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
    } catch (Exception e) {
        LOG.error("prepareCallHierarchy() failed", e);
        return CompletableFuture.completedFuture(List.of());
    }
}

@Override
public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
        CallHierarchyIncomingCallsParams params) {
    try {
        var project = session.getProject();
        if (project == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return new IncomingCallsCommand(params.getItem())
                .runAsync(project, null); // URI not needed, item contains data
    } catch (Exception e) {
        LOG.error("callHierarchyIncomingCalls() failed", e);
        return CompletableFuture.completedFuture(List.of());
    }
}

@Override
public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
        CallHierarchyOutgoingCallsParams params) {
    try {
        var project = session.getProject();
        if (project == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return new OutgoingCallsCommand(params.getItem())
                .runAsync(project, null);
    } catch (Exception e) {
        LOG.error("callHierarchyOutgoingCalls() failed", e);
        return CompletableFuture.completedFuture(List.of());
    }
}
```

### 5.4 Command Implementation Pattern

Following `FindDefinitionCommandBase.java` pattern:

```java
// PrepareCallHierarchyCommand.java
public class PrepareCallHierarchyCommand extends LspCommand<List<CallHierarchyItem>> {
    private final Position pos;

    public PrepareCallHierarchyCommand(Position pos) { this.pos = pos; }

    @Override
    protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
        return () -> "PrepareCallHierarchy call";
    }

    @Override
    protected boolean isCancellable() { return true; }

    @Override
    protected List<CallHierarchyItem> execute(@NotNull ExecutorContext ctx) {
        return EditorUtil.withEditor(ctx.getPsiFile(), ctx.getProject(), editor -> {
            int offset = MiscUtil.positionToOffset(editor, pos);
            // Use IntelliJ APIs to find the callable at offset
            PsiElement element = findCallableElement(editor, offset);
            if (element == null) return List.of();

            CallHierarchyItem item = convertToItem(element, ctx.getPsiFile());
            return List.of(item);
        });
    }
}
```

### 5.5 IntelliJ APIs to Use

For **prepareCallHierarchy**:
- `TargetElementUtil.findTargetElement()` - find the callable at position
- `com.intellij.psi.PsiMethod`, `PsiFunction`, etc. - the callable elements
- PSI `range` and `selectionRange` from element text range

For **incomingCalls**:
- `com.intellij.psi.search.searches.MethodReferencesSearch` - find callers
- `com.intellij.psi.search.searches.OverridingMethodsSearch` - for polymorphic calls
- For each reference, get the containing method as `CallHierarchyItem`

For **outgoingCalls**:
- Parse method body to find `PsiMethodCallExpression` instances
- Resolve each call to its target method
- Return as `CallHierarchyOutgoingCall` with `fromRanges` being the call sites

### 5.6 Using the `data` Field

The `CallHierarchyItem.data` field is used to pass server-side state between requests:

```java
// When creating CallHierarchyItem in prepareCallHierarchy:
Map<String, Object> data = new HashMap<>();
data.put("virtualFileUrl", file.getUrl());
data.put("psiPointer", elementToStablePointer(element)); // e.g., method FQN
// Store enough info to relocate the PSI element later

item.setData(data);
```

```java
// When receiving item in incomingCalls/outgoingCalls:
Map<String, Object> data = (Map<String, Object>) item.getData();
String psiPointer = (String) data.get("psiPointer");
// Re-resolve the PSI element from the pointer
```

### 5.7 LspServer.java Changes

Add capability in `defaultServerCapabilities()`:

```java
// In LspServer.java, defaultServerCapabilities() method
capabilities.setTextDocument(
    new TextDocumentCapabilities(
        ...,
        new CallHierarchyCapabilities(true),  // Enable call hierarchy
        ...
    )
);
```

Or more likely, looking at existing pattern:

```java
// capability map
capabilities.put("textDocument.callHierarchy", true);
// or use LSP4J's CallHierarchyOptions
```

### 5.8 Test Structure

Following existing test patterns in `server/src/test/java/org/rri/ideals/server/lsp/`:

- Create `CallHierarchyTest.java`
- Use test data in `server/test-data/` with Java files containing callable methods
- Test scenarios:
  - prepareCallHierarchy on a method → returns item
  - prepareCallHierarchy on non-callable → returns null/empty
  - incomingCalls → returns callers
  - outgoingCalls → returns callees
  - Cross-file calls
  - Recursive calls

---

## 6. Implementation Order

1. **Register capability** in `LspServer.java`
2. **Add LSP endpoints** to `MyTextDocumentService.java`
3. **Implement PrepareCallHierarchyCommand** - find callable at position
4. **Implement IncomingCallsCommand** - find callers using MethodReferencesSearch
5. **Implement OutgoingCallsCommand** - find callees by parsing method body
6. **Add Python tests** to `test_lsp_comprehensive.py`
7. **Add Java unit/integration tests**
8. **Test with VS Code / Neovim** to verify client compatibility
