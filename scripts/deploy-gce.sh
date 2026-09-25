#!/bin/bash
# ============================================================
# deploy-gce.sh — quad-quarkus + Frontend auf GCE e2-micro VM
# ------------------------------------------------------------
# Deploys das QUAD-Backend als NATIVE Binary (ohne Docker auf
# der VM) + React-Frontend via Nginx auf eine e2-micro-Instanz.
#
# Ablauf:
#   1. Native-Binary lokal bauen (Mandrel via Quarkus Container-Build,
#      braucht Docker lokal, NICHT auf der VM)
#   2. Frontend bauen (quad-ui/dist)
#   3. e2-micro-Instanz anlegen (Deployment, global)
#   4. Binary + Frontend + systemd-Unit + Nginx-Conf hochladen
#   5. systemd quad-backend starten, Nginx einrichten (Firewall tcp:80)
#
# Nutzung:
#   ./scripts/deploy-gce.sh --project my-proj          # bauen + deployen
#   ./scripts/deploy-gce.sh --skip-build               # nur deployen (Binary existiert)
#   ./scripts/deploy-gce.sh --instance quad e2micro    # Name übersteuern
#
# Voraussetzungen: gcloud (authentifiziert), docker (nativer Build),
#   OPENAI_API_KEY in deploy/.env.secrets.cloud (optional, sonst dummy)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GCE_DIR="$PROJECT_DIR/deploy/gce"
DIST_DIR="$PROJECT_DIR/quad-ui/dist"
BINARY="$PROJECT_DIR/quad-quarkus/target/quad-quarkus-1.0.0-SNAPSHOT-runner"

# Defaults
PROJECT_ID="${GCP_PROJECT_ID:-}"
ZONE="${GCE_ZONE:-europe-west1-b}"
INSTANCE_NAME="${GCE_INSTANCE:-quad-e2micro}"
MACHINE_TYPE="e2-micro"
IMAGE_FAMILY="debian-12"
IMAGE_PROJECT="debian-cloud"
SKIP_BUILD=false
VM_USER="${VM_USER:-$(whoami)}"

log() { echo -e "\033[1;34m▶ $1\033[0m"; }
ok()  { echo -e "\033[1;32m✓ $1\033[0m"; }
warn(){ echo -e "\033[1;33m⚠ $1\033[0m"; }
err() { echo -e "\033[1;31m✗ $1\033[0m"; exit 1; }

usage() {
    cat <<'EOF'
quad GCE e2-micro Deploy (nativ, ohne Docker auf der VM)

Usage: ./scripts/deploy-gce.sh [options]

Options:
  --project ID      GCP project id (oder GCP_PROJECT_ID)
  --zone ZONE       GCE zone (default: europe-west1-b)
  --instance NAME   Instance name (default: quad-e2micro)
  --skip-build      Don't build, deploy existing binary
  --help            Show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --project)  PROJECT_ID="$2"; shift 2 ;;
        --zone)     ZONE="$2"; shift 2 ;;
        --instance) INSTANCE_NAME="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --help|-h)  usage; exit 0 ;;
        *)          shift ;;
    esac
done

# ── Prüfungen ─────────────────────────────────────────────────
check_prereqs() {
    command -v gcloud >/dev/null 2>&1 || err "gcloud CLI not installed"
    if [[ -z "$PROJECT_ID" ]]; then
        PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
        [[ -z "$PROJECT_ID" ]] && err "GCP project not set. Use --project or GCP_PROJECT_ID"
    fi
    if [[ "$SKIP_BUILD" != "true" ]]; then
        command -v docker >/dev/null 2>&1 || err "Docker needed for native container-build (not required on the VM)"
    fi
    # Secrets laden (OpenAI-Key optional)
    local secrets="$PROJECT_DIR/deploy/.env.secrets.cloud"
    if [[ -f "$secrets" ]]; then
        set -a; source "$secrets"; set +a
        log "Secrets geladen aus deploy/.env.secrets.cloud"
    else
        warn "Kein .env.secrets.cloud — startet in dummy/OpenAI-Lesen-Modus"
    fi
    # Repo-Konvention: Secrets als CLOUD_*, Deploy braucht OPENAI_*
    # (.env.cloud wird hier absichtlich nicht gesourct — dessen ${CLOUD_*:…}
    # Expansion liefe vor dem Secrets-Load und ergäbe den Fallback).
    OPENAI_API_KEY="${CLOUD_OPENAI_API_KEY:-${OPENAI_API_KEY:-}}"
    OPENAI_BASE_URL="${CLOUD_OPENAI_BASE_URL:-${OPENAI_BASE_URL:-}}"
    OPENAI_MODEL="${CLOUD_OPENAI_MODEL:-${OPENAI_MODEL:-}}"
}

