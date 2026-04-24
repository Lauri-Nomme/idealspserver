#!/usr/bin/env python3
"""
Test script for IdeaLS LSP server - comprehensive version.
"""

import json
import socket
import time

PROJECT_PATH = "/vokk/home/lauri/dev/idealspserver/git/server/src/main/java"


# Track diagnostics and code actions responses
diagnostics_result = {}
code_actions_result = {}


def recv_message(sock, timeout=None):
    """Receive and parse a JSON-RPC message."""
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
    """Receive response(s) including progress notifications and diagnostics."""
    while True:
        resp = recv_message(sock)
        if resp is None:
            break

        # Collect diagnostics notifications
        if resp.get("method") == "textDocument/publishDiagnostics":
            diagnostics_result["data"] = resp.get("params", {})

        # Respond to server-to-client requests (e.g. window/workDoneProgress/create)
        if "id" in resp and "method" in resp:
            reply = {"jsonrpc": "2.0", "id": resp["id"], "result": None}
            content = json.dumps(reply)
            sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
            continue

        # Skip other notifications
        if "id" not in resp:
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


def drain_notifications(sock, seconds=5):
    """Read all notifications from the socket for a given duration."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        remaining = max(0.1, deadline - time.time())
        msg = recv_message(sock, timeout=remaining)
        if msg is None:
            continue
        if msg.get("method") == "textDocument/publishDiagnostics":
            diagnostics_result["data"] = msg.get("params", {})
        # Respond to server-to-client requests
        if "id" in msg and "method" in msg:
            reply = {"jsonrpc": "2.0", "id": msg["id"], "result": None}
            content = json.dumps(reply)
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
    print("2. Opened test file, waiting for indexing and source root stabilization...")
    drain_notifications(sock, seconds=20)

    # Check if didOpen already produced diagnostics
    if diagnostics_result.get("data"):
        diags = diagnostics_result["data"].get("diagnostics", [])
        print(f"    (didOpen produced {len(diags)} diagnostics)")
    else:
        print(f"    (no diagnostics from didOpen)")

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

    # Test definition - line 28 (0-indexed) has "MyTextDocumentService" reference at char 16
    resp = send_and_recv(
        sock,
        "textDocument/definition",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 28, "character": 20},
        },
        3,
    )
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"4. Definition: OK - Found {len(result)} location(s)")
            for loc in result[:2]:
                uri = loc.get("uri", loc.get("targetUri", ""))
                print(f"    - {uri.split('/')[-1]}")
        else:
            print(f"4. Definition: OK")
    else:
        print(f"4. Definition: FAILED or no result")
        if resp:
            print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
            if resp.get("error"):
                print(f"    error: {resp['error']}")

    # Test references - find references to MyTextDocumentService at line 28
    resp = send_and_recv(
        sock,
        "textDocument/references",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 28, "character": 20},
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

    # Test completion - line 31 is empty line inside class body (good for keyword completions)
    resp = send_and_recv(
        sock,
        "textDocument/completion",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 31, "character": 0},
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

    # Test hover - line 28 (0-indexed) has MyTextDocumentService
    resp = send_and_recv(
        sock,
        "textDocument/hover",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 28, "character": 20},
        },
        7,
    )
    if resp and "result" in resp:
        print(f"8. Hover: OK")
    else:
        print(f"8. Hover: not supported or failed")

    # Test type definition - line 28 (0-indexed) has "myTextDocumentService" variable at char 37
    # Type definition should navigate to MyTextDocumentService class
    resp = send_and_recv(
        sock,
        "textDocument/typeDefinition",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 28, "character": 40},
        },
        8,
    )
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"9. Type definition: OK - Found {len(result)} location(s)")
            for loc in result[:2]:
                uri = loc.get("uri", loc.get("targetUri", ""))
                print(f"    - {uri.split('/')[-1]}")
        else:
            print(f"9. Type definition: OK")
    else:
        err = resp.get("error") if resp else None
        print(f"9. Type definition: FAILED (error={err})")
        if resp:
            print(f"    raw: {json.dumps(resp.get('result'))[:200]}")

    # Test implementation - line 26 (0-indexed) has LspSession interface at char 71
    # Should find implementing classes
    resp = send_and_recv(
        sock,
        "textDocument/implementation",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 26, "character": 73},
        },
        9,
    )
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"10. Implementation: OK - Found {len(result)} location(s)")
        else:
            print(f"10. Implementation: OK")
    else:
        err = resp.get("error") if resp else None
        print(f"10. Implementation: FAILED (error={err})")
        if resp:
            print(f"    raw: {json.dumps(resp.get('result'))[:200]}")

    # Test document highlight - line 27 (0-indexed) has "LOG" at char 30
    # LOG is used multiple times in the file
    resp = send_and_recv(
        sock,
        "textDocument/documentHighlight",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 27, "character": 30},
        },
        10,
    )
    if resp and "result" in resp and resp["result"]:
        print(f"11. Document highlight: OK - Found {len(resp['result'])} highlights")
    else:
        err = resp.get("error") if resp else None
        print(f"11. Document highlight: FAILED (error={err})")

    # Test diagnostics on existing file - use LspServer.java which we know exists
    error_test_file = f"{PROJECT_PATH}/org/rri/ideals/server/LspServer.java"

    # Send didChange to introduce an error - change an int to String
    send_notification(
        sock,
        "textDocument/didChange",
        {
            "textDocument": {"uri": f"file://{error_test_file}", "version": 2},
            "contentChanges": [{"text": " String x = 123;  // Type mismatch error\n"}],
        },
    )
    print("    Sent change to LspServer.java to introduce error...")
    drain_notifications(sock, seconds=8)

    # Check if diagnostics were received (from either didOpen or didChange)
    if diagnostics_result.get("data"):
        diags = diagnostics_result["data"].get("diagnostics", [])
        if diags:
            print(f"12. Diagnostics: OK - Found {len(diags)} diagnostics")
            for d in diags[:3]:
                msg = d.get("message", "")[:60]
                sev = {1: "Error", 2: "Warn", 3: "Info", 4: "Hint"}.get(d.get("severity"), "?")
                print(f"    - [{sev}] {msg}")
        else:
            print(f"12. Diagnostics: OK - but no errors")
    else:
        print(f"12. Diagnostics: No diagnostics received")

    # Restore original file content so code actions work on a proper file
    send_notification(
        sock,
        "textDocument/didChange",
        {
            "textDocument": {"uri": f"file://{error_test_file}", "version": 3},
            "contentChanges": [{"text": text}],
        },
    )
    drain_notifications(sock, seconds=8)

    # Test code actions on restored file using its diagnostics
    if diagnostics_result.get("data") and diagnostics_result["data"].get("diagnostics"):
        # Find a diagnostic with a range to use for code actions
        diag = diagnostics_result["data"]["diagnostics"][0]
        action_range = diag["range"]
        resp = send_and_recv(
            sock,
            "textDocument/codeAction",
            {
                "textDocument": {"uri": f"file://{error_test_file}"},
                "range": action_range,
                "context": {"diagnostics": [diag]}
            },
            12,
        )
        if resp and "result" in resp:
            actions = resp["result"]
            if actions:
                print(f"13. Code Actions: OK - Found {len(actions)} actions")
                for a in actions[:3]:
                    title = a.get("title", "unknown")
                    print(f"    - {title[:60]}")
            else:
                print(f"13. Code Actions: OK - No actions for this diagnostic")
        else:
            print(f"13. Code Actions: Failed or not supported")
    else:
        print(f"13. Code Actions: Skipped (no diagnostics)")

    # Test cross-file references
    # Use clean Java files from main source - LspServer is referenced from LspServerRunnerBase
    bootstrap_path = "/vokk/home/lauri/dev/idealspserver/git/server/src/main/java/org/rri/ideals/server/bootstrap"
    lsp_server_file = f"{PROJECT_PATH}/org/rri/ideals/server/LspServer.java"
    lsp_runner_file = f"{bootstrap_path}/LspServerRunnerBase.java"

    # Open LspServerRunnerBase.java which references LspServer
    with open(lsp_runner_file) as f:
        runner_text = f.read()
    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{lsp_runner_file}",
                "languageId": "java",
                "version": 1,
                "text": runner_text,
            }
        },
    )
    print("    Waiting extra 5 seconds for cross-file indexing...")
    drain_notifications(sock, seconds=5)

    # Find references to LspServer class - should find usages in LspServerRunnerBase.java
    resp = send_and_recv(
        sock,
        "textDocument/references",
        {
            "textDocument": {"uri": f"file://{lsp_server_file}"},
            "position": {
                "line": 26,
                "character": 13,
            },  # "L" of "LspServer" in "public class LspServer" (LSP lines 0-indexed)
            "context": {"includeDeclaration": True},
        },
        13,
    )
    if resp and "result" in resp and resp["result"]:
        refs = resp["result"]
        # Check if we got cross-file references (should include LspServerRunnerBase.java)
        cross_file = any("LspServerRunnerBase" in str(r.get("uri", "")) for r in refs)
        same_file = any("LspServer.java" in str(r.get("uri", "")) for r in refs)
        print(f"15. Cross-file References: OK - Found {len(refs)} references")
        print(f"    - Same file: {same_file}, Cross-file: {cross_file}")
        if not cross_file:
            print(f"    WARNING: Cross-file references may not be working!")
    else:
        print(f"15. Cross-file References: FAILED or no result")

    sock.close()
    print("\n=== All tests completed ===")


if __name__ == "__main__":
    test_all()
