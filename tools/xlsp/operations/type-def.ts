import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

function extractLocation(r: any): { uri: string; rangeStart: any; rangeEnd: any } {
  if (r.uri && r.range) return { uri: r.uri, rangeStart: r.range.start, rangeEnd: r.range.end }
  if (r.targetUri) return { uri: r.targetUri, rangeStart: r.targetSelectionRange?.start || r.targetRange?.start, rangeEnd: r.targetSelectionRange?.end || r.targetRange?.end }
  return { uri: r.uri || r.targetUri || "", rangeStart: r, rangeEnd: r }
}

export async function getTypeDefinition(client: LspClient, pos: SymbolPosition): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/typeDefinition", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 15_000)

  const raw = resp?.result
  if (!raw) return []
  const results = Array.isArray(raw) ? raw : [raw]
  return results.map((r: any) => {
    const loc = extractLocation(r)
    return {
      file: loc.uri.replace(/^file:\/\//, "") || pos.file,
      line: loc.rangeStart?.line,
      character: loc.rangeStart?.character,
    }
  })
}
