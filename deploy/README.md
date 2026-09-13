# quad Deployment

This directory contains Docker Compose stacks and configurations for various
deployment profiles: local development (`dev`), VM/Docker (`prod`) and cloud (`cloud`).

## Profiles

| Profile | Use | DB | OTel | Identity | MCP | Frontend |
|---------|--------|----|------|----------|-----|----------|
| `dev` | Local development | PostgreSQL (Docker) | No | Keycloak | Yes | React (Nginx) |
| `prod` | VM / Docker Server | PostgreSQL (Docker) | optional | Keycloak | Yes | React (Nginx) |
| `cloud` | GCP Cloud Run / GKE | SQLite (file) | No | No | optional | React (Nginx/Cloud CDN) |
| `minimal` | 1GB VM / Raspberry Pi | SQLite (file) | No | No | No | React (Nginx, same container) |

## Stacks

Each file is its own Docker Compose stack that can be deployed individually:

| File | Stack Name | Description |
|-------|-----------|-------------|
| `01-infrastructure.yml` | `infra` | PostgreSQL + Redis + Kafka |
| `02-observability.yml` | `obs` | OTel Collector + Prometheus + Tempo + Grafana |
| `03-identity-keycloak.yml` | `keycloak` | Keycloak Identity Provider |
| `04-mcp-servers.yml` | `mcp` | MCP Tool Server |
| `05-app-quarkus.yml` | `quarkus` | Quarkus Backend (full, JVM) |
| `05-app-minimal.yml` | `minimal` | Quarkus Backend (native) + Frontend |
| `06-frontend-react.yml` | `frontend` | React Web UI (standalone) |

## Prerequisites

- Docker + Docker Compose
- A valid OpenAI API key if `OPENAI_API_KEY` is needed
- For native builds: Mandrel/GraalVM (built inside the Docker container)

---

## Docker Deployment (Local, VM, On-Prem)

### 1. Minimal (native, SQLite, no Identity/MCP - 1 GB VM enough)

**Resource footprint:** ~200 MB RAM, ~1 GB disk (image + DB), 1 CPU core.

```bash
# 1. Build native binary (takes ~4 minutes inside Docker)
./scripts/build.sh --profile cloud --native

# 2. Start the minimal stack
./scripts/start.sh up minimal

# 3. Open: http://localhost
```

Or directly with Docker Compose:

```bash
docker compose -f deploy/05-app-minimal.yml up -d
```

**What runs:** One container with the native binary (Quarkus, SQLite DB) + one Nginx container
(serves the frontend, proxies `/api/` to the backend). No Identity, no MCP, no Kafka.

Configuration via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENAI_API_KEY` | (empty → dummy mode) | OpenAI API Key |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Compatible LLM provider |
| `OPENAI_MODEL` | `gpt-4o-mini` | Model name |

### 2. Production (PostgreSQL, with Identity/MCP)

```bash
# 1. Build JVM image
./scripts/build.sh --profile prod

# 2. Start all stacks
./scripts/start.sh up --profile prod

# 3. Start individual stacks:
./scripts/start.sh up infra      # PostgreSQL, Redis, Kafka
./scripts/start.sh up keycloak   # Identity
./scripts/start.sh up mcp        # MCP Tools
./scripts/start.sh up quarkus    # Backend
./scripts/start.sh up frontend   # React UI
```

> **Note on Kafka addressing:** The broker advertises its listener as
> `PLAINTEXT://kafka:9092` (docker-internal) so the backend containers
> (`quarkus`, `redpanda-console`) can reach it by the service name. On the
> app side `QUAD_MESSAGING_KAFKA_INBOUND_BOOTSTRAP_SERVERS=kafka:9092` applies.
> For host-side consumers (e.g. the `E2E_HitlKafkaAgentTest` / host JVM), map
> `kafka` to `127.0.0.1` in `/etc/hosts` (the host port `9092:9092` is exposed
> via compose) or switch `KAFKA_ADVERTISED_LISTENERS` in `01-infrastructure.yml`
> back to `PLAINTEXT://localhost:9092` for host-only plans.
>
> **HITL on missing permission (Port 02):** If `QUAD_PERMISSION_ENABLED=true`
> is set, a missing permission (mode-deny) can be escalated to a human instead of a
> hard block — `QUAD_PERMISSION_HITL_ESCALATION=true`.
> The guard then creates a checkpoint (APPROVE = tool still runs,
> REJECT = Cancel) and delivers the notification via the configured
> HITL channels (`quad.hitl.notification.channels`, e.g. `kafka` →
> topic `quad-hitl-checkpoints`). Without HITL (disabled), everything falls back to
> the previous hard cancel.

