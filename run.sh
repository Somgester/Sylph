#!/usr/bin/env bash
set -euo pipefail

SYLPH_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SYLPH_SCRIPT_DIR"
source "$SYLPH_SCRIPT_DIR/init.sh"

if ! bash "$SYLPH_SCRIPT_DIR/scripts/install-desktop.sh"; then
    printf 'Could not refresh Sylph desktop icon; continuing app launch.\n' >&2
fi

# The JavaFX run task captures source sets that Gradle cannot configuration-cache.
exec ./gradlew :app:run --no-configuration-cache "$@"
