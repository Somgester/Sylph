#!/usr/bin/env python3
"""Validate Herdr 0.9.5 theme data and write only theme settings, never executable code."""

import json
import os
from pathlib import Path
import re
import sys
import tempfile
import tomllib
from urllib.parse import urlsplit


MAX_BYTES = 65536
THEMES = frozenset("""catppuccin catppuccin-latte terminal tokyo-night tokyo-night-day
dracula nord gruvbox gruvbox-light one-dark one-light solarized solarized-light
kanagawa kanagawa-lotus rose-pine rose-pine-dawn vesper""".split())
TOKENS = frozenset("""accent panel_bg sidebar_bg active_row_bg selection_bg surface0 surface1
surface_dim overlay0 overlay1 text subtext0 mauve green yellow red blue teal peach""".split())
NAMED_COLORS = frozenset("""reset default none transparent black red green yellow blue magenta
purple cyan white gray grey darkgray darkgrey lightred lightgreen lightyellow lightblue
lightmagenta lightcyan""".split())


def color(value):
    if not isinstance(value, str):
        raise ValueError("Colors must be strings.")
    value = value.strip().lower()
    if value in NAMED_COLORS or re.fullmatch(r"#[0-9a-f]{3}(?:[0-9a-f]{3})?", value):
        return value
    rgb = re.fullmatch(r"rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)", value)
    if rgb and all(int(part) <= 255 for part in rgb.groups()):
        return value
    raise ValueError("Unsupported color; use hex, rgb(0..255, 0..255, 0..255), or a named color.")


def color_section(values, modes=False):
    if not isinstance(values, dict):
        raise ValueError("Custom colors must be a TOML table.")
    allowed = TOKENS | {"light", "dark"} if modes else TOKENS
    if values.keys() - allowed:
        raise ValueError("Unknown custom theme field.")
    return {key: color_section(value) if modes and key in {"light", "dark"} else color(value)
            for key, value in values.items()}


def normalize(data):
    if data.keys() != {"theme"} or not isinstance(data["theme"], dict) or not data["theme"]:
        raise ValueError("Provide a nonempty [theme] table only; full Herdr configs and Desktop layouts are unsupported.")
    theme = data["theme"]
    if theme.keys() - {"name", "auto_switch", "dark_name", "light_name", "custom"}:
        raise ValueError("Unknown theme field.")
    result = {}
    for key, value in theme.items():
        if key == "custom":
            result[key] = color_section(value, modes=True)
        elif key == "auto_switch":
            if not isinstance(value, bool):
                raise ValueError("auto_switch must be true or false.")
            result[key] = value
        else:
            if not isinstance(value, str) or value not in THEMES:
                raise ValueError("Unknown built-in theme name.")
            result[key] = value
    lines = ["onboarding = false", "", "[theme]"]
    for key, value in result.items():
        if key != "custom":
            lines.append(f"{key} = {str(value).lower() if isinstance(value, bool) else json.dumps(value)}")
    custom = result.get("custom", {})
    for section, values in [("theme.custom", custom),
                            ("theme.custom.light", custom.get("light", {})),
                            ("theme.custom.dark", custom.get("dark", {}))]:
        colors = {key: value for key, value in values.items() if key not in {"light", "dark"}}
        if colors:
            lines.extend(["", f"[{section}]"])
            lines.extend(f"{key} = {json.dumps(value)}" for key, value in colors.items())
    return "\n".join(lines) + "\n"


def install(source, destination):
    with Path(source).open("rb") as stream:
        contents = stream.read(MAX_BYTES + 1)
    if len(contents) > MAX_BYTES:
        raise ValueError("Theme exceeds the 64 KiB limit.")
    normalized = normalize(tomllib.loads(contents.decode("utf-8")))
    destination = Path(destination).absolute()
    if destination.is_symlink() or destination.is_dir():
        raise ValueError("Theme destination must be a regular file.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=destination.parent,
                                         prefix=".herdr-theme-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(normalized)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    if len(sys.argv) != 3:
        raise ValueError("Expected source and destination, or --check-url URL.")
    if sys.argv[1] == "--check-url":
        url = sys.argv[2]
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or any(character.isspace() or ord(character) < 32 for character in url)):
            raise ValueError("Use an HTTPS URL without credentials or whitespace.")
        return
    install(sys.argv[1], sys.argv[2])


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        # Input and paths can contain secrets; report a fixed diagnostic for OS/parser failures.
        message = str(error) if type(error) is ValueError else "Cannot read, validate, or install the theme file."
        print(f"Theme import failed: {message}", file=sys.stderr)
        sys.exit(7 if isinstance(error, OSError) else 3)