### 3. Development (for active development)

```bash
./scripts/start.sh up
```

Help for `start.sh`:

```bash
./scripts/start.sh help
```

---

## GCP Cloud Run Deployment

### Prerequisites

```bash
gcloud auth login
gcloud config set project YOUR_PROJECT_ID
gcloud services enable run.googleapis.com containerregistry.googleapis.com
```

### Build & Deploy

```bash
# 1. Build image
./scripts/build.sh --profile cloud --native

# 2. Push to GCR and deploy
./scripts/deploy.sh --project YOUR_PROJECT_ID --region europe-west1
```

Push only without deploying:

```bash
./scripts/deploy.sh --push
```

### Configure Secrets

Fill in `deploy/.env.secrets.cloud` (from template):

```bash
cp deploy/.env.secrets.cloud.example deploy/.env.secrets.cloud
# OPENAI_API_KEY=sk-...
```

### Architecture (Cloud Run)

```
Cloud Run (quad-quarkus)
  ├── Port 8080 — Quarkus Backend (native, SQLite)
  └── SQLite: /tmp/quad-cloud.db (ephemeral, persist via Cloud Storage → optional)

Cloud Run (quad-frontend)
  ├── Port 80 — Nginx (serves React build)
  └── proxied /api/ → quad-quarkus:8080

No PostgreSQL, Redis, Kafka, Keycloak, OTel, MCP.
```

**Note:** Cloud Run uses an ephemeral filesystem. SQLite data is lost when
undeploying/scaling. For Cloud Run production we recommend:
- PostgreSQL via Cloud SQL (instead of SQLite), or
- SQLite mounted via Cloud Storage Fuse (slower, but persistent).

---

## GKE (Kubernetes) Deployment

```bash
# Deploy with Helm
./scripts/deploy.sh --target gke --project YOUR_PROJECT_ID
```

Helm chart: `deploy/helm/quad/`

---

## Native Binary (without Docker, for Bare-Metal/VM)

The native binary can also be started directly on a Linux VM (no Docker needed):

```bash
# Build locally
mvn package -Pnative -DskipTests -Dquarkus.profile=cloud -pl quad-quarkus -am

# Copy binary
scp quad-quarkus/target/quad-quarkus-*-runner vm:/opt/quad/application
scp -r quad-ui/dist vm:/opt/quad/frontend/

# Start on the VM (with systemd or tmux)
/opt/quad/application -Dquarkus.profile=cloud
```

Then serve the frontend via Nginx on the VM (see `deploy/nginx-minimal.conf` as a template).

---

## HITL — Follow-up Questions and Approvals (Human In The Loop)

> **Detailed documentation:** see [`HITL.md`](../HITL.md) in the repo root
> (architecture, channel routing/auto-fallback, channels, sequence diagram).

quad supports HITL checkpoints: the agent asks the user for permission for certain
actions (e.g. "Write file xyz"). The approval runs via:

**0. Auto-routing (popup instead of email/Kafka)**

When a user opens the frontend, the `HitlOverlay` registers an
SSE stream (`/api/checkpoints/stream/ui`). The backend detects the connected UI
and delivers checkpoints as **popups**; only without an open UI is the user
notified async via **email/Kafka** (see `quad.hitl.notification.channels`).
The Nginx `/api/` location is configured for SSE (no buffering).

**1. Mark tools (configuration)**

In `application.properties` (or per environment variable):

```properties
quad.hitl.approval-tools=writeFile,appendFile,deleteFile
```

Tools listed here create a checkpoint before execution.

**2. API endpoints (for external integration)**

| Method | Path | Description |
|---------|------|-------------|
| `GET` | `/api/checkpoints/{sessionId}/pending` | Fetch open approvals |
| `POST` | `/api/checkpoints/{id}/approve` | Approve (body: `{"feedback": "OK"}`) |
| `POST` | `/api/checkpoints/{id}/reject` | Reject (body: `{"feedback": "reason"}`) |
| `GET` | `/api/checkpoints/stream/{sessionId}` | SSE stream for live notifications |

**3. Frontend overlay**

In the React frontend a `HitlOverlay` dialog appears automatically as soon as a
checkpoint is pending. The user sees the tool name, arguments and can choose
Approve/Reject. The run is blocked until a decision is made.

**4. Email notification (optional)**

