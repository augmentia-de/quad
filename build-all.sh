#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== QUAD Studio Full Build ==="
echo ""

"$SCRIPT_DIR/build-backend.sh"

echo ""
echo "=== Build Complete ==="