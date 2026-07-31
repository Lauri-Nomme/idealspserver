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

describe("xlsp inspect-list command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("inspect-list: lists inspections with query filter", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "$/inspection/list") {
          return {
            result: [
              { shortName: "UnusedDeclaration", displayName: "Unused declaration", group: "Declaration redundancy", enabled: true, description: "Reports unused symbols" },
              { shortName: "FieldCanBeLocal", displayName: "Field can be local", group: "Declaration redundancy", enabled: true },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["inspect-list", "unused"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(2)
      expect(results[0].shortName).toBe("UnusedDeclaration")
      expect(results[1].enabled).toBe(true)
      const req = mock.requests.find((r) => r.method === "$/inspection/list")
      expect(req).toBeDefined()
      expect(req!.params.query).toBe("unused")
    } finally {
      await mock.close()
    }
  })

  test("inspect-list alias insp", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "$/inspection/list" ? { result: [] } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["insp"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})

describe("xlsp inspect command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("inspect: runs inspection by name on a file", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "$/inspection/runByName") {
          return {
            result: [
              { range: { start: { line: 4, character: 2 }, end: { line: 4, character: 5 } }, severity: 2, message: "Method 'add' is never used", code: "UnusedDeclaration" },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["inspect", "UnusedDeclaration", "in", "Main.java"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].severity).toBe("warning")
      expect(results[0].message).toContain("never used")
      const req = mock.requests.find((r) => r.method === "$/inspection/runByName")
      expect(req).toBeDefined()
      expect(req!.params.name).toBe("UnusedDeclaration")
      expect(req!.params.textDocument.uri).toBe(`file://${ws.javaFile}`)
    } finally {
      await mock.close()
    }
  })

  test("inspect: missing file -> error", async () => {
    const mock = await startMockServer()
    try {
      const { stdout } = await runCli(mock.port, ["inspect", "UnusedDeclaration"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("file required")
    } finally {
      await mock.close()
    }
  })

  slowTest("inspect-all: runs with null textDocument", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "$/inspection/runByName" ? { result: [] } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["inspect-all", "UnusedDeclaration"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      const req = mock.requests.find((r) => r.method === "$/inspection/runByName")
      expect(req).toBeDefined()
      expect(req!.params.textDocument).toBeNull()
    } finally {
      await mock.close()
    }
  })
})
