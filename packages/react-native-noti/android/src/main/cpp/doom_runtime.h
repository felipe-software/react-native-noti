// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstdint>
#include <string>

namespace noti_doom {
constexpr int kWidth = 320;
constexpr int kHeight = 200;
constexpr int kPixelCount = kWidth * kHeight;
struct FrameInfo {
    uint64_t sequence = 0;
    uint64_t applied_input = 0;
    uint64_t discarded_input = 0;
    int64_t game_tic = 0;
    int64_t rendered_at_ns = 0;
};
bool start(const std::string& wad_path);
bool render(int32_t* pixels, FrameInfo* info = nullptr);
// Zero means rejected; otherwise a monotonic ID for this queued key event.
uint64_t key(int code, bool pressed);
void stop();
// 0 mutes (and pauses output), 1 is full volume.
void set_volume(float volume);
}
