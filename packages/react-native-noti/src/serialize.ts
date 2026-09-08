import { Fragment, isValidElement, type ReactNode } from 'react';
import type {
    NotiProps,
    NotiStyle,
    RuntimeAnimationState,
    RuntimeTransition,
    SerializedNode,
    SerializedRuntimeFrame,
} from './types';

const lengths = new Set([
    'top',
    'right',
    'bottom',
    'left',
    'padding',
    'paddingHorizontal',
    'paddingVertical',
    'paddingTop',
    'paddingRight',
    'paddingBottom',
    'paddingLeft',
    'gap',
    'borderRadius',
    'fontSize',
    'translateX',
    'translateY',
    'elevation',
]);
const numericTransforms = new Set(['scale', 'scaleX', 'scaleY', 'rotate']);
const fields = new Set([
    ...lengths,
    ...numericTransforms,
    'position',
    'anchor',
    'width',
    'height',
    'backgroundColor',
    'opacity',
    'color',
    'fontWeight',
    'textAlign',
    'verticalAlign',
    'justifyContent',
    'alignItems',
]);
const animationName = /^[a-z][a-zA-Z0-9_]*$/;
const color = /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/;

export function rem(value: unknown, name: string): number {
    if (value === 0) return 0;
    if (typeof value !== 'string' || !/^-?(?:\d+(?:\.\d+)?|\.\d+)rem$/.test(value)) {
        throw new Error(`${name} must be a rem length, for example "1rem", or 0.`);
    }
    const result = parseFloat(value) * 16;
    if (!Number.isFinite(result) || Math.abs(result) > 8192)
        throw new Error(`${name} is out of range.`);
    return result;
}

function finite(value: unknown, name: string, minimum: number, maximum: number): number {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum || value > maximum)
        throw new Error(`${name} must be between ${minimum} and ${maximum}.`);
    return value;
}

function serializeStyle(input: NotiStyle = {}): SerializedNode['style'] {
    const output: SerializedNode['style'] = {};
    for (const [name, value] of Object.entries(input)) {
        if (!fields.has(name)) throw new Error(`Unsupported notification style: ${name}`);
        if (value === undefined) continue;
        if (lengths.has(name) || name === 'width' || name === 'height') {
            const dimension = name === 'width' || name === 'height';
            const converted =
                dimension && (value === 'auto' || value === '100%') ? value : rem(value, name);
            if (
                typeof converted === 'number' &&
                converted < 0 &&
                !['top', 'right', 'bottom', 'left', 'translateX', 'translateY'].includes(name)
            )
                throw new Error(`${name} cannot be negative.`);
            output[name] = converted;
        } else if (name === 'opacity') {
            finite(value, 'opacity', 0, 1);
            output[name] = value;
        } else if (numericTransforms.has(name)) {
            finite(value, name, name === 'rotate' ? -36000 : 0, 36000);
            output[name] = value;
        } else {
            output[name] = value;
        }
    }
    return output;
}

function flatten(children: ReactNode): ReactNode[] {
    if (Array.isArray(children)) return children.flatMap(flatten);
    if (children === null || children === undefined || typeof children === 'boolean') return [];
    if (isValidElement<{ children?: ReactNode }>(children) && children.type === Fragment)
        return flatten(children.props.children);
    return [children];
}

function channel(value: number): string {
    return Math.round(Math.max(0, Math.min(255, value)))
        .toString(16)
        .padStart(2, '0');
}

function interpolateColor(from: string, to: string, progress: number): string {
    if (!color.test(from) || !color.test(to))
        throw new Error('Runtime animation colors must use #RRGGBB or #RRGGBBAA.');
    const expand = (value: string) => (value.length === 7 ? `${value}ff` : value);
    const left = expand(from);
    const right = expand(to);
    return `#${[1, 3, 5, 7]
        .map((offset) => {
            const start = parseInt(left.slice(offset, offset + 2), 16);
            const end = parseInt(right.slice(offset, offset + 2), 16);
            return channel(start + (end - start) * progress);
        })
        .join('')}`;
}

function cubic(value: number, a: number, b: number): number {
    const inverse = 1 - value;
    return 3 * inverse * inverse * value * a + 3 * inverse * value * value * b + value ** 3;
}

function bezierProgress(progress: number, points: [number, number, number, number]): number {
    let low = 0;
    let high = 1;
    for (let iteration = 0; iteration < 14; iteration += 1) {
        const guess = (low + high) / 2;
        if (cubic(guess, points[0], points[2]) < progress) low = guess;
        else high = guess;
    }
    return cubic((low + high) / 2, points[1], points[3]);
}

