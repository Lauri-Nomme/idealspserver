# PRD: Semantic Search via IDEA Structural Search

## Status

**Implemented** — `textDocument/semanticSearch` custom LSP method, xlsp CLI integration, constraint support, and 15 unit/feature/integration tests

---

## 1. Problem

AI agents need to search code by **structure**, not text. Examples:

- "Find all methods that return `Optional`"
- "Find all `catch` blocks catching `IOException`"
- "Find all fields of type `AtomicReference`"
- "Find all method calls with `null` literal argument"
- "Find all `if (x == null)` patterns"

Current tools:
| Tool | What it does | Limitation |
|------|-------------|-----------|
| `grep` | Text patterns | Matches comments, strings, false positives on names |
| `xlsp references` | Symbol usages | Requires exact symbol name, no structural constraints |
| `xlsp symbols` | File structure | Returns declaration sites only |

**None can answer**: "Find all methods returning `Optional<String>`" without knowing the symbol name.

## 2. Solution: Use IDEA Structural Search via Reflection

### 2.1 Why Reflection

IDEA's Structural Search (SSR) is the mature, battle-tested solution for this exact problem. It:
- Understands PSI structure, not just text
- Has ~50 predefined templates (Java, Kotlin, Python, etc.)
- Has a powerful constraint system (type filters, regex, script constraints)
- Gets automatically updated with each IDEA release

The catch: SSR has **no public programmatic API**. All entry points go through the UI dialog (`StructuralSearchDialog` / `StructuralSearchAction`). Using it programmatically requires reflection.

### 2.2 Architecture

```
Agent query: "methods returning Optional"
    ↓
xlsp semantic "methods returning Optional"
    ↓
Pattern construction:
  - Map natural query → SSR pattern template
  - Apply type constraints ($ReturnType$ = "Optional")
    ↓
Reflection-based SSR invocation:
  - StructuralSearchProfile.createPatternTree() → PsiElement[]
  - Compile pattern via internal visitor
  - Traverse project files
  - Collect matches (PsiElement → match location)
    ↓
Return: [{file, line, column, context, matchedText}]
```

### 2.3 Key Classes (accessed via reflection)

| Class | Role |
|-------|------|
| `StructuralSearchProfile` (internal) | Creates PSI pattern tree from text template |
| `JavaStructuralSearchProfile` | Java-specific pattern compilation |
| `MatchOptions` | Search configuration (pattern text, file type, scope) |
| `CompiledPattern` | Compiled search pattern |
| `GlobalMatchingVisitor` | Visitor that matches pattern against PSI |
| `SearchContext` | Provides project + file access |
| `StructuralSearchUtil.getProfileByLanguage()` | Gets language profile |

### 2.4 Pattern Construction

Agents write **natural patterns** that map to SSR templates:

```
Agent: "methods returning Optional"
→ SSR pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$);"
→ Constraint on $ReturnType$: text filter = "Optional"
→ Scope: project files (*.java)
```

More complex (via script constraint):
```
Agent: "methods that throw IOException"
→ SSR pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$) throws $Exception$;"
→ Script constraint on $Exception$:
  "import com.intellij.psi.*; __context__.getText().contains('IOException')"
```

### 2.5 Query-to-Pattern Mapping

| Agent Query | SSR Pattern |
|-------------|-------------|
| `methods returning <type>` | `$ReturnType$ $MethodName$($ParameterType$ $Parameter$);` |
| `fields of type <type>` | `$Type$ $FieldName$;` |
| `catch blocks catching <exception>` | `catch ($ExceptionType$ $e$) {$Statement*;}` |
| `null checks` | `if ($Expr$ == null) {$Statement*;}` |
| `method calls with null arg` | `$Method$($Arg*$, null, $Arg2*$)` |
| `new statements creating <type>` | `new $Type$($ParameterType$ $Parameter$)` |

The xlsp tool provides a **pattern builder DSL** that translates these natural queries into SSR patterns.

## 3. Implementation

### 3.1 Files to Create

```
server/src/main/java/org/rri/ideals/server/semantic/
  SemanticSearchCommand.java          # Main LSP command
  SemanticSearchUtil.java            # Reflection-based SSR invocation
  SemanticPatternBuilder.java       # Query → SSR pattern translation
  SemanticSearchParams.java         # LSP params (query, scope, constraints)
```

### 3.2 SemanticSearchUtil — Reflection Entry Points

```java
public class SemanticSearchUtil {
  // Get SSR profile for Java
  private static StructuralSearchProfile getProfile(Project project) {
    // StructuralSearchUtil.getProfileByLanguage(language)
  }

  // Compile pattern text into PSI tree
  private static CompiledPattern compilePattern(Project project, String patternText) {
    // profile.createPatternTree(text, ...) → PsiElement[]
    // profile.compile(elements, visitor)
  }

  // Run search and collect matches
  public static List<SemanticMatch> search(Project project, SemanticPattern pattern) {
    // Create SearchContext via reflection
    // Traverse files in scope
    // Collect matches into SemanticMatch (file, range, matchedText)
  }
}
```

### 3.3 LSP Custom Methods

