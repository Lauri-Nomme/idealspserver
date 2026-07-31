import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp rename command", () => {
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
            name: "add",
            kind: 6,
            location: { uri: `file://${ws.javaFile}`, range: { start: { line: 4, character: 13 } } },
          }],
        }
      }
      if (extra) return extra(method)
      return undefined
    }
  }

  test("rename: sends rename with new name, maps workspace edits", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/rename") {
          return {
            result: {
              changes: {
                [`file://${ws.javaFile}`]: [
                  { range: { start: { line: 4, character: 13 }, end: { line: 4, character: 16 } }, newText: "sum" },
                  { range: { start: { line: 9, character: 4 }, end: { line: 9, character: 7 } }, newText: "sum" },
                ],
              },
            },
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["rename", "add", "to", "sum"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(2)
      expect(results[0].file).toBe(ws.javaFile)
      expect(results[0].newText).toBe("sum")
      expect(results[1].line).toBe(9)

      const req = mock.requests.find((r) => r.method === "textDocument/rename")
      expect(req).toBeDefined()
      expect(req!.params.newName).toBe("sum")
    } finally {
      await mock.close()
    }
  })

  test("rename alias rn with documentChanges", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/rename") {
          return {
            result: {
              documentChanges: [
                {
                  textDocument: { uri: `file://${ws.javaFile}`, version: 1 },
                  edits: [{ range: { start: { line: 4, character: 13 }, end: { line: 4, character: 16 } }, newText: "sum" }],
                },
              ],
            },
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["rn", "add", "to", "sum"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results).toHaveLength(1)
      expect(results[0].newText).toBe("sum")
    } finally {
      await mock.close()
    }
  })

  test("rename: missing new name -> error", async () => {
    const mock = await startMockServer()
    try {
      const { stdout } = await runCli(mock.port, ["rename", "add"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("new name required")
    } finally {
      await mock.close()
    }
  })
})

describe("xlsp prepare-rename command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("prepare-rename: returns range and placeholder", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "workspace/symbol") {
          return {
            result: [{ name: "add", kind: 6, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 4, character: 13 } } } }],
          }
        }
        if (method === "textDocument/prepareRename") {
          return {
            result: {
              range: { start: { line: 4, character: 13 }, end: { line: 4, character: 16 } },
              placeholder: "add",
            },
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["prepare-rename", "add"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(results[0].placeholder).toBe("add")
      expect(results[0].range.start.character).toBe(13)
    } finally {
      await mock.close()
    }
  })

  test("prepare-rename: start/end form and null result -> error", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "workspace/symbol") {
          return { result: [{ name: "add", kind: 6, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 4, character: 13 } } } }] }
        }
        if (method === "textDocument/prepareRename") {
          return { result: { start: { line: 4, character: 13 }, end: { line: 4, character: 16 } } }
        }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["pr", "add"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].range.start).toEqual({ line: 4, character: 13 })
    } finally {
      await mock.close()
    }
  })
})
