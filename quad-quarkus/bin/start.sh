#!/bin/bash

# QUAD Quarkus Dev Mode Launcher
# Starts the quad-quarkus application in dev mode with live coding

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

source "$SCRIPT_DIR/../../set_keys.sh"

PORT=8082
WORKSPACE="/work/quad"
QUIET=""

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -p, --port PORT          HTTP port (default: 8082)"
    echo "  -w, --workspace PATH     Workspace base directory (default: /work/quad)"
    echo "  -q, --quiet              Suppress verbose output"
    echo "  -h, --help               Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  QUAD_WORKSPACE                   Workspace base directory"
    echo "  OPENAI_API_KEY                   OpenAI API key for LLM calls"
    echo "  OPENAI_BASE_URL                  OpenAI-compatible API base URL"
    echo "  OPENAI_MODEL                     Model name (default: gpt-4o-mini)"
    echo ""
    echo "Examples:"
    echo "  $0"
    echo "  $0 -p 9090"
    echo "  $0 -w /tmp/quad"
    exit 0
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -p|--port)
                PORT="$2"
                shift 2
                ;;
            -w|--workspace)
                WORKSPACE="$2"
                shift 2
                ;;
            -q|--quiet)
                QUIET="true"
                shift
                ;;
            -h|--help)
                usage
                ;;
            *)
                echo "Error: Unknown option: $1" >&2
                usage
                ;;
        esac
    done
}

check_prerequisites() {
    if ! command -v mvn &> /dev/null; then
        echo "Error: Maven (mvn) is required but not found" >&2
        exit 1
    fi
}

start() {
    cd "$PROJECT_ROOT"

    if [ "$QUIET" != "true" ]; then
        echo ""
        echo "=== QUAD Quarkus Dev Mode ==="
        echo "Port: $PORT"
        echo "Workspace: $WORKSPACE"
        echo "Profile: dev (live coding enabled)"
        echo ""
    fi

    mvn quarkus:dev \
        -Dquarkus.http.port="$PORT" \
        -Dquad.workspace="$WORKSPACE" \
        -Dquad.agent.workspace="$WORKSPACE"
}

main() {
    parse_args "$@"
    check_prerequisites
    start
}

main "$@"
