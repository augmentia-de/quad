#!/bin/bash
# deploy.sh — deploys quad to the cloud (GCP Cloud Run / GKE)
#
# Usage:
#   ./scripts/deploy.sh --profile cloud              # default: Cloud Run
#   ./scripts/deploy.sh --profile cloud --target gke  # GKE
#   ./scripts/deploy.sh --profile cloud --push        # push only, no deploy
#
# Prerequisites:
#   - gcloud CLI installed and authenticated
#   - docker image built (./scripts/build.sh --profile cloud)
#   - deploy/.env.secrets.cloud filled with secrets

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$PROJECT_DIR/deploy"
HELM_DIR="$DEPLOY_DIR/helm/quad"

# Defaults
ACTIVE_PROFILE="cloud"
DEPLOY_TARGET="cloudrun"  # cloudrun | gke
PUSH_ONLY=false
PROJECT_ID="${GCP_PROJECT_ID:-}"
REGION="${GCP_REGION:-europe-west1}"
SERVICE_NAME="quad"

# Helper functions
log() { echo -e "\033[1;34m▶ $1\033[0m"; }
ok()  { echo -e "\033[1;32m✓ $1\033[0m"; }
warn(){ echo -e "\033[1;33m⚠ $1\033[0m"; }
err() { echo -e "\033[1;31m✗ $1\033[0m"; exit 1; }

usage() {
    cat <<EOF
quad Cloud Deploy Script

Usage: ./scripts/deploy.sh [options]

Options:
  --profile cloud   profile (cloud only)
  --target TARGET   deploy target: cloudrun (default) | gke
  --push            push images only, do not deploy
  --project ID      GCP project id
  --region REGION   GCP region (default: europe-west1)
  --service NAME    service name (default: quad)

Prerequisites:
  - gcloud CLI: gcloud auth login
  - Docker: ./scripts/build.sh --profile cloud
  - Secrets: fill deploy/.env.secrets.cloud

Examples:
  ./scripts/deploy.sh                              # Cloud Run deploy
  ./scripts/deploy.sh --target gke                 # GKE deploy
  ./scripts/deploy.sh --push                       # push only
  ./scripts/deploy.sh --project my-project --region us-central1
EOF
}

# Arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --profile)  ACTIVE_PROFILE="$2"; shift 2 ;;
        --target)   DEPLOY_TARGET="$2"; shift 2 ;;
        --push)     PUSH_ONLY=true; shift ;;
        --project)  PROJECT_ID="$2"; shift 2 ;;
        --region)   REGION="$2"; shift 2 ;;
        --service)  SERVICE_NAME="$2"; shift 2 ;;
        --help|-h)  usage; exit 0 ;;
        *)          shift ;;
    esac
done

# Checks
check_prereqs() {
    command -v gcloud >/dev/null 2>&1 || err "gcloud CLI not installed"
    command -v docker >/dev/null 2>&1 || err "Docker not installed"
    
    if [[ -z "$PROJECT_ID" ]]; then
        PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
        [[ -z "$PROJECT_ID" ]] && err "GCP project not set. Use --project or GCP_PROJECT_ID"
    fi
    
    local secrets_file="$DEPLOY_DIR/.env.secrets.cloud"
    if [[ ! -f "$secrets_file" ]]; then
        warn "Secrets file not found: $secrets_file"
        warn "Creating from template..."
        cp "$DEPLOY_DIR/.env.secrets.cloud.example" "$secrets_file" 2>/dev/null || true
    fi
    
    if ! docker images | grep -q "quad-quarkus:$ACTIVE_PROFILE"; then
        err "Docker image quad-quarkus:$ACTIVE_PROFILE not found. Build first: ./scripts/build.sh --profile $ACTIVE_PROFILE"
    fi
}

# Load secrets
load_secrets() {
    local secrets_file="$DEPLOY_DIR/.env.secrets.cloud"
    if [[ -f "$secrets_file" ]]; then
        set -a
        source "$secrets_file"
        set +a
    fi
}

# Docker push
push_images() {
    log "Push docker images to GCR..."
    
    local repo="gcr.io/$PROJECT_ID"
    
    log "  → Push quad-quarkus..."
    docker tag "quad-quarkus:$ACTIVE_PROFILE" "$repo/quad-quarkus:$ACTIVE_PROFILE"
    docker push "$repo/quad-quarkus:$ACTIVE_PROFILE"
    
    if docker images | grep -q "quad-frontend:$ACTIVE_PROFILE"; then
        log "  → Push quad-frontend..."
        docker tag "quad-frontend:$ACTIVE_PROFILE" "$repo/quad-frontend:$ACTIVE_PROFILE"
        docker push "$repo/quad-frontend:$ACTIVE_PROFILE"
    fi
    
    if docker images | grep -q "quad-mcp:$ACTIVE_PROFILE"; then
        log "  → Push quad-mcp..."
        docker tag "quad-mcp:$ACTIVE_PROFILE" "$repo/quad-mcp:$ACTIVE_PROFILE"
        docker push "$repo/quad-mcp:$ACTIVE_PROFILE"
    fi
    
    ok "Images gepusht zu $repo"
}