```properties
quad.hitl.timeout-seconds=300
quad.hitl.notification.emails=admin@example.com
```

Requires a configured SMTP server (`EXTERNAL_SMTP_HOST` etc.).

**5. Timeout**

If a checkpoint is not answered within `quad.hitl.timeout-seconds` (default: 120 s),
the agent aborts with a timeout.

**Example (REST API for a custom UI):**

```bash
# Fetch open checkpoints
curl http://localhost:8080/api/checkpoints/session-abc-123/pending

# Approve
curl -X POST http://localhost:8080/api/checkpoints/cp-42/approve \
  -H 'Content-Type: application/json' \
  -d '{"feedback": "looks good"}'

# Reject
curl -X POST http://localhost:8080/api/checkpoints/cp-42/reject \
  -H 'Content-Type: application/json' \
  -d '{"feedback": "please choose a different file"}'
```

---

## Environment Variables (Backend)

### Quarkus (all profiles)

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_PROFILE` | `dev` | Active profile (`dev`, `prod`, `cloud`) |
| `QUARKUS_HTTP_PORT` | `8080` | HTTP port |
| `QUARTUS_HTTP_HOST` | `0.0.0.0` | Bind address |

### LLM / OpenAI

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENAI_API_KEY` | — | OpenAI API key (empty = dummy mode) |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Compatible LLM provider |
| `OPENAI_MODEL` | `gpt-4o-mini` | Model name |

### Datasource

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_DATASOURCE_DB_KIND` | `sqlite` | DB type (`sqlite`, `postgresql`) |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:sqlite:data/quad.db` | JDBC URL |
| `QUARKUS_DATASOURCE_USERNAME` | — | DB user |
| `QUARKUS_DATASOURCE_PASSWORD` | — | DB password |

### Identity / OIDC

| Variable | Default | Description |
|----------|---------|-------------|
| `OIDC_ENABLED` | `false` | Enable OIDC |
| `OIDC_ISSUER_URL` | — | Keycloak realm URL |
| `OIDC_CLIENT_ID` | `quad-ui` | OIDC client ID |
| `OIDC_CLIENT_SECRET` | — | OIDC client secret |

### HITL

| Variable | Default | Description |
|----------|---------|-------------|
| `quad.hitl.approval-tools` | — | Comma-separated list of tools requiring approval |
| `quad.hitl.timeout-seconds` | `120` | Timeout until abort |
| `quad.hitl.notification.emails` | — | Email address for notifications |
| `EXTERNAL_SMTP_HOST` | — | SMTP server |
| `EXTERNAL_SMTP_PORT` | — | SMTP port |
| `EXTERNAL_MAIL_USER` | — | SMTP user |
| `EXTERNAL_MAIL_PASS` | — | SMTP password |

### Feature Flags

| Variable | Default | Description |
|----------|---------|-------------|
| `QUAD_MCP_ENABLED` | `true` | Enable MCP tools |
| `QUAD_MCP_CONFIG_PATH` | — | Path to the MCP configuration |
| `QUAD_MODEL_TIERS_ENABLED` | `true` | Enable model tiering |
| `QUAD_SKILLS_ENABLED` | `false` | Enable the skill system |

---

## File Structure

```
deploy/
├── .env.cloud              # Cloud environment variables
├── .env.dev                # Dev environment variables
├── .env.prod               # Prod environment variables
├── .env.secrets.cloud      # Cloud secrets (not committed)
├── .env.secrets.cloud.example  # Template for cloud secrets
├── 01-infrastructure.yml   # PostgreSQL + Redis + Kafka
├── 02-observability.yml    # OTel + Prometheus + Tempo + Grafana
├── 03-identity-keycloak.yml # Keycloak
├── 04-mcp-servers.yml      # MCP Tool Server
├── 05-app-quarkus.yml      # Quarkus Backend (JVM)
├── 05-app-minimal.yml      # [NEW] Minimal stack (native, SQLite, no Identity/MCP)
├── 06-frontend-react.yml   # React Frontend
├── 07-tools.yml            # Helper tools (Adminer etc.)
├── 08-observability-ui.yml # Loki + Promtail (log aggregation)
├── nginx-minimal.conf      # [NEW] Nginx configuration for the minimal stack
├── init-pgvector.sql        # PostgreSQL-PgVector initialization
├── otel-collector-config.yaml
├── prometheus.yml
├── tempo.yaml
├── helm/quad/              # Helm chart for GKE
├── keycloak/               # Keycloak realm configuration
└── helper-config/          # Configuration helpers
```