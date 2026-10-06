// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstdint>
#include <string>

namespace noti_doom {
constexpr int kWidth = 320;
constexpr int kHeight = 200;
constexpr int kPixelCount = kWidth * kHeight;
bool start(const std::string& wad_path);
bool render(int32_t* pixels);
void key(int code, bool pressed);
void stop();
// 0 mutes (and pauses output), 1 is full volume.
void set_volume(float volume);
}
