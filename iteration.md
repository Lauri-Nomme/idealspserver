# Developing and Testing IdeaLS

## Install Plugin After Code Changes

### Quick Rebuild and Install

```bash
cd /vokk/home/lauri/dev/idealspserver/git

# Run the install script - builds and restarts the LSP service
bash scripts/shell/install-plugin.sh
```

The install script:
1. Compiles the server code with gradle
2. Packages the JAR
3. Copies to IntelliJ sandbox plugins directory
4. Restarts the systemd service (`ideals-lsp.service`)

### Manual Restart (if needed)

```bash
# Restart the service
systemctl --user restart idealsp.service

# Check status
systemctl --user status idealsp.service
```

### Check Logs

```bash
# View recent logs
journalctl --user -u idealsp.service -n 30 --no-pager

# Follow logs in real-time
journalctl --user -u idealsp.service -f
```

## Run Comprehensive LSP Tests

### Prerequisites

Ensure the LSP server is running:
```bash
# Check if service is running
systemctl --user status idealsp.service

# Or check via netstat
ss -tlnp | grep 8989
```

### Run Tests

```bash
cd /vokk/home/lauri/dev/idealspserver/git
python3 scripts/test_lsp_comprehensive.py
```

### Test Output (April 2026)

```
Connected to LSP server

1. Initialize: OK
2. Opened test file, waiting for indexing...
3. Document symbols: OK - Found 1 symbols
4. Definition: OK - Found 1 location(s)
5. References: OK - Found 4+ references
6. Workspace symbols: OK - Found 43 symbols
7. Completion: OK - Found 43 completions
8. Hover: OK
9. Type definition: OK - Found 1 location(s)
10. Implementation: OK - Found 1 location(s)
11. Document highlight: OK - Found 7 highlights
12. Diagnostics: OK - Found 3 diagnostics
13. Code Actions: OK
14. Cross-file References: OK - Found 5+ references

=== All tests completed ===
```

### Test Timeout

The test waits 10 seconds for indexing after opening the file:
```python
time.sleep(10)
```

If tests fail unexpectedly, increase this delay.

## Quick Iteration Cycle

```bash
# 1. Make code changes in editor

# 2. Rebuild and install
cd /vokk/home/lauri/dev/idealspserver/git
bash scripts/shell/install-plugin.sh

# 3. Wait for service to start (about 5 seconds)
# The script already waits

# 4. Run tests
python3 scripts/test_lsp_comprehensive.py
```

## Other Useful Commands

### Check Service Status

```bash
systemctl --user status idealsp.service
```

### Stop Service

```bash
systemctl --user stop idealsp.service
```

### Service Logs (filtered)

```bash
# Show only errors
journalctl --user -u idealsp.service --no-pager | grep -i error

# Show warnings
journalctl --user -u idealsp.service --no-pager | grep -i warn
```

### Test Without Rebuild

If you only need to restart the service (no code changes):

```bash
systemctl --user restart idealsp.service
sleep 5
python3 scripts/test_lsp_comprehensive.py
```

## Troubleshooting

### Service Won't Start

```bash
# Check for errors
journalctl --user -u idealsp.service -n 50 --no-pager
```

### Port Already in Use

```bash
# Kill existing process
fuser -k 8989/tcp

# Restart service
systemctl --user restart idealsp.service
```

### Build Failures

```bash
# Clean build
cd /vokk/home/lauri/dev/idealspserver/git/server
./gradlew clean jar --no-daemon 2>&1 | tail -30
```

## Test File Location

The comprehensive test uses:
- Project path: `/vokk/home/lauri/dev/idealspserver/git/server/src/main/java`
- Test file: `org/rri/ideals/server/LspServer.java`

To change the test file:
```python
# In test_lsp_comprehensive.py
PROJECT_PATH = "/path/to/your/project"
test_file = f"{PROJECT_PATH}/path/to/File.java"
```

## Run Unit Tests

```bash
cd /vokk/home/lauri/dev/idealspserver/git/server

# Run all tests
./gradlew test --no-daemon

# Run specific test class
./gradlew test --tests "tf.locals.idealsp.server.lsp.ReferencesTest" --no-daemon

# Run specific test method
./gradlew test --tests "tf.locals.idealsp.server.lsp.ReferencesTest.testReferences" --no-daemon
```

## Logging Guidelines

- **ALWAYS use `LOG.warn()` for logging** - never `LOG.info()`. This ensures logs are visible in production.
- Add logging when debugging to track code flow and identify issues
- Check logs with: `journalctl --user -u idealsp.service --no-pager | grep -iE '(error|warn)'`