const { withAppBuildGradle } = require('expo/config-plugins');

// Keep extracted PNGs outside generated android/, so clean prebuilds preserve them.
module.exports = function withBadApple(config) {
    return withAppBuildGradle(config, (config) => {
        const marker = '// Bad Apple demo assets';
        if (!config.modResults.contents.includes(marker)) {
            config.modResults.contents += `\n${marker}\nandroid.sourceSets.main.assets.srcDir(new File(rootDir, '../assets'))\n`;
        }
        return config;
    });
};
