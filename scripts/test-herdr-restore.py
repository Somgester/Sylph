#!/usr/bin/env python3
"""Opt-in Herdr restart check. Pass --real-kiro for two real chats without sending prompts."""

import concurrent.futures
import fcntl
import http.server
import json
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import termios
import threading
import time
import uuid


def check(cwd, real_kiro):
    herdr = shutil.which("herdr")
    if not herdr:
        raise RuntimeError("Install Herdr CLI before running this opt-in check.")
    hook = Path(__file__).resolve().parent / "kiro-session-hook.py"
    with tempfile.TemporaryDirectory(prefix="sr-", dir="/tmp") as temporary:
        root = Path(temporary)
        binary = root / "bin"
        binary.mkdir()
        log = root / "kiro-argv.jsonl"
        if real_kiro:
            binary = hook.parent / "herdr-shell"
        else:
            fake = binary / "kiro-cli"
            fake.write_text(f"#!{sys.executable}\n" + '''
import json, os, subprocess, sys, time
with open(os.environ["TEST_KIRO_LOG"], "a") as output:
    output.write(json.dumps(sys.argv[1:]) + "\\n")
event = {"hook_event_name":"SessionStart", "session_id":"exact-resume-id", "cwd":os.getcwd()}
result = subprocess.run([sys.executable, os.environ["TEST_KIRO_HOOK"]], input=json.dumps(event), text=True)
if result.returncode: sys.exit(result.returncode)
time.sleep(120)
''')
            fake.chmod(0o755)
        reports = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                if self.headers.get("Authorization") != "Bearer fixture-token":
                    self.send_response(403)
                else:
                    reports.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                    self.send_response(204)
                self.end_headers()

            def log_message(self, *args):
                pass

        receiver = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=receiver.serve_forever, daemon=True).start()
        session = "sylph-" + uuid.uuid4().hex[:20]
        config = root / "herdr"
        config.mkdir()
        settings = config / "config.toml"
        settings.write_text('onboarding = false\n[terminal]\ndefault_shell = "/bin/sh"\n'
                            'shell_mode = "non_login"\n[update]\nversion_check = false\nmanifest_check = false\n')
        base = {key: value for key, value in os.environ.items() if not key.startswith(("HERDR_", "SYLPH_"))}
        env = dict(base, HERDR_CONFIG_PATH=str(settings), XDG_CONFIG_HOME=str(root),
                   XDG_STATE_HOME=str(root / "state"), PATH=str(binary) + os.pathsep + os.environ["PATH"],
                   SHELL="/bin/sh", ENV="/dev/null", TERM="xterm-256color",
                   TEST_KIRO_LOG=str(log), TEST_KIRO_HOOK=str(hook), SYLPH_HERDR_SESSION_ID=session,
                   SYLPH_KIRO_CAPTURE_URL=f"http://127.0.0.1:{receiver.server_port}/session",
                   SYLPH_KIRO_CAPTURE_TOKEN="fixture-token")
        if real_kiro:
            # Exercise interactive Bash startup, including PATH changes in ~/.bashrc.
            settings.write_text(settings.read_text().replace("/bin/sh", "/bin/bash"))
            env.update(SYLPH_HERDR_WRAPPER_DIR=str(binary), SHELL="/bin/bash")
            env["BASH_FUNC_kiro-cli%%"] = '() { "$SYLPH_HERDR_WRAPPER_DIR/kiro-cli" "$@"; }'
        for name in ("HERDR_SESSION", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_ENV",
                     "SYLPH_KIRO_CAPTURE_FILE"):
            env.pop(name, None)
        clients = []

        def cli(*arguments):
            return subprocess.run([herdr, "--session", session, *arguments], env=env, cwd=cwd,
                                  capture_output=True, text=True, timeout=20)

        def attach():
            master, slave = pty.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))

            def controlling_terminal():
                os.setsid()
                fcntl.ioctl(0, termios.TIOCSCTTY, 0)

            client = subprocess.Popen([herdr, "--session", session], env=env, cwd=cwd,
                                      stdin=slave, stdout=slave, stderr=slave, preexec_fn=controlling_terminal)
            os.close(slave)
            clients.append((client, master))

            def drain():
                while client.poll() is None:
                    try:
                        if select.select([master], [], [], 0.1)[0]:
                            os.read(master, 65536)
                    except OSError:
                        return

            threading.Thread(target=drain, daemon=True).start()

        def wait_for(condition, description):
            deadline = time.monotonic() + (60 if real_kiro else 15)
            while time.monotonic() < deadline:
                if condition():
                    return
                time.sleep(0.1)
            raise AssertionError("Timed out: " + description)

        def snapshot():
            result = cli("api", "snapshot")
            if result.returncode:
                return {}
            data = json.loads(result.stdout)
            data = data.get("result", data)
            return data.get("snapshot", data)

        try:
            attach()
            wait_for(lambda: bool(snapshot().get("panes")), "first server ready")
            pane = snapshot()["panes"][0]["pane_id"]
            started = cli("pane", "run", pane, "kiro-cli")
            assert started.returncode == 0, started.stderr
            saved_layout = config / "sessions" / session / "session.json"
            wait_for(lambda: reports and saved_layout.is_file() and "--resume-id" in saved_layout.read_text(),
                     "native Kiro resume reference saved")
            first = reports[0]["kiroSessionId"]
            assert reports[0]["herdrSessionId"] == session
            if not real_kiro:
                assert first == "exact-resume-id"
            def pane_sessions():
                return {(r.get("paneId"), r["kiroSessionId"]) for r in reports if r["herdrSessionId"] == session}

            if real_kiro:
                created = cli("tab", "create", "--cwd", cwd)
                assert created.returncode == 0, created.stderr
                second = next(p["pane_id"] for p in snapshot()["panes"] if p["pane_id"] != pane)
                assert cli("pane", "run", second, "kiro-cli").returncode == 0
                wait_for(lambda: len(pane_sessions()) == 2 and
                         saved_layout.read_text().count("--resume-id") == 2, "both internal tabs saved")
                expected = pane_sessions()
                assert {p for p, _ in expected} == {pane, second}
                assert len({sid for _, sid in expected}) == 2
            reports.clear()
            if real_kiro and sys.platform == "linux":
                # The peer of this fixture-only Unix socket is its own Herdr server.
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                    client.connect(str(saved_layout.parent / "herdr.sock"))
                    pid, uid, _ = struct.unpack("3i", client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    assert uid == os.getuid() and Path(f"/proc/{pid}/exe").resolve() == Path(herdr).resolve()
                    os.kill(pid, signal.SIGKILL)
                wait_for(lambda: clients[0][0].poll() is not None, "client disconnected after forced stop")
            else:
                stopped = cli("session", "stop", session)
                assert stopped.returncode == 0, stopped.stderr
            attach()
            if real_kiro:
                wait_for(lambda: pane_sessions() == expected, "both original chats loaded in their own internal tabs")
                return first
            wait_for(lambda: log.is_file() and len(log.read_text().splitlines()) >= 2, "Kiro resumed after server stop")
            invocations = [json.loads(line) for line in log.read_text().splitlines()]
            assert invocations == [[], ["--resume-id", "exact-resume-id"]], invocations
            print("Native Herdr restart passed: fake Kiro resumed once with the exact saved ID.")
        except Exception:
            print("Fixture Kiro invocations:", log.read_text() if log.exists() else "none")
            print("Fixture capture count:", len(reports))
            print("Herdr snapshot available:", bool(snapshot().get("panes")))
            raise
        finally:
            cli("session", "stop", session)
            for client, master in clients:
                if client.poll() is None:
                    client.terminate()
                    try:
                        client.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        client.kill()
                        client.wait(timeout=3)
                os.close(master)
            receiver.shutdown()
            receiver.server_close()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="sylph-kiro-workspace-") as workspace:
        if sys.argv[1:] == ["--real-kiro"]:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(check, workspace, True) for _ in range(2)]
                assert len({future.result() for future in futures}) == 2
            print("Two Herdr sessions each restored both internal tabs with the original Kiro IDs.")
        elif not sys.argv[1:]:
            check(workspace, False)
        else:
            sys.exit("Usage: test-herdr-restore.py [--real-kiro]")
