import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  withTextScan,
  MOCK_LOCATION,
  type TestWorkspace,
} from "../test-utils"

const TIMEOUT = 20_000

describe("file-based symbol resolution", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("resolves top-level class symbol via text scan and sends definition", async () => {
    const mock = await startMockServer({
      requestHandler: withTextScan((method) => {
        if (method === "textDocument/definition") return { result: [MOCK_LOCATION] }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["define", "Main", "in", "Main.java"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].file).toBe("/tmp/project/src/Other.java")

      const req = mock.requests.find((r) => r.method === "textDocument/definition")
      expect(req).toBeDefined()
      expect(req!.params.textDocument.uri).toBe(`file://${ws.javaFile}`)
      expect(req!.params.position).toHaveProperty("line")
      expect(req!.params.position).toHaveProperty("character")

      const openReq = mock.requests.find((r) => r.method === "textDocument/didOpen")
      expect(openReq).toBeDefined()
      const closeReq = mock.requests.find((r) => r.method === "textDocument/didClose")
      expect(closeReq).toBeDefined()
    } finally {
      await mock.close()
    }
  }, TIMEOUT)

  test("falls back to workspace/symbol when not found in file", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/documentSymbol") return { result: [] }
        if (method === "workspace/symbol") {
          return {
            result: [{
              name: "Missing",
              kind: 5,
              location: { uri: "file:///tmp/project/src/Missing.java", range: { start: { line: 4, character: 6 } } },
            }],
          }
        }
        if (method === "textDocument/definition") return { result: [MOCK_LOCATION] }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["define", "Missing", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(mock.requests.find((r) => r.method === "workspace/symbol")).toBeDefined()
    } finally {
      await mock.close()
    }
  }, TIMEOUT)

  test("documentSymbol match used when available (no text scan)", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/documentSymbol") {
          return {
            result: [{
              name: "Main",
              kind: 5,
              selectionRange: { start: { line: 2, character: 14 }, end: { line: 2, character: 18 } },
            }],
          }
        }
        if (method === "textDocument/definition") return { result: [MOCK_LOCATION] }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["define", "Main", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      const req = mock.requests.find((r) => r.method === "textDocument/definition")
      expect(req!.params.position).toEqual({ line: 2, character: 14 })
    } finally {
      await mock.close()
    }
  }, TIMEOUT)

  test("symbol not found in file or workspace -> error", async () => {
    const mock = await startMockServer({
      requestHandler: withTextScan((method) => {
        if (method === "workspace/symbol") return { result: [] }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["define", "Nope", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("symbol not found")
    } finally {
      await mock.close()
    }
  }, TIMEOUT)
})

describe("xlsp define command (workspace symbol path)", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  function workspaceHandler(extra?: (method: string) => any) {
    return (method: string) => {
      if (method === "workspace/symbol") {
        return {
          result: [{
            name: "Main",
            kind: 5,
            location: { uri: `file://${ws.javaFile}`, range: { start: { line: 0, character: 7 } } },
          }],
        }
      }
      if (extra) return extra(method)
      return undefined
    }
  }

  test("define alias def works", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/definition" ? { result: [MOCK_LOCATION] } : undefined)),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["def", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(results[0].file).toBe("/tmp/project/src/Other.java")
    } finally {
      await mock.close()
    }
  })

  test("LocationLink format (targetUri)", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/definition") {
          return {
            result: [{
              targetUri: "file:///tmp/project/src/Linked.java",
              targetSelectionRange: { start: { line: 2, character: 3 }, end: { line: 2, character: 8 } },
            }],
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["define", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(results[0].file).toBe("/tmp/project/src/Linked.java")
      expect(results[0].line).toBe(2)
    } finally {
      await mock.close()
    }
  })

  test("no workspace match -> symbol not found error with hint", async () => {
    const mock = await startMockServer({
      requestHandler: (m) => (m === "workspace/symbol" ? { result: [] } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["define", "Ghost"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("symbol not found")
      expect(meta.hint).toContain("xlsp symbols")
    } finally {
      await mock.close()
    }
  })

  test("context lines attached when local file readable", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/definition") {
          return { result: [{ uri: `file://${ws.javaFile}`, range: { start: { line: 3, character: 2 }, end: { line: 3, character: 5 } } }] }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["define", "Main", "--context=1"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].context).toBeDefined()
      expect(results[0].context.length).toBeGreaterThan(0)
      expect(results[0].context.some((c: any) => c.marker === ">")).toBe(true)
    } finally {
      await mock.close()
    }
  })
})
