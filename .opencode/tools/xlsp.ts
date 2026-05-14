import { tool } from "@opencode-ai/plugin"

export default tool({
  description: "Symbol-first LSP operations — find definitions, references, hover, diagnostics, call hierarchy, semantic search, etc. without needing line/column coordinates. Use 'status' to check server/project health.",
  args: {
    operation: tool.schema
      .enum(["status", "define", "references", "hover", "complete", "symbols", "diagnostics", "implement", "type-def", "signature", "actions", "apply", "calls", "dataflow", "inspect-list", "inspect", "inspect-all", "semantic"])
      .describe("LSP operation to perform"),
    symbol: tool.schema
      .string()
      .describe("Symbol name, query string, or file path (for diagnostics/actions). For semantic search, use natural language: 'fields of type X', 'methods returning X', 'methods named X', 'methods taking X', 'catch blocks catching X', 'null checks', 'new X', or raw SSR patterns like '$Type$ $FieldName$;'"),
    file: tool.schema
      .string()
      .optional()
      .describe("Optional file path to scope the query (relative to worktree)"),
    dir: tool.schema
      .enum(["incoming", "outgoing", "from", "to"])
      .optional()
      .describe("Direction for calls (incoming/outgoing) or dataflow (from/to)"),
    wait: tool.schema
      .boolean()
      .optional()
      .describe("Wait for indexing to complete before running (recommended for semantic search)"),
    constraint: tool.schema
      .array(tool.schema.string())
      .optional()
      .describe("Variable constraints for semantic search. Format: $VarName.key=value. Supported keys: regex/text (text filter), notRegex/notText (inverted), type/exprType (e.g. java.util.Optional), notType/notExprType (inverted type), formalType/argType, within (class/method), contains, context/target, minCount/min, maxCount/max, reference, script. Example: $ReturnType$.regex=Optional"),
    lang: tool.schema
      .string()
      .optional()
      .describe("Language for semantic search (default: java)"),
    scope: tool.schema
      .string()
      .optional()
      .describe("Search scope for semantic search: project or file (default: project)"),
  },
  async execute(args, context) {
    const cmd = [
      "bun",
      "run",
      "tools/xlsp/cli.ts",
      args.operation,
      ...(args.symbol && args.symbol !== "undefined" ? [args.symbol] : []),
      ...(args.file ? ["in", args.file] : []),
      ...(args.dir ? ["--dir", args.dir] : []),
      ...(args.wait ? ["--wait"] : []),
      ...(args.lang ? ["--lang", args.lang] : []),
      ...(args.scope ? ["--scope", args.scope] : []),
    ]
    if (args.constraint) {
      for (const c of args.constraint) cmd.push("--constraint", c)
    }
    const proc = Bun.spawn(cmd, {
      cwd: context.worktree,
      env: { ...process.env, PROJECT_WORKSPACE: context.worktree },
      stdout: "pipe",
      stderr: "pipe",
    })

    const stdout = await new Response(proc.stdout).text()
    const stderr = await new Response(proc.stderr).text()
    await proc.exited

    if (stderr) process.stderr.write(stderr)
    return stdout.trim()
  },
})
