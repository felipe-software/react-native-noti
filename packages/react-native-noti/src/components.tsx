import type {
    AnimatePresenceProps,
    ButtonProps,
    ChronometerProps,
    GridProps,
    GifProps,
    ImageProps,
    LoopProps,
    NotiProps,
    ProgressProps,
    ScrollViewProps,
    SpacerProps,
    TextProps,
    ViewFlipperProps,
    ViewProps,
} from './types';

function primitive<P extends NotiProps>(kind: string, name: string) {
    const component = (_props: P) => null;
    return Object.assign(component, { notificationPrimitive: kind, displayName: `Noti.${name}` });
}

export const NotiPrimitives = {
    View: primitive<ViewProps>('view', 'View'),
    Grid: primitive<GridProps>('grid', 'Grid'),
    Text: primitive<TextProps>('text', 'Text'),
    Button: primitive<ButtonProps>('button', 'Button'),
    Image: primitive<ImageProps>('image', 'Image'),
    Gif: primitive<GifProps>('gif', 'Gif'),
    Spacer: primitive<SpacerProps>('spacer', 'Spacer'),
    Progress: primitive<ProgressProps>('progress', 'Progress'),
    Chronometer: primitive<ChronometerProps>('chronometer', 'Chronometer'),
    ScrollView: primitive<ScrollViewProps>('scroll', 'ScrollView'),
    ViewFlipper: primitive<ViewFlipperProps>('flipper', 'ViewFlipper'),
    Loop: primitive<LoopProps>('loop', 'Loop'),
    AnimatePresence: primitive<AnimatePresenceProps>('presence', 'AnimatePresence'),
    Crossfade: primitive<AnimatePresenceProps>('crossfade', 'Crossfade'),
};
