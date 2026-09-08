import type { ReactNode } from 'react';
import { Noti, type NotificationScene } from 'react-native-noti';
import { artA, artB } from './art';
import { orbitGif } from './gif';

type Palette = { background: string; panel: string; accent: string; text: string; muted: string };

const palettes: Palette[] = [
    {
        background: '#111827',
        panel: '#243047',
        accent: '#fbbf24',
        text: '#ffffff',
        muted: '#b6c2d2',
    },
    {
        background: '#160f25',
        panel: '#33204f',
        accent: '#c084fc',
        text: '#faf5ff',
        muted: '#d8b4fe',
    },
    {
        background: '#071d1a',
        panel: '#123d36',
        accent: '#5eead4',
        text: '#f0fdfa',
        muted: '#99f6e4',
    },
    {
        background: '#241014',
        panel: '#4c1d28',
        accent: '#fb7185',
        text: '#fff1f2',
        muted: '#fecdd3',
    },
    {
        background: '#071b2b',
        panel: '#12344f',
        accent: '#38bdf8',
        text: '#f0f9ff',
        muted: '#bae6fd',
    },
    {
        background: '#211607',
        panel: '#493212',
        accent: '#fb923c',
        text: '#fff7ed',
        muted: '#fed7aa',
    },
    {
        background: '#171717',
        panel: '#303030',
        accent: '#e5e5e5',
        text: '#ffffff',
        muted: '#a3a3a3',
    },
    {
        background: '#191028',
        panel: '#382259',
        accent: '#a78bfa',
        text: '#f5f3ff',
        muted: '#ddd6fe',
    },
    {
        background: '#071c28',
        panel: '#12354a',
        accent: '#22d3ee',
        text: '#ecfeff',
        muted: '#a5f3fc',
    },
    {
        background: '#201009',
        panel: '#4b2816',
        accent: '#facc15',
        text: '#fefce8',
        muted: '#fef08a',
    },
    {
        background: '#081d12',
        panel: '#163b27',
        accent: '#4ade80',
        text: '#f0fdf4',
        muted: '#bbf7d0',
    },
    {
        background: '#180f20',
        panel: '#352044',
        accent: '#f472b6',
        text: '#fdf2f8',
        muted: '#fbcfe8',
    },
];

export const examples = [
    'Centered Action',
    'Absolute Orbit',
    'GIF Frames',
    'Spring Scale',
    'Keyframe Wave',
    'Fade Stories',
    'Drift Carousel',
    'Presence Swap',
    'Lyric Scroll',
    'Metric Grid',
    'Live Countdown',
    'BG Crossfade',
] as const;

const scrollRows = [
    'Before the city wakes',
    'Signals cross the dark',
    'Every window moving',
    'Hold the current line',
    'Let the old words rise',
    'Keep the next one close',
    'Motion follows time',
    'Light returns in frames',
    'Then the loop begins',
];

function action(label: string, key: string, palette: Palette, width: `${number}rem` = '4.5rem') {
    return (
        <Noti.Button
            key={key}
            id={key}
            onPress="pulse-example"
            style={{
                width,
                height: '2.25rem',
                borderRadius: '1.125rem',
                backgroundColor: palette.accent,
                color: '#09090b',
                fontSize: '.65rem',
                fontWeight: 'bold',
                textAlign: 'center',
                verticalAlign: 'center',
            }}
        >
            {label}
        </Noti.Button>
    );
}

