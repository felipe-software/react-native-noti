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
