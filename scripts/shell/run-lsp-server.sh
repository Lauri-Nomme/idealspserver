#!/bin/bash
#
# IdeaLS LSP Server - Headless Runner
#
# Usage:
#   ./run-lsp-server.sh          # Run in production mode
#   ./run-lsp-server.sh --debug # Run with strace debug output
#

set -e

IDEA_DIR="/data/idea/idea-IU-261.22158.277"
IDEA_JDK="$IDEA_DIR/jbr"
LSP_PORT="${LSP_PORT:-8989}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if IDEA is installed
if [ ! -d "$IDEA_DIR" ]; then
    echo -e "${RED}Error: IDEA not found at $IDEA_DIR${NC}"
    echo "Please update IDEA_DIR in this script or install IDEA Community 2025.3.4+"
    exit 1
fi

# Check if JAVA_HOME/JDK is set
if [ ! -d "$IDEA_JDK" ]; then
    echo -e "${RED}Error: JDK not found at $IDEA_JDK${NC}"
    echo "Please update IDEA_JDK in this script"
    exit 1
fi

# Setup environment
export IDEA_JDK="$IDEA_JDK"
export JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

# Check plugin is installed
PLUGIN_DIR="$HOME/.local/share/JetBrains/IdeaIC2025.3/plugins/lib"
if [ ! -f "$PLUGIN_DIR/server-1.0-SNAPSHOT.jar" ]; then
    echo -e "${YELLOW}Warning: Plugin not found at $PLUGIN_DIR${NC}"
    echo "Installing plugin..."
    
    mkdir -p "$PLUGIN_DIR"
    
    # Copy from build output
    if [ -f "/vokk/home/lauri/dev/idealspserver/git/server/build/distributions/server-1.0-SNAPSHOT.zip" ]; then
        cd /tmp
        rm -rf server-1.0-SNAPSHOT
        unzip -q /vokk/home/lauri/dev/idealspserver/git/server/build/distributions/server-1.0-SNAPSHOT.zip
        cp -r server/* "$PLUGIN_DIR/"
        echo -e "${GREEN}Plugin installed${NC}"
    else
        echo -e "${RED}Error: Plugin build not found at /vokk/home/lauri/dev/idealspserver/git/server/build/distributions/${NC}"
        echo "Please build the plugin first: cd server && ./gradlew buildPlugin"
        exit 1
    fi
fi

# Parse arguments
DEBUG=false
ARGS=()

while [[ $# -gt 0 ]]; do
    case $1 in
        --debug)
            DEBUG=true
            shift
            ;;
        --port)
            LSP_PORT="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--debug] [--port PORT]"
            echo ""
            echo "Options:"
            echo "  --debug     Run with strace to debug file access"
            echo "  --port PORT Set the LSP server port (default: 8989)"
            echo "  -h, --help  Show this help message"
            exit 0
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

cd "$IDEA_DIR"

echo -e "${GREEN}Starting IdeaLS LSP server on port $LSP_PORT...${NC}"
echo "IDEA directory: $IDEA_DIR"
echo "JDK: $IDEA_JDK"

if [ "$DEBUG" = true ]; then
    echo -e "${YELLOW}Running in DEBUG mode with strace${NC}"
    strace -f -e open,openat,access -o /tmp/idea_strace.log ./bin/idea lsp-server tcp "$LSP_PORT"
else
    ./bin/idea lsp-server tcp "$LSP_PORT"
fi