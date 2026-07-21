# Project Structure - PRD

## 1. Overview

Project Structure is a custom LSP extension that provides an AI agent with a high-level architectural view of the codebase. Unlike file-level operations (document symbols, workspace symbols) that return individual declarations, this returns the **skeleton** of the project — modules, their dependencies, entry points, and source layout.

This is inspired by capabilities in code-graph-mcp, codebase-memory-mcp, and Seer-MCP (see competitive analysis §5.3.6), which all provide architecture-level understanding that xlsp lacks.

### Use Cases for AI Agents

| Scenario | Without this feature | With this feature |
|----------|--------------------|--------------------|
| "Understand this project" | Read `build.gradle`, `pom.xml`, directory listings, piece together manually | Single call returns modules, deps, entry points, package layout |
| "Where's the entry point?" | grep for `main`, guess from conventions | Explicit `main()` and annotated entry points returned |
| "How do modules depend?" | Read build files, resolve transitive deps manually | Dependency graph with direct edges |
| "Where to add a new class?" | Navigate directories, read source roots | Source root layout with suggested packages |
| "What frameworks are used?" | Scan imports across files | Module SDK, facet types, library deps inferred |

---

## 2. Protocol Design

Since this is not a standard LSP feature, it uses a custom method under the `idealsp/` namespace:

### 2.1 Server Capability

No separate capability registration needed — the method is handled directly by `LspServer.java`'s JSON-RPC dispatch if it receives a matching method name.

### 2.2 Request

**Method:** `idealsp/projectStructure`

**Params:**

```typescript
interface ProjectStructureParams {
    /**
     * Optional scope filter. Defaults to "all".
     * - "modules" — only module list + dependency graph
     * - "source" — only source roots and package layout
     * - "entry" — only entry points
     * - "all" — everything
     */
    scope?: "all" | "modules" | "source" | "entry";
}
```

### 2.3 Response

```typescript
interface ProjectStructureResult {
    /** The workspace root path */
    workspaceRoot: string;

    /** Project-level info */
    project: {
        name: string;
        basePath: string;
        sdk?: string;
        languageLevel?: string;
    };

    /** All modules in the project */
    modules: ModuleInfo[];

    /** Pre-computed dependency edges (adjacency list) */
    dependencyGraph: {
        edges: { from: string; to: string; scope: "COMPILE" | "TEST" | "RUNTIME" | "PROVIDED" }[];
    };

    /** Entry points discovered across all modules */
    entryPoints: EntryPoint[];

    /** Source root layout per module */
    sourceLayout: SourceRootInfo[];
}

interface ModuleInfo {
    name: string;
    type: "JAVA_MODULE" | "ANDROID_MODULE" | "WEB_MODULE" | "PLUGIN_MODULE";
    group?: string;
    contentRoots: string[];
    sdk?: string;
    languageLevel?: string;
    /** Facets detected (Spring, JPA, Android, etc.) */
    facets: string[];
    /** Library dependencies (name only, excludes transitive) */
    libraryDependencies: string[];
}

interface EntryPoint {
    kind: "main" | "test" | "application" | "servlet" | "listener";
    name: string;
    qualifiedName: string;
    file: string;
    line: number;
    module: string;
}

interface SourceRootInfo {
    module: string;
    path: string;
    type: "SOURCE" | "TEST" | "RESOURCE" | "TEST_RESOURCE" | "GENERATED";
    rootFor: string;
    /** Top-level packages found in this source root */
    packages: string[];
}
```

### 2.4 JSON-RPC Example

**Request:**
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "idealsp/projectStructure",
    "params": {
        "scope": "all"
    }
}
```

**Response (skeleton):**
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "workspaceRoot": "file:///home/user/project",
        "project": {
            "name": "my-app",
            "basePath": "/home/user/project",
            "sdk": "corretto-17"
        },
        "modules": [
            {
                "name": "my-app.main",
                "type": "JAVA_MODULE",
                "contentRoots": ["/home/user/project/src/main"],
                "sdk": "corretto-17",
                "facets": ["Spring"],
                "libraryDependencies": ["spring-boot-starter-web", "jackson-core"]
            },
            {
                "name": "my-app.test",
                "type": "JAVA_MODULE",
                "contentRoots": ["/home/user/project/src/test"],
                "facets": [],
                "libraryDependencies": ["junit-jupiter", "mockito-core"]
            }
        ],
        "dependencyGraph": {
            "edges": [
                { "from": "my-app.test", "to": "my-app.main", "scope": "TEST" }
            ]
        },
        "entryPoints": [
            {
                "kind": "main",
                "name": "main",
                "qualifiedName": "com.example.MyApp.main",
                "file": "file:///home/user/project/src/main/java/com/example/MyApp.java",
                "line": 8,
                "module": "my-app.main"
            }
        ],
        "sourceLayout": [
            {
                "module": "my-app.main",
                "path": "/home/user/project/src/main/java",
                "type": "SOURCE",
                "rootFor": "Source roots",
                "packages": ["com.example", "com.example.model", "com.example.service"]
            }
        ]
    }
}
```

