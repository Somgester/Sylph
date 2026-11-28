#!/usr/bin/env python3
"""Check the scoped hook with fake local events; no Kiro chat, account, or home changes."""

import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).resolve().parent / "kiro-session-hook.py"


class CaptureTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="sylph-kiro-hook-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.received = []
        self.status = 204
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                fixture.received.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(fixture.status)
                self.end_headers()

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.env = dict(os.environ)
        for name in list(self.env):
            if name.startswith(("SYLPH_", "HERDR_")):
                self.env.pop(name)
        self.env.update(SYLPH_KIRO_CAPTURE_URL=f"http://127.0.0.1:{self.server.server_port}/session",
                        SYLPH_KIRO_CAPTURE_TOKEN="fixture-token", SYLPH_HERDR_SESSION_ID="sylph-first")

    def capture(self, **changes):
        event = dict(hook_event_name="SessionStart", session_id="exact-id", cwd=str(self.directory),
                     user_prompt="must never be forwarded")
        event.update(changes)
        return subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(event), env=self.env,
                              capture_output=True, text=True, timeout=5)

    def test_capture_forwards_only_scoped_identity_and_directory(self):
        result = self.capture()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)
        self.assertEqual([dict(herdrSessionId="sylph-first", kiroSessionId="exact-id",
                               directory=str(self.directory))], self.received)

    def test_unscoped_session_is_a_quiet_noop(self):
        self.env.pop("SYLPH_HERDR_SESSION_ID")
        result = self.capture()
        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stdout + result.stderr)
        self.assertEqual([], self.received)

    def test_subagent_and_unrelated_events_do_not_replace_parent_session(self):
        for changes in (dict(hook_event_name="SubagentStart", agent_id="child"), dict(parent_session_id="parent"),
                        dict(hook_event_name="SessionEnd")):
            result = self.capture(**changes)
            self.assertEqual(0, result.returncode)
        self.assertEqual([], self.received)

    def test_invalid_id_or_directory_is_rejected_without_echoing_payload(self):
        for changes in (dict(session_id="bad;secret-command"), dict(cwd="relative")):
            result = self.capture(**changes)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("secret-command", result.stderr)
        self.assertEqual([], self.received)

    def test_closed_tab_response_is_a_quiet_noop(self):
        self.status = 410
        result = self.capture()
        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stdout + result.stderr)

    def test_capture_reports_exact_resume_argv_to_herdr(self):
        binary = self.directory / "fake-herdr"
        log = self.directory / "herdr-argv.json"
        binary.write_text(f"#!{sys.executable}\n" + '''
import json, os, pathlib, sys
pathlib.Path(os.environ["TEST_HERDR_ARGV"]).write_text(json.dumps(sys.argv[1:]))
''')
        binary.chmod(0o755)
        self.env.update(HERDR_ENV="1", HERDR_BIN_PATH=str(binary), HERDR_PANE_ID="w1:p1",
                        TEST_HERDR_ARGV=str(log))
        result = self.capture()
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = json.loads(log.read_text())
        self.assertEqual(["kiro-cli", "--resume-id", "exact-id"], arguments[-3:])
        self.assertIn("sylph:kiro", arguments)
        self.assertNotIn("fixture-token", arguments)
        log.unlink()
        self.status = 410
        result = self.capture()
        self.assertEqual(0, result.returncode)
        self.assertFalse(log.exists())

    def test_remote_endpoints_are_rejected(self):
        self.env["SYLPH_KIRO_CAPTURE_URL"] = "https://example.org/session"
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.received)

    def test_installer_keeps_other_hooks_and_rejects_filename_conflicts(self):
        hooks, helpers = self.directory / "hooks", self.directory / "helpers with spaces"
        hooks.mkdir()
        other = hooks / "other.json"
        other.write_text("keep other hook")
        command = [sys.executable, str(SCRIPT), "--install", str(hooks), str(helpers)]
        for _ in range(2):
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
        installed = json.loads((hooks / "sylph-herdr-session.json").read_text())
        self.assertEqual("v1", installed["version"])
        definition, = installed["hooks"]
        self.assertEqual("SessionStart", definition["trigger"])
        self.assertEqual("command", definition["action"]["type"])
        self.assertEqual("keep other hook", other.read_text())
        if os.name == "posix":
            self.assertEqual(0o600, (helpers / "kiro-session-hook.py").stat().st_mode & 0o777)
        conflict = hooks / "sylph-herdr-session.json"
        conflict.write_text(json.dumps(definition, indent=2) + "\n")
        subprocess.run(command, check=True, timeout=5)
        self.assertEqual(installed, json.loads(conflict.read_text()))
        conflict.write_text("user-owned file")
        result = subprocess.run(command, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("user-owned file", conflict.read_text())

    def test_setup_checks_version_and_installs_only_into_explicit_test_directories(self):
        binary = self.directory / "bin"
        binary.mkdir()
        kiro = binary / "kiro-cli"
        hooks, helpers = self.directory / "setup-hooks", self.directory / "setup-helpers"
        env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                   SYLPH_KIRO_HOOKS_DIR=str(hooks), SYLPH_KIRO_HELPER_DIR=str(helpers))
        for version, expected in (("2.12.0", 1), ("2.21.4", 0)):
            kiro.write_text(f"#!/bin/sh\necho 'kiro-cli {version}'\n")
            kiro.chmod(0o755)
            result = subprocess.run(["/bin/bash", str(SCRIPT.parent / "setup-kiro-capture.sh")],
                                    env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(expected, result.returncode, result.stderr)
        self.assertTrue((hooks / "sylph-herdr-session.json").is_file())
        self.assertTrue((helpers / "kiro-session-hook.py").is_file())


if __name__ == "__main__":
    unittest.main()