function exampleContent(index: number, phase: number, palette: Palette): ReactNode {
    switch (index) {
        case 0:
            return (
                <Noti.View
                    key="center"
                    direction="overlay"
                    style={{ width: '100%', height: '12rem', padding: '1rem' }}
                >
                    <Noti.View
                        style={{
                            position: 'absolute',
                            anchor: 'center',
                            width: '100%',
                            height: '9rem',
                            borderRadius: '1.25rem',
                            backgroundColor: palette.panel,
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: '.65rem',
                        }}
                    >
                        <Noti.Text style={{ color: palette.muted, fontSize: '.68rem' }}>
                            EXPLICIT HEIGHT
                        </Noti.Text>
                        {action('PERFECT CENTER', 'center-action', palette, '8.5rem')}
                    </Noti.View>
                </Noti.View>
            );
        case 1:
            return (
                <Noti.View
                    key="absolute"
                    direction="overlay"
                    style={{ width: '100%', height: '12rem' }}
                >
                    {[
                        ['1rem', '.5rem', '2.75rem'],
                        ['6.5rem', '2.7rem', '3.5rem'],
                        ['12.5rem', '.8rem', '2.25rem'],
                    ].map(([left, top, size], dot) => (
                        <Noti.Loop
                            key={`orbit-${dot}`}
                            from={{ scale: 0.55, opacity: 0.35, rotate: -20 }}
                            animate={{ scale: 1.12, opacity: 1, rotate: 20, translateY: '-.35rem' }}
                            transition={{
                                duration: 700 + dot * 260,
                                frames: 8,
                                repeatReverse: true,
                                easing: 'easeInOut',
                            }}
                            style={{
                                position: 'absolute',
                                left: left as `${number}rem`,
                                top: top as `${number}rem`,
                                width: size as `${number}rem`,
                                height: size as `${number}rem`,
                            }}
                        >
                            <Noti.View
                                style={{
                                    width: size as `${number}rem`,
                                    height: size as `${number}rem`,
                                    borderRadius: '1.4rem',
                                    backgroundColor: dot === 1 ? palette.accent : palette.panel,
                                }}
                            />
                        </Noti.Loop>
                    ))}
                    <Noti.Text
                        style={{
                            position: 'absolute',
                            left: '1rem',
                            bottom: '.5rem',
                            color: palette.text,
                            fontSize: '.8rem',
                            fontWeight: 'bold',
                        }}
                    >
                        ABSOLUTE / LOOP
                    </Noti.Text>
                </Noti.View>
            );
        case 2:
            return (
                <Noti.View
                    key="gif"
                    direction="row"
                    style={{
                        width: '100%',
                        height: '12rem',
                        padding: '.75rem',
                        gap: '.75rem',
                        borderRadius: '1rem',
                        backgroundColor: palette.panel,
                        alignItems: 'center',
                    }}
                >
                    <Noti.View
                        style={{
                            width: '5.25rem',
                            height: '5.25rem',
                            borderRadius: '1rem',
                            backgroundColor: palette.background,
                        }}
                    >
                        <Noti.Gif
                            source={orbitGif}
                            fallbackSource={artA}
                            maxFrames={30}
                            optimization="automatic"
                            resizeMode="stretch"
                            accessibilityLabel="Animated orbit GIF"
                            style={{ width: '100%', height: '100%' }}
                        />
                    </Noti.View>
                    <Noti.View
                        style={{
                            width: '6.75rem',
                            height: '5rem',
                            justifyContent: 'center',
                            gap: '.4rem',
                        }}
                    >
                        <Noti.Text
                            style={{ color: palette.text, fontSize: '1rem', fontWeight: 'bold' }}
                        >
                            GIF FRAMES
                        </Noti.Text>
                        <Noti.Text style={{ color: palette.muted, fontSize: '.68rem' }}>
                            UP TO 30 FRAMES
                        </Noti.Text>
                    </Noti.View>
                </Noti.View>
            );
        case 3:
            return (
                <Noti.View
                    key="spring"
                    direction="row"
                    style={{
                        width: '100%',
                        height: '12rem',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: '1.25rem',
                    }}
                >
                    <Noti.Loop
                        from={{
                            scale: 0.35,
                            opacity: 0.25,
                            rotate: -35,
                            backgroundColor: palette.panel,
                        }}
                        animate={[
                            {
                                scale: 1.28,
                                opacity: 1,
                                rotate: 18,
                                backgroundColor: palette.accent,
                            },
                            { scale: 1, rotate: 0, backgroundColor: palette.panel },
                        ]}
                        transition={{
                            type: 'spring',
                            duration: 1450,
                            damping: 8,
                            stiffness: 170,
                            frames: 24,
                            repeatReverse: true,
                        }}
                        style={{ width: '5rem', height: '5rem' }}
                    >
                        <Noti.View
                            style={{
                                anchor: 'center',
                                width: '3.6rem',
                                height: '3.6rem',
                                borderRadius: '1rem',
                                backgroundColor: palette.accent,
                                alignItems: 'center',
                                justifyContent: 'center',
                            }}
                        >
                            <Noti.Text
                                style={{
                                    color: '#09090b',
                                    fontSize: '1.25rem',
                                    fontWeight: 'bold',
                                }}
                            >
                                S
                            </Noti.Text>
                        </Noti.View>
                    </Noti.Loop>
                    <Noti.Text
                        numberOfLines={2}
                        style={{
                            width: '7rem',
                            color: palette.text,
                            fontSize: '1.1rem',
                            fontWeight: 'bold',
                        }}
                    >
                        SPRING SCALE
                    </Noti.Text>
                </Noti.View>
            );
        case 4:
            return (
                <Noti.View
                    key="wave"
                    direction="row"
                    style={{
                        width: '100%',
                        height: '12rem',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: '.55rem',
                        backgroundColor: palette.panel,
                        borderRadius: '1rem',
                    }}
                >
                    {[0, 1, 2, 3, 4, 5, 6].map((bar) => (
                        <Noti.Loop
                            key={`bar-${bar}`}
                            from={{ scaleY: 0.2, opacity: 0.4, backgroundColor: palette.muted }}
                            animate={{ scaleY: 1.4, opacity: 1, backgroundColor: palette.accent }}
                            transition={{
                                duration: 460 + bar * 90,
                                frames: 6,
                                repeatReverse: true,
                                easing: 'easeInOut',
                            }}
                            style={{ width: '1rem', height: '4.5rem' }}
                        >
                            <Noti.View
                                style={{
                                    anchor: 'center',
                                    width: '.65rem',
                                    height: '3rem',
                                    borderRadius: '.35rem',
                                    backgroundColor: palette.accent,
                                }}
                            />
                        </Noti.Loop>
                    ))}
                </Noti.View>
            );
        case 5:
            return (
                <Noti.ViewFlipper
                    key="fade-stories"
                    animation="fade"
                    interval={900}
                    style={{ width: '100%', height: '12rem' }}
                >
                    {['FOCUS', 'BUILD', 'SHIP'].map((word, slide) => (
                        <Noti.View
                            key={word}
                            style={{
                                width: '100%',
                                height: '12rem',
                                borderRadius: '1rem',
                                backgroundColor: slide === 1 ? palette.accent : palette.panel,
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: '.35rem',
                            }}
                        >
                            <Noti.Text
                                style={{
                                    color: slide === 1 ? '#09090b' : palette.muted,
                                    fontSize: '.65rem',
                                }}
                            >
                                0{slide + 1}
                            </Noti.Text>
                            <Noti.Text
                                style={{
                                    color: slide === 1 ? '#09090b' : palette.text,
                                    fontSize: '1.5rem',
                                    fontWeight: 'bold',
                                }}
                            >
                                {word}
                            </Noti.Text>
                        </Noti.View>
                    ))}
                </Noti.ViewFlipper>
            );
        case 6:
            return (
                <Noti.ViewFlipper
                    key="drift-carousel"
                    animation="drift"
                    interval={1050}
                    style={{ width: '100%', height: '12rem' }}
                >
                    {['TRANSLATE', 'SCALE', 'ALPHA'].map((word, slide) => (
                        <Noti.View
                            key={word}
                            direction="row"
                            style={{
                                width: '100%',
                                height: '12rem',
                                padding: '1rem',
                                gap: '1rem',
                                borderRadius: '1rem',
                                backgroundColor: palette.panel,
                                alignItems: 'center',
                            }}
                        >
                            <Noti.Text
                                style={{
                                    color: palette.accent,
                                    fontSize: '2rem',
                                    fontWeight: 'bold',
                                }}
                            >
                                {slide + 1}
                            </Noti.Text>
                            <Noti.Text
                                style={{
                                    color: palette.text,
                                    fontSize: '1rem',
                                    fontWeight: 'bold',
                                }}
                            >
                                {word}
                            </Noti.Text>
                        </Noti.View>
                    ))}
                </Noti.ViewFlipper>
            );
        case 7: {
            const states = ['READY', 'RUNNING', 'DONE'];
            const state = phase % states.length;
            return (
                <Noti.AnimatePresence
                    key="presence"
                    enter="zoom"
                    exit="fade"
                    style={{ width: '100%', height: '12rem' }}
                >
                    <Noti.View
                        key={`presence-${state}`}
                        style={{
                            width: '100%',
                            height: '12rem',
                            borderRadius: '1rem',
                            backgroundColor: state === 2 ? palette.accent : palette.panel,
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: '.65rem',
                        }}
                    >
                        <Noti.Text
                            style={{
                                color: state === 2 ? '#09090b' : palette.text,
                                fontSize: '1.35rem',
                                fontWeight: 'bold',
                            }}
                        >
                            {states[state]}
                        </Noti.Text>
                        {action('SWAP', 'presence-swap', palette, '5rem')}
                    </Noti.View>
                </Noti.AnimatePresence>
            );
        }
        case 8: {
            const scrollIndex = phase % 5;
            return (
                <Noti.ScrollView
                    key="scroll"
                    id="demo-scroll"
                    index={scrollIndex}
                    windowSize={5}
                    itemHeight="2.4rem"
                    autoPlay
                    interval={1600}
                    enter="slideUp"
                    exit="slideDown"
                    style={{
                        width: '100%',
                        height: '12rem',
                        paddingHorizontal: '1rem',
                        borderRadius: '1rem',
                        backgroundColor: palette.panel,
                    }}
                >
                    {scrollRows.map((row, rowIndex) => (
                        <Noti.Text
                            key={row}
                            style={{
                                height: '2.4rem',
                                color:
                                    rowIndex === scrollIndex + 2 ? palette.accent : palette.muted,
                                fontSize: rowIndex === scrollIndex + 2 ? '.9rem' : '.65rem',
                                fontWeight: rowIndex === scrollIndex + 2 ? 'bold' : 'normal',
                                verticalAlign: 'center',
                                textAlign: 'center',
                            }}
                        >
                            {row}
                        </Noti.Text>
                    ))}
                </Noti.ScrollView>
            );
        }
        case 9:
            return (
                <Noti.Grid
                    key="grid"
                    columns={3}
                    rows={2}
                    style={{ width: '100%', height: '12rem', gap: '.5rem' }}
                >
                    {['CPU', 'RAM', 'FPS', 'NET', 'IO', 'GPU'].map((metric, tile) => (
                        <Noti.View
                            key={metric}
                            style={{
                                width: '5rem',
                                height: '5.75rem',
                                borderRadius: '.75rem',
                                backgroundColor:
                                    tile === phase % 6 ? palette.accent : palette.panel,
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: '.2rem',
                            }}
                        >
                            <Noti.Text
                                style={{
                                    color: tile === phase % 6 ? '#09090b' : palette.muted,
                                    fontSize: '.58rem',
                                }}
                            >
                                {metric}
                            </Noti.Text>
                            <Noti.Text
                                style={{
                                    color: tile === phase % 6 ? '#09090b' : palette.text,
                                    fontSize: '1rem',
                                    fontWeight: 'bold',
                                }}
                            >
                                {47 + tile * 7}
                            </Noti.Text>
                        </Noti.View>
                    ))}
                </Noti.Grid>
            );
        case 10:
            return (
                <Noti.View
                    key="countdown"
                    style={{
                        width: '100%',
                        height: '12rem',
                        padding: '1rem',
                        borderRadius: '1rem',
                        backgroundColor: palette.panel,
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: '.75rem',
                    }}
                >
                    <Noti.Text
                        style={{ color: palette.muted, fontSize: '.62rem', fontWeight: 'bold' }}
                    >
                        LIVE CHRONOMETER
                    </Noti.Text>
                    <Noti.Chronometer
                        base={Date.now() + 300_000}
                        countDown
                        format="%s"
                        style={{
                            width: '12rem',
                            height: '2.5rem',
                            color: palette.text,
                            fontSize: '1.75rem',
                            fontWeight: 'bold',
                            textAlign: 'center',
                            verticalAlign: 'center',
                        }}
                    />
                    <Noti.Progress
                        value={0}
                        max={100}
                        indeterminate
                        color={palette.accent}
                        trackColor={palette.background}
                        style={{ width: '13rem', height: '.4rem' }}
                    />
                </Noti.View>
            );
        default:
            return (
                <Noti.View
                    key="image"
                    direction="overlay"
                    style={{
                        width: '100%',
                        height: '12rem',
                        borderRadius: '1rem',
                        backgroundColor: palette.panel,
                    }}
                >
                    <Noti.ViewFlipper
                        key="background-crossfade"
                        animation="crossfade"
                        interval={1800}
                        style={{ position: 'absolute', width: '100%', height: '100%' }}
                    >
                        <Noti.Image
                            key="art-a"
                            source={artA}
                            resizeMode="stretch"
                            style={{ width: '100%', height: '100%' }}
                        />
                        <Noti.Image
                            key="art-b"
                            source={artB}
                            resizeMode="stretch"
                            style={{ width: '100%', height: '100%' }}
                        />
                    </Noti.ViewFlipper>
                    <Noti.View
                        style={{
                            position: 'absolute',
                            left: '1rem',
                            bottom: '.8rem',
                            width: '10rem',
                            height: '3rem',
                            paddingHorizontal: '.75rem',
                            borderRadius: '.75rem',
                            backgroundColor: '#160f25dd',
                            justifyContent: 'center',
                        }}
                    >
                        <Noti.Text
                            style={{ color: '#ffffff', fontSize: '1rem', fontWeight: 'bold' }}
                        >
                            BG CROSSFADE
                        </Noti.Text>
                        <Noti.Text style={{ color: '#fbcfe8', fontSize: '.62rem' }}>
                            OPACITY · OLD + NEW
                        </Noti.Text>
                    </Noti.View>
                </Noti.View>
            );
    }
}

