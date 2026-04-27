# xLSP: Enhanced LSP Tool for Agent Code Navigation

## Problem Statement

The current LSP tool in opencode requires me to provide exact file path + line number + character position for every operation. This is impractical for agent usage because:

1. **I don't know coordinates**: I can't see the file - I must first read it and count lines manually
2. **Token waste**: Reading files just to find coordinates wastes tokens
3. **Cognitive overhead**: Converting symbol queries to coordinates breaks my workflow
4. **Error-prone**: Manual counting leads to wrong positions and failed operations

Example of current workflow problem:
```
User: "Find where Logger is defined in LspServer.java"
Me (current): 
  1. read LspServer.java (waste tokens scanning)
  2. grep "Logger" to find line (extra operation)
  3. call lsp tool with exact coordinates
```

What should happen:
```
User: "Find where Logger is defined"
Me: xlsp define Logger in LspServer.java → returns location directly
```

## Solution Overview

Create an opencode plugin that exposes an `xlsp` tool with symbol-based operations. The tool communicates with the IdeaLS LSP server on port 8989 and provides a simplified interface optimized for agent consumption.

## Design Principles

1. **Symbol-first input**: Accept symbol names, not coordinates
2. **Minimal reading**: Do symbol resolution internally, don't require me to read files first
3. **Structured output**: Return JSON that I can parse programmatically
4. **Graceful failures**: Return empty results, not errors, when nothing found
5. **Consistent interface**: Same parameter pattern for all operations
6. **File context**: Derive context from file path, not cursor position

## Tool Interface

### Command Format
```
xlsp <operation> <symbol> [in <file-or-pattern>] [--option value]
```

### Operations

| Operation | Description | Returns |
|-----------|-------------|---------|
| `define` | Find definition location | `[{file, line, column, context}]` |
| `references` | Find all references | `[{file, line, column, context}]` |
| `hover` | Get documentation/type | `{type, documentation}` |
| `complete` | Get completions | `[ {label, kind, detail} ]` |
| `symbols` | Search symbols in file/workspace | `[{name, kind, file, line}]` |
| `implement` | Find implementations | `[{file, line, column}]` |
| `calls` | Call hierarchy (incoming/outgoing) | `[{direction, name, file, line}]` |
| `diagnostics` | Get errors/warnings | `[{severity, message, file, line, column}]` |
| `actions` | Get code actions | `[{title, kind, edit}]` |
| `signature` | Parameter signature help | `{label, parameters: [{name, type}]}` |

### Input Flexibility

The tool should accept:
- Symbol names: `Logger`, `process`, `MyService`
- Partial patterns: `*Handler`, `get*`, `*Service*`
- Exact names: `"MyClassName"` (with quotes for special chars)
- File patterns: `*.java`, `**/src/**/*.java`
- Relative paths: `src/main/java/Foo.java`
- Absolute paths: `/full/path/to/Foo.java`

### Output Format

All operations return JSON with this structure:
```json
{
  "success": true,
  "operation": "define",
  "query": "Logger",
  "count": 1,
  "results": [
    {
      "file": "/path/to/file.java",
      "line": 27,
      "column": 37,
      "context": "Logger LOG = Logger.getInstance(LspServer.class)",
      "symbol": "Logger"
    }
  ]
}
```

Error format:
```json
{
  "success": false,
  "error": "LSP server not available",
  "hint": "Start LSP server on port 8989"
}
```

## Implementation Details

### Plugin Structure
```
/path/to/project/tool/xlsp.ts  # Main tool definition
```

### Connection Management
- Maintain persistent TCP connection to localhost:8989
- Auto-reconnect on disconnect
- Connection pooling for efficiency

### Request Flow
1. Parse input (symbol + optional file context)
2. If file provided but no position: auto-resolve symbol position via document symbols
3. Send LSP request with resolved position
4. Parse response into simplified JSON
5. Return structured result

### Symbol Resolution Strategy
When symbol provided without position:
1. If file specified: get document symbols, find matching symbol
2. Use first match with correct name
3. Extract line/column from symbol location
4. Proceed with LSP operation

### Error Handling
- Server unavailable: Return `{success: false, error: "server unavailable"}`
- Symbol not found: Return `{success: true, count: 0, results: []}`
- Invalid file: Return `{success: false, error: "file not found"}`
- Timeout: Return `{success: false, error: "timeout"}`

## Usage Examples

### Current (painful) workflow:
```
Me: read /path/to/LspServer.java
Me: grep "Logger" LspServer.java → finds line 27
Me: lsp operation: goToDefinition, filePath: "...", line: 27, character: 37
```

### With xLSP:
```
Me: xlsp define Logger in LspServer.java
→ {success: true, results: [{file: "...", line: 27, column: 37, ...}]}
```

### Batch search:
```
Me: xlsp symbols "Lsp" in server/src/main/java
→ {success: true, count: 15, results: [{name: "LspServer", ...}, ...]}
```

### Find all references:
```
Me: xlsp references "myTextDocumentService" in server/src
→ {success: true, count: 5, results: [{file: "...", line: X, column: Y}, ...]}
```

### Get diagnostics:
```
Me: xlsp diagnostics src/main/java
→ {success: true, count: 2, results: [{severity: "error", message: "...", ...}]}
```

## Integration with Agent Workflow

The xLSP tool should integrate seamlessly with my existing tools:

1. **Combined with read**: After xlsp returns location, I can use read tool at that location
2. **Combined with grep**: xlsp for semantic search, grep for text search when needed
3. **Error recovery**: Empty results from xlsp → fall back to grep
4. **Verification**: Use xlsp to verify my grep results are accurate

## Testing

Test the tool with these scenarios:
1. Define lookup: `xlsp define Logger in LspServer.java`
2. References: `xlsp references LspServer`
3. Symbols search: `xlsp symbols "Lsp" in server/src/main/java`
4. Diagnostics: `xlsp diagnostics server/src/main/java/tf/locals/idealsp/server`
5. Invalid file: `xlsp define Something in nonexistent.java`
6. Symbol not found: `xlsp define NonExistentSymbol`

## Acceptance Criteria

1. **Symbol resolution works**: Can find symbol position without me providing coordinates
2. **All operations return JSON**: No raw text or formatting that requires parsing
3. **Graceful failures**: Empty results, not errors, when nothing found
4. **Works with IdeaLS server**: Communicates correctly with LSP on port 8989
5. **Consistent interface**: All operations follow same input/output pattern
6. **Better than current**: Reduces token usage and operations compared to current workflow
7. **Error recovery**: I can recover from failures without extra tool calls