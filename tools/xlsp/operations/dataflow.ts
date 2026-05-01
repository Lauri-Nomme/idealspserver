import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function getDataflow(
  client: LspClient,
  pos: SymbolPosition,
  direction: "from" | "to"
): Promise<any[]> {
  const method = direction === "from"
    ? "textDocument/dataflowFrom"
    : "textDocument/dataflowTo"

  const resp = await client.sendRequest(method, {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 20_000)

  const raw = resp?.result
  if (!raw) return []
  const results = Array.isArray(raw) ? raw : []
  return results.map((r: any) => {
    const loc = r.location || r
    return {
      file: (loc.uri || "").replace(/^file:\/\//, "") || pos.file,
      line: loc.range?.start?.line,
      character: loc.range?.start?.character,
      direction,
    }
  })
}
