#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: ./scripts/import-herdr-theme.sh FILE_OR_HTTPS_URL [DESTINATION]" >&2
    exit 2
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "Theme import requires Python 3.11 or newer." >&2; exit 4; }
python3 -c 'import tomllib' 2>/dev/null || {
    echo "Theme import requires Python 3.11 or newer (tomllib)." >&2
    exit 4
}
source_theme="$1"
destination="${2:-$HOME/.sylph/herdr-cli/imported-theme.toml}"
temporary=""
trap 'if [[ -n "$temporary" ]]; then rm -rf -- "$temporary"; fi' EXIT

if [[ "$source_theme" == https://* ]]; then
    # Disable user curl configuration, URL globbing, and non-HTTPS redirects.
    python3 "$script_dir/validate-herdr-theme.py" --check-url "$source_theme"
    command -v curl >/dev/null 2>&1 || { echo "Downloading a theme requires curl." >&2; exit 5; }
    temporary="$(mktemp -d)"
    if ! curl --disable --fail --location --silent --show-error --globoff \
        --proto '=https' --proto-redir '=https' --max-redirs 3 \
        --connect-timeout 15 --max-time 60 --max-filesize 65536 \
        --output "$temporary/theme.toml" -- "$source_theme"; then
        exit 6
    fi
    source_theme="$temporary/theme.toml"
elif [[ "$source_theme" == *://* ]]; then
    echo "Theme downloads require an HTTPS URL." >&2
    exit 3
fi

python3 "$script_dir/validate-herdr-theme.py" "$source_theme" "$destination"
echo "Herdr CLI theme imported successfully."
