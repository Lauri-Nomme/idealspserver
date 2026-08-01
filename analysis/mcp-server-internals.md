# IntelliJ MCP Server plugin — internal architecture and per-endpoint analysis

> Source: cellophane of the bundled MCP server plugin (plugin `com.intellij.mcpServer`, v`261.22158.277`)
> from IDEA IU-261.22158.277, produced into `analysis/cellophane/src/`.
> Scope: how the plugin is wired end-to-end (extension points → reflection → SDK) and how
> every exposed MCP tool is implemented. Cellophane gaps are called out explicitly.

---

## 1. High-level architecture

```
MCP client (Claude Code, Cursor, etc.)
        │  JSON-RPC over SSE / Streamable HTTP / WebSocket
        ▼
Ktor CIO embedded server (127.0.0.1, free port, default 64342)
        │  McpServerService.startServer(port, authCheck)
        ▼
io.modelcontextprotocol.kotlin.sdk server (JetBrains' MCP Kotlin SDK)
        │  Server(Implementation, ServerCapabilities(tools)) → createSession(transport)
        ▼
ServerSession.onInitialized → register tools (addTools/removeTools live-update)
        │
        ▼
RegisteredTool{ Tool, handler(CallToolRequest → CallToolResult) }
        │  McpServerService.mcpToolToRegisteredTool()
        ▼
McpTool.call(JsonObject args)  ──►  ReflectionCallableMcpTool  ──►  CallableBridge  ──►  KCallable.callSuspendBy()
        │                                                              (kotlin-reflect arg decode)
        ▼
Toolset Kotlin object, e.g. ReadToolset, SearchToolset, TextToolset … (each method = one tool)
```

Three plugin extension points are declared in `plugin.xml`:

| EP | interface | purpose |
|---|---|---|
| `com.intellij.mcpServer.mcpToolset` | `McpToolset` | A Kotlin object holding `@McpTool`-annotated suspend functions (one function = one tool). `isEnabled()` gate. |
| `com.intellij.mcpServer.mcpToolsProvider` | `McpToolsProvider` | Supplies `List<McpTool>`. Default impl `ReflectionToolsProvider` turns all enabled toolsets into tools. |
| `com.intellij.mcpServer.mcpToolFilterProvider` | `McpToolFilterProvider` | Streams `StateFlow<List<McpToolFilter>>` to enable/disable tools dynamically (registry key, settings UI, disallow list). |

Toolsets are loaded from three XMLs:
- `plugin.xml` → 9 general toolsets (`toolsets/general/*`)
- `mcpServer-terminal.xml` → `TerminalToolset` (optional dep `org.jetbrains.plugins.terminal`)
- `mcpServer-vcs.xml` → `VcsToolset` (optional dep `Git4Idea`)

---

## 2. Toolset → tool conversion (reflection pipeline)

`ReflectionToolsProvider.getTools()`:
1. `McpToolset.Companion.getEnabledToolsets()` — all EP instances with `isEnabled()==true`.
2. For each: `ToolsetReflection_utilKt.asTools(toolset, McpServerJson)`.
3. `asTools` reflects `KClasses.getFunctions(KClass)` and keeps members whose `@McpTool`
   annotation is found (on the function or, via `getImplementedMethods`, an overridden/interface
   method — BFS through superclasses/interfaces). One function → one `ReflectionCallableMcpTool`.
4. Tool **name**: `@McpTool.name` if non-blank, else the Kotlin function name.
5. **Category**: `McpToolCategory(toolsetSimpleName, toolsetQualifiedName)` — so every tool's
   fully-qualified name is `<toolsetQualifiedName>.<functionName>` (used by the filter globs,
   e.g. `-*.get_file_text_by_path`).

