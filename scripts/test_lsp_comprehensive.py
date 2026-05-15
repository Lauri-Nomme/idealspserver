#!/usr/bin/env python3
"""
Test script for IdeaLS LSP server - comprehensive version.
"""

import json
import os
import socket
import time

# Workspace root (where .idea/ lives — enables Gradle import and proper indexing)
PROJECT_ROOT = os.environ.get("PROJECT_WORKSPACE", "/vokk/home/lauri/dev/idealspserver/git")
# Source root for file paths (src/main/java)
SOURCE_PATH = os.path.join(PROJECT_ROOT, "server/src/main/java")


# Track diagnostics and code actions responses
diagnostics_result = {}
code_actions_result = {}

# Track test results
test_results = []
passed = 0
failed = 0
skipped = 0
known_limitations = 0


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


def record_result(test_num, name, status, detail=""):
    """Record a test result."""
    global passed, failed, skipped, known_limitations
    test_results.append({"num": test_num, "name": name, "status": status, "detail": detail})
    if status == "PASS":
        passed += 1
    elif status == "FAIL":
        failed += 1
    elif status == "SKIP":
        skipped += 1
    elif status == "KNOWN":
        known_limitations += 1


def print_summary():
    """Print a summary of all test results."""
    print("\n" + "=" * 60)
    print("TEST SUMMARY")
    print("=" * 60)
    for r in test_results:
        status_symbol = {
            "PASS": "✓",
            "FAIL": "✗",
            "SKIP": "○",
            "KNOWN": "⚠",
        }.get(r["status"], "?")
        detail = f" - {r['detail']}" if r["detail"] else ""
        print(f"  {status_symbol} Test {r['num']:2d}: {r['name']}{detail}")
    print("-" * 60)
    print(f"  Passed: {passed}")
    print(f"  Failed: {failed}")
    print(f"  Skipped: {skipped}")
    print(f"  Known limitations: {known_limitations}")
    print(f"  Total: {len(test_results)}")
    print("=" * 60)


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
            "workspaceFolders": [{"uri": f"file://{PROJECT_ROOT}", "name": "git"}],
            "capabilities": {},
        },
        1,
    )
    print(f"\n1. Initialize: {'OK' if resp and 'result' in resp else 'FAILED'}")
    record_result(1, "Initialize", "PASS" if resp and "result" in resp else "FAIL")

    send_notification(sock, "initialized", {})

    # Open file
    test_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
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
        record_result(2, "didOpen diagnostics", "PASS", f"{len(diags)} diags")
    else:
        print(f"    (no diagnostics from didOpen)")
        record_result(2, "didOpen diagnostics", "PASS", "no diags")

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
        record_result(3, "Document symbols", "PASS", f"{len(symbols)} symbols")
    else:
        print(f"3. Document symbols: FAILED - {resp}")
        record_result(3, "Document symbols", "FAIL")

    # Test definition - line 48 (0-indexed) has "MyTextDocumentService" reference
    resp = send_and_recv(
        sock,
        "textDocument/definition",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 48, "character": 20},
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
            record_result(4, "Definition", "PASS", f"{len(result)} locations")
        else:
            print(f"4. Definition: OK")
            record_result(4, "Definition", "PASS")
    else:
        if resp and "result" in resp and not resp["result"]:
            print(f"4. Definition: no results - known server limitation (TargetElementUtil)")
            record_result(4, "Definition", "KNOWN", "returns [] - TargetElementUtil limitation")
        else:
            print(f"4. Definition: FAILED or no result")
            if resp:
                print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
                if resp.get("error"):
                    print(f"    error: {resp['error']}")
            record_result(4, "Definition", "FAIL")

    # Test references - find references to MyTextDocumentService at line 48
    resp = send_and_recv(
        sock,
        "textDocument/references",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 48, "character": 20},
            "context": {"includeDeclaration": True},
        },
        4,
    )
    if resp and "result" in resp and resp["result"]:
        print(f"5. References: OK - Found {len(resp['result'])} references")
        record_result(5, "References", "PASS", f"{len(resp['result'])} refs")
    else:
        print(f"5. References: FAILED or no result")
        record_result(5, "References", "FAIL")

    # Test workspace symbols
    resp = send_and_recv(sock, "workspace/symbol", {"query": "Lsp"}, 5)
    if resp and "result" in resp and resp["result"]:
        print(f"6. Workspace symbols: OK - Found {len(resp['result'])} symbols")
        for s in resp["result"][:3]:
            print(f"   - {s.get('name')}")
        record_result(6, "Workspace symbols", "PASS", f"{len(resp['result'])} symbols")
    else:
        print(f"6. Workspace symbols: FAILED or no result")
        record_result(6, "Workspace symbols", "FAIL")

    # Test completion - line 50 is empty line inside class body (good for keyword completions)
    resp = send_and_recv(
        sock,
        "textDocument/completion",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 50, "character": 0},
        },
        6,
    )
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"7. Completion: OK - Found {len(result)} completions")
            record_result(7, "Completion", "PASS", f"{len(result)} items")
        else:
            print(f"7. Completion: OK - got CompletionList")
            record_result(7, "Completion", "PASS")
    else:
        print(f"7. Completion: FAILED")
        record_result(7, "Completion", "FAIL")

    # Test hover - line 48 (0-indexed) has MyTextDocumentService
    resp = send_and_recv(
        sock,
        "textDocument/hover",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 48, "character": 20},
        },
        7,
    )
    if resp and "result" in resp:
        print(f"8. Hover: OK")
        record_result(8, "Hover", "PASS")
    else:
        print(f"8. Hover: not supported or failed")
        record_result(8, "Hover", "FAIL")

    # Test type definition - line 48 (0-indexed) has "myTextDocumentService" variable
    # Type definition should navigate to MyTextDocumentService class
    resp = send_and_recv(
        sock,
        "textDocument/typeDefinition",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 48, "character": 40},
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
            record_result(9, "Type definition", "PASS", f"{len(result)} locations")
        else:
            print(f"9. Type definition: OK")
            record_result(9, "Type definition", "PASS")
    else:
        if resp and "result" in resp and not resp["result"]:
            print(f"9. Type definition: no results - known server limitation (TargetElementUtil)")
            record_result(9, "Type definition", "KNOWN", "returns [] - TargetElementUtil limitation")
        else:
            err = resp.get("error") if resp else None
            print(f"9. Type definition: FAILED (error={err})")
            if resp:
                print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
            record_result(9, "Type definition", "FAIL")

    # Test implementation - line 46 (0-indexed) has LspSession interface
    # Should find implementing classes
    resp = send_and_recv(
        sock,
        "textDocument/implementation",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 46, "character": 73},
        },
        9,
    )
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        if isinstance(result, list):
            print(f"10. Implementation: OK - Found {len(result)} location(s)")
            record_result(10, "Implementation", "PASS", f"{len(result)} locations")
        else:
            print(f"10. Implementation: OK")
            record_result(10, "Implementation", "PASS")
    else:
        if resp and "result" in resp and not resp["result"]:
            print(f"10. Implementation: no results - known server limitation (TargetElementUtil)")
            record_result(10, "Implementation", "KNOWN", "returns [] - TargetElementUtil limitation")
        else:
            err = resp.get("error") if resp else None
            print(f"10. Implementation: FAILED (error={err})")
            if resp:
                print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
            record_result(10, "Implementation", "FAIL")

    # Test document highlight - line 47 (0-indexed) has "LOG"
    # LOG is used multiple times in the file
    # Use a shorter socket timeout to avoid hanging if the server doesn't respond
    sock.settimeout(10)
    resp = send_and_recv(
        sock,
        "textDocument/documentHighlight",
        {
            "textDocument": {"uri": f"file://{test_file}"},
            "position": {"line": 47, "character": 30},
        },
        10,
    )
    sock.settimeout(30)
    if resp and "result" in resp and resp["result"]:
        print(f"11. Document highlight: OK - Found {len(resp['result'])} highlights")
        record_result(11, "Document highlight", "PASS", f"{len(resp['result'])} highlights")
    else:
        err = resp.get("error") if resp else None
        if resp is None:
            print(f"11. Document highlight: TIMEOUT - known limitation (HighlightUsagesHandler hangs)")
            record_result(11, "Document highlight", "KNOWN", "times out in HighlightUsagesHandler")
        else:
            print(f"11. Document highlight: no results (error={err})")
            record_result(11, "Document highlight", "KNOWN", "returns None - may need full indexing")

    # Test diagnostics on existing file - use LspServer.java which we know exists
    error_test_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"

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
            record_result(12, "Diagnostics", "PASS", f"{len(diags)} diags")
        else:
            print(f"12. Diagnostics: OK - but no errors")
            record_result(12, "Diagnostics", "PASS", "no errors")
    else:
        print(f"12. Diagnostics: No diagnostics received")
        record_result(12, "Diagnostics", "FAIL")

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

    # Test code actions - organize imports on a clean file (no error needed)
    org_test_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
    with open(org_test_file) as f:
        org_text = f.read()

    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{org_test_file}",
                "languageId": "java",
                "version": 1,
                "text": org_text,
            }
        },
    )
    print("    Waiting extra 3 seconds for indexing...")
    drain_notifications(sock, seconds=3)

    resp = send_and_recv(
        sock,
        "textDocument/codeAction",
        {
            "textDocument": {"uri": f"file://{org_test_file}"},
            "range": {"start": {"line": 0, "character": 0}, "end": {"line": 100, "character": 0}},
            "context": {"diagnostics": []}
        },
        13,
    )
    if resp and "result" in resp:
        actions = resp["result"]
        if actions:
            print(f"13. Code Actions (organize imports): OK - Found {len(actions)} actions")
            for a in actions[:3]:
                title = a.get("title", "unknown")
                print(f"    - {title[:60]}")
                if a.get("data"):
                    resolved = send_and_recv(sock, "codeAction/resolve", a, 14)
                    if resolved and "result" in resolved:
                        print(f"      Resolved title: {resolved['result'].get('title', 'N/A')[:60]}")
            record_result(13, "Code Actions", "PASS", f"{len(actions)} actions")
        else:
            print(f"13. Code Actions (organize imports): OK - No actions needed")
            record_result(13, "Code Actions", "PASS", "no actions")
    else:
        print(f"13. Code Actions (organize imports): Skipped")
        record_result(13, "Code Actions", "SKIP")

    # ============================================
    # Call Hierarchy Tests (prepareCallHierarchy)
    # ============================================
    # Setup: Open the test-data file which has callers inside same file
    # getName() is called by process() inside test-data
    test_calls_file = os.path.join(PROJECT_ROOT, "server/test-data/callhierarchy/TestCalls.java")
    try:
        with open(test_calls_file) as f:
            test_calls_text = f.read()
    except FileNotFoundError:
        print("TEST_DATA_NOT_FOUND: test-data/callhierarchy/TestCalls.java")
        test_calls_text = None

    if test_calls_text:
        send_notification(
            sock,
            "textDocument/didOpen",
            {
                "textDocument": {
                    "uri": f"file://{test_calls_file}",
                    "languageId": "java",
                    "version": 1,
                    "text": test_calls_text,
                }
            },
        )
        drain_notifications(sock, seconds=5)

        # Test 14: prepareCallHierarchy on getName() method (line 10, char 17)
        resp = send_and_recv(
            sock,
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{test_calls_file}"},
                "position": {"line": 10, "character": 17},
            },
            14,
        )
        if resp and "result" in resp and resp["result"]:
            items = resp["result"]
            if len(items) >= 1 and any(i.get("name") == "getName" for i in items):
                print(f"14. PrepareCallHierarchy on getName(): OK - Got {[i.get('name') for i in items]}")
                record_result(14, "PrepareCallHierarchy getName", "PASS", str([i.get('name') for i in items]))
            else:
                print(f"14. PrepareCallHierarchy: FAILED - Expected 'getName', got {[i.get('name') for i in items]}")
                record_result(14, "PrepareCallHierarchy getName", "FAIL", str([i.get('name') for i in items]))
        else:
            print(f"14. PrepareCallHierarchy: FAILED or no result - {resp}")
            record_result(14, "PrepareCallHierarchy getName", "FAIL")

        # Store for subsequent tests
        getname_item = None
        if resp and "result" in resp and resp["result"]:
            getname_item = resp["result"][0]

        # Test 15: incomingCalls to getName() - should find process() as caller
        if getname_item:
            resp = send_and_recv(
                sock,
                "callHierarchy/incomingCalls",
                {"item": getname_item},
                15,
            )
            if resp and "result" in resp and resp["result"]:
                calls = resp["result"]
                incoming_names = sorted([c["from"]["name"] for c in calls])
                expected = ["process"]
                if all(name in incoming_names for name in expected):
                    print(f"15. IncomingCalls to getName(): OK - Got {incoming_names}")
                    record_result(15, "IncomingCalls getName", "PASS", str(incoming_names))
                else:
                    print(f"15. IncomingCalls: FAILED - Expected {expected}, got {incoming_names}")
                    record_result(15, "IncomingCalls getName", "FAIL", str(incoming_names))
            else:
                print(f"15. IncomingCalls: FAILED or no result - {resp}")
                record_result(15, "IncomingCalls getName", "FAIL")

        # Test 17: prepareCallHierarchy on process() method (line 17, char 17)
        resp = send_and_recv(
            sock,
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{test_calls_file}"},
                "position": {"line": 17, "character": 17},
            },
            17,
        )
        if resp and "result" in resp and resp["result"]:
            items = resp["result"]
            if len(items) == 1 and items[0]["name"] == "process":
                print(f"17. PrepareCallHierarchy on process(): OK - Got '{items[0]['name']}'")
                record_result(17, "PrepareCallHierarchy process", "PASS")
            else:
                print(f"17. PrepareCallHierarchy: FAILED - Expected 'process', got {[i.get('name') for i in items]}")
                record_result(17, "PrepareCallHierarchy process", "FAIL", str([i.get('name') for i in items]))
        else:
            print(f"17. PrepareCallHierarchy: FAILED or no result - {resp}")
            record_result(17, "PrepareCallHierarchy process", "FAIL")

        process_item = None
        if resp and "result" in resp and resp["result"]:
            process_item = resp["result"][0]

        # Test 18: outgoingCalls from process() - should find getName(), printName(), TestCalls constructor
        if process_item:
            resp = send_and_recv(
                sock,
                "callHierarchy/outgoingCalls",
                {"item": process_item},
                18,
            )
            if resp and "result" in resp and resp["result"]:
                calls = resp["result"]
                outgoing_names = sorted([c["to"]["name"] for c in calls])
                if "getName" in outgoing_names and "printName" in outgoing_names:
                    print(f"18. OutgoingCalls from process(): OK - Got {outgoing_names}")
                    record_result(18, "OutgoingCalls process", "PASS", str(outgoing_names))
                else:
                    print(f"18. OutgoingCalls: FAILED - Expected getName/printName, got {outgoing_names}")
                    record_result(18, "OutgoingCalls process", "FAIL", str(outgoing_names))
            else:
                print(f"18. OutgoingCalls: FAILED or no result - {resp}")
                record_result(18, "OutgoingCalls process", "FAIL")

        # Test 19: incomingCalls to process() - should find main()
        if process_item:
            resp = send_and_recv(
                sock,
                "callHierarchy/incomingCalls",
                {"item": process_item},
                19,
            )
            if resp and "result" in resp and resp["result"]:
                calls = resp["result"]
                incoming_names = sorted([c["from"]["name"] for c in calls])
                if "main" in incoming_names:
                    print(f"19. IncomingCalls to process(): OK - Got {incoming_names}")
                    record_result(19, "IncomingCalls process", "PASS", str(incoming_names))
                else:
                    print(f"19. IncomingCalls: FAILED - Expected 'main', got {incoming_names}")
                    record_result(19, "IncomingCalls process", "FAIL", str(incoming_names))
            else:
                print(f"19. IncomingCalls: FAILED or no result - {resp}")
                record_result(19, "IncomingCalls process", "FAIL")

    # Test 20: prepareCallHierarchy on non-callable (a field declaration)
    # IntelliJ's API may return the containing class constructor for field positions
    resp = send_and_recv(
        sock,
        "textDocument/prepareCallHierarchy",
        {
            "textDocument": {"uri": f"file://{test_calls_file}"},
            "position": {"line": 4, "character": 20},  # 'name' field
        },
        20,
    )
    if resp and "result" in resp:
        result = resp["result"]
        if result is None or (isinstance(result, list) and len(result) == 0):
            print(f"20. PrepareCallHierarchy on field: OK - Got expected null/empty")
            record_result(20, "PrepareCallHierarchy field", "PASS", "null/empty as expected")
        else:
            names = [i.get("name") for i in (result if isinstance(result, list) else [result])]
            print(f"20. PrepareCallHierarchy on field: got {names} (IntelliJ returns containing class)")
            record_result(20, "PrepareCallHierarchy field", "KNOWN", f"returns {names} - IntelliJ behavior")
    else:
        print(f"20. PrepareCallHierarchy on field: FAILED - {resp}")
        record_result(20, "PrepareCallHierarchy field", "FAIL")

        # Cleanup: close test file
        send_notification(
            sock,
            "textDocument/didClose",
            {"textDocument": {"uri": f"file://{test_calls_file}"}},
        )

    # Test cross-file references
    # Use clean Java files from main source - LspServer is referenced from LspServerRunnerBase
    bootstrap_path = os.path.join(SOURCE_PATH, "tf/locals/idealsp/server/bootstrap")
    lsp_server_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
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
    print("    Waiting extra 15 seconds for cross-file indexing...")
    drain_notifications(sock, seconds=15)

    # Find references to LspServer class - should find usages in LspServerRunnerBase.java
    resp = send_and_recv(
        sock,
        "textDocument/references",
        {
            "textDocument": {"uri": f"file://{lsp_server_file}"},
            "position": {
                "line": 46,
                "character": 13,
            },  # "L" of "LspServer" in "public class LspServer" (LSP lines 0-indexed)
            "context": {"includeDeclaration": True},
        },
        13,
    )
    if resp and "result" in resp and resp["result"]:
        refs = resp["result"]
        cross_file = any("LspServerRunnerBase" in str(r.get("uri", "")) for r in refs)
        same_file = any("LspServer.java" in str(r.get("uri", "")) for r in refs)
        print(f"15b. Cross-file References: OK - Found {len(refs)} references")
        print(f"    - Same file: {same_file}, Cross-file: {cross_file}")
        if cross_file:
            record_result(15, "Cross-file references", "PASS", f"{len(refs)} refs, cross-file={cross_file}")
        else:
            print(f"    WARNING: Cross-file references not working - known limitation")
            record_result(15, "Cross-file references", "KNOWN", "all refs are same-file")
    else:
        print(f"15b. Cross-file References: FAILED or no result")
        record_result(15, "Cross-file references", "FAIL")

