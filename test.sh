#!/usr/bin/env bash
set -euo pipefail

SYLPH_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SYLPH_SCRIPT_DIR"
source "$SYLPH_SCRIPT_DIR/init.sh"

exec ./gradlew :app:test "$@"
    