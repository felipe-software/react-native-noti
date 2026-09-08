const fs = require('node:fs');
const path = require('node:path');

const presets = {
    fade: { from: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 } },
    crossfade: {
        from: { opacity: 0 },
        animate: { opacity: 1 },
        exit: { opacity: 0 },
        transition: { duration: 900, easing: 'linear' },
    },
    slideUp: {
        from: { opacity: 0, translateY: '1rem' },
        animate: { opacity: 1, translateY: 0 },
        exit: { opacity: 0, translateY: '-1rem' },
    },
    slideDown: {
        from: { opacity: 0, translateY: '-1rem' },
        animate: { opacity: 1, translateY: 0 },
        exit: { opacity: 0, translateY: '1rem' },
    },
    zoom: {
        from: { opacity: 0, scale: 0.85 },
        animate: { opacity: 1, scale: 1 },
        exit: { opacity: 0, scale: 1.1 },
    },
    spin: {
        from: { rotate: 0 },
        animate: { rotate: 360 },
        transition: { duration: 4000, easing: 'linear', repeat: -1 },
    },
};
const xmlHeader = '<?xml version="1.0" encoding="utf-8"?>\n';
const namespace = 'xmlns:android="http://schemas.android.com/apk/res/android"';
const properties = new Set([
    'opacity',
    'translateX',
    'translateY',
    'scale',
    'scaleX',
    'scaleY',
    'rotate',
]);

function finite(value, label, min, max) {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max)
        throw new Error(`${label} must be between ${min} and ${max}.`);
    return value;
}
function delta(value = 0) {
    if (value === 0) return '0';
    if (typeof value !== 'string') throw new Error('Translations use rem, percentages, or 0.');
    if (/^-?(?:\d+(?:\.\d+)?|\.\d+)%$/.test(value)) return value;
    if (/^-?(?:\d+(?:\.\d+)?|\.\d+)rem$/.test(value))
        return `${finite(parseFloat(value), 'translation', -512, 512) * 16}dip`;
    throw new Error('Translations use rem, percentages, or 0.');
}
function state(value = {}) {
    for (const [key, item] of Object.entries(value)) {
        if (!properties.has(key)) throw new Error(`Unsupported animation property: ${key}`);
        if (key.startsWith('translate')) delta(item);
        else
            finite(
                item,
                key,
                key === 'opacity' || key.startsWith('scale') ? 0 : -36000,
                key === 'opacity' ? 1 : 36000
            );
    }
    return value;
}
function compileAnimation(name, definition, outgoing = false, fillAfter = true) {
    const from = state(outgoing ? definition.animate : definition.from);
    const to = state(outgoing ? (definition.exit ?? { opacity: 0 }) : definition.animate);
    const transition = definition.transition ?? {};
    const duration = finite(transition.duration ?? 300, 'duration', 1, 10000);
    const delay = finite(transition.delay ?? 0, 'delay', 0, 10000);
    if (!Number.isInteger(duration) || !Number.isInteger(delay))
        throw new Error('duration and delay must be integer milliseconds.');
    const repeat = outgoing ? 0 : finite(transition.repeat ?? 0, 'repeat', -1, 100);
    if (!Number.isInteger(repeat)) throw new Error('repeat must be an integer.');
    const easing = transition.easing ?? 'easeInOut';
    const interpolators = {
        linear: '@android:interpolator/linear',
        easeIn: '@android:interpolator/accelerate_quad',
        easeOut: '@android:interpolator/decelerate_quad',
        easeInOut: '@android:interpolator/fast_out_slow_in',
    };
    if (!interpolators[easing] && !(Array.isArray(easing) && easing.length === 4))
        throw new Error('easing must be a named easing or a cubic Bézier tuple.');
    const interpolator = interpolators[easing] ?? `@interpolator/nm_${name}`;
    const timing = `android:duration="${duration}" android:startOffset="${delay}" android:repeatCount="${repeat}" android:repeatMode="${transition.repeatReverse ? 'reverse' : 'restart'}"`;
    const rows = [];
    if ('opacity' in from || 'opacity' in to)
        rows.push(
            `<alpha android:fromAlpha="${from.opacity ?? 1}" android:toAlpha="${to.opacity ?? 1}" ${timing} />`
        );
    if (['translateX', 'translateY'].some((key) => key in from || key in to))
        rows.push(
            `<translate android:fromXDelta="${delta(from.translateX)}" android:toXDelta="${delta(to.translateX)}" android:fromYDelta="${delta(from.translateY)}" android:toYDelta="${delta(to.translateY)}" ${timing} />`
        );
    if (['scale', 'scaleX', 'scaleY'].some((key) => key in from || key in to))
        rows.push(
            `<scale android:fromXScale="${from.scaleX ?? from.scale ?? 1}" android:toXScale="${to.scaleX ?? to.scale ?? 1}" android:fromYScale="${from.scaleY ?? from.scale ?? 1}" android:toYScale="${to.scaleY ?? to.scale ?? 1}" android:pivotX="50%" android:pivotY="50%" ${timing} />`
        );
    if ('rotate' in from || 'rotate' in to)
        rows.push(
            `<rotate android:fromDegrees="${from.rotate ?? 0}" android:toDegrees="${to.rotate ?? 0}" android:pivotX="50%" android:pivotY="50%" ${timing} />`
        );
    if (!rows.length) rows.push(`<alpha android:fromAlpha="1" android:toAlpha="1" ${timing} />`);
    return (
        xmlHeader +
        `<set ${namespace} android:fillAfter="${fillAfter}" android:shareInterpolator="true" android:interpolator="${interpolator}">\n${rows.join('\n')}\n</set>\n`
    );
}

