import { LspClient } from "../lsp-client"

interface TextEdit {
  range: { start: { line: number; character: number }; end: { line: number; character: number } }
  newText: string
}

export async function formatFile(
  client: LspClient,
  file: string,
  workspaceRoot: string,
  range?: { start: { line: number; character: number }; end: { line: number; character: number } },
): Promise<{ applied: boolean; file: string; edits: TextEdit[] }> {
  const absPath = file.startsWith("/") ? file : `${workspaceRoot}/${file}`
  const uri = `file://${absPath}`
  const original = await Bun.file(absPath).text()

  client.sendNotification("textDocument/didOpen", {
    textDocument: { uri, languageId: "java", version: 1, text: original },
  })
  await client.drainNotifications(3000)

  const options = { tabSize: 4, insertSpaces: true, insertFinalNewline: true }
  const resp = range
    ? await client.sendRequest("textDocument/rangeFormatting", {
        textDocument: { uri },
        range,
        options,
      }, 30_000)
    : await client.sendRequest("textDocument/formatting", {
        textDocument: { uri },
        options,
      }, 30_000)

  client.sendNotification("textDocument/didClose", { textDocument: { uri } })

  const edits: TextEdit[] = resp?.result || []
  return { applied: edits.length > 0, file: absPath, edits }
}
