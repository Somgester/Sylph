#!/usr/bin/env python3
"""Exercise theme imports with temporary files and a fake downloader; no network or Herdr process."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ThemeImportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="sylph-theme-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.source = self.directory / "theme with spaces.toml"
        self.destination = self.directory / "profile with spaces/imported-theme.toml"
        self.source.write_text('[theme]\nname = "nord"\n')
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        (self.bin / "python3").symlink_to(sys.executable)
        self.download_log = self.directory / "download.json"
        downloader = self.bin / "curl"
        downloader.write_text(f"#!{sys.executable}\n" + '''
import json, os, pathlib, sys
args = sys.argv[1:]
pathlib.Path(os.environ["THEME_DOWNLOAD_LOG"]).write_text(json.dumps(args))
pathlib.Path(args[args.index("--output") + 1]).write_text(os.environ["THEME_DOWNLOAD_BODY"])
sys.exit(int(os.environ["THEME_DOWNLOAD_STATUS"]))
''')
        downloader.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{self.bin}:/usr/bin:/bin", TMPDIR=str(self.directory),
                        THEME_DOWNLOAD_LOG=str(self.download_log), THEME_DOWNLOAD_STATUS="0",
                        THEME_DOWNLOAD_BODY=self.source.read_text())

    def run_import(self, source=None):
        return subprocess.run(["/bin/bash", str(ROOT / "scripts/import-herdr-theme.sh"),
                               str(self.source if source is None else source), str(self.destination)],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def preserve_previous(self):
        self.destination.parent.mkdir(parents=True, exist_ok=True)
        self.destination.write_text("previous theme")

    def test_local_import_normalizes_only_theme_settings_and_is_private(self):
        self.source.write_text('''[theme]
name = "rose-pine-dawn"
auto_switch = true
dark_name = "dracula"
light_name = "one-light"
[theme.custom]
accent = "#aBc"
red = "rgb(255, 0, 5)"
[theme.custom.light]
text = "black"
[theme.custom.dark]
text = "#ffffff"
''')
        result = self.run_import()
        self.assertEqual(0, result.returncode, result.stderr)
        data = tomllib.loads(self.destination.read_text())
        self.assertEqual({"onboarding", "theme"}, data.keys())
        self.assertFalse(data["onboarding"])
        self.assertEqual("#abc", data["theme"]["custom"]["accent"])
        self.assertEqual("black", data["theme"]["custom"]["light"]["text"])
        self.assertEqual("#ffffff", data["theme"]["custom"]["dark"]["text"])
        self.assertEqual(0o600, self.destination.stat().st_mode & 0o777)
        self.assertFalse(self.download_log.exists())

    def test_download_uses_https_bounds_and_passes_url_as_one_argument(self):
        url = "https://example.org/theme.toml?literal=$(echo%20hello)&theme=nord"
        result = self.run_import(url)
        self.assertEqual(0, result.returncode, result.stderr)
        args = json.loads(self.download_log.read_text())
        self.assertEqual("--disable", args[0])
        self.assertEqual(["--", url], args[-2:])
        for option, value in (("--proto", "=https"), ("--proto-redir", "=https"),
                              ("--max-filesize", "65536"), ("--max-time", "60")):
            self.assertEqual(value, args[args.index(option) + 1])
        self.assertEqual([], list(self.directory.glob("tmp*")))

    def test_network_failure_preserves_previous_theme_and_cleans_temporary_files(self):
        self.preserve_previous()
        self.env["THEME_DOWNLOAD_STATUS"] = "22"
        result = self.run_import("https://example.org/missing.toml")
        self.assertEqual(6, result.returncode)
        self.assertEqual("previous theme", self.destination.read_text())
        self.assertEqual([], list(self.directory.glob("tmp*")))

    def test_unsafe_urls_rejected_before_downloading(self):
        for url in ("http://example.org/theme.toml", "file:///tmp/theme.toml",
                    "https://user:secret@example.org/theme.toml", "https://example.org/bad\nurl"):
            with self.subTest(url=url):
                result = self.run_import(url)
                self.assertEqual(3, result.returncode)
                self.assertFalse(self.download_log.exists())
                self.assertNotIn("secret", result.stdout + result.stderr)

    def test_invalid_data_preserves_previous_theme(self):
        self.preserve_previous()
        for data in ('[theme]\nname = "unknown"', '[theme]\nauto_switch = "yes"',
                     '[theme.custom]\naccent = "rgb(256, 0, 0)"', '[theme.custom]\naccent = 5',
                     '[theme.custom]\ncommand = "touch marker"', '[theme.custom.light]\ntext = "bogus"',
                     '[theme]\nname = "nord"\n[shell]\ncommand = "touch marker"',
                     '[theme]\ncommand = "touch marker"', '[theme]\n', '<html>not TOML</html>',
                     '[theme]\nname = "nord"\nname = "dracula"'):
            with self.subTest(data=data):
                self.source.write_text(data)
                result = self.run_import()
                self.assertEqual(3, result.returncode)
                self.assertEqual("previous theme", self.destination.read_text())
                self.assertFalse((self.directory / "marker").exists())
                self.assertEqual([], list(self.destination.parent.glob(".herdr-theme-*")))

    def test_invalid_download_is_not_installed(self):
        self.preserve_previous()
        self.env["THEME_DOWNLOAD_BODY"] = "<html>GitHub page instead of raw TOML</html>"
        result = self.run_import("https://example.org/theme")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("previous theme", self.destination.read_text())
        self.assertEqual([], list(self.directory.glob("tmp*")))

    def test_oversized_or_non_utf8_input_is_rejected(self):
        for data in (b"#" * 65537, b"\xff\xfe"):
            self.source.write_bytes(data)
            result = self.run_import()
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(self.destination.exists())

    def test_symlink_destination_is_not_followed_or_replaced(self):
        self.destination.parent.mkdir()
        target = self.directory / "untouched"
        target.write_text("unchanged")
        self.destination.symlink_to(target)
        result = self.run_import()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(self.destination.is_symlink())
        self.assertEqual("unchanged", target.read_text())

    def test_successful_import_replaces_previous_theme_without_leaving_staging_files(self):
        self.preserve_previous()
        result = self.run_import()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("nord", tomllib.loads(self.destination.read_text())["theme"]["name"])
        self.assertEqual([], list(self.destination.parent.glob(".herdr-theme-*")))

    def test_missing_source_reports_file_access_failure(self):
        self.preserve_previous()
        result = self.run_import(self.directory / "missing.toml")
        self.assertEqual(7, result.returncode)
        self.assertEqual("previous theme", self.destination.read_text())

    def test_missing_or_unsupported_python_reports_dependency_failure(self):
        # Restrict PATH so the fixture cannot fall back to the machine's Python.
        (self.bin / "dirname").symlink_to(shutil.which("dirname"))
        self.env["PATH"] = str(self.bin)
        python = self.bin / "python3"
        python.unlink()
        for old_python in (False, True):
            with self.subTest(old_python=old_python):
                if old_python:
                    python.write_text("#!/bin/sh\nexit 1\n")
                    python.chmod(0o755)
                result = self.run_import()
                self.assertEqual(4, result.returncode)
                self.assertIn("Python 3.11", result.stderr)
                self.assertFalse(self.destination.exists())

    def test_missing_curl_reports_dependency_failure_before_downloading(self):
        (self.bin / "dirname").symlink_to(shutil.which("dirname"))
        (self.bin / "curl").unlink()
        self.env["PATH"] = str(self.bin)
        result = self.run_import("https://example.org/theme.toml")
        self.assertEqual(5, result.returncode)
        self.assertIn("requires curl", result.stderr)
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
