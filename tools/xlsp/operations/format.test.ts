import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { spawn } from "node:child_process"
import { createServer, type Server } from "node:net"
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

interface MockLspServer {
  server: Server
  port: number
  requests: { method: string; params: any; id: number }[]
  close: () => Promise<void>
}

function startMockServer(handler: (method: string, params: any, id: number) => any): Promise<MockLspServer> {
  const requests: { method: string; params: any; id: number }[] = []
  const server = createServer((socket) => {
    let buf = ""
    socket.setEncoding("utf8")
    socket.on("data", (data: string) => {
      buf += data
      while (true) {
        const endIdx = buf.indexOf("\r\n\r\n")
        if (endIdx === -1) break
        const header = buf.slice(0, endIdx)
        const match = header.match(/Content-Length:\s*(\d+)/i)
        if (!match) {
          buf = buf.slice(endIdx + 4)
          continue
        }
        const length = parseInt(match[1], 10)
        const bodyStart = endIdx + 4
        if (buf.length < bodyStart + length) break
        const body = JSON.parse(buf.slice(bodyStart, bodyStart + length))
        buf = buf.slice(bodyStart + length)

        if (body.method === "initialize") {
          const resp = {
            jsonrpc: "2.0",
            id: body.id,
            result: {
              capabilities: {},
              serverInfo: { name: "mock", version: "1.0" },
            },
          }
          send(socket, resp)
        } else if (body.id !== undefined && body.method) {
          requests.push({ method: body.method, params: body.params, id: body.id })
          const result = handler(body.method, body.params, body.id)
          if (result !== undefined) {
            send(socket, { jsonrpc: "2.0", id: body.id, result })
          }
        } else {
          requests.push({ method: body.method, params: body.params, id: -1 })
        }
      }
    })
  })

  return new Promise((resolve, reject) => {
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address()
      if (!addr || typeof addr === "string") {
        reject(new Error("no port"))
        return
      }
      resolve({
        server,
        port: addr.port,
        requests,
        close: () => new Promise((r) => server.close(() => r())),
      })
    })
  })
}

function send(socket: any, msg: any): void {
  const body = JSON.stringify(msg)
  socket.write(`Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`)
}

const REPO_ROOT = join(import.meta.dir, "..", "..", "..")

async function runCli(port: number, args: string[], wsRoot: string): Promise<{ stdout: string; code: number }> {
  return new Promise((resolve, reject) => {
    const proc = spawn("bun", ["run", "tools/xlsp/cli.ts", "--port", String(port), ...args], {
      cwd: REPO_ROOT,
      env: { ...process.env, XLSP_PORT: String(port), PROJECT_WORKSPACE: wsRoot },
    })
    let stdout = ""
    proc.stdout.on("data", (d: string) => (stdout += d))
    proc.stderr.on("data", (d: string) => (stdout += d))
    proc.on("error", reject)
    proc.on("close", (code) => resolve({ stdout, code: code ?? -1 }))
  })
}

