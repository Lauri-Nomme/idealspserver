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

describe("xlsp diagnostics command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  slowTest("diagnostics: collects publishDiagnostics notifications", async () => {
    const mock = await startMockServer({
      notificationHandler: (method, params) => {
        if (method === "textDocument/didOpen") {
          return [{
            method: "textDocument/publishDiagnostics",
            params: {
              uri: `file://${ws.javaFile}`,
              diagnostics: [
                { range: { start: { line: 4, character: 2 }, end: { line: 4, character: 5 } }, severity: 1, message: "Method 'add' never used", source: "IntelliJ" },
                { range: { start: { line: 2, character: 2 }, end: { line: 2, character: 7 } }, severity: 2, message: "Field can be final", source: "IntelliJ" },
              ],
            },
          }]
        }
        return []
      },
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["diagnostics", "Main.java"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(2)
      expect(results[0].severity).toBe("error")
      expect(results[0].message).toContain("add")
      expect(results[1].severity).toBe("warn")
    } finally {
      await mock.close()
    }
  })

  slowTest("diagnostics: --severity filter", async () => {
    const mock = await startMockServer({
      notificationHandler: (method) => {
        if (method === "textDocument/didOpen") {
          return [{
            method: "textDocument/publishDiagnostics",
            params: {
              uri: `file://${ws.javaFile}`,
              diagnostics: [
                { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } }, severity: 1, message: "err", source: "s" },
                { range: { start: { line: 1, character: 0 }, end: { line: 1, character: 1 } }, severity: 2, message: "warn", source: "s" },
                { range: { start: { line: 2, character: 0 }, end: { line: 2, character: 1 } }, severity: 3, message: "info", source: "s" },
              ],
            },
          }]
        }
        return []
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["diagnostics", "Main.java", "--severity=warn"], ws.root)
      const { meta, results } = parseOutput(stdout)
      expect(meta.count).toBe(1)
      expect(results[0].severity).toBe("warn")
      expect(results[0].message).toBe("warn")
    } finally {
      await mock.close()
    }
  })

  slowTest("diagnostics: alias diag, empty result", async () => {
    const mock = await startMockServer({
      notificationHandler: (method) =>
        method === "textDocument/didOpen"
          ? [{ method: "textDocument/publishDiagnostics", params: { uri: `file://${ws.javaFile}`, diagnostics: [] } }]
          : [],
    })
    try {
      const { stdout } = await runCli(mock.port, ["diag", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(true)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })
})
