# PRD: File Structure (Ctrl+F12) Feature for Agentic Use

## What is Ctrl+F12 in IntelliJ IDEA?

The **File Structure** popup (Ctrl+F12) in IntelliJ IDEA displays a hierarchical view of all symbols in the currently open file:
- Classes, interfaces, enums
- Methods (with signatures)
- Fields/properties
- Inner classes
- Constructors
- Annotations (on classes/methods/fields)

It provides quick navigation to any symbol by pressing Enter or clicking.

## Evaluation: Is It Useful for Agentic Use?

### Yes, for these reasons:

1. **Code Understanding** - AI agents need to understand file structure to navigate large files without reading entire content
2. **Token Efficiency** - Get full file structure in one call vs. parsing file line-by-line
3. **Targeted Operations** - Agents can identify specific methods/fields to operate on
4. **Batch Analysis** - Analyze all methods in a class at once (e.g., find all public methods)
5. **Refactoring Support** - Identify refactoring targets (private methods, unused fields)

### Current LSP Coverage

The LSP `textDocument/documentSymbol` method already provides this functionality:
- Returns hierarchical `DocumentSymbol` tree with children
- Includes: name, kind, range, selectionRange, detail, documentation
- Supports all languages the IDE supports (Java, Kotlin, Python, etc.)

### Gap Analysis

| Feature | IntelliJ Ctrl+F12 | Current LSP documentSymbol |
|---------|-------------------|----------------------------|
| Hierarchical tree | ✅ | ✅ |
| Method signatures | ✅ | Partial (detail field) |
| Visibility icons | ✅ | ❌ |
| Inherited members | Separate option | ❌ |
| Filter by kind | ✅ | ❌ |
| Member count per class | ✅ | ❌ |

## Proposed Implementation

### Option 1: Enhance xlsp `symbols` command (Recommended)

Extend the existing `xlsp symbols` command with a `--file` flag to return full file structure:

```
xlsp symbols --file MyClass.java
```

Returns:
```json
{
  "success": true,
  "operation": "symbols",
  "file": "MyClass.java",
  "count": 15,
  "tree": [
    {
      "name": "MyClass",
      "kind": "class",
      "detail": "public",
      "line": 10,
      "children": [
        {
          "name": "process(String): void",
          "kind": "method",
          "detail": "public",
          "line": 15
        },
        {
          "name": "calculate",
          "kind": "method",
          "detail": "private",
          "line": 22
        },
        {
          "name": "logger",
          "kind": "field",
          "detail": "private final",
          "line": 12
        }
      ]
    }
  ]
}
```

### Option 2: New command `xlsp structure`

A dedicated command with additional options:

```
xlsp structure <file> [--kind method|field|class] [--visibility public|private]
```

### Option 3: Extend documentSymbol LSP response

Enhance the LSP server to include more metadata:
- Visibility modifiers (public/private/protected)
- Member counts
- Inherited members (when requested)

## Recommendation

**Proceed with Option 1** - enhance existing `symbols --file` operation. This is:
- Low effort (reuses existing documentSymbol implementation)
- Consistent with xlsp design principles
- Sufficient for agentic use cases

Add optional filtering:
- `--kind` filter: class, interface, enum, method, field, constructor
- `--visibility` filter: public, private, protected, package

## Status: ✅ COMPLETED

Implemented in `tools/xlsp/operations/symbols.ts` and `tools/xlsp/cli.ts` (commit `0ebbf20`). Supports `--kind`, `--visibility`, and `--tree` flags.

## Implementation Plan

### Phase 1: Enhance symbols.ts (Priority: High)
1. Modify `symbols.ts` to support `--file` flag without query
2. Return full hierarchical tree instead of flat list
3. Add `detail` field with method signatures

### Phase 2: Add filters (Priority: Medium)
1. Add `--kind` filter to only return specific symbol kinds
2. Add `--visibility` filter based on detail parsing

### Phase 3: Server Enhancement (Priority: Low)
1. Add visibility modifier detection in DocumentSymbolService
2. Add member counts

## Acceptance Criteria

1. **Full file structure**: `xlsp symbols --file Foo.java` returns complete hierarchy
2. **Method signatures**: Include parameter types and return types
3. **Kind filtering**: `--kind method` returns only methods
4. **Token savings**: Single call returns what would require parsing 500+ lines
5. **Graceful handling**: Return empty tree for empty/unsupported files
6. **Works for all supported languages**: Java, Kotlin, Python

## Example Agent Usage

```
Agent: "Find all private methods in PaymentService.java"
Agent: xlsp symbols --file PaymentService.java --kind method
→ Parse response, filter by "private" in detail field

Agent: "What methods accept String parameter?"
Agent: xlsp symbols --file Handler.java
→ Filter children where detail contains "String"

Agent: "List all fields in this class"
Agent: xlsp symbols --file Config.java --kind field
```