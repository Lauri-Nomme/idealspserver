#!/bin/bash
# Install IdeaLSP plugin and restart LSP server

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
unzip -o "$PLUGIN_DIR/build/distributions/idealsp-1.0-SNAPSHOT.zip" -d /tmp/
cp /tmp/idealsp/lib/* "$PLUGIN_PATH/"

echo "Restarting service..."
systemctl --user daemon-reload
systemctl --user restart idealsp

echo "Waiting for LSP server..."
for i in {1..30}; do
    if python3 -c "import socket; s=socket.socket(); s.connect(('127.0.0.1', 8989)); s.close()" 2>/dev/null; then
        echo "LSP server is running on port 8989"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: LSP server not responding after 30 seconds"
        exit 1
    fi
    sleep 1
done
