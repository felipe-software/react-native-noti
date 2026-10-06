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
