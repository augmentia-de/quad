#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Building QUAD Backend ==="

# First build UI
echo "Building UI first..."
"$SCRIPT_DIR/build-ui.sh"

# Then build Quarkus backend
echo ""
echo "Building Quarkus backend..."
cd "$SCRIPT_DIR/quad-quarkus"

# Use the correct option for Quarkus 3.x
mvn quarkus:build -DskipTests -q -Dquarkus.package.type=uber-jar

echo ""
echo "Backend build complete"
echo "Runner jar: $SCRIPT_DIR/quad-quarkus/target/quad-quarkus-*-runner.jar"