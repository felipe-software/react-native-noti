#!/usr/bin/env python3
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / 'build'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
TOOLS = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
JAVA = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
ENV = {**os.environ, 'JAVA_HOME': str(JAVA), 'PATH': str(JAVA / 'bin') + os.pathsep + os.environ['PATH']}
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
SIZE = 'android:layout_width="match_parent" android:layout_height="match_parent"'


def resource(kind, name, xml):
    path = BUILD / 'res' / kind / (name + '.xml')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(xml)


def run(*args):
    subprocess.run([str(arg) for arg in args], env=ENV, check=True, cwd=ROOT)


def main():
    if BUILD.exists():
        for name in ('res', 'java', 'classes', 'dex'):
            shutil.rmtree(BUILD / name, ignore_errors=True)
    BUILD.mkdir(exist_ok=True)
    for name in ('java', 'classes', 'dex'):
        (BUILD / name).mkdir()
    for name, extra in [('flip', 'android:autoStart="true" android:flipInterval="33"'), ('manual', 'android:autoStart="false"'), ('animated', 'android:autoStart="false" android:inAnimation="@anim/slide"'), ('loop', 'android:autoStart="false" android:inAnimation="@anim/loop"')]:
        resource('layout', name, f'<ViewFlipper {NS} {SIZE} android:id="@+id/flip" {extra} />')
    resource('layout', 'cell', f'<TextView {NS} {SIZE} android:id="@+id/label" android:gravity="center" android:textSize="30sp" android:textColor="#ffffff" android:background="#203344" />')
    resource('layout', 'image', f'<ImageView {NS} {SIZE} android:id="@+id/image" android:scaleType="fitCenter" />')
    resource('layout', 'property_motion', f'<FrameLayout {NS} {SIZE} android:id="@+id/flip"><TextView android:id="@+id/label" android:layout_width="110dp" android:layout_height="64dp" android:layout_gravity="center_vertical" android:gravity="center" android:text="XML motion" android:textColor="#ffffff" android:background="#3a5465" android:stateListAnimator="@animator/motion" /></FrameLayout>')
    resource('layout', 'group', f'<FrameLayout {NS} {SIZE} android:id="@+id/holder" />')
    resource('layout', 'adapter', f'<AdapterViewFlipper {NS} {SIZE} android:id="@+id/adapter" android:autoStart="true" android:flipInterval="100" android:inAnimation="@animator/still" android:outAnimation="@animator/still" />')
    resource('layout', 'deep_xml', f'<FrameLayout {NS} {SIZE}>' + f'<FrameLayout {SIZE}>' * 31 + f'<TextView {SIZE} android:text="32 levels of XML" />' + '</FrameLayout>' * 32)
    resource('layout', 'legacy_hit', f'<FrameLayout {NS} {SIZE}><ViewFlipper android:id="@+id/flip" android:layout_width="80dp" android:layout_height="80dp" android:inAnimation="@anim/offset"><Button android:id="@+id/target" {SIZE} android:text="Tap" /></ViewFlipper></FrameLayout>')
    resource('anim', 'slide', f'<translate {NS} android:fromXDelta="-75%p" android:toXDelta="75%p" android:duration="1000" />')
    resource('anim', 'loop', f'<rotate {NS} android:fromDegrees="0" android:toDegrees="360" android:pivotX="50%" android:pivotY="50%" android:duration="700" android:repeatCount="infinite" android:interpolator="@android:interpolator/linear" />')
    resource('anim', 'offset', f'<translate {NS} android:fromXDelta="120" android:toXDelta="120" android:duration="1" android:fillAfter="true" />')
    resource('animator', 'still', f'<objectAnimator {NS} android:propertyName="alpha" android:valueFrom="1" android:valueTo="1" android:duration="0" />')
    resource('animator', 'motion', f'<selector {NS}><item android:state_enabled="true"><set android:ordering="together"><objectAnimator android:propertyName="translationX" android:valueType="floatType" android:valueFrom="0" android:valueTo="360" android:duration="1000" android:repeatCount="infinite" android:repeatMode="reverse" /><objectAnimator android:propertyName="rotation" android:valueType="floatType" android:valueFrom="-20" android:valueTo="20" android:duration="800" android:repeatCount="infinite" android:repeatMode="reverse" /></set></item></selector>')
    classes = ['FrameLayout', 'LinearLayout', 'RelativeLayout', 'GridLayout', 'TextView', 'Button', 'ImageView', 'ImageButton', 'ProgressBar', 'Chronometer', 'TextClock', 'AnalogClock', 'ViewFlipper', 'AdapterViewFlipper', 'ListView', 'GridView', 'StackView', 'CheckBox', 'Switch', 'RadioButton', 'RadioGroup', 'View', 'Space', 'EditText', 'android.webkit.WebView', 'SurfaceView', 'TextureView', 'VideoView', 'ScrollView', 'dev.noti.flipperlab.CustomText']
    for index, cls in enumerate(classes):
        resource('layout', 'class_' + str(index), f'<{cls} {NS} {SIZE} android:id="@+id/probe" />')
    resource('xml', 'widget', f'<appwidget-provider {NS} android:minWidth="250dp" android:minHeight="150dp" android:updatePeriodMillis="0" android:initialLayout="@layout/cell" android:resizeMode="horizontal|vertical" android:widgetCategory="home_screen" />')
    resource('drawable', 'icon', f'<vector {NS} android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#ffffffff" android:pathData="M4,4H20V20H4Z" /></vector>')
    run(TOOLS / 'aapt2', 'compile', '--dir', BUILD / 'res', '-o', BUILD / 'resources.zip')
    run(TOOLS / 'aapt2', 'link', '-I', ANDROID, '--manifest', ROOT / 'AndroidManifest.xml', '--java', BUILD / 'java', '-o', BUILD / 'base.apk', BUILD / 'resources.zip')
    sources = list((ROOT / 'src').glob('*.java')) + list((BUILD / 'java').rglob('*.java'))
    run(JAVA / 'bin/javac', '-source', '8', '-target', '8', '-classpath', ANDROID, '-d', BUILD / 'classes', *sources)
    run(TOOLS / 'd8', '--lib', ANDROID, '--min-api', '31', '--output', BUILD / 'dex', *list((BUILD / 'classes').rglob('*.class')))
    with zipfile.ZipFile(BUILD / 'base.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
        apk.write(BUILD / 'dex/classes.dex', 'classes.dex')
    run(TOOLS / 'zipalign', '-f', '4', BUILD / 'base.apk', BUILD / 'aligned.apk')
    if not (BUILD / 'debug.keystore').exists():
        run(JAVA / 'bin/keytool', '-genkeypair', '-keystore', BUILD / 'debug.keystore', '-storepass', 'android', '-keypass', 'android', '-alias', 'debug', '-dname', 'CN=ViewFlipper Lab', '-keyalg', 'RSA', '-validity', '10000')
    run(TOOLS / 'apksigner', 'sign', '--ks', BUILD / 'debug.keystore', '--ks-pass', 'pass:android', '--out', BUILD / 'viewflipper-lab.apk', BUILD / 'aligned.apk')
    run(TOOLS / 'apksigner', 'verify', BUILD / 'viewflipper-lab.apk')
    observer = BUILD / 'ObserverManifest.xml'
    observer.write_text(f'<manifest {NS} package="dev.noti.flipperobserver"><uses-sdk android:minSdkVersion="31" android:targetSdkVersion="36" /><application android:label="ViewFlipper observer" android:debuggable="true" android:allowBackup="false"><activity android:name="dev.noti.flipperlab.ObserverActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity><service android:name="dev.noti.flipperlab.LabListener" android:exported="true" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"><intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter></service></application></manifest>')
    run(TOOLS / 'aapt2', 'link', '-I', ANDROID, '--manifest', observer, '-o', BUILD / 'observer-base.apk')
    with zipfile.ZipFile(BUILD / 'observer-base.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
        apk.write(BUILD / 'dex/classes.dex', 'classes.dex')
    run(TOOLS / 'zipalign', '-f', '4', BUILD / 'observer-base.apk', BUILD / 'observer-aligned.apk')
    run(TOOLS / 'apksigner', 'sign', '--ks', BUILD / 'debug.keystore', '--ks-pass', 'pass:android', '--out', BUILD / 'viewflipper-observer.apk', BUILD / 'observer-aligned.apk')
    run(TOOLS / 'apksigner', 'verify', BUILD / 'viewflipper-observer.apk')
    print(BUILD / 'viewflipper-lab.apk')


if __name__ == '__main__':
    main()
