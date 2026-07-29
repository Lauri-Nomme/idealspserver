import { LspClient } from "../lsp-client"

export async function refactor(
  client: LspClient,
  uri: string,
  refactorType: string,
  line: number,
  character: number,
  name?: string,
  targetPackageUri?: string,
  selectionStart?: { line: number; character: number },
  selectionEnd?: { line: number; character: number },
): Promise<{ applied: boolean; operation: string; failureReason?: string }> {
  const body: Record<string, any> = {
    uri,
    type: refactorType,
    position: { line, character },
    name: name || null,
  }
  if (targetPackageUri) {
    body.targetPackageUri = targetPackageUri
  }
  if (selectionStart) {
    body.startRange = selectionStart
  }
  if (selectionEnd) {
    body.endRange = selectionEnd
  }
  const resp = await client.sendRequest("idealsp/refactor", body, 120_000)

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
