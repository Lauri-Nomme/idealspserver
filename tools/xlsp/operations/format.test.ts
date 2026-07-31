import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp format command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("full-file format: sends formatting, reports success", async () => {
    const edits = [{ range: { start: { line: 1, character: 0 }, end: { line: 1, character: 0 } }, newText: "    " }]
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/formatting") return { result: edits }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["format", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.operation).toBe("format")
      expect(meta.applied).toBe(true)
      expect(meta.count).toBe(1)

      const formatReq = mock.requests.find((r) => r.method === "textDocument/formatting")
      expect(formatReq).toBeDefined()
      expect(formatReq!.params.textDocument.uri).toBe(`file://${ws.javaFile}`)
      expect(formatReq!.params.options).toEqual({ tabSize: 4, insertSpaces: true, insertFinalNewline: true })

      const openReq = mock.requests.find((r) => r.method === "textDocument/didOpen")
      expect(openReq).toBeDefined()
      expect(openReq!.params.textDocument.text).toBe(ws.content)

      const closeReq = mock.requests.find((r) => r.method === "textDocument/didClose")
      expect(closeReq).toBeDefined()
      expect(closeReq!.params.textDocument.uri).toBe(`file://${ws.javaFile}`)
    } finally {
      await mock.close()
    }
  })

  test("full-file format: no edits -> applied=false, count=0", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/formatting" ? { result: [] } : undefined),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["format", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.applied).toBe(false)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("range format: --pos single point expands to range", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/rangeFormatting" ? { result: [] } : undefined),
    })
    try {
      await runCli(mock.port, ["format", "Main.java", "--pos=1:2"], ws.root)
      const rangeReq = mock.requests.find((r) => r.method === "textDocument/rangeFormatting")
      expect(rangeReq).toBeDefined()
      expect(rangeReq!.params.range).toEqual({
        start: { line: 1, character: 2 },
        end: { line: 1, character: 2 },
      })
      const fullReq = mock.requests.find((r) => r.method === "textDocument/formatting")
      expect(fullReq).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  test("range format: --pos with explicit end range", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/rangeFormatting" ? { result: [] } : undefined),
    })
    try {
      await runCli(mock.port, ["format", "Main.java", "--pos=1:0-2:4"], ws.root)
      const rangeReq = mock.requests.find((r) => r.method === "textDocument/rangeFormatting")
      expect(rangeReq).toBeDefined()
      expect(rangeReq!.params.range).toEqual({
        start: { line: 1, character: 0 },
        end: { line: 2, character: 4 },
      })
    } finally {
      await mock.close()
    }
  })

  test("format with absolute path", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/formatting" ? { result: [] } : undefined),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["format", ws.javaFile], ws.root)
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.file).toBe(ws.javaFile)
      const formatReq = mock.requests.find((r) => r.method === "textDocument/formatting")
      expect(formatReq!.params.textDocument.uri).toBe(`file://${ws.javaFile}`)
    } finally {
      await mock.close()
    }
  })

  test("format: file required error", async () => {
    const mock = await startMockServer()
    try {
      const { stdout, code } = await runCli(mock.port, ["format"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(false)
      expect(meta.operation).toBe("format")
      expect(meta.error).toContain("file required")
    } finally {
      await mock.close()
    }
  })

  test("format alias 'fmt' works", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/formatting" ? { result: [] } : undefined),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["fmt", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.operation).toBe("fmt")
    } finally {
      await mock.close()
    }
  })

  test("format results include edit details", async () => {
    const edits = [
      { range: { start: { line: 1, character: 0 }, end: { line: 1, character: 0 } }, newText: "    " },
      { range: { start: { line: 2, character: 0 }, end: { line: 2, character: 0 } }, newText: "    " },
    ]
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/formatting" ? { result: edits } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["format", "Main.java"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results.length).toBe(2)
      expect(results[0].line).toBe(1)
      expect(results[0].character).toBe(0)
      expect(results[0].newText).toBe("    ")
      expect(results[1].line).toBe(2)
      expect(results[1].endLine).toBe(2)
    } finally {
      await mock.close()
    }
  })
})
