// SPDX-License-Identifier: GPL-2.0-or-later
#include "doom_runtime.h"
#include "doom_audio.h"
#include "doom_guard.h"
#include "doomgeneric.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#endif

extern "C" {
extern int gametic;
extern int ticdup;
}

namespace {
using Clock = std::chrono::steady_clock;
struct KeyEvent { unsigned char code; bool pressed; uint64_t sequence; };
struct CommandInput { int64_t after_tic; uint64_t sequence; };
struct Runtime {
    std::mutex lifecycle;
    std::mutex mutex;
    std::condition_variable changed;
    bool initialized = false;
    bool starting = false;
    bool running = false;
    bool ticking = false;
    bool failed = false;
    bool has_frame = false;
    std::string wad_path;
    std::string config_dir;
    std::array<int32_t, noti_doom::kPixelCount> frame{};
    std::array<bool, 256> held{};
    std::deque<KeyEvent> keys;
    uint64_t next_input = 1;
    uint64_t consumed_input = 0;
    uint64_t command_input = 0;
    uint64_t discarded_input = 0;
    std::deque<CommandInput> commands;
    noti_doom::FrameInfo frame_info;
    // These clock fields are exclusively owned by the engine thread.
    Clock::time_point epoch = Clock::now();
    Clock::duration suspended{};
};

Runtime& runtime() {
    // Doom's C globals and this sleeping worker have process lifetime. Destroying
    // synchronization objects during Android library teardown would race it.
    static Runtime* instance = new Runtime();
    return *instance;
}

bool valid_wad(const std::string& path) {
    if (path.empty() || path.front() != '/' || path.find('\0') != std::string::npos) return false;
    FILE* file = fopen(path.c_str(), "rb");
    if (!file) return false;
    unsigned char header[12];
    bool valid = fread(header, 1, sizeof(header), file) == sizeof(header);
    valid = valid && memcmp(header, "IWAD", 4) == 0;
    auto le32 = [](const unsigned char* p) {
        return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
    };
    if (valid && fseek(file, 0, SEEK_END) == 0) {
        const long length = ftell(file);
        const uint64_t count = le32(header + 4);
        const uint64_t directory = le32(header + 8);
        valid = count > 0 && count < 100000 && directory >= 12 && length >= 12 &&
                directory + count * 16 <= static_cast<uint64_t>(length);
    } else {
        valid = false;
    }
    fclose(file);
    return valid;
}

void release_keys(Runtime& state) {
    // A later watermark must never falsely acknowledge a key discarded by pause/overflow.
    state.discarded_input = state.next_input - 1;
    state.keys.clear();
    for (int i = 0; i < 256; ++i) {
        if (state.held[i]) state.keys.push_back({static_cast<unsigned char>(i), false, 0});
    }
}

void engine_thread() {
    Runtime& state = runtime();
    // The engine retains argv pointers, so keep their storage for its lifetime.
    std::vector<std::string> arguments = {
        "noti-doom", "-iwad", state.wad_path, "-warp", "1", "1",
        "-skill", "3", "-nogui",
        "-config", state.config_dir + "noti-doom.cfg",
        "-extraconfig", state.config_dir + "noti-doom-extra.cfg"
    };
    std::vector<char*> argv;
    for (auto& arg : arguments) argv.push_back(arg.data());
    const int argc = static_cast<int>(argv.size());
    argv.push_back(nullptr);
    state.epoch = Clock::now();
    bool ok = noti_doom_create_safe(argc, argv.data(), state.config_dir.data());
    std::unique_lock<std::mutex> lock(state.mutex);
    state.starting = false;
    state.initialized = ok;
    state.ticking = false;
    state.changed.notify_all();
    while (ok) {
        if (!state.running) {
            const auto pause_start = Clock::now();
            state.changed.wait(lock, [&] { return state.running; });
            state.suspended += Clock::now() - pause_start;
        }
        state.ticking = true;
        lock.unlock();
        // TryRunTics inside Doom advances the original 35 Hz simulation and
        // sleeps until its next tic. It is independent of notification refresh.
        ok = noti_doom_tick_safe();
        lock.lock();
        state.ticking = false;
        state.changed.notify_all();
    }
    state.failed = true;
    state.running = false;
    state.has_frame = false;
    state.changed.notify_all();
    noti_audio_set_running(0);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_ERROR, "NotiDoom", "%s", noti_doom_error());
#else
    fprintf(stderr, "NotiDoom: %s\n", noti_doom_error());
#endif
}
} // namespace

