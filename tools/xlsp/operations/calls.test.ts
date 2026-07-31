import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp calls command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  const hierarchyItem = {
    name: "add",
    kind: 6,
    uri: `file://${ws ? ws.javaFile : ""}`,
    range: { start: { line: 4, character: 13 }, end: { line: 4, character: 16 } },
    selectionRange: { start: { line: 4, character: 13 }, end: { line: 4, character: 16 } },
  }

  function handlerFor(dir: "incoming" | "outgoing") {
    return (method: string) => {
      if (method === "workspace/symbol") {
        return {
          result: [{ name: "add", kind: 6, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 4, character: 13 } } } }],
        }
      }
      if (method === "textDocument/prepareCallHierarchy") return { result: [hierarchyItem] }
      if (method === "callHierarchy/incomingCalls") {
        return {
          result: [
            { from: { name: "run", kind: 6, uri: `file://${ws.javaFile}`, selectionRange: { start: { line: 9, character: 2 } } }, fromRanges: [{ start: { line: 10, character: 4 } }] },
          ],
        }
      }
      if (method === "callHierarchy/outgoingCalls") {
        return {
          result: [
            { to: { name: "helper", kind: 6, uri: "file:///p/Helper.java", selectionRange: { start: { line: 1, character: 2 } } }, fromRanges: [{ start: { line: 5, character: 6 } }] },
          ],
        }
      }
      return undefined
    }
  }

  test("calls: incoming by default", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("incoming") })
    try {
      const { stdout, code } = await runCli(mock.port, ["calls", "add"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].name).toBe("run")
      expect(results[0].direction).toBe("incoming")
      expect(results[0].fromRanges).toHaveLength(1)
      const prep = mock.requests.find((r) => r.method === "textDocument/prepareCallHierarchy")
      expect(prep).toBeDefined()
    } finally {
      await mock.close()
    }
  })

  test("calls: outgoing direction", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("outgoing") })
    try {
      const { stdout } = await runCli(mock.port, ["call", "add", "--dir=outgoing"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].name).toBe("helper")
      expect(results[0].direction).toBe("outgoing")
      expect(results[0].file).toBe("/p/Helper.java")
    } finally {
      await mock.close()
    }
  })

  test("calls: --line/--char direct position bypasses symbol resolution", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("incoming") })
    try {
      const { stdout } = await runCli(mock.port, ["calls", "in", "Main.java", "--line=4", "--char=13"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].name).toBe("run")
      expect(mock.requests.find((r) => r.method === "workspace/symbol")).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  test("calls: empty prepare result", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "workspace/symbol") {
          return { result: [{ name: "add", kind: 6, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 4, character: 13 } } } }] }
        }
        if (method === "textDocument/prepareCallHierarchy") return { result: null }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["calls", "add"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