---

## 3. Server Implementation Design

### 3.1 Architecture

```
Client Request (idealsp/projectStructure)
    ↓
LspServer.java — route to a new LspCommand subclass
    ↓
ProjectStructureCommand.java (implements LspCommand<Void> — no file path needed)
    ↓
IntelliJ Module / PSI APIs
    ↓
Structured JSON result
```

The key difference from existing commands: this is a **project-level** operation, not a file-level one. It doesn't take a file path or position — it operates on the entire project. This means it needs a variant of `LspCommand.runAsync()` that doesn't require a file path.

### 3.2 Files to Create/Modify

#### New Files:

- **`server/.../commands/NoOpFileLspCommand.java`** — Variant of `LspCommand` for commands that don't need a file path (no `LspPath` parameter, no `PsiFile` in `ExecutorContext`). Needed because all current commands take a file path.
- **`server/.../projectstructure/ProjectStructureCommand.java`** — Main command class
- **`server/.../projectstructure/ModuleCollector.java`** — Collects module info
- **`server/.../projectstructure/EntryPointFinder.java`** — Scans for entry points
- **`server/.../projectstructure/PackageLister.java`** — Lists top-level packages per source root

#### Files to Modify:

- **`LspServer.java`** — Register JSON-RPC handler for `idealsp/projectStructure`
- **`LspCommand.java`** (or its callers) — Support commands without a file path

### 3.3 IntelliJ APIs to Use

| Data | API |
|------|-----|
| Module list | `ModuleManager.getInstance(project).getModules()` |
| Module name | `module.getName()` |
| Content roots | `ModuleRootManager.getInstance(module).getContentRoots()` |
| Source roots | `ModuleRootManager.getInstance(module).getSourceRoots()` |
| Source root type | `SourceFolder.getRootType()` — `TestSources`, `Sources`, `ResourceRoot` |
| SDK | `ModuleRootManager.getInstance(module).getSdk()` |
| Language level | `LanguageLevelProjectExtension.getInstance(project)` |
| Dependencies | `ModuleRootManager.getInstance(module).getDependencies()` |
| Library deps | `OrderEntry` filtering by `LibraryOrderEntry` type |
| Facets | `FacetManager.getInstance(module).getFacets()` — `WebFacet`, `SpringFacet`, `JpaFacet`, etc. |
| Entry points (main) | `PsiManager` + PSI scan for `public static void main(String[])` in each source root |
| Entry points (tests) | PSI scan for `@Test`, `@SpringBootTest`, etc. |
| Packages | `PsiDirectory` walk on source roots, collect first-level children |

### 3.4 LspCommand Variant for No-File Commands

Current `LspCommand.runAsync()` requires an `LspPath`:

```java
public @NotNull CompletableFuture<@Nullable R> runAsync(
    @NotNull Project project, @NotNull LspPath path)
```

For project-level commands, add an overload:

```java
public @NotNull CompletableFuture<@Nullable R> runAsync(
    @NotNull Project project)
```

This variant skips the file-resolution step and directly calls `execute()` with an `ExecutorContext` that has a null `PsiFile`. The `execute()` method in `ProjectStructureCommand` ignores the `PsiFile` and works directly with the `Project`.

### 3.5 ProjectStructureCommand.execute()

```java
@Override
protected ProjectStructureResult execute(@NotNull ExecutorContext ctx) {
    Project project = ctx.getProject();

    ProjectStructureResult result = new ProjectStructureResult();
    result.setWorkspaceRoot(project.getBasePath());
    result.setProject(collectProjectInfo(project));
    result.setModules(ModuleCollector.collect(project));
    result.setDependencyGraph(ModuleCollector.buildGraph(project));
    result.setSourceLayout(PackageLister.list(project));
    result.setEntryPoints(EntryPointFinder.find(project));
    return result;
}
```

