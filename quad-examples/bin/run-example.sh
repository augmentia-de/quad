#!/bin/bash
# Run Two-Stage Workflow Example
# Usage: ./run-example.sh [requirements]

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXAMPLES_DIR="$PROJECT_ROOT/"

# Build quad-core first
echo "Building quad-core..."
cd "$EXAMPLES_DIR"
mvn compile -q

# Create a minimal runtime environment
echo "Creating runtime..."
java -cp "quad-core/target/classes:$EXAMPLES_DIR/target/classes" \
    -Dquarkus.dev-services.enabled=false \
    -Dmanagement.enabled=false \
    de.augmentia.quad.examples.TwoStageWorkflowMain "$@"