# ── 1. Build ──────────────────────────────────────────────────
build_backend() {
    [[ "$SKIP_BUILD" == "true" ]] && { warn "Skip build (--skip-build)"; return 0; }
    log "Maven native build (Mandrel via Container)..."
    cd "$PROJECT_DIR"
    mvn clean package -Dnative -DskipTests \
        -Dquarkus.native.container-build=true \
        -Dquarkus.profile=cloud \
        -pl quad-quarkus -am
    [[ -x "$BINARY" ]] || err "Native Binary nicht gefunden: $BINARY"
    ok "Native Binary: $BINARY"
}

build_frontend() {
    [[ "$SKIP_BUILD" == "true" ]] && { warn "Skip build (--skip-build)"; return 0; }
    log "Frontend build (npm run build)..."
    (cd "$PROJECT_DIR/quad-ui" && npm run build)
    [[ -f "$DIST_DIR/index.html" ]] || err "Frontend build nicht gefunden: $DIST_DIR/index.html"
    ok "Frontend dist: $DIST_DIR"
}

# ── 3. VM anlegen ─────────────────────────────────────────────
create_vm() {
    log "Prüfe ob Instanz $INSTANCE_NAME existiert..."
    if gcloud compute instances describe "$INSTANCE_NAME" --project="$PROJECT_ID" --zone="$ZONE" >/dev/null 2>&1; then
        warn "Instanz existiert bereits — überspringe Erzeugung"
        return 0
    fi
    log "Erzeuge e2-micro Instanz $INSTANCE_NAME ($ZONE)..."
    gcloud compute instances create "$INSTANCE_NAME" \
        --project="$PROJECT_ID" \
        --zone="$ZONE" \
        --machine-type="$MACHINE_TYPE" \
        --image-family="$IMAGE_FAMILY" \
        --image-project="$IMAGE_PROJECT" \
        --tags=http-server \
        --scopes=default
    ok "Instanz erzeugt"
}

