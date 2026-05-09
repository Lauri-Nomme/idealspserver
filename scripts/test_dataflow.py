#!/usr/bin/env python3
import json
import socket
import time
import os

PROJECT_PATH = "/vokk/home/lauri/dev/idealspserver/git/server/test-data/dataflow"
DATA_FILE = f"{PROJECT_PATH}/DataFlowTest.java"

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.settimeout(30)
sock.connect(("127.0.0.1", 8989))
print("Connected to LSP server")

def recv_message(sock, timeout=5):
    old_timeout = sock.gettimeout()
    sock.settimeout(timeout)
    try:
        header = b""
        while b"\r\n\r\n" not in header:
            c = sock.recv(1)
            if not c:
                return None
            header += c
        
        match = None
        for line in header.decode().split("\r\n"):
            if line.startswith("Content-Length:"):
                match = line
                break
        if not match:
            return None
        
        length = int(match.split(":")[1].strip())
        body = b""
        while len(body) < length:
            chunk = sock.recv(length - len(body))
            if not chunk:
                break
            body += chunk
        
        return json.loads(body.decode())
    finally:
        sock.settimeout(old_timeout)

def send_and_recv(sock, method, params, req_id):
    """Send a request and get response."""
    req = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
    return recv_message(sock, req_id)

def send_notification(sock, method, params):
    """Send a notification."""
    req = {"jsonrpc": "2.0", "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())

# Initialize
resp = send_and_recv(sock, "initialize", {
    "processId": 12345,
    "rootUri": f"file://{PROJECT_PATH}",
    "capabilities": {}
}, 1)
print(f"Initialize: OK")
send_notification(sock, "initialized", {})

# Open file
with open(DATA_FILE) as f:
    text = f.read()
send_notification(sock, "textDocument/didOpen", {
    "textDocument": {"uri": f"file://{DATA_FILE}", "languageId": "java", "version": 1, "text": text}
})
time.sleep(0.5)

# Test dataflowFrom
print("Testing dataflowFrom on line 3...")
resp = send_and_recv(sock, "textDocument/dataflowFrom", {
    "textDocument": {"uri": f"file://{DATA_FILE}"},
    "position": {"line": 3, "character": 9}
}, 3)
print(f"Result: {resp}")

# Test dataflowTo
print("\nTesting dataflowTo on line 3...")
resp = send_and_recv(sock, "textDocument/dataflowTo", {
    "textDocument": {"uri": f"file://{DATA_FILE}"},
    "position": {"line": 3, "character": 9}
}, 4)
print(f"Result: {resp}")

sock.close()