import { LspClient } from "../lsp-client"

export interface StructureResult {
  workspaceRoot?: string
  project?: Record<string, string>
  modules?: {
    name: string
    type: string
    contentRoots?: string[]
    sdk?: string
    languageLevel?: string
    facets?: string[]
    libraryDependencies?: string[]
  }[]
  sourceLayout?: {
    moduleName: string
    sourceType: string
    root: string
    packages: { name: string; fileCount: number }[]
  }[]
  entryPoints?: {
    name: string
    kind: string
    moduleName: string
  }[]
  message?: string
}

export async function getProjectStructure(client: LspClient): Promise<StructureResult> {
  const resp = await client.sendRequest("idealsp/projectStructure", { scope: "all" }, 30_000)
  if (resp?.error) throw new Error(resp.error.message || String(resp.error.code))
  return (resp?.result || {}) as StructureResult
}
