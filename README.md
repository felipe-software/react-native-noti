# React Native Noti

Android-only Expo workspace for declarative custom notifications and home-screen widgets backed by `RemoteViews`.

- `apps/demo` contains the development client and the 12 component demos.
- `packages/react-native-noti` contains the reusable Expo native module.

Android 12 / API 31 or newer is required. Run `bun install`, then run `bunx expo prebuild --clean` and `bunx expo run android` from `apps/demo`.

## Example

```tsx
import { Noti } from 'react-native-noti';

const lyrics = ['First line', 'Second line', 'Third line', 'Fourth line'];

const lyricScroll = (id: string) => (
    <Noti.ScrollView
        id={id}
        itemHeight="2rem"
        windowSize={3}
        autoPlay
        interval={1600}
        style={{ width: '100%', height: '6rem', backgroundColor: '#18181b' }}
    >
        {lyrics.map((line) => (
            <Noti.Text key={line} style={{ color: '#fafafa', textAlign: 'center' }}>
                {line}
            </Noti.Text>
        ))}
    </Noti.ScrollView>
);

const small = (
    <Noti.View style={{ width: '100%', height: '3rem', backgroundColor: '#18181b' }}>
        <Noti.Text style={{ color: '#fafafa' }}>First line</Noti.Text>
    </Noti.View>
);

const expanded = lyricScroll('expanded-lyrics');
const widget = lyricScroll('widget-lyrics');

await Noti.requestPermission();
await Noti.create({ title: 'Now playing', collapsed: small, expanded });
await Noti.widgets.requestPin(widget);
```

See the [package README](packages/react-native-noti/README.md) for installation, APIs, components, and Android limits.

## Doom in a notification

The **Doom** card runs the real Doom engine with the freely redistributable Freedoom Phase 1 levels and artwork. Tap **Play Doom**, allow notifications, then pull down the shade and expand **Doom · Noti**. The expanded notification contains the live game, movement/turn buttons, **Fire**, and **Use** for doors and switches. **Pause / Resume** and **Stop Doom** work directly in the notification, even with React in the background.

Rebuild the Android development client to include the native engine and bundled WAD. Game assets work offline; no commercial Doom WAD is required. The native simulation runs at 35 ticks/s, while this demo samples live frames at a target of 30 fps and sends eight-frame batches to a SystemUI ViewFlipper (about 3.8 notification updates/s). Frames use 240×150 RGB565 to bound each batch payload. Batching adds roughly 230–265 ms of visual latency. The expanded game area is 200 dp tall; with controls and Android decoration, the notification reaches 358 dp on the API 36 test emulator. This is interactive gameplay, not prerecorded frames. Android/SystemUI determines the actual visible frame rate. Notification buttons support taps: movement/turn/use holds a key for 250 ms, fire for 160 ms; repeated taps extend the pulse. Audio is disabled.

```tsx
if (await Noti.requestPermission()) await Noti.startDoom();
await Noti.doomAction('forward'); // also backward, left, right, fire, use, pause
const status = await Noti.getDoomStatus();
await Noti.stopDoom();
```

Stopping removes the notification and pauses the engine. Playing again resumes the level while the app process is alive; after process death it starts E1M1 again. `framesPublished` counts frames submitted to Android, not frames displayed. The native engine and its source/license notices are in [`android/src/main/cpp`](packages/react-native-noti/android/src/main/cpp); the JNI contract is documented in [docs/doom-engine-contract.md](docs/doom-engine-contract.md).

## Bad Apple in a notification

The demo includes the complete silent Bad Apple video (3m39s, 6,572 source frames at 30 fps). Use **Play**, then open the Android notification shade and expand **Bad Apple!!**. The controls select 15, 30, or an experimental 60 Hz schedule, restart playback, and stop it. A 30 fps source still has at most 30 unique images per second in the 60 Hz mode.

```sh
bun install
bun run bad-apple:prepare
bun --cwd apps/demo android --device emulator-5554 --port 8081
```

`bad-apple:prepare` downloads [the source MP4](https://github.com/NPCat/bad-apple-bot/blob/main/bad_apple.mp4) and uses the installed `ffmpeg` to extract 160×120 PNGs into `apps/demo/assets/bad-apple`. The config plugin packages those frames as Android assets; playback then works offline. Generated frames are ignored by Git. With an existing Android project created before this example, run `bun --cwd apps/demo prebuild` once before building.

To reuse a local copy instead of downloading:

```sh
python3 apps/demo/scripts/prepare-bad-apple.py --source /path/to/bad_apple.mp4
```

The [example component](apps/demo/src/BadApple.tsx) uses `Noti.playFrames`, `Noti.stopFrames`, and `Noti.getFramesStatus`. A native foreground service publishes bounded batches, and a `ViewFlipper` in SystemUI plays the frames between notification updates. JavaScript does not send individual frames. Expand the notification to see the animation; the collapsed preview updates only at batch boundaries.

The configured frequency and native progress are scheduling information. To measure visible motion, record the expanded notification with Android `screenrecord`, then analyze only its movie rectangle using the recording's original timestamps:

```sh
python3 apps/demo/scripts/measure-notification-fps.py recording.mp4 --crop width:height:x:y
```

The measurement script requires NumPy and FFmpeg. It counts image changes above a luma noise threshold, so black scenes and repeated source images reduce the result. It does not infer displayed fps from `notify()` calls or the requested interval. Add `--reference-frames apps/demo/assets/bad-apple/frames` to cross-check captured images against the source and detect subtle changes below the conservative pixel threshold. The reference comparison reports its match coverage and luma error, and uses a timing search window around the expected source position.
