import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function findReferences(client: LspClient, pos: SymbolPosition): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/references", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
    context: { includeDeclaration: true },
  }, 15_000)

  const raw = resp?.result || []
  if (!Array.isArray(raw)) return []
  return raw.map((r: any) => ({
    file: (r.uri || "").replace(/^file:\/\//, ""),
    line: r.range?.start?.line,
    character: r.range?.start?.character,
  }))
}
