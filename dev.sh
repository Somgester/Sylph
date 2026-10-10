#!/usr/bin/env bash
set -euo pipefail

SYLPH_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SYLPH_SCRIPT_DIR"
source "$SYLPH_SCRIPT_DIR/init.sh"

echo "Checking lint..."
"$SYLPH_SCRIPT_DIR/lint.sh"
echo "Running tests..."
"$SYLPH_SCRIPT_DIR/test.sh"
echo "Starting Sylph..."
exec "$SYLPH_SCRIPT_DIR/run.sh" "$@"
