// SPDX-License-Identifier: GPL-2.0-or-later
// Force-included into the vendored Chocolate Doom 2.2.1 OPL player. That release
// declared this in its i_sound.h; doomgeneric's older header predates it.
#ifndef NOTI_DOOM_OPL_COMPAT_H
#define NOTI_DOOM_OPL_COMPAT_H

typedef enum
{
    opl_v_old,  // Doom 1.666
    opl_v_new   // Doom 1.9
} opl_driver_ver_t;

#endif
