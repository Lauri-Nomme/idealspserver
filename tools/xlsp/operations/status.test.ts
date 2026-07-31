import { describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
} from "../test-utils"

describe("xlsp status command", () => {
  test("status: reports server health and experimental capabilities", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "initialize") {
          return {
            result: {
              capabilities: {
                experimental: { projectRoot: "/project", moduleCount: 2, indexingComplete: true, version: "1.0.280", commit: "81f2f62f" },
              },
            },
          }
        }
        return undefined
      },
    })
    const ws = makeWorkspace()
    try {
      const { stdout, code } = await runCli(mock.port, ["status"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.operation).toBe("status")
      expect(meta.serverStatus.projectRoot).toBe("/project")
      expect(meta.serverStatus.indexingComplete).toBe(true)
      expect(meta.serverStatus.version).toBe("1.0.280")
      expect(meta.serverStatus.commit).toBe("81f2f62f")
    } finally {
      await mock.close()
      cleanupWorkspace(ws)
    }
  })

  test("status alias st without experimental info", async () => {
    const mock = await startMockServer({
      requestHandler: () => ({ result: { capabilities: {} } }),
    })
    const ws = makeWorkspace()
    try {
      const { stdout } = await runCli(mock.port, ["st"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(meta.success).toBe(true)
      expect(meta.serverStatus).toBeUndefined()
    } finally {
      await mock.close()
      cleanupWorkspace(ws)
    }
  })
})
