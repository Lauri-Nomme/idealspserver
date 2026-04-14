#!/usr/bin/env python3
"""
Quick test to verify LSP server is responding.
"""

import json
import socket
import re
import sys


def recv_message(sock):
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
        body += sock.recv(length - len(body))

    return json.loads(body.decode())


def main():
    host = "127.0.0.1"
    port = 8989

    if len(sys.argv) > 1:
        port = int(sys.argv[1])

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)

    try:
        sock.connect((host, port))
        print(f"Connected to {host}:{port}")

        # Simple initialize
        req = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "processId": 12345,
                "clientInfo": {"name": "test"},
                "capabilities": {},
            },
        }
        content = json.dumps(req)
        sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())

        resp = recv_message(sock)

        if "error" in resp:
            print(f"ERROR: {resp['error'].get('message', 'unknown')}")
            return 1

        caps = resp.get("result", {}).get("capabilities", {})
        print(f"OK - Server initialized")
        print(f"Capabilities: {list(caps.keys())}")
        return 0

    except socket.timeout:
        print("ERROR: Connection timeout")
        return 1
    except ConnectionRefused:
        print(f"ERROR: Connection refused to {host}:{port}")
        return 1
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
