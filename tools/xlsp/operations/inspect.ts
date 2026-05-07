import { LspClient } from "../lsp-client"

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
