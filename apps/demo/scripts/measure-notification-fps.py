#!/usr/bin/env python3
"""Measure visible changes inside the movie rectangle of an Android screen recording.

Uses original presentation timestamps (no fps conversion) and only movie pixels.
Static source scenes naturally lower this metric; it is not a SystemUI draw counter.
Requires ffmpeg, ffprobe and numpy.
"""

import argparse
import json
import pathlib
import subprocess

import numpy as np


def match_source(video, crop, directory, timestamps, source_fps):
    """Cross-check motion against the prepared source, including subtle image changes."""
    def decode(input_args, filters):
        raw = subprocess.check_output([
            "ffmpeg", "-v", "error", *input_args, "-an", "-vf", filters,
            "-fps_mode", "passthrough", "-enc_time_base", "1:1000000", "-f", "rawvideo", "-",
        ])
        return np.frombuffer(raw, np.uint8).reshape(-1, 32 * 24).astype(np.float32)

    scale = "scale=32:24:flags=area,format=gray"
    source = decode(["-framerate", str(source_fps), "-i",
                     str(pathlib.Path(directory) / "frame_%05d.png")], scale)
    frames = decode(["-i", video], f"crop={crop}:exact=1,{scale}")
    times = timestamps - timestamps[0]
    seed = next((i for i, frame in enumerate(frames) if frame.std() > 40), None)
    if seed is None:
        return {"error": "No sufficiently distinctive image for reference matching"}
    seed_index = int(np.mean((source - frames[seed]) ** 2, axis=1).argmin())
    start = seed_index - times[seed] * source_fps
    changes, errors, accepted, previous = [], [], 0, None
    for i, (time, frame) in enumerate(zip(times, frames)):
        expected = round(start + time * source_fps)
        # Allow a batch of timing variation and handle the demo's video loop.
        candidates = np.arange(expected - 75, expected + 76) % len(source)
        distance = np.mean((source[candidates] - frame) ** 2, axis=1)
        best = int(distance.argmin())
        errors.append(float(distance[best]))
        if distance[best] >= 36:
            continue
        accepted += 1
        index = candidates[best]
        if previous is not None and not np.array_equal(source[index], source[previous]):
            changes.append(i)
        previous = index
    return {
        "sourceStartSeconds": round(float(start / source_fps), 3),
        "matchedCapturedFrames": accepted,
        "capturedFrames": len(frames),
        "medianRmsLumaError": round(float(np.sqrt(np.median(errors))), 3),
        "sourceImageChanges": len(changes),
        "sourceChangesPerSecond": round(len(changes) / float(times[-1]), 2),
        "changesPerCompleteSecond": [
            int(sum(second <= times[i] < second + 1 for i in changes))
            for second in range(int(times[-1]))
        ],
        "note": "Nearest source-image cross-check at 32x24, rejecting RMS luma errors of 6 or more.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video")
    parser.add_argument("--crop", required=True, help="Movie rectangle: width:height:x:y")
    parser.add_argument("--threshold", type=float, default=1.5,
                        help="Mean luma difference required to count a new visible image")
    parser.add_argument("--reference-frames", help="Optional prepared frame_00001.png directory")
    parser.add_argument("--source-fps", type=int, default=30)
    args = parser.parse_args()
    metadata = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
        "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", args.video,
    ]))
    timestamps = np.array([float(frame["best_effort_timestamp_time"])
                           for frame in metadata["frames"]])
    pixels = subprocess.check_output([
        "ffmpeg", "-v", "error", "-i", args.video, "-an", "-vf",
        f"crop={args.crop}:exact=1,scale=80:60:flags=area,format=gray",
        "-fps_mode", "passthrough", "-enc_time_base", "1:1000000", "-f", "rawvideo", "-",
    ])
    frames = np.frombuffer(pixels, dtype=np.uint8).reshape(-1, 60, 80)
    if len(frames) != len(timestamps) or len(frames) < 2:
        raise RuntimeError("Recording must contain matching frames and presentation timestamps")
    changes = []
    previous = frames[0].astype(np.int16)
    for i, frame in enumerate(frames[1:], 1):
        current = frame.astype(np.int16)
        if np.abs(current - previous).mean() >= args.threshold:
            changes.append(float(timestamps[i]))
            previous = current
    duration = float(timestamps[-1] - timestamps[0])
    times = np.array(changes)
    windows = [int(np.count_nonzero((times >= second) & (times < second + 1)))
               for second in np.arange(timestamps[0], timestamps[-1] - 1, 1)]
    intervals = np.diff(np.r_[timestamps[0], times, timestamps[-1]])
    result = {
        "recording": args.video, "movieCrop": args.crop,
        "durationSeconds": round(duration, 3), "capturedFrames": len(frames),
        "visibleImageChanges": len(changes),
        "visibleChangesPerSecond": round(len(changes) / duration, 2),
        "changesPerCompleteSecond": windows,
        "p95HoldMs": round(float(np.percentile(intervals, 95) * 1000), 2),
        "longestHoldMs": round(float(intervals.max() * 1000), 2),
        "lumaDifferenceThreshold": args.threshold,
        "note": "Pixel-change measurement, not requested fps; static source frames lower the result.",
    }
    if args.reference_frames:
        result["sourceMatching"] = match_source(
            args.video, args.crop, args.reference_frames, timestamps, args.source_fps,
        )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
