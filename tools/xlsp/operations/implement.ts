import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function getImplementations(client: LspClient, pos: SymbolPosition): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/implementation", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 15_000)

  const raw = resp?.result
  if (!raw) return []
  const results = Array.isArray(raw) ? raw : [raw]
  return results.map((r: any) => ({
    file: (r.uri || "").replace(/^file:\/\//, "") || pos.file,
    line: r.range?.start?.line,
    character: r.range?.start?.character,
  }))
}
