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
import { getCodeActions, applyCodeAction } from "./operations/actions"
import { getCallHierarchy } from "./operations/calls"
import { getDataflow } from "./operations/dataflow"
import { semanticSearch } from "./operations/semantic"
import { listInspections, runInspection, runInspectionOnAllFiles } from "./operations/inspect"

interface Output {
  success: boolean
  operation: string
  query?: string
  file?: string
  error?: string
  hint?: string
  count?: number
  results?: any[]
  tree?: any[]
  applied?: boolean
  failureReason?: string
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

function countSymbols(nodes: any[]): number {
  let count = 0
  for (const node of nodes) {
    count++
    if (node.children) count += countSymbols(node.children)
  }
  return count
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
    printJson(fail("help", "Usage: xlsp <operation> [symbol] [in <file>] [--port N] [--wait] [--context N] [--severity error|warn|info|hint] [--constraint $Var.key=val] [--lang java] [--scope project|file]"))
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
        const hasQuery = symbol.length > 0
        const isFileOnly = !!file && !hasQuery
        const tree = isFileOnly
        const kind = args.kind as string
        const visibility = args.visibility as string
        const results = await searchSymbols(client, symbol || "", file, { kind, visibility, tree: tree as boolean })
        if (tree && Array.isArray(results)) {
          printJson({ success: true, operation, file, count: countSymbols(results), tree: results })
        } else if (results && results.results) {
          printJson(ok(operation, results.results, symbol, file))
        } else if (Array.isArray(results)) {
          printJson(ok(operation, results, symbol, file))
        } else {
          printJson({ success: true, operation, file, count: 0, results: [] })
        }
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

      case "apply": {
        if (!file) { printJson(fail(operation, "file required", "Use 'xlsp actions <file>' to get action titles first")); return }
        if (!symbol) { printJson(fail(operation, "action title required", "Specify the action title to apply")); return }
        const absPath = file.startsWith("/") ? file : `${wsRoot}/${file}`
        const uri = `file://${absPath}`
        const text = await Bun.file(absPath).text()
        const lines = text.split("\n")
        client.sendNotification("textDocument/didOpen", {
          textDocument: { uri, languageId: "java", version: 1, text },
        })
        await client.drainNotifications(5000)
        const actionsResp = await client.sendRequest("textDocument/codeAction", {
          textDocument: { uri },
          range: {
            start: { line: 0, character: 0 },
            end: { line: lines.length - 1, character: lines[lines.length - 1].length },
          },
          context: { diagnostics: [] },
        }, 15_000)
        client.sendNotification("textDocument/didClose", { textDocument: { uri } })
        const actions = actionsResp?.result || []
        const action = actions.find((a: any) => a.title === symbol)
        if (!action) {
          printJson(fail(operation, "action not found", `Action "${symbol}" not available at this location`))
          break
        }
        const data = action.data ? JSON.parse(action.data) : { uri, range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } } }
        const result = await applyCodeAction(client, symbol, data.uri, data.range)
        printJson({
          success: true,
          operation: "apply",
          query: symbol,
          file,
          applied: result.applied,
          failureReason: result.failureReason,
        })
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
        const results = await runInspection(client, file, symbol, wsRoot)
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file))
        break
      }

      case "inspect-all":
      case "insp-all": {
        if (!symbol) { printJson(fail(operation, "inspection name required", "Use 'xlsp inspect-list' to discover available inspections")); return }
        const results = await runInspectionOnAllFiles(client, symbol, wsRoot)
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol))
        break
      }

      case "semantic":
      case "sem": {
        if (!symbol) { printJson(fail(operation, "pattern required", "Specify an SSR pattern like 'methods returning Optional'")); return }
        const scope = (args.scope as string) || "project"
        const lang = (args.lang as string) || "java"
        const absFile = file ? (file.startsWith("/") ? file : `${wsRoot}/${file}`) : undefined

        // Parse --constraint args: --constraint $ReturnType.type=Optional --constraint $MethodName.regex=get.*
        const constraints: Record<string, Record<string, string>> = {}
        const rawConstraints = Array.isArray(args.constraint) ? args.constraint : args.constraint ? [args.constraint] : []
        for (const c of rawConstraints) {
          const dotIdx = c.indexOf(".")
          const eqIdx = c.indexOf("=")
          if (dotIdx < 0 || eqIdx < 0) continue
          const varName = c.slice(0, dotIdx)
          const key = c.slice(dotIdx + 1, eqIdx)
          const value = c.slice(eqIdx + 1)
          if (!constraints[varName]) constraints[varName] = {}
          constraints[varName][key] = value
        }

        let raw: any[]
        try {
          raw = await semanticSearch(client, symbol, scope, absFile, lang, constraints)
        } catch (e: any) {
          printJson(fail(operation, e.message || String(e),
              "Supported constraint keys for --constraint $VarName.key=value:\n" +
              "  regex, text         - regex/text filter on the variable match\n" +
              "  notRegex, notText   - inverted regex filter\n" +
              "  type, exprType      - expression type filter (e.g. java.util.Optional)\n" +
              "  notType, notExprType - inverted expression type filter\n" +
              "  formalType, argType  - formal argument type filter\n" +
              "  within              - within constraint (e.g. class, method)\n" +
              "  contains            - contains constraint\n" +
              "  context, target     - context/target constraint\n" +
              "  minCount, min       - minimum occurrences\n" +
              "  maxCount, max       - maximum occurrences\n" +
              "  reference           - reference constraint\n" +
              "  script              - Groovy script constraint"))
          return
        }
        const results = raw.map((m: any) => ({
          file: (m.uri || "").replace(/^file:\/\//, ""),
          line: m.start?.line,
          character: m.start?.character,
          endLine: m.end?.line,
          endCharacter: m.end?.character,
          matchedText: m.matchedText,
        }))
        if (ctxLines) await addContext(results, ctxLines)
        printJson(ok(operation, results, symbol, file))
        break
      }

      default:
        printJson(fail(operation, `Unknown operation: ${operation}`, "Supported: status, define, references, hover, complete, symbols, diagnostics, implement, type-def, signature, actions, calls, dataflow, inspect-list, semantic"))
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
