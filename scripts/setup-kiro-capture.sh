#!/usr/bin/env bash
set -euo pipefail

if ! command -v kiro-cli >/dev/null 2>&1; then
    echo "Kiro CLI is not installed. Skipping optional session capture setup."
    exit 0
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "Kiro session capture needs Python 3.8+. Install it and rerun ./init.sh." >&2
    exit 1
fi
python3 -c 'import sys; sys.exit(sys.version_info < (3, 8))' || {
    echo "Kiro session capture needs Python 3.8+. Install it and rerun ./init.sh." >&2
    exit 1
}
version="$(kiro-cli --version)"
if [[ ! "$version" =~ ([0-9]+)\.([0-9]+) ]] ||
    (( BASH_REMATCH[1] < 2 || (BASH_REMATCH[1] == 2 && BASH_REMATCH[2] < 13) )); then
    echo "Kiro session capture requires Kiro CLI 2.13 or newer with global lifecycle hooks." >&2
    exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 "$script_dir/kiro-session-hook.py" --install \
    "${SYLPH_KIRO_HOOKS_DIR:-$HOME/.kiro/hooks}" "${SYLPH_KIRO_HELPER_DIR:-$HOME/.sylph/kiro-hooks}"
echo "Sylph Kiro session-start hook is ready. Only Sylph Herdr tabs report session IDs."
