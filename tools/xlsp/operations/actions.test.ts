import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp actions command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  slowTest("actions: lists code actions for file", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/codeAction") {
          return {
            result: [
              { title: "Import class", kind: "quickfix", isPreferred: false, data: "{\"uri\":\"x\"}" },
              { title: "Rename", kind: "refactor", isPreferred: true, data: "{\"uri\":\"y\"}" },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["actions", "Main.java"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(2)
      expect(results[0].title).toBe("Import class")
      expect(results[0].kind).toBe("quickfix")
      expect(results[1].isPreferred).toBe(true)
      const req = mock.requests.find((r) => r.method === "textDocument/codeAction")
      expect(req).toBeDefined()
      expect(req!.params.range.start).toEqual({ line: 0, character: 0 })
    } finally {
      await mock.close()
    }
  })

  slowTest("actions: empty result", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/codeAction" ? { result: [] } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["act", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})

describe("xlsp apply command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  const actionData = () => JSON.stringify({
    uri: `file://${ws.javaFile}`,
    range: { start: { line: 1, character: 2 }, end: { line: 1, character: 5 } },
  })

  slowTest("apply: resolves action by title and applies", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/codeAction") {
          return { result: [{ title: "Add missing annotation", data: actionData() }] }
        }
        if (method === "idealsp/codeActionApply") {
          return { result: { applied: true } }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["apply", "Add missing annotation", "in", "Main.java"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.applied).toBe(true)
      const applyReq = mock.requests.find((r) => r.method === "idealsp/codeActionApply")
      expect(applyReq).toBeDefined()
      expect(applyReq!.params.title).toBe("Add missing annotation")
      expect(applyReq!.params.uri).toBe(`file://${ws.javaFile}`)
    } finally {
      await mock.close()
    }
  })

  slowTest("apply: action not found -> error", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/codeAction" ? { result: [{ title: "Other action", data: actionData() }] } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["apply", "Missing action", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("action not found")
    } finally {
      await mock.close()
    }
  })
})
