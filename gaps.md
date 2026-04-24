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

### NEW: IDE Diagnostics Integration

**Current Problem**: AI agents cannot access real-time IDE errors and warnings
**Solution**: Extend LSP diagnostics to retrieve actual IDE inspection results

| Task | Current approach | LSP-powered approach |
|------|------------------|---------------------|
| **Find compilation errors** | `grep "error:"` in console | `diagnostics` → real-time IDE errors |
| **Get inspection warnings** | manual code review | `diagnostics` → style warnings |
| **Understand error context** | read logs | `hover` → error explanations |
| **Apply quick fixes** | manual editing | `code_action` → IDE quick fixes |

### How This Transforms Development

**Example: Fix a bug in a service method**

Current workflow:
1. `grep "List<User>"` to find the class
2. `glob "*.java"` to find files
3. `read` each file to find method
4. `grep` to find usages
5. Manually track what changed

VS with LSP:
1. `find_symbol(FindUsagesCommand)` → go to definition
2. `references` → see all callers instantly
3. `rename_symbol` to change safely across project

This changes O(hours) of manual searching into seconds of precise operations.

### NEW: IDE Diagnostics Transformation

**Example: Fix compilation errors with AI agent**

Current workflow:
1. `mvn compile` to see errors
2. Read error messages from console
3. Manually navigate to error location
4. Search for similar fixes online
5. Apply fix manually

VS with Enhanced LSP:
1. `textDocument/publishDiagnostics` → get real IDE errors
2. `hover` on error → get detailed explanation
3. `code_action` → get available quick fixes
4. Apply fix via LSP → cross-file safe

This changes O(minutes) of error debugging into seconds of automated assistance.

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

## 2. What's Working (as of 2026.1)

### Currently Working (Tested)
| Feature | Status | Test Result |
|--------|--------|----------|
| Initialize | ✅ Working | OK |
| Document Symbols | ✅ Working | 1 symbol found |
| Workspace Symbols | ✅ Working | 27 symbols found |
| Definition | ✅ Working | 1 location |
| Hover | ✅ Working | OK |
| **Diagnostics (Basic)** | ⚠️ Basic | Shows basic errors |
| Initialize (advertised) | ⚠️ Bug | Capabilities not in response |

### NEW: IDE Diagnostics Gap Analysis
| Feature | Status | AI Agent Need |
|--------|--------|--------------|
| **IDE Error Retrieval** | ❌ Not Implemented | Get real-time compilation errors |
| **Warning Detection** | ❌ Not Implemented | Get style/inspection warnings |
| **Quick Fix Access** | ❌ Not Implemented | Access IDE quick fixes via LSP |

### AI Agent Tools - What's Needed

For AI agents to work effectively, we need these LSP tools integrated:

| Priority | Tool | LSP Method | Current Status | AI Use Case |
|----------|------|-----------|----------------|------------|
| **CRITICAL** | find_symbol | workspace/symbol | ✅ Working | Find any symbol fast |
| **CRITICAL** | references | textDocument/references | ❌ Broken | Find all usages |
| **CRITICAL** | completion | textDocument/completion | ❌ Broken | Type-aware suggestions |
| **HIGH** | rename_symbol | textDocument/rename | ❌ Unknown | Safe refactoring |
| **HIGH** | code_action | textDocument/codeAction | ❌ Unknown | Auto-imports, quick fixes |
| **HIGH** | definition | textDocument/definition | ✅ Working | Jump to definition |
| **MEDIUM** | implementation | textDocument/implementation | ❌ Unknown | Find interface impls |
| **MEDIUM** | signature_help | textDocument/signatureHelp | ❌ Unknown | Parameter hints |
| **LOW** | formatting | textDocument/formatting | ❌ Unknown | Auto-format |

### NEW: IDE Diagnostics Retrieval | **HIGH** | diagnostics | textDocument/publishDiagnostics | ⚠️ Partial | IDE errors/warnings for AI agents |

### Known Issues (Tested)
| Feature | Status | Test Result |
|--------|--------|----------|
| References | ✅ Working | All tests pass |
| Completion | ❌ Broken | ClassCastException |
| Implementation | ❌ Unknown | Not tested |
| Type Definition | ❌ Unknown | Not tested |
| Code Actions | ❓ Partial | Implemented but EDT-blocking, no cancellation |
| Rename | ❓ Partial | Not tested |
| Signature Help | ❓ Unknown | Not tested |
| Document Highlight | ✅ Working | All tests pass |
| Formatting | ❓ Unknown | Not tested |

### Code Says (Implemented / In Progress)
These features ARE declared in ServerCapabilities and tested:
- Code Actions (textDocument/codeAction) - Implemented, needs improvement
- Signature Help (textDocument/signatureHelp) - Not tested
- Document Highlight (textDocument/documentHighlight) - ✅ Implemented
- Formatting (textDocument/formatting) - Not tested
- Rename (textDocument/rename) - Not tested
- Implementation (textDocument/implementation) - Not tested
- Type Definition (textDocument/typeDefinition) - ✅ Implemented

### LSP Spec Coverage

#### Core Features (Must Have)
| Feature | LSP Method | Status |
|--------|----------|--------|
| Go to Definition | textDocument/definition | ✅ Working |
| Find References | textDocument/references | ✅ Working |
| Completion | textDocument/completion | ❌ Broken (ClassCastException) |
| Hover | textDocument/hover | ✅ Working |
| Diagnostics | textDocument/publishDiagnostics | ⚠️ Partial |

### New: IDE Diagnostics Implementation Plan

### Technical Approach

To implement IDE diagnostics retrieval, we need to extend the LSP server to:

1. **Access IntelliJ Inspection API**
   ```java
   // Use IntelliJ's inspection manager
   InspectionManager inspectionManager = InspectionManager.getInstance(project);
   GlobalInspectionContext context = inspectionManager.createNewGlobalContext(true);
   
   // Run inspections on file
   List<ProblemDescriptor> problems = inspectionManager.inspectFile(psiFile, context);
   ```

2. **Convert to LSP Diagnostics Format**
   ```java
   // Convert IntelliJ ProblemDescriptor to LSP Diagnostic
   Diagnostic lspDiagnostic = new Diagnostic();
   lspDiagnostic.setRange(convertToLspRange(problemDescriptor.getTextRange()));
   lspDiagnostic.setSeverity(convertToLspSeverity(problemDescriptor.getSeverity()));
   lspDiagnostic.setMessage(problemDescriptor.getDescriptionTemplate());
   ```

3. **Real-time Updates**
   - Use `PsiDocumentManager` to detect file changes
   - Trigger inspections on file save or modification
   - Send incremental diagnostic updates

### Required API Integration

| IntelliJ API | LSP Equivalent | Implementation Notes |
|--------------|----------------|---------------------|
| `InspectionManager.inspectFile()` | `textDocument/diagnostic` | Core inspection runner |
| `ProblemDescriptor` | `Diagnostic` | Error/wrapper container |
| `LocalQuickFix` | `CodeAction` | Quick fix integration |
| `HighlightInfo` | `DocumentHighlight` | Additional context |

### Implementation Files to Modify

1. **`MyTextDocumentService.java`** - Add diagnostic publishing
2. **Create `DiagnosticService.java`** - Handle inspection logic
3. **Create `QuickFixProvider.java`** - Convert IntelliJ quick fixes to LSP code actions

### Testing Strategy

1. **Error Detection Test** - Verify compilation errors are detected
2. **Warning Detection Test** - Verify inspection warnings are captured
3. **Quick Fix Test** - Verify quick fixes are available via code actions
4. **Real-time Update Test** - Verify diagnostics update on file changes

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