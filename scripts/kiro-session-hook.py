#!/usr/bin/env python3
"""Install or run Sylph's scoped Kiro session-start hook. Never retain chat contents."""

import json
import os
from pathlib import Path
import re
import shlex
import sys
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit


def atomic_write(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError("Expected a regular hook file.")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".sylph-hook-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def install(hooks, helpers):
    helper = helpers / "kiro-session-hook.py"
    hook = hooks / "sylph-herdr-session.json"
    command = shlex.join([sys.executable, str(helper.absolute())])
    definition = {"name": "Sylph Herdr session capture", "trigger": "SessionStart",
                  "action": {"type": "command", "command": command}, "timeout": 5}
    legacy = (json.dumps(definition, indent=2) + "\n").encode()
    encoded = (json.dumps({"version": "v1", "hooks": [definition]}, indent=2) + "\n").encode()
    if hook.is_symlink():
        raise ValueError("Cannot replace a symbolic-link hook.")
    current = hook.read_bytes() if hook.exists() else None
    if current not in (None, legacy, encoded):
        raise ValueError("A different hook already uses Sylph's hook filename; it was kept.")
    atomic_write(helper, Path(__file__).read_bytes())
    if current != encoded:
        atomic_write(hook, encoded)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def capture():
    endpoint = os.environ.get("SYLPH_KIRO_CAPTURE_FILE", "")
    url = os.environ.get("SYLPH_KIRO_CAPTURE_URL", "")
    token = os.environ.get("SYLPH_KIRO_CAPTURE_TOKEN", "")
    tab = os.environ.get("SYLPH_HERDR_SESSION_ID", "")
    if endpoint and tab:
        path = Path(endpoint)
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 4096:
            return
        connection = json.loads(path.read_text())
        url, token = connection["SYLPH_KIRO_CAPTURE_URL"], connection["SYLPH_KIRO_CAPTURE_TOKEN"]
    if not url or not token or not tab:
        return
    parsed = urlsplit(url)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port
            or parsed.path != "/session" or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("Invalid Sylph session endpoint.")
    if not re.fullmatch(r"sylph-[a-z0-9][a-z0-9_-]{0,57}", tab):
        raise ValueError("Invalid Sylph tab identifier.")
    raw = sys.stdin.buffer.read(65537)
    if len(raw) > 65536:
        raise ValueError("Session event is too large.")
    event = json.loads(raw)
    if event.get("hook_event_name") not in ("SessionStart", "AgentSpawn", "agentSpawn"):
        return
    # Subagent events must not replace the parent chat's restore identity.
    if event.get("parent_session_id"):
        return
    session = event.get("session_id")
    cwd = event.get("cwd")
    if not isinstance(session, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,255}", session):
        raise ValueError("Invalid Kiro session identifier.")
    if not isinstance(cwd, str) or not Path(cwd).is_absolute():
        raise ValueError("Expected an absolute session directory.")
    payload = {"herdrSessionId": tab, "kiroSessionId": session, "directory": cwd}
    pane = os.environ.get("HERDR_PANE_ID", "")
    if pane:
        payload["paneId"] = pane
    data = json.dumps(payload).encode()
    request = urllib.request.Request(url, data=data, headers={"Authorization": "Bearer " + token,
                                                            "Content-Type": "application/json"})
    # Do not send local session metadata through an HTTP proxy or redirected endpoint.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=3) as response:
            if response.status != 204:
                raise ValueError("Sylph did not accept the session event.")
    except urllib.error.HTTPError as error:
        if error.code == 410:
            return  # The user closed this Sylph tab; never recreate its saved entry.
        raise
    # Herdr owns pane restore; report a native argv instead of typing into the TUI on restart.
    binary = os.environ.get("HERDR_BIN_PATH", "")
    if os.environ.get("HERDR_ENV") == "1" and binary and pane:
        result = subprocess.run([binary, "pane", "report-agent", pane, "--source", "sylph:kiro",
                                 "--agent", "kiro", "--state", "unknown", "--seq", str(time.time_ns()),
                                 "--agent-session-id", session, "--", "kiro-cli", "--resume-id", session],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=3)
        if result.returncode != 0:
            raise ValueError("Herdr could not save its pane resume command.")


if __name__ == "__main__":
    try:
        if len(sys.argv) == 4 and sys.argv[1] == "--install":
            install(Path(sys.argv[2]), Path(sys.argv[3]))
        elif len(sys.argv) == 1:
            capture()
        else:
            raise ValueError("Invalid hook arguments.")
    except (OSError, ValueError, TypeError, KeyError, AttributeError, urllib.error.URLError, subprocess.TimeoutExpired):
        message = ("Sylph could not install its Kiro hook. Check sylph-herdr-session.json conflicts and permissions."
                   if len(sys.argv) > 1 else
                   "Sylph could not record this Kiro session. Existing session state was kept.")
        print(message, file=sys.stderr)
        sys.exit(1)
