#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PORT="${QUAD_PORT:-8086}"
MODE="${QUAD_MODE:-dev}"  # dev | prod
WORKSPACE="${QUAD_WORKSPACE:-${SCRIPT_DIR}/workspace/shared}"

echo "Starting QUAD backend (Quarkus) in $MODE mode on port $PORT..."
echo "Workspace: $WORKSPACE"

if [ -f "$SCRIPT_DIR/.env" ]; then
    echo "Loading environment from .env..."
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi

source "$SCRIPT_DIR/set_keys.sh"

# ── DEV MODE: Hot Reload mit quarkus:dev ──
if [ "$MODE" = "dev" ]; then
    echo ""
    echo "╔═══════════════════════════════════════════════╗"
    echo "║       QUAD Backend Development Mode          ║"
    echo "╠═══════════════════════════════════════════════╣"
    echo "║  Port:     $PORT                             ║"
    echo "║  Debug:    $(QUAD_DEBUG=true)                ║"
    echo "║  Workspace: $WORKSPACE                      ║"
    echo "╚═══════════════════════════════════════════════╝"
    echo ""

    if [ "${QUAD_DEBUG:-false}" = "true" ]; then
        echo "⚡ Debug enabled on port 5005..."
    fi

    # Create workspace dir if missing
    mkdir -p "$WORKSPACE"

    cd "$SCRIPT_DIR/quad-quarkus"
    mvn clean quarkus:dev \
        -Dquarkus.http.port="$PORT" \
        -Dquad.workspace="$WORKSPACE" \
        -Dquad.agent.workspace="$WORKSPACE" \
        -Dquad.workspace.granted-dirs="${QUAD_GRANTED_DIRS:-}" \
        -DskipTests
    exit 0
fi

# ── PROD MODE: Runner Jar starten ──
if [ "$MODE" = "prod" ]; then
    cd "$SCRIPT_DIR/../quad/quad-quarkus/target"

    RUNNER_JAR="quad-quarkus-1.0.0-SNAPSHOT-runner.jar"

    if [ ! -f "$RUNNER_JAR" ]; then
        echo "Building production JAR first..."
        cd "$SCRIPT_DIR/../quad"
        mvn -f quad-quarkus/pom.xml quarkus:build -DskipTests -q -Dquarkus.package.type=uber-jar
        cd "$SCRIPT_DIR/../quad/quad-quarkus/target"
    fi

    BUILD_ARGS=""
    if [ "${QUAD_DEBUG:-true}" = "true" ]; then
        echo "Debug mode enabled on port 5005..."
        BUILD_ARGS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    fi

    java $BUILD_ARGS \
        -Dquad.workspace="$WORKSPACE" \
        -Dquad.agent.workspace="$WORKSPACE" \
        -Dquad.workspace.granted-dirs="${QUAD_GRANTED_DIRS:-}" \
        -jar "$RUNNER_JAR"
    exit 0
fi

echo "ERROR: Unknown mode '$MODE'. Use 'dev' or 'prod'."
exit 1