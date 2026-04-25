# IdeaLS LSP Server - Headless Mode

Run the IdeaLS plugin as a Language Server Protocol (LSP) server without needing a full IDE GUI.

## Prerequisites

- IntelliJ IDEA Community 2026.1+ installed
- IdeaLS plugin built and available

## Setup

1. Install the plugin (if not already installed):

```bash
# Replace IDEA_PATH with your IntelliJ installation path
IDEA_PATH=/path/to/idea-IC-261.xxxxx.xx
IDEA_CONFIG_DIR=~/.local/share/JetBrains/IdeaIC2026.1

mkdir -p $IDEA_CONFIG_DIR/plugins/lib

# Copy the plugin JAR and dependencies
cd /vokk/home/lauri/dev/idealspserver/git
./gradlew clean build -x test

# Copy built plugin to IDEA plugins directory
cp -r server/build/plugins/lib/* $IDEA_CONFIG_DIR/plugins/lib/
```

2. Set environment variables:

```bash
export IDEA_JDK=$IDEA_PATH/jbr
export JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
```

## Running

### Production Mode

```bash
cd $IDEA_PATH
./bin/idea lsp-server tcp 8989
```

The LSP server will start on `127.0.0.1:8989`.

### Using systemd Service

```bash
# Start service
systemctl --user start ideals-lsp.service

# Check status
systemctl --user status ideals-lsp.service

# View logs
journalctl --user -u ideals-lsp.service -f
```

## Using the Script

Run the provided script:

```bash
./run-lsp-server.sh
```

Or with debug mode:

```bash
./run-lsp-server.sh --debug
```

## Verifying

Check that the server is listening:

```bash
ss -tlnp | grep 8989
```

Expected output:
```
LISTEN 0 50 [::ffff:127.0.0.1]:8989 *:* users:(("idea",pid=...,fd=...))
```

## Troubleshooting

- If you get "Application cannot start in a headless mode", make sure `JAVA_TOOL_OPTIONS` includes `-Djava.awt.headless=true`
- If the plugin isn't loaded, check the log at `~/.cache/JetBrains/IdeaIC2026.1/log/idea.log` for "Loaded custom plugins: IdeaLS"
- Make sure the plugin is in `~/.local/share/JetBrains/IdeaIC2026.1/plugins/lib/` not the IDE's plugins folder
- Check service logs: `journalctl --user -u ideals-lsp.service -n 50 --no-pager`