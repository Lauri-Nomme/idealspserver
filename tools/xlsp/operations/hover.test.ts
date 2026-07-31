import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp hover command", () => {
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

  test("hover: markdown contents", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/hover") {
          return {
            result: {
              contents: { kind: "markdown", value: "**class Main**\n\nA sample class." },
              range: { start: { line: 0, character: 7 }, end: { line: 0, character: 11 } },
            },
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["hover", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].text).toContain("**class Main**")
      expect(results[0].type).toBe("markdown")
      expect(results[0].range.start.line).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("hover alias h with string contents", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/hover" ? { result: { contents: "int add(int, int)" } } : undefined)),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["h", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(results[0].text).toBe("int add(int, int)")
    } finally {
      await mock.close()
    }
  })

  test("hover: array of marked strings", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/hover") {
          return {
            result: {
              contents: [{ value: "part one" }, { value: "part two" }],
            },
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["hover", "Main"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].text).toBe("part one\npart two")
    } finally {
      await mock.close()
    }
  })

  test("hover: empty result", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/hover" ? { result: null } : undefined)),
    })
    try {
      const { stdout } = await runCli(mock.port, ["hover", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