namespace noti_doom {
bool start(const std::string& wad_path) {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lifecycle(state.lifecycle);
    std::unique_lock<std::mutex> lock(state.mutex);
    if (state.failed) return false;
    if (state.initialized) {
        if (state.wad_path != wad_path) return false;
        state.running = true;
        state.changed.notify_all();
        noti_audio_set_running(1);
        return true;
    }
    if (!valid_wad(wad_path)) return false;
    state.wad_path = wad_path;
    state.config_dir = wad_path.substr(0, wad_path.find_last_of('/') + 1);
    state.starting = true;
    state.running = true;
    noti_audio_set_running(1);
    try {
        std::thread(engine_thread).detach();
    } catch (...) {
        state.starting = false;
        state.running = false;
        noti_audio_set_running(0);
        return false;
    }
    state.changed.wait(lock, [&] { return !state.starting; });
    return state.initialized && !state.failed;
}

bool render(int32_t* pixels, FrameInfo* info) {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.running || !state.has_frame || state.failed) return false;
    std::copy(state.frame.begin(), state.frame.end(), pixels);
    if (info) {
        *info = state.frame_info;
        // Queue invalidation can happen after the last draw, without producing a new image.
        info->discarded_input = state.discarded_input;
    }
    return true;
}

uint64_t key(int code, bool pressed) {
    if (code < 0 || code > 255) return 0;
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.running || state.failed) return 0;
    // Bound the queue if a caller floods input; releases cannot be lost.
    if (state.keys.size() >= 512) release_keys(state);
    const auto sequence = state.next_input++;
    state.keys.push_back({static_cast<unsigned char>(code), pressed, sequence});
    return sequence;
}

void stop() {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lifecycle(state.lifecycle);
    std::unique_lock<std::mutex> lock(state.mutex);
    state.running = false;
    state.changed.wait(lock, [&] { return !state.ticking && !state.starting; });
    release_keys(state);
    noti_audio_set_running(0);
}

void set_volume(float volume) { noti_audio_set_volume(volume); }
} // namespace noti_doom

extern "C" {
// Called after G_BuildTiccmd has processed events. The command is for `tic`, which may
// be ahead of gametic; consuming DG_GetKey alone is not proof that it has been simulated.
void noti_doom_command_built(int tic) {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (state.consumed_input > state.command_input) {
        state.commands.push_back({int64_t(tic + 1) * std::max(ticdup, 1), state.consumed_input});
        state.command_input = state.consumed_input;
    }
}
void DG_Init() {}
void DG_SetWindowTitle(const char*) {}
void DG_DrawFrame() {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    for (int i = 0; i < noti_doom::kPixelCount; ++i) {
        state.frame[i] = static_cast<int32_t>(DG_ScreenBuffer[i] | 0xff000000u);
    }
    while (!state.commands.empty() && state.commands.front().after_tic <= gametic) {
        state.frame_info.applied_input = state.commands.front().sequence;
        state.commands.pop_front();
    }
    ++state.frame_info.sequence;
    state.frame_info.game_tic = gametic;
    state.frame_info.rendered_at_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
        Clock::now().time_since_epoch()).count();
    state.has_frame = true;
}
void DG_SleepMs(uint32_t ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
uint32_t DG_GetTicksMs() {
    Runtime& state = runtime();
    return 1 + static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now() - state.epoch - state.suspended).count());
}
int DG_GetKey(int* pressed, unsigned char* key) {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (state.keys.empty()) return 0;
    const KeyEvent event = state.keys.front();
    state.keys.pop_front();
    state.held[event.code] = event.pressed;
    state.consumed_input = std::max(state.consumed_input, event.sequence);
    *pressed = event.pressed;
    *key = event.code;
    return 1;
}
}
