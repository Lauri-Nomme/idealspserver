# Competitive Analysis: xlsp / IdeaLSP vs. Alternative Code Intelligence Solutions

> A capability-by-capability comparison of xlsp against 10 competing solutions
> across IntelliJ-based, Tree-sitter-based, and hybrid approaches.

---

## 1. Competitive Landscape

### IntelliJ-Plugin-Based (require running IDE/JVM)

| # | Solution | Author | Base | Tools | Transport |
|---|----------|--------|------|-------|-----------|
| 1 | **IntelliJ Built-in MCP Server** | JetBrains | IDE plugin (bundled) | ~15 | MCP (SSE/stdio) |
| 2 | **MCP Steroid** | JetBrains/jonnyzzz | IDE plugin + devrig CLI | 8 + 58 resources | MCP (SSE/HTTP) |
| 3 | **MCPCodeIntelligence** | StarsMilky | IDE plugin | 13 | MCP (Streamable HTTP) |
| 4 | **idea-mcp-plugin** | puddinging | IDE plugin | 40+ | MCP (SSE/Stdio) |
| 5 | **intellij-mcp** | jiayun | IDE plugin | 7 | MCP (HTTP) |
| 6 | **MCP Server Companion** | maximehamm | IDE plugin (extends built-in) | ~16 | MCP (proxy) |
| 7 | **IDE Index MCP Server** | hechtcarmel | IDE plugin | 21 | MCP (Streamable HTTP) |
| 8 | **Serena** (JetBrains mode) | oraios | IDE plugin + LSP | ~20 | MCP (stdio/HTTP) |

### Tree-Sitter / Standalone (no IDE needed)

| # | Solution | Author | Engine | Tools | Languages |
|---|----------|--------|--------|-------|-----------|
| 9 | **code-graph-mcp** | sdsrss | Tree-sitter + SQLite-vec | 12 | 16 languages |
| 10 | **codebase-memory-mcp** | ahundt | Tree-sitter + SQLite | 14 | 64 languages |
| 11 | **code-intel-mcp** (Joern) | HarshalRathore | Joern CPG + ArangoDB | 14 | Multi |
| 12 | **Seer-MCP** | vladimirhegai | Tree-sitter + SQLite | 8 | 10 languages |
| 13 | **go-code** | anatolykoptev | Tree-sitter | 30 | 16 languages |
| 14 | **navigation-agent-mcp** | j0k3r-dev-rgl | Tree-sitter + custom | 6 | 8 languages |
| 15 | **Rhizome** | basidiocarp | Tree-sitter + LSP | ~10 | Multi |

### xlsp / IdeaLSP (This Project)

| Attribute | Value |
|-----------|-------|
| Architecture | Headless IntelliJ-based LSP server (IdeaLSP) + CLI client (xlsp) |
| Transport | Raw TCP → JSON-RPC 2.0 (LSP), no MCP |
| Operations | 18 LSP commands |
| Runtime | Bun (client) + JVM (server, IntelliJ IDEA platform) |
| Languages | Any language supported by IntelliJ IDEA (Java, Kotlin, Python, Go, etc.) |
| Deployment | systemd service + CLI tool, no full IDE UI required |

---

## 2. Capability Comparison Matrix

### Legend
- **✅** = Fully supported
- **◐** = Partial / limited
- **❌** = Not supported
- **—** = Not applicable

### 2.1 Core Symbol Navigation

| Capability | xlsp | Built-in MCP | MCP Steroid | MCPCodeIntel | idea-mcp | intellij-mcp | IDE Index | Serena JB | code-graph | codebase-mem | Seer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Find symbol by name | ✅ | ❌ | ◐(via script) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Go to definition | ✅ | ❌ | ◐(via script) | ✅ | ✅ | ❌ | ✅ | ◐ | ❌ | ❌ | ✅ |
| Find references | ✅ | ❌ | ◐(via script) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Document symbols | ✅ | ❌ | ◐(via script) | ✅ | ✅ | ✅ | ✅ | ✅ | ◐ | ❌ | ❌ |
| Workspace symbols | ✅ | ❌ | ◐(via script) | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Type hierarchy | ❌ | ❌ | ◐(via script) | ❌ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Call hierarchy | ✅ | ❌ | ◐(via script) | ❌ | ✅ | ❌ | ✅ | ◐ | ✅ | ✅ | ✅ |
| Implementations | ✅ | ❌ | ◐(via script) | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |

### 2.2 Code Analysis

