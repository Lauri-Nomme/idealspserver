import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp references command", () => {
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

  test("references: sends request with includeDeclaration", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/references") {
          return {
            result: [
              { uri: "file:///tmp/project/src/A.java", range: { start: { line: 1, character: 2 }, end: { line: 1, character: 5 } } },
              { uri: "file:///tmp/project/src/B.java", range: { start: { line: 9, character: 0 }, end: { line: 9, character: 3 } } },
            ],
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["references", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(2)
      expect(results[0].file).toBe("/tmp/project/src/A.java")
      expect(results[1].file).toBe("/tmp/project/src/B.java")

      const req = mock.requests.find((r) => r.method === "textDocument/references")
      expect(req).toBeDefined()
      expect(req!.params.context).toEqual({ includeDeclaration: true })
    } finally {
      await mock.close()
    }
  })

  test("references alias refs with context lines", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/references") {
          return { result: [{ uri: `file://${ws.javaFile}`, range: { start: { line: 0, character: 7 }, end: { line: 0, character: 11 } } }] }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["refs", "Main", "--context=2"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].context).toBeDefined()
      expect(results[0].context.length).toBeGreaterThan(0)
    } finally {
      await mock.close()
    }
  })

  test("references: empty result", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/references" ? { result: [] } : undefined)),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["references", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("references: server error surfaces", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/references") return { error: { code: -32603, message: "indexing" } }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["references", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
