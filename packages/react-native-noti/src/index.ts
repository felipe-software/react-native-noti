import { NativeModule, requireOptionalNativeModule } from 'expo';
import { PermissionsAndroid, Platform } from 'react-native';
import type { ReactNode } from 'react';
import { NotiPrimitives } from './components';
import { serializeScene } from './serialize';
import type {
    ActiveNotification,
    CreateNotificationOptions,
    FramePlaybackOptions,
    FramePlaybackStatus,
    NotiAction,
    NotificationIdentity,
    NotificationTarget,
    NotificationUpdate,
    WidgetAction,
} from './types';

export type * from './types';
export type { NotiAnimation, AnimationState, NotiPluginOptions } from '../plugin';

type Events = {
    onAction: (event: NotiAction) => void;
    onWidgetAction: (event: WidgetAction) => void;
    onWidgetPinned: (event: { widgetId: number }) => void;
};
declare class NotificationMotionModule extends NativeModule<Events> {
    create(options: string): Promise<NotificationIdentity>;
    update(identity: NotificationIdentity, update: string): Promise<void>;
    adopt(identity: NotificationIdentity): Promise<NotificationIdentity>;
    listActive(): Promise<ActiveNotification[]>;
    dismiss(identity: NotificationIdentity): Promise<void>;
    scrollTo(identity: NotificationIdentity, nodeId: string, index: number): Promise<void>;
    playFrames(options: string): Promise<FramePlaybackStatus>;
    stopFrames(): Promise<FramePlaybackStatus>;
    getFramesStatus(): Promise<FramePlaybackStatus>;
    isWidgetPinningSupported(): Promise<boolean>;
    requestPinWidget(scene: string): Promise<boolean>;
    listWidgets(): Promise<number[]>;
    updateWidget(widgetId: number, scene: string): Promise<void>;
    updateAllWidgets(scene: string): Promise<void>;
}

function native(): NotificationMotionModule {
    if (Platform.OS !== 'android' || Number(Platform.Version) < 31)
        throw new Error('Noti requires Android 12 / API 31 or newer.');
    const module = requireOptionalNativeModule<NotificationMotionModule>('NotificationMotion');
    if (!module) throw new Error('Noti is not linked. Rebuild the Expo development client.');
    return module;
}

function identity(target: NotificationTarget): NotificationIdentity {
    const result = typeof target === 'number' ? { id: target, tag: null } : target;
    if (
        !Number.isInteger(result.id) ||
        result.id < -2147483648 ||
        result.id > 2147483647 ||
        (result.tag !== null && typeof result.tag !== 'string')
    )
        throw new Error('Invalid Android notification identity.');
    return { id: result.id, tag: result.tag };
}

function serializeUpdate(update: NotificationUpdate): string {
    const actions = update.actions?.map((item) => {
        if (
            typeof item.id !== 'string' ||
            !/^[a-zA-Z0-9._-]{1,64}$/.test(item.id) ||
            typeof item.onPress !== 'string' ||
            !/^[a-zA-Z0-9._-]{1,64}$/.test(item.onPress) ||
            typeof item.title !== 'string' ||
            item.title.length < 1 ||
            item.title.length > 40
        ) {
            throw new Error(
                'Notification actions require a 1–64 character id/onPress and a 1–40 character title.'
            );
        }
        return item;
    });
    if (actions && actions.length > 3)
        throw new Error('Android notifications support at most three actions.');
    return JSON.stringify({
        ...update,
        actions,
        collapsed: update.collapsed === undefined ? undefined : serializeScene(update.collapsed),
        expanded:
            update.expanded === undefined
                ? undefined
                : update.expanded === null
                  ? null
                  : serializeScene(update.expanded),
        headsUp:
            update.headsUp === undefined
                ? undefined
                : update.headsUp === null
                  ? null
                  : serializeScene(update.headsUp),
    });
}

function widgetId(id: number): number {
    if (!Number.isInteger(id) || id <= 0 || id > 2147483647)
        throw new Error('Invalid Android widget id.');
    return id;
}

