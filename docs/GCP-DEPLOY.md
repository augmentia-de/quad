# GCP Build & Deploy

Step-by-step guide for building and deploying quad to GCP (Cloud Run or GKE).

---

## Prerequisites

```bash
# 1. gcloud auth
gcloud auth login
gcloud config set project YOUR_PROJECT_ID
# TEST: gcloud config get-value project  ->  YOUR_PROJECT_ID

# 2. Docker running
# TEST: docker --version  ->  Docker version 24+

# 3. Helm installed (GKE only)
# TEST: helm version  ->  v3.12+

# 4. Secrets configured
cp deploy/.env.secrets.cloud.example deploy/.env.secrets.cloud
# edit deploy/.env.secrets.cloud
# TEST: cat deploy/.env.secrets.cloud | grep -c "="  ->  > 0
```

---

## Phase 1: Build

```bash
# 5. Maven build (cloud profile, skip tests)
./scripts/build.sh --profile cloud
# TEST: ls quad-quarkus/target/quarkus-app/quarkus-run.jar  ->  exists

# 6. Docker build (done by build.sh above)
# TEST: docker images | grep quad-quarkus:cloud  ->  shows image
# TEST: docker images | grep quad-frontend:cloud  ->  shows image (if frontend exists)
```

---

## Phase 2: Push to GCR

```bash
# 7. Tag and push
./scripts/deploy.sh --profile cloud --push
# TEST: gcloud container images list --repository=gcr.io/YOUR_PROJECT_ID
#   ->  shows quad-quarkus, quad-frontend
```

---

## Phase 3a: Deploy to Cloud Run

```bash
# 8. Deploy all services
./scripts/deploy.sh --profile cloud --target cloudrun
# TEST: gcloud run services list --region=europe-west1
#   ->  shows quad-quarkus, quad-frontend

# 9. Get service URLs
QUARKUS_URL=$(gcloud run services describe quad-quarkus \
  --region=europe-west1 --format='value(status.url)')
FRONTEND_URL=$(gcloud run services describe quad-frontend \
  --region=europe-west1 --format='value(status.url)')
echo "Quarkus: $QUARKUS_URL"
echo "Frontend: $FRONTEND_URL"

# 10. Health check
curl "$QUARKUS_URL/q/health"
# TEST: returns {"status":"UP"}

# 11. Smoke test (interactive workflow chat)
curl -X POST "$QUARKUS_URL/api/ui/dynamic/chat/start" \
  -H "Content-Type: application/json" \
  -d '{"task":"Write a one-line greeting and return it as a string."}'
# TEST: returns 200 with JSON {"sessionId":..., "workflow":..., "reply":...}
# continues with: POST /api/ui/dynamic/chat {"sessionId":..., "request":"..."}
```

---

## Phase 3b: Deploy to GKE (alternative)

```bash
# 12. Get cluster credentials
gcloud container clusters get-credentials quad-cluster \
  --region=europe-west1 --project=YOUR_PROJECT_ID
# TEST: kubectl cluster-info  ->  shows control plane URL

# 13. Helm install/upgrade
./scripts/deploy.sh --profile cloud --target gke
# TEST: kubectl get pods -n quad  ->  shows Running pods

# 14. Check logs
kubectl logs -l app.kubernetes.io/component=quarkus -n quad --tail=50
# TEST: no "ERROR" or "FATAL" in startup logs

# 15. Ingress IP (if enabled)
kubectl get ingress -n quad
# TEST: shows ADDRESS with external IP

# 16. Test via Ingress
curl -X POST "http://EXTERNAL_IP/api/ui/dynamic/chat/start" \
  -H "Content-Type: application/json" \
  -d '{"task":"Say hello in exactly two words."}'
# TEST: returns 200
```

---

## Phase 4: Verify

```bash
# 17. Health endpoints
curl "$QUARKUS_URL/q/health/live"    # TEST: 200 + "UP"
curl "$QUARKUS_URL/q/health/ready"   # TEST: 200 + "UP"

# 18. LLM connectivity (requires valid OPENAI_API_KEY in secrets)
curl -X POST "$QUARKUS_URL/api/ui/dynamic/chat/start" \
  -H "Content-Type: application/json" \
  -d '{"task":"What is 2+2? Return the number as a string."}'
# TEST: returns a coherent reply (not an error about a missing API key)

# 19. Persistence (SQLite)
kubectl rollout restart deployment/quad-quarkus -n quad
sleep 30
curl "$QUARKUS_URL/q/health"
# TEST: 200 + "UP" after restart

# 20. HPA (if enabled)
kubectl get hpa -n quad
# TEST: shows TARGETS with percentage values
```

---

## Troubleshooting

| Symptom | Check |
|---|---|
| `ImagePullBackOff` | Image exists in GCR? `gcr.io/PROJECT_ID/quad-quarkus:cloud` |
| `CrashLoopBackOff` | `kubectl logs <pod> -n quad --previous` |
| `Connection refused` | Port mismatch? Quarkus should listen on `8080` |
| `API key missing` | Secrets set in Cloud Run? `gcloud run services describe quad-quarkus --format=json` |
| HPA not scaling | Metrics server installed? `kubectl top nodes` |
