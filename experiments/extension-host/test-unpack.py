"""Exercise containment checks without downloading or executing an extension."""

import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).with_name("unpack-extension.py")


class UnpackTest(unittest.TestCase):
    def unpack(self, entry, text="fixture"):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            archive = root / "fixture.vsix"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr(entry, text)
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(archive), str(root / "output")],
                capture_output=True, text=True, check=False,
            )
            files = {
                str(file.relative_to(root)): file.read_text()
                for file in root.rglob("*") if file.is_file() and file != archive
            }
            return result, files

    def test_unpacks_extension_files(self):
        result, files = self.unpack("extension/package.json", '{"name":"fixture"}')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"output/package.json": '{"name":"fixture"}'}, files)

    def test_rejects_parent_traversal(self):
        result, files = self.unpack("extension/../escaped")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, files)

    def test_rejects_backslash_paths(self):
        result, files = self.unpack("extension/..\\escaped")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, files)

    def test_rejects_symlinks(self):
        entry = zipfile.ZipInfo("extension/link")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        result, files = self.unpack(entry, "../escaped")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, files)


if __name__ == "__main__":
    unittest.main()
