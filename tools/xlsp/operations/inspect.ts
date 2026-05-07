import { LspClient } from "../lsp-client"
import { resolve } from "node:path"

export async function listInspections(
  client: LspClient,
  query: string
): Promise<any[]> {
  const resp = await client.sendRequest("$/inspection/list", { query }, 15_000)
  const raw = resp?.result || []
  if (!Array.isArray(raw)) return []
  return raw.map((i: any) => ({
    shortName: i.shortName,
    displayName: i.displayName,
    group: i.group,
    enabled: i.enabled,
    description: i.description,
  }))
}

export async function runInspection(
  client: LspClient,
  file: string,
  name: string,
  workRoot: string
): Promise<any[]> {
  const absPath = file.startsWith("/") ? file : resolve(workRoot, file)
  const uri = `file://${absPath}`
  const resp = await client.sendRequest("$/inspection/runByName", {
    textDocument: { uri },
    name,
  }, 30_000)
  const raw = resp?.result || []
  if (!Array.isArray(raw)) return []
  return raw.map((d: any) => ({
    severity: severityName(d.severity),
    message: d.message,
    code: d.code,
    line: d.range?.start?.line,
    character: d.range?.start?.character,
    endLine: d.range?.end?.line,
    endCharacter: d.range?.end?.character,
  }))
}

function severityName(n: number): string {
  switch (n) {
    case 1: return "error"
    case 2: return "warning"
    case 3: return "info"
    case 4: return "hint"
    default: return "unknown"
  }
}

