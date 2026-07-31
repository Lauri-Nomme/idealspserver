import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp type-def command", () => {
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

  test("type-def: sends typeDefinition request", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/typeDefinition") {
          return {
            result: [{ uri: "file:///tmp/project/src/Type.java", range: { start: { line: 7, character: 0 }, end: { line: 7, character: 5 } } }],
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["type-def", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].file).toBe("/tmp/project/src/Type.java")
      expect(results[0].character).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("type-def alias td", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/typeDefinition" ? { result: null } : undefined)),
    })
    try {
      const { stdout } = await runCli(mock.port, ["td", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("type-def: LocationLink result", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/typeDefinition") {
          return {
            result: [{
              targetUri: "file:///tmp/project/src/Linked.java",
              targetSelectionRange: { start: { line: 3, character: 9 }, end: { line: 3, character: 13 } },
            }],
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["type-def", "Main"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].file).toBe("/tmp/project/src/Linked.java")
      expect(results[0].line).toBe(3)
    } finally {
      await mock.close()
    }
  })
})
