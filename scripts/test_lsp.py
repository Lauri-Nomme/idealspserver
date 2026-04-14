#!/usr/bin/env python3
"""
Test script for IdeaLS LSP server.
Sends basic LSP protocol messages and verifies the server responds.
"""

import json
import socket
import re
import time
import sys
import argparse

PROJECT_PATH = "/vokk/home/lauri/dev/idealspserver/git/server/src/main/java"


def recv_message(sock):
    """Receive and parse a JSON-RPC message."""
    header = b""
    while b"\r\n\r\n" not in header:
        c = sock.recv(1)
        if not c:
            return None
        header += c

    match = re.search(rb"Content-Length: (\d+)", header)
    if not match:
        return None

    length = int(match.group(1))
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            break
        body += chunk

    return json.loads(body.decode())


def send_request(sock, method, params, req_id):
    """Send a JSON-RPC request."""
    req = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
    return recv_message(sock)


def send_notification(sock, method, params):
    """Send a JSON-RPC notification (no response)."""
    req = {"jsonrpc": "2.0", "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())


def test_initialize(sock, project_path):
    """Test LSP initialize."""
    print("Testing initialize...")
    resp = send_request(
        sock,
        "initialize",
        {
            "processId": 12345,
            "clientInfo": {"name": "test-client", "version": "1.0"},
            "workspaceFolders": [{"uri": f"file://{project_path}", "name": "server"}],
            "capabilities": {},
        },
        1,
    )

    if "error" in resp:
        print(f"  ERROR: {resp['error'].get('message', 'unknown')}")
        return False

    caps = resp.get("result", {}).get("capabilities", {})
    print(f"  OK - Capabilities: {list(caps.keys())}")
    return True


def test_open_file(sock, file_path):
    """Test opening a file."""
    print(f"Opening {file_path}...")
    with open(file_path) as f:
        text = f.read()

    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{file_path}",
                "languageId": "java",
                "version": 1,
                "text": text,
            }
        },
    )
    print("  OK")
    return True


def test_document_symbols(sock, file_path):
    """Test document symbols."""
    print("Testing document symbols...")
    resp = send_request(
        sock,
        "textDocument/documentSymbol",
        {"textDocument": {"uri": f"file://{file_path}"}},
        2,
    )

    if "result" in resp and resp["result"]:
        symbols = resp["result"]
        print(f"  OK - Found {len(symbols)} symbols")
        for s in symbols[:5]:
            print(f"    - {s.get('name')} ({s.get('kind')})")
    else:
        print(f"  Not implemented or empty")
    return True


def test_definition(sock, file_path, line, character):
    """Test go to definition."""
    print(f"Testing definition at line {line}, char {character}...")
    resp = send_request(
        sock,
        "textDocument/definition",
        {
            "textDocument": {"uri": f"file://{file_path}"},
            "position": {"line": line, "character": character},
        },
        3,
    )

    if "result" in resp and resp["result"]:
        loc = resp["result"]
        print(f"  OK - Definition: {loc}")
    else:
        print(f"  Not found or not implemented")
    return True


def test_references(sock, file_path, line, character):
    """Test find references."""
    print(f"Testing references at line {line}, char {character}...")
    resp = send_request(
        sock,
        "textDocument/references",
        {
            "textDocument": {"uri": f"file://{file_path}"},
            "position": {"line": line, "character": character},
            "context": {"includeDeclaration": True},
        },
        4,
    )

    if "result" in resp and resp["result"]:
        refs = resp["result"]
        print(f"  OK - Found {len(refs)} references")
    else:
        print(f"  Not found or not implemented")
    return True


def test_workspace_symbols(sock, query):
    """Test workspace symbols."""
    print(f"Testing workspace symbols (query: {query})...")
    resp = send_request(sock, "workspace/symbol", {"query": query}, 5)

    if "result" in resp and resp["result"]:
        symbols = resp["result"]
        print(f"  OK - Found {len(symbols)} symbols")
        for s in symbols[:5]:
            print(f"    - {s.get('name')}")
    else:
        print(f"  Not found or not implemented")
    return True


def test_completion(sock, file_path, line, character):
    """Test completion."""
    print(f"Testing completion at line {line}, char {character}...")
    resp = send_request(
        sock,
        "textDocument/completion",
        {
            "textDocument": {"uri": f"file://{file_path}"},
            "position": {"line": line, "character": character},
        },
        6,
    )

    if "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"  OK - Found {len(result)} completions")
        else:
            print(f"  OK - CompletionProvider result")
    else:
        print(f"  Not implemented")
    return True


def main():
    parser = argparse.ArgumentParser(description="Test IdeaLS LSP server")
    parser.add_argument("--host", default="127.0.0.1", help="LSP server host")
    parser.add_argument("--port", type=int, default=8989, help="LSP server port")
    parser.add_argument("--project", default=PROJECT_PATH, help="Project path")
    parser.add_argument("--file", help="Test file to open")
    parser.add_argument(
        "--wait", type=int, default=5, help="Wait time after opening file"
    )
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(30)

    try:
        sock.connect((args.host, args.port))
        print(f"Connected to {args.host}:{args.port}")

        # Initialize
        if not test_initialize(sock, args.project):
            print("Failed to initialize")
            return 1

        # Send initialized notification
        send_notification(sock, "initialized", {})

        # Open file if provided
        if args.file:
            test_open_file(sock, args.file)
            print(f"Waiting {args.wait}s for indexing...")
            time.sleep(args.wait)

            # Run tests on the file
            test_document_symbols(sock, args.file)
            test_completion(sock, args.file, 0, 10)
            test_definition(sock, args.file, 10, 10)

        # Workspace symbol test
        test_workspace_symbols(sock, "Lsp")

        print("\n=== All tests completed ===")

    except socket.timeout:
        print("ERROR: Connection timeout")
        return 1
    except Exception as e:
        print(f"ERROR: {e}")
        return 1
    finally:
        sock.close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
