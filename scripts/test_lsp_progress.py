#!/usr/bin/env python3
"""
Test script for IdeaLS LSP server with progress handling.
"""

import json
import socket
import re
import time
import sys
import argparse

PROJECT_PATH = "/vokk/home/lauri/dev/idealspserver/git/server"


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


def send_notification(sock, method, params):
    """Send a JSON-RPC notification (no response)."""
    req = {"jsonrpc": "2.0", "method": method, "params": params}
    content = json.dumps(req)
    sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())


def receive_response(sock, expected_id):
    """Receive response(s) including progress notifications."""
    while True:
        resp = recv_message(sock)
        if resp is None:
            break

        # Skip progress notifications
        if resp.get("method") == "window/workDoneProgress":
            print(f"  Progress: {resp.get('params', {}).get('token', 'unknown')}")
            continue

        # Check if this is our response
        if resp.get("id") == expected_id:
            return resp

        # Skip other responses
        print(f"  Skipping: {resp.get('method', 'unknown')}")

    return None


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
        print("Testing initialize...")
        send_request(
            sock,
            "initialize",
            {
                "processId": 12345,
                "clientInfo": {"name": "test-client", "version": "1.0"},
                "workspaceFolders": [
                    {"uri": f"file://{args.project}", "name": "server"}
                ],
                "capabilities": {},
            },
            1,
        )

        resp = receive_response(sock, 1)
        if resp and "result" in resp:
            caps = resp.get("result", {}).get("capabilities", {})
            print(f"  OK - Capabilities: {list(caps.keys())[:5]}...")
        else:
            print(f"  ERROR: {resp}")
            return 1

        # initialized notification
        send_notification(sock, "initialized", {})

        # Open file if provided
        if args.file:
            print(f"Opening {args.file}...")
            with open(args.file) as f:
                text = f.read()

            lang = (
                "java"
                if args.file.endswith(".java")
                else "kotlin"
                if args.file.endswith(".kt")
                else "plain"
            )
            send_notification(
                sock,
                "textDocument/didOpen",
                {
                    "textDocument": {
                        "uri": f"file://{args.file}",
                        "languageId": lang,
                        "version": 1,
                        "text": text,
                    }
                },
            )
            print(f"  OK")

            print(f"Waiting {args.wait}s for indexing...")
            time.sleep(args.wait)

            # Document symbols
            print("Testing document symbols...")
            send_request(
                sock,
                "textDocument/documentSymbol",
                {"textDocument": {"uri": f"file://{args.file}"}},
                2,
            )

            resp = receive_response(sock, 2)
            if resp and "result" in resp and resp["result"]:
                symbols = resp["result"]
                print(f"  OK - Found {len(symbols)} symbols")
                for s in symbols[:3]:
                    if hasattr(s, "get"):
                        print(f"    - {s.get('right', {}).get('name', s)}")
                    else:
                        print(f"    - {s}")
            else:
                print(f"  Result: {resp}")

        print("\n=== Test completed ===")
        return 0

    except socket.timeout:
        print("ERROR: Connection timeout")
        return 1
    except Exception as e:
        print(f"ERROR: {e}")
        import traceback

        traceback.print_exc()
        return 1
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
