import { LspClient } from "../lsp-client"

export interface SymbolTreeNode {
  name: string
  kind: string
  detail?: string
  line?: number
  character?: number
  children?: SymbolTreeNode[]
}

export interface SearchOptions {
  kind?: string
  visibility?: string
  tree?: boolean
}

const KIND_MAP: Record<string, string> = {
  "class": "class",
  "interface": "interface",
  "enum": "enum",
  "method": "method",
  "function": "function",
  "field": "field",
  "variable": "variable",
  "property": "property",
  "constructor": "constructor",
}

function getVisibility(detail?: string): string {
  if (!detail) return "package"
  const d = detail.toLowerCase()
  if (d.includes("private")) return "private"
  if (d.includes("protected")) return "protected"
  if (d.includes("public")) return "public"
  return "package"
}

function matchesKind(kind: string | number, filter?: string): boolean {
  if (!filter) return true
  const normalized = KIND_MAP[filter.toLowerCase()] || filter.toLowerCase()
  return String(kind).toLowerCase() === normalized
}

function matchesVisibility(detail: string | undefined, filter?: string): boolean {
  if (!filter) return true
  return getVisibility(detail) === filter.toLowerCase()
}

function filterTree(nodes: SymbolTreeNode[], kind?: string, visibility?: string): SymbolTreeNode[] {
  const result: SymbolTreeNode[] = []
  for (const node of nodes) {
    const nodeMatches = matchesKind(node.kind, kind) && matchesVisibility(node.detail, visibility)
    const filteredChildren = node.children
      ? filterTree(node.children, kind, visibility)
      : undefined

    if (nodeMatches || (filteredChildren && filteredChildren.length > 0)) {
      result.push({
        ...node,
        children: filteredChildren?.length ? filteredChildren : undefined,
      })
    }
  }
  return result
}

const SERVER_KIND_MAP: Record<number, string> = {
  1: "file",
  5: "class",
  6: "method",
  8: "field",
}

function kindToString(kind: number | string): string {
  if (typeof kind === "string") return kind
  return SERVER_KIND_MAP[kind] || "unknown"
}

function toTreeNode(sym: any): SymbolTreeNode {
  return {
    name: sym.name,
    kind: kindToString(sym.kind),
    detail: sym.detail,
    line: sym.selectionRange?.start?.line,
    character: sym.selectionRange?.start?.character,
    children: sym.children ? sym.children.map(toTreeNode) : undefined,
  }
}

function flattenTree(nodes: SymbolTreeNode[]): any[] {
  const result: any[] = []
  for (const node of nodes) {
    result.push({
      name: node.name,
      kind: node.kind,
      line: node.line,
      character: node.character,
      detail: node.detail,
    })
    if (node.children) result.push(...flattenTree(node.children))
  }
  return result
}

export async function searchSymbols(
  client: LspClient,
  query: string,
  file?: string,
  options?: SearchOptions
): Promise<any> {
  if (query) {
    const resp = await client.sendRequest("workspace/symbol", { query }, 15_000)
    const raw = resp?.result || []
    if (!Array.isArray(raw)) return options?.tree ? [] : { success: true, count: 0, results: [] }
    const results = raw.slice(0, 30).map((s: any) => ({
      name: s.name,
      kind: s.kind,
      file: (s.location?.uri || "").replace(/^file:\/\//, ""),
      line: s.location?.range?.start?.line,
      character: s.location?.range?.start?.character,
      containerName: s.containerName,
    }))
    return options?.tree ? results : { success: true, count: results.length, results }
  }

  if (file) {
    const uri = `file://${file}`
    try {
      const text = await Bun.file(file).text()
      client.sendNotification("textDocument/didOpen", {
        textDocument: { uri, languageId: "java", version: 1, text },
      })
      await client.drainNotifications(5000)
    } catch {
      return options?.tree ? [] : { success: true, count: 0, results: [] }
    }

    const resp = await client.sendRequest("textDocument/documentSymbol", {
      textDocument: { uri },
    }, 10_000)

    client.sendNotification("textDocument/didClose", { textDocument: { uri } })

    const raw = Array.isArray(resp?.result) ? resp.result : []
    const tree = raw.map(toTreeNode)

    if (options?.tree) {
      const filtered = filterTree(tree, options.kind, options.visibility)
      return filtered
    }

    const flat = flattenTree(tree)
    const filtered = flat.filter(
      (s) => matchesKind(s.kind, options?.kind) && matchesVisibility(s.detail, options?.visibility)
    )
    return { success: true, count: filtered.length, results: filtered }
  }

  return options?.tree ? [] : { success: true, count: 0, results: [] }
}