Each tool gets `ReflectionCallableMcpTool(descriptor, CallableBridge)`:
- `McpToolDescriptor(name, description, category, fullyQualifiedName, inputSchema, outputSchema)`
- `description` = trimmed `@McpDescription` (falls back to function name)
- `inputSchema` = `Schema_utilKt.parametersSchema(function, [projectPathParameter])`
- `outputSchema` = `Schema_utilKt.returnTypeSchema(function)` (nullable)

### 2.1 Schema generation
Uses **schemakenerator** (`io.github.smiley4.schemakenerator`) + **kotlinx.serialization**:

```
CoreSteps.initial(KType)
  → SerializationSteps.analyzeTypeUsingKotlinxSerialization   // reads @Serializable data classes
  → JsonSchemaSteps.generateJsonSchema
  → handleCoreAnnotations
  → JsonSchemaCoreAnnotationMcpDescriptionStep  (custom step: injects @McpDescription text)
  → RemoveNumericBoundsStep                      (custom step: strips kotlinx numeric min/max bounds)
  → JsonSchemaSteps.compileInlining(false)
```

- `parametersSchema` iterates `KCallable.parameters` + implicit params; each non-optional param
  becomes a required property; result wrapped in `McpToolSchema` (properties, required,
  `additionalProperties:false`, optional `definitions`).
- `returnTypeSchema` returns `null` (no outputSchema) for String, Char, Boolean, Int/Long/Double/
  Float/Byte/Short, Unit, enums, and `McpToolCallResult`. For `@Serializable` data classes it
  produces the JSON-schema of the result object. Output schema only reaches the SDK when the
  registry key `mcp.server.structured.tool.output` is enabled.
