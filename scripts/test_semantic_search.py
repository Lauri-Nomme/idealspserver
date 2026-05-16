#!/usr/bin/env python3
"""
Standalone test for semantic search - run this immediately after service restart.
"""

import json
import os
import socket
import time
import sys

PROJECT_ROOT = os.environ.get("PROJECT_WORKSPACE", "/vokk/home/lauri/dev/idealspserver/git")
SOURCE_PATH = os.path.join(PROJECT_ROOT, "server/src/main/java")

diagnostics_result = {}


def recv_message(sock, timeout=None):
    old_timeout = sock.gettimeout()
    if timeout is not None:
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
    except socket.timeout:
        return None
    finally:
        sock.settimeout(old_timeout)


def recv_response(sock, expected_id):
    while True:
        resp = recv_message(sock)
        if resp is None:
            break

        if resp.get("method") == "textDocument/publishDiagnostics":
            diagnostics_result["data"] = resp.get("params", {})

        if "id" in resp and "method" in resp:
            reply = {"jsonrpc": "2.0", "id": resp["id"], "result": None}
            content = json.dumps(reply)
            sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
            continue

        if "id" not in resp:
            continue

        if resp.get("id") == expected_id:
            return resp

    return None


def send_and_recv(sock, method, params, req_id):
    req = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
    return recv_response(sock, req_id)


def send_notification(sock, method, params):
    req = {"jsonrpc": "2.0", "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())


def drain_notifications(sock, seconds=5):
    deadline = time.time() + seconds
    while time.time() < deadline:
        remaining = max(0.1, deadline - time.time())
        msg = recv_message(sock, timeout=remaining)
        if msg is None:
            continue
        if msg.get("method") == "textDocument/publishDiagnostics":
            diagnostics_result["data"] = msg.get("params", {})
        if "id" in msg and "method" in msg:
            reply = {"jsonrpc": "2.0", "id": msg["id"], "result": None}
            content = json.dumps(reply)
            sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())


def test_semantic_search():
    print("=== Semantic Search Standalone Test ===\n")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(30)
    sock.connect(("127.0.0.1", 8989))
    print("1. Connected to LSP server")

    # Initialize
    resp = send_and_recv(
        sock,
        "initialize",
        {
            "processId": 12345,
            "clientInfo": {"name": "test", "version": "1.0"},
            "workspaceFolders": [{"uri": f"file://{PROJECT_ROOT}", "name": "git"}],
            "capabilities": {},
        },
        1,
    )
    print(f"2. Initialize: {'OK' if resp and 'result' in resp else 'FAILED'}")
    send_notification(sock, "initialized", {})

    # Test with main project file
    semantic_test_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
    print(f"   Using file: {semantic_test_file}")

    with open(semantic_test_file) as f:
        semantic_file_content = f.read()

    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{semantic_test_file}",
                "languageId": "java",
                "version": 1,
                "text": semantic_file_content,
            }
        },
    )
    print("3. Opened test file, waiting for indexing...")
    drain_notifications(sock, seconds=5)

    # Test 1: Find field declarations
    print("\n4. Testing semantic search for field declarations...")
    print("   Pattern: $Modifiers$ $Type$ $FieldName$;")
    sock.settimeout(20)
    resp = send_and_recv(
        sock,
        "textDocument/semanticSearch",
        {
            "pattern": "$Modifiers$ $Type$ $FieldName$;",
            "scope": "file",
            "language": "java",
            "fileUri": f"file://{semantic_test_file}",
        },
        10,
    )
    sock.settimeout(30)

    if resp:
        print(f"   Response received: id={resp.get('id')}")
        if "result" in resp:
            matches = resp["result"]
            print(f"   Result: {len(matches)} matches")
            for m in matches[:5]:
                start = m.get("start", {})
                line = start.get("line", "?")
                text = m.get("matchedText", "")[:60]
                print(f"     - line {line}: {text}")
        if "error" in resp:
            print(f"   Error: {resp['error']}")
    else:
        print("   TIMEOUT - no response")

    # Test 2: Find Logger fields with constraint
    print("\n5. Testing semantic search for Logger fields with constraint...")
    print("   Pattern: $Modifiers$ $Type$ $FieldName$; with $Type$ regex=Logger")
    sock.settimeout(20)
    resp = send_and_recv(
        sock,
        "textDocument/semanticSearch",
        {
            "pattern": "$Modifiers$ $Type$ $FieldName$;",
            "scope": "file",
            "language": "java",
            "fileUri": f"file://{semantic_test_file}",
            "constraints": {"$Type$": {"regex": "Logger"}},
        },
        11,
    )
    sock.settimeout(30)

    if resp:
        print(f"   Response received: id={resp.get('id')}")
        if "result" in resp:
            matches = resp["result"]
            print(f"   Result: {len(matches)} Logger matches")
            for m in matches[:5]:
                start = m.get("start", {})
                line = start.get("line", "?")
                text = m.get("matchedText", "")[:60]
                print(f"     - line {line}: {text}")
        if "error" in resp:
            print(f"   Error: {resp['error']}")
    else:
        print("   TIMEOUT - no response")

    # Test 3: Invalid constraint
    print("\n6. Testing semantic search with invalid constraint...")
    print("   Pattern: $Modifiers$ $Type$ $FieldName$; with $Type$ foo=bar")
    sock.settimeout(20)
    resp = send_and_recv(
        sock,
        "textDocument/semanticSearch",
        {
            "pattern": "$Modifiers$ $Type$ $FieldName$;",
            "scope": "file",
            "language": "java",
            "fileUri": f"file://{semantic_test_file}",
            "constraints": {"$Type$": {"foo": "bar"}},
        },
        12,
    )
    sock.settimeout(30)

    if resp:
        print(f"   Response received: id={resp.get('id')}")
        if "error" in resp:
            print(f"   Error (expected): {resp['error'].get('message', '')[:80]}")
        elif "result" in resp:
            print(f"   Unexpected result: {len(resp['result'])} matches")
    else:
        print("   TIMEOUT - no response")

    # Shutdown
    print("\n7. Testing shutdown...")
    resp = send_and_recv(sock, "shutdown", {}, 13)
    if resp and "result" in resp:
        print("   Shutdown: OK")
        send_notification(sock, "exit", {})
    else:
        print(f"   Shutdown: FAILED")

    sock.close()
    print("\n=== Test completed ===")


if __name__ == "__main__":
    test_semantic_search()
