import { spawn } from "node:child_process"
import { createServer, type Server } from "node:net"
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { test } from "bun:test"

export const REPO_ROOT = join(import.meta.dir, "..", "..")

export interface MockResponse {
  result?: any
  error?: { code?: number; message: string }
}

export type RequestHandler = (method: string, params: any, id: number) => MockResponse | undefined

export type NotificationHandler = (method: string, params: any) => { method: string; params: any }[]

export interface MockLspServer {
  server: Server
  port: number
  requests: { method: string; params: any; id: number }[]
  close: () => Promise<void>
}

function send(socket: any, msg: any): void {
  const body = JSON.stringify(msg)
  socket.write(`Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`)
}

export function startMockServer(config?: {
  requestHandler?: RequestHandler
  notificationHandler?: NotificationHandler
}): Promise<MockLspServer> {
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
          requests.push({ method: body.method, params: body.params, id: body.id })
          const resp = config?.requestHandler?.(body.method, body.params, body.id)
          const result = resp?.result !== undefined
            ? resp.result
            : { capabilities: {}, serverInfo: { name: "mock", version: "1.0" } }
          if (resp?.error) {
            send(socket, { jsonrpc: "2.0", id: body.id, error: resp.error })
          } else {
            send(socket, { jsonrpc: "2.0", id: body.id, result })
          }
          send(socket, { jsonrpc: "2.0", method: "idea/indexFinished", params: {} })
        } else if (body.id !== undefined && body.method) {
          requests.push({ method: body.method, params: body.params, id: body.id })
          const resp = config?.requestHandler?.(body.method, body.params, body.id)
          if (resp?.error) {
            send(socket, { jsonrpc: "2.0", id: body.id, error: resp.error })
          } else if (resp?.result !== undefined) {
            send(socket, { jsonrpc: "2.0", id: body.id, result: resp.result })
          }
        } else {
          requests.push({ method: body.method, params: body.params, id: -1 })
          const notifs = config?.notificationHandler?.(body.method, body.params) ?? []
          for (const n of notifs) {
            send(socket, { jsonrpc: "2.0", ...n })
          }
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

/** Convenience wrapper: responds [] to documentSymbol so text-scan symbol resolution is fast. */
export function withTextScan(handler: RequestHandler): RequestHandler {
  return (method, params, id) => {
    if (method === "textDocument/documentSymbol") return { result: [] }
    return handler(method, params, id)
  }
}

export function runCli(
  port: number,
  args: string[],
  wsRoot: string,
): Promise<{ stdout: string; code: number }> {
  return new Promise((resolve, reject) => {
    const proc = spawn("bun", ["run", "tools/xlsp/cli.ts", "--port", String(port), ...args], {
      cwd: REPO_ROOT,
      env: { ...process.env, XLSP_PORT: String(port), PROJECT_WORKSPACE: wsRoot, XLSP_INDEX_WAIT_SECONDS: "0.01" },
    })
    let stdout = ""
    proc.stdout.on("data", (d: string) => (stdout += d))
    proc.stderr.on("data", (d: string) => (stdout += d))
    proc.on("error", reject)
    proc.on("close", (code) => resolve({ stdout, code: code ?? -1 }))
  })
}

export function parseOutput(stdout: string): { meta: any; results: any[] } {
  const lines = stdout.trim().split("\n")
  const meta = JSON.parse(lines[0])
  const results = lines.slice(1).map((l) => JSON.parse(l))
  return { meta, results }
}

export interface TestWorkspace {
  root: string
  javaFile: string
  javaPath: string
  content: string
}

const JAVA_SAMPLE = `package com.example;

public class Main {
  private int count;

  public int add(int a, int b) {
    return a + b;
  }

  public void run() {
    add(1, 2);
  }
}
`

export function makeWorkspace(files: Record<string, string> = {}): TestWorkspace {
  const root = mkdtempSync(join(tmpdir(), "xlsp-ws-"))
  const javaPath = "Main.java"
  const javaFile = join(root, javaPath)
  writeFileSync(javaFile, JAVA_SAMPLE)
  for (const [rel, content] of Object.entries(files)) {
    const abs = join(root, rel)
    mkdirSync(join(abs, ".."), { recursive: true })
    writeFileSync(abs, content)
  }
  return { root, javaFile, javaPath, content: JAVA_SAMPLE }
}

export function cleanupWorkspace(ws: TestWorkspace): void {
  rmSync(ws.root, { recursive: true, force: true })
}

export const MOCK_LOCATION = {
  uri: "file:///tmp/project/src/Other.java",
  range: {
    start: { line: 5, character: 10 },
    end: { line: 5, character: 15 },
  },
}

/** Default per-test timeout for CLI tests that involve long server-side drains. */
export const SLOW_TEST_TIMEOUT = 90_000

/** Wrapper for tests that hit slow CLI operations (long server-side notification drains). */
export function slowTest(name: string, fn: () => void | Promise<void>): void {
  test(name, fn, SLOW_TEST_TIMEOUT)
}
