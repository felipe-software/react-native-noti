#!/usr/bin/env python3
"""Decode lab-only image IDs from real SystemUI video and join native input/frame traces."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess


def quantiles(values):
    if not values:
        return {'count': 0, 'median': None, 'p90': None}
    values = sorted(values)
    return {'count': len(values), 'median': statistics.median(values),
            'p90': values[int(.9 * (len(values) - 1))], 'min': values[0], 'max': values[-1]}


def decode(raw, width, y, count):
    values = []
    for cell in range(5 + count + 5):
        at = ((y + 3) * width + cell * 6 + 3) * 3
        values.append(sum(raw[at:at + 3]) / 3)
    bits = [int(value > 120) for value in values]
    if bits[:5] != [1, 0, 1, 1, 0]:
        return None
    if any((value < 170 if bit else value > 80) for bit, value in zip(bits, values)):
        return None
    value = sum(bit << i for i, bit in enumerate(bits[5:5 + count]))
    check = 0
    for shift in range(0, count, 5):
        check ^= value >> shift
    if [((check >> i) & 1) for i in range(5)] != bits[5 + count:]:
        return None
    return value


def load_lines(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('trial', type=Path)
    args = parser.parse_args()
    folder = args.trial
    events = load_lines(folder / 'events.jsonl')
    live_gif = any(row['kind'] == 'gif-write' for row in events)
    observed = load_lines(folder / 'posted.jsonl')
    layout = json.loads((folder / 'layout.json').read_text())['frame']['rect']
    clock = next(row for row in events if row['kind'] == 'clock-rect')
    frames = {row['frame']: row for row in events if row['kind'] == 'frame'}
    video = folder / 'recording.mp4'
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
        '-show_frames', '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(video)], timeout=20))
    times = [float(row['best_effort_timestamp_time']) for row in probe['frames']]
    scale = min(layout['width'] / 320, layout['height'] / 200)
    width, height = round(320 * scale), round(200 * scale)
    left = round(layout['x'] + (layout['width'] - width) / 2)
    top = round(layout['y'] + layout['height'] - height)
    filters = (f'[0:v]split[c][g];[c]crop=252:12:{clock["x"]}:{clock["y"]}[clock];'
               f'[g]crop={width}:{height}:{left}:{top},scale=320:200:flags=area,crop=180:6:0:0,pad=252:12:0:0[game];'
               '[clock][game]vstack=inputs=2[out]')
    if live_gif:
        filters = (f'[0:v]split[c][g];[c]crop=252:12:{clock["x"]}:{clock["y"]}[clock];'
                   f'[g]crop={width}:{height}:{left}:{top},scale=320:200:flags=area,split[t][b];'
                   '[t]crop=180:6:0:0,pad=252:12:0:0[game];'
                   '[b]crop=180:6:0:194,pad=252:12:0:0[bottom];'
                   '[clock][game][bottom]vstack=inputs=3[out]')
    conversion = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-filter_complex', filters,
        '-map', '[out]', '-fps_mode', 'passthrough', '-enc_time_base', '1:1000000', '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-'],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=True)
    raw_frames = conversion.stdout
    frame_bytes = 252 * (36 if live_gif else 24) * 3
    if len(raw_frames) != len(times) * frame_bytes:
        raise RuntimeError('Video timestamp and decoder frame counts differ')
    decoded = []
    clock_offsets = []
    mismatched_markers = unverified_bottom = 0
    minimum = min(row['uptimeMs'] for row in events) - 5000
    maximum = max(row['uptimeMs'] for row in events) + 5000
    for index, pts in enumerate(times):
        raw = raw_frames[index * frame_bytes:(index + 1) * frame_bytes]
        value = decode(raw, 252, 0, 32)
        if value is not None:
            value += round((minimum - value) / 2 ** 32) * 2 ** 32
            if minimum <= value <= maximum:
                clock_offsets.append(value - pts * 1000)
        game = decode(raw, 252, 12, 20)
        if game in frames:
            if live_gif:
                bottom = decode(raw, 252, 24, 20)
                if bottom != game:
                    if bottom is None: unverified_bottom += 1
                    else: mismatched_markers += 1
                    continue
            decoded.append({'pts': pts, 'frame': game})
    if len(clock_offsets) < 5 or len(decoded) < 100:
        raise RuntimeError(f'Insufficient verified markers: clocks={len(clock_offsets)}, images={len(decoded)}; crop={(width, height, left, top)}')
    offset = statistics.median(clock_offsets)
    for row in decoded:
        row['uptimeMs'] = row['pts'] * 1000 + offset
    decoded = [row for row in decoded if row['uptimeMs'] >= frames[row['frame']]['uptimeMs'] - 50]
    # Independent visible-motion measure, excluding both marker strips.
    body_result = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-vf',
        f'crop={width}:{height}:{left}:{top},scale=32:20:flags=area,crop=32:18:0:1',
        '-fps_mode', 'passthrough', '-enc_time_base', '1:1000000', '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-'],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=True)
    body_size = 32 * 18 * 3
    assert len(body_result.stdout) == len(times) * body_size
    changes = []
    previous = body_result.stdout[:body_size]
    for index in range(1, len(times)):
        current = body_result.stdout[index * body_size:(index + 1) * body_size]
        difference = sum(abs(a-b) for a,b in zip(previous,current)) / body_size
        if difference > 2: changes.append(times[index] * 1000 + offset)
        previous = current
    phases = []
    for name in ['isolated', 'burst']:
        begin = next(row for row in events if row['kind'] == 'phase' and row['name'] == name and row['edge'] == 'begin')
        end = next(row for row in events if row['kind'] == 'phase' and row['name'] == name and row['edge'] == 'end')
        inputs = [row for row in events if row['kind'] == 'input' and begin['uptimeMs'] <= row['uptimeMs'] <= end['uptimeMs']]
        responses = []
        for item in inputs:
            eligible = next((row for row in decoded if row['uptimeMs'] >= item['uptimeMs'] and
                frames[row['frame']]['discardedInput'] < item['input'] <= frames[row['frame']]['appliedInput']), None)
            responses.append({'input': item['input'], 'receivedAt': item['uptimeMs'],
                'firstRecordedResponseMs': None if eligible is None else eligible['uptimeMs'] - item['uptimeMs']})
        samples = [row for row in decoded if begin['uptimeMs'] <= row['uptimeMs'] <= end['uptimeMs']]
        native_ids = [frames[row['frame']]['nativeFrame'] for row in samples]
        native_changes = [b['uptimeMs'] for a,b in zip(samples,samples[1:])
            if frames[a['frame']]['nativeFrame'] != frames[b['frame']]['nativeFrame']]
        motion = [at for at in changes if begin['uptimeMs'] <= at <= end['uptimeMs']]
        duration = (end['uptimeMs'] - begin['uptimeMs']) / 1000
        phases.append({'name': name, 'offeredTaps': begin['offered'], 'receivedTaps': len(inputs),
            'phaseDurationSeconds': duration,
            'recordedDistinctNativeFramesPerSecond': len(set(native_ids)) / duration,
            'recordedNativeFrameChangeGapsMs': quantiles([b-a for a,b in zip(native_changes,native_changes[1:])]),
            'recordedBodyChangesPerSecond': len(motion) / duration,
            'recordedBodyChangeGapsMs': quantiles([b-a for a,b in zip(motion,motion[1:])]),
            'serviceInputToRecordedStateMs': quantiles([row['firstRecordedResponseMs'] for row in responses if row['firstRecordedResponseMs'] is not None]),
            'recordedNativeFrameAgeMs': quantiles([row['uptimeMs'] - frames[row['frame']]['renderedAtNs'] / 1e6 for row in samples]),
            'recordedSampledFrameAgeMs': quantiles([row['uptimeMs'] - frames[row['frame']]['uptimeMs'] for row in samples]),
            'receivedInputGapsMs': quantiles([b['uptimeMs'] - a['uptimeMs'] for a, b in zip(inputs, inputs[1:])]),
            'responses': responses})
    notifications = [row for row in events if row['kind'] == 'notify']
    matched = []
    missing = []
    for row in notifications:
        # Early telemetry wrote its timestamp after setting notification extras. Permit only
        # that documented 0–2 ms gap, with an unambiguous sequence/time pair; later runs carry
        # the exact sender timestamp. Never match historical trials by sequence alone.
        sent = row.get('sentUptimeMs', row['uptimeMs'])
        matches = [item for item in observed if item['sequence'] == row['sequence'] and
            (item['sentUptimeMs'] == sent if 'sentUptimeMs' in row else 0 <= sent - item['sentUptimeMs'] <= 2)]
        if len(matches) > 1:
            raise RuntimeError('Ambiguous observer match')
        if matches:
            matched.append(matches[0])
        else:
            missing.append(row['sequence'])
    result = {'mode': next(row for row in events if row['kind'] == 'frame')['mode'],
        'scope': 'Real SystemUI recording, timed from service input receipt to the first captured image whose native rendered simulation includes that input. This excludes touch dispatch time; missed taps are counted separately. Video clock calibration and capture sampling add uncertainty.',
        'calibration': {'samples': len(clock_offsets), 'offsetMs': offset, 'offsetDistribution': quantiles(clock_offsets)},
        'videoFrames': len(times), 'verifiedGameSamples': len(decoded), 'gameDrawCrop': [width, height, left, top],
        'phases': phases, 'notifyCalls': len(notifications), 'postedSequences': len(matched), 'missingPostedSequences': missing,
        'notifyToObserverMs': quantiles([r['receivedUptimeMs'] - r['sentUptimeMs'] for r in matched]),
        'backwardRecordedFrameTransitions': sum(b['frame'] < a['frame'] for a, b in zip(decoded, decoded[1:])),
        'mismatchedTopBottomMarkers': mismatched_markers if live_gif else None,
        'unverifiedBottomMarkers': unverified_bottom if live_gif else None,
        'bodyMotionMethod': 'Actual recorded game pixels excluding marker rows; 32x18 RGB, mean absolute adjacent-sample difference >2/255. Captured changes are not a display refresh-rate measurement.',
        'decoded': decoded}
    (folder / 'analysis.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: value for key, value in result.items() if key not in ['decoded', 'phases']}))
    for phase in phases:
        print(json.dumps({key: value for key, value in phase.items() if key != 'responses'}))


if __name__ == '__main__':
    main()
