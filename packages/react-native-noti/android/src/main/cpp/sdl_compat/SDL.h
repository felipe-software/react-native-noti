// SPDX-License-Identifier: GPL-2.0-or-later
// The few SDL facilities the vendored Chocolate Doom OPL code uses (OPL_Delay's
// mutex/condition and MIDI byte swaps), implemented on pthreads. SDL itself is
// not part of the Android build.
#ifndef NOTI_SDL_COMPAT_H
#define NOTI_SDL_COMPAT_H

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>  // real SDL.h includes it; opl.c relies on that

#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "The SDL compatibility byte swaps assume a little-endian target"
#endif

typedef pthread_mutex_t SDL_mutex;
typedef pthread_cond_t SDL_cond;

static inline SDL_mutex *SDL_CreateMutex(void)
{
    SDL_mutex *mutex = malloc(sizeof(SDL_mutex));
    if (mutex != NULL) pthread_mutex_init(mutex, NULL);
    return mutex;
}

static inline void SDL_DestroyMutex(SDL_mutex *mutex)
{
    if (mutex == NULL) return;
    pthread_mutex_destroy(mutex);
    free(mutex);
}

static inline int SDL_LockMutex(SDL_mutex *mutex) { return pthread_mutex_lock(mutex); }
static inline int SDL_UnlockMutex(SDL_mutex *mutex) { return pthread_mutex_unlock(mutex); }

static inline SDL_cond *SDL_CreateCond(void)
{
    SDL_cond *cond = malloc(sizeof(SDL_cond));
    if (cond != NULL) pthread_cond_init(cond, NULL);
    return cond;
}

static inline void SDL_DestroyCond(SDL_cond *cond)
{
    if (cond == NULL) return;
    pthread_cond_destroy(cond);
    free(cond);
}

static inline int SDL_CondSignal(SDL_cond *cond) { return pthread_cond_signal(cond); }
static inline int SDL_CondWait(SDL_cond *cond, SDL_mutex *mutex) { return pthread_cond_wait(cond, mutex); }

#define SDL_SwapBE16(x) __builtin_bswap16((uint16_t) (x))
#define SDL_SwapBE32(x) __builtin_bswap32((uint32_t) (x))

#endif
