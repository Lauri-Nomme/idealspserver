# PRD: Run Inspection by Name via LSP

## Status

**Completed.** Both `$/inspection/list` and `$/inspection/runByName` implemented.

### Completed
- `$/inspection/list` LSP extension — list/search all registered inspections by shortName/displayName
- `$/inspection/runByName` LSP extension — run a single inspection on a file, returns LSP Diagnostics
- `InspectionService` (Java) — `listInspections(query)` + `runByName(psiFile, name)`
- Handles: LocalInspectionTool, GlobalSimpleInspectionTool (with ProblemsHolder workaround), GlobalInspectionTool
- `InspectionInfo` + `InspectionRunByNameParams` POJOs
- Unit tests (6 pass) + Comprehensive Python tests (tests 23-27)
- xlsp CLI: `inspect-list` and `inspect` operations
- opencode tool wrapper updated

### Known limitation
Single-file inspection via `runInspectionOnFile` requires proper PSI/JDK context. Results may be empty if the project doesn't have full SDK classpath configured. The full daemon pass (`runMainPasses`) handles this better but runs all inspections.

---

## Usefulness for Agentic Use

**High.**

An AI agent benefits from targeted inspections:

| Scenario | Example |
|----------|---------|
| **Pre-commit check** | Run only "unused imports" + "unused variable" before a final commit |
| **Targeted refactoring** | After removing a method parameter, run "unused parameter" to verify no leftovers |
| **Code quality audit** | Run "deprecated API usage" across the project to find migration targets |
| **Selective noise reduction** | Skip spelling/comment inspections when only interested in logic errors |
| **Performance** | Running 1-2 inspections is faster than the full daemon pass (which queues all ~200 enabled inspections) |
| **Workflow injection** | Agent runs "ConstantConditions" (nullability) only on changed files before proposing a fix |

Compared to current `diagnostics` (which dumps everything), `inspect <name>` gives the agent **precision and speed**.

---

## Feasibility

**Feasible.** IntelliJ Platform provides stable APIs already available in the 2026.1 SDK used by this project. No API additions or upstream changes needed.

### Relevant IntelliJ APIs

| API | Package | Role |
|-----|---------|------|
| `InspectionProfile` | `com.intellij.codeInspection` | Get configured inspections, iterate by short name |
| `InspectionProfileEntry` | `com.intellij.codeInspection` | Base class for inspection tools — has `getShortName()` |
| `LocalInspectionTool` | `com.intellij.codeInspection` | Single-file inspection; call `inspection.visitFile()` or `InspectionEngine.runInspectionOnFile()` |
| `GlobalInspectionTool` | `com.intellij.codeInspection` | Project-wide (out of scope for v1) |
| `InspectionManager` | `com.intellij.codeInspection` | Creates context, collects `ProblemDescriptor` results |
| `ProblemDescriptor` | `com.intellij.codeInspection` | Result of an inspection run (text range + description + fixes) |
| `InspectionEngine` | `com.intellij.codeInspection.ex` | `runInspectionOnFile()` — simpler entry point |

### Approach

```
LSP extension: $/inspection/runByName
  Input: file URI + inspection short name (e.g. "unused", "unchecked", "ConstantConditions")
  Execution:
    1. Look up InspectionProfile for the project
    2. Find the tool with matching getShortName() / getID()
    3. Get ProblemDescriptors via InspectionEngine.runInspectionOnFile(file, tool, manager)
    4. Convert ProblemDescriptor → LSP Diagnostic format
    5. Return diagnostics (filtered to this one inspection)
  Output: LSP Diagnostics (same format as publishDiagnostics)
```

### Required code changes

| Layer | What | Effort |
|-------|------|--------|
| **Java server** | New LSP extension method `$/inspection/runByName` or `textDocument/runInspection` | 1 new command class + integration into MyTextDocumentService |
| **Java server** | `InspectionService` — lookup profile, match short name, run `InspectionEngine` | ~150 LoC |
| **xlsp tool (TS)** | New operation `inspect <name> [in <file>]` | ~50 LoC, reuses existing symbol-resolver and output formatting |
| **Tests** | Unit test: known inspections ("unused", "unchecked") on test fixture | ~100 LoC in `DiagnosticsTest.java` |

### Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Inspection short names are case-sensitive and sometimes surprising | Low | Return list of matching names on miss; xlsp tool can fuzzy-match |
| Some inspections require module-level data (classpath, dependencies) | Medium | Guard with `ModuleUtilCore.findModuleForFile()`; degrade gracefully |
| InspectionProfile initialization may need COMPONENT_LOADED project state | Medium | Already handled by session-keepalive; add explicit readiness check |
| Not all inspections are LocalInspectionTool (some are GlobalInspectionTool, batch inspections, etc.) | Low | v1 scope: LocalInspectionTool only; add support for others in v2 |
| Performance of single inspection vs. batch (may not save time due to PSI setup overhead) | Low | Benchmark; if no gain, pivot to filtering results from full daemon pass instead |

---

## Inspection Name Discovery

