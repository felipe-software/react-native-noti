// SPDX-License-Identifier: GPL-2.0-or-later
#include "g_game.h"

void noti_upstream_build_ticcmd(ticcmd_t* cmd, int maketic);
void noti_doom_command_built(int tic);

// g_game.c is compiled with only its implementation name substituted. All upstream
// source stays unchanged, and every caller still reaches the original command builder.
void G_BuildTiccmd(ticcmd_t* cmd, int maketic) {
    noti_upstream_build_ticcmd(cmd, maketic);
    noti_doom_command_built(maketic);
}