function serializeFramePlayback(options: FramePlaybackOptions): string {
    const {
        assetDirectory,
        frameCount,
        sourceFps = 30,
        fps = 30,
        filenamePrefix = 'frame_',
        filenameDigits = 5,
        filenameStartIndex = 1,
        filenameExtension = 'png',
        batchSize = 60,
        notificationId = 90731,
        title = 'Frame playback',
        body = 'Playing silently in the notification shade',
        channelId = 'noti-frame-playback',
        channelName = 'Frame playback',
        smallIcon = 'nm_small_icon',
        loop = false,
        height = 120,
    } = options;
    if (
        typeof assetDirectory !== 'string' ||
        !assetDirectory ||
        assetDirectory
            .replace(/^\/+|\/+$/g, '')
            .split('/')
            .some((part) => !/^[a-zA-Z0-9._-]+$/.test(part) || part === '.' || part === '..')
    )
        throw new Error('assetDirectory must be a safe relative Android asset directory.');
    if (!Number.isInteger(frameCount) || frameCount < 1 || frameCount > 100_000)
        throw new Error('frameCount must be an integer between 1 and 100000.');
    if (!Number.isInteger(sourceFps) || sourceFps < 1 || sourceFps > 240)
        throw new Error('sourceFps must be an integer between 1 and 240.');
    if (fps !== 15 && fps !== 30 && fps !== 60)
        throw new Error('fps must be 15, 30, or 60.');
    if (!/^[a-zA-Z0-9._-]*$/.test(filenamePrefix))
        throw new Error("filenamePrefix may only contain letters, numbers, '.', '_' and '-'.");
    if (!Number.isInteger(filenameDigits) || filenameDigits < 1 || filenameDigits > 9)
        throw new Error('filenameDigits must be an integer between 1 and 9.');
    if (
        !Number.isSafeInteger(filenameStartIndex) ||
        filenameStartIndex < 0 ||
        filenameStartIndex + frameCount - 1 > 2147483647
    )
        throw new Error('filenameStartIndex must be a non-negative integer.');
    if (String(filenameStartIndex + frameCount - 1).length > filenameDigits)
        throw new Error('filenameDigits is too small for the final frame number.');
    if (!['png', 'jpg', 'jpeg', 'webp'].includes(filenameExtension))
        throw new Error('filenameExtension must be png, jpg, jpeg, or webp.');
    if (!Number.isInteger(batchSize) || batchSize < 2 || batchSize > 60)
        throw new Error('batchSize must be an integer between 2 and 60.');
    if (
        !Number.isInteger(notificationId) ||
        notificationId === 0 ||
        notificationId < -2147483648 ||
        notificationId > 2147483647
    )
        throw new Error('notificationId must be a non-zero Android notification id.');
    if (!Number.isInteger(height) || height < 48 || height > 240)
        throw new Error('height must be an integer between 48 and 240 dp.');
    return JSON.stringify({
        assetDirectory: assetDirectory.replace(/^\/+|\/+$/g, ''),
        frameCount,
        sourceFps,
        fps,
        filenamePrefix,
        filenameDigits,
        filenameStartIndex,
        filenameExtension,
        batchSize,
        notificationId,
        title,
        body,
        channelId,
        channelName,
        smallIcon,
        loop,
        height,
    });
}

const widgets = {
    async isPinningSupported(): Promise<boolean> {
        if (Platform.OS !== 'android' || Number(Platform.Version) < 31) return false;
        return native().isWidgetPinningSupported();
    },
    async requestPin(scene: ReactNode): Promise<boolean> {
        return native().requestPinWidget(JSON.stringify(serializeScene(scene)));
    },
    async list(): Promise<number[]> {
        return native().listWidgets();
    },
    async update(id: number, scene: ReactNode): Promise<void> {
        await native().updateWidget(widgetId(id), JSON.stringify(serializeScene(scene)));
    },
    async updateAll(scene: ReactNode): Promise<void> {
        await native().updateAllWidgets(JSON.stringify(serializeScene(scene)));
    },
    addActionListener(listener: (event: WidgetAction) => void) {
        return native().addListener('onWidgetAction', listener);
    },
    addPinnedListener(listener: (event: { widgetId: number }) => void) {
        return native().addListener('onWidgetPinned', listener);
    },
};

const lifecycle = {
    async requestPermission(): Promise<boolean> {
        if (Platform.OS !== 'android') return false;
        if (Number(Platform.Version) < 33) return true;
        return (
            (await PermissionsAndroid.request(
                PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
            )) === PermissionsAndroid.RESULTS.GRANTED
        );
    },
    async create(options: CreateNotificationOptions): Promise<number> {
        const payload = JSON.parse(serializeUpdate(options));
        return (await native().create(JSON.stringify({ ...options, ...payload }))).id;
    },
    async update(target: NotificationTarget, update: NotificationUpdate): Promise<void> {
        await native().update(identity(target), serializeUpdate(update));
    },
    async adopt(target: NotificationTarget): Promise<NotificationIdentity> {
        return native().adopt(identity(target));
    },
    async listActive(): Promise<ActiveNotification[]> {
        return native().listActive();
    },
    async dismiss(target: NotificationTarget): Promise<void> {
        await native().dismiss(identity(target));
    },
    async scrollTo(target: NotificationTarget, nodeId: string, index: number): Promise<void> {
        if (!Number.isInteger(index) || index < 0)
            throw new Error('Scroll index must be a non-negative integer.');
        await native().scrollTo(identity(target), nodeId, index);
    },
    async playFrames(options: FramePlaybackOptions): Promise<FramePlaybackStatus> {
        return native().playFrames(serializeFramePlayback(options));
    },
    async stopFrames(): Promise<FramePlaybackStatus> {
        return native().stopFrames();
    },
    async getFramesStatus(): Promise<FramePlaybackStatus> {
        return native().getFramesStatus();
    },
    addActionListener(listener: (event: NotiAction) => void) {
        return native().addListener('onAction', listener);
    },
};

export const Noti = Object.assign(NotiPrimitives, lifecycle, { widgets });