Before an agent can run an inspection by name, it must know what's available. IntelliJ ships with hundreds of inspections (Java alone has ~170) and plugins add more. Names are technical short IDs like `unused`, `ConstantConditions`, `unchecked`, `WeakerAccess` — not end-user labels.

### LSP Extension: `$/inspection/list`

```
Request:  inspection/list
Response: {
  inspections: [
    {
      shortName: "unused",           // canonical ID for runByName
      displayName: "Unused declaration",
      group: "Java | Declaration redundancy",
      severity: "WARNING",
      enabled: true,
      description: "Reports declarations (classes, methods, fields) that are never used"
    },
    ...
  ]
}
```

Still missing: how to get the **list** itself. See below.

### Option: New custom LSP method `$/inspection/list`

```
Request:  inspection/list { query?: "unused" }
Response: { inspections: [{ shortName, displayName, group, enabled, description }] }
```

Optional `query` parameter filters by substring match on shortName or displayName — allows `xlsp inspect-search "null"` to find `ConstantConditions` without knowing the exact name.

### Server-side API

```java
// IntelliJ already provides this:
InspectionProfile profile = InspectionProjectProfileManager.getInstance(project).getCurrentProfile();
List<InspectionToolWrapper<?, ?>> tools = profile.getInspectionTools(null);

// Each InspectionToolWrapper has:
//   .getShortName()         → "unused"
//   .getDisplayName()       → "Unused declaration"
//   .getGroupDisplayName()  → "Declaration redundancy"
//   .isEnabled()            → true/false
```

The list is static per project profile — can be cached (invalidate on profile change).

### Why the agent needs search, not just a dump

| Operation | Strategy |
|-----------|----------|
| **Known exact name** | `xlsp inspect unused in Foo.java` |
| **Human description** → name | `xlsp inspect-search "null pointer"` → finds `ConstantConditions` |
| **Browse category** | `xlsp inspect-search "redundancy"` → lists all redundancy inspections |
| **Verify what's enabled** | `xlsp inspect-list` (all, with enabled flag) |

---

## LSP Integration Options

### Option A: New custom LSP methods (Recommended)

Three custom methods:

```
1. inspection/list { query?: string }
   → { inspections: [{ shortName, displayName, group, enabled, description }] }

2. inspection/runByName { textDocument: { uri }, name: "unused" }
   → [ { range, severity, message, code: "unused", ... } ]

3. $/progress notifications (reuse existing pattern) for long-running inspections
```

Clean separation from LSP spec, matches existing project conventions.

### Option B: Filter existing publishDiagnostics

Server-side: run full daemon pass (as today) but tag each diagnostic with the inspection short name that produced it, then filter client-side. Less work on the Java side but no performance benefit and no discovery capability.

**Recommendation: Option A** — true targeted execution yields performance benefits, discovery is essential for agentic use, and the pattern matches the xlsp tool's design of precise, named operations.

---

## xlsp Tool Interface (proposed)

```
xlsp inspect <name> [in <file-or-dir>]
xlsp inspect-list [--query <substring>]    # list/search available inspections

Examples:
  xlsp inspect unused in src/main/java/Foo.java
  xlsp inspect "deprecated api usage" in server/src/
  xlsp inspect-list                         # all inspections
  xlsp inspect-list --query "null"          # find inspections about nullability
  xlsp inspect-list --query "unused"        # find inspections about unused code
```

Output (run inspection):
```json
{
  "success": true,
  "operation": "inspect",
  "query": "unused",
  "file": "src/main/java/Foo.java",
  "count": 3,
  "results": [
    {"severity": "warning", "message": "Field 'bar' is never used", "line": 12, "column": 5},
    {"severity": "warning", "message": "Import 'java.util.List' is never used", "line": 3, "column": 1}
  ]
}
```

Output (list/search):
```json
{
  "success": true,
  "operation": "inspect-list",
  "query": "null",
  "count": 5,
  "results": [
    {"shortName": "ConstantConditions", "displayName": "Constant conditions & exceptions", "group": "Java | Probable bugs", "enabled": true},
    {"shortName": "DataFlowIssue", "displayName": "Data flow issue", "group": "Java | Probable bugs", "enabled": true}
  ]
}
```

---

## Implementation Order

1. **Java server**: `InspectionService.listInspections()` — query and return inspection metadata
2. **Java server**: Custom LSP method `$/inspection/list` in MyTextDocumentService
3. **Java server**: `InspectionService.runByName()` — lookup + run by short name
4. **Java server**: Custom LSP method `$/inspection/runByName`
5. **Java server**: Register both capabilities in `InitializeResult`
6. **xlsp tool**: `operations/inspect-list.ts` (list/search inspections)
7. **xlsp tool**: `operations/inspect.ts` (run inspection by name)
8. **Tests**: `DiagnosticsTest.testListInspections()` + `testRunInspectionByName()`
9. **xlsp tool**: Integration tests with live server

---

## Decision

| Criterion | Assessment |
|-----------|-----------|
| Usefulness | **High** — gives agent fine-grained control |
| Feasibility | **High** — stable APIs already in SDK, pattern matches existing codebase |
| Effort | **3-5 days** (server + tool + tests) |
| Risk | **Low** — well-contained, no breaking changes |
