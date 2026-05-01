import { LspClient } from "./lsp-client"

export interface SymbolPosition {
  uri: string
  file: string
  line: number
  character: number
  opened: boolean
}

export async function resolveSymbolPosition(
  client: LspClient,
  symbol: string,
  file: string | undefined,
  workspaceRoot: string
): Promise<SymbolPosition | null> {
  if (!symbol) return null

  if (file) {
    const absPath = file.startsWith("/") ? file : `${workspaceRoot}/${file}`
    const uri = `file://${absPath}`

    const text = await Bun.file(absPath).text()
    client.sendNotification("textDocument/didOpen", {
      textDocument: { uri, languageId: "java", version: 1, text },
    })

    await client.drainNotifications(8000)

    // Try documentSymbol first (top-level symbols: classes, fields, methods)
    const resp = await client.sendRequest("textDocument/documentSymbol", {
      textDocument: { uri },
    })

    const symbols = resp?.result || []
    const flat = flattenSymbols(symbols)
    const match = flat.find((s: any) => s.name === symbol)
    if (match) {
      return { uri, file: absPath, line: match.selectionRange.start.line, character: match.selectionRange.start.character, opened: true }
    }

    // Fallback: text scan for non-top-level symbols (methods, variables inside classes)
    const textPos = findSymbolInText(text, symbol)
    if (textPos) {
      return { uri, file: absPath, line: textPos.line, character: textPos.character, opened: true }
    }

    // Symbol not found — close file and try workspace search
    client.sendNotification("textDocument/didClose", { textDocument: { uri } })
  }

  const resp = await client.sendRequest("workspace/symbol", { query: symbol })
  const symbols = (resp?.result || []) as any[]
  if (symbols.length === 0) return null

  const match = symbols.find((s: any) => s.name === symbol) || symbols[0]
  const uri: string = match.location?.uri || match.uri || ""
  const filePath = uri.replace(/^file:\/\//, "")
  return {
    uri,
    file: filePath,
    line: match.location?.range?.start?.line || 0,
    character: match.location?.range?.start?.character || 0,
    opened: false,
  }
}

function findSymbolInText(text: string, symbol: string): { line: number; character: number } | null {
  const lines = text.split("\n")
  const patterns = [
    new RegExp(`\\b(public|private|protected|static|final|abstract|synchronized|native|default)?\\s*(class|interface|enum|record|@interface)?\\s*\\b${escapeRegex(symbol)}\\b`),
    new RegExp(`\\b${escapeRegex(symbol)}\\s*\\(`),
    new RegExp(`\\b${escapeRegex(symbol)}\\b`),
  ]
  for (const pattern of patterns) {
    for (let i = 0; i < lines.length; i++) {
      const match = lines[i].match(pattern)
      if (match && match.index !== undefined) {
        return { line: i, character: match.index + match[0].indexOf(symbol) }
      }
    }
  }
  return null
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}

function flattenSymbols(symbols: any[]): any[] {
  const result: any[] = []
  for (const sym of symbols) {
    if (sym?.name) result.push(sym)
    if (sym?.children) result.push(...flattenSymbols(sym.children))
  }
  return result
}
