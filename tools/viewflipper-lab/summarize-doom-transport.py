#!/usr/bin/env python3
"""Retain raw transport probes and summarize the explicitly limited measurements."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('runs', nargs='+', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = {'date': '2026-10-28', 'runs': [], 'scope': 'Isolated warm transport microbenchmark. No end-to-end Doom control latency or SystemUI rendering improvement was measured.',
              'productionDoomChanged': False}
    for path in args.runs:
        data = json.loads(path.read_text())
        assert data['complete'] and data['providerProcessIsSeparate']
        assert data['providerPid'] != data['hostPid']
        summaries = {}
        for mode in ['bitmap', 'bmp-memfd-uri']:
            rows = [row for row in data['samples'] if row['mode'] == mode]
            assert len(rows) == data['samplesPerMode'] == 80
            summaries[mode] = {}
            for key in ['prepareMs', 'remoteViewsAndParcelMs', 'inflateMs', 'totalMs']:
                values = sorted(row[key] for row in rows)
                summaries[mode][key] = {'median': statistics.median(values),
                                        'p90': values[int(.9 * (len(values) - 1))]}
        result['runs'].append({'input': path.name, 'rawSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                               'summary': summaries, 'observation': data})
    source = ROOT / 'artifacts/doom-optimization/aosp/manifest.json'
    if source.exists():
        result['additionalSource'] = json.loads(source.read_text())
    result['batchAgeModel'] = {'scope': 'Arithmetic for steady 33 ms snapshots, before delivery; not a measured input-latency improvement.',
                               'currentFrames': 15, 'candidateFrames': 10, 'intervalMs': 33,
                               'currentOldestAgeMs': 462, 'candidateOldestAgeMs': 297,
                               'currentBatchPeriodMs': 495, 'candidateBatchPeriodMs': 330,
                               'strictModelSteadyMinPeriodMs': 1000 / (.7 * 4.5)}
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'trials': len(result['runs']), 'samplesPerModePerTrial': 80,
                      'bitmapMedianTotalMs': [run['summary']['bitmap']['totalMs']['median'] for run in result['runs']],
                      'uriMedianTotalMs': [run['summary']['bmp-memfd-uri']['totalMs']['median'] for run in result['runs']]}))


if __name__ == '__main__':
    main()