function compactPreview(index: number, phase: number, palette: Palette): ReactNode {
    const frame = { width: '4.25rem', height: '3.25rem' } as const;
    switch (index) {
        case 0:
            return (
                <Noti.View direction="overlay" style={frame}>
                    <Noti.View
                        style={{
                            anchor: 'center',
                            width: '2.25rem',
                            height: '2.25rem',
                            borderRadius: '.7rem',
                            backgroundColor: palette.accent,
                        }}
                    />
                </Noti.View>
            );
        case 1:
        case 3:
            return (
                <Noti.Loop
                    from={{ scale: 0.5, opacity: 0.35, rotate: -20 }}
                    animate={{ scale: 1.15, opacity: 1, rotate: 20 }}
                    transition={{ duration: 850, frames: 8, repeatReverse: true }}
                    style={frame}
                >
                    <Noti.View
                        style={{
                            anchor: 'center',
                            width: '2.4rem',
                            height: '2.4rem',
                            borderRadius: '.8rem',
                            backgroundColor: palette.accent,
                        }}
                    />
                </Noti.Loop>
            );
        case 2:
            return (
                <Noti.View
                    style={{ ...frame, borderRadius: '.75rem', backgroundColor: palette.panel }}
                >
                    <Noti.Gif
                        source={orbitGif}
                        fallbackSource={artA}
                        maxFrames={6}
                        resizeMode="stretch"
                        style={{ width: '100%', height: '100%' }}
                    />
                </Noti.View>
            );
        case 4:
            return (
                <Noti.View
                    direction="row"
                    style={{
                        ...frame,
                        gap: '.3rem',
                        alignItems: 'center',
                        justifyContent: 'center',
                    }}
                >
                    {[0, 1, 2, 3].map((bar) => (
                        <Noti.Loop
                            key={`compact-bar-${bar}`}
                            from={{ scaleY: 0.25, opacity: 0.4 }}
                            animate={{ scaleY: 1.25, opacity: 1 }}
                            transition={{
                                duration: 450 + bar * 100,
                                frames: 5,
                                repeatReverse: true,
                            }}
                            style={{ width: '.6rem', height: '2.5rem' }}
                        >
                            <Noti.View
                                style={{
                                    anchor: 'center',
                                    width: '.35rem',
                                    height: '1.8rem',
                                    borderRadius: '.2rem',
                                    backgroundColor: palette.accent,
                                }}
                            />
                        </Noti.Loop>
                    ))}
                </Noti.View>
            );
        case 5:
        case 6:
            return (
                <Noti.ViewFlipper
                    animation={index === 5 ? 'fade' : 'drift'}
                    interval={850}
                    style={frame}
                >
                    {['A', 'B', 'C'].map((letter, slide) => (
                        <Noti.View
                            key={letter}
                            style={{
                                ...frame,
                                borderRadius: '.75rem',
                                backgroundColor: slide === 1 ? palette.accent : palette.panel,
                                alignItems: 'center',
                                justifyContent: 'center',
                            }}
                        >
                            <Noti.Text
                                style={{
                                    color: slide === 1 ? '#09090b' : palette.text,
                                    fontSize: '1rem',
                                    fontWeight: 'bold',
                                }}
                            >
                                {letter}
                            </Noti.Text>
                        </Noti.View>
                    ))}
                </Noti.ViewFlipper>
            );
        case 7:
            return (
                <Noti.AnimatePresence style={frame} enter="zoom" exit="fade">
                    <Noti.View
                        key={`compact-presence-${phase % 3}`}
                        style={{
                            ...frame,
                            borderRadius: '.75rem',
                            backgroundColor: palette.panel,
                            alignItems: 'center',
                            justifyContent: 'center',
                        }}
                    >
                        <Noti.Text
                            style={{ color: palette.accent, fontSize: '1rem', fontWeight: 'bold' }}
                        >
                            {phase % 3 === 2 ? 'DONE' : 'LIVE'}
                        </Noti.Text>
                    </Noti.View>
                </Noti.AnimatePresence>
            );
        case 8: {
            const index = phase % 7;
            return (
                <Noti.ScrollView
                    id="compact-scroll"
                    index={index}
                    windowSize={3}
                    itemHeight=".8rem"
                    style={{
                        ...frame,
                        paddingVertical: '.4rem',
                        borderRadius: '.75rem',
                        backgroundColor: palette.panel,
                    }}
                >
                    {scrollRows.map((row, rowIndex) => (
                        <Noti.Text
                            key={`compact-${row}`}
                            style={{
                                height: '.8rem',
                                color: rowIndex === index + 1 ? palette.accent : palette.muted,
                                fontSize: '.45rem',
                                fontWeight: rowIndex === index + 1 ? 'bold' : 'normal',
                                textAlign: 'center',
                                verticalAlign: 'center',
                            }}
                        >
                            {row}
                        </Noti.Text>
                    ))}
                </Noti.ScrollView>
            );
        }
        case 9:
            return (
                <Noti.Grid columns={2} rows={2} style={{ ...frame, gap: '.25rem' }}>
                    {[0, 1, 2, 3].map((tile) => (
                        <Noti.View
                            key={`compact-tile-${tile}`}
                            style={{
                                width: '2rem',
                                height: '1.5rem',
                                borderRadius: '.35rem',
                                backgroundColor:
                                    tile === phase % 4 ? palette.accent : palette.panel,
                            }}
                        />
                    ))}
                </Noti.Grid>
            );
        case 10:
            return (
                <Noti.View
                    style={{
                        ...frame,
                        borderRadius: '.75rem',
                        backgroundColor: palette.panel,
                        alignItems: 'center',
                        justifyContent: 'center',
                    }}
                >
                    <Noti.Chronometer
                        base={Date.now() + 300_000}
                        countDown
                        format="%s"
                        style={{
                            width: '3.5rem',
                            height: '2rem',
                            color: palette.accent,
                            fontSize: '1rem',
                            fontWeight: 'bold',
                            textAlign: 'center',
                            verticalAlign: 'center',
                        }}
                    />
                </Noti.View>
            );
        default:
            return (
                <Noti.View direction="overlay" style={{ ...frame, borderRadius: '.75rem' }}>
                    <Noti.ViewFlipper
                        animation="crossfade"
                        interval={1800}
                        style={{ width: '100%', height: '100%' }}
                    >
                        <Noti.Image
                            key="compact-art-a"
                            source={artA}
                            resizeMode="stretch"
                            style={{ width: '100%', height: '100%' }}
                        />
                        <Noti.Image
                            key="compact-art-b"
                            source={artB}
                            resizeMode="stretch"
                            style={{ width: '100%', height: '100%' }}
                        />
                    </Noti.ViewFlipper>
                </Noti.View>
            );
    }
}

