#!/usr/bin/env bun
import { LspClient } from "./lsp-client"
import { resolveSymbolPosition } from "./symbol-resolver"
import { defineSymbol } from "./operations/define"
import { findReferences } from "./operations/references"
import { hoverSymbol } from "./operations/hover"
import { getCompletions } from "./operations/complete"
import { searchSymbols } from "./operations/symbols"
import { getDiagnostics } from "./operations/diagnostics"
import { getImplementations } from "./operations/implement"
import { getTypeDefinition } from "./operations/type-def"
import { getSignatureHelp } from "./operations/signature"
import { getCodeActions } from "./operations/actions"
import { getCallHierarchy } from "./operations/calls"
import { getDataflow } from "./operations/dataflow"
import { listInspections, runInspection } from "./operations/inspect"

interface Output {
  success: boolean
  operation: string
  query?: string
  file?: string
  error?: string
  hint?: string
  count?: number
  results?: any[]
}

function fail(operation: string, error: string, hint?: string): Output {
  const out: Output = { success: false, operation, error }
  if (hint) out.hint = hint
  return out
}

function ok(operation: string, results: any[], query?: string, file?: string): Output {
  return { success: true, operation, query, file, count: results.length, results }
}

function printJson(obj: Output): void {
  const { results, ...rest } = obj
  const lines = [JSON.stringify(rest)]
  if (results && results.length > 0) {
    for (const r of results) lines.push(JSON.stringify(r))
  }
  process.stdout.write(lines.join("\n") + "\n")
}

async function addContext(results: any[], contextLines: number): Promise<void> {
  for (const r of results) {
    if (!r.file || r.line == null) continue
    const f = Bun.file(r.file)
    if (!(await f.exists())) continue
    const lines = (await f.text()).split("\n")
    const start = Math.max(0, r.line - contextLines)
    const end = Math.min(lines.length, r.line + contextLines + 1)
    r.context = []
    for (let i = start; i < end; i++) {
      r.context.push({ line: i, text: lines[i], marker: i === r.line ? ">" : " " })
    }
  }
}

function filterSeverity(results: any[], severity: string): any[] {
  if (!severity) return results
  return results.filter(r => r.severity === severity)
}

function parseArgs(argv: string[]) {
  let pos = 0
  const args: Record<string, string | boolean> = {}
  const positional: string[] = []

  while (pos < argv.length) {
    const arg = argv[pos]
    if (arg.startsWith("--")) {
      const eqIdx = arg.indexOf("=")
      if (eqIdx >= 0) {
        args[arg.slice(2, eqIdx)] = arg.slice(eqIdx + 1)
      } else {
        const next = argv[pos + 1]
        if (next && !next.startsWith("-")) {
          args[arg.slice(2)] = next
          pos++
        } else {
          args[arg.slice(2)] = true
        }
      }
    } else {
      positional.push(arg)
    }
    pos++
  }

  return { args, positional }
}

async function main() {
  const { args, positional } = parseArgs(Bun.argv.slice(2))
  const port = parseInt(args.port as string) || 8989

  if (positional.length < 1) {
    printJson(fail("help", "Usage: xlsp <operation> [symbol] [in <file>] [--port N] [--wait] [--context N] [--severity error|warn|info|hint]"))
    process.exit(1)
  }

  const operation = positional[0]
  const inIdx = positional.indexOf("in")
  const symbol = positional.slice(1, inIdx >= 0 ? inIdx : positional.length).join(" ")
  const file = inIdx >= 0 ? positional.slice(inIdx + 1).join(" ") : undefined
  const severity = (args.severity as string) || ""
  const ctxLines = parseInt(args.context as string) || 0

  const client = new LspClient(port)

  try {
    await client.connect()

    const wsRoot = process.env.PROJECT_WORKSPACE || process.cwd()
    const initResp = await client.sendRequest("initialize", {
      processId: process.pid,
      clientInfo: { name: "xlsp", version: "1.0" },
      workspaceFolders: [{ uri: `file://${wsRoot}`, name: "workspace" }],
      capabilities: {},
    })
    if (initResp.error) {
      printJson(fail(operation, initResp.error.message, "Server returned error"))
      return
    }

    client.sendNotification("initialized", {})

    if (args.wait) {
      await client.waitForIndexing(15)
    }

    switch (operation) {
      case "status":
      case "st": {
        const info: any = { success: true, operation: "status" }
        const exp = (initResp.result as any)?.capabilities?.experimental
            || (initResp.result as any)?.experimental
        if (exp) {
          info.serverStatus = exp
        }
        printJson(info)
        break
      }

      case "define":
      case "def": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found", "Try xlsp symbols <query> first")); return }
        const results = await defineSymbol(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "references":
      case "refs": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found", "Try xlsp symbols <query> first")); return }
        const results = await findReferences(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "hover":
      case "h": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const results = await hoverSymbol(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "complete":
      case "comp": {
        const position = args.line ? { line: parseInt(args.line as string), character: parseInt(args.char as string) || 0 } : undefined
        const results = await getCompletions(client, symbol, file, wsRoot, position)
        printJson(ok(operation, results, symbol, file))
        break
      }

      case "symbols":
      case "sym": {
        const results = await searchSymbols(client, symbol || "", file)
        printJson(ok(operation, results, symbol, file))
        break
      }

      case "diagnostics":
      case "diag": {
        const results = await getDiagnostics(client, file || symbol, wsRoot)
        const filtered = filterSeverity(results, severity)
        if (ctxLines) await addContext(filtered, ctxLines)
        printJson(ok(operation, filtered, undefined, file || symbol))
        break
      }

      case "implement":
      case "impl": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const results = await getImplementations(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "type-def":
      case "td": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const results = await getTypeDefinition(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "signature":
      case "sig": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const results = await getSignatureHelp(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "actions":
      case "act": {
        const results = await getCodeActions(client, file || symbol, wsRoot)
        printJson(ok(operation, results, undefined, file || symbol))
        break
      }

      case "calls":
      case "call": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const dir = (args.dir as string) || "incoming"
        const results = await getCallHierarchy(client, pos, dir === "outgoing" ? "outgoing" : "incoming")
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "dataflow":
      case "df": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const dir = (args.dir as string) || "from"
        const results = await getDataflow(client, pos, dir === "to" ? "to" : "from")
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "inspect-list":
      case "insp": {
        const query = symbol || ""
        const results = await listInspections(client, query)
        printJson(ok(operation, results, query))
        break
      }

      case "inspect":
      case "insp-run": {
        if (!file) { printJson(fail(operation, "file required", "Specify file with 'in <path>' after the inspection name")); return }
        if (!symbol) { printJson(fail(operation, "inspection name required", "Use 'xlsp inspect-list' to discover available inspections")); return }
        const results = await runInspection(client, file, symbol)
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file))
        break
      }

      default:
        printJson(fail(operation, `Unknown operation: ${operation}`, "Supported: status, define, references, hover, complete, symbols, diagnostics, implement, type-def, signature, actions, calls, dataflow, inspect-list"))
    }

    client.sendNotification("shutdown", {})
    client.sendNotification("exit", {})
  } catch (e: any) {
    if (e.code === "ECONNREFUSED") {
      printJson(fail(operation, "server unavailable", "Start LSP server on port " + port))
    } else {
      printJson(fail(operation, e.message || String(e)))
    }
  } finally {
    client.close()
  }
}

main()
