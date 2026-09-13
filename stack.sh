#!/bin/bash
# stack.sh — orchestration script for quad docker stacks
# All stacks share the llm-net network.
#
# Usage:
#   ./stack.sh <command> [stack] [--profile <dev|prod|cloud>]
#
# Commands:
#   up   [stack]  — start stack (default: infra)
#   down [stack]  — stop stack
#   ps   [stack]  — Status anzeigen
#   logs [stack]  — Logs anzeigen
#   all          — Alle Stacks starten
#   stop-all     — Alle Stacks stoppen
#   status       — Status aller Stacks anzeigen
#
# Stacks: infra, obs, keycloak, mcp, quarkus, frontend
# Optionale Stacks: tools, obs-ui (nicht bei "all")
# Profiles: dev (Default), prod, cloud

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$SCRIPT_DIR/deploy"

# Profile support
ACTIVE_PROFILE="dev"

# Argumente parsen
POSITIONAL_ARGS=()
while [[ $# -gt 0 ]]; do
    case $1 in
        --profile)
            ACTIVE_PROFILE="$2"
            shift 2
            ;;
        *)
            POSITIONAL_ARGS+=("$1")
            shift
            ;;
    esac
done

# Profil-Validierung
if [[ ! "$ACTIVE_PROFILE" =~ ^(dev|prod|cloud)$ ]]; then
    echo "Invalid profile: $ACTIVE_PROFILE"
    echo "Available profiles: dev, prod, cloud"
    exit 1
fi

# Profil-Env-Datei laden
load_profile_env() {
    local env_file="$DEPLOY_DIR/.env.$ACTIVE_PROFILE"
    if [[ -f "$env_file" ]]; then
        echo "▶ Lade Profil: $ACTIVE_PROFILE ($env_file)"
        set -a
        source "$env_file"
        set +a
    else
        echo "⚠ Profil-Datei nicht gefunden: $env_file"
        echo "  Verwende Defaults"
    fi
}

# ── Netzwerk anlegen ──────────────────────────────────────────
ensure_network() {
    docker network create llm-net 2>/dev/null || true
}

# ── Stack-Dateien ─────────────────────────────────────────────
declare -A STACK_FILES=(
    [infra]="01-infrastructure.yml"
    [obs]="02-observability.yml"
    [keycloak]="03-identity-keycloak.yml"
    [mcp]="04-mcp-servers.yml"
    [quarkus]="05-app-quarkus.yml"
    [frontend]="06-frontend-react.yml"
    [tools]="07-tools.yml"
    [obs-ui]="08-observability-ui.yml"
)

# ── Order for all/stop-all ──────────────────────────────
# Optional (tools, obs-ui) NICHT bei "all" — nur explizit starten.
STACK_ORDER=("infra" "obs" "keycloak" "mcp" "quarkus" "frontend")

compose_cmd() {
    local stack=$1
    shift
    local file="${STACK_FILES[$stack]:-}"
    if [[ -z "$file" ]]; then
        echo "Unknown stack: $stack"
        echo "Available Stacks: ${!STACK_FILES[*]}"
        exit 1
    fi
    docker compose -f "$DEPLOY_DIR/$file" "$@"
}

# ── Commands ──────────────────────────────────────────────────
cmd_up() {
    local stack=${1:-infra}
    load_profile_env
    ensure_network
    echo ""
    echo "════════════════════════════════════════════════"
    echo "▶ Starte Stack: $stack (Profil: $ACTIVE_PROFILE)"
    echo "════════════════════════════════════════════════"
    compose_cmd "$stack" up -d
    echo ""
    echo "✓ Stack $stack gestartet (Profil: $ACTIVE_PROFILE)"
    compose_cmd "$stack" ps
}

cmd_down() {
    local stack=${1:-}
    if [[ -z "$stack" ]]; then
        echo "Stoppe alle Stacks..."
        for s in "${STACK_ORDER[@]}"; do
            if [[ -f "$DEPLOY_DIR/${STACK_FILES[$s]}" ]]; then
                echo "▶ Stoppe: $s"
                compose_cmd "$s" down --remove-orphans 2>/dev/null || true
            fi
        done
        echo "✓ Alle Stacks gestoppt"
    else
        echo "▶ Stoppe Stack: $stack"
        compose_cmd "$stack" down --remove-orphans
        echo "✓ Stack $stack gestoppt"
    fi
}

cmd_ps() {
    local stack=${1:-}
    if [[ -z "$stack" ]]; then
        for s in "${STACK_ORDER[@]}"; do
            if [[ -f "$DEPLOY_DIR/${STACK_FILES[$s]}" ]]; then
                echo ""
                echo "═══ $s ═══"
                compose_cmd "$s" ps 2>/dev/null || true
            fi
        done
    else
        compose_cmd "$stack" ps
    fi
}

cmd_logs() {
    local stack=${1:-}
    if [[ -z "$stack" ]]; then
        echo "Which stack? Available: ${!STACK_FILES[*]}"
        exit 1
    fi
    compose_cmd "$stack" logs -f --tail=50
}

cmd_all() {
    load_profile_env
    ensure_network
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "▶ Starte ALLE Stacks (Profil: $ACTIVE_PROFILE)"
    echo "════════════════════════════════════════════════════════"
    for s in "${STACK_ORDER[@]}"; do
        if [[ -f "$DEPLOY_DIR/${STACK_FILES[$s]}" ]]; then
            echo ""
            echo "──────────────────────────────────────────────"
            echo "▶ Starte: $s"
            echo "──────────────────────────────────────────────"
            compose_cmd "$s" up -d
        fi
    done
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "✓ Alle Stacks gestartet (Profil: $ACTIVE_PROFILE)"
    echo "════════════════════════════════════════════════════════"
    cmd_ps
}

