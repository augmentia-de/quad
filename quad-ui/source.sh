#!/bin/bash
set -e

ENV_FILE="${1:-$(dirname "$0")/../.env.local}"

if [ ! -f "$ENV_FILE" ]; then
    echo "Error: .env file not found at $ENV_FILE"
    echo "Usage: source.sh [path-to-env-file]"
    echo ""
    echo "Creating default .env file..."
    cat > "${ENV_FILE}" << 'EOF'
# QUAD Studio Environment Configuration
# Backend API
QUAD_API_KEY=sk-your-api-key-here
OPENAI_MODEL=gpt-4o-mini
QUAD_COST_LIMIT=1.0
QUAD_DUMMY_MODE=true
QUAD_WORKSPACE=/work/quad-ui
QUAD_TOOLS=readFile,writeFile,webSearch

# Frontend
VITE_API_BASE_URL=http://localhost:8080/api/ui
VITE_DEBUG=false

# Optional: Advanced Settings
# QUAD_MAX_TOKENS=4000
# QUAD_TEMPERATURE=0.7
EOF
    echo "Created $ENV_FILE"
    echo "Please edit it with your configuration!"
    exit 1
fi

echo "Sourcing QUAD Studio environment from $ENV_FILE..."

while IFS='=' read -r key value; do
    # Skip empty lines and comments
    [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue
    
    # Remove quotes if present
    key="${key%%[[:space:]]}"
    value="${value#\"}"
    value="${value%\"}"
    
    # Export to current shell
    export "$key=$value"
done < "$ENV_FILE"

echo "Environment configured:"
echo "  - OPENAI_MODEL: ${OPENAI_MODEL:-not set}"
echo "  - QUAD_COST_LIMIT: ${QUAD_COST_LIMIT:-not set}"
echo "  - QUAD_DUMMY_MODE: ${QUAD_DUMMY_MODE:-not set}"
echo "  - VITE_API_BASE_URL: ${VITE_API_BASE_URL:-not set}"
echo ""
echo "You can now run: ./quad/start-all.sh"