# ── Cloud Run Deploy ───────────────────────────────────────────
deploy_cloudrun() {
    log "Deploy zu Cloud Run..."
    
    local repo="gcr.io/$PROJECT_ID"
    load_secrets
    
    local services=("quarkus" "frontend")
    
    for service in "${services[@]}"; do
        log "  → Deploy $service..."
        
        local image="$repo/quad-$service:$ACTIVE_PROFILE"
        local port=8080
        [[ "$service" == "frontend" ]] && port=80
        
        # Build env vars from secrets file
        local env_vars="QUARKUS_PROFILE=$ACTIVE_PROFILE"
        if [[ -n "${OPENAI_API_KEY:-}" ]]; then
            env_vars="$env_vars,OPENAI_API_KEY=$OPENAI_API_KEY"
        fi
        if [[ -n "${OPENAI_BASE_URL:-}" ]]; then
            env_vars="$env_vars,OPENAI_BASE_URL=$OPENAI_BASE_URL"
        fi
        if [[ -n "${OPENAI_MODEL:-}" ]]; then
            env_vars="$env_vars,OPENAI_MODEL=$OPENAI_MODEL"
        fi
        
        gcloud run deploy "$SERVICE_NAME-$service" \
            --image="$image" \
            --region="$REGION" \
            --project="$PROJECT_ID" \
            --platform=managed \
            --allow-unauthenticated \
            --port="$port" \
            --memory=512Mi \
            --cpu=1 \
            --min-instances=0 \
            --max-instances=10 \
            --set-env-vars="$env_vars" \
            2>&1 | head -20
        
        ok "  $service deployed"
    done
    
    ok "Cloud Run Deploy abgeschlossen"
}

# ── GKE Deploy ────────────────────────────────────────────────
deploy_gke() {
    log "Deploy zu GKE..."
    
    gcloud container clusters get-credentials "$SERVICE_NAME-cluster" \
        --region="$REGION" \
        --project="$PROJECT_ID" 2>/dev/null || warn "Cluster-Kontext konnte nicht gesetzt werden"
    
    log "  → Helm Upgrade..."
    helm upgrade --install "$SERVICE_NAME" "$HELM_DIR" \
        -f "$HELM_DIR/values-cloud.yaml" \
        --namespace=quad \
        --create-namespace \
        --set quarkus.env.QUARKUS_PROFILE="$ACTIVE_PROFILE" \
        --set quarkus.image.repository="gcr.io/$PROJECT_ID/quad-quarkus" \
        --set quarkus.image.tag="$ACTIVE_PROFILE" \
        --set frontend.image.repository="gcr.io/$PROJECT_ID/quad-frontend" \
        --set frontend.image.tag="$ACTIVE_PROFILE" \
        --wait \
        --timeout=300s
    
    ok "GKE Deploy abgeschlossen"
}

# ── Status ─────────────────────────────────────────────────────
show_status() {
    echo ""
    log "═══ Deploy Status ═══"
    echo ""
    
    if [[ "$DEPLOY_TARGET" == "cloudrun" ]]; then
        gcloud run services list \
            --project="$PROJECT_ID" \
            --region="$REGION" \
            --filter="metadata.name~$SERVICE_NAME" \
            --format="table(metadata.name,status.url,status.conditions[0].type)"
    else
        kubectl get pods -n quad 2>/dev/null || warn "Keine GKE-Verbindung"
    fi
}

# ── Hauptprogramm ─────────────────────────────────────────────
main() {
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  quad Cloud Deploy ($DEPLOY_TARGET)"
    echo "════════════════════════════════════════════════════════"
    echo ""
    
    check_prereqs
    push_images
    
    if [[ "$PUSH_ONLY" == "true" ]]; then
        ok "Nur Push abgeschlossen (kein Deploy)"
        exit 0
    fi
    
    if [[ "$DEPLOY_TARGET" == "cloudrun" ]]; then
        deploy_cloudrun
    elif [[ "$DEPLOY_TARGET" == "gke" ]]; then
        deploy_gke
    else
        err "Unbekanntes Deploy-Ziel: $DEPLOY_TARGET"
    fi
    
    show_status
    
    echo ""
    ok "Deploy abgeschlossen!"
    echo ""
}

main
