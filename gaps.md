# Gap Analysis: IdeaLS Plugin Feature Coverage

## Overview
This document analyzes the gap between IDE features needed for effective software development, what the IdeaLS plugin currently provides, and what's possible according to LSP specs.

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
| Diagnostics | ✅ Working | Show errors |
| Initialize (advertised) | ⚠️ Bug | Capabilities not in response |

### Known Issues (Tested)
| Feature | Status | Test Result |
|--------|--------|----------|
| References | ❌ Broken | Returns empty |
| Completion | ❌ Broken | ClassCastException |
| Implementation | ❌ Unknown | Not tested |
| Type Definition | ❌ Unknown | Not tested |
| Code Actions | ❌ Unknown | Not tested |
| Rename | ❌ Unknown | Not tested |
| Signature Help | ❌ Unknown | Not tested |
| Document Highlight | ❌ Unknown | Not tested |
| Formatting | ❌ Unknown | Not tested |

### Code Says (Not Working)
These features ARE declared in ServerCapabilities but NOT tested:
- Code Actions (textDocument/codeAction)
- Signature Help (textDocument/signatureHelp)
- Document Highlight (textDocument/documentHighlight)
- Formatting (textDocument/formatting)
- Rename (textDocument/rename)
- Implementation (textDocument/implementation)
- Type Definition (textDocument/typeDefinition)

---

## 3. LSP Spec Coverage

Based on LSP 3.17 specification.

### Core Features (Must Have)
| Feature | LSP Method | Required |
|--------|----------|----------|
| Go to Definition | textDocument/definition | Yes |
| Find References | textDocument/references | Yes |
| Completion | textDocument/completion | Yes |
| Hover | textDocument/hover | Yes |
| Diagnostics | textDocument/publishDiagnostics | Yes |

### Important Features
| Feature | LSP Method | Required |
|--------|----------|----------|
| Workspace Symbols | workspace/symbol | Yes |
| Document Symbols | textDocument/documentSymbol | Yes |
| Go to Implementation | textDocument/implementation | No |
| Type Definition | textDocument/typeDefinition | No |
| Code Actions | textDocument/codeAction | No |
| Rename | textDocument/rename | No |

### Nice to Have
| Feature | LSP Method |
|--------|----------|
| Signature Help | textDocument/signatureHelp |
| Document Highlight | textDocument/documentHighlight |
| Formatting | textDocument/formatting |
| Range Formatting | textDocument/rangeFormatting |
| On Type Formatting | textDocument/onTypeFormatting |
| Selection Ranges | textDocument/selectionRanges |
| Call Hierarchy | textDocument/prepareCallHierarchy |
| Semantic Tokens | textDocument/semanticTokens/full |
| Inlay Hints | textDocument/inlayHint |

---

## 4. Gap Summary

### Critical Gaps (Blocking)
1. **Completion** - Essential but broken (ClassCastException in 2026.1)
2. **References** - Essential but returns empty

### Important Gaps
3. **Implementation** - Not tested
4. **Type Definition** - Not tested
5. **Code Actions** - Not implemented
6. **Rename** - Not implemented

### Nice to Have Gaps
7. Signature Help
8. Document Highlight
9. Formatting
10. Semantic Tokens

---

## 5. Next Steps Recommendations

### Priority 1: Fix Critical
1. Fix Completion (void CompletionProcessEx interface in 2026.1)
2. Debug References (returns empty list)

### Priority 2: Important  
3. Add Implementation handler
4. Add Code Actions handler (at least for organize imports)
5. Add Rename handler

### Priority 3: Nice to Have
6. Add Signature Help
7. Add Document Highlight
8. Add Formatting support

---

## 6. LSP Specification Reference

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