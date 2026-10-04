import { useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Pressable, Text, View } from 'react-native';
import { Noti, type DoomStatus } from 'react-native-noti';

export function Doom() {
    const [game, setGame] = useState<DoomStatus | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const active = game?.state === 'playing' || game?.state === 'paused';
    const starting = game?.state === 'starting';

    useEffect(() => {
        let mounted = true;
        const refresh = async () => {
            if (AppState.currentState !== 'active') return;
            try {
                const status = await Noti.getDoomStatus();
                if (mounted) setGame(status);
            } catch (cause) {
                if (mounted) setError(cause instanceof Error ? cause.message : String(cause));
            }
        };
        void refresh();
        const timer = setInterval(refresh, 1000);
        const subscription = AppState.addEventListener('change', (state) => {
            if (state === 'active') void refresh();
        });
        return () => {
            mounted = false;
            clearInterval(timer);
            subscription.remove();
        };
    }, []);

    const run = async (operation: () => Promise<DoomStatus>) => {
        setBusy(true);
        setError(null);
        try {
            setGame(await operation());
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : String(cause));
        } finally {
            setBusy(false);
        }
    };

    const play = () => run(async () => {
        if (!(await Noti.requestPermission())) throw new Error('Enable notifications to play Doom.');
        return Noti.startDoom();
    });

    return (
        <View className="mb-7 rounded-3xl border border-orange-900 bg-zinc-900 p-5">
            <View className="flex-row items-center justify-between">
                <Text className="text-4xl font-black tracking-tight text-orange-300">Doom</Text>
                {busy || starting ? <ActivityIndicator color="#fdba74" /> : null}
            </View>
            <Text className="mt-2 text-sm leading-5 text-zinc-300">
                A real game. Inside your notification.
            </Text>
            <Text className="mt-3 text-sm leading-5 text-zinc-400">
                Expand the notification to move, turn and fire. Tap Use to open doors.
                Each tap makes a short move; tap again to keep going.
            </Text>
            <View className="mt-5 flex-row gap-3">
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={active ? (game?.state === 'paused' ? 'Resume Doom' : 'Pause Doom') : 'Play Doom'}
                    disabled={busy || starting}
                    onPress={() => active ? run(() => Noti.doomAction('pause')) : play()}
                    className="h-12 flex-1 items-center justify-center rounded-xl bg-orange-300"
                >
                    <Text className="font-black text-zinc-950">
                        {starting ? 'Loading…' : active ? (game?.state === 'paused' ? 'Resume' : 'Pause') : 'Play Doom'}
                    </Text>
                </Pressable>
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="Stop Doom"
                    disabled={busy || (!active && !starting)}
                    onPress={() => run(() => Noti.stopDoom())}
                    className="h-12 items-center justify-center rounded-xl border border-zinc-600 px-5"
                >
                    <Text className="font-bold text-white">Stop</Text>
                </Pressable>
            </View>
            <Text accessibilityLiveRegion="polite" className="mt-4 text-xs leading-5 text-zinc-400">
                {error ?? game?.error ?? (
                    starting ? 'Loading the level…' : active
                        ? game?.state === 'paused' ? 'Paused. Resume here or in the notification.'
                            : 'Playing. Pull down and expand the Doom notification.'
                        : game?.state === 'stopped' ? 'Stopped. Play resumes this session until the app process closes.'
                            : 'Freedoom Phase 1 · Doom engine · no audio'
                )}
            </Text>
            <Text className="mt-2 text-xs leading-4 text-zinc-500">
                Live gameplay · targets 30 fps inside the notification. Smoothness depends on Android.
            </Text>
        </View>
    );
}