export function notificationScene(index: number, phase = 0): NotificationScene {
    const safeIndex = ((index % examples.length) + examples.length) % examples.length;
    const palette = palettes[safeIndex];
    const number = String(safeIndex + 1).padStart(2, '0');
    return {
        collapsed: (
            <Noti.View
                direction="overlay"
                style={{
                    width: '100%',
                    height: '4.25rem',
                    backgroundColor: palette.background,
                }}
            >
                <Noti.View
                    style={{
                        position: 'absolute',
                        anchor: 'centerLeft',
                        left: '.75rem',
                        width: '4.25rem',
                        height: '3.25rem',
                    }}
                >
                    {compactPreview(safeIndex, phase, palette)}
                </Noti.View>
                <Noti.Text
                    numberOfLines={2}
                    style={{
                        width: '100%',
                        height: '100%',
                        paddingLeft: '5.75rem',
                        paddingRight: '.75rem',
                        color: palette.text,
                        fontSize: '.9rem',
                        fontWeight: 'bold',
                        verticalAlign: 'center',
                    }}
                >
                    {number} {examples[safeIndex]}
                </Noti.Text>
            </Noti.View>
        ),
        headsUp: (
            <Noti.View
                direction="overlay"
                style={{
                    width: '100%',
                    height: '5.5rem',
                    backgroundColor: palette.background,
                }}
            >
                <Noti.View
                    style={{
                        position: 'absolute',
                        anchor: 'centerLeft',
                        left: '.75rem',
                        width: '4.25rem',
                        height: '3.25rem',
                    }}
                >
                    {compactPreview(safeIndex, phase, palette)}
                </Noti.View>
                <Noti.Text
                    numberOfLines={2}
                    style={{
                        width: '100%',
                        height: '100%',
                        paddingLeft: '5.75rem',
                        paddingRight: '.75rem',
                        color: palette.text,
                        fontSize: '1rem',
                        fontWeight: 'bold',
                        verticalAlign: 'center',
                    }}
                >
                    {number} {examples[safeIndex]}
                </Noti.Text>
            </Noti.View>
        ),
        expanded: (
            <Noti.AnimatePresence
                key="demo-stage"
                enter="slideUp"
                exit="slideDown"
                style={{
                    width: '100%',
                    height: '12rem',
                    backgroundColor: palette.background,
                }}
            >
                {exampleContent(safeIndex, phase, palette)}
            </Noti.AnimatePresence>
        ),
    };
}

export const notificationRows = scrollRows;
