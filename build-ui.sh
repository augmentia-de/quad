#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building QUAD UI..."
cd "$SCRIPT_DIR/quad-ui"

if [ ! -d "node_modules" ]; then
    echo "Installing dependencies..."
    npm install
fi

npm run build

echo "UI build complete to $SCRIPT_DIR/quad-ui/dist"