- `removeRequiredForDefaultValues` drops required-properties that are optional in the serializer
  descriptor (so default-valued fields don't appear as required).

### 2.2 Implicit `projectPath` parameter
Every tool's schema silently includes an implicit `projectPath` param, generated from a stub
function `projectPathParameterStub(String projectPath)` annotated:
> "The project path. Pass this value ALWAYS if you are aware of it. It reduces numbers of
> ambiguous calls. In the case you know only the current working directory you can use it as the
> project path. If you're not aware about the project path you can ask user about it."

`Schema_utilKt.getProjectPathParameter()` = `KParameter` of that stub (optional, so it never lands
in `required`).

### 2.3 JSON config
`McpServerJson` (shared kotlinx `Json`): `ignoreUnknownKeys=true`, `lenient=true`,
`decodeEnumsCaseInsensitive=true`, `explicitNulls=false`.

### 2.4 Invocation bridge
`CallableBridge.call(args: JsonObject)`:
1. Build `argMap`: INSTANCE param ← `thisRef` (the toolset object); VALUE params ←
   `json.decodeFromJsonElement(serializer, args[name])`; optional param missing → skipped.
   Missing required → `IllegalStateException("No argument is passed for required parameter …")`.
2. `KCallables.callSuspendBy(callable, argMap)` — suspends into the tool function.
3. Wraps `InvocationTargetException` cause unwrapping.
4. Result → `CallableBridge.Result(result, returnType, json)`.

`ReflectionCallableMcpTool.call()` post-processes the result to `McpToolCallResult`:
- `null` → `text("[null]")`
- `Unit` → `text("[success]")`
- `Character` → `text("'<c>'")`
- `String` → `text(s)`
- primitives/boxed → `text(toString())`
- `McpToolCallResult` → passthrough
- `McpToolCallResultContent` → wrapped single-content result
- other serializable objects → `text(encodeToString(), encodeToJson())` (structuredContent)

---

## 3. Server + transport layer

### 3.1 `McpServerService` (application-level `@Service`, `Service.Level.APP`)
State: `MutableStateFlow<EmbeddedServer<CIO>>`, private `ServerAndCount`, `callId` counter,
`activeAuthorizedSessions` map (token → session options), `sessionRoots` (sessionId → roots set).

- `start()`/`stop()` toggle the persisted `McpServerSettings` (`enableMcpServer`) and call
  `settingsChanged(enabled)` which start/stops a **global** server on `mcpServerPort`
  (default `64342`, `DEFAULT_MCP_PORT`).
- A second **private** server is started on demand on port `64442`
  (`DEFAULT_MCP_PRIVATE_PORT`) with `authCheck=true` for "authorized" sessions (e.g. the
  stdio/toolkit path that wants a random auth token). `authorizedSession(options, block)`
  refcounts users, shuts the private server down (2 s timeout) when the last user leaves.
- `startServer(desiredPort, authCheck)`:
  - `UtilKt.findFirstFreePort(desiredPort)`
  - maintains a `MutableStateFlow<List<McpTool>>` refreshed on toolset/tools-provider/filter EP
    add/remove; `getMcpToolsFiltered(filter, useFiltersFromEP, excludeProviders)` aggregates
    all providers, applies name filter + all filter providers' `McpToolFilter` chains.
  - builds Ktor `embeddedServer(CIO, port, "127.0.0.1", ...)`:
    - `UtilKt.installHostValidation` (host header guard)
    - `Mcp_sdk_utilKt.installHttpRequestPropagation` (threads the Ktor `ApplicationRequest`
      through coroutine context so tools can read headers)
    - `mcpPatched(app, authInterceptor, block)` — routes
  - on the transport creation, per-session headers are read:
    - `IJ_MCP_SERVER_PROJECT_PATH` header → project path
    - `IJ_MCP_AUTH_TOKEN` header (auth-check mode) → session options lookup
    - `IJ_MCP_ALLOWED_TOOLS` header → comma-separated allowlist → `McpToolFilter.AllowList`
  - session options: `AskCommandExecutionMode` (`ASK`/`DONT_ASK`/`RESPECT_GLOBAL_SETTINGS`) +
    `McpToolFilter`.
  - `Server(Implementation("<product> MCP Server", version), ServerOptions(ServerCapabilities(tools=true)), ...)`
    then `createSession(transport)`, registers `onInitialized` to publish `listRoots` +
    `roots/list_changed` into `sessionRoots`, and a `collectLatest` on the tools flow that
    diffs `updateMcpServerTools()` → `addTools`/`removeTools` on the live SDK session.

### 3.2 Transports (`Mcp_sdk_utilKt`, patched fork of the MCP Kotlin SDK routing)
`mcpPatched(app, prePhase, block)` installs Ktor SSE and routes:
- **SSE**: `GET /sse` (query `sessionId` for post) → `SseServerTransport`; `POST /message`
  → `handlePostMessage`. Transport registry keyed by sessionId.
- **Streamable HTTP**: `POST /mcp` → `handleStreamablePost` (batches allowed, reads raw body,
  `parseMessages`, `mcp-session-id` header handshake, `createStreamableSession` on `initialize`,
  `StreamableHttpServerTransport.handlePost`); `GET /mcp` → `handleStreamableGet`
  (`StreamableHttpServerTransport.handleGet`).
- **WebSocket**: `handleWebSocketEndpoint` (Transport via WebSocket session).
- Auth interceptor (`prePhase`): when `authCheck`, require `IJ_MCP_AUTH_TOKEN` present in
  `activeAuthorizedSessions`, else respond 401
  ("MCP server is running in restricted mode. Please, provide valid authorization token").
- Error responses use JSON-RPC codes: `-32700` parse error, `-32000` server error.

### 3.3 SDK mapping (`McpServerServiceKt`)
- `toSdkTool(McpTool)`: `Tool(name, inputSchema{ToolSchema}, description, outputSchema|null)`
  — outputSchema only when `Registry.is("mcp.server.structured.tool.output")`.
- `toSdkToolCallResult(McpToolCallResult)`: maps `Text` contents → SDK `TextContent`;
  `structuredContent` gated by the same registry key; `isError` → `CallToolResult.isError`.
- Registry keys (`plugin.xml`):
  - `mcp.server.structured.tool.output` (default **true**) — output schemas + structuredContent
  - `mcp.server.ot.trace` (default true) — OpenTelemetry tracer (`mcpServer` scope)
  - `mcp.server.tools.filter` (default `*`) — filter globs, e.g. `-*,+pkg.*,-*.get_file_text_by_path`
  - `mcp.server.detect.mcp.clients` (default false)
  - `mcp.server.write.legacy.key` (default false)
  - `mcp.server.show.advanced.filter.options.ui` (default false)
- Auth token header name constant: `IJ_MCP_AUTH_TOKEN`. Stdio header names referenced:
  `IJ_MCP_SERVER_PROJECT_PATH`, `IJ_MCP_ALLOWED_TOOLS`, `IJ_MCP_SERVER_PORT` (in `McpStdioRunnerKt`,
  not in the cellophane set).

### 3.4 Tool-call handler (the dispatch core)
`mcpToolToRegisteredTool(mcpTool, server, session, projectPathFromInitialRequest)` builds the
`RegisteredTool` handler:
1. **Project resolution** (before running the tool), in priority order:
   1. roots list of the session (`sessionRoots[sessionId]` → `findMostRelevantProjectForRoots`)
   2. `projectPath` argument in the call payload (`args["projectPath"]`)
   3. `IJ_MCP_SERVER_PROJECT_PATH` header (from propagated Ktor request) or the initial session header
   4. else → `McpCallInfoKt.getProjectOrNull`: 0 open projects → null→`McpExpectedError("No project opened")`;
      1 open → that project; many open → `noSuitableProjectError` with an `OpenProjects` JSON
      structuredContent listing base paths and telling the client to ask the user.
2. Builds `McpCallInfo(callId, ClientInfo, Project, descriptor, args, meta, sessionOptions,
   headersWithoutAuthToken)` and wraps the whole tool execution in
   `withContext(McpCallAdditionalDataElement(callInfo))` so tools can read
   `currentCoroutineContext().project / clientInfo / currentToolDescriptor`.
3. Installs (per-call) a `VirtualFileManager` async file listener + `EditorFactory`
   `DocumentListener` that record pre-change VFS/document contents **only for the same callId**
   (thread-context match) — i.e. change tracking for the IDE diff against the MCP client.
4. Runs the tool with timeout/cancellation handling → catches `TimeoutCancellationException`
   ("Calling of tool '…' has been timed out"), `CancellationException`, `McpExpectedError`
   (→ error text + structuredContent), other `Throwable` → `error(e.message)`.
5. `toSdkToolCallResult` → `CallToolResult`.

Activity reporting: `ToolCallListenerKt.reportToolActivity(ctx, msg)` publishes to
`ToolCallListener.TOPIC` (message bus) + trace log, e.g. `"Running command…"` in the terminal tool.

### 3.5 Confirmation / Brave mode (`Execution_utilKt`)
`checkUserConfirmationIfNeeded(notificationText, command, project)`:
- returns immediately in Brave Mode (`McpServerSettings.MyState.enableBraveMode`) or when
  session mode is `DONT_ASK`; otherwise `askConfirmation` opens a `DialogWrapper` on the EDT:
  message + read-only command text area + a **"Enable Brave Mode" checkbox** bound to settings.
- OK → continue; deny → `McpExpectedError("User rejected command execution")`.
- Mode resolution: `McpSessionOptions.commandExecutionMode` — `ASK`, `DONT_ASK`, or
  `RESPECT_GLOBAL_SETTINGS` (default when no auth token); the settings checkbox stores
  `enableBraveMode`.

### 3.6 Error model
`McpExpectedError(message, structuredContent)` → surfaced as MCP error result with optional
structured content (e.g. the open-projects list). `McpToolKt.mcpFail(message, structuredContent)`
is the throwing helper used by tools/utils (returns `Nothing`).

---

## 4. Per-tool analysis

Conventions:
- All paths are relative to the resolved project root unless noted.
- Every tool implicitly accepts `projectPath`.
- `McpServerJson` handles enums case-insensitively and ignores unknown keys.
- TruncateMode enum values: from the start / middle / end / no truncation.

### ReadToolset
- **`read_file`** → returns `String`.
  Params: `file_path` (rel path), `mode` ∈ {`slice`, `lines`, `line_columns`, `offsets`,
  `indentation`}, `start_line` (1-based), `max_lines`, `end_line` (1-based, inclusive for
  `lines`, exclusive for `line_columns`), `start_column`/`end_column` (1-based), `start_offset`/
  `end_offset` (0-based, exclusive), `context_lines` (context per side), and for `indentation`:
  `max_levels`, `include_siblings`, `include_header`.
  Implementation lives in `ReadToolsetKt` (largely intact? no — ReadToolsetKt is 625 lines;
  signature confirms rich range/context/indent modes, caps output via `max_lines`).

### AnalysisToolset
- **`get_file_problems`** → `FileProblemsResult` (inspections).
  Params: `filePath` (rel path), `errorsOnly: boolean`, `timeout` (ms).
  Runs IntelliJ code-inspection analysis on the given file, error/warning filtered by `errorsOnly`,
  gated by timeout.
- **`build_project`** → `BuildProjectResult`.
  Params: `rebuild: boolean`, `filesToRebuild: List<String>?` (rel paths; takes precedence),
  `timeout` (ms). Uses Gradle/compiler build (full rebuild if requested / default incremental).
- **`get_project_modules`** → `ProjectModulesResult`. No params.
  Enumerates project modules (via module model).
- **`get_project_dependencies`** → `ProjectDependenciesResult`. No params.
  Enumerates module dependencies.

### CodeInsightToolset
- **`get_symbol_info`** → `SymbolInfoResult`.
  Params: `filePath` (rel path), `line` (1-based), `column` (1-based).
  Resolves the symbol at the given caret position and returns its info
  (`util/SymbolInfo` — symbol kind, name, containing class/member, signatures, Javadoc, usages
  counts, etc.).

### ExecutionToolset
- **`get_run_configurations`** → `RunConfigurationsList`. No params.
  Lists run configurations (name + type) via `RunManager`.
- **`execute_run_configuration`** → `RunConfigurationResult` (large impl, 1159 lines).
  Params: `configurationName`, `timeout` (ms), `maxLinesCount`, `truncateMode`.
  Locates a `RunnerAndConfigurationSettings` by name, runs it, captures console output,
  truncates to `maxLinesCount` lines, returns exit-code-like status + output.
  (`ExecutionToolsetKt` holds result DTOs.)

### FileToolset
- **`list_directory_tree`** → `DirectoryTreeInfo`.
  Params: `directoryPath` (rel path), `maxDepth`, `timeout`.
  Uses `Fs_utilKt.renderDirectoryTree` (recursive, sorted, `RenderStyle` name-only/absolute,
  errors bagged, maxDepth default 10).
- **`find_files_by_name_keyword`** → `FilesListResult`.
  Params: `nameKeyword`, `fileCountLimit`, `timeout`. Substring match on file names.
- **`find_files_by_glob`** → `FilesListResult`.
  Params: `globPattern` (rel to project root, e.g. `src/**/*.java`), `subDirectoryRelativePath`,
  `addExcluded` (include ignored/excluded files), `fileCountLimit`, `timeout`.
  `PathScope`/`PathPattern` helpers.
- **`open_file_in_editor`** → `Unit`. Params: `filePath`. Opens the file in the IDE editor.
- **`get_all_open_file_paths`** → `OpenFilesInfo`. No params. Lists currently open editor files.
- **`create_new_file`** → `Unit`.
  Params: `pathInProject`, `text: String?` (content), `overwrite: boolean`
  (false → conflict error).

### FormattingToolset
- **`reformat_file`** → `String`. Params: `path` (rel path).
  Runs IntelliJ `CodeStyleManager` reformat on the file, returns the new text.

### TextToolset
- **`get_file_text_by_path`** → `String`.
  Params: `pathInProject`, `truncateMode`, `maxLinesCount`.
- **`replace_text_in_file`** → `Unit`.
  Params: `pathInProject`, `oldText`, `newText`, `replaceAll`, `caseSensitive`.
  Textual replace (document-level edit).
- **`search_in_files_by_text`** → `UsageInfoResult`.
  Params: `searchText`, `directoryToSearch` (rel, optional), `fileMask` (e.g. `*.java`),
  `caseSensitive`, `maxUsageCount`, `timeout`. Usage-based search returning
  `UsageSnippet{file, filePath, lineText, startLine, startColumn, endLine, endColumn, startOffset, endOffset}`.
- **`search_in_files_by_regex`** → `UsageInfoResult`. Same params with `regexPattern`.

### SearchToolset
All return `SearchResult{ items: List<SearchItem>, more: Boolean }`.
`SearchItem{ filePath, startLine, startColumn, endLine, endColumn, startOffset, endOffset, lineText }`.
Common params: `paths: List<String>?` — glob filters, `!` excludes, trailing `/` → `**`,
no `/` → `**/pattern`.
- **`search_text`** → `q`, `paths`, `limit`. Text search.
- **`search_regex`** → `q`, `paths`, `limit`. Regex search.
- **`search_symbol`** → `q`, `paths`, `limit`. Symbol search (name, class, method…) via
  `SearchSymbolSupportKt` (name-matching over PSI symbols).
- **`search_file`** → `q` (glob), `paths`, `includeExcluded`, `limit`. File search.

### TerminalToolset (`mcpServer-terminal.xml`, requires Terminal plugin)
- **`execute_terminal_command`** → `CommandExecutionResult{ is_timed_out, command_exit_code, command_output }`.
  Params: `command`, `executeInShell` (default false — run as process vs user's shell),
  `reuseExistingTerminalWindow` (default true, passes client name as window id),
  `timeout` (default 60000 ms), `maxLinesCount` (default 1000), `truncateMode`.
  Flow:
  1. `reportToolActivity("Running command …")`
  2. `checkUserConfirmationIfNeeded("Do you want to execute command in terminal?", command, project)`
  3. `ToolWindowManager.getToolWindow("Terminal")` → `TerminalToolsetUtilKt.executeShellCommand(...)`
     (`TerminalShellCommandHandler`/`GeneralCommandLine`, output captured to `maxLinesCount`,
     truncated by `truncateMode`, timed out via `timeout`, `is_timed_out` flag on timeout).
  Description stresses: checks process running before collecting output; times out with
  notification; requires confirmation unless Brave Mode.

### VcsToolset (`mcpServer-vcs.xml`, requires Git4Idea)
- **`get_repositories`** → `VcsRoots{ roots: VcsRoot[] }`, `VcsRoot{ pathRelativeToProject, vcsName }`.
  No params. Uses `ProjectLevelVcsManager.getAllVcsRoots()`, relativizes each root path to the
  project dir, VCS name from `AbstractVcs.getName()` (fallback `<Unknown VCS>`).

---

## 5. Settings & configuration surface

`McpServerSettings` (`@State("mcpServer.xml")`): `enableMcpServer` (false), `enableBraveMode`
(false), `mcpServerPort` (64342). `DEFAULT_MCP_PRIVATE_PORT = 64442`.

`McpToolDisallowListSettings`: `disallowedToolNamesFlow` — tool names the user disallowed.

`McpToolFilterConfigurable` + `McpToolFilterOptimizer` + `CheckboxWithValidation`/`ConsentValidator`
→ settings UI for tool filtering (glob patterns, advanced UI gated by registry key).

Startup: `McpServerService$MyProjectListener` (ProjectActivity) warms the service;
`McpClientDetectionActivity` (postStartup) detects MCP clients and can auto-configure them
(notifications: `mcp.client.detected`, `mcp.client.autoconfigured`, `mcp.client.error.autoconfigured`,
`mcp.client.wrong.port.detected`). `McpServerHeadlessStarter` provides `appStarter id="mcpServer"`
(stdin/stdout or HTTP/stdio launching — the `McpStdioRunnerKt` file was not in the cellophane set).

`Statistics`: `McpServerCounterUsagesCollector` (+ `McpToolNameValidator`), application usages
collector, telemetry scope `mcpServer` (`mcp.server.ot.trace` registry key).

---

## 6. Cellophane gaps & caveats

1. **`McpServerService.mcpToolToRegisteredTool` dispatch lambda** (lines ~1835–2330 of the
   cellophaned class): "Unable to fully structure code / Could not resolve type clashes" and the
   inner tool-execution lambda throws `IllegalStateException("Cellophane failed")`. The
   reconstruction above (project resolution order, call-info wrapping, VFS/document listeners,
   error mapping) is from surrounding intact code + call sites — treat exact listener semantics
   and the result-building lambda as inferred.
2. **`McpServerServiceKt`/`Mcp_sdk_utilKt`/`Schema_utilKt`**: missing classes from
   `io.modelcontextprotocol.kotlin.sdk.*` (MCP Kotlin SDK) and `io.github.smiley4.schemakenerator.*`
   weren't on the source-recovery classpath, so calls are shown but SDK internals (e.g. `Server.createSession`,
   `SseServerTransport`, `ToolSchema`) are not verified.
3. `Execution_utilKt.checkUserConfirmationIfNeeded` main body failed to cellophane; the
   confirmation dialog + Brave-mode checkbox are fully visible in `askConfirmation`, and the
   `WhenMappings` for `AskCommandExecutionMode` shows `ASK`/`RESPECT_GLOBAL_SETTINGS` handling.
4. `McpStdioRunnerKt` (stdio header constants) is referenced but not present; header names
   `IJ_MCP_SERVER_PROJECT_PATH`, `IJ_MCP_ALLOWED_TOOLS`, `IJ_MCP_SERVER_PORT` recovered from
   usages.
5. The web UI bundle (frontend) not analyzed.

---

## 7. Implications for IdeaLSP / xlsp

- The whole plugin is a **thin reflection → PSI bridge**: tools are just suspend functions
  annotated `@McpTool`/`@McpDescription`, args decoded via kotlinx.serialization, results
  serialized the same way. Porting/emulating the same shape for a subset is straightforward.
- **What IdeaLSP already covers** (semantic ops): `search_symbol`/`search_text`/`search_regex`
  ↔ xlsp semantic search; `get_symbol_info` ↔ define/hover/signature; `get_file_problems` ↔
  diagnostics; `rename_refactoring` ↔ refactor; `replace_text_in_file`/`create_new_file` ↔
  server-side apply edit.
- **Net-new gaps vs this plugin**: `build_project`, `get_run_configurations`,
  `execute_run_configuration`, `execute_terminal_command`, `get_repositories`, inspections on
  demand (`get_file_problems`), run/output capture with truncation, Brave-mode style confirmation,
  and MCP transport (SSE/Streamable HTTP/WebSocket + stdio) + client auto-config.
- Noteworthy mechanics to reuse:
  - implicit `projectPath` disambiguation + "ask the user" error with open-projects structured
    content (Ides have no CWD in MCP);
  - live tool add/remove via SDK `addTools`/`removeTools` on filter changes;
  - change-tracking per callId (VFS + document listeners) for diff/rollback to the client;
  - structuredContent gated by `mcp.server.structured.tool.output`.
