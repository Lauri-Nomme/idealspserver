import { LspClient } from "../lsp-client"

export async function getCodeActions(
  client: LspClient,
  file: string,
  workspaceRoot: string
): Promise<any[]> {
  const absPath = file.startsWith("/") ? file : `${workspaceRoot}/${file}`
  const uri = `file://${absPath}`

  const text = await Bun.file(absPath).text()
  const lines = text.split("\n")
  client.sendNotification("textDocument/didOpen", {
    textDocument: { uri, languageId: "java", version: 1, text },
  })
  await client.drainNotifications(8000)

  const resp = await client.sendRequest("textDocument/codeAction", {
    textDocument: { uri },
    range: {
      start: { line: 0, character: 0 },
      end: { line: lines.length - 1, character: lines[lines.length - 1].length },
    },
    context: { diagnostics: [] },
  }, 15_000)

  client.sendNotification("textDocument/didClose", { textDocument: { uri } })

  const raw = resp?.result
  if (!raw || !Array.isArray(raw)) return []
  return raw.slice(0, 20).map((a: any) => ({
    title: a.title,
    kind: a.kind,
    isPreferred: a.isPreferred,
  }))
}
