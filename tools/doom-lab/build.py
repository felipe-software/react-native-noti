#!/usr/bin/env python3
"""Compile the real Doom service/engine plus an archived baseline, without Expo."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MODULE = ROOT / 'packages/react-native-noti/android/src/main'
BUILD = HERE / 'build'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
JAVA = Path(os.environ.get('JAVA_HOME', str(Path.home() / '.sdkman/candidates/java/current')))
TOOLS = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
BASELINE = 'b056478e9b930fd705ead02f3f1f7844bbd23151'
ENV = {**os.environ, 'JAVA_HOME': str(JAVA), 'PATH': str(JAVA / 'bin') + os.pathsep + os.environ['PATH']}


def run(*args):
    subprocess.run([str(arg) for arg in args], env=ENV, check=True, cwd=ROOT)


def jar(group, name, version):
    paths = list((CACHE / group / name / version).glob('*/*.jar'))
    if len(paths) != 1:
        raise RuntimeError(f'Expected one cached {group}:{name}:{version} JAR, got {paths}')
    return paths[0]


def instrument(text, mode):
    # Identical silent initial state, without toggling audio after startup or changing the
    # device's global audio settings. Production defaults are not changed by the harness.
    text = text.replace('"muted" to false', '"muted" to true')
    text = text.replace('DoomEngine.render()?.let', 'DoomEngine.renderState(DoomLabTrace.frameState)?.let')
    text = text.replace('private val frameState = LongArray(5)', 'private val frameState = DoomLabTrace.frameState')
    text = text.replace('DoomEngine.key(key, true)', 'DoomLabTrace.nativeKey(key, true)')
    text = text.replace('DoomEngine.keyTracked(key, true)', 'DoomLabTrace.nativeKey(key, true)')
    batch_line = next(line for line in text.splitlines() if 'post(notification("Freedoom · tap controls to play",' in line)
    replacements = [
        ('    val frame = encode(pixels)', f'    DoomLabTrace.frame("{mode}", pixels, next)\n    val frame = encode(pixels)'),
        (batch_line, f'    DoomLabTrace.batch("{mode}", frameBatch, flushed, lastInputAt)\n' + batch_line),
        ('          lastInputAt = now', f'          DoomLabTrace.event("input", "strategy" to "{mode}", "action" to action, "input" to DoomLabTrace.inputId)\n          lastInputAt = now'),
    ]
    for before, after in replacements:
        if text.count(before) != 1:
            raise RuntimeError(f'Instrumentation anchor changed: {before}')
        text = text.replace(before, after)
    if mode == 'candidate':
        before = '          val frame = DoomFrames.add(this@DoomService, pixels)'
        assert text.count(before) == 1
        text = text.replace(before, before + '\n          DoomGifTransport.push(this@DoomService, pixels)')
        text = text.replace('    DoomFrames.clear()', '    DoomFrames.clear()\n    DoomGifTransport.close()')
    post_line = next(line for line in text.splitlines() if 'private fun post(notification: Notification' in line)
    notification_id = 'id' if 'id: Int' in post_line else '16035'
    guard = '\n    if (id == ID && DoomGifTransport.enabled) { DoomGifTransport.show(this); return }' if mode == 'candidate' else ''
    text = text.replace(post_line, post_line + guard + f'\n    DoomLabTrace.notify("{mode}", notification, {notification_id})')
    return text


def dex_apk(base, classes, output, extra=()):
    dex = BUILD / (output.stem + '-dex')
    shutil.rmtree(dex, ignore_errors=True); dex.mkdir()
    run(TOOLS / 'd8', '--lib', ANDROID, '--min-api', '31', '--output', dex, *classes, *extra)
    with zipfile.ZipFile(base, 'a', zipfile.ZIP_DEFLATED) as apk:
        for path in dex.glob('*.dex'):
            apk.write(path, path.name)
    aligned = output.with_suffix('.aligned.apk')
    run(TOOLS / 'zipalign', '-f', '4', base, aligned)
    run(TOOLS / 'apksigner', 'sign', '--ks', BUILD / 'debug.keystore', '--ks-pass', 'pass:android', '--out', output, aligned)
    run(TOOLS / 'apksigner', 'verify', output)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', default='arm64-v8a')
    parser.add_argument('--baseline-ref', default=BASELINE)
    parser.add_argument('--live-slots', type=int, choices=[4, 16, 32], help='Lab-only animation slot-count override')
    parser.add_argument('--live-delay-ms', type=int, choices=[20, 30, 40, 50], help='Lab-only GIF cadence override')
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    for name in ['res', 'generated', 'classes', 'observer-classes']:
        shutil.rmtree(BUILD / name, ignore_errors=True); (BUILD / name).mkdir()
    for rel in ['layout/nm_doom.xml', 'layout/nm_doom_frame.xml', 'values/doom_styles.xml',
                'animator/nm_doom_control_press.xml', 'drawable/nm_doom_control_background.xml', 'drawable/nm_small_icon.xml']:
        target = BUILD / 'res' / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MODULE / 'res' / rel, target)
    for path in (MODULE / 'res/layout').glob('nm_doom_controls*.xml'):
        shutil.copy2(path, BUILD / 'res/layout' / path.name)
    baseline_layout = subprocess.check_output(['git', 'show', args.baseline_ref + ':packages/react-native-noti/android/src/main/res/layout/nm_doom.xml'], cwd=ROOT)
    (BUILD / 'res/layout/nm_doom_baseline.xml').write_bytes(baseline_layout)
    original = subprocess.check_output(['git', 'show', args.baseline_ref + ':packages/react-native-noti/android/src/main/java/expo/modules/notificationmotion/DoomService.kt'], cwd=ROOT).decode()
    current = (MODULE / 'java/expo/modules/notificationmotion/DoomService.kt').read_text()
    baseline = original
    for name in ['DoomService', 'DoomController', 'DoomFrames', 'DoomNotificationRate']:
        baseline = re.sub(r'\b' + name + r'\b', 'Baseline' + name, baseline)
    baseline = baseline.replace('".notificationmotion.doom"', '".notificationmotion.doom.baseline"')
    baseline = re.sub(r'R\.layout\.nm_doom\b', 'R.layout.nm_doom_baseline', baseline)
    (BUILD / 'generated/BaselineDoomService.kt').write_text(instrument(baseline, 'baseline'))
    (BUILD / 'generated/DoomService.kt').write_text(instrument(current, 'candidate'))
    live = (MODULE / 'java/expo/modules/notificationmotion/DoomLiveFrames.kt').read_text()
    if args.live_slots is not None:
        live, count = re.subn(r'private const val FRAME_COUNT = \d+', 'private const val FRAME_COUNT = ' + str(args.live_slots), live)
        assert count == 1, 'Live frame-count anchor changed'
    if args.live_delay_ms is not None:
        before = '    if (frameBytes == 0) frameBytes = encoded.size'
        assert live.count(before) == 1, 'Live encoded-frame anchor changed'
        live = live.replace(before, '    encoded[4] = ' + str(args.live_delay_ms // 10) + '\n' + before)
    live = live.replace('private data class EncodedFrame(val bytes: ByteArray)', 'private data class EncodedFrame(val bytes: ByteArray, val frameId: Int)')
    live = live.replace('    val encoded = checkNotNull(DoomGifEncoder.encode(pixels, FRAME_BUDGET))',
        '    val began = SystemClock.elapsedRealtimeNanos()\n    repeat(6) { row -> System.arraycopy(pixels, row * 320, pixels, (194 + row) * 320, 180) }\n    val encoded = checkNotNull(DoomGifEncoder.encode(pixels, FRAME_BUDGET))')
    live = live.replace('    latest = EncodedFrame(encoded)', '    latest = EncodedFrame(encoded, DoomLabTrace.imageId(pixels))\n    DoomLabTrace.event("gif-write", "frame" to DoomLabTrace.imageId(pixels), "sampleStep" to DoomGifEncoder.sampleStep(), "encodedBytes" to DoomGifEncoder.encodedSize(), "durationNs" to SystemClock.elapsedRealtimeNanos() - began)')
    live = live.replace('    val initial = latest ?: throw FileNotFoundException', '    DoomLabTrace.event("gif-open")\n    val initial = latest ?: throw FileNotFoundException')
    live = live.replace('      val flags = DoomGifEncoder.direct(descriptor.fd)',
        '      if (DoomLabTrace.forceLiveFailure) throw IOException("Injected live-reader failure")\n      val flags = DoomGifEncoder.direct(descriptor.fd)')
    live = live.replace('val frame = frozen[index] ?: (latest ?: initial).also { frozen[index] = it }',
        'val frame = frozen[index] ?: (latest ?: initial).also { frozen[index] = it; DoomLabTrace.event("gif-latch", "index" to index, "frame" to it.frameId) }')
    (BUILD / 'generated/DoomLiveFrames.kt').write_text(live)
    provider = (MODULE / 'java/expo/modules/notificationmotion/DoomFrameProvider.kt').read_text()
    (BUILD / 'generated/BaselineDoomFrameProvider.kt').write_text(provider.replace('DoomFrameProvider', 'BaselineDoomFrameProvider').replace('DoomFrames', 'BaselineDoomFrames'))
    cmake = SDK / 'cmake/3.22.1/bin/cmake'
    ndk = SDK / 'ndk/27.1.12297006'
    native = BUILD / 'native' / args.abi
    run(cmake, '-S', MODULE / 'cpp', '-B', native, '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + str(cmake.parent / 'ninja'),
        '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI=' + args.abi, '-DANDROID_PLATFORM=android-31', '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release')
    run(cmake, '--build', native, '-j', '6')
    triple = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7a-linux-androideabi',
              'x86': 'i686-linux-android', 'x86_64': 'x86_64-linux-android'}[args.abi]
    clang = ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin' / (triple + '31-clang')
    run(clang, '-O3', '-fPIC', '-shared', HERE / 'src/gif_probe.c', '-o', BUILD / 'libdoom_gif_probe.so')
    run(TOOLS / 'aapt2', 'compile', '--dir', BUILD / 'res', '-o', BUILD / 'resources.zip')
    run(TOOLS / 'aapt2', 'link', '-I', ANDROID, '--manifest', HERE / 'AndroidManifest.xml', '--custom-package',
        'expo.modules.notificationmotion', '--java', BUILD / 'generated', '-o', BUILD / 'base.apk', BUILD / 'resources.zip')
    run(JAVA / 'bin/javac', '-source', '8', '-target', '8', '-classpath', ANDROID, '-d', BUILD / 'classes', *list((BUILD / 'generated').rglob('*.java')))
    compiler = [jar('org.jetbrains.kotlin', name, '2.1.20') for name in ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlin-daemon-embeddable']]
    compiler += [jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), jar('org.jetbrains', 'annotations', '13.0')]
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.20')
    classpath = os.pathsep.join(map(str, [ANDROID, BUILD / 'classes', stdlib]))
    kotlin = [*list((BUILD / 'generated').glob('*.kt')), HERE / 'src/DoomLabActivity.kt',
              *list((HERE / 'src').glob('*Probe.kt')),
              MODULE / 'java/expo/modules/notificationmotion/DoomEngine.kt', MODULE / 'java/expo/modules/notificationmotion/DoomFrameProvider.kt',
              MODULE / 'java/expo/modules/notificationmotion/DoomLiveFrameProvider.kt']
    run(JAVA / 'bin/java', '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '1.8', '-classpath', classpath, '-d', BUILD / 'classes', *kotlin)
    with zipfile.ZipFile(BUILD / 'base.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
        apk.write(native / 'libnoti_doom.so', 'lib/' + args.abi + '/libnoti_doom.so')
        apk.write(BUILD / 'libdoom_gif_probe.so', 'lib/' + args.abi + '/libdoom_gif_probe.so')
        apk.write(MODULE / 'assets/doom/freedoom1.wad', 'assets/doom/freedoom1.wad')
    if not (BUILD / 'debug.keystore').exists():
        run(JAVA / 'bin/keytool', '-genkeypair', '-keystore', BUILD / 'debug.keystore', '-storepass', 'android', '-keypass', 'android', '-alias', 'debug', '-dname', 'CN=Doom Lab', '-keyalg', 'RSA', '-validity', '10000')
    dex_apk(BUILD / 'base.apk', list((BUILD / 'classes').rglob('*.class')), BUILD / 'doom-lab.apk', [stdlib])
    observer = BUILD / 'observer-manifest.xml'
    observer.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.noti.doomobserver"><uses-sdk android:minSdkVersion="31" android:targetSdkVersion="36"/><application android:debuggable="true" android:allowBackup="false" android:label="Doom lab observer"><activity android:name=".DoomListener$Launcher" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity><service android:name=".DoomListener" android:exported="true" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"><intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter></service></application></manifest>''')
    run(TOOLS / 'aapt2', 'link', '-I', ANDROID, '--manifest', observer, '-o', BUILD / 'observer-base.apk')
    run(JAVA / 'bin/javac', '-source', '8', '-target', '8', '-classpath', ANDROID, '-d', BUILD / 'observer-classes', HERE / 'src/DoomListener.java')
    dex_apk(BUILD / 'observer-base.apk', list((BUILD / 'observer-classes').rglob('*.class')), BUILD / 'doom-observer.apk')
    manifest = {'baselineRef': args.baseline_ref, 'baselineServiceSha256': hashlib.sha256(original.encode()).hexdigest(),
                'candidateServiceSha256': hashlib.sha256(current.encode()).hexdigest(), 'abi': args.abi,
                'apkSha256': hashlib.sha256((BUILD / 'doom-lab.apk').read_bytes()).hexdigest(),
                'liveSlotsOverride': args.live_slots,
                'liveDelayMsOverride': args.live_delay_ms,
                'instrumentation': 'Both services get identical frame-ID strips and timing logs. The baseline is renamed, but its scheduling is unchanged. The native engine is shared by the variants and starts in a fresh process for each trial.'}
    (BUILD / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    archive = ROOT / 'artifacts/doom-optimization/builds'
    archive.mkdir(parents=True, exist_ok=True)
    source_files = [HERE / 'build.py', HERE / 'AndroidManifest.xml', *list((HERE / 'src').glob('*')),
        *list((MODULE / 'cpp').glob('*.c')), *list((MODULE / 'cpp').glob('*.cpp')), *list((MODULE / 'cpp').glob('*.h')),
        MODULE / 'cpp/CMakeLists.txt', *list((MODULE / 'java/expo/modules/notificationmotion').glob('Doom*.kt'))]
    manifest['sourceSha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files if p.is_file()}
    (BUILD / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    name = manifest['apkSha256']
    shutil.copy2(BUILD / 'doom-lab.apk', archive / (name + '.apk'))
    shutil.copy2(BUILD / 'manifest.json', archive / (name + '.json'))
    with tarfile.open(archive / (name + '.sources.tar.gz'), 'w:gz') as bundle:
        for path in source_files:
            if path.is_file(): bundle.add(path, arcname=str(path.relative_to(ROOT)))
    print(BUILD / 'doom-lab.apk')


if __name__ == '__main__':
    main()
