#!/bin/bash

# AI News Aggregator Runner
# Runs the QUAD AINewsAgent for AI news search via WebSearchTool

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default values
QUERY="latest artificial intelligence breakthroughs 2026"
LIMIT=5
QUIET=""
WORKSPACE="/work/quad"

usage() {
    echo "Usage: $0 [OPTIONS] [query] [limit]"
    echo ""
    echo "Arguments:"
    echo "  query            Search query string (default: 'latest artificial intelligence breakthroughs 2026')"
    echo "  limit            Number of results (default: 5)"
    echo ""
    echo "Options:"
    echo "  -w, --workspace PATH   Workspace base directory (default: /work/quad)"
    echo "  -h, --help             Show this help message"
    echo "  -q, --quiet            Suppress verbose output"
    echo ""
    echo "Environment Variables:"
    echo "  QUAD_WORKSPACE                   Workspace base directory"
    echo "  OPENAI_API_KEY                   API key for web search"
    echo ""
    echo "Set API keys first:"
    echo "  source set_keys.sh"
    echo ""
    echo "Examples:"
    echo "  $0"
    echo "  $0 \"AI news\" 10"
    echo "  $0 -w /tmp/quad \"OpenAI 2026\" 3"
    exit 0
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -w|--workspace)
                WORKSPACE="$2"
                shift 2
                ;;
            -h|--help)
                usage
                ;;
            -q|--quiet)
                QUIET="true"
                shift
                ;;
            *)
                if [ -z "$QUERY_SET" ]; then
                    QUERY="$1"
                    QUERY_SET="1"
                elif [ -z "$LIMIT_SET" ]; then
                    LIMIT="$1"
                    LIMIT_SET="1"
                fi
                shift
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

build() {
    if [ "$QUIET" != "true" ]; then
        echo "Building project..."
    fi
    cd "$PROJECT_ROOT"
    mvn compile -DskipTests -q 2>&1
    if [ $? -ne 0 ]; then
        echo "Error: Build failed" >&2
        exit 1
    fi
}

run() {
    cd "$PROJECT_ROOT"

    DEP_CP=$(mvn dependency:build-classpath -DincludeScope=compile -q -Dmdep.outputFile=/dev/stdout 2>/dev/null)

    if [ "$QUIET" != "true" ]; then
        echo ""
        echo "=== AI News Aggregator ==="
        echo "Query: $QUERY"
        echo "Limit: $LIMIT"
        echo ""
    fi

    java -Dquad.workspace="$WORKSPACE" \
        -cp "quad-core/target/classes:quad-quarkus/target/classes:$DEP_CP" \
        de.augmentia.quad.quarkus.main.AINewsRunner \
        "$QUERY" "$LIMIT"
}

main() {
    parse_args "$@"
    check_prerequisites
    build
    run
}

main "$@"