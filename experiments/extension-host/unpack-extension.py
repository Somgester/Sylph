"""Unpack only extension files after setup.mjs verifies the archive's pinned hash."""

import pathlib
import stat
import sys
import zipfile

archive, destination = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(archive) as package:
    for entry in package.infolist():
        if not entry.filename.startswith("extension/") or entry.is_dir():
            continue
        relative = pathlib.PurePosixPath(entry.filename.removeprefix("extension/"))
        if relative.is_absolute() or ".." in relative.parts or "\\" in str(relative):
            raise ValueError(f"Unsafe extension path: {relative}")
        if stat.S_ISLNK(entry.external_attr >> 16):
            raise ValueError(f"Extension symlink is unsupported: {relative}")
        target = destination.joinpath(*relative.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(package.read(entry))
