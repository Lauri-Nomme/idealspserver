import { LspClient } from "../lsp-client"

export interface SemanticMatch {
  uri: string
  start: { line: number; character: number }
  end: { line: number; character: number }
  matchedText: string
}

export interface VarConstraint {
  regex?: string
  type?: string
  formalType?: string
  within?: string
  contains?: string
  minCount?: number
  maxCount?: number
  reference?: string
  script?: string
}

export async function semanticSearch(
  client: LspClient,
  pattern: string,
  scope?: string,
  file?: string,
  language?: string,
  constraints?: Record<string, Record<string, string>>,
): Promise<SemanticMatch[]> {
  const params: any = {
    pattern,
    scope: scope || "project",
    language: language || "java",
    fileUri: file ? `file://${file}` : undefined,
  }
  if (constraints && Object.keys(constraints).length > 0) {
    params.constraints = constraints
  }

  const resp = await client.sendRequest("textDocument/semanticSearch", params, 60_000)

  if (resp?.error) {
    throw new Error(resp.error.message || String(resp.error.code))
  }

  const raw = resp?.result
  if (!raw) return []
  return raw as SemanticMatch[]
}
