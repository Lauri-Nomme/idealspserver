#!/usr/bin/env python3
"""
Test script for IdeaLS LSP server - comprehensive version.
"""

import json
import socket
import time

PROJECT_PATH = "/vokk/home/lauri/dev/idealspserver/git/server/src/main/java"


def recv_message(sock):
    """Receive and parse a JSON-RPC message."""
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


def recv_response(sock, expected_id):
    """Receive response(s) including progress notifications."""
    while True:
        resp = recv_message(sock)
        if resp is None:
            break

        # Skip notifications
        if "method" in resp and "id" not in resp:
            continue

        if resp.get("id") == expected_id:
            return resp

    return None


def send_and_recv(sock, method, params, req_id):
    """Send a request and get response."""
    req = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
    return recv_response(sock, req_id)


def send_notification(sock, method, params):
    """Send a notification."""
    req = {"jsonrpc": "2.0", "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())


def test_all():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(30)
    sock.connect(("127.0.0.1", 8989))
    print("Connected to LSP server")

    # Initialize
    resp = send_and_recv(
        sock,
        "initialize",
        {
            "processId": 12345,
            "clientInfo": {"name": "test", "version": "1.0"},
            "workspaceFolders": [{"uri": f"file://{PROJECT_PATH}", "name": "server"}],
            "capabilities": {},
        },
        1,
    )
    print(f"\n1. Initialize: {'OK' if resp and 'result' in resp else 'FAILED'}")

    send_notification(sock, "initialized", {})

    # Open file
    test_file = f"{PROJECT_PATH}/org/rri/ideals/server/LspServer.java"
    with open(test_file) as f:
        text = f.read()

    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{test_file}",
                "languageId": "java",
                "version": 1,
                "text": text,
            }
        },
    )
    print("2. Opened test file, waiting for indexing...")
    time.sleep(10)

    # Test document symbols
    resp = send_and_recv(
        sock,
        "textDocument/documentSymbol",
        {"textDocument": {"uri": f"file://{test_file}"}},
        2,
    )
    if resp and "result" in resp and resp["result"]:
        symbols = resp["result"]
        print(f"3. Document symbols: OK - Found {len(symbols)} symbols")
        for s in symbols[:3]:
            name = s.get("name") or (
                s.get("right", {}).get("name") if isinstance(s, dict) else "unknown"
            )
            print(f"   - {name}")
    else:
        print(f"3. Document symbols: FAILED - {resp}")

    # Test definition - line 20 has "LspServer" class declaration
    resp = send_and_recv(
        sock,
        "textDocument/definition",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 20, "character": 10},
        },
        3,
    )
    if resp and "result" in resp and resp["result"]:
        print(f"4. Definition: OK - Found {len(resp['result'])} location(s)")
    else:
        print(f"4. Definition: FAILED or no result")

    # Test references
    resp = send_and_recv(
        sock,
        "textDocument/references",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 20, "character": 10},
            "context": {"includeDeclaration": True},
        },
        4,
    )
    if resp and "result" in resp and resp["result"]:
        print(f"5. References: OK - Found {len(resp['result'])} references")
    else:
        print(f"5. References: FAILED or no result")

    # Test workspace symbols
    resp = send_and_recv(sock, "workspace/symbol", {"query": "Lsp"}, 5)
    if resp and "result" in resp and resp["result"]:
        print(f"6. Workspace symbols: OK - Found {len(resp['result'])} symbols")
        for s in resp["result"][:3]:
            print(f"   - {s.get('name')}")
    else:
        print(f"6. Workspace symbols: FAILED or no result")

    # Test completion - line 25 has some text
    resp = send_and_recv(
        sock,
        "textDocument/completion",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 0, "character": 10},
        },
        6,
    )
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"7. Completion: OK - Found {len(result)} completions")
        else:
            print(f"7. Completion: OK - got CompletionList")
    else:
        print(f"7. Completion: FAILED")

    # Test hover
    resp = send_and_recv(
        sock,
        "textDocument/hover",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 20, "character": 10},
        },
        7,
    )
    if resp and "result" in resp:
        print(f"8. Hover: OK")
    else:
        print(f"8. Hover: not supported or failed")

    sock.close()
    print("\n=== All tests completed ===")


if __name__ == "__main__":
    test_all()
