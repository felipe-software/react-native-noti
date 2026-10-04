// SPDX-License-Identifier: GPL-2.0-or-later
#include "doom_runtime.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>

extern "C" void noti_doom_probe(int64_t result[6]);
static void require(bool value, const char* message) {
    if (!value) { fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}
static void sleep_ms(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
static std::array<int64_t, 6> snapshot() {
    noti_doom::stop();
    std::array<int64_t, 6> state{};
    noti_doom_probe(state.data());
    return state;
}

int main(int argc, char** argv) {
    require(argc >= 2, "pass an absolute Freedoom WAD path");
    require(!noti_doom::start("/does-not-exist.wad"), "missing WAD is rejected safely");
    std::array<int32_t, noti_doom::kPixelCount> pixels{};
    require(!noti_doom::render(pixels.data()), "no frame before start");
    if (argc == 3 && std::string(argv[2]) == "--invalid") {
        require(!noti_doom::start(argv[1]), "invalid engine data returns false without exiting host");
        puts("PASS: engine fatal error contained");
        return 0;
    }
    require(noti_doom::start(argv[1]), "engine starts real Freedoom");
    require(noti_doom::start(argv[1]), "repeated start is idempotent");
    require(!noti_doom::start("/another.wad"), "changing WAD is rejected");
    sleep_ms(300);
    require(noti_doom::render(pixels.data()), "frame available");
    int differing = 0;
    for (const auto pixel : pixels) {
        require((uint32_t(pixel) >> 24) == 255, "ARGB frame is opaque");
        differing += pixel != pixels[0];
    }
    require(differing > 10000, "frame contains rendered world, HUD, sprites");
    FILE* image = fopen("/tmp/noti-doom-smoke.ppm", "wb");
    if (image) {
        fprintf(image, "P6\n320 200\n255\n");
        for (uint32_t pixel : pixels) {
            const unsigned char rgb[] = {static_cast<unsigned char>(pixel >> 16),
                static_cast<unsigned char>(pixel >> 8), static_cast<unsigned char>(pixel)};
            fwrite(rgb, 1, 3, image);
        }
        fclose(image);
    }
    const auto initial = snapshot();
    require(!noti_doom::render(pixels.data()), "no frame exposed while stopped");
    sleep_ms(1100);
    const auto paused = snapshot();
    require(initial == paused, "stopped simulation stays frozen");
    require(noti_doom::start(argv[1]), "session resumes");
    noti_doom::key(0xAD, true);
    sleep_ms(500);
    const auto moved = snapshot();
    require(initial[0] != moved[0] || initial[1] != moved[1], "forward changes actual player position");
    require(moved[5] - initial[5] >= 14 && moved[5] - initial[5] <= 23,
        "35 Hz simulation excludes stopped time");
    require(noti_doom::start(argv[1]), "resume for turn/fire");
    noti_doom::key(0xAE, true);
    noti_doom::key(0xA3, true);
    sleep_ms(800);
    const auto fired = snapshot();
    require(fired[2] != moved[2], "turn changes real player angle");
    require(fired[3] < moved[3], "fire consumes real weapon ammunition");
    require(noti_doom::start(argv[1]), "resume releases held input");
    // Doom can have a ticcmd already built before the release event. Let that
    // command and the weapon's in-progress animation finish before comparison.
    sleep_ms(300);
    const auto settled = snapshot();
    require(noti_doom::start(argv[1]), "resume after input settles");
    sleep_ms(500);
    const auto released = snapshot();
    require(released[2] == settled[2], "stop released held turn control");
    require(released[3] == settled[3], "stop released held fire control");
    printf("PASS: real Doom frames, move, turn, fire, pause/resume; tics=%lld, ammo=%lld -> %lld\n",
        static_cast<long long>(moved[5] - initial[5]), static_cast<long long>(moved[3]),
        static_cast<long long>(fired[3]));
}
