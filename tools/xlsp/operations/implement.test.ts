import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp implement command", () => {
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

  test("implement: sends implementation request", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/implementation") {
          return {
            result: [{ uri: "file:///tmp/project/src/Impl.java", range: { start: { line: 10, character: 4 }, end: { line: 10, character: 7 } } }],
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["implement", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].file).toBe("/tmp/project/src/Impl.java")
      expect(results[0].line).toBe(10)
    } finally {
      await mock.close()
    }
  })

  test("implement alias impl with LocationLink", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/implementation") {
          return {
            result: [{
              targetUri: "file:///tmp/project/src/Other.java",
              targetRange: { start: { line: 1, character: 0 }, end: { line: 1, character: 2 } },
            }],
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["impl", "Main"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].file).toBe("/tmp/project/src/Other.java")
    } finally {
      await mock.close()
    }
  })

  test("implement: empty result", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/implementation" ? { result: null } : undefined)),
    })
    try {
      const { stdout } = await runCli(mock.port, ["implement", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
