import { LspClient } from "../lsp-client"
import { SymbolPosition } from "../symbol-resolver"

export async function renameSymbol(
  client: LspClient,
  pos: SymbolPosition,
  newName: string,
): Promise<any[]> {
  const resp = await client.sendRequest("textDocument/rename", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
    newName,
  }, 30_000)

  const edit = resp?.result
  if (!edit) return []

  const results: any[] = []
  const changes = edit.documentChanges || edit.changes
  if (!changes) return results

  if (Array.isArray(changes)) {
    for (const docChange of changes) {
      const textDocEdit = docChange.left || docChange
      if (!textDocEdit.textDocument || !textDocEdit.edits) continue
      const uri = textDocEdit.textDocument.uri
      for (const textEdit of textDocEdit.edits) {
        results.push({
          file: uri.replace(/^file:\/\//, ""),
          line: textEdit.range.start.line,
          character: textEdit.range.start.character,
          endLine: textEdit.range.end.line,
          endCharacter: textEdit.range.end.character,
          newText: textEdit.newText,
        })
      }
    }
  } else if (typeof changes === "object") {
    for (const [uri, edits] of Object.entries(changes)) {
      for (const textEdit of edits as any[]) {
        results.push({
          file: (uri as string).replace(/^file:\/\//, ""),
          line: textEdit.range.start.line,
          character: textEdit.range.start.character,
          endLine: textEdit.range.end.line,
          endCharacter: textEdit.range.end.character,
          newText: textEdit.newText,
        })
      }
    }
  }

  return results
}

export async function prepareRename(
  client: LspClient,
  pos: SymbolPosition,
): Promise<any> {
  const resp = await client.sendRequest("textDocument/prepareRename", {
    textDocument: { uri: pos.uri },
    position: { line: pos.line, character: pos.character },
  }, 15_000)

  const result = resp?.result
  if (!result) return null

  if (result.range) {
    return {
      range: {
        start: result.range.start,
        end: result.range.end,
      },
      placeholder: result.placeholder || "",
    }
  }
  if (result.start) {
    return {
      range: {
        start: result.start,
        end: result.end,
      },
    }
  }
  return result
}
