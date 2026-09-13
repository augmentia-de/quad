#!/bin/bash

# ResearchAgent Runner
# Runs the QUAD ResearchAgent for web search and file reading

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
EXAMPLES_DIR="$SCRIPT_DIR"

# Default values
WORKSPACE="${QUAD_WORKSPACE:-work/quad}"
TOPIC="Spring boot"
FILEPATH="docs/test.txt"

usage() {
    echo "Usage: $0 [OPTIONS] <topic> <filePath>"
    echo ""
    echo "Arguments:"
    echo "  topic      Web search query"
    echo "  filePath   File path within workspace (relative to session)"
    echo ""
    echo "Options:"
    echo "  -w, --workspace PATH   Workspace base directory (default: /work/quad)"
    echo "  -h, --help             Show this help message"
    echo "  -q, --quiet            Suppress Maven output"
    echo ""
    echo "Environment Variables:"
    echo "  QUAD_WORKSPACE         Workspace base directory"
    echo "  QUAD_TOOLS_WEBSEARCH_API_URL   Search API endpoint"
    echo ""
    echo "Examples:"
    echo "  $0 \"Java programming\" docs/guide.txt"
    echo "  $0 -w /tmp/quad \"Spring Boot\" articles/spring.txt"
    echo "  $0 \"React hooks\" /tmp/quad/workspace/react-docs.md"
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
                QUIET="-q"
                shift
                ;;
            -*)
                echo "Unknown option: $1" >&2
                exit 1
                ;;
            *)
                if [ -z "$TOPIC" ]; then
                    TOPIC="$1"
                elif [ -z "$FILEPATH" ]; then
                    FILEPATH="$1"
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
    echo "Building project..."
    cd "$PROJECT_ROOT"
    mvn install -DskipTests $QUIET
    if [ $? -ne 0 ]; then
        echo "Error: Build failed" >&2
        exit 1
    fi
}

run() {
    if [ -z "$TOPIC" ] || [ -z "$FILEPATH" ]; then
        echo "Error: Both topic and filePath are required" >&2
        usage
    fi

    cd "$EXAMPLES_DIR"

    # Build classpath
    CLASSPATH="$EXAMPLES_DIR/target/classes"
    DEP_CP=$(mvn dependency:build-classpath -DincludeScope=runtime -q -Dmdep.outputFile=/dev/stdout)

    exec java -Dquad.workspace="$WORKSPACE" \
        -cp "$CLASSPATH:$DEP_CP" \
        de.augmentia.quad.examples.ResearchAgentMain \
        "$TOPIC" "$FILEPATH"
}

main() {
    parse_args "$@"
    check_prerequisites
    build
    run
}

main "$@"