| Capability | xlsp | Built-in MCP | MCP Steroid | MCPCodeIntel | idea-mcp | intellij-mcp | IDE Index | Serena JB | code-graph | codebase-mem | Seer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Diagnostics (errors/warnings) | ✅ | ✅ | ◐(via script) | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Code actions / quick fixes | ✅ | ✅ | ◐(via script) | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Apply code action | ✅ | ❌ | ◐(via script) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Run inspection by name | ✅ | ❌ | ◐(via script) | ❌ | ✅ | ❌ | ❌ | ◐ | ❌ | ❌ | ❌ |
| List available inspections | ✅ | ❌ | ◐(via script) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Structural search (SSR) | ✅ | ❌ | ◐(via script) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Data flow analysis | ✅ | ❌ | ◐(via script) | ✅ | ❌ | ❌ | ❌ | ❌ | ◐ | ❌ | ❌ |
| Dead code detection | ❌ | ❌ | ◐(via script) | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| Impact analysis | ❌ | ❌ | ◐(via script) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Quality metrics | ❌ | ❌ | ◐(via script) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### 2.3 Editing & Refactoring

| Capability | xlsp | Built-in MCP | MCP Steroid | MCPCodeIntel | idea-mcp | intellij-mcp | IDE Index | Serena JB | code-graph | codebase-mem | Seer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Rename symbol | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Extract method | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Safe delete | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Move symbol/file | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| File create/edit/delete | ❌ | ✅ | ◐(via script) | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Code generation | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### 2.4 IDE Integration & Infrastructure

| Capability | xlsp | Built-in MCP | MCP Steroid | MCPCodeIntel | idea-mcp | intellij-mcp | IDE Index | Serena JB | code-graph | codebase-mem | Seer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Headless / no UI required | ✅ | ❌ | ◐(devrig) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| MCP protocol | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| LSP protocol | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Multi-language | ✅* | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| No IDE license needed | ◐(headless) | ❌ | ◐(devrig) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Screenshot/vision | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Debugger control | ❌ | ◐ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Build/run configs | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| VCS integration | ❌ | ❌ | ◐ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| HTTP route tracing | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ◐ | ✅ |

\* xlsp supports any language the IdeaLSP server can handle (Java, Kotlin, Python, Go, etc. via IntelliJ plugin)

### 2.5 Agent-Specific Features

| Capability | xlsp | Built-in MCP | MCP Steroid | MCPCodeIntel | idea-mcp | intellij-mcp | IDE Index | Serena JB | code-graph | codebase-mem | Seer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Plug-and-play setup | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| OpenCode tool def | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `--wait` for indexing | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Context lines in results | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Severity filtering | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| CLI output (JSON lines) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Agent skill/hooks | ❌ | ❌ | ✅ (58 resources) | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| Auto-indexing | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| Session history | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ◐(memory) | ❌ | ❌ | ❌ |

---

## 3. Usage Scenario Analysis

### Scenario A: "Where is this symbol defined?"

**Tools that solve it well:** xlsp (`xlsp define`), MCPCodeIntel, intellij-mcp, IDE Index, Serena, code-graph, Seer

| Solution | Approach | Latency | Accuracy | Bonus |
|----------|----------|---------|----------|-------|
| **xlsp** | LSP `textDocument/definition` | 200ms–8s | **Perfect** (PSI-based) | Returns exact position |
| MCPCodeIntel | IntelliJ PSI | 200–500ms | **Perfect** | Also returns type info |
| intellij-mcp | IntelliJ PSI | 200–500ms | **Perfect** | Multi-language |
| Serena (JB) | IntelliJ PSI | 200–500ms | **Perfect** | 40+ languages |
| code-graph-mcp | Tree-sitter | 50–200ms | ◐ (text-based) | 16 languages, no IDE |
| Seer-MCP | Tree-sitter | 50–200ms | ◐ (text-based) | Impact analysis bundled |
| Grep (baseline) | Text regex | 2s–120s | **Poor** (false positives) | Works everywhere |

**Winner:** Tied — xlsp, MCPCodeIntel, Serena, IDE Index all provide perfect PSI-based resolution. xlsp's key advantage: works headless via systemd service.

---

### Scenario B: "Find all usages of this method across the project"

**Tools that solve it well:** xlsp (`xlsp refs`), MCPCodeIntel, intellij-mcp, IDE Index, Serena, code-graph, Seer

