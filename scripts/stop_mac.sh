#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

echo "=========================================="
echo "🛑 Stopping FinAlly AI Trading Workstation"
echo "=========================================="

docker compose down

echo "✓ FinAlly stopped successfully (database volume preserved)."
