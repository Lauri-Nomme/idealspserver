# PRD: Move & Safe Delete Refactoring for xlsp + IdeaLSP

## 1. Problem Statement

IdeaLSP currently supports rename, extract method, introduce variable, and inline refactoring. The two remaining refactoring operations from the competitive analysis are **move** (move class/member between packages/files) and **safe delete** (delete a symbol after verifying it's unused). These complete the refactoring story and bring parity with MCP Steroid and Serena JB.

## 2. Goals

| Capability | Priority | Server | LSP Method |
|-----------|----------|--------|------------|
| Move refactoring | P0 | NEW | `idealsp/refactor` (type: `move`) |
| Safe delete refactoring | P0 | NEW | `idealsp/refactor` (type: `safe-delete`) |

Both operations extend the existing `idealsp/refactor` custom LSP method.

## 3. Architecture

```
LLM / Agent
  │
  └── xlsp refactor move <class-or-file> to <target-package>
        └── LSP: idealsp/refactor { type: "move", ... }
              └── RefactorCommand.dispatch("move")
                    └── MoveClassesOrPackagesHelper / MoveHandler

  └── xlsp refactor safe-delete <symbol> in <file>
        └── LSP: idealsp/refactor { type: "safe-delete", ... }
              └── RefactorCommand.dispatch("safe-delete")
                    └── SafeDeleteProcessor
```

## 4. Server Implementation Plan

### 4.1 Extend `RefactorParams`

Add `targetPackageUri` field (for move) to `RefactorParams.java`:

```java
public class RefactorParams {
    private String type;           // "extract-method" | "introduce-variable" | "inline" | "move" | "safe-delete"
    private String uri;            // file URI
    private Range range;           // selection range
    private String newName;        // for introduce-variable / inline
    private String targetPackageUri;  // NEW — for move refactoring
    
    // ... existing getters/setters
}
```

### 4.2 Move Refactoring

**When:** `type == "move"`

**Params:**
- `uri` — file containing the class/symbol to move
- `range` — position of the symbol to move
- `targetPackageUri` — target package directory URI

**IntelliJ API:** `MoveHandler` / `MoveClassesOrPackagesHelper`

Flow:
1. Resolve PsiElement at position
2. If PsiClass, use `MoveClassesOrPackagesHelper.doMove()` to the target package
3. If file, use `MoveFileHandler` to move the file
4. Return `RefactorResult { type: "move", success: true/false, message: "..." }`

### 4.3 Safe Delete Refactoring

**When:** `type == "safe-delete"`

**Params:**
- `uri` — file containing the symbol to delete
- `range` — position of the symbol

**IntelliJ API:** `SafeDeleteProcessor`

Flow:
1. Resolve PsiElement at position
2. Use `SafeDeleteProcessor()` to verify safe deletion
3. If safe, delete the element and update all references
4. If not safe, return error with details about usages
5. Return `RefactorResult { type: "safe-delete", success: true/false, message: "..." }`

### 4.4 Wiring

Extend `RefactorCommand.dispatch()` switch statement:

```java
case "move":
    // → MoveHandler
case "safe-delete":
    // → SafeDeleteProcessor
```

## 5. xlsp Client Implementation Plan

### 5.1 `refactor move` Operation

```
xlsp refactor move <symbol> to <target-package> in <file>
```

Example: `xlsp refactor move "MyClass" to "com.example.newpackage" in "src/MyClass.java"`

### 5.2 `refactor safe-delete` Operation

```
xlsp refactor safe-delete <symbol> in <file>
```

Example: `xlsp refactor safe-delete "unusedMethod" in "src/MyClass.java"`

## 6. Implementation Order

```
[Phase 1] Extend RefactorParams with targetPackageUri
[Phase 2] Move handler in RefactorCommand
[Phase 3] Safe delete handler in RefactorCommand
[Phase 4] Build + verify with unit tests
```

## 7. Testing

### Unit Tests (Java)
- `RefactorCommandMoveTest` — move class between packages
- `RefactorCommandSafeDeleteTest` — safe delete unused method

### Manual Verification
```
xlsp refactor move "MyClass" to "com.example.newpackage" in "src/com/oldpkg/MyClass.java"
xlsp refactor safe-delete "unusedMethod" in "src/MyClass.java"
```

## 8. Acceptance Criteria

1. Move refactoring: class moves to target package, imports updated
2. Safe delete refactoring: symbol deleted, references updated
3. Safe delete: returns meaningful error when symbol has usages
4. Both operations report success/failure status
5. No regression on existing refactoring operations (extract-method, introduce-variable, inline)
