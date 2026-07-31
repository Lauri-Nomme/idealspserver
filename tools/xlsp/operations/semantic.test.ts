import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp semantic command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("semantic: natural language 'fields of type' translated to SSR pattern", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/semanticSearch") {
          return {
            result: [
              { uri: `file://${ws.javaFile}`, start: { line: 2, character: 2 }, end: { line: 2, character: 7 }, matchedText: "int count;" },
            ],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["semantic", "fields of type int"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].file).toBe(ws.javaFile)
      expect(results[0].matchedText).toBe("int count;")

      const req = mock.requests.find((r) => r.method === "textDocument/semanticSearch")
      expect(req).toBeDefined()
      expect(req!.params.pattern).toBe("$Type$ $FieldName$;")
      expect(req!.params.scope).toBe("project")
      expect(req!.params.language).toBe("java")
      expect(req!.params.constraints).toEqual({ "$Type$": { regex: "int" } })
    } finally {
      await mock.close()
    }
  })

  test("semantic: raw SSR pattern passthrough without constraints", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/semanticSearch" ? { result: [] } : undefined),
    })
    try {
      await runCli(mock.port, ["semantic", "$Type$ $FieldName$;"], ws.root)
      const req = mock.requests.find((r) => r.method === "textDocument/semanticSearch")
      expect(req!.params.pattern).toBe("$Type$ $FieldName$;")
      expect(req!.params.constraints).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  test("semantic: --constraint flags merged with generated constraints", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/semanticSearch" ? { result: [] } : undefined),
    })
    try {
      await runCli(
        mock.port,
        ["semantic", "methods named getter", "--constraint=$ReturnType.type=java.util.Optional", "--constraint=$MethodName.regex=^get"],
        ws.root,
      )
      const req = mock.requests.find((r) => r.method === "textDocument/semanticSearch")
      expect(req!.params.constraints).toEqual({
        "$MethodName$": { regex: "^get" },
        "$ReturnType$": { type: "java.util.Optional" },
      })
    } finally {
      await mock.close()
    }
  })

  test("semantic: file scope and in-file path", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "textDocument/semanticSearch" ? { result: [] } : undefined),
    })
    try {
      await runCli(mock.port, ["semantic", "fields", "--scope=file", "in", "Main.java"], ws.root)
      const req = mock.requests.find((r) => r.method === "textDocument/semanticSearch")
      expect(req!.params.scope).toBe("file")
      expect(req!.params.fileUri).toBe(`file://${ws.javaFile}`)
    } finally {
      await mock.close()
    }
  })

  test("semantic: server error -> fail with constraint hint", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/semanticSearch") {
          return { error: { code: -32603, message: "bad pattern" } }
        }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["semantic", "fields of type X"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("bad pattern")
      expect(meta.hint).toContain("--constraint")
    } finally {
      await mock.close()
    }
  })

  test("semantic: missing pattern -> error", async () => {
    const mock = await startMockServer()
    try {
      const { stdout } = await runCli(mock.port, ["semantic"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("pattern required")
    } finally {
      await mock.close()
    }
  })

  test("semantic alias sem with null checks query", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/semanticSearch") {
          return {
            result: [{ uri: `file://${ws.javaFile}`, start: { line: 1, character: 0 }, end: { line: 1, character: 1 }, matchedText: "if (x == null) {}" }],
          }
        }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["sem", "null checks"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].matchedText).toBe("if (x == null) {}")
      const req = mock.requests.find((r) => r.method === "textDocument/semanticSearch")
      expect(req!.params.pattern).toBe("if ($Expr$ == null) { $Statement*$; }")
    } finally {
      await mock.close()
    }
  })
})
