#!/bin/bash
# Install IdeaLS plugin and restart LSP server

set -e

PLUGIN_DIR="/vokk/home/lauri/dev/idealspserver/git/server"
IDE_VERSION="2026.1"
IDE_DIR="/data/idea/idea-IU-261.22158.277"
PLUGIN_PATH="$HOME/.local/share/JetBrains/IntelliJIdea$IDE_VERSION/plugins/lib"

echo "Building plugin..."
cd "$PLUGIN_DIR"
source ~/.sdkman/bin/sdkman-init.sh
gradle buildPlugin --no-daemon -q

echo "Installing plugin..."
rm -rf "$PLUGIN_PATH"/*
unzip -o "$PLUGIN_DIR/build/distributions/server-1.0-SNAPSHOT.zip" -d /tmp/
cp /tmp/server/lib/* "$PLUGIN_PATH/"

echo "Restarting service..."
systemctl --user daemon-reload
systemctl --user restart ideals-lsp

echo "Waiting for LSP server..."
sleep 15

if python3 -c "import socket; s=socket.socket(); s.connect(('127.0.0.1', 8989))" 2>/dev/null; then
    echo "LSP server is running on port 8989"
else
    echo "ERROR: LSP server not responding"
    exit 1
fi
