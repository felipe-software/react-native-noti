#!/usr/bin/env python3
"""Replay matched real notification taps and retain video, traces and posted sequences."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import signal

PKG = 'dev.noti.doomlab'
OBSERVER = 'dev.noti.doomobserver'
RECEIVER = PKG + '/expo.modules.notificationmotion.DoomLabReceiver'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--session', required=True)
    parser.add_argument('--mode', choices=['baseline', 'candidate', 'gif', 'live'], required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--isolated-taps', type=int, choices=range(1, 13), default=12)
    parser.add_argument('--burst-taps', type=int, choices=range(1, 61), default=60)
    args = parser.parse_args()
    def timeout(signum, frame):
        raise TimeoutError('Doom trial exceeded its 90-second limit')
    signal.signal(signal.SIGALRM, timeout)
    signal.alarm(90)
    args.out.mkdir(parents=True, exist_ok=False)
    log = []
    adb = ['adb', '-s', args.serial]
    target = ['--platform', 'android', '--serial', args.serial, '--config', args.config, '--session', args.session]

    def command(parts, capture=True):
        start = time.time()
        result = subprocess.run(list(map(str, parts)), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        log.append({'command': list(map(str, parts)), 'startedEpoch': start, 'finishedEpoch': time.time(), 'exitCode': result.returncode, 'output': result.stdout})
        (args.out / 'commands.json').write_text(json.dumps(log, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(result.stdout)
        return result.stdout

    def agent(*parts):
        return command([args.agent, *parts, *target])

    def broadcast(*parts):
        return command([*adb, 'shell', 'am', 'broadcast', '-n', RECEIVER, *parts])

    def trace():
        raw = subprocess.check_output([*adb, 'exec-out', 'run-as', PKG, 'cat', 'files/doom-events.jsonl'], timeout=10)
        # A running producer can end this read midway through its buffered final JSON line.
        return [json.loads(line) for line in raw.splitlines(keepends=True) if line.endswith(b'\n')]

    # A fresh process resets Doom's C globals, not the server's rate accounting. Leave the
    # same quiet interval for both variants; all publication checks remain in the real service.
    command([*adb, 'shell', 'am', 'force-stop', PKG])
    command([*adb, 'shell', 'cmd', 'statusbar', 'collapse'])
    time.sleep(5)
    connected = command([*adb, 'exec-out', 'run-as', OBSERVER, 'cat', 'files/connected.txt'])
    if not connected.strip().isdigit():
        raise RuntimeError('Observer has not reported a bound connection')
    recording = False
    try:
        agent('record', 'start', str((args.out / 'recording.mp4').resolve()), '--scope', 'device', '--hide-touches', '--quality', 'high')
        recording = True
        agent('open', PKG)
        time.sleep(2)  # Visible binary clock calibrates video PTS to device uptime.
        agent('open', PKG, 'doomlab://' + args.mode)
        time.sleep(4)
        rows = trace()
        if sum(row['kind'] == 'frame' for row in rows) < 30:
            raise RuntimeError('Doom did not produce enough warm-up frames')
        command([*adb, 'shell', 'cmd', 'statusbar', 'expand-notifications'])
        time.sleep(1)
        frame = button = None
        for attempt in range(4):
            raw = agent('snapshot', '--raw', '--force-full')
            nodes = []
            for line in raw.splitlines():
                if line.startswith('{'):
                    try:
                        nodes.append(json.loads(line))
                    except ValueError:
                        pass
            frame = next((row for row in nodes if (row.get('identifier') == PKG + ':id/nm_doom_frame' or
                args.mode in ['gif', 'live'] and row.get('kind') == 'image' and row.get('identifier') == 'android:id/message_text' and row.get('label') == 'Live Doom gameplay' and row['rect']['height'] >= 200 and row['rect']['width'] >= 320)
                and row.get('visibleToUser') and row['rect']['height'] > 0), None)
            button = next((row for row in nodes if row.get('identifier') == PKG + ':id/nm_doom_right' and row.get('visibleToUser') and row['rect']['height'] > 0), None)
            if frame is None and args.mode in ['baseline', 'candidate']:
                # Some SystemUI snapshots omit the changing custom subtree. Its enclosing
                # main column has the exact movie bounds (the status label is GONE).
                # Retain that accessibility anchor; the analyzer still requires valid pixel IDs.
                indexed = {row['index']: row for row in nodes}
                for column in nodes:
                    if column.get('identifier') != 'android:id/notification_main_column' or not column.get('visibleToUser') or column['rect']['height'] < 400:
                        continue
                    parent = column
                    while parent and not parent.get('identifier', '').endswith('/expandableNotificationRow'):
                        parent = indexed.get(parent.get('parentIndex'))
                    if parent is None:
                        continue
                    labels = []
                    for child in nodes:
                        cursor = child
                        while cursor and cursor['index'] != parent['index']:
                            cursor = indexed.get(cursor.get('parentIndex'))
                        if cursor: labels.append(child.get('label', ''))
                    if 'Doom comparison lab' in labels:
                        frame = {**column, 'measurementAnchor': 'movie container; changing custom subtree omitted'}
                        break
            if frame and button:
                break
            by_index = {row['index']: row for row in nodes}

            def ancestor(row, ancestor_index):
                while row:
                    if row['index'] == ancestor_index:
                        return True
                    row = by_index.get(row.get('parentIndex'))
                return False

            expanders = []
            for row in nodes:
                if not row.get('identifier', '').endswith('/expand_button') or row.get('label') not in ['Expand', 'Développer', 'Agrandir', 'Expandir'] or not row.get('visibleToUser'):
                    continue
                parent = row
                while parent and not parent.get('identifier', '').endswith('/expandableNotificationRow'):
                    parent = by_index.get(parent.get('parentIndex'))
                if parent is None:
                    # HyperOS omits the AOSP row resource ID. Limit the search to the local
                    # header ancestors so another app's expander cannot match the shade root.
                    parent = by_index.get(row.get('parentIndex'))
                    for _ in range(4):
                        if parent is None or any('Doom' in child.get('label', '') and ancestor(child, parent['index']) for child in nodes):
                            break
                        parent = by_index.get(parent.get('parentIndex'))
                if parent and any('Doom' in child.get('label', '') and ancestor(child, parent['index']) for child in nodes):
                    expanders.append(row)
            if not expanders:
                break
            # Android may auto-bundle both rows. Expand that group, then any collapsed child,
            # using fresh tree refs only. The commands log and video retain every setup action.
            expander = min(expanders, key=lambda row: row['depth'])
            agent('click', '@' + expander['ref'])
            agent('wait', '500')
        if not frame or not button:
            raise RuntimeError('Expanded Doom frame and control were not found in the live tree')
        (args.out / 'layout.json').write_text(json.dumps({'frame': frame, 'button': button}, indent=2) + '\n')
        for name, count, interval in [('isolated', args.isolated_taps, 1200), ('burst', args.burst_taps, 100)]:
            broadcast('--es', 'action', 'mark', '--es', 'name', name, '--es', 'edge', 'begin', '--ei', 'offered', count)
            # The stable resource ID comes from the fresh tree above. The same sequence is
            # dispatched by the device backend for both variants; no screenshot coordinates.
            agent('click', 'id="' + PKG + ':id/nm_doom_right"', '--count', str(count), '--interval-ms', str(interval), '--hold-ms', '40', '--timeout', '30000')
            broadcast('--es', 'action', 'mark', '--es', 'name', name, '--es', 'edge', 'end', '--ei', 'offered', count)
            time.sleep(2)
        # Stop the stream, let its pending notification settle, and then read the observer.
        # Reading while the producer is still posting would misclassify its final in-flight
        # update as missing. Pause also preserves the displayed image until evidence is saved.
        broadcast('--es', 'action', 'pause')
        time.sleep(1.5)
        broadcast('--es', 'action', 'flush')
        for pkg, remote, local in [(PKG, 'doom-events.jsonl', 'events.jsonl'), (OBSERVER, 'posted.jsonl', 'posted.jsonl')]:
            data = subprocess.check_output([*adb, 'exec-out', 'run-as', pkg, 'cat', 'files/' + remote], timeout=10)
            (args.out / local).write_bytes(data)
        broadcast('--es', 'action', 'stop')
        agent('record', 'stop')
        recording = False
        notifications = command([*adb, 'logcat', '-d', '-v', 'epoch', '-s', 'NotificationService'])
        own = [line for line in notifications.splitlines() if PKG in line]
        (args.out / 'notification-service.txt').write_text('\n'.join(own) + '\n')
        print(json.dumps({'mode': args.mode, 'out': str(args.out), 'complete': True}))
    finally:
        signal.alarm(0)
        if recording:
            agent('record', 'stop')
        # Preserve only this app's notification/service state changes.
        command([*adb, 'shell', 'am', 'force-stop', PKG])


if __name__ == '__main__':
    main()