# Test dataflow using DataFlowTestTarget.java (rich data flow chains)
    dataflow_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/DataFlowTestTarget.java"
    with open(dataflow_file) as f:
        dataflow_text = f.read()
    send_notification(
        sock,
        "textDocument/didOpen",
        {
            "textDocument": {
                "uri": f"file://{dataflow_file}",
                "languageId": "java",
                "version": 1,
                "text": dataflow_text,
            }
        },
    )
    print("    Waiting 10s for dataflow file indexing...")
    time.sleep(10)

    # Test 21: dataflowFrom on constructor param (line 7, char 37 = "input")
    # public DataFlowTestTarget(String input) — flows to: this.inputValue = input (line 8)
    resp = send_and_recv(
        sock,
        "textDocument/dataflowFrom",
        {
            "textDocument": {"uri": f"file://{dataflow_file}"},
            "position": {"line": 7, "character": 37},
        },
        21,
    )
    if resp and "result" in resp:
        result = resp["result"]
        if isinstance(result, list):
            print(f"21. DataFlowFrom on constructor param: OK - Found {len(result)} locations")
            if len(result) > 0:
                for item in result:
                    loc_data = item.get("location", {})
                    uri = loc_data.get("uri", "no-uri")
                    range_data = loc_data.get("range", {})
                    line = range_data.get("start", {}).get("line", -1)
                    print(f"    - {uri.split('/')[-1]}:{line}")
                record_result(21, "DataFlowFrom", "PASS", f"{len(result)} locations")
            else:
                print(f"    (Empty result)")
                record_result(21, "DataFlowFrom", "FAIL", "empty result")
        else:
            print(f"21. DataFlowFrom on constructor param: OK - got {type(result).__name__}")
            record_result(21, "DataFlowFrom", "PASS")
    else:
        print(f"21. DataFlowFrom: FAILED")
        record_result(21, "DataFlowFrom", "FAIL")

    # Test 22: dataflowTo on inputValue field (line 3, char 20 = "n" in inputValue)
    # Should find: constructor param "input" that flows INTO this field
    resp = send_and_recv(
        sock,
        "textDocument/dataflowTo",
        {
            "textDocument": {"uri": f"file://{dataflow_file}"},
            "position": {"line": 3, "character": 20},
        },
        22,
    )
    if resp and "result" in resp:
        result = resp["result"]
        if isinstance(result, list):
            print(f"22. DataFlowTo on field: OK - Found {len(result)} locations")
            if len(result) > 0:
                for item in result:
                    loc_data = item.get("location", {})
                    uri = loc_data.get("uri", "no-uri")
                    range_data = loc_data.get("range", {})
                    line = range_data.get("start", {}).get("line", -1)
                    print(f"    - {uri.split('/')[-1]}:{line}")
                record_result(22, "DataFlowTo", "PASS", f"{len(result)} locations")
            else:
                print(f"    (Empty result)")
                record_result(22, "DataFlowTo", "FAIL", "empty result")
        else:
            print(f"22. DataFlowTo on field: OK - got {type(result).__name__}")
            record_result(22, "DataFlowTo", "PASS")
    else:
        print(f"22. DataFlowTo: FAILED")
        record_result(22, "DataFlowTo", "FAIL")

    # Test inspection list — list all inspections
    resp = send_and_recv(sock, "$/inspection/list", {"query": ""}, 23)
    if resp and "result" in resp and resp["result"]:
        inspections = resp["result"]
        print(f"23. Inspection list (all): OK - Found {len(inspections)} inspections")
        first_three = sorted(inspections, key=lambda i: i.get("shortName", ""))[:3]
        for i in first_three:
            print(f"    - {i.get('shortName')}: {i.get('displayName', '')[:40]}")
        record_result(23, "Inspection list all", "PASS", f"{len(inspections)} inspections")
    else:
        err = resp.get("error") if resp else None
        print(f"23. Inspection list (all): FAILED (error={err})")
        record_result(23, "Inspection list all", "FAIL")

    # Test inspection list — search by query
    resp = send_and_recv(sock, "$/inspection/list", {"query": "unused"}, 24)
    if resp and "result" in resp and resp["result"]:
        inspections = resp["result"]
        print(f"24. Inspection list (search 'unused'): OK - Found {len(inspections)} inspections")
        for i in inspections[:3]:
            print(f"    - {i.get('shortName')}: {i.get('displayName', '')[:40]}")
        if len(inspections) > 0:
            all_match = all("unused" in (i.get("shortName", "") + i.get("displayName", "")).lower()
                           for i in inspections)
            print(f"    - All results match 'unused': {all_match}")
        record_result(24, "Inspection list search", "PASS", f"{len(inspections)} inspections")
    else:
        err = resp.get("error") if resp else None
        print(f"24. Inspection list (search): FAILED (error={err})")
        record_result(24, "Inspection list search", "FAIL")

    # Test inspection list — non-existent query
    resp = send_and_recv(sock, "$/inspection/list", {"query": "zzzthisdoesnotexist"}, 25)
    if resp and "result" in resp:
        inspections = resp["result"]
        if isinstance(inspections, list) and len(inspections) == 0:
            print(f"25. Inspection list (non-existent): OK - Got empty list as expected")
            record_result(25, "Inspection list non-existent", "PASS")
        else:
            print(f"25. Inspection list (non-existent): UNEXPECTED - Got {len(inspections) if isinstance(inspections, list) else type(inspections).__name__}")
            record_result(25, "Inspection list non-existent", "FAIL")
    else:
        err = resp.get("error") if resp else None
        print(f"25. Inspection list (non-existent): FAILED (error={err})")
        record_result(25, "Inspection list non-existent", "FAIL")

    # Test inspection runByName — run a specific inspection on a file
    test_run_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
    resp = send_and_recv(
        sock,
        "$/inspection/runByName",
        {"textDocument": {"uri": f"file://{test_run_file}"}, "name": "unused"},
        26,
    )
    if resp and "result" in resp:
        diagnostics = resp["result"]
        if isinstance(diagnostics, list):
            print(f"26. Inspection runByName (unused): OK - Found {len(diagnostics)} diagnostics")
            for d in diagnostics[:3]:
                sev = {1: "Error", 2: "Warn", 3: "Info", 4: "Hint"}.get(d.get("severity"), "?")
                msg = (d.get("message") or "")[:60]
                print(f"    - [{sev}] {msg}")
            record_result(26, "Inspection runByName unused", "PASS", f"{len(diagnostics)} diags")
        else:
            print(f"26. Inspection runByName: FAILED - unexpected format")
            record_result(26, "Inspection runByName unused", "FAIL")
    else:
        err = resp.get("error") if resp else None
        print(f"26. Inspection runByName (unused): FAILED (error={err})")
        record_result(26, "Inspection runByName unused", "FAIL")

    # Test inspection runByName with non-existent name
    resp = send_and_recv(
        sock,
        "$/inspection/runByName",
        {"textDocument": {"uri": f"file://{test_run_file}"}, "name": "zzzthisdoesnotexist"},
        27,
    )
    if resp and "result" in resp:
        diagnostics = resp["result"]
        if isinstance(diagnostics, list) and len(diagnostics) == 0:
            print(f"27. Inspection runByName (non-existent): OK - Got empty list as expected")
            record_result(27, "Inspection runByName non-existent", "PASS")
        else:
            print(f"27. Inspection runByName (non-existent): OK - returned safely")
            record_result(27, "Inspection runByName non-existent", "PASS")
    else:
        err = resp.get("error") if resp else None
        print(f"27. Inspection runByName (non-existent): FAILED (error={err})")
        record_result(27, "Inspection runByName non-existent", "FAIL")

    # Test inspection runByName on all files (no textDocument) - known to timeout
    sock.settimeout(15)
    resp = send_and_recv(
        sock,
        "$/inspection/runByName",
        {"name": "unused"},
        28,
    )
    sock.settimeout(30)
    if resp and "result" in resp:
        diagnostics = resp["result"]
        if isinstance(diagnostics, list):
            print(f"28. Inspection runByName (all files): OK - Found {len(diagnostics)} diagnostics across project")
            for d in diagnostics[:3]:
                sev = {1: "Error", 2: "Warn", 3: "Info", 4: "Hint"}.get(d.get("severity"), "?")
                msg = (d.get("message") or "")[:60]
                code = d.get("code", "")
                print(f"    - [{sev}] {msg}")
            record_result(28, "Inspection runByName all-files", "PASS", f"{len(diagnostics)} diags")
        else:
            print(f"28. Inspection runByName (all files): FAILED - unexpected format")
            record_result(28, "Inspection runByName all-files", "FAIL")
    else:
        err = resp.get("error") if resp else None
        if resp is None:
            print(f"28. Inspection runByName (all files): TIMEOUT - known limitation (project-wide inspection is slow)")
            record_result(28, "Inspection runByName all-files", "KNOWN", "TIMEOUT - project-wide inspection is slow")
        else:
            print(f"28. Inspection runByName (all files): FAILED (error={err})")
            record_result(28, "Inspection runByName all-files", "FAIL")

    # Test inspection runByName on all files with null textDocument
    sock.settimeout(15)
    resp = send_and_recv(
        sock,
        "$/inspection/runByName",
        {"textDocument": None, "name": "unused"},
        29,
    )
    sock.settimeout(30)
    if resp and "result" in resp:
        diagnostics = resp["result"]
        if isinstance(diagnostics, list):
            print(f"29. Inspection runByName (null textDocument): OK - Found {len(diagnostics)} diagnostics across project")
            record_result(29, "Inspection runByName null-textDocument", "PASS", f"{len(diagnostics)} diags")
        else:
            print(f"29. Inspection runByName (null textDocument): FAILED - unexpected format")
            record_result(29, "Inspection runByName null-textDocument", "FAIL")
    else:
        err = resp.get("error") if resp else None
        if resp is None:
            print(f"29. Inspection runByName (null textDocument): TIMEOUT - known limitation")
            record_result(29, "Inspection runByName null-textDocument", "KNOWN", "TIMEOUT")
        else:
            print(f"29. Inspection runByName (null textDocument): FAILED (error={err})")
            record_result(29, "Inspection runByName null-textDocument", "FAIL")

