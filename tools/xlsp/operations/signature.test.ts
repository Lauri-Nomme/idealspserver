import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp signature command", () => {
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

  test("signature: maps signatures and parameters", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/signatureHelp") {
          return {
            result: {
              signatures: [
                {
                  label: "int add(int a, int b)",
                  documentation: { value: "Adds two numbers" },
                  parameters: [
                    { label: "a", documentation: "first" },
                    { label: "b", documentation: "second" },
                  ],
                },
              ],
              activeSignature: 0,
              activeParameter: 1,
            },
          }
        }
        return undefined
      }),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["signature", "Main"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(1)
      expect(results[0].label).toBe("int add(int a, int b)")
      expect(results[0].documentation).toBe("Adds two numbers")
      expect(results[0].parameters).toHaveLength(2)
      expect(results[0].parameters[0].name).toBe("a")
      expect(results[0].activeParameter).toBe(1)
    } finally {
      await mock.close()
    }
  })

  test("signature alias sig: string documentation", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => {
        if (m === "textDocument/signatureHelp") {
          return { result: { signatures: [{ label: "void run()", documentation: "runs" }] } }
        }
        return undefined
      }),
    })
    try {
      const { stdout } = await runCli(mock.port, ["sig", "Main"], ws.root)
      const { results } = parseOutput(stdout)
      expect(results[0].label).toBe("void run()")
      expect(results[0].documentation).toBe("runs")
    } finally {
      await mock.close()
    }
  })

  test("signature: empty result", async () => {
    const mock = await startMockServer({
      requestHandler: workspaceHandler((m) => (m === "textDocument/signatureHelp" ? { result: null } : undefined)),
    })
    try {
      const { stdout } = await runCli(mock.port, ["signature", "Main"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
