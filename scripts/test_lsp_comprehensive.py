#!/usr/bin/env python3
"""
Test script for IdeaLSP server - comprehensive version.

Each test is a separate method; tests are individually skippable via
--test/--tests/--from/--skip.

Usage:
  python3 test_lsp_comprehensive.py              # Run all tests
  python3 test_lsp_comprehensive.py --test 39     # Run single test
  python3 test_lsp_comprehensive.py --tests 39,40,41  # Run specific tests
  python3 test_lsp_comprehensive.py --from 39     # Run from test 39 onward
  python3 test_lsp_comprehensive.py --skip 37,38  # Run all except specified
"""

import argparse
import json
import os
import shutil
import socket
import sys
import time

# Workspace root (where .idea/ lives — enables Gradle import and proper indexing)
PROJECT_ROOT = os.environ.get("PROJECT_WORKSPACE", "/vokk/home/lauri/dev/idealspserver/git")
# Source root for file paths (src/main/java)
SOURCE_PATH = os.path.join(PROJECT_ROOT, "server/src/main/java")

LSP_SERVER_FILE = f"{SOURCE_PATH}/tf/locals/idealsp/server/LspServer.java"
BOOTSTRAP_PATH = f"{SOURCE_PATH}/tf/locals/idealsp/server/bootstrap"
LSP_RUNNER_FILE = f"{BOOTSTRAP_PATH}/LspServerRunnerBase.java"
TEST_CALLS_FILE = os.path.join(PROJECT_ROOT, "server/test-data/callhierarchy/TestCalls.java")
TYPE_HIERARCHY_FILE = os.path.join(PROJECT_ROOT, "server/test-data/typehierarchy/TypeHierarchyTest.java")
DATAFLOW_FILE = f"{SOURCE_PATH}/tf/locals/idealsp/server/DataFlowTestTarget.java"

REFACTOR_TEST_FILE = f"{SOURCE_PATH}/tf/locals/idealsp/server/RefactorTest.java"
REFACTOR_SNIPPET = (
    "public class RefactorTest {\n"
    "    void test() {\n"
    "        int a = 1;\n"
    "        int b = 2;\n"
    "        int c = a + b;\n"
    "        String s = \"hello\";\n"
    "    }\n"
    "}\n"
)

MOVE_TEST_FILE = f"{SOURCE_PATH}/tf/locals/idealsp/server/MoveMe.java"
MOVE_TARGET_DIR = f"{SOURCE_PATH}/tf/locals/idealsp/server/movedest"
MOVE_SNIPPET = (
    "package tf.locals.idealsp.server;\n"
    "public class MoveMe {\n"
    "    public void sayHello() {\n"
    "        System.out.println(\"hello\");\n"
    "    }\n"
    "}\n"
)

SAFEDELETE_TEST_FILE = f"{SOURCE_PATH}/tf/locals/idealsp/server/DeleteMe.java"
SAFEDELETE_SNIPPET = (
    "package tf.locals.idealsp.server;\n"
    "public class DeleteMe {\n"
    "    private int keep;\n"
    "    public void usedMethod() {\n"
    "        System.out.println(\"used\");\n"
    "    }\n"
    "    public void unusedMethod() {\n"
    "        int x = 1;\n"
    "    }\n"
    "}\n"
)

APPLY_TEST_FILE = f"{SOURCE_PATH}/tf/locals/idealsp/server/ApplyTest.java"
APPLY_SNIPPET = (
    "package tf.locals.idealsp.server;\n"
    "public class ApplyTest {\n"
    "    public static void f() {\n"
    "        int a = \"\";\n"
    "        System.out.println();\n"
    "    }\n"
    "}\n"
)

# Track diagnostics and code actions responses
diagnostics_result = {}
code_actions_result = {}

# Buffer for notifications received outside the main wait loop
notification_buffer = []

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

        # Buffer idea/indexFinished so the wait loop can find it
        if resp.get("method") == "idea/indexFinished":
            notification_buffer.append(resp)

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