### 3.6 Entry Point Discovery

**main() methods:**
- Search all `PsiJavaFile` instances in source roots
- Find `PsiMethod` with name "main", `public static void`, single `String[]` param
- Match the name via `PsiKeyword.PUBLIC`, `PsiKeyword.STATIC`, `PsiKeyword.VOID`, identifier "main"

**Application classes (framework-specific):**
- Scan for classes annotated with `@SpringBootApplication`, `@MicronautApplication`, `@QuarkusApplication`, `@Application`
- Alternatively, just call `JavaPsiFacade.findClass()` to check known framework entry-point annotations
- Since the ideal server doesn't have full classpath, this may be unreliable — fall back to listing all classes with a `main()` method

**Test suites:**
- PSI search for `@Test`, `@ParameterizedTest`, `@SpringBootTest` annotations
- Classes extending `TestCase`, `AbstractTest`

### 3.7 Package Listing

Walk each source root directory and collect immediate child packages (first-level directories that contain `.java` files).

This avoids walking the entire tree — just the top-level packages give a good overview. Full package listing would be too expensive.

### 3.8 Handling Large Projects

- The response should be IO-efficient — no file contents are read
- For very large projects (>100 modules), consider truncating to top-N with a `truncated: true` flag
- Package listing can be lazy (only first-level children)
- Dependency graph can omit transitive edges

---

## 4. Test Cases for test_lsp_comprehensive.py

### 4.1 Test Data

No new test files needed — the existing project (`idealspserver/git/`) is the test subject.

### 4.2 Test Cases

```python
# Test N: projectStructure (default scope = "all")
resp = send_and_recv(sock, "idealsp/projectStructure", {"scope": "all"}, N)
assert resp and "result" in resp
result = resp["result"]

# Verify project info
assert result["project"]["name"] is not None
assert result["project"]["basePath"] is not None
assert result["workspaceRoot"] is not None

# Verify at least one module exists
assert len(result["modules"]) > 0
# Module has required fields
module = result["modules"][0]
assert module["name"]
assert module["type"] in ["JAVA_MODULE", ...]
assert len(module["contentRoots"]) > 0

# Verify dependency graph exists
assert "edges" in result["dependencyGraph"]

# Verify source layout exists
assert len(result["sourceLayout"]) > 0
# Each has required fields
layout = result["sourceLayout"][0]
assert layout["module"]
assert layout["path"]
assert layout["type"] in ["SOURCE", "TEST", ...]
assert "packages" in layout

# Verify entry points exist (our project has a main class)
assert len(result["entryPoints"]) > 0
ep = result["entryPoints"][0]
assert ep["name"]
assert ep["qualifiedName"]
assert ep["file"]
assert ep["module"]
```

---

## 5. Implementation Order

| Step | What | Dependencies |
|------|------|-------------|
| 1 | Add `runAsync(Project)` overload to `LspCommand` | None |
| 2 | Create `ProjectStructureCommand` (basic shell) | Step 1 |
| 3 | Implement `ModuleCollector` — module list + deps | None |
| 4 | Implement `PackageLister` — source root walk | Step 3 |
| 5 | Implement `EntryPointFinder` — main/Test scan | Step 3 |
| 6 | Wire into `LspServer.java`'s JSON-RPC dispatch | Step 2 |
| 7 | Add Python tests | Step 6 |
| 8 | Manual validation with `xlsp` or `python3 -c` script | Step 6 |

---

## 6. Open Questions

1. **Should the response be paginated for large projects?** The IntelliJ platform SDK plugin project has ~20 modules. For a workspace with 100+ Gradle subprojects, the response could be large. Add a `truncated` flag?

2. **How reliable is entry-point scanning without full classpath?** Framework annotations (`@SpringBootApplication`) won't resolve without the dependency jars on the classpath. Fallback to `main()` method detection which is purely structural.

3. **Should package listing be recursive?** First-level is enough for orientation. Recursive listing could be a separate scope flag.

4. **Should we include `.iml` file-based module info or Gradle project info?** Prefer runtime module model — it reflects the actual loaded configuration.

5. **Dependency graph — direct or transitive?** Direct only. Transitive makes the response too large and is seldom useful for orientation.