```
textDocument/semanticSearch
  → Custom method using SSR under the hood
  → Params: pattern: string, scope: string (project|file|dir), constraints: map
  → Result: SemanticMatch[]

textDocument/semanticSearch/listTemplates
  → Lists predefined SSR templates (JavaPredefinedConfigurations)
  → Result: {name, pattern, description}[]
```

### 3.4 xlsp Integration

```bash
xlsp semantic "methods returning Optional"           # → SSR pattern + execute
xlsp semantic "fields of type AtomicReference"       # → field SSR pattern
xlsp semantic "catch blocks catching IOException"     # → catch SSR pattern
xlsp templates                                       # → list predefined templates
```

### 3.5 Example Agent Workflow

```
Agent: "I need to refactor all methods that return Optional to return Optional<String>"
Agent: xlsp semantic "methods returning Optional"
→ [{file: "UserService.java", line: 42, matchedText: "Optional getUser()"},
   {file: "UserService.java", line: 58, matchedText: "Optional findById()"}, ...]
Agent: xlsp semantic "methods returning Optional" --context 2
→ Returns with surrounding code context
Agent: [applies edits to each method signature]
```

## 4. Feasibility Assessment

### 4.1 Technical Feasibility: **HIGH**

- SSR is already installed (part of IDEA, bundled plugin `com.intellij.java` depends on SSR)
- PSI traversal APIs are stable and well-understood
- Pattern compilation follows predictable flow: `text → PsiElement[] → CompiledPattern → MatchVisitor`
- Predefined templates provide examples of pattern construction

### 4.2 Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Reflection breakage on IDEA version upgrade | Medium | SSR APIs rarely change; pin to stable method names; test on each IDEA release |
| Performance (large project traversal) | Medium | Add progress reporting; use `SearchRequestCollector` for cancellation; scope to file/dir when given |
| Pattern compilation failure | Low | Return clear error with pattern syntax hint; validate pattern before running |
| Script constraint evaluation | Medium | Start with text-based constraints only; defer Groovy script support to v2 |

### 4.3 Effort Estimate

| Phase | Task | Effort |
|-------|------|--------|
| 1 | SSR reflection utilities (pattern tree + compile) | 1-2 days |
| 2 | File traversal + match collection | 1 day |
| 3 | LSP custom method registration | 0.5 day |
| 4 | xlsp CLI integration | 0.5 day |
| 5 | Tests + error handling | 1-2 days |
| **Total** | | **4-6 days** |

## 5. Relationship to Existing Features

### 5.1 Complements, Not Replaces

- `xlsp references` — symbol-level usage search → **still needed** for "where is `Foo` used"
- `xlsp symbols` — declaration navigation → **still needed** for file structure
- `xlsp define` — go to definition → **still needed** for jumping to definitions
- `xlsp semantic` — **new** structural pattern search → fills the gap neither grep nor LSP can fill

### 5.2 Data Flow Integration

SSR and data flow analysis are complementary:
- **SSR** finds all code fragments matching a structural pattern
- **Data flow** traces how values propagate from/to matched fragments
- Could chain: `xlsp semantic "fields of type List" | xlsp dataflow from <each field>`

## 6. Open Questions

1. **Should we implement SSR replace (structural replace) too?** — v1 scope: search only. Replace adds complexity (preview, confirmation flow).

2. **Script constraints (Groovy) vs text constraints only?** — Start with text constraints (type name, regex on name). Script constraints require Groovy evaluation context setup.

3. **Language support** — Start with Java only (matches project). Add Kotlin/Python in v2.

4. **Predefined templates or custom patterns?** — Provide both: `xlsp templates` lists IDEA's built-in templates; `xlsp semantic <pattern>` accepts custom patterns.

5. **Should we cache compiled patterns?** — Yes, patterns are expensive to compile. Cache by pattern text + project + scope hash.

## 7. Acceptance Criteria

1. **Natural query → results**: `xlsp semantic "methods returning Optional"` returns all matching methods across the project
2. **Correct PSI matching**: Results are actual code constructs, not text matches
3. **Type constraints work**: `$ReturnType$` constrained to `Optional` matches `Optional`, `Optional<String>`, etc.
4. **Predefined templates**: `xlsp templates` returns at least 30 predefined patterns (Java)
5. **Performance**: Project search completes within 30s for typical plugin project (~1000 files)
6. **Graceful degradation**: Invalid pattern returns error with suggestion
7. **No SSR UI required**: Search executes headlessly, no dialogs
8. **Incremental updates**: Pattern re-runs on changed files (via didChange)

## 8. References

- [Structural Search in IntelliJ IDEA](https://www.plugin-dev.com/intellij-use/navigation/search-structurally/) — detailed usage guide
- [Structural Search and Replace docs](https://www.jetbrains.com/help/idea/structural-search-and-replace.html) — official docs
- [Structural Search examples](https://www.jetbrains.com/help/idea/structural-search-and-replace-examples.html) — pattern examples
- `JavaPredefinedConfigurations.java` — predefined template list (50+ patterns)
- `StructuralSearchProfile.java` — pattern compilation entry point
