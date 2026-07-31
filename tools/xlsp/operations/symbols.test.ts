import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  slowTest,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp symbols command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("symbols: workspace query search", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "workspace/symbol") {
          return {
            result: [
              { name: "Main", kind: 5, containerName: "com.example", location: { uri: "file:///p/Main.java", range: { start: { line: 3, character: 8 } } } },
              { name: "add", kind: 6, containerName: "Main", location: { uri: "file:///p/Main.java", range: { start: { line: 6, character: 12 } } } },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["symbols", "add"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(2)
      expect(results[0].name).toBe("Main")
      expect(results[0].file).toBe("/p/Main.java")
      expect(results[1].name).toBe("add")
      expect(results[1].containerName).toBe("Main")
    } finally {
      await mock.close()
    }
  })

  slowTest("symbols alias sym: file structure tree", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/documentSymbol") {
          return {
            result: [
              {
                name: "Main",
                kind: 5,
                selectionRange: { start: { line: 0, character: 7 }, end: { line: 0, character: 11 } },
                children: [
                  { name: "add", kind: 6, detail: "public", selectionRange: { start: { line: 4, character: 2 }, end: { line: 4, character: 5 } } },
                ],
              },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["sym", "in", "Main.java", "--tree"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.tree).toBeDefined()
      expect(meta.count).toBe(2)
      expect(meta.tree[0].name).toBe("Main")
      expect(meta.tree[0].children[0].name).toBe("add")
    } finally {
      await mock.close()
    }
  })

  slowTest("symbols: file symbols flat with --kind filter", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/documentSymbol") {
          return {
            result: [
              { name: "Main", kind: 5, selectionRange: { start: { line: 0, character: 7 }, end: { line: 0, character: 11 } } },
              { name: "count", kind: 8, detail: "private", selectionRange: { start: { line: 2, character: 2 }, end: { line: 2, character: 7 } } },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["symbols", "in", "Main.java", "--kind=field"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(meta.tree[0].name).toBe("count")
      expect(meta.tree[0].kind).toBe("field")
    } finally {
      await mock.close()
    }
  })

  test("symbols: no query and no file -> empty", async () => {
    const mock = await startMockServer()
    try {
      const { stdout } = await runCli(mock.port, ["symbols"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
