import { LspClient } from "../lsp-client"

export async function searchSymbols(
  client: LspClient,
  query: string,
  file?: string
): Promise<any[]> {
  if (query) {
    const resp = await client.sendRequest("workspace/symbol", { query }, 15_000)
    const raw = resp?.result || []
    if (!Array.isArray(raw)) return []
    return raw.slice(0, 30).map((s: any) => ({
      name: s.name,
      kind: s.kind,
      file: (s.location?.uri || "").replace(/^file:\/\//, ""),
      line: s.location?.range?.start?.line,
      character: s.location?.range?.start?.character,
      containerName: s.containerName,
    }))
  }

  if (file) {
    const uri = `file://${file}`
    const resp = await client.sendRequest("textDocument/documentSymbol", {
      textDocument: { uri },
    }, 10_000)
    const raw = resp?.result || []
    const flat = flattenSymbols(Array.isArray(raw) ? raw : [])
    return flat.map((s: any) => ({
      name: s.name,
      kind: s.kind,
      line: s.selectionRange?.start?.line,
      character: s.selectionRange?.start?.character,
      detail: s.detail,
    }))
  }

  return []
}

function flattenSymbols(symbols: any[]): any[] {
  const result: any[] = []
  for (const sym of symbols) {
    if (sym?.name) result.push(sym)
    if (sym?.children) result.push(...flattenSymbols(sym.children))
  }
  return result
}
