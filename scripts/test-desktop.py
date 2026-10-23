#!/usr/bin/env python3
"""Verify desktop registration in temporary directories without opening the editor."""

import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
APP_ID = "dev.somgester.sylph.EditorApp"


@unittest.skipUnless(sys.platform.startswith("linux"), "Desktop registration is Linux-only")
class DesktopTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="sylph-desktop-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.project = self.directory / 'Sylph space "quote" $dollar `tick` %percent \\slash=equals'
        (self.project / "scripts").mkdir(parents=True)
        (self.project / "app/src/main/resources/icons").mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/install-desktop.sh", self.project / "scripts/install-desktop.sh")
        shutil.copy2(ROOT / "app/src/main/resources/icons/sylph.path",
                     self.project / "app/src/main/resources/icons/sylph.path")
        (self.project / "run.sh").write_text('printf launched > "$SYLPH_TEST_LOG"\n')
        self.data = self.directory / 'data "quote" \\slash space'
        self.marker = self.directory / "launched"
        # Use system utilities, independent of local command wrappers.
        self.env = dict(os.environ, XDG_DATA_HOME=str(self.data), PATH="/usr/bin:/bin",
                        SYLPH_TEST_LOG=str(self.marker))
        self.entry = self.data / "applications" / f"{APP_ID}.desktop"
        self.icon = self.data / "icons/hicolor/scalable/apps" / f"{APP_ID}.svg"

    def install(self):
        return subprocess.run(["/bin/bash", str(self.project / "scripts/install-desktop.sh")],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_installs_shared_logo_and_matching_application_identity(self):
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        text = self.entry.read_text()
        self.assertIn("Name=Sylph\n", text)
        self.assertIn(f"StartupWMClass={APP_ID}\n", text)
        self.assertIn("Terminal=false\n", text)
        path = ET.parse(self.icon).getroot().find("{http://www.w3.org/2000/svg}path")
        self.assertEqual((ROOT / "app/src/main/resources/icons/sylph.path").read_text().strip(), path.get("d"))
        self.assertEqual("#06b6d4", path.get("fill"))
        self.assertEqual(0o644, stat.S_IMODE(self.entry.stat().st_mode))
        self.assertEqual(0o644, stat.S_IMODE(self.icon.stat().st_mode))
        if shutil.which("desktop-file-validate"):
            checked = subprocess.run(["desktop-file-validate", str(self.entry)], capture_output=True, text=True)
            self.assertEqual(0, checked.returncode, checked.stdout + checked.stderr)

    def test_repeated_registration_does_not_rewrite_unchanged_files(self):
        self.assertEqual(0, self.install().returncode)
        before = [(path.read_bytes(), path.stat().st_mtime_ns) for path in (self.entry, self.icon)]
        self.assertEqual(0, self.install().returncode)
        after = [(path.read_bytes(), path.stat().st_mtime_ns) for path in (self.entry, self.icon)]
        self.assertEqual(before, after)

    @unittest.skipUnless(shutil.which("gio"), "GIO is needed to verify desktop Exec parsing")
    def test_desktop_exec_launches_the_exact_script_even_with_reserved_path_characters(self):
        self.assertEqual(0, self.install().returncode)
        result = subprocess.run(["gio", "launch", str(self.entry)], env=self.env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        deadline = time.monotonic() + 5
        while not self.marker.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual("launched", self.marker.read_text())

    def test_control_character_paths_are_rejected_before_writing(self):
        self.env["XDG_DATA_HOME"] = str(self.directory / "bad\npath")
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("control characters", result.stderr)
        self.assertFalse(self.entry.exists())
        self.assertFalse((self.directory / "bad\npath").exists())


if __name__ == "__main__":
    unittest.main()