# ── 4. Hochladen (gcloud compute scp/ssh) ─────────────────────
upload_and_configure() {
    local VM="$VM_USER@$INSTANCE_NAME"

    log "Verzeichnisse auf der VM anlegen..."
    gcloud compute ssh "$VM" --project="$PROJECT_ID" --zone="$ZONE" --command="
        sudo mkdir -p /opt/quad/frontend /opt/quad/data /etc/quad &&
        sudo useradd -r -m -d /opt/quad quad 2>/dev/null || true &&
        sudo chown -R quad:quad /opt/quad &&
        sudo rm -rf /tmp/quad-frontend && mkdir -p /tmp/quad-frontend"

    log "Upload Native Binary + Frontend + Assets..."
    gcloud compute scp "$BINARY" "$VM:/tmp/quad-quarkus-1.0.0-SNAPSHOT-runner" \
        --project="$PROJECT_ID" --zone="$ZONE" >/dev/null
    gcloud compute scp --recurse "$DIST_DIR/." "$VM:/tmp/quad-frontend/" \
        --project="$PROJECT_ID" --zone="$ZONE" >/dev/null

    log "Upload systemd-Unit, Nginx-Conf, Env..."
    gcloud compute scp "$GCE_DIR/quad-backend.service" "$VM:/tmp/quad-backend.service" \
        --project="$PROJECT_ID" --zone="$ZONE" >/dev/null
    gcloud compute scp "$GCE_DIR/nginx-vm.conf" "$VM:/tmp/nginx-vm.conf" \
        --project="$PROJECT_ID" --zone="$ZONE" >/dev/null

    # Env-Datei mit OpenAI-Key (optional)
    local env_file="/tmp/quad.env"
    : > "$env_file"
    [[ -n "${OPENAI_API_KEY:-}" ]] && printf 'OPENAI_API_KEY=%s\n' "$OPENAI_API_KEY" >> "$env_file"
    [[ -n "${OPENAI_BASE_URL:-}" ]] && printf 'OPENAI_BASE_URL=%s\n' "$OPENAI_BASE_URL" >> "$env_file"
    [[ -n "${OPENAI_MODEL:-}" ]] && printf 'OPENAI_MODEL=%s\n' "$OPENAI_MODEL" >> "$env_file"
    gcloud compute scp "$env_file" "$VM:/tmp/quad.env" \
        --project="$PROJECT_ID" --zone="$ZONE" >/dev/null
    rm -f "$env_file"

    log "VM-Konfiguration (systemd + Nginx)..."
    gcloud compute ssh "$VM" --project="$PROJECT_ID" --zone="$ZONE" --command="
        set -e
        sudo cp /tmp/quad-quarkus-1.0.0-SNAPSHOT-runner /opt/quad/quad-quarkus-1.0.0-SNAPSHOT-runner &&
        sudo rm -rf /opt/quad/frontend && sudo mkdir -p /opt/quad/frontend &&
        sudo cp -r /tmp/quad-frontend/. /opt/quad/frontend/ &&
        sudo chmod 755 /opt/quad/quad-quarkus-1.0.0-SNAPSHOT-runner &&
        sudo chown -R quad:quad /opt/quad &&
        sudo rm -f /tmp/quad-quarkus-1.0.0-SNAPSHOT-runner && sudo rm -rf /tmp/quad-frontend &&
        sudo cp /tmp/quad-backend.service /etc/systemd/system/quad-backend.service &&
        sudo cp /tmp/quad.env /etc/quad/quad.env &&
        sudo chown root:quad /etc/quad/quad.env &&
        sudo chmod 640 /etc/quad/quad.env &&
        sudo apt-get update -qq && sudo apt-get install -y -qq nginx &&
        sudo cp /tmp/nginx-vm.conf /etc/nginx/sites-available/quad &&
        sudo ln -sf /etc/nginx/sites-available/quad /etc/nginx/sites-enabled/quad &&
        sudo rm -f /etc/nginx/sites-enabled/default &&
        sudo nginx -t &&
        sudo systemctl daemon-reload &&
        sudo systemctl enable quad-backend &&
        sudo systemctl restart quad-backend &&
        sudo systemctl restart nginx"

    ok "VM konfiguriert"
}

show_status() {
    local ip
    ip=$(gcloud compute instances describe "$INSTANCE_NAME" \
        --project="$PROJECT_ID" --zone="$ZONE" \
        --format='get(networkInterfaces[0].accessConfigs[0].natIP)')
    echo ""
    log "═══════════ QUAD e2-micro Deploy Status ═══════════"
    echo "  UI:        http://$ip"
    echo "  Backend:   http://$ip/api/ (via Nginx)"
    echo "  Health:    http://$ip/q/health  (Quarkus SmallRye)"
    echo "  SSH:       gcloud compute ssh $VM_USER@$INSTANCE_NAME --zone=$ZONE --project=$PROJECT_ID"
    echo "  Logs:      sudo journalctl -u quad-backend -f"
    echo "  SQLite:    /opt/quad/data/quad-cloud.db (persistent)"
    echo ""
    ok "Deploy abgeschlossen!"
}

# ── Hauptprogramm ─────────────────────────────────────────────
main() {
    echo ""
    echo "══════════════════════════════════════════════"
    echo "  quad GCE e2-micro Deploy (nativ, ohne Docker)"
    echo "══════════════════════════════════════════════"
    echo ""
    check_prereqs
    build_backend
    build_frontend
    create_vm
    upload_and_configure
    show_status
}

main "$@"