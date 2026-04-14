# IdeaLS LSP Server - Headless Mode

Run the IdeaLS plugin as a Language Server Protocol (LSP) server without needing a full IDE GUI.

## Prerequisites

- IntelliJ IDEA Community 2025.3.4+ installed at `/data/idea/idea-IC-253.32098.37`
- IdeaLS plugin built and available

## Setup

1. Install the plugin (if not already installed):

```bash
cd /data/idea/idea-IC-253.32098.37
mkdir -p ~/.local/share/JetBrains/IdeaIC2025.3/plugins/lib

# Copy the plugin JAR and dependencies
cp -r /vokk/home/lauri/dev/idealspserver/git/server/build/distributions/server-1.0-SNAPSHOT.zip /tmp/
cd /tmp
unzip -q server-1.0-SNAPSHOT.zip
cp -r server/* ~/.local/share/JetBrains/IdeaIC2025.3/plugins/lib/
```

2. Set environment variables:

```bash
export IDEA_JDK=/data/idea/idea-IC-253.32098.37/jbr
export JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
```

## Running

### Production Mode

```bash
cd /data/idea/idea-IC-253.32098.37
./bin/idea lsp-server tcp 8989
```

The LSP server will start on `127.0.0.1:8989`.

### Debug Mode (with strace)

```bash
cd /data/idea/idea-IC-253.32098.37
strace -f -e open,openat,access -o /tmp/idea_strace.log ./bin/idea lsp-server tcp 8989
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
- If the plugin isn't loaded, check the log at `~/.cache/JetBrains/IdeaIC2025.3/log/idea.log` for "Loaded custom plugins: IdeaLS"
- Make sure the plugin is in `~/.local/share/JetBrains/IdeaIC2025.3/plugins/lib/` not the IDE's plugins folder