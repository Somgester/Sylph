#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

export PATH="$HOME/.proto/bin:$HOME/.proto/shims:$PATH"

if ! command -v proto &> /dev/null; then
  echo "proto not found — installing it now (https://moonrepo.dev/proto)..."
  bash <(curl -fsSL https://moonrepo.dev/install/proto.sh) --yes

  [ -f "$HOME/.bashrc" ] && source "$HOME/.bashrc" || true
fi

echo "Ensuring toolchain from .prototools is installed..."
proto install

echo "Using: $(java -version 2>&1 | head -n 1)"

./gradlew :app:check
./gradlew :app:build
exec ./gradlew :app:run "$@"
