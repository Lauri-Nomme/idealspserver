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

export interface GeneratedPattern {
  pattern: string
  constraints?: Record<string, Record<string, string>>
}

export function buildPattern(query: string): GeneratedPattern {
  const lower = query.toLowerCase().trim()

  // fields of type <type>
  const fieldsOfType = lower.match(/^fields\s+(of\s+)?type\s+(.+)/)
  if (fieldsOfType) return {
    pattern: "$Type$ $FieldName$;",
    constraints: { "$Type$": { regex: fieldsOfType[2] } },
  }

  // fields named <name>
  const fieldsNamed = lower.match(/^fields?\s+named\s+(.+)/)
  if (fieldsNamed) return {
    pattern: "$Type$ $FieldName$;",
    constraints: { "$FieldName$": { regex: fieldsNamed[1] } },
  }

  // methods returning <type>
  const methodsReturning = lower.match(/^methods?\s+returning\s+(.+)/)
  if (methodsReturning) return {
    pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$);",
    constraints: { "$ReturnType$": { regex: methodsReturning[1] } },
  }

  // methods named <name>
  const methodsNamed = lower.match(/^methods?\s+named\s+(.+)/)
  if (methodsNamed) return {
    pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$);",
    constraints: { "$MethodName$": { regex: methodsNamed[1] } },
  }

  // methods taking <type>
  const methodsTaking = lower.match(/^methods?\s+taking\s+(.+)/)
  if (methodsTaking) return {
    pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$);",
    constraints: { "$ParameterType$": { regex: methodsTaking[1] } },
  }

  // catch blocks catching <exception>
  const catchBlocks = lower.match(/^catch\s+(blocks?\s+)?catching\s+(.+)/)
  if (catchBlocks) return {
    pattern: "catch ($ExceptionType$ $e$) { $Statement*$; }",
    constraints: { "$ExceptionType$": { regex: catchBlocks[2] } },
  }

  // null checks / null == expr / expr == null
  if (lower.includes("null check") || lower === "null checks" || lower === "null") return {
    pattern: "if ($Expr$ == null) { $Statement*$; }",
  }

  // method calls with null arg
  if (lower.includes("null arg")) return {
    pattern: "$Method$($Arg1*$, null, $Arg2*$)",
  }

  // new statements creating <type>
  const newStmts = lower.match(/^new\s+(.+)/)
  if (newStmts) return {
    pattern: "new $Type$($ParameterType$ $Parameter$)",
    constraints: { "$Type$": { regex: newStmts[1] } },
  }

  // catch blocks (no specific exception)
  if (lower.startsWith("catch")) return {
    pattern: "catch ($ExceptionType$ $e$) { $Statement*$; }",
  }

  // methods (generic)
  if (lower.startsWith("method")) return {
    pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$);",
  }

  // fields (generic)
  if (lower.startsWith("field")) return {
    pattern: "$Type$ $FieldName$;",
  }

  // if it already looks like an SSR pattern (contains $ signs), pass through
  if (query.includes("$")) return { pattern: query }

  // otherwise, treat the query as a regex constraint on method names
  return {
    pattern: "$ReturnType$ $MethodName$($ParameterType$ $Parameter$);",
    constraints: { "$MethodName$": { regex: query } },
  }
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
