import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp typehier command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  const item = {
    name: "Main",
    kind: 5,
    uri: "file:///p/Main.java",
    range: { start: { line: 0, character: 7 }, end: { line: 0, character: 11 } },
    selectionRange: { start: { line: 0, character: 7 }, end: { line: 0, character: 11 } },
  }

  function handlerFor(dir: "super" | "sub" | "both") {
    return (method: string) => {
      if (method === "workspace/symbol") {
        return {
          result: [{ name: "Main", kind: 5, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 0, character: 7 } } } }],
        }
      }
      if (method === "textDocument/prepareTypeHierarchy") return { result: [item] }
      if (method === "typeHierarchy/supertypes") {
        return { result: [{ name: "Object", kind: 5, uri: "file:///p/Object.java", range: { start: { line: 0, character: 0 } } }] }
      }
      if (method === "typeHierarchy/subtypes") {
        return { result: [{ name: "SubMain", kind: 5, uri: "file:///p/Sub.java", range: { start: { line: 0, character: 0 } } }] }
      }
      return undefined
    }
  }

  test("typehier: supertypes by default", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("super") })
    try {
      const { stdout, code } = await runCli(mock.port, ["typehier", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count.parents).toBe(1)
      expect(meta.count.children).toBe(0)
      expect(results[0].parents[0].name).toBe("Object")
      const req = mock.requests.find((r) => r.method === "textDocument/prepareTypeHierarchy")
      expect(req).toBeDefined()
    } finally {
      await mock.close()
    }
  })

  test("typehier alias th: subtypes", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("sub") })
    try {
      const { stdout } = await runCli(mock.port, ["th", "Main", "--dir=sub"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(meta.count.children).toBe(1)
      expect(results[0].children[0].name).toBe("SubMain")
      const req = mock.requests.find((r) => r.method === "typeHierarchy/subtypes")
      expect(req).toBeDefined()
      expect(mock.requests.find((r) => r.method === "typeHierarchy/supertypes")).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  test("typehier: both directions", async () => {
    const mock = await startMockServer({ requestHandler: handlerFor("both") })
    try {
      const { stdout } = await runCli(mock.port, ["typehier", "Main", "--dir=both"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.count.parents).toBe(1)
      expect(meta.count.children).toBe(1)
      expect(mock.requests.find((r) => r.method === "typeHierarchy/supertypes")).toBeDefined()
      expect(mock.requests.find((r) => r.method === "typeHierarchy/subtypes")).toBeDefined()
    } finally {
      await mock.close()
    }
  })

  test("typehier: no hierarchy -> error", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "workspace/symbol") {
          return { result: [{ name: "Main", kind: 5, location: { uri: `file://${ws.javaFile}`, range: { start: { line: 0, character: 7 } } } }] }
        }
        if (method === "textDocument/prepareTypeHierarchy") return { result: null }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["typehier", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("no type hierarchy")
    } finally {
      await mock.close()
    }
  })
})
