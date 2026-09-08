const { withAndroidManifest, withDangerousMod } = require('expo/config-plugins');
const fs = require('node:fs');
const path = require('node:path');
const { resources, writeResources } = require('./compiler');

const WIDGET_PROVIDER = 'expo.modules.notificationmotion.MotionWidgetProvider';

function configureWidgetManifest(androidManifest, enabled) {
    const application = androidManifest.manifest.application?.[0];
    if (!application) throw new Error('AndroidManifest.xml does not contain an application node.');
    const receivers = application.receiver ?? [];
    application.receiver = receivers.filter(
        (receiver) => receiver.$?.['android:name'] !== WIDGET_PROVIDER
    );
    if (enabled) {
        application.receiver.push({
            $: {
                'android:name': WIDGET_PROVIDER,
                'android:exported': 'false',
            },
            'intent-filter': [
                {
                    action: [
                        { $: { 'android:name': 'android.appwidget.action.APPWIDGET_UPDATE' } },
                    ],
                },
            ],
            'meta-data': [
                {
                    $: {
                        'android:name': 'android.appwidget.provider',
                        'android:resource': '@xml/nm_widget_info',
                    },
                },
            ],
        });
    }
    return androidManifest;
}

function withNotificationMotion(config, options = {}) {
    const files = resources(options.animations ?? {});
    const configured = withAndroidManifest(config, (mod) => {
        mod.modResults = configureWidgetManifest(mod.modResults, options.widgets === true);
        return mod;
    });
    return withDangerousMod(configured, [
        'android',
        async (mod) => {
            const directory = path.join(mod.modRequest.platformProjectRoot, 'app/src/main/res');
            const manifest = path.join(
                mod.modRequest.platformProjectRoot,
                '.notification-motion-resources.json'
            );
            if (fs.existsSync(manifest)) {
                for (const file of JSON.parse(fs.readFileSync(manifest, 'utf8'))) {
                    if (/^(anim|layout|interpolator)\/nm_[a-z0-9_]+\.xml$/.test(file))
                        fs.rmSync(path.join(directory, file), { force: true });
                }
            }
            writeResources(directory, files);
            fs.writeFileSync(manifest, JSON.stringify(Object.keys(files)));
            return mod;
        },
    ]);
}

withNotificationMotion.configureWidgetManifest = configureWidgetManifest;
module.exports = withNotificationMotion;
