import { useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Pressable, Text, View } from 'react-native';
import { Noti } from 'react-native-noti';
import { DeliveryMap, type DeliveryStatus } from '../modules/delivery-map';

const accessToken = process.env.EXPO_PUBLIC_MAPBOX_TOKEN;
const store = '-122.4186,37.7596';
const home = '-122.4372,37.7513';
const durationMs = 80_000;
const mapStyle = 'mapbox://styles/mapbox/navigation-day-v1';
const darkMapStyle = 'mapbox://styles/mapbox/navigation-night-v1';

async function fetchRoute(token: string) {
    const response = await fetch(
        `https://api.mapbox.com/directions/v5/mapbox/driving/${store};${home}` +
            `?geometries=geojson&overview=full&access_token=${encodeURIComponent(token)}`
    );
    if (!response.ok) throw new Error(`Mapbox could not find a route (${response.status}).`);
    const { routes } = await response.json();
    const route = routes?.[0];
    if (!route) throw new Error('Mapbox could not find a route.');
    return {
        route: route.geometry.coordinates as [number, number][],
        etaSeconds: route.duration as number,
    };
}

export function Delivery() {
    const [delivery, setDelivery] = useState<DeliveryStatus | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const tracking = delivery?.state === 'starting' || delivery?.state === 'enroute';

    useEffect(() => {
        let mounted = true;
        const refresh = async () => {
            if (AppState.currentState !== 'active') return;
            try {
                const status = await DeliveryMap.getStatus();
                if (mounted) setDelivery(status);
            } catch (cause) {
                if (mounted) setError(cause instanceof Error ? cause.message : String(cause));
            }
        };
        void refresh();
        const timer = setInterval(refresh, 1000);
        return () => {
            mounted = false;
            clearInterval(timer);
        };
    }, []);

    const run = async (operation: () => Promise<DeliveryStatus>) => {
        setBusy(true);
        setError(null);
        try {
            setDelivery(await operation());
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : String(cause));
        } finally {
            setBusy(false);
        }
    };

    const track = () =>
        run(async () => {
            if (!accessToken) throw new Error('Add EXPO_PUBLIC_MAPBOX_TOKEN to apps/demo/.env.local.');
            if (!(await Noti.requestPermission()))
                throw new Error('Enable notifications to track a delivery.');
            return DeliveryMap.start({
                accessToken,
                mapStyle,
                darkMapStyle,
                courier: 'Marco',
                store: 'Tacos Luna',
                durationMs,
                ...(await fetchRoute(accessToken)),
            });
        });

    return (
        <View className="mb-7 rounded-3xl border border-rose-900 bg-zinc-900 p-5">
            <View className="flex-row items-center justify-between">
                <Text className="text-4xl font-black tracking-tight text-rose-300">Delivery</Text>
                {busy || delivery?.state === 'starting' ? <ActivityIndicator color="#fda4af" /> : null}
            </View>
            <Text accessibilityLiveRegion="polite" className="mt-2 min-h-10 text-sm leading-5 text-zinc-300">
                {error ??
                    delivery?.error ??
                    (tracking && delivery?.headline
                        ? `${delivery.headline}. ${delivery.detail}.`
                        : 'A live Mapbox map, zooming in as your courier gets closer. Pull down the shade to watch.')}
            </Text>
            <View className="mt-5 flex-row gap-3">
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="Track delivery"
                    disabled={busy || tracking}
                    onPress={track}
                    className={`h-12 flex-1 items-center justify-center rounded-xl ${tracking ? 'bg-zinc-700' : 'bg-rose-300'}`}
                >
                    <Text className={`font-black ${tracking ? 'text-zinc-400' : 'text-zinc-950'}`}>
                        {tracking ? 'On the way' : delivery?.state === 'delivered' ? 'Track again' : 'Track order'}
                    </Text>
                </Pressable>
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="Stop delivery"
                    disabled={busy || !tracking}
                    onPress={() => run(() => DeliveryMap.stop())}
                    className="h-12 items-center justify-center rounded-xl border border-zinc-600 px-5"
                >
                    <Text className="font-bold text-white">Stop</Text>
                </Pressable>
            </View>
        </View>
    );
}
