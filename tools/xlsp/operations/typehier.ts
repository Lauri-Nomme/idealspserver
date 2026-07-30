import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function prepareTypeHierarchy(
  client: LspClient,
  pos: SymbolPosition
): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/prepareTypeHierarchy", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 15_000)

  const raw = resp?.result
  if (!raw) return []
  const items = Array.isArray(raw) ? raw : [raw]
  return items.map((i: any) => ({
    name: i.name,
    kind: i.kind,
    detail: i.detail,
    uri: i.uri,
    range: i.range,
    selectionRange: i.selectionRange,
    data: i.data,
    file: (i.uri || "").replace(/^file:\/\//, ""),
    line: i.range?.start?.line,
    character: i.range?.start?.character,
  }))
}

export async function getTypeHierarchySupertypes(
  client: LspClient,
  item: any
): Promise<any[]> {
  const resp = await client.sendRequest("typeHierarchy/supertypes", {
    item,
  }, 15_000)

  const raw = resp?.result
  if (!raw) return []
  const items = Array.isArray(raw) ? raw : [raw]
  return items.map((i: any) => ({
    name: i.name,
    detail: i.detail,
    kind: i.kind,
    uri: i.uri,
    file: (i.uri || "").replace(/^file:\/\//, ""),
    line: i.range?.start?.line,
  }))
}

export async function getTypeHierarchySubtypes(
  client: LspClient,
  item: any
): Promise<any[]> {
  const resp = await client.sendRequest("typeHierarchy/subtypes", {
    item,
  }, 15_000)

  const raw = resp?.result
  if (!raw) return []
  const items = Array.isArray(raw) ? raw : [raw]
  return items.map((i: any) => ({
    name: i.name,
    detail: i.detail,
    kind: i.kind,
    uri: i.uri,
    file: (i.uri || "").replace(/^file:\/\//, ""),
    line: i.range?.start?.line,
  }))
}
