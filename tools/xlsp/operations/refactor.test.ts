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

describe("xlsp refactor command", () => {
  let ws: TestWorkspace

  beforeAll(() => {
    ws = makeWorkspace()
  })

  afterAll(() => {
    cleanupWorkspace(ws)
  })

  test("refactor extract-method with --pos range", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "idealsp/refactor") {
          return { result: { applied: true, operation: "extract-method" } }
        }
        return undefined
      },
    })
    try {
      const { stdout, code } = await runCli(
        mock.port,
        ["refactor", "extract-method", "newMethod", "in", "Main.java", "--pos=5:4-8:6"],
        ws.root,
      )
      const { meta } = parseOutput(stdout)
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.applied).toBe(true)
      const req = mock.requests.find((r) => r.method === "idealsp/refactor")
      expect(req).toBeDefined()
      expect(req!.params.type).toBe("extract-method")
      expect(req!.params.name).toBe("newMethod")
      expect(req!.params.position).toEqual({ line: 5, character: 4 })
      expect(req!.params.startRange).toEqual({ line: 5, character: 4 })
      expect(req!.params.endRange).toEqual({ line: 8, character: 6 })
    } finally {
      await mock.close()
    }
  })

  test("refactor introduce-variable with single --pos", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => (method === "idealsp/refactor" ? { result: { applied: true, operation: "introduce-variable" } } : undefined),
    })
    try {
      const { stdout } = await runCli(
        mock.port,
        ["refactor", "introduce-variable", "in", "Main.java", "--pos=10:8"],
        ws.root,
      )
      const { meta } = parseOutput(stdout)
      expect(meta.applied).toBe(true)
      const req = mock.requests.find((r) => r.method === "idealsp/refactor")
      expect(req!.params.type).toBe("introduce-variable")
      expect(req!.params.position).toEqual({ line: 10, character: 8 })
      expect(req!.params.startRange).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  slowTest("refactor move with --target", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "idealsp/refactor") return { result: { applied: true, operation: "move" } }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(
        mock.port,
        ["refactor", "move", "Main", "--target=src/movepkg", "in", "Main.java"],
        ws.root,
      )
      const { meta } = parseOutput(stdout)
      expect(meta.applied).toBe(true)
      const req = mock.requests.find((r) => r.method === "idealsp/refactor")
      expect(req!.params.type).toBe("move")
      expect(req!.params.targetPackageUri).toBe(`file://${ws.root}/src/movepkg`)
    } finally {
      await mock.close()
    }
  })

  test("refactor move without --target -> error", async () => {
    const mock = await startMockServer()
    try {
      const { stdout } = await runCli(mock.port, ["refactor", "move", "Main", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("--target")
    } finally {
      await mock.close()
    }
  })

  slowTest("refactor inline with symbol resolution", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/documentSymbol") return { result: [] }
        if (method === "idealsp/refactor") return { result: { applied: true, operation: "inline" } }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["refactor", "inline", "helper", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.applied).toBe(true)
      const req = mock.requests.find((r) => r.method === "idealsp/refactor")
      expect(req!.params.type).toBe("inline")
      expect(req!.params.name).toBe("helper")
    } finally {
      await mock.close()
    }
  })

  slowTest("refactor safe-delete", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "textDocument/documentSymbol") return { result: [] }
        if (method === "idealsp/refactor") return { result: { applied: true, operation: "safe-delete" } }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["refactor", "safe-delete", "oldMethod", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.applied).toBe(true)
      const req = mock.requests.find((r) => r.method === "idealsp/refactor")
      expect(req!.params.type).toBe("safe-delete")
    } finally {
      await mock.close()
    }
  })

  test("refactor unknown type -> error", async () => {
    const mock = await startMockServer()
    try {
      const { stdout } = await runCli(mock.port, ["refactor", "bogus", "in", "Main.java"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.success).toBe(false)
      expect(meta.error).toContain("unknown refactor type")
    } finally {
      await mock.close()
    }
  })

  test("refactor: server failure surfaces", async () => {
    const mock = await startMockServer({
      requestHandler: (method) => {
        if (method === "idealsp/refactor") {
          return { result: { applied: false, operation: "inline", failureReason: "no target found" } }
        }
        return undefined
      },
    })
    try {
      const { stdout } = await runCli(mock.port, ["refactor", "inline", "in", "Main.java", "--pos=1:0"], ws.root)
      const { meta } = parseOutput(stdout)
      expect(meta.applied).toBe(false)
      expect(meta.failureReason).toBe("no target found")
    } finally {
      await mock.close()
    }
  })
})