describe("xlsp format command", () => {
  let wsRoot: string
  let javaFile: string

  beforeAll(() => {
    wsRoot = mkdtempSync(join(tmpdir(), "xlsp-format-"))
    javaFile = join(wsRoot, "Main.java")
    writeFileSync(javaFile, "class Main {\nint x = 1;\n}\n")
  })

  afterAll(() => {
    rmSync(wsRoot, { recursive: true, force: true })
  })

  test("full-file format: sends formatting, applies edits, reports success", async () => {
    const edits = [{ range: { start: { line: 1, character: 0 }, end: { line: 1, character: 0 } }, newText: "    " }]
    const mock = await startMockServer((method, params) => {
      if (method === "textDocument/formatting") return edits
      return undefined
    })
    try {
      const { stdout, code } = await runCli(mock.port, ["format", "Main.java"], wsRoot)
      const lines = stdout.trim().split("\n")
      const meta = JSON.parse(lines[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.operation).toBe("format")
      expect(meta.applied).toBe(true)
      expect(meta.count).toBe(1)

      const formatReq = mock.requests.find((r) => r.method === "textDocument/formatting")
      expect(formatReq).toBeDefined()
      expect(formatReq!.params.textDocument.uri).toBe(`file://${javaFile}`)
      expect(formatReq!.params.options).toEqual({ tabSize: 4, insertSpaces: true, insertFinalNewline: true })

      const openReq = mock.requests.find((r) => r.method === "textDocument/didOpen")
      expect(openReq).toBeDefined()
      expect(openReq!.params.textDocument.text).toBe("class Main {\nint x = 1;\n}\n")

      const closeReq = mock.requests.find((r) => r.method === "textDocument/didClose")
      expect(closeReq).toBeDefined()
      expect(closeReq!.params.textDocument.uri).toBe(`file://${javaFile}`)
    } finally {
      await mock.close()
    }
  })

  test("full-file format: no edits -> applied=false, count=0", async () => {
    const mock = await startMockServer(() => [])
    try {
      const { stdout, code } = await runCli(mock.port, ["format", "Main.java"], wsRoot)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.applied).toBe(false)
      expect(meta.count).toBe(0)
    } finally {
      await mock.close()
    }
  })

  test("range format: --pos single point expands to range", async () => {
    const mock = await startMockServer(() => [])
    try {
      await runCli(mock.port, ["format", "Main.java", "--pos=1:2"], wsRoot)
      const rangeReq = mock.requests.find((r) => r.method === "textDocument/rangeFormatting")
      expect(rangeReq).toBeDefined()
      expect(rangeReq!.params.range).toEqual({
        start: { line: 1, character: 2 },
        end: { line: 1, character: 2 },
      })
      const fullReq = mock.requests.find((r) => r.method === "textDocument/formatting")
      expect(fullReq).toBeUndefined()
    } finally {
      await mock.close()
    }
  })

  test("range format: --pos with explicit end range", async () => {
    const mock = await startMockServer(() => [])
    try {
      await runCli(mock.port, ["format", "Main.java", "--pos=1:0-2:4"], wsRoot)
      const rangeReq = mock.requests.find((r) => r.method === "textDocument/rangeFormatting")
      expect(rangeReq).toBeDefined()
      expect(rangeReq!.params.range).toEqual({
        start: { line: 1, character: 0 },
        end: { line: 2, character: 4 },
      })
    } finally {
      await mock.close()
    }
  })

  test("format with absolute path", async () => {
    const mock = await startMockServer(() => [])
    try {
      const { stdout, code } = await runCli(mock.port, ["format", javaFile], wsRoot)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.file).toBe(javaFile)
      const formatReq = mock.requests.find((r) => r.method === "textDocument/formatting")
      expect(formatReq!.params.textDocument.uri).toBe(`file://${javaFile}`)
    } finally {
      await mock.close()
    }
  })

  test("format: file required error", async () => {
    const mock = await startMockServer(() => [])
    try {
      const { stdout, code } = await runCli(mock.port, ["format"], wsRoot)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(false)
      expect(meta.operation).toBe("format")
      expect(meta.error).toContain("file required")
    } finally {
      await mock.close()
    }
  })

  test("format alias 'fmt' works", async () => {
    const mock = await startMockServer(() => [])
    try {
      const { stdout, code } = await runCli(mock.port, ["fmt", "Main.java"], wsRoot)
      const meta = JSON.parse(stdout.trim().split("\n")[0])
      expect(code).toBe(0)
      expect(meta.success).toBe(true)
      expect(meta.operation).toBe("fmt")
    } finally {
      await mock.close()
    }
  })

  test("format results include edit details", async () => {
    const edits = [
      { range: { start: { line: 1, character: 0 }, end: { line: 1, character: 0 } }, newText: "    " },
      { range: { start: { line: 2, character: 0 }, end: { line: 2, character: 0 } }, newText: "    " },
    ]
    const mock = await startMockServer(() => edits)
    try {
      const { stdout } = await runCli(mock.port, ["format", "Main.java"], wsRoot)
      const lines = stdout.trim().split("\n")
      const meta = JSON.parse(lines[0])
      expect(meta.count).toBe(2)
      const edit1 = JSON.parse(lines[1])
      expect(edit1.line).toBe(1)
      expect(edit1.character).toBe(0)
      expect(edit1.newText).toBe("    ")
      const edit2 = JSON.parse(lines[2])
      expect(edit2.line).toBe(2)
      expect(edit2.endLine).toBe(2)
    } finally {
      await mock.close()
    }
  })
})