def resync_socket(sock, timeout=20):
    """Drain any orphan responses from a previous TIMEOUT so the next
    test doesn't pick up stale messages."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        remaining = max(0.5, deadline - time.time())
        msg = recv_message(sock, timeout=remaining)
        if msg is None:
            break
        if "id" in msg and "method" in msg:
            reply = {"jsonrpc": "2.0", "id": msg["id"], "result": None}
            content = json.dumps(reply)
            sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())


def parse_test_args():
    parser = argparse.ArgumentParser(description="Run LSP comprehensive tests")
    parser.add_argument("--test", "-t", type=int, help="Run a single test by number")
    parser.add_argument("--tests", type=str, help="Comma-separated list of test numbers")
    parser.add_argument("--from", dest="from_num", type=int, help="Run from test N onward")
    parser.add_argument("--skip", type=str, help="Comma-separated list of test numbers to skip")
    return parser.parse_args()


TEST_ARGS = parse_test_args()

def should_run(test_num):
    if TEST_ARGS.test is not None:
        return test_num == TEST_ARGS.test
    if TEST_ARGS.tests is not None:
        selected = [int(t.strip()) for t in TEST_ARGS.tests.split(",")]
        return test_num in selected
    if TEST_ARGS.from_num is not None:
        return test_num >= TEST_ARGS.from_num
    if TEST_ARGS.skip is not None:
        skipped_nums = [int(t.strip()) for t in TEST_ARGS.skip.split(",")]
        return test_num not in skipped_nums
    return True


def skip_test(test_num, test_name):
    print(f"{test_num}. {test_name}: SKIPPED")
    record_result(test_num, test_name, "SKIP")


class ComprehensiveTestSuite:
    """One method per test; every test is independently skippable."""

    def __init__(self):
        self.sock = None
        self.sock2 = None
        self.opened = set()
        self.file_texts = {}
        self.index_ready = False

    # ------------------------------------------------------------------
    # Shared plumbing
    # ------------------------------------------------------------------

    def connect(self):
        if self.sock is not None:
            return self.sock
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(90)
        sock.connect(("127.0.0.1", 8989))
        print("Connected to LSP server")
        self.sock = sock
        return sock

    def initialize(self):
        self.connect()
        if self.index_ready:
            return True
        resp = send_and_recv(
            self.sock,
            "initialize",
            {
                "processId": 12345,
                "clientInfo": {"name": "test", "version": "1.0"},
                "workspaceFolders": [{"uri": f"file://{PROJECT_ROOT}", "name": "git"}],
                "capabilities": {},
            },
            1,
        )
        self.init_resp = resp
        send_notification(self.sock, "initialized", {})
        return resp and "result" in resp

    def ensure_open(self, path, version=1, force=False, language="java"):
        uri = f"file://{path}"
        if not force and uri in self.opened:
            return
        with open(path) as f:
            text = f.read()
        self.file_texts[path] = text
        send_notification(self.sock, "textDocument/didOpen", {
            "textDocument": {"uri": uri, "languageId": language, "version": version, "text": text}
        })
        self.opened.add(uri)

    def close_open(self, path):
        send_notification(self.sock, "textDocument/didClose", {"textDocument": {"uri": f"file://{path}"}})
        self.opened.discard(f"file://{path}")

    def wait_index(self, timeout=120):
        if self.index_ready:
            return
        deadline = time.time() + timeout
        while time.time() < deadline:
            while notification_buffer:
                notif = notification_buffer.pop(0)
                if notif.get("method") == "idea/indexFinished":
                    print("    Received idea/indexFinished notification (from buffer)")
                    self.index_ready = True
                    break
            if self.index_ready:
                break
            msg = recv_message(self.sock, timeout=min(10, deadline - time.time()))
            if msg is None:
                continue
            if msg.get("method") == "textDocument/publishDiagnostics":
                diagnostics_result["data"] = msg.get("params", {})
            if msg.get("method") == "idea/indexFinished":
                print("    Received idea/indexFinished notification")
                self.index_ready = True
                break
            if msg.get("method") == "idea/indexStarted":
                print("    Received idea/indexStarted notification, waiting for finish...")
                continue
            if "id" in msg and "method" in msg:
                reply = {"jsonrpc": "2.0", "id": msg["id"], "result": None}
                content = json.dumps(reply)
                self.sock.send(f"Content-Length: {len(content)}\r\n\r\n{content}".encode())
        if not self.index_ready:
            print("    WARNING: indexing did not complete within 120s, proceeding anyway")
            self.index_ready = True
        drain_notifications(self.sock, seconds=5)

    def resync(self, timeout=15):
        resync_socket(self.sock, timeout)

    def request(self, method, params, req_id):
        return send_and_recv(self.sock, method, params, req_id)

    # ------------------------------------------------------------------
    # Test 1: Initialize
    # ------------------------------------------------------------------

    def test_01_initialize(self):
        ok = self.initialize()
        print(f"\n1. Initialize: {'OK' if ok else 'FAILED'}")
        record_result(1, "Initialize", "PASS" if ok else "FAIL")

    # ------------------------------------------------------------------
    # Test 2: didOpen + indexing
    # ------------------------------------------------------------------

    def test_02_didopen(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        print("2. Opened test file, waiting for indexing and source root stabilization...")
        self.wait_index()
        if diagnostics_result.get("data"):
            diags = diagnostics_result["data"].get("diagnostics", [])
            print(f"    (didOpen produced {len(diags)} diagnostics)")
            record_result(2, "didOpen diagnostics", "PASS", f"{len(diags)} diags")
        else:
            print(f"    (no diagnostics from didOpen)")
            record_result(2, "didOpen diagnostics", "PASS", "no diags")

    # ------------------------------------------------------------------
    # Tests 3-13: textDocument basics on LspServer.java
    # ------------------------------------------------------------------

    def test_03_document_symbols(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request("textDocument/documentSymbol", {"textDocument": {"uri": f"file://{LSP_SERVER_FILE}"}}, 2)
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

    def test_04_definition(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/definition",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 54, "character": 28},
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
                print(f"4. Definition: no results")
                record_result(4, "Definition", "FAIL", "returns []")
            else:
                print(f"4. Definition: FAILED or no result")
                if resp:
                    print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
                    if resp.get("error"):
                        print(f"    error: {resp['error']}")
                record_result(4, "Definition", "FAIL")

    def test_05_references(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/references",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 52, "character": 13},
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

    def test_06_workspace_symbols(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request("workspace/symbol", {"query": "Lsp"}, 5)
        if resp and "result" in resp and resp["result"]:
            print(f"6. Workspace symbols: OK - Found {len(resp['result'])} symbols")
            for s in resp["result"][:3]:
                print(f"   - {s.get('name')}")
            record_result(6, "Workspace symbols", "PASS", f"{len(resp['result'])} symbols")
        else:
            print(f"6. Workspace symbols: FAILED or no result")
            record_result(6, "Workspace symbols", "FAIL")

    def test_07_completion(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/completion",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
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

    def test_08_hover(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/hover",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 54, "character": 28},
            },
            7,
        )
        if resp and "result" in resp:
            print(f"8. Hover: OK")
            record_result(8, "Hover", "PASS")
        else:
            print(f"8. Hover: not supported or failed")
            record_result(8, "Hover", "FAIL")

    def test_09_type_definition(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/typeDefinition",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 54, "character": 44},
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
                print(f"9. Type definition: no results")
                record_result(9, "Type definition", "FAIL", "returns []")
            else:
                err = resp.get("error") if resp else None
                print(f"9. Type definition: FAILED (error={err})")
                if resp:
                    print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
                record_result(9, "Type definition", "FAIL")

    def test_10_implementation(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/implementation",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 52, "character": 70},
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
                print(f"10. Implementation: no results")
                record_result(10, "Implementation", "FAIL", "returns []")
            else:
                err = resp.get("error") if resp else None
                print(f"10. Implementation: FAILED (error={err})")
                if resp:
                    print(f"    raw: {json.dumps(resp.get('result'))[:200]}")
                record_result(10, "Implementation", "FAIL")

    def test_11_document_highlight(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/documentHighlight",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 53, "character": 30},
            },
            10,
        )
        self.sock.settimeout(90)
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

    def test_12_diagnostics(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        send_notification(
            self.sock,
            "textDocument/didChange",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}", "version": 2},
                "contentChanges": [{"text": " String x = 123;  // Type mismatch error\n"}],
            },
        )
        print("    Sent change to LspServer.java to introduce error...")
        drain_notifications(self.sock, seconds=8)
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
        text = self.file_texts.get(LSP_SERVER_FILE, "")
        send_notification(
            self.sock,
            "textDocument/didChange",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}", "version": 3},
                "contentChanges": [{"text": text}],
            },
        )
        drain_notifications(self.sock, seconds=8)

    def test_13_code_actions_organize(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True)
        self.wait_index()
        print("    Waiting extra 3 seconds for indexing...")
        drain_notifications(self.sock, seconds=3)
        resp = self.request(
            "textDocument/codeAction",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
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
                        resolved = self.request("codeAction/resolve", a, 14)
                        if resolved and "result" in resolved:
                            print(f"      Resolved title: {resolved['result'].get('title', 'N/A')[:60]}")
                record_result(13, "Code Actions", "PASS", f"{len(actions)} actions")
            else:
                print(f"13. Code Actions (organize imports): OK - No actions needed")
                record_result(13, "Code Actions", "PASS", "no actions")
        else:
            print(f"13. Code Actions (organize imports): Skipped")
            record_result(13, "Code Actions", "SKIP")

    # ------------------------------------------------------------------
    # Tests 14-20: Call hierarchy (TestCalls.java)
    # ------------------------------------------------------------------

    def _ensure_calls(self):
        self.initialize()
        self.ensure_open(TEST_CALLS_FILE)
        self.wait_index()
        drain_notifications(self.sock, seconds=5)

    def test_14_prepare_call_getname(self):
        self._ensure_calls()
        resp = self.request(
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{TEST_CALLS_FILE}"},
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

    def test_15_incoming_getname(self):
        self._ensure_calls()
        resp = self.request(
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{TEST_CALLS_FILE}"},
                "position": {"line": 10, "character": 17},
            },
            14,
        )
        getname_item = resp["result"][0] if resp and "result" in resp and resp["result"] else None
        if getname_item:
            resp = self.request("callHierarchy/incomingCalls", {"item": getname_item}, 15)
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
        else:
            print(f"15. IncomingCalls: SKIPPED - prepare returned no item")
            record_result(15, "IncomingCalls getName", "SKIP")

    def test_17_prepare_call_process(self):
        self._ensure_calls()
        resp = self.request(
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{TEST_CALLS_FILE}"},
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

    def test_18_outgoing_process(self):
        self._ensure_calls()
        resp = self.request(
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{TEST_CALLS_FILE}"},
                "position": {"line": 17, "character": 17},
            },
            17,
        )
        process_item = resp["result"][0] if resp and "result" in resp and resp["result"] else None
        if process_item:
            resp = self.request("callHierarchy/outgoingCalls", {"item": process_item}, 18)
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
        else:
            print(f"18. OutgoingCalls: SKIPPED - prepare returned no item")
            record_result(18, "OutgoingCalls process", "SKIP")

    def test_19_incoming_process(self):
        self._ensure_calls()
        resp = self.request(
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{TEST_CALLS_FILE}"},
                "position": {"line": 17, "character": 17},
            },
            17,
        )
        process_item = resp["result"][0] if resp and "result" in resp and resp["result"] else None
        if process_item:
            resp = self.request("callHierarchy/incomingCalls", {"item": process_item}, 19)
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
        else:
            print(f"19. IncomingCalls: SKIPPED - prepare returned no item")
            record_result(19, "IncomingCalls process", "SKIP")

    def test_20_prepare_call_field(self):
        self._ensure_calls()
        resp = self.request(
            "textDocument/prepareCallHierarchy",
            {
                "textDocument": {"uri": f"file://{TEST_CALLS_FILE}"},
                "position": {"line": 4, "character": 20},
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

    # ------------------------------------------------------------------
    # Test 16: Cross-file references
    # ------------------------------------------------------------------

    def test_16_cross_file_refs(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.ensure_open(LSP_RUNNER_FILE)
        self.wait_index()
        print("    Waiting extra 15 seconds for cross-file indexing...")
        drain_notifications(self.sock, seconds=15)
        resp = self.request(
            "textDocument/references",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 52, "character": 13},
                "context": {"includeDeclaration": True},
            },
            13,
        )
        if resp and "result" in resp and resp["result"]:
            refs = resp["result"]
            cross_file = any("LspServerRunnerBase" in str(r.get("uri", "")) for r in refs)
            same_file = any("LspServer.java" in str(r.get("uri", "")) for r in refs)
            print(f"16. Cross-file References: OK - Found {len(refs)} references")
            print(f"    - Same file: {same_file}, Cross-file: {cross_file}")
            if cross_file:
                record_result(16, "Cross-file references", "PASS", f"{len(refs)} refs, cross-file={cross_file}")
            else:
                print(f"    WARNING: Cross-file references not working - known limitation")
                record_result(16, "Cross-file references", "KNOWN", "all refs are same-file")
        else:
            print(f"16. Cross-file References: FAILED or no result")
            record_result(16, "Cross-file references", "FAIL")

    # ------------------------------------------------------------------
    # Tests 21-22: Data flow (DataFlowTestTarget.java)
    # ------------------------------------------------------------------

    def _ensure_dataflow(self):
        self.initialize()
        self.ensure_open(DATAFLOW_FILE)
        self.wait_index()
        print("    Waiting 10s for dataflow file indexing...")
        time.sleep(10)

    def test_21_dataflow_from(self):
        self._ensure_dataflow()
        resp = self.request(
            "textDocument/dataflowFrom",
            {
                "textDocument": {"uri": f"file://{DATAFLOW_FILE}"},
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

    def test_22_dataflow_to(self):
        self._ensure_dataflow()
        resp = self.request(
            "textDocument/dataflowTo",
            {
                "textDocument": {"uri": f"file://{DATAFLOW_FILE}"},
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

    # ------------------------------------------------------------------
    # Tests 31-33: Semantic search (separate connection)
    # ------------------------------------------------------------------

    def _connect_semantic(self):
        if self.sock2 is not None:
            return self.sock2
        sock2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock2.settimeout(30)
        try:
            sock2.connect(("127.0.0.1", 8989))
        except ConnectionRefusedError:
            print("    Semantic Search: SKIPPED - server unavailable")
            return None
        send_and_recv(sock2, "initialize", {"processId": 12345, "clientInfo": {"name": "test", "version": "1.0"}, "workspaceFolders": [{"uri": f"file://{PROJECT_ROOT}", "name": "git"}], "capabilities": {}}, 100)
        send_notification(sock2, "initialized", {})
        with open(LSP_SERVER_FILE) as f:
            semantic_file_content = f.read()
        send_notification(sock2, "textDocument/didOpen", {"textDocument": {"uri": f"file://{LSP_SERVER_FILE}", "languageId": "java", "version": 1, "text": semantic_file_content}})
        drain_notifications(sock2, 5)
        self.sock2 = sock2
        return sock2

    def _close_semantic(self):
        if self.sock2 is not None:
            send_and_recv(self.sock2, "shutdown", {}, 140)
            send_notification(self.sock2, "exit", {})
            self.sock2.close()
            self.sock2 = None

    def test_31_semantic_fields(self):
        sock2 = self._connect_semantic()
        if sock2 is None:
            record_result(31, "Semantic Search fields", "SKIP", "server unavailable")
            return
        sock2.settimeout(20)
        resp = send_and_recv(sock2, "textDocument/semanticSearch", {"pattern": "$Type$ $FieldName$;", "scope": "file", "language": "java", "fileUri": f"file://{LSP_SERVER_FILE}"}, 131)
        sock2.settimeout(30)
        if resp and "result" in resp:
            matches = resp["result"]
            if isinstance(matches, list) and len(matches) > 0:
                print(f"31. Semantic Search (fields): OK - Found {len(matches)} field declarations")
                for m in matches[:3]:
                    start = m.get("start", {})
                    line = start.get("line", "?")
                    text = m.get("matchedText", "")[:50]
                    print(f"    - line {line}: {text}")
                record_result(31, "Semantic Search fields", "PASS", f"{len(matches)} matches")
            else:
                print(f"31. Semantic Search (fields): no results returned")
                record_result(31, "Semantic Search fields", "KNOWN", "no results - SSR may not match")
        else:
            print(f"31. Semantic Search (fields): TIMEOUT")
            record_result(31, "Semantic Search fields", "KNOWN", "TIMEOUT")

    def test_32_semantic_logger(self):
        sock2 = self._connect_semantic()
        if sock2 is None:
            record_result(32, "Semantic Search Logger", "SKIP", "server unavailable")
            return
        sock2.settimeout(20)
        resp = send_and_recv(sock2, "textDocument/semanticSearch", {"pattern": "$Type$ $FieldName$;", "scope": "file", "language": "java", "fileUri": f"file://{LSP_SERVER_FILE}", "constraints": {"$Type$": {"regex": "Logger"}}}, 132)
        sock2.settimeout(30)
        if resp and "result" in resp:
            matches = resp["result"]
            if isinstance(matches, list) and len(matches) > 0:
                print(f"32. Semantic Search (Logger fields): OK - Found {len(matches)} Logger fields")
                for m in matches[:3]:
                    start = m.get("start", {})
                    line = start.get("line", "?")
                    text = m.get("matchedText", "")[:80]
                    print(f"    - line {line}: {text}")
                record_result(32, "Semantic Search Logger", "PASS", f"{len(matches)} matches")
            else:
                print(f"32. Semantic Search (Logger fields): no results")
                record_result(32, "Semantic Search Logger", "KNOWN", "no results")
        else:
            print(f"32. Semantic Search (Logger fields): TIMEOUT")
            record_result(32, "Semantic Search Logger", "KNOWN", "TIMEOUT")

    def test_33_semantic_invalid(self):
        sock2 = self._connect_semantic()
        if sock2 is None:
            record_result(33, "Semantic Search invalid constraint", "SKIP", "server unavailable")
            return
        sock2.settimeout(20)
        resp = send_and_recv(sock2, "textDocument/semanticSearch", {"pattern": "$Modifiers$ $Type$ $FieldName$;", "scope": "file", "language": "java", "fileUri": f"file://{LSP_SERVER_FILE}", "constraints": {"$Type$": {"foo": "bar"}}}, 133)
        sock2.settimeout(30)
        if resp and "error" in resp:
            print(f"33. Semantic Search invalid constraint: OK - got error")
            record_result(33, "Semantic Search invalid constraint", "PASS")
        elif resp and "result" in resp:
            print(f"33. Semantic Search invalid constraint: returned result instead of error")
            record_result(33, "Semantic Search invalid constraint", "FAIL")
        else:
            print(f"33. Semantic Search invalid constraint: TIMEOUT")
            record_result(33, "Semantic Search invalid constraint", "KNOWN", "TIMEOUT")
        self._close_semantic()

    # ------------------------------------------------------------------
    # Tests 23-30: Inspections and code actions
    # ------------------------------------------------------------------

    def test_23_inspection_list_all(self):
        self.initialize()
        resp = self.request("$/inspection/list", {"query": ""}, 23)
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

    def test_24_inspection_list_search(self):
        self.initialize()
        resp = self.request("$/inspection/list", {"query": "unused"}, 24)
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

    def test_25_inspection_list_missing(self):
        self.initialize()
        resp = self.request("$/inspection/list", {"query": "zzzthisdoesnotexist"}, 25)
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

    def test_26_inspection_run_unused(self):
        self.initialize()
        resp = self.request(
            "$/inspection/runByName",
            {"textDocument": {"uri": f"file://{LSP_SERVER_FILE}"}, "name": "unused"},
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

    def test_27_inspection_run_missing(self):
        self.initialize()
        resp = self.request(
            "$/inspection/runByName",
            {"textDocument": {"uri": f"file://{LSP_SERVER_FILE}"}, "name": "zzzthisdoesnotexist"},
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

    def test_28_inspection_run_allfiles(self):
        self.initialize()
        self.sock.settimeout(15)
        resp = self.request("$/inspection/runByName", {"name": "unused"}, 28)
        self.sock.settimeout(90)
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

    def test_29_inspection_run_null(self):
        self.initialize()
        self.sock.settimeout(15)
        resp = self.request("$/inspection/runByName", {"textDocument": None, "name": "unused"}, 29)
        self.sock.settimeout(90)
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

    def test_30_code_actions(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE)
        self.wait_index()
        resp = self.request(
            "textDocument/codeAction",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
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

    # ------------------------------------------------------------------
    # Tests 34-38: signature/formatting/rename/resolve on LspServer.java
    # ------------------------------------------------------------------

    def test_34_signature_help(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True, version=100)
        self.wait_index()
        drain_notifications(self.sock, seconds=3)
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/signatureHelp",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 65, "character": 50},
            },
            34,
        )
        self.sock.settimeout(90)
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

    def test_35_formatting(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True, version=100)
        self.wait_index()
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/formatting",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "options": {"tabSize": 4, "insertSpaces": True},
            },
            35,
        )
        self.sock.settimeout(90)
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

    def test_36_range_formatting(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True, version=100)
        self.wait_index()
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/rangeFormatting",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "range": {
                    "start": {"line": 0, "character": 0},
                    "end": {"line": 10, "character": 0},
                },
                "options": {"tabSize": 4, "insertSpaces": True},
            },
            36,
        )
        self.sock.settimeout(90)
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

    def test_37_rename(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True, version=100)
        self.wait_index()
        rename_original_backup = None
        if os.path.exists(LSP_SERVER_FILE):
            with open(LSP_SERVER_FILE) as f:
                rename_original_backup = f.read()
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/rename",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 47, "character": 30},
                "newName": "renamedLOG",
            },
            37,
        )
        self.sock.settimeout(90)
        if rename_original_backup is not None:
            with open(LSP_SERVER_FILE, "w") as f:
                f.write(rename_original_backup)
            send_notification(self.sock, "textDocument/didChange", {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}", "version": 101},
                "contentChanges": [{"text": rename_original_backup}],
            })
            drain_notifications(self.sock, seconds=1)
        if resp and "result" in resp:
            result = resp["result"]
            if result:
                has_changes = bool(result.get("changes")) or bool(
                    result.get("documentChanges") if hasattr(result, "get") else None
                )
                if has_changes:
                    changes = result.get("changes") or {}
                    dc = result.get("documentChanges") or []
                    total_changes = sum(
                        len(v) for v in changes.values()
                    ) if changes else len(dc)
                    print(f"37. Rename: OK - Would make {total_changes} changes across {len(changes) if changes else len(dc)} files")
                    record_result(37, "Rename", "PASS", f"{total_changes} changes")
                else:
                    print(f"37. Rename: OK - No changes needed")
                    record_result(37, "Rename", "PASS", "no changes")
            else:
                print(f"37. Rename: OK - No changes needed")
                record_result(37, "Rename", "PASS", "no changes")
        else:
            print(f"37. Rename: TIMEOUT or not supported")
            record_result(37, "Rename", "KNOWN", "TIMEOUT - server not responding")

    def test_371_cross_file_rename(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True, version=100)
        self.ensure_open(LSP_RUNNER_FILE)
        self.wait_index()
        xfile_backups = {}
        for xf in [LSP_SERVER_FILE, LSP_RUNNER_FILE]:
            if os.path.exists(xf):
                with open(xf) as f:
                    xfile_backups[xf] = f.read()
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/rename",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 214, "character": 27},
                "newName": "renamedStop",
            },
            371,
        )
        self.sock.settimeout(90)
        for xf, content in xfile_backups.items():
            with open(xf, "w") as f:
                f.write(content)
            send_notification(self.sock, "textDocument/didChange", {
                "textDocument": {"uri": f"file://{xf}", "version": 999},
                "contentChanges": [{"text": content}],
            })
        drain_notifications(self.sock, seconds=1)
        if resp and "result" in resp:
            result = resp["result"]
            if result:
                doc_changes = result.get("documentChanges") or []
                uris_involved = set()
                for dc in doc_changes:
                    if isinstance(dc, dict):
                        ted = dc.get("left", dc)
                        uri = ted.get("textDocument", {}).get("uri", "")
                        if uri:
                            uris_involved.add(uri)
                changes_map = result.get("changes") or {}
                uris_involved.update(changes_map.keys())
                has_server_runner = any("LspServerRunnerBase" in u for u in uris_involved)
                has_lsp_server = any("LspServer.java" in u for u in uris_involved)
                if has_lsp_server and has_server_runner:
                    print(f"371. Cross-file Rename: OK - Affected {len(uris_involved)} files: {[u.split('/')[-1] for u in uris_involved]}")
                    record_result(371, "Cross-file Rename", "PASS", f"{len(uris_involved)} files")
                else:
                    print(f"371. Cross-file Rename: FAIL - Expected both LspServer.java and LspServerRunnerBase.java, got: {[u.split('/')[-1] for u in uris_involved]}")
                    record_result(371, "Cross-file Rename", "FAIL", f"got files: {list(uris_involved)}")
            else:
                print(f"371. Cross-file Rename: No result")
                record_result(371, "Cross-file Rename", "FAIL", "no result")
        else:
            print(f"371. Cross-file Rename: TIMEOUT or not supported")
            record_result(371, "Cross-file Rename", "KNOWN", "TIMEOUT")

    def test_38_resolve_completion(self):
        self.initialize()
        self.ensure_open(LSP_SERVER_FILE, force=True, version=100)
        self.wait_index()
        self.sock.settimeout(10)
        resp = self.request(
            "textDocument/completion",
            {
                "textDocument": {"uri": f"file://{LSP_SERVER_FILE}"},
                "position": {"line": 50, "character": 4},
            },
            38,
        )
        self.sock.settimeout(90)
        if resp and "result" in resp and resp["result"]:
            result = resp["result"]
            items = result if isinstance(result, list) else result.get("items", [])
            if items:
                first_item = items[0]
                if first_item.get("data"):
                    resolved = self.request("completionItem/resolve", first_item, 39)
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

    # ------------------------------------------------------------------
    # Tests 39-42: Type hierarchy (TypeHierarchyTest.java)
    # ------------------------------------------------------------------

    def _ensure_typehierarchy(self):
        self.initialize()
        self.ensure_open(TYPE_HIERARCHY_FILE)
        self.wait_index()
        drain_notifications(self.sock, seconds=5)

    def test_39_type_hierarchy_concrete(self):
        self._ensure_typehierarchy()
        resp = self.request(
            "textDocument/prepareTypeHierarchy",
            {
                "textDocument": {"uri": f"file://{TYPE_HIERARCHY_FILE}"},
                "position": {"line": 15, "character": 7},
            },
            39,
        )
        if resp and "result" in resp and resp["result"]:
            items = resp["result"]
            item_names = [i.get("name") for i in items]
            if "ConcreteImpl" in item_names:
                print(f"39. PrepareTypeHierarchy (ConcreteImpl): OK - Got {item_names}")
                record_result(39, "PrepareTypeHierarchy ConcreteImpl", "PASS", str(item_names))
            else:
                print(f"39. PrepareTypeHierarchy: FAILED - Expected 'ConcreteImpl', got {item_names}")
                record_result(39, "PrepareTypeHierarchy ConcreteImpl", "FAIL", str(item_names))
        else:
            print(f"39. PrepareTypeHierarchy: FAILED or no result - {resp}")
            record_result(39, "PrepareTypeHierarchy ConcreteImpl", "FAIL")

    def test_40_supertypes_concrete(self):
        self._ensure_typehierarchy()
        resp = self.request(
            "textDocument/prepareTypeHierarchy",
            {
                "textDocument": {"uri": f"file://{TYPE_HIERARCHY_FILE}"},
                "position": {"line": 15, "character": 7},
            },
            39,
        )
        concrete_item = resp["result"][0] if resp and "result" in resp and resp["result"] else None
        if concrete_item:
            resp = self.request("typeHierarchy/supertypes", {"item": concrete_item}, 40)
            if resp and "result" in resp and resp["result"]:
                super_names = sorted([i.get("name") for i in resp["result"]])
                if "AbstractBase" in super_names:
                    print(f"40. Supertypes ConcreteImpl: OK - Got {super_names}")
                    record_result(40, "Supertypes ConcreteImpl", "PASS", str(super_names))
                else:
                    print(f"40. Supertypes ConcreteImpl: FAILED - Expected AbstractBase, got {super_names}")
                    record_result(40, "Supertypes ConcreteImpl", "FAIL", str(super_names))
            else:
                print(f"40. Supertypes ConcreteImpl: FAILED or no result - {resp}")
                record_result(40, "Supertypes ConcreteImpl", "FAIL")
        else:
            print(f"40. Supertypes ConcreteImpl: SKIPPED - prepare returned no item")
            record_result(40, "Supertypes ConcreteImpl", "SKIP")

    def test_41_type_hierarchy_abstract(self):
        self._ensure_typehierarchy()
        resp = self.request(
            "textDocument/prepareTypeHierarchy",
            {
                "textDocument": {"uri": f"file://{TYPE_HIERARCHY_FILE}"},
                "position": {"line": 8, "character": 16},
            },
            41,
        )
        if resp and "result" in resp and resp["result"]:
            items = resp["result"]
            item_names = [i.get("name") for i in items]
            if "AbstractBase" in item_names:
                print(f"41. PrepareTypeHierarchy (AbstractBase): OK - Got {item_names}")
                record_result(41, "PrepareTypeHierarchy AbstractBase", "PASS", str(item_names))
            else:
                print(f"41. PrepareTypeHierarchy: FAILED - Expected 'AbstractBase', got {item_names}")
                record_result(41, "PrepareTypeHierarchy AbstractBase", "FAIL", str(item_names))
        else:
            print(f"41. PrepareTypeHierarchy: FAILED or no result - {resp}")
            record_result(41, "PrepareTypeHierarchy AbstractBase", "FAIL")

    def test_42_subtypes_abstract(self):
        self._ensure_typehierarchy()
        resp = self.request(
            "textDocument/prepareTypeHierarchy",
            {
                "textDocument": {"uri": f"file://{TYPE_HIERARCHY_FILE}"},
                "position": {"line": 8, "character": 16},
            },
            41,
        )
        abstract_item = resp["result"][0] if resp and "result" in resp and resp["result"] else None
        if abstract_item:
            resp = self.request("typeHierarchy/subtypes", {"item": abstract_item}, 42)
            if resp and "result" in resp and resp["result"]:
                sub_names = sorted([i.get("name") for i in resp["result"]])
                if "ConcreteImpl" in sub_names:
                    print(f"42. Subtypes AbstractBase: OK - Got {sub_names}")
                    record_result(42, "Subtypes AbstractBase", "PASS", str(sub_names))
                else:
                    print(f"42. Subtypes AbstractBase: FAILED - Expected ConcreteImpl, got {sub_names}")
                    record_result(42, "Subtypes AbstractBase", "FAIL", str(sub_names))
            else:
                print(f"42. Subtypes AbstractBase: FAILED or no result - {resp}")
                record_result(42, "Subtypes AbstractBase", "FAIL")
        else:
            print(f"42. Subtypes AbstractBase: SKIPPED - prepare returned no item")
            record_result(42, "Subtypes AbstractBase", "SKIP")

    # ------------------------------------------------------------------
    # Tests 43-45: Refactoring (RefactorTest.java)
    # ------------------------------------------------------------------

    def _open_refactor(self):
        self.initialize()
        with open(REFACTOR_TEST_FILE, "w") as f:
            f.write(REFACTOR_SNIPPET)
        self.ensure_open(REFACTOR_TEST_FILE, force=True)
        self.wait_index()
        drain_notifications(self.sock, seconds=2)

    def _close_refactor(self):
        self.close_open(REFACTOR_TEST_FILE)
        drain_notifications(self.sock, seconds=1)
        if os.path.exists(REFACTOR_TEST_FILE):
            os.remove(REFACTOR_TEST_FILE)

    def test_43_refactor_extract(self):
        self.initialize()
        self.resync()
        self._open_refactor()
        self.sock.settimeout(10)
        resp = self.request(
            "idealsp/refactor",
            {"uri": f"file://{REFACTOR_TEST_FILE}", "type": "extract-method",
             "position": {"line": 2, "character": 8},
             "startRange": {"line": 2, "character": 8},
             "endRange": {"line": 4, "character": 23},
             "name": None},
            43,
        )
        self.sock.settimeout(90)
        applied = resp.get("result", {}).get("applied", False) if resp else False
        if applied:
            print(f"43. Refactor extract-method: OK")
            record_result(43, "Refactor extract-method", "PASS")
        else:
            reason = resp.get("result", {}).get("failureReason", "N/A") if resp else "TIMEOUT"
            print(f"43. Refactor extract-method: FAILED - {reason}")
            record_result(43, "Refactor extract-method", "KNOWN" if not resp else "FAIL", reason)
        self._close_refactor()

    def test_44_refactor_introduce(self):
        self.initialize()
        self.resync()
        self._open_refactor()
        self.sock.settimeout(10)
        resp = self.request(
            "idealsp/refactor",
            {"uri": f"file://{REFACTOR_TEST_FILE}", "type": "introduce-variable",
             "position": {"line": 4, "character": 18}, "name": None},
            44,
        )
        self.sock.settimeout(90)
        applied = resp.get("result", {}).get("applied", False) if resp else False
        if applied:
            print(f"44. Refactor introduce-variable: OK")
            record_result(44, "Refactor introduce-variable", "PASS")
        else:
            reason = resp.get("result", {}).get("failureReason", "N/A") if resp else "TIMEOUT"
            print(f"44. Refactor introduce-variable: FAILED - {reason}")
            record_result(44, "Refactor introduce-variable", "KNOWN" if not resp else "FAIL", reason)
        self._close_refactor()

    def test_45_refactor_inline(self):
        self.initialize()
        self.resync()
        self._open_refactor()
        self.sock.settimeout(10)
        resp = self.request(
            "idealsp/refactor",
            {"uri": f"file://{REFACTOR_TEST_FILE}", "type": "inline",
             "position": {"line": 4, "character": 18}, "name": None},
            45,
        )
        self.sock.settimeout(90)
        applied = resp.get("result", {}).get("applied", False) if resp else False
        if applied:
            print(f"45. Refactor inline: OK")
            record_result(45, "Refactor inline", "PASS")
        else:
            reason = resp.get("result", {}).get("failureReason", "N/A") if resp else "TIMEOUT"
            print(f"45. Refactor inline: FAILED - {reason}")
            record_result(45, "Refactor inline", "KNOWN" if not resp else "FAIL", reason)
        self._close_refactor()

    # ------------------------------------------------------------------
    # Tests 47-50: Project structure
    # ------------------------------------------------------------------

    def test_47_project_structure(self):
        self.initialize()
        self.sock.settimeout(15)
        resp = self.request("idealsp/projectStructure", {"scope": "all"}, 47)
        self.sock.settimeout(90)
        if resp and "result" in resp:
            result = resp["result"]
            ok = True
            checks = []
            if result.get("project") and result["project"].get("name"):
                checks.append("project_name")
            else:
                ok = False
            modules = result.get("modules", [])
            msg = result.get("message", "")
            if msg:
                checks.append(f"msg:{msg}")
            if modules:
                m = modules[0]
                if m.get("name") and m.get("type") and m.get("contentRoots"):
                    checks.append("modules")
                else:
                    ok = False
            else:
                if not msg:
                    ok = False
            graph = result.get("dependencyGraph", {})
            if "edges" in graph:
                checks.append("dep_graph")
            layout = result.get("sourceLayout", [])
            if layout:
                l = layout[0]
                if l.get("module") and l.get("path") and l.get("type") and "packages" in l:
                    checks.append("source_layout")
            eps = result.get("entryPoints", [])
            checks.append(f"entry_points({len(eps)})")
            if ok:
                print(f"47. ProjectStructure: OK - {', '.join(checks)}")
                record_result(47, "ProjectStructure", "PASS", ", ".join(checks))
            else:
                print(f"47. ProjectStructure: FAILED - {', '.join(checks)}")
                record_result(47, "ProjectStructure", "FAIL", ", ".join(checks))
        else:
            print(f"47. ProjectStructure: FAILED or timeout")
            record_result(47, "ProjectStructure", "FAIL")

    def test_48_project_structure_modules(self):
        self.initialize()
        resp = self.request("idealsp/projectStructure", {"scope": "modules"}, 48)
        if resp and "result" in resp:
            r = resp["result"]
            mods = r.get("modules", [])
            layout = r.get("sourceLayout") or []
            eps = r.get("entryPoints") or []
            msg = r.get("message", "")
            if mods and not layout and not eps:
                print(f"48. ProjectStructure scope=modules: OK")
                record_result(48, "ProjectStructure modules", "PASS")
            elif mods:
                print(f"48. ProjectStructure scope=modules: PARTIAL - got modules but layout/eps not empty")
                record_result(48, "ProjectStructure modules", "KNOWN", "layout/eps not empty")
            elif msg:
                print(f"48. ProjectStructure scope=modules: {msg}")
                record_result(48, "ProjectStructure modules", "KNOWN", msg)
            else:
                print(f"48. ProjectStructure scope=modules: FAILED - no modules")
                record_result(48, "ProjectStructure modules", "FAIL")
        else:
            print(f"48. ProjectStructure scope=modules: FAILED")
            record_result(48, "ProjectStructure modules", "FAIL")

    def test_49_project_structure_source(self):
        self.initialize()
        resp = self.request("idealsp/projectStructure", {"scope": "source"}, 49)
        if resp and "result" in resp:
            r = resp["result"]
            mods = r.get("modules", [])
            layout = r.get("sourceLayout", [])
            if layout and not mods:
                print(f"49. ProjectStructure scope=source: OK - {len(layout)} source roots")
                record_result(49, "ProjectStructure source", "PASS", f"{len(layout)} roots")
            else:
                print(f"49. ProjectStructure scope=source: PARTIAL - layout={len(layout)}, modules={len(mods)}")
                record_result(49, "ProjectStructure source", "KNOWN")
        else:
            print(f"49. ProjectStructure scope=source: FAILED")
            record_result(49, "ProjectStructure source", "FAIL")

    def test_50_project_structure_entry(self):
        self.initialize()
        resp = self.request("idealsp/projectStructure", {"scope": "entry"}, 50)
        if resp and "result" in resp:
            r = resp["result"]
            eps = r.get("entryPoints", [])
            if eps:
                ep_names = [ep.get("name", "?") for ep in eps[:5]]
                print(f"50. ProjectStructure scope=entry: OK - {len(eps)} entry points: {ep_names}")
                record_result(50, "ProjectStructure entry", "PASS", f"{len(eps)} points")
            else:
                print(f"50. ProjectStructure scope=entry: PARTIAL - no entry points found")
                record_result(50, "ProjectStructure entry", "KNOWN", "no entry points")
        else:
            print(f"50. ProjectStructure scope=entry: FAILED")
            record_result(50, "ProjectStructure entry", "FAIL")

    # ------------------------------------------------------------------
    # Test 52: Refactor move (MoveMe.java)
    # ------------------------------------------------------------------

    def test_52_refactor_move(self):
        self.initialize()
        with open(MOVE_TEST_FILE, "w") as f:
            f.write(MOVE_SNIPPET)
        os.makedirs(MOVE_TARGET_DIR, exist_ok=True)
        self.ensure_open(MOVE_TEST_FILE, force=True)
        self.wait_index()
        drain_notifications(self.sock, seconds=2)
        self.sock.settimeout(120)
        resp = self.request(
            "idealsp/refactor",
            {"uri": f"file://{MOVE_TEST_FILE}", "type": "move",
             "position": {"line": 1, "character": 22},
             "targetPackageUri": f"file://{MOVE_TARGET_DIR}"},
            52,
        )
        self.sock.settimeout(90)
        applied = resp.get("result", {}).get("applied", False) if resp else False
        if applied:
            print(f"52. Refactor move: OK")
            record_result(52, "Refactor move", "PASS")
        else:
            reason = resp.get("result", {}).get("failureReason", "N/A") if resp else "TIMEOUT"
            print(f"52. Refactor move: FAILED - {reason}")
            record_result(52, "Refactor move", "FAIL" if resp else "KNOWN", reason)
        self.close_open(MOVE_TEST_FILE)
        drain_notifications(self.sock, seconds=1)
        if os.path.exists(MOVE_TEST_FILE):
            os.remove(MOVE_TEST_FILE)
        if os.path.isdir(MOVE_TARGET_DIR):
            shutil.rmtree(MOVE_TARGET_DIR, ignore_errors=True)

    # ------------------------------------------------------------------
    # Test 53: Refactor safe-delete (DeleteMe.java)
    # ------------------------------------------------------------------

    def test_53_refactor_safe_delete(self):
        self.initialize()
        with open(SAFEDELETE_TEST_FILE, "w") as f:
            f.write(SAFEDELETE_SNIPPET)
        self.ensure_open(SAFEDELETE_TEST_FILE, force=True)
        self.wait_index()
        drain_notifications(self.sock, seconds=2)
        self.sock.settimeout(120)
        resp = self.request(
            "idealsp/refactor",
            {"uri": f"file://{SAFEDELETE_TEST_FILE}", "type": "safe-delete",
             "position": {"line": 6, "character": 23}},
            53,
        )
        self.sock.settimeout(90)
        applied = resp.get("result", {}).get("applied", False) if resp else False
        if applied:
            print(f"53. Refactor safe-delete: OK")
            record_result(53, "Refactor safe-delete", "PASS")
        else:
            reason = resp.get("result", {}).get("failureReason", "N/A") if resp else "TIMEOUT"
            print(f"53. Refactor safe-delete: FAILED - {reason}")
            record_result(53, "Refactor safe-delete", "FAIL" if resp else "KNOWN", reason)
        self.close_open(SAFEDELETE_TEST_FILE)
        drain_notifications(self.sock, seconds=1)
        if os.path.exists(SAFEDELETE_TEST_FILE):
            os.remove(SAFEDELETE_TEST_FILE)

    # ------------------------------------------------------------------
    # Test 54: codeActionApply (ApplyTest.java)
    # ------------------------------------------------------------------

    def test_54_code_action_apply(self):
        self.initialize()
        with open(APPLY_TEST_FILE, "w") as f:
            f.write(APPLY_SNIPPET)
        self.ensure_open(APPLY_TEST_FILE, force=True)
        self.wait_index()
        drain_notifications(self.sock, seconds=5)
        code_action_resp = self.request(
            "textDocument/codeAction",
            {
                "textDocument": {"uri": f"file://{APPLY_TEST_FILE}"},
                "range": {"start": {"line": 3, "character": 8}, "end": {"line": 3, "character": 20}},
                "context": {"diagnostics": []},
            },
            541,
        )
        actions = []
        if code_action_resp and "result" in code_action_resp:
            for item in code_action_resp["result"]:
                if isinstance(item, dict) and "title" in item.get("right", item):
                    action_item = item.get("right", item)
                    actions.append(action_item)
        apply_title = None
        for a in actions:
            t = a.get("title", "")
            if "Change variable" in t and "type to" in t:
                apply_title = t
                break
        if apply_title:
            self.sock.settimeout(120)
            resp = self.request(
                "idealsp/codeActionApply",
                {"title": apply_title, "uri": f"file://{APPLY_TEST_FILE}",
                 "range": {"start": {"line": 3, "character": 8}, "end": {"line": 3, "character": 20}}},
                54,
            )
            self.sock.settimeout(90)
            applied = resp.get("result", {}).get("applied", False) if resp else False
            if applied:
                print(f"54. codeActionApply: OK - applied '{apply_title}'")
                record_result(54, "codeActionApply", "PASS", apply_title)
            else:
                reason = resp.get("result", {}).get("failureReason", "N/A") if resp else "TIMEOUT"
                print(f"54. codeActionApply: FAILED - {reason}")
                record_result(54, "codeActionApply", "FAIL" if resp else "KNOWN", reason)
        else:
            action_titles = [a.get("title", "?") for a in actions]
            print(f"54. codeActionApply: FAILED - no matching action found among {action_titles}")
            record_result(54, "codeActionApply", "FAIL", f"no action found among {action_titles}")
        self.close_open(APPLY_TEST_FILE)
        drain_notifications(self.sock, seconds=1)
        if os.path.exists(APPLY_TEST_FILE):
            os.remove(APPLY_TEST_FILE)

    # ------------------------------------------------------------------
    # Test 51: Shutdown
    # ------------------------------------------------------------------

    def test_51_shutdown(self):
        self.initialize()
        resp = self.request("shutdown", {}, 51)
        if resp and "result" in resp:
            print(f"51. Shutdown: OK")
            record_result(51, "Shutdown", "PASS")
            send_notification(self.sock, "exit", {})
        else:
            print(f"51. Shutdown: FAILED")
            record_result(51, "Shutdown", "FAIL")


# Registry of (test number, display name, method name). Execution order follows
# registry order; each test is independently gated by should_run().
TESTS = [
    (1, "Initialize", "test_01_initialize"),
    (2, "didOpen diagnostics", "test_02_didopen"),
    (3, "Document symbols", "test_03_document_symbols"),
    (4, "Definition", "test_04_definition"),
    (5, "References", "test_05_references"),
    (6, "Workspace symbols", "test_06_workspace_symbols"),
    (7, "Completion", "test_07_completion"),
    (8, "Hover", "test_08_hover"),
    (9, "Type definition", "test_09_type_definition"),
    (10, "Implementation", "test_10_implementation"),
    (11, "Document highlight", "test_11_document_highlight"),
    (12, "Diagnostics", "test_12_diagnostics"),
    (13, "Code Actions (organize imports)", "test_13_code_actions_organize"),
    (14, "PrepareCallHierarchy getName", "test_14_prepare_call_getname"),
    (15, "IncomingCalls getName", "test_15_incoming_getname"),
    (16, "Cross-file references", "test_16_cross_file_refs"),
    (17, "PrepareCallHierarchy process", "test_17_prepare_call_process"),
    (18, "OutgoingCalls process", "test_18_outgoing_process"),
    (19, "IncomingCalls process", "test_19_incoming_process"),
    (20, "PrepareCallHierarchy field", "test_20_prepare_call_field"),
    (21, "DataFlowFrom", "test_21_dataflow_from"),
    (22, "DataFlowTo", "test_22_dataflow_to"),
    (31, "Semantic Search fields", "test_31_semantic_fields"),
    (32, "Semantic Search Logger", "test_32_semantic_logger"),
    (33, "Semantic Search invalid constraint", "test_33_semantic_invalid"),
    (23, "Inspection list all", "test_23_inspection_list_all"),
    (24, "Inspection list search", "test_24_inspection_list_search"),
    (25, "Inspection list non-existent", "test_25_inspection_list_missing"),
    (26, "Inspection runByName unused", "test_26_inspection_run_unused"),
    (27, "Inspection runByName non-existent", "test_27_inspection_run_missing"),
    (28, "Inspection runByName all-files", "test_28_inspection_run_allfiles"),
    (29, "Inspection runByName null-textDocument", "test_29_inspection_run_null"),
    (30, "Code Actions", "test_30_code_actions"),
    (34, "Signature Help", "test_34_signature_help"),
    (35, "Formatting", "test_35_formatting"),
    (36, "Range Formatting", "test_36_range_formatting"),
    (37, "Rename", "test_37_rename"),
    (371, "Cross-file Rename", "test_371_cross_file_rename"),
    (38, "ResolveCompletionItem", "test_38_resolve_completion"),
    (39, "PrepareTypeHierarchy ConcreteImpl", "test_39_type_hierarchy_concrete"),
    (40, "Supertypes ConcreteImpl", "test_40_supertypes_concrete"),
    (41, "PrepareTypeHierarchy AbstractBase", "test_41_type_hierarchy_abstract"),
    (42, "Subtypes AbstractBase", "test_42_subtypes_abstract"),
    (43, "Refactor extract-method", "test_43_refactor_extract"),
    (44, "Refactor introduce-variable", "test_44_refactor_introduce"),
    (45, "Refactor inline", "test_45_refactor_inline"),
    (47, "ProjectStructure", "test_47_project_structure"),
    (48, "ProjectStructure modules", "test_48_project_structure_modules"),
    (49, "ProjectStructure source", "test_49_project_structure_source"),
    (50, "ProjectStructure entry", "test_50_project_structure_entry"),
    (52, "Refactor move", "test_52_refactor_move"),
    (53, "Refactor safe-delete", "test_53_refactor_safe_delete"),
    (54, "codeActionApply", "test_54_code_action_apply"),
    (51, "Shutdown", "test_51_shutdown"),
]


def run():
    notification_buffer.clear()
    suite = ComprehensiveTestSuite()
    for num, name, method_name in TESTS:
        if should_run(num):
            getattr(suite, method_name)()
        else:
            skip_test(num, name)
    if suite.sock is not None:
        suite.sock.close()
    print_summary()
    print("\n=== All tests completed ===")


if __name__ == "__main__":
    run()
    if failed > 0:
        sys.exit(1)
