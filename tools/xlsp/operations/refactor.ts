import { LspClient } from "../lsp-client"

export async function refactor(
  client: LspClient,
  uri: string,
  refactorType: string,
  line: number,
  character: number,
  name?: string,
): Promise<{ applied: boolean; operation: string; failureReason?: string }> {
  const resp = await client.sendRequest("idealsp/refactor", {
    uri,
    type: refactorType,
    position: { line, character },
    name: name || null,
  }, 120_000)

  const result = resp?.result as any
  if (!result) {
    return { applied: false, operation: refactorType, failureReason: "no response from server" }
  }
  return {
    applied: result.applied === true,
    operation: result.operation || refactorType,
    failureReason: result.failureReason,
  }
}
