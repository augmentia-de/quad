#!/bin/bash
# build.sh — builds all docker images for all profiles
#
# Usage:
#   ./scripts/build.sh              # build all profiles
#   ./scripts/build.sh --profile dev  # dev image only
#   ./scripts/build.sh --profile cloud --native  # native cloud image
#
# Builds:
#   1. Maven build (quad-core + quad-quarkus)
#   2. Docker images (quarkus, frontend, mcp)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$PROJECT_DIR/deploy"

# Profile
BUILD_PROFILE=""
BUILD_NATIVE=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --profile) BUILD_PROFILE="$2"; shift 2 ;;
        --native)  BUILD_NATIVE=true; shift ;;
        *) shift ;;
    esac
done

# Helper functions
log() { echo -e "\033[1;34m▶ $1\033[0m"; }
ok()  { echo -e "\033[1;32m✓ $1\033[0m"; }
err() { echo -e "\033[1;31m✗ $1\033[0m"; exit 1; }

# Maven build
build_maven() {
    local profile=${1:-dev}
    log "Maven build (profile: $profile)"
    
    cd "$PROJECT_DIR"
    
    case "$profile" in
        dev)
            mvn clean package -DskipTests -Dquarkus.profile=dev
            ;;
        prod)
            mvn clean package -DskipTests -Dquarkus.profile=prod
            ;;
        cloud)
            if [[ "$BUILD_NATIVE" == true ]]; then
                mvn clean package -Pnative -DskipTests -Dquarkus.profile=cloud -pl quad-quarkus -am
            else
                mvn clean package -DskipTests -Dquarkus.profile=cloud
            fi
            ;;
    esac
    
    ok "Maven build complete"
}

# ── Docker Images ─────────────────────────────────────────────
build_docker() {
    local profile=${1:-dev}
    log "Docker Build (Profil: $profile)"
    
    cd "$PROJECT_DIR"
    
    # Netzwerk anlegen
    docker network create llm-net 2>/dev/null || true
    
    # Quarkus Image
    local dockerfile="Dockerfile.jvm"
    [[ "$BUILD_NATIVE" == true ]] && dockerfile="Dockerfile.native"
    log "  → Building quad-quarkus ($profile, $dockerfile)..."
    docker build \
        -f "quad-quarkus/src/main/docker/$dockerfile" \
        -t "quad-quarkus:$profile" \
        .
    
    # Frontend Image (context = Repo-Root, siehe frontend.Dockerfile)
    if [[ -d "quad-ui" && -f "quad-ui/docker/frontend.Dockerfile" ]]; then
        log "  → Building quad-frontend ($profile)..."
        docker build \
            -f quad-ui/docker/frontend.Dockerfile \
            -t "quad-frontend:$profile" \
            .
    fi
    
    # MCP Image (falls Dockerfile existiert)
    if [[ -d "mcp" && -f "mcp/Dockerfile" ]]; then
        log "  → Building quad-mcp ($profile)..."
        docker build \
            -t "quad-mcp:$profile" \
            mcp/
    fi
    
    ok "Docker Images gebaut ($profile)"
    docker images | grep "quad-"
}

# ── Hauptprogramm ─────────────────────────────────────────────
main() {
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  quad Build Script"
    echo "════════════════════════════════════════════════════════"
    echo ""
    
    if [[ -n "$BUILD_PROFILE" ]]; then
        # Einzelnes Profil
        if [[ ! "$BUILD_PROFILE" =~ ^(dev|prod|cloud)$ ]]; then
            err "Invalid profile: $BUILD_PROFILE (dev|prod|cloud)"
        fi
        build_maven "$BUILD_PROFILE"
        build_docker "$BUILD_PROFILE"
    else
        # Alle Profile
        for profile in dev prod cloud; do
            echo ""
            log "═══ Building Profile: $profile ═══"
            build_maven "$profile"
            build_docker "$profile"
        done
    fi
    
    echo ""
    ok "Build abgeschlossen!"
    echo ""
    echo "Next steps:"
    echo "  ./scripts/start.sh --profile dev    # Lokal starten"
    echo "  ./scripts/deploy.sh --profile cloud # In Cloud deployen"
    echo ""
}

main
