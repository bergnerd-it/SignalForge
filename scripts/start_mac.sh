#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

BUILD_FLAG=""
if [[ "$1" == "--build" ]]; then
  BUILD_FLAG="--build"
fi

echo "=========================================="
echo "⚡ Starting SignalForge AI Trading Workstation"
echo "=========================================="

# Build and start via docker-compose
docker compose up -d $BUILD_FLAG

echo ""
echo "🚀 SignalForge is up and running!"
echo "👉 Open your browser at: http://localhost:8000"
echo "=========================================="

# Optionally open default browser on macOS if available
if command -v open >/dev/null 2>&1; then
  open http://localhost:8000 || true
fi
