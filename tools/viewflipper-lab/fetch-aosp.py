#!/usr/bin/env python3
import argparse
import base64
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request

PATHS = [
    'core/java/android/widget/ViewFlipper.java',
    'core/java/android/widget/ViewAnimator.java',
    'core/java/android/widget/AdapterViewFlipper.java',
    'core/java/android/widget/AdapterViewAnimator.java',
    'core/java/android/widget/RemoteViews.java',
    'core/java/android/widget/ImageView.java',
    'core/java/android/view/View.java',
    'core/java/android/view/animation/Animation.java',
    'core/java/android/view/animation/AnimationUtils.java',
    'core/java/android/app/Notification.java',
    'core/java/android/service/notification/RateEstimator.java',
    'core/res/res/values/config.xml',
    'services/appwidget/java/com/android/server/appwidget/AppWidgetServiceImpl.java',
    'services/core/java/com/android/server/notification/NotificationManagerService.java',
    'services/core/java/com/android/server/notification/NotificationUsageStats.java',
    'core/java/com/android/internal/widget/remotecompose/core/RemoteComposeBuffer.java',
    'core/java/com/android/internal/widget/remotecompose/core/WireBuffer.java',
    'core/java/com/android/internal/widget/remotecompose/core/CoreDocument.java',
    'core/java/com/android/internal/widget/remotecompose/core/Operations.java',
    'core/java/com/android/internal/widget/remotecompose/core/RemoteContext.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/Header.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/DrawCircle.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/FloatExpression.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/RootContentBehavior.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/Utils.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/utilities/AnimatedFloatExpression.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/PaintData.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/paint/PaintBundle.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/ClickArea.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/RootLayoutComponent.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/LayoutComponent.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/LayoutComponentContent.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/managers/CanvasLayout.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/CanvasContent.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/modifiers/WidthModifierOperation.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/modifiers/HeightModifierOperation.java',
    'core/java/com/android/internal/widget/remotecompose/core/operations/layout/Component.java',
]


def metadata(path, data, ref):
    return {'path': path, 'url': f'https://android.googlesource.com/platform/frameworks/base/+/{ref}/{path}',
            'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ref', default='android-16.0.0_r1')
    parser.add_argument('--out', type=Path, default=Path(__file__).resolve().parents[2] / 'artifacts/viewflipper-limits/aosp')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    def fetch(path):
        url = f'https://android.googlesource.com/platform/frameworks/base/+/{args.ref}/{path}?format=TEXT'
        data = base64.b64decode(urllib.request.urlopen(url, timeout=45).read())
        (args.out / Path(path).name).write_bytes(data)
        return metadata(path, data, args.ref)

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        rows = list(executor.map(fetch, PATHS))
    (args.out / 'manifest.json').write_text(json.dumps({'ref': args.ref, 'files': rows}, indent=2) + '\n')
    print(f'Saved {len(rows)} source files and manifest to {args.out}')


if __name__ == '__main__':
    main()
