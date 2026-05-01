import { LspClient } from "../lsp-client"

export async function getDiagnostics(
  client: LspClient,
  file: string,
  workspaceRoot: string
): Promise<any[]> {
  const absPath = file.startsWith("/") ? file : `${workspaceRoot}/${file}`
  const uri = `file://${absPath}`

  const text = await Bun.file(absPath).text()
  client.sendNotification("textDocument/didOpen", {
    textDocument: { uri, languageId: "java", version: 1, text },
  })

  client.clearDiagnostics()
  await client.drainNotifications(20_000)

  client.sendNotification("textDocument/didClose", { textDocument: { uri } })

  const collected = client.diagnosticsCollected()
  const all: any[] = []
  for (const d of collected) {
    for (const diag of d.diagnostics) {
      all.push({
        file: (d.uri || "").replace(/^file:\/\//, ""),
        line: diag.range.start.line,
        character: diag.range.start.character,
        severity: { 1: "error", 2: "warn", 3: "info", 4: "hint" }[diag.severity] || "?",
        message: diag.message,
        source: diag.source,
      })
    }
  }
  return all
}
