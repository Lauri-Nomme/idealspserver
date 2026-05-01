import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function hoverSymbol(client: LspClient, pos: SymbolPosition): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/hover", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 10_000)

  const raw = resp?.result
  if (!raw) return []
  const contents = raw.contents
  const text = typeof contents === "string" ? contents
    : Array.isArray(contents) ? contents.map((c: any) => c.value || c).join("\n")
    : contents?.value || JSON.stringify(contents)
  return [{ type: raw.type || "markdown", text, range: raw.range }]
}
