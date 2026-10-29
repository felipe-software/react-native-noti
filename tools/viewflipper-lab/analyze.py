#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('video', type=Path)
    parser.add_argument('--crop', default='920:500:80:820')
    parser.add_argument('--start', type=float, default=1)
    parser.add_argument('--end', type=float)
    parser.add_argument('--threshold', type=float, default=4)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    probe = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames', '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(args.video)], check=True, capture_output=True)
    times = [float(frame['best_effort_timestamp_time']) for frame in json.loads(probe.stdout)['frames']]
    width, height = 64, 40
    decoded = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(args.video), '-vf', f'crop={args.crop},scale={width}:{height}', '-fps_mode', 'passthrough', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], check=True, capture_output=True).stdout
    frame_bytes = width * height * 3
    if len(decoded) != len(times) * frame_bytes:
        raise RuntimeError('Decoded frames and original timestamps differ')
    samples = []
    previous = None
    changes = []
    end = args.end if args.end is not None else times[-1]
    for index, time in enumerate(times):
        if not args.start <= time <= end:
            continue
        frame = decoded[index * frame_bytes:(index + 1) * frame_bytes]
        difference = sum(abs(a - b) for a, b in zip(previous, frame)) / frame_bytes if previous is not None else 0
        if previous is not None and difference > args.threshold:
            changes.append(time)
        green = [(offset // 3 % width, offset // 3 // width) for offset in range(0, frame_bytes, 3)
                 if abs(frame[offset] - 65) < 30 and abs(frame[offset + 1] - 201) < 30 and abs(frame[offset + 2] - 170) < 30]
        samples.append({'timeSeconds': time, 'meanAbsoluteRgbDifference': difference,
                        'tealPixelCount': len(green), 'tealMeanX': sum(x for x, _ in green) / len(green) if green else None})
        previous = frame
    if len(samples) < 2:
        raise RuntimeError('Not enough video frames in the requested time window')
    duration = samples[-1]['timeSeconds'] - samples[0]['timeSeconds']
    positions = [sample['tealMeanX'] for sample in samples if sample['tealPixelCount'] >= 2]
    report = {'video': args.video.name, 'crop': args.crop, 'downsample': [width, height], 'threshold': args.threshold,
              'firstTimestampSeconds': samples[0]['timeSeconds'], 'lastTimestampSeconds': samples[-1]['timeSeconds'],
              'windowSeconds': duration, 'decodedFramesInWindow': len(samples), 'significantChanges': len(changes),
              'significantChangesPerSecond': len(changes) / duration, 'changeTimestampsSeconds': changes,
              'tealCentroidMinX': min(positions) if positions else None, 'tealCentroidMaxX': max(positions) if positions else None,
              'samples': samples,
              'interpretation': 'Pixel changes at original recording timestamps; encoder sampling and the threshold can miss display changes. Teal centroids are in downsampled crop coordinates.'}
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key not in ('samples', 'changeTimestampsSeconds')}, indent=2))


if __name__ == '__main__':
    main()
