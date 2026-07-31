import { LspClient } from "../lsp-client"

interface TextEdit {
  range: { start: { line: number; character: number }; end: { line: number; character: number } }
  newText: string
}

function applyTextEdits(text: string, edits: TextEdit[]): string {
  const sorted = [...edits].sort((a, b) => {
    if (b.range.start.line !== a.range.start.line) return b.range.start.line - a.range.start.line
    return b.range.start.character - a.range.start.character
  })
  const lines = text.split("\n")
  for (const edit of sorted) {
    const { start, end } = edit.range
    const startIdx = lineCharToOffset(lines, start.line, start.character)
    const endIdx = lineCharToOffset(lines, end.line, end.character)
    if (startIdx < 0 || endIdx < startIdx) continue
    const full = lines.join("\n")
    const updated = full.slice(0, startIdx) + edit.newText + full.slice(endIdx)
    lines.length = 0
    lines.push(...updated.split("\n"))
  }
  return lines.join("\n")
}

function lineCharToOffset(lines: string[], line: number, character: number): number {
  if (line < 0 || line >= lines.length) return -1
  let offset = 0
  for (let i = 0; i < line; i++) offset += lines[i].length + 1
  return offset + Math.min(character, lines[line].length)
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
  if (edits.length === 0) {
    return { applied: false, file: absPath, edits: [] }
  }

  const formatted = applyTextEdits(original, edits)
  await Bun.write(absPath, formatted)

  return { applied: true, file: absPath, edits }
}
