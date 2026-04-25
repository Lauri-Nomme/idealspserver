# Gap Analysis: IdeaLS Plugin Feature Coverage

## Overview
This document analyzes the gap between IDE features needed for effective software development, what the IdeaLS plugin currently provides, and what's possible according to LSP specs.

---

## AI Agent Tools Analysis

### Current Tools (Text-Level)

As an AI agent using this IDE, I have these tools:

| Tool | What it does | Limitation |
|------|-------------|-----------|
| **grep** | Search text patterns in files | False positives, misses renamed symbols |
| **glob** | Find files by name pattern | Can't find symbol definitions |
| **read** | Read file contents | Manual navigation |
| **write** | Write files | No symbolic context |
| **edit** | Edit by string match | Doesn't understand code structure |
| **lsp** (limited) | Some code navigation | Only works in already-open files |
| **bash** | Run shell commands | No IDE integration |

These are all **TEXT-BASED** tools working at the character/line level.

### What AI Agents Need (Symbol-Level)

For effective software development, AI agents need **SYMBOLIC** tools that understand code structure:

| Task | Current approach | LSP-powered approach |
|------|------------------|---------------------|
| **Find a method** | `grep "method name"` (false matches) | `find_symbol(method)` → exact match |
| **Find usages** | `grep "foo"` (misses imports) | `references` → all usages |
| **Go to definition** | manuall search | `definition` → jump |
| **Rename** | multiple search+replace | `rename_symbol` → cross-file safe |
| **Completion** | none | `completion` → type-aware |
| **Organize imports** | none | `code_action` → auto-imports |
| **Fix errors** | read error, search fix | `code_action` → quick fix |

### IDE Diagnostics Integration ✅ IMPLEMENTED

AI agents can now access real-time IDE errors and warnings via LSP diagnostics.

| Task | LSP-powered approach |
|------|---------------------|
| **Find compilation errors** | `diagnostics` → real-time IDE errors |
| **Get inspection warnings** | `diagnostics` → style warnings |
| **Understand error context** | `hover` → error explanations |
| **Apply quick fixes** | `code_action` → IDE quick fixes |

### How LSP Transforms Development

**Example: Fix a bug with AI agent**

LSP workflow:
1. `find_symbol()` → go to definition
2. `references` → see all callers instantly
3. `diagnostics` → see errors/warnings
4. `code_action` → get quick fixes
5. `rename_symbol` → safe cross-file rename

This changes O(hours) of manual searching into seconds of precise operations.

---

## 1. What Developers Need

### Navigation
- **Go to Definition** - Jump to symbol definition
- **Go to Implementation** - Jump to interface/class implementation
- **Go to Type Definition** - Jump to type (for variables, returns)
- **Find References** - Find all usages of a symbol
- **Find Implementations** - Find all implementations of interface
- **Workspace Symbol Search** - Search any symbol across project
- **File Outline** - Document symbols/structure

### Code Understanding
- **Hover** - Show type/value info on hover
- **Document Highlight** - Highlight all refs to variable
- **Semantic Tokens** - Syntax highlighting (colors for classes, methods, etc)

### Code Editing
- **Completion** - Auto-complete while typing
- **Signature Help** - Parameter hints for function calls
- **Code Actions** - Quick fixes (imports, refactoring)
- **Rename** - Rename symbol across project

### Quality
- **Diagnostics** - Error/warning inline
- **Document Format** - Auto-format file
- **On-track Format** - Auto-format while typing

---

## 2. What's Working (as of April 2026)

### Currently Working (Tested & Verified)
| Feature | Status | Test Result |
|--------|--------|----------|
| Initialize | ✅ Working | OK |
| Document Symbols | ✅ Working | 1+ symbols found |
| Workspace Symbols | ✅ Working | 43 symbols found |
| Definition | ✅ Working | 1+ locations |
| Hover | ✅ Working | OK |
| **Diagnostics** | ✅ Working | 3 diagnostics with proper severity |
| References | ✅ Working | Cross-file references working |
| Completion | ✅ Working | All 11 tests passing |
| Type Definition | ✅ Working | OK |
| Implementation | ✅ Working | OK |
| Document Highlight | ✅ Working | 7+ highlights |
| Rename | ✅ Working | OK |
| Signature Help | ✅ Working | OK |

### AI Agent Tools - Status

For AI agents working effectively with LSP tools:

