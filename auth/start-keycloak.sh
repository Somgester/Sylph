#!/usr/bin/env bash
set -euo pipefail

sylph_json_string_value() {
  # Keycloak substitutes raw text before parsing JSON. Control characters are
  # rejected below. Escape JSON syntax and prevent nested ${...} interpolation.
  local value="${1//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "${value//\$/\\u0024}"
}

sylph_configure_provider() {
  local provider="$1" id_variable="${1}_CLIENT_ID" secret_variable="${1}_CLIENT_SECRET"
  local client_id="${!id_variable:-}" client_secret="${!secret_variable:-}" enabled=false
  if [[ ( -n "$client_id" && -z "$client_secret" ) || ( -z "$client_id" && -n "$client_secret" ) ]]; then
    echo "Set both ${provider}_CLIENT_ID and ${provider}_CLIENT_SECRET, or leave both empty." >&2
    return 1
  fi
  if [[ "$client_id" =~ [[:cntrl:]] || "$client_secret" =~ [[:cntrl:]] ]]; then
    echo "${provider} credentials cannot contain control characters or newlines." >&2
    return 1
  fi
  if [[ -n "$client_id" && -n "$client_secret" ]]; then
    enabled=true
  fi
  export "SYLPH_${provider}_ENABLED=$enabled"
  export "SYLPH_${provider}_CLIENT_ID_JSON=$(sylph_json_string_value "$client_id")"
  export "SYLPH_${provider}_CLIENT_SECRET_JSON=$(sylph_json_string_value "$client_secret")"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  sylph_configure_provider GOOGLE
  sylph_configure_provider GITHUB
  exec /opt/keycloak/bin/kc.sh "$@"
fi
