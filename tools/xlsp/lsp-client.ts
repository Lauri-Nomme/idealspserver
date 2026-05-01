import { connect } from "node:net"

export interface LspMessage {
  jsonrpc: "2.0"
  id?: number
  method?: string
  params?: any
  result?: any
  error?: { code: number; message: string; data?: any }
}

export interface DiagnosticNotification {
  uri: string
  diagnostics: {
    range: { start: { line: number; character: number }; end: { line: number; character: number } }
    severity: number
    message: string
    source: string
  }[]
}

export class LspClient {
  private socket: ReturnType<typeof connect> | null = null
  private buf = ""
  private nextId = 1
  private port: number
  private diagnostics: DiagnosticNotification[] = []
  private messageQueue: LspMessage[] = []
  private wakeup: (() => void) | null = null

  constructor(port = 8989) {
    this.port = port
  }

  async connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socket = connect({ host: "127.0.0.1", port: this.port }, () => {
        this.socket!.setEncoding("utf8")
        this.socket!.on("data", (data: string) => this.onData(data))
        resolve()
      })
      this.socket.on("error", reject)
    })
  }

  close(): void {
    this.socket?.destroy()
    this.socket = null
  }

  diagnosticsCollected(): DiagnosticNotification[] {
    return this.diagnostics
  }

  clearDiagnostics(): void {
    this.diagnostics = []
  }

  sendRequest(method: string, params: any, timeoutMs = 30_000): Promise<LspMessage> {
    const id = this.nextId++
    const msg = JSON.stringify({ jsonrpc: "2.0", id, method, params })
    const frame = `Content-Length: ${Buffer.byteLength(msg, "utf8")}\r\n\r\n${msg}`
    this.socket!.write(frame)

    return this.receiveResponse(id, timeoutMs)
  }

  sendNotification(method: string, params: any): void {
    const msg = JSON.stringify({ jsonrpc: "2.0", method, params })
    const frame = `Content-Length: ${Buffer.byteLength(msg, "utf8")}\r\n\r\n${msg}`
    this.socket!.write(frame)
  }

  private waitForMessage(timeoutMs: number): Promise<LspMessage | null> {
    return new Promise((resolve) => {
      // Check queue first (may already have messages from onData)
      if (this.messageQueue.length > 0) {
        resolve(this.messageQueue.shift()!)
        return
      }

      const timeout = setTimeout(() => {
        this.wakeup = null
        resolve(null)
      }, timeoutMs)

      this.wakeup = () => {
        clearTimeout(timeout)
        // Guard: onData may have already shifted the message
        if (this.messageQueue.length > 0) {
          resolve(this.messageQueue.shift()!)
        } else {
          // Re-register wakeup and continue waiting
          this.wakeup = () => { clearTimeout(timeout); resolve(this.messageQueue.shift()!) }
        }
      }
    })
  }

  private onData(data: string): void {
    this.buf += data
    while (true) {
      const endIdx = this.buf.indexOf("\r\n\r\n")
      if (endIdx === -1) break

      const header = this.buf.slice(0, endIdx)
      const match = header.match(/Content-Length:\s*(\d+)/i)
      if (!match) {
        this.buf = this.buf.slice(endIdx + 4)
        continue
      }

      const length = parseInt(match[1], 10)
      const bodyStart = endIdx + 4
      if (this.buf.length < bodyStart + length) break

      const body = this.buf.slice(bodyStart, bodyStart + length)
      this.buf = this.buf.slice(bodyStart + length)

      try {
        const msg = JSON.parse(body) as LspMessage
        this.messageQueue.push(msg)
        if (this.wakeup) {
          const wake = this.wakeup
          this.wakeup = null
          wake()
        }
      } catch (_) {
        // skip malformed messages
      }
    }
  }

  private async receiveResponse(expectedId: number, timeoutMs: number): Promise<LspMessage> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const remaining = Math.max(50, deadline - Date.now())
      const msg = await this.waitForMessage(remaining)
      if (!msg) return { jsonrpc: "2.0", error: { code: -1, message: "timeout" } }

      if (msg.method === "textDocument/publishDiagnostics") {
        this.diagnostics.push(msg.params as DiagnosticNotification)
        continue
      }

      if (msg.id !== undefined && msg.method) {
        const reply = { jsonrpc: "2.0", id: msg.id, result: null }
        const frame = `Content-Length: ${Buffer.byteLength(JSON.stringify(reply), "utf8")}\r\n\r\n${JSON.stringify(reply)}`
        this.socket!.write(frame)
        continue
      }

      if (msg.id === undefined) continue

      if (msg.id === expectedId) return msg
    }
    return { jsonrpc: "2.0", error: { code: -1, message: "timeout" } }
  }

  drainNotifications(timeoutMs: number): Promise<void> {
    return new Promise((resolve) => {
      const deadline = Date.now() + timeoutMs
      const check = async () => {
        if (Date.now() >= deadline) {
          resolve()
          return
        }
        const remaining = Math.max(50, deadline - Date.now())
        const msg = await this.waitForMessage(remaining)
        if (!msg) {
          resolve()
          return
        }
        if (msg.method === "textDocument/publishDiagnostics") {
          this.diagnostics.push(msg.params as DiagnosticNotification)
        }
        if (msg.id !== undefined && msg.method) {
          const reply = { jsonrpc: "2.0", id: msg.id, result: null }
          const frame = `Content-Length: ${Buffer.byteLength(JSON.stringify(reply), "utf8")}\r\n\r\n${JSON.stringify(reply)}`
          this.socket!.write(frame)
        }
        check()
      }
      check()
    })
  }
}