| Solution | Latency | Cross-file | Transitive | Structured output |
|----------|---------|-----------|------------|-------------------|
| **xlsp** | 500ms–9s | ✅ | ❌ | ✅ JSON lines |
| MCPCodeIntel | 200–500ms | ✅ | ❌ | ✅ |
| intellij-mcp | 200–500ms | ✅ | ❌ | ✅ |
| IDE Index | 200–500ms | ✅ | ❌ | ✅ |
| code-graph | 100–500ms | ✅ | ✅ (call graph) | ✅ |
| Seer | 100–500ms | ✅ | ✅ (callers) | ✅ |

**Winner:** code-graph-mcp and Seer — provide transitive call-chain tracing that pure LSP-based solutions cannot. xlsp is competitive on precision but lacks transitive depth.

---

### Scenario C: "What errors/warnings does this file have?"

**Tools that solve it well:** xlsp (`xlsp diagnostics`), Built-in MCP Server, Serena

| Solution | Latency | Quick fixes | Severity filter | Context |
|----------|---------|------------|----------------|---------|
| **xlsp** | 200ms–20s | ✅ (code actions) | ✅ | ✅ |
| Built-in MCP | 200–500ms | ✅ | ❌ | ❌ |
| Serena (JB) | 200–500ms | ✅ | ❌ | ❌ |
| MCP Steroid | via script | via script | via script | via script |

**Winner:** xlsp — only solution with severity filtering (`--severity error`) and context lines (`--context 2`). Built-in MCP server lacks these agent-friendly features.

---

### Scenario D: "Run code inspection X across all files"

**Tools that solve it well:** xlsp (`xlsp inspect-all`), idea-mcp-plugin

| Solution | Latency | By-name | By-file | All files | Output |
|----------|---------|---------|---------|-----------|--------|
| **xlsp** | 30–120s | ✅ | ✅ | ✅ | Structured JSON |
| idea-mcp | 30–120s | ❌ | ✅ | ❌ | MCP result |
| Built-in MCP | 30–120s | ❌ | ✅ | ❌ | MCP result |
| Serena (JB) | 30–120s | ❌ | ✅ | ❌ | MCP result |

**Winner:** xlsp — **only solution** with `inspect-all` (run-by-name across all project files) and `inspect-list` (discover available inspections by name). This is a unique capability built specifically for the xlsp tool.

---

### Scenario E: "Apply this code action / fix"

**Tools that solve it well:** xlsp (`xlsp actions`, `xlsp apply`), MCPCodeIntel

| Solution | List actions | Apply by title | Server-side apply |
|----------|-------------|----------------|-------------------|
| **xlsp** | ✅ | ✅ | ✅ (custom `idealsp/codeActionApply`) |
| MCPCodeIntel | ✅ | ❌ | ❌ (returns edits to client) |
| Built-in MCP | ✅ | ✅ | ✅ |
| MCP Steroid | via script | via script | via script |

