#!/usr/bin/env bash
# ============================================================
# run-step1.sh — Quickstart Step1 (MinimalAgent) mit .env-Settings
# ------------------------------------------------------------
# Lädt die LLM-Settings ausschließlich aus quad-quarkus/.env
# (OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_MODEL), kompiliert
# quad-examples und startet den Quickstart-Agenten per Java-Classpath.
#
# Nutzung:
#   ./run-step1.sh                 # Step1 (MinimalAgent) mit .env
#   ./run-step1.sh --step 3        # anderer Quickstart-Schritt (1..5)
#   ./run-step1.sh --class <FQCN>  # beliebige Main-Klasse
#   ./run-step1.sh --keys          # zusätzlich set_keys.sh sourcen (überschreibt OPENAI_*)
#   ./run-step1.sh --rebuild       # Reactor-Abhängigkeiten frisch installieren
#   ./run-step1.sh --list          # verfügbare Schritte auflisten
#
# Env-Overrides:
#   QUAD_ENV_FILE=/pfad/.env       # andere .env verwenden
#
# Beispiel .env (quad-quarkus/.env):
#   OPENAI_API_KEY=...
#   OPENAI_BASE_URL=https://openrouter.ai/api/v1
#   OPENAI_MODEL=deepseek/deepseek-v4-flash-0731
# ============================================================
set -euo pipefail

EXAMPLES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${EXAMPLES_DIR}/.." && pwd)"          # .../quad-quarkus
ENV_FILE="${QUAD_ENV_FILE:-${ROOT_DIR}/.env}"

STEP=1
REBUILD=0
WITH_KEYS=0
MAIN_CLASS=""

declare -A STEP_CLASSES=(
  [1]="de.augmentia.quad.examples.quickstart.Step1_MinimalAgent"
  [2]="de.augmentia.quad.examples.quickstart.Step2_AgentWithTool"
  [3]="de.augmentia.quad.examples.quickstart.Step3_ConfiguredAgent"
  [4]="de.augmentia.quad.examples.quickstart.Step4_StructuredOutput"
  [5]="de.augmentia.quad.examples.quickstart.Step5_SubAgents"
)

usage() {
  cat <<'EOF'
run-step1.sh — Quickstart Step1 (MinimalAgent) mit .env-Settings

Optionen:
  --step N            Quickstart-Schritt 1..5 (Default: 1)
  --class <FQCN>      beliebige Main-Klasse statt eines Steps
  --keys              zusätzlich set_keys.sh sourcen (überschreibt OPENAI_*)
  --rebuild           Reactor-Abhängigkeiten frisch installieren
  --list              verfügbare Schritte auflisten
  -h, --help          diese Hilfe

Env-Override:
  QUAD_ENV_FILE=/pfad/.env   andere .env verwenden (Default: ../.env)
EOF
}

list_steps() {
  local k
  for k in 1 2 3 4 5; do printf '  %s  %s\n' "$k" "${STEP_CLASSES[$k]}"; done
}

while [ $# -gt 0 ]; do
  case "$1" in
    --step)    STEP="${2:?--step braucht eine Zahl}"; shift 2 ;;
    --class)   MAIN_CLASS="${2:?--class braucht einen FQCN}"; shift 2 ;;
    --keys)    WITH_KEYS=1; shift ;;
    --rebuild) REBUILD=1; shift ;;
    --list)    list_steps; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unbekannte Option: $1" >&2; usage; exit 1 ;;
  esac
done

MAIN_CLASS="${MAIN_CLASS:-${STEP_CLASSES[$STEP]:-}}"
if [ -z "${MAIN_CLASS}" ]; then
  echo "❌ Unbekannter Step: ${STEP} (erwartet 1..5)" >&2
  exit 1
fi

command -v mvn  >/dev/null 2>&1 || { echo "❌ mvn wird benötigt." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "❌ java wird benötigt." >&2; exit 1; }

# ── .env laden (einzige Quelle der LLM-Settings) ─────────────
if [ -f "${ENV_FILE}" ]; then
  echo "🌱 Lade ${ENV_FILE}"
  set +eu; set -a; . "${ENV_FILE}"; set +a; set -eu
else
  echo "⚠️  Keine .env unter ${ENV_FILE} — nutze vorhandene Umgebungsvariablen."
fi

# ── optional zusätzlich set_keys.sh (explizit per --keys; überschreibt OPENAI_*) ─
if [ "${WITH_KEYS}" = "1" ] && [ -f "${ROOT_DIR}/set_keys.sh" ]; then
  echo "🔑 Lade zusätzlich ${ROOT_DIR}/set_keys.sh"
  set +eu; set -a; . "${ROOT_DIR}/set_keys.sh"; set +a; set -eu
fi

: "${OPENAI_API_KEY:?OPENAI_API_KEY nicht gesetzt (in ${ENV_FILE} setzen oder --keys verwenden)}"

BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com/v1}"
MODEL="${OPENAI_MODEL:-gpt-4o-mini}"
echo "   base url : ${BASE_URL}"
echo "   model    : ${MODEL}"
echo "   api key  : ${OPENAI_API_KEY:0:6}..."
echo

# ── Build ────────────────────────────────────────────────────
echo "🔨 Kompiliere quad-examples (-am) ..."
( cd "${ROOT_DIR}" && mvn -q -pl quad-examples -am -DskipTests compile )

if [ "${REBUILD}" = "1" ]; then
  echo "🔨 Installiere Reactor-Abhängigkeiten frisch (--rebuild) ..."
  ( cd "${ROOT_DIR}" && mvn -q -pl quad-examples -am -DskipTests install )
fi

# ── Runtime-Classpath ermitteln ──────────────────────────────
CPFILE="$(mktemp)"
trap 'rm -f "${CPFILE}"' EXIT
( cd "${EXAMPLES_DIR}" && mvn -q dependency:build-classpath \
    -DincludeScope=runtime -Dmdep.outputFile="${CPFILE}" )
CLASSPATH="${EXAMPLES_DIR}/target/classes:$(cat "${CPFILE}")"

# ── Start ────────────────────────────────────────────────────
echo "🚀 Starte ${MAIN_CLASS}"
echo "──────────────────────────────────────────────────────────"
exec java -cp "${CLASSPATH}" "${MAIN_CLASS}"
