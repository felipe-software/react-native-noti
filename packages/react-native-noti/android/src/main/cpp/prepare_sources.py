#!/usr/bin/env python3
"""Reproduce the checked-in Doom engine and Freedoom assets; never run at build time."""
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import tarfile
from urllib.request import urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent
COMMIT = "dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284"
ENGINE_URL = f"https://codeload.github.com/ozkl/doomgeneric/tar.gz/{COMMIT}"
ENGINE_SHA = "1bd3f7f26220494159a38d71f2847ec81b58d6bbd7c7c8d81b08993018001148"
DATA_URL = "https://github.com/freedoom/freedoom/releases/download/v0.13.0/freedoom-0.13.0.zip"
DATA_SHA = "3f9b264f3e3ce503b4fb7f6bdcb1f419d93c7b546f4df3e874dd878db9688f59"
WAD_SHA = "7323bcc168c5a45ff10749b339960e98314740a734c30d4b9f3337001f9e703d"


def download(url, expected):
    with urlopen(url, timeout=120) as response:
        data = response.read()
    if sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch: {url}")
    return BytesIO(data)


def main():
    engine = ROOT / "vendor" / "doomgeneric"
    with tarfile.open(fileobj=download(ENGINE_URL, ENGINE_SHA), mode="r:gz") as archive:
        prefix = f"doomgeneric-{COMMIT}/"
        for member in archive.getmembers():
            if not member.isfile() or not member.name.startswith(prefix):
                continue
            relative = Path(member.name[len(prefix):])
            if ".." in relative.parts or relative.is_absolute():
                raise RuntimeError("Invalid archive path")
            if relative.parts[0] != "doomgeneric" and str(relative) not in ("LICENSE", "README.md", "README.TXT"):
                continue
            destination = engine / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.extractfile(member).read())
    assets = ROOT.parent / "assets" / "doom"
    assets.mkdir(parents=True, exist_ok=True)
    with ZipFile(download(DATA_URL, DATA_SHA)) as archive:
        for name in ("freedoom1.wad", "COPYING.txt", "CREDITS.txt", "CREDITS-MUSIC.txt"):
            data = archive.read(f"freedoom-0.13.0/{name}")
            if name.endswith(".wad") and sha256(data).hexdigest() != WAD_SHA:
                raise RuntimeError("WAD checksum mismatch")
            (assets / name).write_bytes(data)
    (assets / "DOOM-ENGINE-LICENSE.txt").write_bytes((engine / "LICENSE").read_bytes())
    print(f"Prepared doomgeneric {COMMIT} and Freedoom Phase 1 0.13.0 (SHA-256 verified)")


if __name__ == "__main__":
    main()
