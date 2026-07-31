import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import {
  startMockServer,
  runCli,
  parseOutput,
  makeWorkspace,
  cleanupWorkspace,
  type TestWorkspace,
} from "../test-utils"

describe("xlsp structure command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  const structureResult = {
    workspaceRoot: "/project",
    project: { name: "myproject", sdk: "jdk-17" },
    modules: [
      { name: "server", type: "java", contentRoots: ["/project/server"], sdk: "jdk-17" },
      { name: "tools", type: "java", contentRoots: ["/project/tools"] },
    ],
    dependencyGraph: { "server": ["tools"] },
    entryPoints: [{ name: "Main", kind: "class", moduleName: "server" }],
    sourceLayout: [{ moduleName: "server", sourceType: "sources", root: "/project/server/src", packages: [{ name: "com.example", fileCount: 3 }] }],
  }

  test("structure: returns project structure with counts", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "idealsp/projectStructure" ? { result: structureResult } : undefined),
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["structure"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.operation).toBe("structure")
      expect(meta.count.modules).toBe(2)
      expect(meta.count.entryPoints).toBe(1)
      expect(meta.count.sourceRoots).toBe(1)
      const req = mock.requests.find((r) => r.method === "idealsp/projectStructure")
      expect(req).toBeDefined()
      expect(req!.params.scope).toBe("all")
    } finally {
      await mock.close()
    }
  })

  test("structure alias struct", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "idealsp/projectStructure" ? { result: { modules: [], entryPoints: [], sourceLayout: [] } } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["struct"], ws.root)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(meta.success).toBe(true)
      expect(meta.count.modules).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("structure: server error surfaces", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "idealsp/projectStructure" ? { error: { code: -32603, message: "not indexed" } } : undefined),
    })
    try {
      const { stdout } = await runCli(mock.port, ["structure"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("not indexed")
    } finally {
      await mock.close()
    }
  })
})
