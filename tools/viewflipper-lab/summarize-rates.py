#!/usr/bin/env python3
import argparse
import json
import statistics
from pathlib import Path


def summarize(sender, events):
    result = []
    for probe in sender['results']:
        if not probe['name'].startswith('notification/rate-') or probe['outcome'] != 'accepted':
            continue
        phase = probe['value']
        rate = phase['requestedRate']
        offered = {(row['sequence'], row['uptimeMs']) for row in phase['offered']}
        received = [row for row in events if row['id'] == 10000 + rate and (row['sequence'], row['sentUptimeMs']) in offered]
        unique = {row['sequence'] for row in received}
        delays = sorted(row['receivedUptimeMs'] - row['sentUptimeMs'] for row in received)
        result.append({
            'requestedPerSecond': rate,
            'offered': len(offered),
            'received': len(received),
            'uniqueSequences': len(unique),
            'lastReceivedSequence': max(unique) if unique else None,
            'lastActiveSequence': phase['lastAcceptedSequence'],
            'postedToListenerMedianMs': statistics.median(delays) if delays else None,
            'postedToListenerP90Ms': delays[int(.9 * (len(delays) - 1))] if delays else None,
            'missingSequences': [sequence for sequence, _ in sorted(offered) if sequence not in unique],
        })
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('sender', type=Path)
    parser.add_argument('listener', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    sender = json.loads(args.sender.read_text())
    if not sender['complete']:
        raise RuntimeError('Sender experiment is incomplete')
    events = [json.loads(line) for line in args.listener.read_text().splitlines() if line.strip()]
    rows = summarize(sender, events)
    if len(rows) != 4:
        raise RuntimeError('Expected four completed rate phases')
    args.out.write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps([{key: value for key, value in row.items() if key != 'missingSequences'} for row in rows], indent=2))


if __name__ == '__main__':
    main()