function shell(inner, gravity = 'top|left', clipsOutline = true) {
    return (
        xmlHeader +
        `<FrameLayout ${namespace} android:id="@+id/nm_node" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_gravity="${gravity}" android:clipToOutline="${clipsOutline}" android:clipChildren="false" android:clipToPadding="false">${inner}</FrameLayout>\n`
    );
}

const anchors = {
    tl: 'top|left',
    tc: 'top|center_horizontal',
    tr: 'top|right',
    cl: 'center_vertical|left',
    cc: 'center',
    cr: 'center_vertical|right',
    bl: 'bottom|left',
    bc: 'bottom|center_horizontal',
    br: 'bottom|right',
};

function flippers(name, clipsChildren = false) {
    return ['in', 'out']
        .map(
            (side) =>
                `<ViewFlipper android:id="@+id/nm_${side}" android:layout_width="match_parent" android:layout_height="wrap_content" android:clipChildren="${clipsChildren}" android:clipToPadding="${clipsChildren}" android:animateFirstView="false" android:measureAllChildren="true" ${side === 'out' ? 'android:importantForAccessibility="noHideDescendants"' : ''} android:inAnimation="@anim/nm_${name}_${side}">${['a', 'b'].map((slot) => `<FrameLayout android:id="@+id/nm_${side}_${slot}" android:layout_width="match_parent" android:layout_height="wrap_content" android:clipChildren="false" android:clipToPadding="false" />`).join('')}</ViewFlipper>`
        )
        .join('');
}

