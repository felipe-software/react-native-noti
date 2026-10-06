// SPDX-License-Identifier: GPL-2.0-or-later
// Test-only access to real Doom state; call exclusively while runtime is stopped.
#include "doomstat.h"
#include "d_loop.h"
#include "s_sound.h"
#include "sounds.h"
#include <stdint.h>

void noti_doom_probe(int64_t result[6])
{
    player_t* player = &players[consoleplayer];
    result[0] = player->mo->x;
    result[1] = player->mo->y;
    result[2] = player->mo->angle;
    result[3] = player->ammo[am_clip];
    result[4] = player->health;
    result[5] = gametic;
}

// Stops the music and plays the pistol at full volume, so its samples stand alone.
void noti_doom_probe_sound(void)
{
    S_StopMusic();
    S_StartSound(NULL, sfx_pistol);
}
