#!/usr/bin/env bash
set -euo pipefail

[[ "$OSTYPE" == linux* ]] || exit 0
SYLPH_PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SYLPH_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}"
[[ "$SYLPH_DATA_DIR" == /* ]] || SYLPH_DATA_DIR="$HOME/.local/share"
# JavaFX uses the Application subclass name as its GTK window class.
SYLPH_APP_ID=dev.somgester.sylph.EditorApp
SYLPH_ICON="$SYLPH_DATA_DIR/icons/hicolor/scalable/apps/$SYLPH_APP_ID.svg"
SYLPH_DESKTOP="$SYLPH_DATA_DIR/applications/$SYLPH_APP_ID.desktop"

for path in "$SYLPH_PROJECT_DIR" "$SYLPH_DATA_DIR"; do
    if [[ "$path" == *$'\n'* || "$path" == *$'\r'* || "$path" == *$'\t'* ]]; then
        printf 'Cannot register desktop icon for a path containing control characters.\n' >&2
        exit 1
    fi
done

# Desktop entries decode backslashes before parsing Exec's quoted arguments.
sylph_exec_argument() {
    local value="$1"
    value="${value//%/%%}"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    value="${value//\$/\\\$}"
    value="${value//\`/\\\`}"
    value="${value//\\/\\\\}"
    printf '"%s"' "$value"
}

mkdir -p -- "$(dirname -- "$SYLPH_ICON")" "$(dirname -- "$SYLPH_DESKTOP")"
SYLPH_TEMP=""
trap '[[ -z "$SYLPH_TEMP" ]] || rm -f -- "$SYLPH_TEMP"' EXIT
sylph_install_file() {
    local target="$1"
    SYLPH_TEMP="$(mktemp "${target}.XXXXXX")"
    cat > "$SYLPH_TEMP"
    chmod 644 "$SYLPH_TEMP"
    if ! cmp -s -- "$SYLPH_TEMP" "$target"; then
        mv -fT -- "$SYLPH_TEMP" "$target"
    else
        rm -f -- "$SYLPH_TEMP"
    fi
    SYLPH_TEMP=""
}

SYLPH_MARK="$(< "$SYLPH_PROJECT_DIR/app/src/main/resources/icons/sylph.path")"
sylph_install_file "$SYLPH_ICON" <<EOF
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#06b6d4" d="$SYLPH_MARK"/></svg>
EOF
sylph_install_file "$SYLPH_DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=Sylph
Comment=Sylph code editor
Exec=/bin/bash -- $(sylph_exec_argument "$SYLPH_PROJECT_DIR/run.sh")
Icon=${SYLPH_ICON//\\/\\\\}
Terminal=false
StartupNotify=true
StartupWMClass=$SYLPH_APP_ID
Categories=Development;IDE;
EOF
