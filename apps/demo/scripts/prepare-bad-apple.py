#!/usr/bin/env python3
"""Download Bad Apple once and extract the complete silent 30 fps frame sequence."""

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "assets" / "bad-apple"
SOURCE = "https://raw.githubusercontent.com/NPCat/bad-apple-bot/main/bad_apple.mp4"
SOURCE_SHA256 = "a7a9abf52f9ea3d8b46d728b8fa557278162d2a3d03cdecea55ea2c0034e857c"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, help="Use an already downloaded MP4")
    parser.add_argument("--force", action="store_true", help="Regenerate existing frames")
    args = parser.parse_args()
    manifest_path = DESTINATION / "manifest.json"
    if not args.force and manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if len(list((DESTINATION / "frames").glob("frame_*.png"))) == manifest["frameCount"]:
            print(f"Bad Apple ready: {manifest['frameCount']} frames at {manifest['sourceFps']} fps")
            return
    if not shutil.which("ffmpeg"):
        parser.error("ffmpeg must be installed and on PATH")

    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bad-apple-", dir=DESTINATION.parent) as directory:
        staging = pathlib.Path(directory)
        video = args.source.resolve() if args.source else staging / "source.mp4"
        if not args.source:
            print(f"Downloading {SOURCE}", flush=True)
            urllib.request.urlretrieve(SOURCE, video)
        source_hash = hashlib.sha256(video.read_bytes()).hexdigest()
        if source_hash != SOURCE_SHA256:
            raise RuntimeError("Source checksum differs from the Bad Apple version used by this demo")
        frames = staging / "frames"
        frames.mkdir()
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "warning", "-i", str(video),
            "-an", "-vf", "fps=30,scale=160:120:flags=lanczos,format=gray",
            "-threads", "2", "-start_number", "1", str(frames / "frame_%05d.png"),
        ], check=True)
        count = len(list(frames.glob("frame_*.png")))
        if count < 2:
            raise RuntimeError("The source did not produce a playable frame sequence")
        manifest = {
            "sourceUrl": SOURCE if not args.source else "local MP4 (see sourceSha256)",
            "sourceSha256": source_hash,
            "assetPath": "bad-apple/frames", "frameCount": count,
            "sourceFps": 30, "width": 160, "height": 120,
            "durationSeconds": count / 30,
        }
        if DESTINATION.exists():
            shutil.rmtree(DESTINATION)
        DESTINATION.mkdir()
        shutil.move(str(frames), str(DESTINATION / "frames"))
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"Prepared {count} frames ({count / 30:.2f}s) in {DESTINATION}")


if __name__ == "__main__":
    main()
