#!/usr/bin/env bash
set -euo pipefail

# Keep setup reproducible; update the version and upstream checksums together.
SYLPH_HERDR_VERSION=0.9.5
SYLPH_HERDR_BIN="$HOME/.local/share/sylph/tools/bin"
if command -v herdr >/dev/null 2>&1 && herdr --version >/dev/null 2>&1; then
    echo "Herdr CLI is already installed. Skipping installation."
    exit 0
fi
for candidate in "$SYLPH_HERDR_BIN/herdr" "$HOME/.local/bin/herdr"; do
    if [[ -x "$candidate" ]] && "$candidate" --version >/dev/null 2>&1; then
        echo "Herdr CLI is already installed at $candidate. Skipping installation."
        exit 0
    fi
done

case "$(uname -s)/$(uname -m)" in
    Linux/x86_64|Linux/amd64)
        target=linux-x86_64
        expected=14d9e8bc8b4a9e0118d77c32266f76d461f09b40342eb23752960bd89f59929d ;;
    Linux/aarch64|Linux/arm64)
        target=linux-aarch64
        expected=17d7536f2da50859523680ef279dffcac8e4a235f4e46fe8da6c2ec44141897c ;;
    Darwin/x86_64|Darwin/amd64)
        target=macos-x86_64
        expected=5fb0a908ec7f6745e3e245738b06bfad56c46391c391ab3c0955fbead01f1369 ;;
    Darwin/aarch64|Darwin/arm64)
        target=macos-aarch64
        expected=fb77c2232a05a8035421382c894392f4174e4e38a18e48af3dd0290157c3a7e2 ;;
    *)
        echo "Automatic Herdr setup supports Linux and macOS on x86_64 or ARM64." >&2
        echo "Install Herdr CLI from https://herdr.dev/tui/docs/install/ and rerun init.sh." >&2
        exit 1 ;;
esac
command -v curl >/dev/null 2>&1 || { echo "Herdr setup requires curl." >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
    checksum=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
    checksum=(shasum -a 256)
else
    echo "Herdr setup requires sha256sum or shasum." >&2
    exit 1
fi

mkdir -p "$SYLPH_HERDR_BIN"
temporary="$(mktemp -d "$SYLPH_HERDR_BIN/.herdr-setup.XXXXXX")"
trap 'rm -rf -- "$temporary"' EXIT
url="https://github.com/herdrdev/herdr/releases/download/v$SYLPH_HERDR_VERSION/herdr-$target"
echo "Downloading Herdr CLI $SYLPH_HERDR_VERSION for $target..."
curl --fail --location --silent --show-error --retry 2 --connect-timeout 15 --max-time 180     "$url" -o "$temporary/herdr"
actual="$("${checksum[@]}" < "$temporary/herdr")"
if [[ "${actual%% *}" != "$expected" ]]; then
    echo "Herdr CLI checksum mismatch. Installation stopped." >&2
    exit 1
fi
chmod 755 "$temporary/herdr"
"$temporary/herdr" --version
mv -f -- "$temporary/herdr" "$SYLPH_HERDR_BIN/herdr"
echo "Herdr CLI setup completed successfully. Run ./run.sh and choose Herdr CLI."
