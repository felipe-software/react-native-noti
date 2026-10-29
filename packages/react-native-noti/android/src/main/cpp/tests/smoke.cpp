// SPDX-License-Identifier: GPL-2.0-or-later
#include "doom_runtime.h"
#include "doom_audio.h"
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

extern "C" void noti_doom_probe(int64_t result[6]);
extern "C" void noti_doom_probe_sound(void);
static void require(bool value, const char* message) {
    if (!value) { fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}
static void sleep_ms(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
static noti_doom::FrameInfo wait_for_input(uint64_t input, uint64_t prior_frame) {
    require(input != 0, "tracked input was accepted");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
    std::array<int32_t, noti_doom::kPixelCount> pixels{};
    noti_doom::FrameInfo frame;
    while (std::chrono::steady_clock::now() < deadline) {
        if (noti_doom::render(pixels.data(), &frame) && frame.applied_input >= input &&
                frame.discarded_input < input) {
            require(frame.sequence > prior_frame && frame.game_tic > 0 && frame.rendered_at_ns > 0,
                "input acknowledgement belongs to a newly rendered game frame");
            return frame;
        }
        sleep_ms(1);
    }
    require(false, "input reached a simulated, rendered frame");
    return frame;
}
// The host has no audio device, so tests pull the mixer directly.
static double render_rms(int frames, const char* wav_path) {
    std::vector<int16_t> audio(frames * 2);
    noti_audio_render(audio.data(), frames);
    double sum = 0;
    for (const auto sample : audio) sum += double(sample) * sample;
    FILE* wav = fopen(wav_path, "wb");
    if (wav) {
        const uint32_t bytes = audio.size() * 2, rate = NOTI_AUDIO_RATE, byte_rate = rate * 4;
        const uint32_t riff = 36 + bytes, fmt_size = 16;
        const uint16_t pcm = 1, channels = 2, align = 4, bits = 16;
        fwrite("RIFF", 1, 4, wav); fwrite(&riff, 4, 1, wav); fwrite("WAVEfmt ", 1, 8, wav);
        fwrite(&fmt_size, 4, 1, wav); fwrite(&pcm, 2, 1, wav); fwrite(&channels, 2, 1, wav);
        fwrite(&rate, 4, 1, wav); fwrite(&byte_rate, 4, 1, wav); fwrite(&align, 2, 1, wav);
        fwrite(&bits, 2, 1, wav); fwrite("data", 1, 4, wav); fwrite(&bytes, 4, 1, wav);
        fwrite(audio.data(), 2, audio.size(), wav);
        fclose(wav);
    }
    return std::sqrt(sum / audio.size());
}
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
    require(noti_doom::key(0xAD, true) == 0, "input rejected before engine start");
    require(noti_doom::key(-1, true) == 0 && noti_doom::key(256, true) == 0,
        "invalid tracked keys are rejected");
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
    require(noti_doom::key(0xAD, true) == 0, "stopped engine rejects tracked input");
    sleep_ms(1100);
    const auto paused = snapshot();
    require(initial == paused, "stopped simulation stays frozen");
    require(noti_doom::start(argv[1]), "session resumes");
    noti_doom::FrameInfo prior;
    require(noti_doom::render(pixels.data(), &prior), "resume has a snapshot");
    const auto move_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
    const auto movement_input = noti_doom::key(0xAD, true);
    const auto movement_frame = wait_for_input(movement_input, prior.sequence);
    require(movement_frame.game_tic > initial[5], "acknowledged command tick was simulated");
    std::this_thread::sleep_until(move_deadline);
    const auto moved = snapshot();
    require(initial[0] != moved[0] || initial[1] != moved[1], "forward changes actual player position");
    require(moved[5] - initial[5] >= 14 && moved[5] - initial[5] <= 23,
        "35 Hz simulation excludes stopped time");
    require(noti_doom::start(argv[1]), "resume for turn/fire");
    noti_doom::FrameInfo invalidated;
    require(noti_doom::render(pixels.data(), &invalidated), "resumed frame metadata available");
    require(invalidated.discarded_input >= movement_input, "pause invalidates earlier pending input acknowledgements");
    const auto fire_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(800);
    const auto turn_input = noti_doom::key(0xAE, true);
    const auto fire_input = noti_doom::key(0xA3, true);
    require(fire_input > turn_input && turn_input > movement_input, "input IDs survive pause/resume monotonically");
    const auto fire_frame = wait_for_input(fire_input, movement_frame.sequence);
    require(fire_frame.game_tic > movement_frame.game_tic, "later input acknowledged by a later simulation tick");
    std::this_thread::sleep_until(fire_deadline);
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
    // E1M1's music has been queued since the level started; the OPL clock only
    // advances as audio is rendered.
    const double music = render_rms(NOTI_AUDIO_RATE, "/tmp/noti-doom-music.wav");
    require(music > 200, "OPL music plays E1M1 from the WAD's GENMIDI instruments");
    noti_doom_probe_sound();
    const double pistol = render_rms(NOTI_AUDIO_RATE / 4, "/tmp/noti-doom-pistol.wav");
    require(pistol > 500, "pistol sound effect mixes");
    // The 0.5 s pistol sample and OPL note releases fade out first.
    render_rms(NOTI_AUDIO_RATE / 2, "/tmp/noti-doom-fade.wav");
    const double after = render_rms(NOTI_AUDIO_RATE / 2, "/tmp/noti-doom-after.wav");
    require(after < 20, "effect ends and stopped music is silent");
    printf("PASS: real Doom frames, move, turn, fire, pause/resume; tics=%lld, ammo=%lld -> %lld; "
        "audio RMS music=%.0f pistol=%.0f after=%.1f\n",
        static_cast<long long>(moved[5] - initial[5]), static_cast<long long>(moved[3]),
        static_cast<long long>(fired[3]), music, pistol, after);
}