function timingProgress(progress: number, easing: RuntimeTransition['easing']): number {
    if (progress === 0 || progress === 1) return progress;
    if (Array.isArray(easing)) {
        easing.forEach((value, index) =>
            finite(
                value,
                'Bézier control point',
                index % 2 === 0 ? 0 : -10,
                index % 2 === 0 ? 1 : 10
            )
        );
        return bezierProgress(progress, easing);
    }
    if (easing === 'linear') return progress;
    if (easing === 'easeIn') return progress * progress;
    if (easing === 'easeOut') return 1 - (1 - progress) ** 2;
    return progress < 0.5 ? 2 * progress * progress : 1 - (-2 * progress + 2) ** 2 / 2;
}

function springProgress(progress: number, transition: RuntimeTransition): number {
    const damping = finite(transition.damping ?? 12, 'damping', 0.1, 100);
    const stiffness = finite(transition.stiffness ?? 120, 'stiffness', 1, 1000);
    const mass = finite(transition.mass ?? 1, 'mass', 0.1, 20);
    const duration = transition.duration ?? 1200;
    const seconds = progress * (duration / 1000);
    const decay = Math.exp((-damping * seconds) / (2 * mass));
    const frequency = Math.sqrt(stiffness / mass);
    const raw = 1 - decay * Math.cos(frequency * seconds);
    const finalSeconds = duration / 1000;
    const final =
        1 - Math.exp((-damping * finalSeconds) / (2 * mass)) * Math.cos(frequency * finalSeconds);
    return final === 0 ? progress : raw / final;
}

function runtimeState(input: RuntimeAnimationState): SerializedRuntimeFrame {
    const output: SerializedRuntimeFrame = {};
    if (input.scale !== undefined) {
        const scale = finite(input.scale, 'scale', 0, 100);
        output.scaleX = scale;
        output.scaleY = scale;
    }
    for (const [name, value] of Object.entries(input)) {
        if (name === 'scale') continue;
        if (name === 'translateX' || name === 'translateY') output[name] = rem(value, name);
        else if (name === 'opacity') output[name] = finite(value, name, 0, 1);
        else if (name === 'rotate') output[name] = finite(value, name, -36000, 36000);
        else if (name === 'scaleX' || name === 'scaleY') output[name] = finite(value, name, 0, 100);
        else if (name === 'backgroundColor') {
            if (typeof value !== 'string' || !color.test(value))
                throw new Error('Runtime animation colors must use #RRGGBB or #RRGGBBAA.');
            output[name] = value;
        } else throw new Error(`Unsupported runtime animation property: ${name}`);
    }
    return output;
}

export function compileRuntimeFrames(
    fromInput: RuntimeAnimationState,
    animateInput: RuntimeAnimationState | RuntimeAnimationState[],
    transition: RuntimeTransition = {}
): { frames: SerializedRuntimeFrame[]; interval: number } {
    const targets = Array.isArray(animateInput) ? animateInput : [animateInput];
    if (targets.length < 1 || targets.length > 8)
        throw new Error('animate must contain 1–8 runtime states.');
    const states = [runtimeState(fromInput), ...targets.map(runtimeState)];
    const duration = finite(
        transition.duration ?? (transition.type === 'spring' ? 1200 : 800),
        'duration',
        100,
        30000
    );
    const frameDuration = transition.repeatReverse ? 66 : 33;
    const automaticFrameCount = Math.min(48, Math.max(2, Math.round(duration / frameDuration) + 1));
    const frameCount = finite(transition.frames ?? automaticFrameCount, 'frames', 2, 48);
    if (!Number.isInteger(frameCount)) throw new Error('frames must be an integer.');
    const keys = new Set(states.flatMap((state) => Object.keys(state)));
    const resolved: SerializedRuntimeFrame[] = [];
    states.forEach((state, stateIndex) => {
        const output: SerializedRuntimeFrame = {};
        for (const key of keys) {
            const previous = stateIndex === 0 ? undefined : resolved[stateIndex - 1][key];
            const future = states
                .slice(stateIndex)
                .find((candidate) => candidate[key] !== undefined)?.[key];
            output[key] =
                state[key] ??
                previous ??
                future ??
                (key.startsWith('scale') || key === 'opacity' ? 1 : 0);
        }
        resolved.push(output);
    });
    const forward = Array.from({ length: frameCount }, (_, index) => {
        const linear = index / (frameCount - 1);
        const segmentPosition = linear * (resolved.length - 1);
        const segment = Math.min(resolved.length - 2, Math.floor(segmentPosition));
        const segmentLinear = index === frameCount - 1 ? 1 : segmentPosition - segment;
        const progress =
            transition.type === 'spring'
                ? springProgress(segmentLinear, transition)
                : timingProgress(segmentLinear, transition.easing);
        const frame: SerializedRuntimeFrame = {};
        for (const key of keys) {
            const start = resolved[segment][key];
            const end = resolved[segment + 1][key];
            frame[key] =
                typeof start === 'string' || typeof end === 'string'
                    ? interpolateColor(
                          String(start),
                          String(end),
                          Math.max(0, Math.min(1, progress))
                      )
                    : Number(start) + (Number(end) - Number(start)) * progress;
        }
        return frame;
    });
    const frames = transition.repeatReverse
        ? [...forward, ...forward.slice(1, -1).reverse()]
        : forward;
    return {
        frames,
        interval: Math.max(16, Math.round(duration / Math.max(1, frames.length - 1))),
    };
}

