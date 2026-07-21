# PRD: Type Hierarchy (LSP 3.17) for xlsp + IdeaLSP

## 1. Problem Statement

Competitive analysis shows **type hierarchy** is the single biggest gap vs. other IntelliJ-based LSP solutions (intellij-mcp, idea-mcp, Serena JB). Call hierarchy (method calls) is already implemented, but agents have no structured way to answer:

- "What classes extend this class?"
- "What interfaces does this class implement?"
- "What are all subclasses of this type?"

## 2. Goals

| Capability | Priority | Server | LSP Method |
|-----------|----------|--------|------------|
| `textDocument/prepareTypeHierarchy` | P0 | NEW | Prepare a type hierarchy root from a position |
| `typeHierarchy/supertypes` | P0 | NEW | Get supertypes (superclass + interfaces) |
| `typeHierarchy/subtypes` | P0 | NEW | Get all subclasses/inheritors |

## 3. Architecture

```
LLM / Agent
  │
  ├── xlsp type-hierarchy <symbol> in <file>        ← prepare + resolve
  │     └── LSP: textDocument/prepareTypeHierarchy → TypeHierarchyItem[]
  │
  ├── xlsp type-hierarchy supertypes <item>         ← supertypes query
  │     └── LSP: typeHierarchy/supertypes → TypeHierarchyItem[]
  │
  └── xlsp type-hierarchy subtypes <item>           ← subtypes query
        └── LSP: typeHierarchy/subtypes → TypeHierarchyItem[]
```

All type hierarchy operations are **read-only** (no edits). Follows the same pattern as call hierarchy.

## 4. Server Implementation Plan

### 4.1 `textDocument/prepareTypeHierarchy`

**Package:** `server/src/main/java/tf/locals/idealsp/server/typehierarchy/`

| File | Role |
|------|------|
| `PrepareTypeHierarchyCommand.java` | Extends `LspCommand<List<TypeHierarchyItem>>`. Finds `PsiClass` at position via PSI tree walk. Converts to `TypeHierarchyItem` with name, kind, uri, range, detail (qualified name), and data map for relocation. |
| `SupertypesCommand.java` | Takes `TypeHierarchyItem`, resolves to `PsiClass`, returns superclass + interfaces as `List<TypeHierarchyItem>`. |
| `SubtypesCommand.java` | Takes `TypeHierarchyItem`, resolves to `PsiClass`, uses `ClassInheritorsSearch` to find all subclasses. |

**TypeHierarchyItem data map:**
```java
data.put("fileUrl", file.getVirtualFile().getUrl());
data.put("className", psiClass.getName());
```

**Resolution:** Look up class by name in the file, then optionally match by qualified name.

### 4.2 Wiring

**`MyTextDocumentService.java`** — override three default methods:

```java
@Override
public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(TypeHierarchyPrepareParams params) {
    // → PrepareTypeHierarchyCommand
}

@Override
public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(TypeHierarchySupertypesParams params) {
    // → SupertypesCommand
}

@Override
public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(TypeHierarchySubtypesParams params) {
    // → SubtypesCommand
}
```

**`LspServer.java`** — enable capability:
```java
it.setTypeHierarchyProvider(true);
```

### 4.3 IntelliJ APIs Used

| API | Purpose |
|-----|---------|
| `PsiTreeUtil.getParentOfType(element, PsiClass.class)` | Find enclosing class from position |
| `PsiClass.getSuperClass()` | Direct superclass |
| `PsiClass.getInterfaces()` | Direct interfaces |
| `ClassInheritorsSearch.search(psiClass)` | Find all subclasses |
| `PsiClass.getQualifiedName()` | Detail for item |

## 5. xlsp Client Implementation Plan

### 5.1 `type-hierarchy` Operation

```
xlsp type-hierarchy <symbol> in <file> [--supertypes | --subtypes]
```

Behavior:
- Without `--supertypes`/`--subtypes`: prepare + return root item + both directions
- With `--supertypes`: resolve root, then query supertypes
- With `--subtypes`: resolve root, then query subtypes

Output: flattened list of `TypeHierarchyItem` with depth/level indicator.

## 6. Implementation Order

```
[Phase 1] Server: PrepareTypeHierarchyCommand  → rebuild, test
[Phase 2] Server: SupertypesCommand           → rebuild, test
[Phase 3] Server: SubtypesCommand             → rebuild, test
[Phase 4] Wiring: MyTextDocumentService       → rebuild, test
[Phase 5] Build + verify with unit tests
```

## 7. Testing

### Unit Tests (Java)
- `PrepareTypeHierarchyCommandTest` — verify class resolution + item creation
- `SupertypesCommandTest` — verify superclass + interface hierarchy
- `SubtypesCommandTest` — verify inheritor search

### Manual Verification
```
# Prepare type hierarchy for a class
xlsp type-hierarchy "DocumentSymbol" in "src/DocumentSymbol.java"

# Get supertypes
xlsp type-hierarchy supertypes <item-id>

# Get subtypes
xlsp type-hierarchy subtypes <item-id>
```

## 8. Acceptance Criteria

1. `prepareTypeHierarchy` returns a valid `TypeHierarchyItem` for a class at cursor position
2. `typeHierarchy/supertypes` returns superclass and interfaces
3. `typeHierarchy/subtypes` returns all direct subclasses
4. Items include correct: name, kind, uri, range, selectionRange, detail, data
5. Resolution from `data` map works across `prepare` → `supertypes`/`subtypes` round-trip
6. Java interfaces, enums, annotations, classes all resolve correctly
7. Kotlin classes resolve correctly via the same PSI tree walk
8. Graceful empty response when no type is found at cursor
