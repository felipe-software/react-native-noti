import { useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Pressable, Text, View } from 'react-native';
import { Noti } from 'react-native-noti';

const rates = [15, 30, 60] as const;
const clock = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

export function BadApple() {
    const [fps, setFps] = useState<(typeof rates)[number]>(30);
    const [busy, setBusy] = useState(false);
    const [playing, setPlaying] = useState(false);
    const [status, setStatus] = useState('Full video · 3m 39s · no audio');

    useEffect(() => {
        const refresh = async () => {
            if (AppState.currentState !== 'active') return;
            try {
                const current = await Noti.getFramesStatus();
                setPlaying(current.state === 'playing' || current.state === 'starting');
                if (current.state === 'playing') {
                    const elapsed = current.durationMs > 0 ? current.elapsedMs % current.durationMs : 0;
                    setStatus(`${clock(elapsed)} / ${clock(current.durationMs)} · Open and expand the notification.`);
                } else if (current.state === 'error') {
                    setStatus(current.error ?? 'Unable to play the video.');
                } else if (current.state === 'stopped') {
                    setStatus('Playback stopped');
                } else if (current.state === 'completed') {
                    setStatus('Video completed');
                }
            } catch (error) {
                setStatus(error instanceof Error ? error.message : String(error));
            }
        };
        void refresh();
        const timer = setInterval(refresh, 1000);
        const subscription = AppState.addEventListener('change', (state) => {
            if (state === 'active') void refresh();
        });
        return () => {
            clearInterval(timer);
            subscription.remove();
        };
    }, []);

    const play = async () => {
        setBusy(true);
        try {
            if (!(await Noti.requestPermission())) {
                throw new Error('Enable notifications to play the video.');
            }
            await Noti.playFrames({
                notificationId: 15030,
                title: 'Bad Apple!!',
                body: 'Full video · no audio',
                assetDirectory: 'bad-apple/frames',
                frameCount: 6572,
                sourceFps: 30,
                fps,
                loop: true,
                height: 192,
            });
            setPlaying(true);
            setStatus('Playing. Open and expand the notification.');
        } catch (error) {
            setStatus(error instanceof Error ? error.message : String(error));
        } finally {
            setBusy(false);
        }
    };

    const stop = async () => {
        setBusy(true);
        try {
            await Noti.stopFrames();
            setPlaying(false);
            setStatus('Playback stopped');
        } catch (error) {
            setStatus(error instanceof Error ? error.message : String(error));
        } finally {
            setBusy(false);
        }
    };

    return (
        <View className="mb-7 rounded-3xl border border-zinc-700 bg-zinc-900 p-5">
            <View className="flex-row items-center justify-between">
                <Text className="text-3xl font-black tracking-tight text-white">Bad Apple!!</Text>
                {busy ? <ActivityIndicator color="#c4b5fd" /> : null}
            </View>
            <Text className="mt-2 text-sm leading-5 text-zinc-400">
                The full video, inside a notification.
            </Text>
            <View className="mt-5 flex-row gap-2">
                {rates.map((rate) => (
                    <Pressable
                        key={rate}
                        accessibilityRole="button"
                        accessibilityLabel={`Select ${rate} fps`}
                        accessibilityState={{ selected: rate === fps }}
                        disabled={busy}
                        onPress={() => setFps(rate)}
                        className={`h-11 flex-1 items-center justify-center rounded-xl ${
                            rate === fps ? 'bg-white' : 'bg-zinc-800'
                        }`}
                    >
                        <Text className={`font-bold ${rate === fps ? 'text-zinc-950' : 'text-zinc-300'}`}>
                            {rate} fps
                        </Text>
                    </Pressable>
                ))}
            </View>
            <Text className="mt-2 text-xs leading-4 text-zinc-500">
                {fps === 60
                    ? 'Experimental 60 Hz: the source has 30 unique frames/s.'
                    : `${fps} fps requested. Smoothness depends on Android.`}
            </Text>
            <View className="mt-4 flex-row gap-3">
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="Play Bad Apple"
                    disabled={busy}
                    onPress={play}
                    className="h-12 flex-1 items-center justify-center rounded-xl bg-violet-300"
                >
                    <Text className="font-black text-zinc-950">{playing ? 'Restart' : 'Play'}</Text>
                </Pressable>
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="Stop Bad Apple"
                    disabled={busy}
                    onPress={stop}
                    className="h-12 items-center justify-center rounded-xl border border-zinc-600 px-5"
                >
                    <Text className="font-bold text-white">Stop</Text>
                </Pressable>
            </View>
            <Text accessibilityLiveRegion="polite" className="mt-4 text-xs leading-5 text-zinc-400">
                {status}
            </Text>
        </View>
    );
}