export function serializeScene(scene: ReactNode): SerializedNode {
    let count = 0;
    const ids = new Set<string>();
    function visit(child: ReactNode, path: string, depth: number): SerializedNode {
        if (++count > 96 || depth > 7)
            throw new Error('Notification scenes support up to 96 nodes and 7 nesting levels.');
        if (!isValidElement<NotiProps & Record<string, unknown>>(child))
            throw new Error('Use Noti.Text for notification text.');
        const type = (child.type as { notificationPrimitive?: SerializedNode['type'] })
            .notificationPrimitive;
        if (!type)
            throw new Error(
                'Only Noti primitives and React fragments are supported. Call scene helpers before passing their result.'
            );
        const props = child.props;
        if (props.id) {
            if (ids.has(props.id as string))
                throw new Error(`Duplicate notification node id: ${props.id}`);
            ids.add(props.id as string);
        }
        const node: SerializedNode = {
            type,
            key: child.key === null ? path : String(child.key),
            id: props.id as string | undefined,
            style: serializeStyle(props.style as NotiStyle | undefined),
            animation: props.animation as string | undefined,
            enter: props.enter as string | undefined,
            exit: props.exit as string | undefined,
            transitionKey: props.transitionKey as string | number | undefined,
            accessibilityLabel: props.accessibilityLabel as string | undefined,
            onPress: props.onPress as string | undefined,
            children: [],
        };
        for (const name of [node.animation, node.enter, node.exit]) {
            if (name && !animationName.test(name)) throw new Error('Invalid animation name.');
        }
        const children = flatten(props.children as ReactNode);
        if (type === 'text' || type === 'button') {
            if (children.some((value) => typeof value !== 'string' && typeof value !== 'number'))
                throw new Error(
                    `Noti.${type === 'button' ? 'Button' : 'Text'} accepts text and numbers only.`
                );
            node.text = children.join('');
            node.numberOfLines = (props.numberOfLines as number | undefined) ?? 1;
            if (
                !Number.isInteger(node.numberOfLines) ||
                node.numberOfLines < 1 ||
                node.numberOfLines > 10
            )
                throw new Error('numberOfLines must be 1–10.');
            if (type === 'button' && !node.onPress)
                throw new Error('Noti.Button requires onPress.');
        } else if (type === 'image' || type === 'gif') {
            const sourcePattern =
                type === 'gif'
                    ? /^(data:image\/gif;base64,|https:\/\/|file:\/\/|content:\/\/)/
                    : /^(data:image\/[\w.+-]+;base64,|file:\/\/|content:\/\/)/;
            if (typeof props.source !== 'string' || !sourcePattern.test(props.source))
                throw new Error(
                    type === 'gif'
                        ? 'GIF source must be an HTTPS URL, GIF data URI, file URI or content URI.'
                        : 'Image source must be a base64 data URI, file URI or content URI.'
                );
            node.source = props.source;
            node.resizeMode = (props.resizeMode as string | undefined) ?? 'cover';
            if (!['cover', 'contain', 'stretch'].includes(node.resizeMode))
                throw new Error('Invalid resizeMode.');
            if (type === 'gif') {
                node.maxFrames = finite(props.maxFrames ?? 30, 'maxFrames', 2, 60);
                if (!Number.isInteger(node.maxFrames))
                    throw new Error('maxFrames must be an integer.');
                if (props.interval !== undefined) {
                    node.interval = finite(props.interval, 'interval', 20, 60000);
                    if (!Number.isInteger(node.interval))
                        throw new Error('interval must be an integer.');
                }
                node.optimization = (props.optimization as string | undefined) ?? 'automatic';
                if (!['automatic', 'smoothness', 'quality', 'none'].includes(node.optimization))
                    throw new Error('Invalid GIF optimization strategy.');
                node.maxBytes = finite(props.maxBytes ?? 2_000_000, 'maxBytes', 256_000, 3_500_000);
                if (!Number.isInteger(node.maxBytes))
                    throw new Error('maxBytes must be an integer.');
                if (props.fallbackSource !== undefined) {
                    if (
                        typeof props.fallbackSource !== 'string' ||
                        !/^(data:image\/[\w.+-]+;base64,|file:\/\/|content:\/\/)/.test(
                            props.fallbackSource
                        )
                    )
                        throw new Error(
                            'GIF fallbackSource must be a base64 data URI, file URI or content URI.'
                        );
                    node.fallbackSource = props.fallbackSource;
                }
            }
        } else if (type === 'progress') {
            node.max = finite(props.max ?? 100, 'max', 1, 1_000_000);
            node.value = finite(props.value, 'value', 0, node.max);
            node.indeterminate = props.indeterminate === true;
            for (const name of ['color', 'trackColor'] as const) {
                const value = props[name];
                if (value !== undefined && (typeof value !== 'string' || !color.test(value)))
                    throw new Error(`${name} must use #RRGGBB or #RRGGBBAA.`);
                node[name] = value as string | undefined;
            }
        } else if (type === 'chronometer') {
            node.base = finite(props.base, 'base', 0, Number.MAX_SAFE_INTEGER);
            node.started = props.started !== false;
            node.countDown = props.countDown === true;
            node.format = typeof props.format === 'string' ? props.format : undefined;
        } else if (type !== 'spacer') {
            node.direction =
                type === 'presence' || type === 'crossfade'
                    ? 'overlay'
                    : ((props.direction as string | undefined) ?? 'column');
            if (!['row', 'column', 'overlay'].includes(node.direction))
                throw new Error('Invalid layout direction.');
            node.children = children.map((value, index) =>
                visit(value, `${path}.${index}`, depth + 1)
            );
            if (
                node.children.some((value) => value.style.position === 'absolute') &&
                node.direction !== 'overlay'
            )
                throw new Error('Absolute children require a Noti.View with direction="overlay".');
            if (new Set(node.children.map((value) => value.key)).size !== node.children.length)
                throw new Error('Sibling notification keys must be unique.');
        }
        if (type === 'grid') {
            node.columns = finite(props.columns, 'columns', 1, 6);
            node.rows = props.rows === undefined ? undefined : finite(props.rows, 'rows', 1, 8);
            if (
                !Number.isInteger(node.columns) ||
                (node.rows !== undefined && !Number.isInteger(node.rows))
            )
                throw new Error('Grid rows and columns must be integers.');
        }
        if (type === 'scroll') {
            if (!props.id) throw new Error('Noti.ScrollView requires an id.');
            node.itemHeight = rem(props.itemHeight, 'itemHeight');
            node.windowSize = (props.windowSize as number | undefined) ?? 3;
            node.index = (props.index as number | undefined) ?? 0;
            if (
                node.itemHeight <= 0 ||
                !Number.isInteger(node.windowSize) ||
                node.windowSize < 1 ||
                node.windowSize > 7
            )
                throw new Error('ScrollView needs positive itemHeight and windowSize 1–7.');
            if (
                !Number.isInteger(node.index) ||
                node.index < 0 ||
                node.index > Math.max(0, node.children.length - node.windowSize)
            )
                throw new Error('ScrollView index is outside its content window.');
            node.autoPlay = props.autoPlay === true;
            if (node.autoPlay) {
                if (node.children.length <= node.windowSize || node.children.length > 12)
                    throw new Error('Auto-playing ScrollView requires windowSize + 1 to 12 rows.');
                node.interval = finite(props.interval ?? 1400, 'interval', 100, 60000);
                if (!Number.isInteger(node.interval))
                    throw new Error('ScrollView interval must be an integer.');
            }
            node.style.height = node.itemHeight * node.windowSize;
        }
        if (type === 'flipper') {
            if (node.children.length < 2 || node.children.length > 12)
                throw new Error('Noti.ViewFlipper needs 2–12 children.');
            node.interval = finite(props.interval ?? 1400, 'interval', 100, 60000);
            node.index = finite(props.index ?? 0, 'index', 0, node.children.length - 1);
            node.autoPlay = props.autoPlay !== false;
            node.animation = node.animation ?? 'fade';
            if (!Number.isInteger(node.interval) || !Number.isInteger(node.index))
                throw new Error('ViewFlipper interval and index must be integers.');
        }
        if (type === 'loop') {
            if (node.children.length !== 1)
                throw new Error('Noti.Loop requires exactly one child.');
            const runtime = compileRuntimeFrames(
                props.from as RuntimeAnimationState,
                props.animate as RuntimeAnimationState,
                props.transition as RuntimeTransition | undefined
            );
            node.runtimeFrames = runtime.frames;
            node.interval = runtime.interval;
        }
        return node;
    }
    const roots = flatten(scene);
    if (roots.length !== 1)
        throw new Error('A notification scene needs exactly one root Noti component.');
    return visit(roots[0], 'root', 0);
}
