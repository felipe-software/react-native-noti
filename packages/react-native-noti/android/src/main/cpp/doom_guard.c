// SPDX-License-Identifier: GPL-2.0-or-later
// Keep longjmp inside C frames: never unwind JNI or a C++ lock/destructor.
#include "doom_guard.h"
#include "doomgeneric.h"
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

static jmp_buf engine_boundary;
static char error_message[1024];
static char* private_config_dir;

void noti_upstream_config_dir(char* dir);
void M_SetConfigDir(char* ignored)
{
    (void) ignored;
    noti_upstream_config_dir(private_config_dir);
}

void I_Error(char* format, ...)
{
    va_list args;
    va_start(args, format);
    vsnprintf(error_message, sizeof(error_message), format, args);
    va_end(args);
    fprintf(stderr, "Noti Doom: %s\n", error_message);
    longjmp(engine_boundary, 1);
}

void I_Quit(void)
{
    I_Error("Engine quit; create a new Android process to start a new session");
}

_Noreturn void noti_doom_exit(int status)
{
    snprintf(error_message, sizeof(error_message), "Engine exited (%d)", status);
    longjmp(engine_boundary, 1);
}

const char* noti_doom_error(void) { return error_message; }

int noti_doom_create_safe(int argc, char** argv, char* config_dir)
{
    private_config_dir = config_dir;
    if (setjmp(engine_boundary)) return 0;
    doomgeneric_Create(argc, argv);
    return 1;
}

int noti_doom_tick_safe(void)
{
    if (setjmp(engine_boundary)) return 0;
    doomgeneric_Tick();
    return 1;
}