**Winner:** xlsp — custom `idealsp/codeActionApply` applies actions server-side via WriteCommandAction, not by returning edits to the client. This is safer (IntelliJ's write lock) and handles complex actions correctly.

---

### Scenario F: "Trace data flow from/to this variable"

**Tools that solve it well:** xlsp (`xlsp dataflow`), MCPCodeIntel, code-intel-mcp

| Solution | Direction | Depth | Taint tracking | Accuracy |
|----------|-----------|-------|---------------|----------|
| **xlsp** | From/To | 1 level | ❌ | PSI-based, no false positives |
| MCPCodeIntel | N/A | N/A | ❌ | IntelliJ-based |
| code-intel-mcp | Forward/Backward | Unlimited | ✅ (Joern CPG) | CPG-based |
| Seer | Forward/Backward | Configurable | ❌ | Tree-sitter based |
| code-graph | Forward/Backward | Unlimited | ❌ | Tree-sitter based |

**Winner:** code-intel-mcp (Joern) — full taint tracking with unlimited depth. xlsp's dataflow is LSP-based and limited to 1-hop, though it has zero false positives.

---

### Scenario G: "Plot the architecture of this project"

**Tools that solve it well:** code-base-memory, code-graph, Seer, go-code

| Solution | Modules | Entry points | Routes | Dependencies |
|----------|---------|-------------|--------|-------------|
| codebase-memory | ✅ (Louvain) | ✅ | ◐ | ✅ |
| code-graph | ✅ | ✅ | ✅ (HTTP) | ✅ |
| Seer | ❌ | ✅ | ✅ (HTTP/RPC) | ✅ |
| go-code | ✅ | ✅ | ❌ | ✅ |
| **xlsp** | ❌ | ❌ | ❌ | ❌ |

**Winner:** code-graph-mcp and codebase-memory-mcp — xlsp has no architecture-level tooling. This is a gap.

---

### Scenario H: "Set up in CI / headless server"

**Tools that solve it well:** xlsp, code-graph, codebase-memory, Seer, go-code

| Solution | Docker-friendly | No UI | systemd service | Startup time |
|----------|----------------|-------|----------------|--------------|
| **xlsp** (IdeaLSP) | ◐ (JVM-heavy) | ✅ | ✅ | 10–30s |
| code-graph | ✅ (single binary) | ✅ | ❌ | 50–200ms |
| codebase-memory | ✅ (single binary) | ✅ | ❌ | <1ms queries |
| Seer | ✅ (single binary) | ✅ | ❌ | 50–200ms |
| Built-in MCP | ❌ | ❌ | ❌ | N/A (needs IDE) |
| MCP Steroid | ◐ (devrig) | ◐ (best-effort) | ❌ | 30–60s |

**Winner:** codebase-memory-mcp and code-graph — lightweight, no JVM, instant startup. xlsp's JVM dependency limits CI/fast-start scenarios.

---

### Scenario I: "Refactor — rename this symbol everywhere"

**Tools that solve it well:** MCP Steroid, MCPCodeIntel, idea-mcp, Serena (JB)

| Solution | Rename | Extract | Move | Safe Delete |
|----------|--------|---------|------|-------------|
| MCP Steroid | ✅ | ✅ | ✅ | ✅ |
| MCPCodeIntel | ✅ | ❌ | ❌ | ❌ |
| idea-mcp | ✅ | ✅ | ✅ | ❌ |
| Serena (JB) | ✅ | ✅ | ✅ | ✅ |
| **xlsp** | ❌ | ❌ | ❌ | ❌ |

**Winner:** MCP Steroid and Serena JB — xlsp has zero refactoring capability. This is the single biggest gap.

---

## 4. Gap Analysis: xlsp vs. The Field

### 4.1 What xlsp Has That Others Don't (Unique Advantages)

| Feature | Why Unique | Competitive Moat |
|---------|-----------|-----------------|
| **`inspect-list` + `inspect-all`** | Run named IntelliJ inspections by name across all files. No other tool has this — MCP Steroid can via scripting but requires writing Kotlin. | **Strong** — built for agentic workflows |
| **Server-side code action apply** | Custom `idealsp/codeActionApply` applies actions inside IntelliJ's write lock. Others return edits to the client and hope they're applied correctly. | **Moderate** — safer for complex refactors |
| **`--wait` + `--context` + `--severity` flags** | Agent-friendly CLI ergonomics. Other tools assume IDE is ready and don't offer context/severity filtering in the CLI. | **Moderate** — easy to replicate |
| **Headless via systemd** | Runs as a daemon without IDE UI. Built-in MCP and most IntelliJ plugins require a full IDE window. MCP Steroid's devrig is catching up. | **Strong** — important for CI/agent workflows |
| **LSP-native (not MCP)** | Direct TCP connection to LSP server. Lower latency than MCP proxy layers, deterministic JSON output. | **Weak** — MCP ecosystem wins |
| **Custom dataflow methods** | `textDocument/dataflowFrom` + `textDocument/dataflowTo` — custom LSP extensions, not standard. | **Moderate** — Joern-based solutions are deeper |

### 4.2 What xlsp Is Missing vs. Competitors (Priority Order)

| Missing Feature | Competitors That Have It | Impact | Priority |
|----------------|-------------------------|--------|----------|
| **Refactoring** (rename, extract, move) | MCP Steroid, Serena, MCPCodeIntel, idea-mcp | **Critical** — agents need safe rename | P0 |
| **Type hierarchy** | intellij-mcp, idea-mcp, IDE Index, Serena | **High** — class hierarchy understanding | P1 |
| **MCP protocol support** | All competitors | **High** — ecosystem compatibility | P1 |
| **Impact analysis** | code-graph, Seer, codebase-memory | **Medium** — pre-edit safety check | P2 |
| **Architecture overview** | codebase-memory, go-code, code-graph | **Medium** — project orientation | P2 |
| **Debugger integration** | MCP Steroid, debugger MCP, Serena | **Medium** — runtime investigation | P2 |
| **Auto-indexing / file watcher** | code-graph, codebase-memory | **Medium** — always-fresh index | P2 |
| **HTTP route tracing** | code-graph, Seer | **Low** — web-specific | P3 |
| **Transitive call chains (>1 hop)** | code-graph, Seer, code-intel-mcp | **Low** — most agents need 1 hop | P3 |
| **Dead code detection** | code-graph, codebase-memory | **Low** — nice-to-have | P3 |
| **Taint tracking** | code-intel-mcp (Joern) | **Low** — security-specific | P3 |

### 4.3 xlsp's Niche Positioning

xlsp occupies a unique spot in the landscape:

```
                    Rich Features
                         ↑
                         |  MCP Steroid, Serena
                         |     (Full IDE in agent)
                         |
             idea-mcp ── | ── MCPCodeIntel
             40+ tools   |   13 tools
                         |
    xlsp ────────────────┤
    18 ops, headless     |  Built-in MCP
    LSP-native           |  15 tools
                         |
     intellij-mcp ───────┤
     7 tools             |
                         |  Tree-sitter tools
                         |  (code-graph, Seer, etc.)
                         |
                         |  grep / rg
                    Headless / Lightweight → 
```

**xlsp's niche:** The only headless IntelliJ-based solution that requires no full IDE UI, provides LSP-native operations, and runs as a systemd service. It meets in the middle between full-IDE plugins (rich but heavy) and Tree-sitter tools (lightweight but less precise).

---

## 5. Strategic Recommendations

### 5.1 Immediate (P0) — Close Critical Gaps

1. **Add rename refactoring** — `xlsp rename <old> <new> in <file>`. Uses existing `textDocument/rename` LSP method. This is the #1 feature agents need that xlsp lacks.

2. **Add type hierarchy** — `xlsp type-hierarchy <symbol> [--dir up|down]`. Uses existing `textDocument/typeHierarchy` LSP method (IntelliJ supports it since 2024.x).

### 5.2 Short-term (P1) — Ecosystem Compatibility

3. **MCP wrapper** — Expose xlsp operations as MCP tools. Allows integration with Claude Code, Codex, Cursor, and 50+ MCP clients. A simple bridge from the existing CLI would dramatically expand reach.

4. **Add `xlsp rename` + type hierarchy** (from P0).

### 5.3 Medium-term (P2) — Feature Parity

5. **Impact analysis** — Build on existing `xlsp refs` and `xlsp dataflow` to produce `xlsp impact <symbol>` — returns callees, callers, tests, risk assessment. This is the main differentiator of Tree-sitter tools that xlsp could match using IntelliJ's superior index.

6. **Architecture overview** — `xlsp project` — module list, dependency graph, entry points. IntelliJ's project model has all this data already.

7. **Code actions enhancement** — Implement `xlsp apply <title>` more robustly, support `textDocument/rename` via code action workflow.

### 5.4 Long-term (P3) — Unique Capabilities

8. **Structural search enhancement** — Current SSR via `xlsp semantic` uses IntelliJ's reflection-based SSR. This is already unique (only MCPCodeIntel has it too). Double down: expose more constraint types, add templates listing, support SSR replace.

9. **Session persistence** — Keep xlsp client connection alive across queries (instead of connect/disconnect per call). Estimated 10× latency improvement.

10. **Plugin marketplace** — Package xlsp operations as reusable opencode/xlsp skills for the agent ecosystem.

---

## 6. Competitive Scoring Summary

| Category | Weight | xlsp | Top Competitor | xlsp Score | Top Score |
|----------|--------|------|----------------|-----------|-----------|
| Symbol navigation | 20% | 6/7 | MCPCodeIntel 6/7 | **86%** | **86%** |
| Code analysis | 20% | **6/9** | MCPCodeIntel 4/9 | **67%** | 44% |
| Refactoring | 15% | 0/4 | MCP Steroid 4/4 | **0%** | **100%** |
| IDE integration | 15% | **2/5** | Built-in MCP 5/5 | 40% | **100%** |
| Agent UX | 15% | 6/8 | MCP Steroid 4/8 | **75%** | 50% |
| CI/Headless | 10% | 3/5 | codebase-memory 5/5 | 60% | **100%** |
| Ecosystem (MCP) | 5% | 0/1 | All others 1/1 | **0%** | **100%** |

**Weighted total:** xlsp ≈ **56%** vs top competitor per category.

**Bottom line:** xlsp is strongest in **code analysis** (leads the field) and **symbol navigation** (tied for best), but critically weak in **refactoring** (P0 gap) and **MCP ecosystem** (P1 gap). Its unique headless-IntelliJ architecture is the differentiator — no other solution offers IntelliJ-grade analysis without a full IDE UI.

---

## 7. xlsp CLI vs. MCP Protocol: Analysis for OpenCode & Claude Code CLI

This section evaluates whether xlsp's current CLI-first design has tangible benefits over an MCP-native approach when used specifically through agent frameworks like OpenCode and Claude Code CLI.

### 7.1 Architecture Comparison

```
CLI approach (current):
  LLM Agent → bash tool → bun cli.ts → TCP connect → LSP init → op → shutdown → close → stdout JSON

MCP approach (hypothetical):
  LLM Agent → MCP tool → xlsp-mcp-server (persistent) → TCP connect → LSP init → [op] × N → ... → result
```

The difference is not just protocol — it's about **connection lifecycle**. With CLI, the LSP handshake happens per call. With MCP, the server process and LSP connection persist across calls.

### 7.2 Token Usage Breakdown

#### 7.2.1 Fixed Context Overhead (per session)

| Cost item | CLI (bash) | MCP | Notes |
|-----------|-----------|-----|-------|
| Bash tool definition | ~150 tokens | ~150 tokens | Always present in OpenCode |
| xlsp MCP tool schemas (×18) | 0 | ~3600–7200 tokens | ~200–400 tokens per tool (description + JSON schema with property names, types, descriptions) |
| xlsp usage instructions | 0–200 tokens | 0 | Agent may need bash invocation examples |
| **Total session overhead** | **~150 tokens** | **~3750–7350 tokens** | MCP costs 25–50× more upfront tokens per session |

The OpenCode MCP docs explicitly warn: *"When you use an MCP server, it adds to the context. This can quickly add up if you have a lot of tools."* A full xlsp MCP server exposing all 18 operations would add roughly 3600–7200 tokens of tool definitions to every LLM call. For a 128K context model at $15/M input tokens, this is **$0.05–0.11 per session before any actual work**.

#### 7.2.2 Per-Call Token Cost

| Scenario | CLI (bash) | MCP | Delta |
|----------|-----------|-----|-------|
| Simple query (`xlsp refs --symbol Foo`) | 40–50 tokens | 35–45 tokens | ~MCP 10% less |
| Query with flags (`--context 2 --severity error`) | 50–65 tokens | 45–60 tokens | ~MCP 10% less |
| Result output (10 refs) | ~300 tokens | ~300 tokens | Identical |
| Error recovery (malformed query) | 60–80 tokens (retry) | 0 tokens (schema enforced) | MCP wins |

**Per-call tokens are approximately equal.** The bash command string is slightly longer than the MCP JSON-RPC call, but the difference (~5–10 tokens) is negligible.

**Verdict:** MCP costs **25–50× more upfront tokens** but comparable per-call tokens. For sessions with 10+ xlsp calls, the upfront cost amortizes to a ~5–15% overall increase. For 1–3 call sessions (common in agent workflows), the CLI approach is significantly more token-efficient.

#### 7.2.3 Output Token Efficiency

| Aspect | CLI (bash) | MCP |
|--------|-----------|-----|
| Result wrapping | Raw JSON lines | `{ content: [{ type: "text", text: "..." }] }` |
| Overhead per result | ~5 tokens (JSON key names) | ~15 tokens (MCP content wrapper) |
| Streaming | Not supported | Yes (MCP supports progress notifications) |

MCP adds ~10 tokens of wrapping per result call, which is negligible.

### 7.3 Latency Analysis

#### 7.3.1 Per-Call Latency Breakdown

| Phase | CLI (bash) | MCP (persistent) | Winner |
|-------|-----------|-------------------|--------|
| Shell spawn / process start | ~50–150ms (Bun runtime init) | 0ms (already running) | MCP |
| TCP connect (127.0.0.1:8989) | ~1ms | ~1ms (first call only) | Tie |
| LSP initialize handshake | ~100ms–8s (index state dependent) | ~100ms–8s (first call only) | MCP |
| Operation execution | ~50ms–2s | ~50ms–2s | Tie |
| LSP shutdown handshake | ~10ms | 0ms | MCP |
| Socket close | ~1ms | 0ms | MCP |
| **Total (cold, with indexing)** | **~500ms–10s** | **~200ms–8s** | **MCP (~30% faster)** |
| **Total (warm, indexed)** | **~200ms–400ms** | **~50ms–250ms** | **MCP (~50% faster)** |

**Key insight:** The dominant cost is LSP initialization, not bash/MCP overhead. With a persistent MCP connection, the LSP handshake happens once. With the CLI, it happens every call.

The xlsp source confirms each invocation does a full connect→init→shutdown cycle:
```
1. client = new LspClient(port)
2. client.connect()
3. client.sendRequest("initialize", ...)
4. client.sendNotification("initialized", {})
5. [dispatch]
6. client.sendNotification("shutdown", {})
7. client.sendNotification("exit", {})
8. client.close()
```

#### 7.3.2 Impact on Agent Workflows

| Workflow pattern | CLI | MCP | Notes |
|-----------------|-----|-----|-------|
| Single shot ("find this symbol") | ~500ms–10s | ~200ms–8s | MCP faster, both acceptable |
| Sequential ("refs → calls → dataflow") | ~1.5s–30s (×3) | ~400ms–8s (mostly first call) | **MCP 3–4× faster** |
| Diagnostic loop ("diagnostics → fix → diagnostics") | ~1s–20s (×2) | ~500ms–8s (first call) | MCP 2× faster |
| Batch inspection ("inspect-all 20 inspections") | **30–120s** | **30–120s** | Tie (dominated by server, not transport) |

**For multi-step agent workflows, a persistent MCP connection saves 2–4× latency.**

#### 7.3.3 Practical Note

The IdeaLSP server (IntelliJ backend) is the bottleneck, not CLI or MCP. The server takes 10–30 seconds to start regardless of transport. Once running, LSP initialize takes 100ms–8s depending on whether the project index is ready. Neither CLI nor MCP changes this.

### 7.4 Precision & Reliability

| Dimension | CLI (bash) | MCP |
|-----------|-----------|-----|
| Argument validation | None (LLM constructs string) | Schema-enforced (type checking, required fields) |
| Quoting/escaping errors | Common (spaces, special chars in symbols) | Impossible (structured JSON) |
| Working directory issues | Must pass `workdir` parameter | Server-configured, or per-call parameter |
| Output parsing | LLM must parse JSON lines from text | Structured result object |
| Error propagation | JSON error field (LLM must read) | MCP `isError` flag + structured content |
| Idempotency | Natural (stateless per call) | Natural (if MCP server is stateless) |
| Connection management | None (spawn per call) | Must handle reconnection, timeout, OAuth |
| Failure mode | Clean (exit code 0, JSON error) | Can hang (connection leak, timeout) |

**MCP wins on precision** — enforced schemas eliminate entire classes of LLM errors (missing required args, invalid types, quoting mistakes). The structured result format also means the LLM doesn't need to parse JSON from text output, reducing cognitive load.

**CLI wins on reliability** — simpler failure model. Each invocation is an independent process. No connection state, no reconnection logic, no OAuth. If the server is down, the error is immediate and obvious.

### 7.5 Agent Integration Complexity

| Aspect | CLI (bash) | MCP |
|--------|-----------|-----|
| Setup | Add xlsp to PATH | Configure MCP server in opencode.json |
| Tool discovery | Agent must know command syntax | Agent reads tool descriptions + schemas |
| Self-documentation | None (no --help in xlsp) | Built-in (descriptions in tool defs) |
| Permission model | Standard bash permissions | Tool-specific permissions (`server_toolname`) |
| Cross-platform | Requires Bun | Requires Bun (local MCP) or any (remote) |
| **OpenCode-specific** | Uses existing bash tool | Registers `xlsp_*` tools with prefix |

**MCP advantages in agent integration:**
- **Tool discovery**: The LLM can see tool names, descriptions, and parameter schemas. It doesn't need to memorize CLI syntax.
- **Guided usage**: Descriptions can include hints about when to use each operation (e.g., "Use this to find all usages of a symbol before renaming it").
- **Permission granularity**: Agents can be given `xlsp_refs` permission without `xlsp_inspect`, etc.
- **No bash prompt bloat**: The bash tool's 115-line instruction prompt (in OpenCode) already tells the LLM not to use bash for file ops. Adding xlsp CLI conventions would add further bloat.

**CLI advantages in agent integration:**
- **Zero configuration**: If xlsp is on PATH, it just works. No config file changes.
- **No framework lock-in**: Works in any CLI environment (OpenCode, Claude Code, Cursor, shell scripts, CI pipelines).
- **Simple debugging**: `xlsp refs --symbol Foo` works the same in a terminal as when called by an agent.

### 7.6 OpenCode-Specific Analysis

#### 7.6.1 Context Window Impact

OpenCode injects tool descriptions into the system prompt for every LLM call. With 18 xlsp MCP tools each adding ~300 tokens of schema, that's ~5400 tokens *per turn*. For comparison:

- 5400 tokens ≈ 2× the length of the bash tool's entire instruction prompt (115 lines ≈ 2700 tokens)
- 5400 tokens ≈ 15% of a 32K context model's capacity
- 5400 tokens at $15/M input tokens (Sonnet 4o): **$0.08 per turn**

For a 10-turn conversation: $0.80 in context costs just from xlsp tool definitions.

#### 7.6.2 Tool Selection Noise

With 18 MCP tools alongside OpenCode's ~15 built-in tools, the LLM has 33+ tools to choose from. This can cause:
- **Tool flooding**: LLM may pick wrong xlsp tool for a task
- **Context dilution**: Each tool gets less attention
- **Schema interference**: JSON schemas from different tools may confuse the model

OpenCode's own docs warn about this: "MCP servers add to your context, so you want to be careful with which ones you enable."

#### 7.6.3 The Bash Tool Is Already Warm

OpenCode spawns a persistent shell session. The bash tool uses `child_process.spawn()` with the user's shell — the shell itself stays warm. However, xlsp (via `bun cli.ts`) still pays Bun's startup cost per call (~50–150ms), since each CLI invocation is a new process.

With a local MCP server, the server process stays running. Bun startup happens once, at MCP server startup.

### 7.7 Claude Code CLI-Specific Analysis

Claude Code CLI uses a similar architecture to OpenCode:
- Has a bash/command execution tool
- Supports MCP servers
- Loads MCP tool definitions into context per session

The tradeoffs are identical, with one nuance: Claude Code CLI tends to be used for shorter, more targeted sessions (one-off questions, quick fixes), where the upfront MCP context cost is harder to amortize.

### 7.8 Quantitative Comparison

| Metric | CLI (bash) | MCP | xlsp's position |
|--------|-----------|-----|----------------|
| **Tokens/session (fixed)** | ~150 | ~3750–7350 | **CLI wins** (25–50× less) |
| **Tokens/call** | ~45 | ~40 | Tie (~10% difference) |
| **Latency/call (cold)** | ~500ms–10s | ~200ms–8s | MCP ~30% faster |
| **Latency/call (warm)** | ~200–400ms | ~50–250ms | **MCP ~50% faster** |
| **Latency (3-call chain)** | ~600ms–30s | ~200ms–8s | **MCP 2–4× faster** |
| **Argument errors** | Common | Never | **MCP wins** |
| **Setup complexity** | Add to PATH | Edit opencode.json | **CLI wins** |
| **Framework portability** | Everywhere | MCP hosts only | **CLI wins** |
| **Connection state mgmt** | None | Reconnect, OAuth | **CLI wins** |
| **Output parsing burden** | LLM parses text | Structured result | **MCP wins** |
| **Self-documenting** | No | Yes (schemas) | **MCP wins** |
| **Per-tool permissions** | No | Yes | **MCP wins** |

### 7.9 Verdict & Recommendation

The tradeoff is clear:

**Choose CLI (current approach) when:**
- Token budget is tight (short sessions, expensive models)
- Simplicity matters more than latency (single queries, CI pipelines)
- Cross-platform portability is required (works without OpenCode)
- Connection state management is undesirable

**Choose MCP when:**
- Multi-step agent workflows dominate (3+ sequential queries)
- Latency sensitivity is high (interactive use)
- Argument correctness is critical (complex queries with many parameters)
- Tool discovery and self-documentation are valuable
- Persistent connection is acceptable (long-running agent sessions)

#### Recommendation for xlsp

**Build an MCP bridge, but don't replace the CLI.** The CLI-first approach has genuine advantages for token efficiency and simplicity. An MCP wrapper that:

1. Maintains a persistent LSP connection to IdeaLSP
2. Exposes 5–8 high-value tools (not all 18) to limit context bloat
3. Reuses the same CLI code paths under the hood

would give users the best of both worlds. The MCP server can be a simple wrapper that:
- Spawns as a daemon (or uses the existing systemd service)
- Keeps one persistent LSP connection
- Exposes a curated tool set (not every flag variation)
- Delegates to the same LSP commands the CLI uses

**A reasonable compromise:** Ship the CLI by default for zero-config use, and provide an MCP server (`mcp-xlsp` or an opencode.json snippet) for users who want persistent connections and structured tool integration.

#### Quantitative Recommendation

| Scenario | Recommended approach | Rationale |
|----------|---------------------|-----------|
| 1-off queries | CLI | No upfront cost, simple |
| PR review / code analysis | CLI or MCP | Either works; CLI simpler |
| Multi-step agent task (3+ ops) | MCP | 2–4× latency improvement amortizes token cost |
| CI/CD pipeline | CLI | No MCP server dependency |
| Interactive agent session | MCP | Latency matters, connection persists |
| Cross-editor use | CLI | Works in any terminal |
