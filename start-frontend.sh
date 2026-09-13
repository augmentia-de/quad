#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Starting QUAD UI in development mode..."
cd "$SCRIPT_DIR/quad-ui"
npm run dev