cmd_status() {
    echo ""
    echo "╔══════════════════════════════════════════════════╗"
    echo "║         quad Docker Infrastructure Status        ║"
    echo "╠══════════════════════════════════════════════════╣"
    echo "║                                                  ║"
    echo "║  Netzwerk: llm-net                               ║"
    printf "║  Status:   "; docker network inspect llm-net --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null | xargs -I{} echo "{}║"
    echo "║                                                  ║"
    echo "╠══════════════════════════════════════════════════╣"

    for s in "${STACK_ORDER[@]}"; do
        if [[ -f "$DEPLOY_DIR/${STACK_FILES[$s]}" ]]; then
            local running=$(compose_cmd "$s" ps --format "{{.State}}" 2>/dev/null | grep -c "running" || echo 0)
            local total=$(compose_cmd "$s" ps --format "{{.Name}}" 2>/dev/null | wc -l | tr -d ' ')
            if [[ "$running" -gt 0 ]]; then
                printf "║  %-12s ● %d/%d running                     ║\n" "$s" "$running" "$total"
            else
                printf "║  %-12s ○ stopped                            ║\n" "$s"
            fi
        fi
    done

    echo "╠══════════════════════════════════════════════════╣"
    echo "║  Endpoints:                                      ║"
    echo "║    Postgres:     localhost:5432                   ║"
    echo "║    Redis:        localhost:6379                   ║"
    echo "║    Kafka:        localhost:9092                   ║"
    echo "║    OTel Collect: localhost:4317 (gRPC)           ║"
    echo "║    OTel Collect: localhost:4318 (HTTP)           ║"
    echo "║    Prometheus:   localhost:9090                  ║"
    echo "║    Tempo:        localhost:3200                  ║"
    echo "║    Grafana:      localhost:3001                  ║"
    echo "║    Langfuse:     localhost:5001                  ║"
    echo "║    Keycloak:     localhost:8180                   ║"
    echo "║    Spring AI:    localhost:8080                  ║"
    echo "║    Quarkus:      localhost:8086                  ║"
    echo "║    Frontend:     localhost:80                    ║"
    echo "║    ── Tools (optional) ──                        ║"
    echo "║    RedpandaCons: localhost:8082                   ║"
    echo "║    Adminer:      localhost:8083                   ║"
    echo "║    RedisInsight: localhost:8084                   ║"
    echo "║    ── Obs-UI (optional) ──                       ║"
    echo "║    Loki:         localhost:3100                   ║"
    echo "║    (Grafana/Prometheus via Layer 2: :3001/:9090)  ║"
    echo "╚══════════════════════════════════════════════════╝"
}

# ── Hauptprogramm ─────────────────────────────────────────────
usage() {
    cat <<EOF
quad Docker Stack Manager

Nutzung: ./stack.sh <command> [stack] [--profile <dev|prod|cloud>]

Commands:
  up   [stack]     Stack starten (Default: infra)
  down [stack|all] Stack stoppen (kein Argument = alle)
  ps   [stack|all] Status anzeigen (kein Argument = alle)
  logs <stack>     Logs anzeigen
  all              Alle Stacks starten
  stop-all         Alle Stacks stoppen
  status           Status + Endpoints anzeigen

Profiles:
  --profile dev    Entwicklung (Default): H2, Dummy-Mode, Debug-Logging
  --profile prod   Docker/VM: PostgreSQL, OTel, RESTRICTED
  --profile cloud  GCP: Cloud SQL Proxy, Secret Manager

Stacks:
  infra      PostgreSQL + Redis + Kafka
  obs        OTel Collector + Prometheus + Tempo + Grafana + Langfuse
  keycloak   Keycloak OIDC Identity Provider (Realm quarkus + lab)
  mcp        MCP Tool Server (filesystem, memory, git, fetch)
  quarkus    Quarkus Backend (depends on infra + mcp, see stack order)
  frontend   React Web UI

Options (nicht bei "all", einzeln deploybar):
  tools      Redpanda Console + Adminer + RedisInsight
  obs-ui     Loki + Promtail (Logs); anzeigen via Layer-2-Grafana (obs)

Beispiele:
  ./stack.sh up infra                              # Dev-Profile (Default)
  ./stack.sh up keycloak                           # Identity-Layer
  ./stack.sh up tools                              # Debug-Helper
  ./stack.sh up obs-ui                             # Loki-Logs (optional)
  ./stack.sh up infra --profile prod               # Prod-Profile
  ./stack.sh all --profile dev                     # Alles im Dev-Modus
  ./stack.sh all --profile prod                    # Alles im Prod-Modus
  ./stack.sh status                                # Status anzeigen
  ./stack.sh down all                              # Alles stoppen
EOF
}

case "${POSITIONAL_ARGS[0]:-}" in
    up)       cmd_up "${POSITIONAL_ARGS[1]:-}" ;;
    down)     cmd_down "${POSITIONAL_ARGS[1]:-}" ;;
    ps)       cmd_ps "${POSITIONAL_ARGS[1]:-}" ;;
    logs)     cmd_logs "${POSITIONAL_ARGS[1]:-}" ;;
    all)      cmd_all ;;
    stop-all) cmd_down ;;
    status)   cmd_status ;;
    help|-h|--help) usage ;;
    *)        usage; exit 1 ;;
esac
