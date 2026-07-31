import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp dataflow command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  function handlerFor(dir: "from" | "to") {
    return (method: string) => {
      if (method === "workspace/symbol") {
        return {
          result: [{ name: "count", kind: 9, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 2, character: 10 } } } }],
        }
      }
      if (method === "textDocument/dataflowFrom") {
        return {
          result: [{ location: { uri: `file://${ws.javaFile}`, range: { start: { line: 6, character: 4 }, end: { line: 6, character: 9 } } } }],
        }
      }
      if (method === "textDocument/dataflowTo") {
        return {
          result: [{ location: { uri: "file:///p/Other.java", range: { start: { line: 12, character: 2 }, end: { line: 12, character: 7 } } } }],
        }
      }
      return undefined
    }
  }

  test("dataflow: from direction by default", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("from") })
    try {
      const { stdout, code } = await runCli(mock.port, ["dataflow", "count"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].file).toBe(ws.javaFile)
      expect(results[0].line).toBe(6)
      expect(results[0].direction).toBe("from")
      const req = mock.requests.find((r) => r.method === "textDocument/dataflowFrom")
      expect(req).toBeDefined()
      expect(req!.params.position).toHaveProperty("line")
    } finally {
      await mock.close()
    }
  })

  test("dataflow: to direction", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("to") })
    try {
      const { stdout } = await runCli(mock.port, ["df", "count", "--dir=to"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].file).toBe("/p/Other.java")
      expect(results[0].direction).toBe("to")
      const req = mock.requests.find((r) => r.method === "textDocument/dataflowTo")
      expect(req).toBeDefined()
    } finally {
      await mock.close()
    }
  })

  test("dataflow: --line/--char direct position", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("from") })
    try {
      const { stdout } = await runCli(mock.port, ["dataflow", "in", "Main.java", "--line=2", "--char=10"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].file).toBe(ws.javaFile)
      expect(mock.requests.find((r) => r.method === "workspace/symbol")).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  test("dataflow: empty result", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "workspace/symbol") {
          return { result: [{ name: "count", kind: 9, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 2, character: 10 } } } }] }
        }
        if (method === "textDocument/dataflowFrom") return { result: null }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["dataflow", "count"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