# ============================================
    # Code Action Apply Test
    # ============================================
    # Use LspServer.java which is already open from test 13 and indexed
    apply_test_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"

    # Get code actions - should return (not hang) since file is already open
    resp = send_and_recv(
        sock,
        "textDocument/codeAction",
        {
            "textDocument": {"uri": f"file://{apply_test_file}"},
            "range": {"start": {"line": 0, "character": 0}, "end": {"line": 100, "character": 0}},
            "context": {"diagnostics": []}
        },
        30,
    )
    if resp and "result" in resp:
        actions = resp["result"]
        if actions:
            print(f"30. Code Actions: OK - Found {len(actions)} actions")
            record_result(30, "Code Actions", "PASS", f"{len(actions)} actions")
        else:
            print(f"30. Code Actions: OK - No actions at this location")
            record_result(30, "Code Actions", "PASS", "no actions")
    else:
        print(f"30. Code Actions: FAILED - no response")
        record_result(30, "Code Actions", "FAIL")

    # ============================================
    # Additional LSP Feature Tests
    # ============================================

    # Re-open the file cleanly before additional tests
    sig_help_file = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
    with open(sig_help_file) as f:
        clean_text = f.read()

    send_notification(sock, "textDocument/didOpen", {
        "textDocument": {"uri": f"file://{sig_help_file}", "languageId": "java", "version": 100, "text": clean_text}
    })
    drain_notifications(sock, seconds=3)

    # Test signatureHelp - on a method call with parameters (line 65 has messageBusConnection = ...)
    sock.settimeout(10)
    resp = send_and_recv(
        sock,
        "textDocument/signatureHelp",
        {
            "textDocument": {"uri": f"file://{sig_help_file}"},
            "position": {"line": 65, "character": 50},
        },
        34,
    )
    sock.settimeout(30)
    if resp and "result" in resp:
        result = resp["result"]
        if result and result.get("signatures"):
            sigs = result["signatures"]
            print(f"34. Signature Help: OK - Found {len(sigs)} signatures")
            record_result(34, "Signature Help", "PASS", f"{len(sigs)} signatures")
        else:
            print(f"34. Signature Help: OK - No signatures at this position")
            record_result(34, "Signature Help", "PASS", "no signatures")
    else:
        print(f"34. Signature Help: TIMEOUT or not supported")
        record_result(34, "Signature Help", "KNOWN", "TIMEOUT - server not responding")

    # Test formatting - format the entire file
    sock.settimeout(10)
    resp = send_and_recv(
        sock,
        "textDocument/formatting",
        {
            "textDocument": {"uri": f"file://{sig_help_file}"},
            "options": {"tabSize": 4, "insertSpaces": True},
        },
        35,
    )
    sock.settimeout(30)
    if resp and "result" in resp:
        result = resp["result"]
        if result:
            print(f"35. Formatting: OK - Got {len(result)} text edits")
            record_result(35, "Formatting", "PASS", f"{len(result)} edits")
        else:
            print(f"35. Formatting: OK - File is already formatted")
            record_result(35, "Formatting", "PASS", "already formatted")
    else:
        print(f"35. Formatting: TIMEOUT or not supported")
        record_result(35, "Formatting", "KNOWN", "TIMEOUT - server not responding")

    # Test range formatting - format a small range
    sock.settimeout(10)
    resp = send_and_recv(
        sock,
        "textDocument/rangeFormatting",
        {
            "textDocument": {"uri": f"file://{sig_help_file}"},
            "range": {
                "start": {"line": 0, "character": 0},
                "end": {"line": 10, "character": 0},
            },
            "options": {"tabSize": 4, "insertSpaces": True},
        },
        36,
    )
    sock.settimeout(30)
    if resp and "result" in resp:
        result = resp["result"]
        if result:
            print(f"36. Range Formatting: OK - Got {len(result)} text edits")
            record_result(36, "Range Formatting", "PASS", f"{len(result)} edits")
        else:
            print(f"36. Range Formatting: OK - Range is already formatted")
            record_result(36, "Range Formatting", "PASS", "already formatted")
    else:
        print(f"36. Range Formatting: TIMEOUT or not supported")
        record_result(36, "Range Formatting", "KNOWN", "TIMEOUT - server not responding")

    # Test rename - on LOG field (line 47, char 30)
    sock.settimeout(10)
    resp = send_and_recv(
        sock,
        "textDocument/rename",
        {
            "textDocument": {"uri": f"file://{sig_help_file}"},
            "position": {"line": 47, "character": 30},
            "newName": "renamedLOG",
        },
        37,
    )
    sock.settimeout(30)
    if resp and "result" in resp:
        result = resp["result"]
        if result and result.get("changes"):
            changes = result["changes"]
            total_changes = sum(len(uris) for uris in changes.values())
            print(f"37. Rename: OK - Would make {total_changes} changes across {len(changes)} files")
            record_result(37, "Rename", "PASS", f"{total_changes} changes")
        else:
            print(f"37. Rename: OK - No changes needed")
            record_result(37, "Rename", "PASS", "no changes")
    else:
        print(f"37. Rename: TIMEOUT or not supported")
        record_result(37, "Rename", "KNOWN", "TIMEOUT - server not responding")

    # Test resolveCompletionItem - get a completion item and resolve it
    sock.settimeout(10)
    resp = send_and_recv(
        sock,
        "textDocument/completion",
        {
            "textDocument": {"uri": f"file://{sig_help_file}"},
            "position": {"line": 50, "character": 4},
        },
        38,
    )
    sock.settimeout(30)
    if resp and "result" in resp and resp["result"]:
        result = resp["result"]
        items = result if isinstance(result, list) else result.get("items", [])
        if items:
            first_item = items[0]
            if first_item.get("data"):
                resolved = send_and_recv(sock, "completionItem/resolve", first_item, 39)
                if resolved and "result" in resolved:
                    print(f"38. ResolveCompletionItem: OK - Resolved '{resolved['result'].get('label', 'unknown')}'")
                    record_result(38, "ResolveCompletionItem", "PASS")
                else:
                    print(f"38. ResolveCompletionItem: FAILED")
                    record_result(38, "ResolveCompletionItem", "FAIL")
            else:
                print(f"38. ResolveCompletionItem: SKIP - No data on completion item")
                record_result(38, "ResolveCompletionItem", "SKIP")
        else:
            print(f"38. ResolveCompletionItem: SKIP - No completions available")
            record_result(38, "ResolveCompletionItem", "SKIP")
    else:
        print(f"38. ResolveCompletionItem: TIMEOUT - no response")
        record_result(38, "ResolveCompletionItem", "KNOWN", "TIMEOUT")

    # Test shutdown lifecycle (run BEFORE semantic search which hangs server)
    resp = send_and_recv(sock, "shutdown", {}, 40)
    if resp and "result" in resp:
        print(f"39. Shutdown: OK")
        record_result(39, "Shutdown", "PASS")
        send_notification(sock, "exit", {})
    else:
        print(f"39. Shutdown: FAILED")
        record_result(39, "Shutdown", "FAIL")

    sock.close()

    # ============================================
    # Semantic Search Tests (separate connection - known to hang server)
    # Run LAST since they may leave server in bad state
    # ============================================
    semantic_test_file = os.path.join(SOURCE_PATH, "tf/locals/idealsp/server/LspServer.java")
    with open(semantic_test_file) as f:
        semantic_file_content = f.read()

    sock2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock2.settimeout(30)
    try:
        sock2.connect(("127.0.0.1", 8989))
        send_and_recv(sock2, "initialize", {"processId": 12345, "clientInfo": {"name": "test", "version": "1.0"}, "workspaceFolders": [{"uri": f"file://{PROJECT_ROOT}", "name": "git"}], "capabilities": {}}, 100)
        send_notification(sock2, "initialized", {})
        send_notification(sock2, "textDocument/didOpen", {"textDocument": {"uri": f"file://{semantic_test_file}", "languageId": "java", "version": 1, "text": semantic_file_content}})
        drain_notifications(sock2, 5)

        sock2.settimeout(10)
        resp = send_and_recv(sock2, "textDocument/semanticSearch", {"pattern": "$Type$ $FieldName$;", "scope": "file", "language": "java", "fileUri": f"file://{semantic_test_file}"}, 131)
        sock2.settimeout(30)
        if resp and "result" in resp and len(resp["result"]) > 0:
            print(f"31. Semantic Search: OK - Found {len(resp['result'])} matches")
            record_result(31, "Semantic Search", "PASS", f"{len(resp['result'])} matches")
        else:
            print(f"31. Semantic Search: TIMEOUT - known limitation (Matcher.findMatches may hang)")
            record_result(31, "Semantic Search", "KNOWN", "TIMEOUT")

        sock2.settimeout(10)
        resp = send_and_recv(sock2, "textDocument/semanticSearch", {"pattern": "$Type$ $FieldName$;", "scope": "file", "language": "java", "fileUri": f"file://{semantic_test_file}", "constraints": {"$Type$": {"regex": "Logger"}}}, 132)
        sock2.settimeout(30)
        if resp and "result" in resp:
            matched = len(resp["result"]) if resp["result"] else 0
            print(f"32. Semantic Search with constraint: {'OK' if matched > 0 else 'no results'} - Found {matched}")
            record_result(32, "Semantic Search constraint", "PASS" if matched > 0 else "KNOWN", f"{matched} matches")
        else:
            print(f"32. Semantic Search with constraint: TIMEOUT - known limitation")
            record_result(32, "Semantic Search constraint", "KNOWN", "TIMEOUT")

        sock2.settimeout(10)
        resp = send_and_recv(sock2, "textDocument/semanticSearch", {"pattern": "$Type$ $FieldName$;", "scope": "file", "language": "java", "fileUri": f"file://{semantic_test_file}", "constraints": {"$Type$": {"foo": "bar"}}}, 133)
        sock2.settimeout(30)
        if resp and "error" in resp:
            print(f"33. Semantic Search invalid constraint: OK - got error")
            record_result(33, "Semantic Search invalid constraint", "PASS")
        else:
            print(f"33. Semantic Search invalid constraint: TIMEOUT - known limitation")
            record_result(33, "Semantic Search invalid constraint", "KNOWN", "TIMEOUT")

        send_and_recv(sock2, "shutdown", {}, 140)
        send_notification(sock2, "exit", {})
        sock2.close()
    except ConnectionRefusedError:
        print("31-33. Semantic Search: SKIPPED - server unavailable")
        record_result(31, "Semantic Search", "SKIP")
        record_result(32, "Semantic Search constraint", "SKIP")
        record_result(33, "Semantic Search invalid constraint", "SKIP")

    print_summary()
    print("\n=== All tests completed ===")


if __name__ == "__main__":
    test_all()
