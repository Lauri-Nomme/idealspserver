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
  return {
    success: true,
    operation,
    query,
    file,
    count: results.length,
    results,
  }
}

function printJson(obj: Output): void {
  const { results, ...rest } = obj
  const lines = [JSON.stringify(rest)]
  if (results && results.length > 0) {
    for (const r of results) {
      lines.push(JSON.stringify(r))
    }
  }
  process.stdout.write(lines.join("\n") + "\n")
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
    printJson(fail("help", "Usage: xlsp <operation> [symbol] [in <file>] [--port N]"))
    process.exit(1)
  }

  const operation = positional[0]
  const inIdx = positional.indexOf("in")
  const symbol = positional.slice(1, inIdx >= 0 ? inIdx : positional.length).join(" ")
  const file = inIdx >= 0 ? positional.slice(inIdx + 1).join(" ") : undefined

  const client = new LspClient(port)

  try {
    await client.connect()

    // Initialize
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

    switch (operation) {
      case "define":
      case "def": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) {
          printJson(fail(operation, "symbol not found", "Try xlsp symbols <query> first"))
          return
        }
        const results = await defineSymbol(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "references":
      case "refs": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) {
          printJson(fail(operation, "symbol not found", "Try xlsp symbols <query> first"))
          return
        }
        const results = await findReferences(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "hover":
      case "h": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) {
          printJson(fail(operation, "symbol not found"))
          return
        }
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
        printJson(ok(operation, results, undefined, file || symbol))
        break
      }

      case "implement":
      case "impl": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const results = await getImplementations(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      case "type-def":
      case "td": {
        const pos = await resolveSymbolPosition(client, symbol, file, wsRoot)
        if (!pos) { printJson(fail(operation, "symbol not found")); return }
        const results = await getTypeDefinition(client, pos)
        if (pos.opened) client.sendNotification("textDocument/didClose", { textDocument: { uri: pos.uri } })
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
        printJson(ok(operation, results, symbol, file ?? pos.file))
        break
      }

      default:
        printJson(fail(operation, `Unknown operation: ${operation}`, "Supported: define, references, hover, complete, symbols, diagnostics, implement, type-def, signature, actions, calls, dataflow"))
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
