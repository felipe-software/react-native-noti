#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(path):
    return json.loads(path.read_text())


def summary(report):
    if not report['complete']:
        raise RuntimeError('Native probe run is incomplete')
    return {
        'sdk': report['sdk'], 'fingerprint': report['fingerprint'],
        'model': report.get('model'), 'manufacturer': report.get('manufacturer'),
        'appDisplayMetrics': {key: report[key] for key in ['widthPixels', 'heightPixels', 'density']},
        'probeCount': len(report['results']),
        'outcomes': {outcome: sum(row['outcome'] == outcome for row in report['results']) for outcome in ['accepted', 'rejected', 'unsupported']},
        'expectationMismatches': [row['name'] for row in report['results'] if not row['matchesExpectation']],
    }


def compact_recording(path):
    data = load(path)
    return {key: value for key, value in data.items() if key not in ['samples', 'changeTimestampsSeconds']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--emulator', type=Path, default=ROOT / 'artifacts/viewflipper-limits/emulator-compatibility-run.json')
    parser.add_argument('--xiaomi', type=Path, default=ROOT / 'artifacts/viewflipper-limits/xiaomi/native-results.json')
    parser.add_argument('--xiaomi-artifacts', type=Path, default=ROOT / 'artifacts/viewflipper-limits/xiaomi')
    args = parser.parse_args()
    emulator = load(args.emulator)
    xiaomi = load(args.xiaomi)
    original = load(ROOT / 'docs/viewflipper-limits-results.json')
    baseline = {row['name']: row for row in emulator['results']}
    phone = {row['name']: row for row in xiaomi['results']}
    common = sorted(baseline.keys() & phone.keys())
    differences = [{'name': name, 'emulator': baseline[name]['outcome'], 'xiaomi': phone[name]['outcome']} for name in common if baseline[name]['outcome'] != phone[name]['outcome']]
    timing = []
    for name in common:
        if name.startswith('timing/'):
            timing.append({'intervalMs': baseline[name]['value']['requestedIntervalMs'],
                           'emulatorChangesPerSecond': baseline[name]['value']['changesPerSecond'],
                           'xiaomiChangesPerSecond': phone[name]['value']['changesPerSecond']})
    timing.sort(key=lambda row: row['intervalMs'])
    recording_files = ['notification-1ms-analysis.json', 'notification-33ms-analysis.json', 'uri-analysis.json', 'after-exit-verified-analysis.json']
    observations = {name: compact_recording(args.xiaomi_artifacts / name) for name in recording_files}
    xiaomi['delivery'] = load(args.xiaomi_artifacts / 'rate-summary.json')
    xiaomi['recordingObservations'] = observations
    xiaomi['producerExitCheck'] = load(args.xiaomi_artifacts / 'producer-death.json')
    xiaomi['deviceEvidence'] = load(args.xiaomi_artifacts / 'device.json')
    xiaomi['apkBuild'] = load(args.xiaomi_artifacts / 'apk-build.json')
    xiaomi['pinRequest'] = load(args.xiaomi_artifacts / 'pin-request.json')
    xiaomi['notificationServiceLog'] = (args.xiaomi_artifacts / 'notification-service-errors.txt').read_text().splitlines()
    if (args.xiaomi_artifacts / 'cleanup.json').exists():
        xiaomi['cleanup'] = load(args.xiaomi_artifacts / 'cleanup.json')
    xiaomi['scope'] = 'Physical Redmi Note 12 Pro 5G, Android 14 / HyperOS 2; private widget host tested. Pin API accepted a request, but no launcher placement was observed.'
    (ROOT / 'docs/viewflipper-xiaomi-results.json').write_text(json.dumps(xiaomi, indent=2) + '\n')
    report = {
        'date': '2026-10-28',
        'emulator': summary(emulator), 'xiaomi': summary(xiaomi),
        'sameOutcomeCount': len(common) - len(differences), 'outcomeDifferences': differences,
        'localChildSelectionTiming': timing,
        'emulatorDelivery': original['delivery'], 'xiaomiDelivery': xiaomi['delivery'],
        'xiaomiConfiguredResources': xiaomi['deviceEvidence']['resourceLookups'],
        'recordingChangesPerSecond': {
            'emulator33ms': original['recordingObservations']['notification-33ms-analysis.json']['significantChangesPerSecond'],
            'xiaomi33ms': observations['notification-33ms-analysis.json']['significantChangesPerSecond'],
            'emulator1ms': original['recordingObservations']['notification-1ms-analysis.json']['significantChangesPerSecond'],
            'xiaomi1ms': observations['notification-1ms-analysis.json']['significantChangesPerSecond'],
            'xiaomiAfterExit33ms': observations['after-exit-verified-analysis.json']['significantChangesPerSecond'],
        },
        'scope': 'Different Android versions, OEM software, hardware and refresh rates; this comparison does not isolate an OEM-only effect or establish universal FPS/rate ceilings.',
    }
    (ROOT / 'docs/viewflipper-device-comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'sameOutcomes': report['sameOutcomeCount'], 'outcomeDifferences': differences,
                      'xiaomiMismatches': report['xiaomi']['expectationMismatches']}, indent=2))


if __name__ == '__main__':
    main()
