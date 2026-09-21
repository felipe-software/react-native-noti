import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { Noti, type NotificationTarget } from 'react-native-noti';
import { examples, notificationScene } from './scenes';
import { BadApple } from './BadApple';

const demoIdentity = { id: 12012, tag: 'noti-demo' } as const;

function Demo() {
    const [notificationId, setNotificationId] = useState<number | null>(null);
    const [widgetCount, setWidgetCount] = useState(0);
    const [selected, setSelected] = useState(0);
    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState('Ready');
    const idRef = useRef<number | null>(null);
    const selectedRef = useRef(0);
    const phaseRef = useRef(0);

    const commit = useCallback(
        async (
            target: NotificationTarget | null | false,
            nextSelected: number,
            nextPhase: number
        ) => {
            const scene = notificationScene(nextSelected, nextPhase);
            const update = {
                title: `Noti ${String(nextSelected + 1).padStart(2, '0')} · ${examples[nextSelected]}`,
                body: 'Android RemoteViews demo',
                actions: [{ id: 'next-example', title: 'NEXT', onPress: 'next-example' }],
                ...scene,
            };
            let id = idRef.current;
            if (target === null) {
                const granted = await Noti.requestPermission();
                if (!granted) throw new Error('Notification permission denied');
                id = await Noti.create({
                    ...demoIdentity,
                    channelId: 'noti-demos',
                    channelName: 'Noti Demos',
                    importance: 'high',
                    ...update,
                });
            } else if (target !== false) {
                await Noti.update(target, update);
                id = typeof target === 'number' ? target : target.id;
            }
            await Noti.widgets.updateAll(scene.expanded);
            idRef.current = id;
            selectedRef.current = nextSelected;
            phaseRef.current = nextPhase;
            setNotificationId(id);
            setSelected(nextSelected);
            setStatus(`Showing ${String(nextSelected + 1).padStart(2, '0')}`);
        },
        []
    );

    const run = useCallback(async (operation: () => Promise<void>) => {
        setBusy(true);
        try {
            await operation();
        } catch (error) {
            setStatus(error instanceof Error ? error.message : String(error));
        } finally {
            setBusy(false);
        }
    }, []);

    const show = (index: number) =>
        run(() => commit(idRef.current === null ? null : demoIdentity, index, 0));

    const pulse = () =>
        run(() =>
            commit(
                idRef.current === null ? null : demoIdentity,
                selectedRef.current,
                phaseRef.current + 1
            )
        );

    const pinWidget = () =>
        run(async () => {
            const scene = notificationScene(selectedRef.current, phaseRef.current);
            const requested = await Noti.widgets.requestPin(scene.expanded);
            setStatus(requested ? 'Choose where to pin the widget' : 'Widget pinning unsupported');
        });

    const dismiss = () =>
        run(async () => {
            if (idRef.current === null) return;
            await Noti.dismiss(demoIdentity);
            idRef.current = null;
            setNotificationId(null);
            setStatus('Dismissed');
        });

    useEffect(() => {
        const notificationSubscription = Noti.addActionListener((event) => {
            if (event.id !== demoIdentity.id || event.tag !== demoIdentity.tag) return;
            const nextSelected =
                event.action === 'next-example'
                    ? (selectedRef.current + 1) % examples.length
                    : selectedRef.current;
            const nextPhase = event.action === 'next-example' ? 0 : phaseRef.current + 1;
            commit(event, nextSelected, nextPhase).catch((error: unknown) =>
                setStatus(error instanceof Error ? error.message : String(error))
            );
        });
        const widgetSubscription = Noti.widgets.addActionListener((event) => {
            const nextSelected =
                event.action === 'next-example'
                    ? (selectedRef.current + 1) % examples.length
                    : selectedRef.current;
            const nextPhase = event.action === 'next-example' ? 0 : phaseRef.current + 1;
            commit(idRef.current === null ? false : demoIdentity, nextSelected, nextPhase).catch(
                (error: unknown) =>
                    setStatus(error instanceof Error ? error.message : String(error))
            );
        });
        const pinnedSubscription = Noti.widgets.addPinnedListener(() => {
            Noti.widgets
                .list()
                .then((widgets) => setWidgetCount(widgets.length))
                .catch((error: unknown) =>
                    setStatus(error instanceof Error ? error.message : String(error))
                );
        });
        Noti.widgets
            .list()
            .then((widgets) => setWidgetCount(widgets.length))
            .catch((error: unknown) =>
                setStatus(error instanceof Error ? error.message : String(error))
            );
        Noti.listActive()
            .then((active) => active.find((item) => item.id === demoIdentity.id))
            .then((existing) => {
                if (!existing) return;
                return Noti.adopt({ id: existing.id, tag: existing.tag }).then((identity) => {
                    idRef.current = identity.id;
                    setNotificationId(identity.id);
                    setStatus('Notification found');
                });
            })
            .catch((error: unknown) =>
                setStatus(error instanceof Error ? error.message : String(error))
            );
        return () => {
            notificationSubscription.remove();
            widgetSubscription.remove();
            pinnedSubscription.remove();
        };
    }, [commit]);

    return (
        <SafeAreaView className="flex-1 bg-zinc-950" edges={['top']}>
            <StatusBar style="light" />
            <ScrollView contentContainerClassName="px-5 pb-12 pt-6">
                <BadApple />
                <View className="mb-7 flex-row items-end justify-between">
                    <View>
                        <Text className="text-xs font-bold tracking-[0.2rem] text-violet-300">
                            NOTI
                        </Text>
                        <Text className="mt-1 text-4xl font-black tracking-tight text-white">
                            12 demos
                        </Text>
                    </View>
                    <View className="items-end">
                        {busy ? <ActivityIndicator color="#c4b5fd" /> : null}
                        <Text className="mt-1 text-xs font-bold text-zinc-500">{status}</Text>
                    </View>
                </View>

                <View className="mb-5 flex-row gap-3">
                    <Pressable
                        accessibilityRole="button"
                        onPress={pulse}
                        disabled={busy}
                        className="h-12 flex-1 items-center justify-center rounded-2xl bg-violet-300"
                    >
                        <Text className="text-sm font-black text-zinc-950">PULSE</Text>
                    </Pressable>
                    <Pressable
                        accessibilityRole="button"
                        accessibilityLabel="Pin demo widget"
                        onPress={pinWidget}
                        disabled={busy}
                        className="h-12 flex-1 items-center justify-center rounded-2xl border border-violet-300 bg-zinc-900"
                    >
                        <Text className="text-sm font-black text-violet-300">PIN</Text>
                    </Pressable>
                    <Pressable
                        accessibilityRole="button"
                        onPress={dismiss}
                        disabled={busy || notificationId === null}
                        className="h-12 flex-1 items-center justify-center rounded-2xl border border-zinc-700 bg-zinc-900"
                    >
                        <Text className="text-sm font-black text-white">DISMISS</Text>
                    </Pressable>
                </View>

                <View className="flex-row flex-wrap justify-between gap-y-3">
                    {examples.map((name, index) => {
                        const active = index === selected;
                        return (
                            <Pressable
                                key={name}
                                accessibilityRole="button"
                                accessibilityLabel={`Show ${name}`}
                                onPress={() => show(index)}
                                disabled={busy}
                                className={`h-28 w-[48%] justify-between rounded-3xl border p-4 ${
                                    active
                                        ? 'border-violet-300 bg-violet-300'
                                        : 'border-zinc-800 bg-zinc-900'
                                }`}
                            >
                                <Text
                                    className={`text-xs font-black ${active ? 'text-violet-950' : 'text-zinc-500'}`}
                                >
                                    {String(index + 1).padStart(2, '0')}
                                </Text>
                                <Text
                                    className={`text-base font-black ${active ? 'text-violet-950' : 'text-white'}`}
                                >
                                    {name}
                                </Text>
                            </Pressable>
                        );
                    })}
                </View>

                <Text className="mt-5 text-center text-xs font-bold text-zinc-600">
                    NOTIFICATION {notificationId ?? '—'} · WIDGETS {widgetCount}
                </Text>
            </ScrollView>
        </SafeAreaView>
    );
}

export default function App() {
    return (
        <SafeAreaProvider>
            <Demo />
        </SafeAreaProvider>
    );
}
