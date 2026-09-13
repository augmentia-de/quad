#!/bin/bash
# dev-infra.sh — Manage quad dev infrastructure (Docker Compose)
#
# Usage:
#   ./scripts/dev-infra.sh up       # Start OTel Collector + Tempo + Grafana + Prometheus
#   ./scripts/dev-infra.sh down     # Stop all services
#   ./scripts/dev-infra.sh status   # Show container status
#   ./scripts/dev-infra.sh logs     # Tail logs (Ctrl+C to stop)
#   ./scripts/dev-infra.sh restart  # Restart all services
#   ./scripts/dev-infra.sh health   # Check health endpoints
#
# Services:
#   OTel Collector  → localhost:4317 (gRPC), :4318 (HTTP), :8888 (metrics)
#   Tempo           → localhost:3200 (traces)
#   Grafana         → localhost:3010 (admin/admin)
#   Prometheus      → localhost:9090 (metrics)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$PROJECT_DIR/deploy/02-observability.yml"

# ── Helpers ────────────────────────────────────────────────────
log()  { echo -e "\033[1;34m▶ $1\033[0m"; }
ok()   { echo -e "\033[1;32m✓ $1\033[0m"; }
warn() { echo -e "\033[1;33m⚠ $1\033[0m"; }
err()  { echo -e "\033[1;31m✗ $1\033[0m"; exit 1; }

dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

# ── Commands ───────────────────────────────────────────────────
cmd_up() {
    log "Starting dev infrastructure..."
    dc up -d
    echo ""
    ok "Services started:"
    echo "  OTel Collector  → localhost:4317 (gRPC) / :4318 (HTTP) / :8888 (metrics)"
    echo "  Tempo           → localhost:3200"
    echo "  Grafana         → localhost:3010 (admin/admin)"
    echo "  Prometheus      → localhost:9090"
    echo ""
    log "Check status: ./scripts/dev-infra.sh status"
}

cmd_down() {
    log "Stopping dev infrastructure..."
    dc down
    ok "All services stopped"
}

cmd_status() {
    log "Dev infrastructure status:"
    echo ""
    dc ps
    echo ""
    cmd_health
}

cmd_logs() {
    dc logs -f --tail=50
}

cmd_restart() {
    log "Restarting dev infrastructure..."
    dc restart
    ok "All services restarted"
}

cmd_health() {
    log "Health checks:"
    echo ""

    local ok_count=0
    local total=4

    # OTel Collector (gRPC)
    if ss -tlnp 2>/dev/null | grep -q ':4317 ' ; then
        echo "  OTel Collector :4317  ✓ listening"
        ok_count=$((ok_count + 1))
    else
        echo "  OTel Collector :4317  ✗ not listening"
    fi

    # Tempo
    if curl -sf http://localhost:3200/ready >/dev/null 2>&1; then
        echo "  Tempo          :3200  ✓ ready"
        ok_count=$((ok_count + 1))
    else
        echo "  Tempo          :3200  ✗ not ready"
    fi

    # Grafana
    if curl -sf http://localhost:3010/api/health >/dev/null 2>&1; then
        echo "  Grafana        :3010  ✓ healthy"
        ok_count=$((ok_count + 1))
    else
        echo "  Grafana        :3010  ✗ not healthy"
    fi

    # Prometheus
    if curl -sf http://localhost:9090/-/healthy >/dev/null 2>&1; then
        echo "  Prometheus     :9090  ✓ healthy"
        ok_count=$((ok_count + 1))
    else
        echo "  Prometheus     :9090  ✗ not healthy"
    fi

    echo ""
    ok "$ok_count/$total services healthy"
}

# ── Usage ──────────────────────────────────────────────────────
usage() {
    cat <<EOF
quad Dev Infrastructure Manager

Usage: ./scripts/dev-infra.sh <command>

Commands:
  up       Start OTel Collector + Tempo + Grafana + Prometheus
  down     Stop all services
  status   Show container status + health checks
  logs     Tail logs (Ctrl+C to stop)
  restart  Restart all services
  health   Check health endpoints

Services:
  OTel Collector  → localhost:4317 (gRPC), :4318 (HTTP), :8888 (metrics)
  Tempo           → localhost:3200
  Grafana         → localhost:3010 (admin/admin)
  Prometheus      → localhost:9090

EOF
}

# ── Main ───────────────────────────────────────────────────────
case "${1:-help}" in
    up)       cmd_up ;;
    down)     cmd_down ;;
    status)   cmd_status ;;
    logs)     cmd_logs ;;
    restart)  cmd_restart ;;
    health)   cmd_health ;;
    help|-h|--help) usage ;;
    *)        err "Unknown command: $1  (use: up|down|status|logs|restart|health)" ;;
esac
