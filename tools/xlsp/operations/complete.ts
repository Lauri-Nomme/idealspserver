import { LspClient } from "../lsp-client"

export async function getCompletions(
  client: LspClient,
  prefix: string,
  file: string | undefined,
  workspaceRoot: string,
  position?: { line: number; character: number }
): Promise<any[]> {
  let uri: string
  let text = ""
  if (file) {
    const absPath = file.startsWith("/") ? file : `${workspaceRoot}/${file}`
    uri = `file://${absPath}`
    text = await Bun.file(absPath).text()
    client.sendNotification("textDocument/didOpen", {
      textDocument: { uri, languageId: "java", version: 1, text },
    })
    await client.drainNotifications(5000)
  } else {
    uri = `file://${workspaceRoot}/dummy.java`
  }

  if (!position) {
    const lines = text.split("\n")
    position = { line: lines.length - 1, character: lines[lines.length - 1].length }
  }

  const resp = await client.sendRequest("textDocument/completion", {
    textDocument: { uri },
    position,
  }, 15_000)

  if (file) {
    client.sendNotification("textDocument/didClose", { textDocument: { uri } })
  }

  const raw = resp?.result
  if (!raw) return []
  const items = Array.isArray(raw) ? raw : (raw.items || [])
  return items.slice(0, 20).map((i: any) => ({
    label: i.label,
    kind: i.kind,
    detail: i.detail,
    insertText: i.insertText || i.label,
  }))
}
