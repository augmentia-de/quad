#!/bin/bash
# start.sh — starts/stops docker stacks for a profile
#
# Nutzung:
#   ./scripts/start.sh up              # Dev-Stack starten
#   ./scripts/start.sh up --profile prod  # Prod-Stack starten
#   ./scripts/start.sh down            # Alle stoppen
#   ./scripts/start.sh ps              # Status anzeigen
#   ./scripts/start.sh logs quarkus    # Logs anzeigen
#
# Profile:
#   dev    (Default) H2, Dummy-Mode, Debug-Logging
#   prod   PostgreSQL, OTel
#   cloud  SQLite, kein OTel, minimal
#
# Stacks:
#   infra, obs, mcp, quarkus, frontend, minimal

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$PROJECT_DIR/deploy"

# ── Profil ────────────────────────────────────────────────────
ACTIVE_PROFILE="dev"
COMMAND=""
STACK=""

# Argumente parsen
POSITIONAL=()
while [[ $# -gt 0 ]]; do
    case $1 in
        --profile) ACTIVE_PROFILE="$2"; shift 2 ;;
        *)         POSITIONAL+=("$1"); shift ;;
    esac
done

COMMAND="${POSITIONAL[0]:-help}"
STACK="${POSITIONAL[1]:-}"

# ── Validierung ────────────────────────────────────────────────
if [[ ! "$ACTIVE_PROFILE" =~ ^(dev|prod|cloud)$ ]]; then
    echo "Invalid profile: $ACTIVE_PROFILE"
    echo "Available profiles: dev, prod, cloud"
    exit 1
fi

# ── Hilfsfunktionen ───────────────────────────────────────────
log() { echo -e "\033[1;34m▶ $1\033[0m"; }
ok()  { echo -e "\033[1;32m✓ $1\033[0m"; }
err() { echo -e "\033[1;31m✗ $1\033[0m"; exit 1; }

# ── Env laden ──────────────────────────────────────────────────
load_env() {
    local env_file="$DEPLOY_DIR/.env.$ACTIVE_PROFILE"
    if [[ -f "$env_file" ]]; then
        log "Lade Env: $env_file"
        set -a
        source "$env_file"
        set +a
    fi

    # Secrets laden (falls vorhanden)
    local secrets_file="$DEPLOY_DIR/.env.secrets.$ACTIVE_PROFILE"
    if [[ -f "$secrets_file" ]]; then
        log "Lade Secrets: $secrets_file"
        set -a
        source "$secrets_file"
        set +a
    fi
}

# ── Netzwerk ───────────────────────────────────────────────────
ensure_network() {
    docker network create llm-net 2>/dev/null || true
}

# ── Compose Command ───────────────────────────────────────────
declare -A STACK_FILES=()
for f in "$DEPLOY_DIR"/*.yml; do
    local_name=$(basename "$f" .yml)
    case "$local_name" in
        01-infrastructure)   STACK_FILES[infra]="$f" ;;
        02-observability)    STACK_FILES[obs]="$f" ;;
        03-identity-keycloak) STACK_FILES[keycloak]="$f" ;;
        04-mcp-servers)      STACK_FILES[mcp]="$f" ;;
        05-app-quarkus)      STACK_FILES[quarkus]="$f" ;;
        05-app-minimal)      STACK_FILES[minimal]="$f" ;;
        06-frontend-react)   STACK_FILES[frontend]="$f" ;;
    esac
done

compose_cmd() {
    local stack=$1
    shift
    local file="${STACK_FILES[$stack]:-}"
    if [[ -z "$file" ]]; then
        err "Unknown stack: $stack (Available: ${!STACK_FILES[*]})"
    fi
    docker compose -f "$file" "$@"
}

validate_stack() {
    local stack=$1

    if [[ -z "${STACK_FILES[$stack]:-}" ]]; then
        err "Unknown stack: $stack (Available: ${!STACK_FILES[*]})"
    fi
}

# ── Commands ──────────────────────────────────────────────────
cmd_up() {
    local stack=${1:-}
    load_env
    ensure_network

    echo ""
    log "════════════════════════════════════════════════════════"
    log "  Starte Stack(s) — Profil: $ACTIVE_PROFILE"
    log "════════════════════════════════════════════════════════"

    if [[ -n "$stack" ]]; then
        validate_stack "$stack"
        compose_cmd "$stack" up -d
        ok "Stack $stack gestartet"
        compose_cmd "$stack" ps
    else
        for s in infra obs mcp quarkus frontend; do
            if [[ -n "${STACK_FILES[$s]:-}" ]]; then
                log "→ Starte: $s"
                compose_cmd "$s" up -d
            fi
        done
        ok "Alle Stacks gestartet"
        cmd_ps
    fi
}

cmd_down() {
    local stack=${1:-}
    load_env

    echo ""
    log "Stoppe Stack(s)..."

    if [[ -n "$stack" ]]; then
        validate_stack "$stack"
        compose_cmd "$stack" down --remove-orphans
        ok "Stack $stack gestoppt"
    else
        for s in frontend quarkus mcp obs infra; do
            if [[ -n "${STACK_FILES[$s]:-}" ]]; then
                compose_cmd "$s" down --remove-orphans 2>/dev/null || true
            fi
        done
        ok "Alle Stacks gestoppt"
    fi
}

cmd_ps() {
    load_env
    echo ""
    log "═══ Status ($ACTIVE_PROFILE) ═══"
    for s in infra obs mcp quarkus frontend; do
        if [[ -n "${STACK_FILES[$s]:-}" ]]; then
            echo ""
            echo "── $s ──"
            compose_cmd "$s" ps 2>/dev/null || true
        fi
    done
}

cmd_logs() {
    local stack=${1:-}
    if [[ -z "$stack" ]]; then
        err "Which stack? Available: ${!STACK_FILES[*]}"
    fi
    validate_stack "$stack"
    load_env
    compose_cmd "$stack" logs -f --tail=100
}

# ── Usage ──────────────────────────────────────────────────────
usage() {
    cat <<EOF
quad Start Script

Nutzung: ./scripts/start.sh <command> [stack] [--profile <dev|prod|cloud>]

Commands:
  up   [stack]     Stack(s) starten
  down [stack]     Stack(s) stoppen
  ps               Status anzeigen
  logs <stack>     Logs anzeigen

Profiles:
  --profile dev    Entwicklung (Default)
  --profile prod   Docker/VM
  --profile cloud  GCP Cloud Run / GKE

Stacks:
  infra      PostgreSQL + Redis + Kafka
  obs        OTel Collector + Prometheus + Tempo + Grafana
  mcp        MCP Tool Server
  quarkus    Quarkus Backend (full)
  minimal    Quarkus Backend (nativ, SQLite) + Frontend
  frontend   React Web UI

Beispiele:
  ./scripts/start.sh up                              # Dev starten
  ./scripts/start.sh up --profile prod               # Prod starten
  ./scripts/start.sh up quarkus                      # Nur Quarkus starten
  ./scripts/start.sh up minimal                      # Minimal-Stack starten
  ./scripts/start.sh down                            # Alles stoppen
  ./scripts/start.sh ps                              # Status
  ./scripts/start.sh logs quarkus                    # Quarkus-Logs
EOF
}

# ── Hauptprogramm ─────────────────────────────────────────────
case "$COMMAND" in
    up)    cmd_up "$STACK" ;;
    down)  cmd_down "$STACK" ;;
    ps)    cmd_ps ;;
    logs)  cmd_logs "$STACK" ;;
    help|-h|--help) usage ;;
    *)     usage; exit 1 ;;
esac