| Priority | Tool | LSP Method | Current Status | AI Use Case |
|----------|------|-----------|----------------|------------|
| **CRITICAL** | find_symbol | workspace/symbol | ✅ Working | Find any symbol fast |
| **CRITICAL** | references | textDocument/references | ✅ Working | Find all usages |
| **CRITICAL** | completion | textDocument/completion | ✅ Working | Type-aware suggestions |
| **HIGH** | rename_symbol | textDocument/rename | ✅ Working | Safe refactoring |
| **HIGH** | code_action | textDocument/codeAction | ⚠️ Partial | Auto-imports, quick fixes (some lazy init issues) |
| **HIGH** | definition | textDocument/definition | ✅ Working | Jump to definition |
| **MEDIUM** | implementation | textDocument/implementation | ✅ Working | Find interface impls |
| **MEDIUM** | signature_help | textDocument/signatureHelp | ✅ Working | Parameter hints |
| **LOW** | formatting | textDocument/formatting | ❓ Unknown | Auto-format |
| **HIGH** | diagnostics | textDocument/publishDiagnostics | ✅ Working | IDE errors/warnings for AI agents |

### LSP Spec Coverage

#### Core Features (Must Have)
| Feature | LSP Method | Status |
|--------|----------|--------|
| Go to Definition | textDocument/definition | ✅ Working |
| Find References | textDocument/references | ✅ Working |
| Completion | textDocument/completion | ✅ Working |
| Hover | textDocument/hover | ✅ Working |
| Diagnostics | textDocument/publishDiagnostics | ✅ Working |

### IDE Diagnostics Implementation ✅ COMPLETED

Diagnostics are now fully implemented using `DaemonCodeAnalyzerEx.runMainPasses()` with proper `HighlightingSession` registration.

**Implementation approach used:**
1. **HighlightingSession registration** via `HighlightingSessionImpl.runInsideHighlightingSession()`
2. **Progress indicator** via `ProgressManager.getInstance().runProcess()` with `DaemonProgressIndicator`
3. **CodeInsightContext** obtained via `ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile))`
4. **Quick fixes** registered via `HighlightInfo.findRegisteredQuickFix()`

**Files modified:**
- `DiagnosticsTask.java` - Core diagnostics implementation
- `build.gradle.kts` - Added Kotlin plugin dependency

**Test results:**
- `test_lsp_comprehensive.py`: Found 3 diagnostics with proper severity (Error/Warning)
- Diagnostics working in both unit tests and live LSP server

## 7. LSP Specification Reference

Source: LSP 3.17 Specification (https://microsoft.github.io/language-server-protocol/specifications/lsp/3-17/specification/)

### Complete Feature List (LSP 3.17)

#### Language Features (textDocument/*)
| Feature | Method | Since |
|--------|--------|-------|
| Synchronization | textDocument/didOpen etc | 3.0 |
| Completion | textDocument/completion | 3.0 |
| Completion Resolve | completionItem/resolve | 3.0 |
| Hover | textDocument/hover | 3.0 |
| Signature Help | textDocument/signatureHelp | 3.0 |
| Go to Definition | textDocument/definition | 3.0 |
| Go to Type Definition | textDocument/typeDefinition | 3.6 |
| Go to Implementation | textDocument/implementation | 3.6 |
| Find References | textDocument/references | 3.0 |
| Document Highlights | textDocument/documentHighlight | 3.0 |
| Document Symbols | textDocument/documentSymbol | 3.0 |
| Code Action | textDocument/codeAction | 3.8 |
| Rename | textDocument/rename | 3.0 |
| Link at Cursor | textDocument/link | 3.18 |
| Selection Range | textDocument/selectionRange | 3.15 |
| Formatting | textDocument/formatting | 3.0 |
| Range Formatting | textDocument/rangeFormatting | 3.8 |
| On-Type Formatting | textDocument/onTypeFormatting | 3.0 |
| Inlay Hint | textDocument/inlayHint | 3.17 |

#### Workspace Features (workspace/*)
| Feature | Method | Since |
|--------|--------|-------|
| Symbols | workspace/symbol | 3.0 |
| Symbol Resolve | workspaceSymbol/resolve | 3.17 |
| Apply Edit | workspace/applyEdit | 3.0 |
| Code Lens | workspace/codeLens | 3.0 |
| Execute Command | workspace/executeCommand | 3.0 |

#### Window Features
| Feature | Method |
|--------|--------|
| Show Message | window/showMessage |
| Show Message Request | window/showMessageRequest |
| Log Message | window/logMessage |
| Progress | $/progress |

#### Lifecycle
| Feature | Method |
|--------|--------|
| Initialize | initialize |
| Initialized | initialized |
| Shutdown | shutdown |
| Exit | exit |

### IntelliJ Platform SDK Reference
- Plugin SDK: https://plugins.jetbrains.com/docs/intellij/
- Plugin Examples: https://github.com/JetBrains/intellij-community

### IdeaLS Repository
- Main: https://github.com/idea-statica/ideals
- Issues: https://github.com/idea-statica/ideals/issues