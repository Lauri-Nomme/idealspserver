import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export interface CallResult {
  name: string
  kind: number
  file: string
  line: number
  character: number
  fromRanges?: { line: number; character: number }[]
  direction: "incoming" | "outgoing"
}

export async function getCallHierarchy(
  client: LspClient,
  pos: SymbolPosition,
  direction: "incoming" | "outgoing"
): Promise<CallResult[]> {
  const prepare = await client.sendRequest("textDocument/prepareCallHierarchy", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 15_000)

  const items = prepare?.result
  if (!items || !Array.isArray(items) || items.length === 0) return []

  const method = direction === "incoming"
    ? "callHierarchy/incomingCalls"
    : "callHierarchy/outgoingCalls"

  const resp = await client.sendRequest(method, { item: items[0] }, 15_000)
  const calls = resp?.result
  if (!calls || !Array.isArray(calls)) return []

  return calls.map((c: any) => ({
    name: (c.from || c.to)?.name,
    kind: (c.from || c.to)?.kind,
    file: ((c.from || c.to)?.uri || "").replace(/^file:\/\//, ""),
    line: (c.from || c.to)?.selectionRange?.start?.line,
    character: (c.from || c.to)?.selectionRange?.start?.character,
    fromRanges: (c.fromRanges || []).map((r: any) => ({
      line: r.start?.line,
      character: r.start?.character,
    })),
    direction,
  }))
}
