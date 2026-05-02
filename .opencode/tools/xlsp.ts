import { tool } from "@opencode-ai/plugin"

export default tool({
  description: "Symbol-first LSP operations — find definitions, references, hover, diagnostics, call hierarchy, etc. without needing line/column coordinates. Use 'status' to check server/project health.",
  args: {
    operation: tool.schema
      .enum(["status", "define", "references", "hover", "complete", "symbols", "diagnostics", "implement", "type-def", "signature", "actions", "calls", "dataflow"])
      .describe("LSP operation to perform"),
    symbol: tool.schema
      .string()
      .describe("Symbol name, query string, or file path (for diagnostics/actions)"),
    file: tool.schema
      .string()
      .optional()
      .describe("Optional file path to scope the query (relative to worktree)"),
    dir: tool.schema
      .enum(["incoming", "outgoing", "from", "to"])
      .optional()
      .describe("Direction for calls (incoming/outgoing) or dataflow (from/to)"),
  },
  async execute(args, context) {
    const proc = Bun.spawn(
      [
        "bun",
        "run",
        "tools/xlsp/cli.ts",
        args.operation,
        args.symbol,
        ...(args.file ? ["in", args.file] : []),
        ...(args.dir ? ["--dir", args.dir] : []),
      ],
      {
        cwd: context.worktree,
        env: { ...process.env, PROJECT_WORKSPACE: context.worktree },
        stdout: "pipe",
        stderr: "pipe",
      }
    )

    const stdout = await new Response(proc.stdout).text()
    const stderr = await new Response(proc.stderr).text()
    await proc.exited

    if (stderr) process.stderr.write(stderr)
    return stdout.trim()
  },
})
