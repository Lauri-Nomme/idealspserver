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

### Architecture: CLI-first, TypeScript + Bun

Build as a command-line tool first (faster iteration), then wrap as an opencode tool.
Uses the same stack as opencode plugins: TypeScript, Bun runtime, `@opencode-ai/plugin` for the tool wrapper.

```
xlsp <operation> <symbol> [in <file>] [--port 8989]
                    ↓
   Parse args → Connect TCP (JSON-RPC) → LSP server on port 8989
                    ↓
     Symbol resolution → LSP request → Parse response → JSON stdout
```

Key design decisions:
1. CLI first — same module serves both `bun run cli.ts define Logger` and opencode `tool()`
2. Reuse JSON-RPC framing from `test_lsp_comprehensive.py` (Content-Length header, notification draining)
3. Symbol-first input — no line/column needed; resolves via `documentSymbol` or `workspace/symbol`

### File Structure

```
tools/xlsp/
  cli.ts                       # CLI entry point (#!/usr/bin/env bun), argv parsing, subcommand dispatch
  lsp-client.ts                # TCP connection, JSON-RPC send/recv, notification drain
  operations/
    define.ts                  # textDocument/definition
    references.ts              # textDocument/references
    hover.ts                   # textDocument/hover
    complete.ts                # textDocument/completion
    symbols.ts                 # workspace/symbol + textDocument/documentSymbol
    implement.ts               # textDocument/implementation
    calls.ts                   # callHierarchy/incomingCalls + callHierarchy/outgoingCalls
    diagnostics.ts             # textDocument/publishDiagnostics
    actions.ts                 # textDocument/codeAction
    signature.ts               # textDocument/signatureHelp
    type-def.ts                # textDocument/typeDefinition
    dataflow.ts                # textDocument/dataflowFrom + textDocument/dataflowTo (custom extension)
  symbol-resolver.ts           # Resolve symbol name → file+position via didOpen + documentSymbol
  output.ts                    # JSON formatting
.opencode/tools/xlsp.ts        # OpenCode tool wrapper (thin, delegates to core)
```

### Operations & LSP Mapping

| CLI operation | LSP method | Symbol resolution |
|---|---|---|
| `define <sym>` | `textDocument/definition` | Resolve sym → position |
| `references <sym>` | `textDocument/references` | Resolve sym → position |
| `hover <sym>` | `textDocument/hover` | Resolve sym → position |
| `complete <prefix>` | `textDocument/completion` | Manual pos or end-of-file |
| `symbols [query]` | `workspace/symbol` | Query string, no resolution needed |
| `symbols --file <f>` | `textDocument/documentSymbol` | N/A |
| `implement <sym>` | `textDocument/implementation` | Resolve sym → position |
| `calls in <sym>` | `callHierarchy/incomingCalls` | Prepare hierarchy, then incoming |
| `calls out <sym>` | `callHierarchy/outgoingCalls` | Prepare hierarchy, then outgoing |
| `diagnostics <file>` | `textDocument/publishDiagnostics` | Open file, drain notifications |
| `actions <file>` | `textDocument/codeAction` | Full file range |
| `signature <sym>` | `textDocument/signatureHelp` | Resolve sym → position |
| `type-def <sym>` | `textDocument/typeDefinition` | Resolve sym → position |
| `dataflow-from <sym>` | `textDocument/dataflowFrom` | Resolve sym → position |
| `dataflow-to <sym>` | `textDocument/dataflowTo` | Resolve sym → position |

### Symbol Resolution Strategy

```
Input: "define Logger in LspServer.java"
  1. If file given → didOpen file → documentSymbol → find symbol matching name → extract position
  2. If no file → workspace/symbol query → pick best match → use its location
  3. Proceed with LSP operation at resolved position
  4. Close file after operation (didClose notification)
```

### Output Format

All output is JSON to stdout:
```json
{
  "success": true,
  "operation": "define",
  "query": "Logger",
  "file": "LspServer.java",
  "count": 1,
  "results": [
    {"name": "LOG", "file": "path/to/Logger.java", "line": 18, "column": 13, "uri": "file:///path/..."}
  ]
}
```

Error output:
```json
{"success": false, "operation": "define", "error": "server unavailable", "hint": "Start LSP server on port 8989"}
```

### Connection Lifecycle

- Connect per invocation (simpler for CLI)
- Timeout: 30s for most ops, 60s for diagnostics/indexing
- Graceful shutdown (shutdown + exit LSP messages)

### Implementation Order

1. `lsp-client.ts` — TCP JSON-RPC framing (port from test_lsp_comprehensive.py)
2. `cli.ts` — argv parsing, subcommand dispatch
3. `symbol-resolver.ts` — didOpen + documentSymbol → find symbol position
4. `define.ts`, `references.ts` — simplest operations, prove the pattern
5. `hover.ts`, `complete.ts`, `symbols.ts`
6. `diagnostics.ts` — needs notification draining
7. `calls.ts` — multi-step (prepare → incoming/outgoing)
8. `actions.ts`, `signature.ts`, `type-def.ts`, `implement.ts`, `dataflow.ts`
9. `.opencode/tools/xlsp.ts` — thin opencode `tool()` wrapper

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