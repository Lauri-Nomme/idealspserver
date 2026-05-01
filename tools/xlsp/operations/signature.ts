import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function getSignatureHelp(client: LspClient, pos: SymbolPosition): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/signatureHelp", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 10_000)

  const raw = resp?.result
  if (!raw) return []
  const sigs = raw.signatures || []
  return sigs.map((s: any) => ({
    label: s.label,
    documentation: typeof s.documentation === "string" ? s.documentation : s.documentation?.value || "",
    parameters: (s.parameters || []).map((p: any) => ({ name: p.label, type: typeof p.documentation === "string" ? p.documentation : "" })),
    activeParameter: raw.activeParameter,
    activeSignature: raw.activeSignature,
  }))
}
