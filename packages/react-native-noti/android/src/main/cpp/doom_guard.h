// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
int noti_doom_create_safe(int argc, char** argv, char* config_dir);
int noti_doom_tick_safe(void);
const char* noti_doom_error(void);
#ifdef __cplusplus
}
#endif
