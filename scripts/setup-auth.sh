#!/usr/bin/env bash
set -euo pipefail

SYLPH_AUTH_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../auth" && pwd)"
SYLPH_AUTH_ENV="$SYLPH_AUTH_DIR/.env"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Keycloak setup requires Docker with Docker Compose v2." >&2
  echo "Install it from https://docs.docker.com/get-started/get-docker/ and rerun ./init.sh." >&2
  echo "For guest-only editing, use ./init.sh --java-only and ./run.sh." >&2
  exit 1
fi
if ! docker compose up --help | grep -q -- '--wait-timeout'; then
  echo "Update Docker Compose v2: Keycloak setup requires support for --wait and --wait-timeout." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker is unavailable. Start Docker and ensure your user can access it, then rerun ./init.sh." >&2
  exit 1
fi

if [[ -L "$SYLPH_AUTH_ENV" || ( -e "$SYLPH_AUTH_ENV" && ! -f "$SYLPH_AUTH_ENV" ) ]]; then
  echo "Keycloak credentials must be a regular auth/.env file, not a symlink or directory." >&2
  exit 1
fi
if [[ ! -f "$SYLPH_AUTH_ENV" ]]; then
  # Create exclusively: another setup process must not overwrite existing credentials.
  if ! (umask 077; set -o noclobber
    printf '%s\n' 'KC_BOOTSTRAP_ADMIN_USERNAME=sylph-admin' '' \
      '# Optional OAuth credentials, used only when importing a fresh realm.' \
      '# Set both values for a provider; single-quote literal secrets.' \
      'GOOGLE_CLIENT_ID=' 'GOOGLE_CLIENT_SECRET=' 'GITHUB_CLIENT_ID=' 'GITHUB_CLIENT_SECRET=' \
      > "$SYLPH_AUTH_ENV"
  ); then
    if [[ ! -f "$SYLPH_AUTH_ENV" || -L "$SYLPH_AUTH_ENV" ]]; then
      echo "Could not create private Keycloak credentials in auth/.env." >&2
      exit 1
    fi
  fi
fi
chmod 600 "$SYLPH_AUTH_ENV"
if ! grep -Eq '^[[:space:]]*(export[[:space:]]+)?KC_BOOTSTRAP_ADMIN_PASSWORD[[:space:]]*=' "$SYLPH_AUTH_ENV"; then
  SYLPH_AUTH_PASSWORD="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
  if [[ ! "$SYLPH_AUTH_PASSWORD" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Could not generate a secure Keycloak admin password." >&2
    exit 1
  fi
  printf '\nKC_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$SYLPH_AUTH_PASSWORD" >> "$SYLPH_AUTH_ENV"
  unset SYLPH_AUTH_PASSWORD
fi

echo "Setting up Keycloak (first run downloads its container image)..."
if ! docker compose --env-file "$SYLPH_AUTH_ENV" -f "$SYLPH_AUTH_DIR/compose.yaml" \
    up -d --pull missing --wait --wait-timeout 180 keycloak; then
  echo "Keycloak setup failed. Check Docker, network access, and whether port 8080 is already in use." >&2
  echo "Inspect logs: docker compose --env-file auth/.env -f auth/compose.yaml logs keycloak" >&2
  exit 1
fi

echo "Keycloak is ready at http://localhost:8080. Sylph realm and desktop client are configured."
echo "Admin credentials are in auth/.env (private; existing credentials and accounts are preserved)."
echo "Google/GitHub on a fresh realm use client IDs/secrets from auth/.env or the environment."
echo "Existing realms keep their provider settings; startup import does not overwrite them."