function resources(custom = {}, includeBase = false) {
    for (const name of Object.keys(custom)) {
        if (!/^[a-z][a-zA-Z0-9_]*$/.test(name) || name.startsWith('scroll_'))
            throw new Error(`Invalid or reserved animation name: ${name}`);
    }
    const animations = { ...presets, ...custom };
    for (let size = 1; size <= 7; size++) {
        for (const [direction, sign] of [
            ['up', 1],
            ['down', -1],
        ]) {
            animations[`scroll_${direction}_${size}`] = {
                from: { translateY: `${(sign * 100) / size}%` },
                animate: { translateY: 0 },
                exit: { translateY: `${(-sign * 100) / size}%` },
            };
        }
    }
    const files = {};
    for (const [key, definition] of Object.entries(animations)) {
        const name = key.toLowerCase();
        const isScroll = name.startsWith('scroll_');
        if (files[`anim/nm_${name}_in.xml`])
            throw new Error('Animation names must be unique ignoring case.');
        files[`anim/nm_${name}_in.xml`] = compileAnimation(name, definition, false, !isScroll);
        files[`anim/nm_${name}_out.xml`] = compileAnimation(name, definition, true, !isScroll);
        const easing = definition.transition?.easing;
        if (Array.isArray(easing)) {
            easing.forEach((value, index) =>
                finite(
                    value,
                    'Bézier control point',
                    index % 2 === 0 ? 0 : -10,
                    index % 2 === 0 ? 1 : 10
                )
            );
            files[`interpolator/nm_${name}.xml`] =
                xmlHeader +
                `<pathInterpolator ${namespace} android:controlX1="${easing[0]}" android:controlY1="${easing[1]}" android:controlX2="${easing[2]}" android:controlY2="${easing[3]}" />\n`;
        }
        for (const [anchor, gravity] of Object.entries(anchors)) {
            files[`layout/nm_motion_${name}_${anchor}.xml`] = shell(
                flippers(name, isScroll),
                gravity,
                false
            );
            files[`layout/nm_flipper_${name}_${anchor}.xml`] = shell(
                `<ViewFlipper android:id="@+id/nm_content" android:layout_width="match_parent" android:layout_height="match_parent" android:layout_gravity="${gravity}" android:clipChildren="${isScroll}" android:clipToPadding="${isScroll}" android:autoStart="true" android:animateFirstView="false" android:measureAllChildren="true" android:inAnimation="@anim/nm_${name}_in" android:outAnimation="@anim/nm_${name}_out" />`,
                gravity,
                false
            );
        }
    }
    if (includeBase) {
        const size = 'android:layout_width="match_parent" android:layout_height="wrap_content"';
        for (const [anchor, gravity] of Object.entries(anchors)) {
            for (const direction of ['column', 'row', 'overlay']) {
                const tag = direction === 'overlay' ? 'FrameLayout' : 'LinearLayout';
                files[`layout/nm_view_${direction}_${anchor}.xml`] = shell(
                    `<${tag} android:id="@+id/nm_content" ${size} android:clipChildren="false" android:clipToPadding="false" ${direction === 'overlay' ? '' : `android:orientation="${direction === 'row' ? 'horizontal' : 'vertical'}"`} /><FrameLayout android:id="@+id/nm_overlay" android:layout_width="match_parent" android:layout_height="match_parent" android:clipChildren="false" android:clipToPadding="false" />`,
                    gravity
                );
            }
            files[`layout/nm_grid_${anchor}.xml`] = shell(
                `<GridLayout android:id="@+id/nm_content" ${size} android:clipChildren="false" android:clipToPadding="false" /><FrameLayout android:id="@+id/nm_overlay" android:layout_width="match_parent" android:layout_height="match_parent" android:clipChildren="false" android:clipToPadding="false" />`,
                gravity
            );
            files[`layout/nm_text_${anchor}.xml`] = shell(
                `<TextView android:id="@+id/nm_content" ${size} android:textColor="#ffffff" android:textSize="14sp" android:ellipsize="end" />`,
                gravity
            );
            files[`layout/nm_progress_${anchor}.xml`] = shell(
                `<ProgressBar android:id="@+id/nm_content" style="?android:attr/progressBarStyleHorizontal" android:layout_width="match_parent" android:layout_height="wrap_content" />`,
                gravity
            );
            files[`layout/nm_chronometer_${anchor}.xml`] = shell(
                `<Chronometer android:id="@+id/nm_content" ${size} android:textColor="#ffffff" android:textSize="14sp" />`,
                gravity
            );
            files[`layout/nm_runtime_${anchor}.xml`] = shell(
                `<ViewFlipper android:id="@+id/nm_content" android:layout_width="match_parent" android:layout_height="match_parent" android:layout_gravity="${gravity}" android:clipChildren="false" android:clipToPadding="false" android:autoStart="true" android:animateFirstView="false" android:measureAllChildren="false" />`,
                gravity,
                false
            );
            for (const [mode, scale] of Object.entries({
                cover: 'centerCrop',
                contain: 'fitCenter',
                stretch: 'fitXY',
            })) {
                files[`layout/nm_image_${mode}_${anchor}.xml`] = shell(
                    `<ImageView android:id="@+id/nm_content" android:layout_width="match_parent" android:layout_height="match_parent" android:scaleType="${scale}" />`,
                    gravity
                );
            }
        }
        files['drawable/nm_small_icon.xml'] =
            xmlHeader +
            `<vector ${namespace} android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#ffffff" android:pathData="M4,4h16v12H9l-5,4z" /></vector>\n`;
    }
    return files;
}
function writeResources(directory, files) {
    for (const [relative, contents] of Object.entries(files)) {
        const file = path.join(directory, relative);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, contents);
    }
}
module.exports = { compileAnimation, resources, writeResources };
