// SPDX-License-Identifier: GPL-2.0-or-later
#ifndef NOTI_DOOM_AUDIO_H
#define NOTI_DOOM_AUDIO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Output plays only while the engine runs and the volume is above zero.
void noti_audio_set_running(int running);
// Master gain from 0 (muted; output paused) to 1, e.g. for audio focus ducking.
void noti_audio_set_volume(float volume);
// Mixes interleaved stereo 16-bit frames at NOTI_AUDIO_RATE. The output stream
// calls this; host tests call it directly because they have no audio device.
void noti_audio_render(int16_t *output, int frames);

#define NOTI_AUDIO_RATE 48000

#ifdef __cplusplus
}
#endif

#endif
