import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp complete command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("complete: sends completion request, maps items", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/completion") {
          return {
            result: {
              isIncomplete: false,
              items: [
                { label: "add", kind: 3, detail: "int add(int, int)", insertText: "add" },
                { label: "count", kind: 9, detail: "private int", insertText: "count" },
                { label: "run", kind: 2, detail: "void run()", insertText: "run" },
              ],
            },
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["complete", "Main.java"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(3)
      expect(results[0].label).toBe("add")
      expect(results[0].insertText).toBe("add")
      expect(results[1].label).toBe("count")
    } finally {
      await mock.close()
    }
  })

  test("complete: array result form", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/completion") {
          return { result: [{ label: "foo", kind: 6, detail: "void foo()" }] }
        }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["complete", "Main.java"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(meta.count).toBe(1)
      expect(results[0].label).toBe("foo")
    } finally {
      await mock.close()
    }
  })

  test("complete: explicit --line/--char position", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/completion" ? { result: [] } : undefined),
    })
    try {
      await runCli(mock.port, ["complete", "Main.java", "--line=3", "--char=5"], ws.root)
      const req = mock.requests.find((r) => r.method === "textDocument/completion")
      expect(req).toBeDefined()
      expect(req!.params.position).toEqual({ line: 3, character: 5 })
    } finally {
      await mock.close()
    }
  })

  test("complete: caps at 20 items", async () => {
    const items = Array.from({ length: 25 }, (_, i) => ({ label: `item${i}`, kind: 6 }))
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/completion" ? { result: { items } } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["complete", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.count).toBe(20)
    } finally {
      await mock.close()
    }
  })
})
