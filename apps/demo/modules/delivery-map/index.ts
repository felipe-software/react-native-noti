import { requireOptionalNativeModule } from 'expo';

export type DeliveryOptions = {
    /** Public Mapbox token used for the off-screen map. */
    accessToken: string;
    /** Mapbox style URLs, chosen by the system's light or dark mode when a delivery starts. */
    mapStyle: string;
    darkMapStyle: string;
    /** Road geometry from the store to the customer, as `[longitude, latitude]` pairs. */
    route: [number, number][];
    courier: string;
    store: string;
    /** Real-world duration of the route. It drives the displayed ETA. */
    etaSeconds: number;
    /** How long the simulated delivery takes on screen. */
    durationMs: number;
};

export type DeliveryStatus = {
    state: 'idle' | 'starting' | 'enroute' | 'delivered' | 'stopped' | 'error';
    headline: string | null;
    detail: string | null;
    progress: number;
    error: string | null;
};

type NativeDeliveryMap = {
    start(options: string): Promise<DeliveryStatus>;
    stop(): Promise<DeliveryStatus>;
    getStatus(): Promise<DeliveryStatus>;
};

function native(): NativeDeliveryMap {
    const module = requireOptionalNativeModule<NativeDeliveryMap>('DeliveryMap');
    if (!module) throw new Error('DeliveryMap is not linked. Rebuild the Expo development client.');
    return module;
}

export const DeliveryMap = {
    start: (options: DeliveryOptions) => native().start(JSON.stringify(options)),
    stop: () => native().stop(),
    getStatus: () => native().getStatus(),
};
