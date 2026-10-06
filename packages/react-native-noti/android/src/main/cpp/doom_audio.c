// SPDX-License-Identifier: GPL-2.0-or-later
// Android audio for the embedded engine, in place of doomgeneric's SDL backends:
// - DG_sound_module mixes Doom's DMX sound effects on 16 linearly resampled channels.
// - DG_music_module runs Chocolate Doom's OPL player (GENMIDI instruments from the
//   WAD) on the DOSBox OPL emulator, through noti_opl_driver below, a software
//   driver standing in for Chocolate Doom's SDL one.
// - An AAudio stream pulls both through noti_audio_render().
// The engine thread drives the modules; only the output callback mixes.

#include "doom_audio.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "deh_str.h"
#include "doomtype.h"
#include "i_sound.h"
#include "m_config.h"
#include "m_misc.h"
#include "w_wad.h"
#include "z_zone.h"

#include "dbopl.h"
#include "opl.h"
#include "opl_internal.h"
#include "opl_queue.h"

#ifdef __ANDROID__
#include <aaudio/AAudio.h>
#include <android/log.h>
#endif

#define COUNT(array) (sizeof(array) / sizeof(*(array)))
#define CHANNELS 16
#define CHUNK 256

static void AudioLog(const char *message)
{
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_WARN, "NotiDoom", "%s", message);
#else
    fprintf(stderr, "NotiDoom: %s\n", message);
#endif
}

// Config variables that m_config.c binds when sound is enabled. The SDL backends
// defined them; none apply here.
int use_libsamplerate = 0;
float libsamplerate_scale = 0.65f;
char *timidity_cfg_path = "";
char *gus_patch_path = "";
int gus_ram_kb = 1024;
void I_InitTimidityConfig(void) {}

// The OPL player hands each song to its MIDI parser through a temporary file.
// Upstream M_TempFile uses /tmp, which Android lacks; use the engine's private
// directory instead (i_oplmusic.c is compiled with M_TempFile=noti_temp_file).
char *noti_temp_file(char *name)
{
    const size_t length = strlen(configdir);
    const char *separator = length > 0 && configdir[length - 1] == '/' ? "" : "/";
    return M_StringJoin(configdir, separator, name, NULL);
}

// ---- Sound effects ---------------------------------------------------------

typedef struct
{
    uint32_t length;
    uint32_t rate;
    int16_t samples[];
} sound_t;

typedef struct
{
    const sound_t *sound;
    uint64_t position;  // 32.32 fixed-point sample index
    uint64_t step;
    int left;           // 0..255
    int right;
} channel_t;

static pthread_mutex_t mix_lock = PTHREAD_MUTEX_INITIALIZER;
static channel_t channels[CHANNELS];
static boolean use_sfx_prefix;

static int GetSfxLumpNum(sfxinfo_t *sfx)
{
    char name[9];

    if (sfx->link != NULL) sfx = sfx->link;
    if (use_sfx_prefix) M_snprintf(name, sizeof(name), "ds%s", DEH_String(sfx->name));
    else M_StringCopy(name, DEH_String(sfx->name), sizeof(name));
    // Unlike the SDL backend, a sound missing from the WAD is skipped, not fatal.
    return W_CheckNumForName(name);
}

// Decodes on the engine thread, once per sound; the result lives for the process.
static const sound_t *LoadSound(sfxinfo_t *sfx)
{
    if (sfx->driver_data != NULL) return sfx->driver_data;
    const int lump = sfx->lumpnum >= 0 ? sfx->lumpnum : GetSfxLumpNum(sfx);
    if (lump < 0) return NULL;

    const int size = W_LumpLength(lump);
    const byte *data = W_CacheLumpNum(lump, PU_STATIC);
    sound_t *sound = NULL;
    // DMX format 3: rate, sample count, then unsigned 8-bit PCM. Like DMX, skip
    // the 16 padding bytes at each end.
    if (size >= 8 && data[0] == 3 && data[1] == 0)
    {
        const uint32_t rate = data[2] | data[3] << 8;
        uint32_t length = data[4] | data[5] << 8 | data[6] << 16 | (uint32_t) data[7] << 24;
        if (rate > 0 && length > 32 && length <= (uint32_t) size - 8)
        {
            length -= 32;
            sound = malloc(sizeof(sound_t) + length * sizeof(int16_t));
            if (sound != NULL)
            {
                sound->length = length;
                sound->rate = rate;
                for (uint32_t i = 0; i < length; ++i)
                {
                    sound->samples[i] = (int16_t) ((data[8 + 16 + i] - 128) << 8);
                }
            }
        }
    }
    W_ReleaseLumpNum(lump);
    sfx->driver_data = sound;
    return sound;
}

// As Chocolate Doom's SDL backend: separation 0..254 pans left to right.
static void SetParams(channel_t *channel, int vol, int sep)
{
    const int left = ((254 - sep) * vol) / 127;
    const int right = (sep * vol) / 127;
    channel->left = left < 0 ? 0 : left > 255 ? 255 : left;
    channel->right = right < 0 ? 0 : right > 255 ? 255 : right;
}

static boolean SoundInit(boolean prefix)
{
    use_sfx_prefix = prefix;
    return true;
}

static void SoundShutdown(void) {}
static void SoundUpdate(void) {}

static void UpdateSoundParams(int channel, int vol, int sep)
{
    if (channel < 0 || channel >= CHANNELS) return;
    pthread_mutex_lock(&mix_lock);
    SetParams(&channels[channel], vol, sep);
    pthread_mutex_unlock(&mix_lock);
}

static int StartSound(sfxinfo_t *sfx, int channel, int vol, int sep)
{
    if (channel < 0 || channel >= CHANNELS) return -1;
    const sound_t *sound = LoadSound(sfx);
    if (sound == NULL) return -1;
    pthread_mutex_lock(&mix_lock);
    channels[channel].sound = sound;
    channels[channel].position = 0;
    channels[channel].step = ((uint64_t) sound->rate << 32) / NOTI_AUDIO_RATE;
    SetParams(&channels[channel], vol, sep);
    pthread_mutex_unlock(&mix_lock);
    return channel;
}

static void StopSound(int channel)
{
    if (channel < 0 || channel >= CHANNELS) return;
    pthread_mutex_lock(&mix_lock);
    channels[channel].sound = NULL;
    pthread_mutex_unlock(&mix_lock);
}

static boolean SoundIsPlaying(int channel)
{
    if (channel < 0 || channel >= CHANNELS) return false;
    pthread_mutex_lock(&mix_lock);
    const boolean playing = channels[channel].sound != NULL;
    pthread_mutex_unlock(&mix_lock);
    return playing;
}

static void CacheSounds(sfxinfo_t *sounds, int count)
{
    for (int i = 0; i < count; ++i) LoadSound(&sounds[i]);
}

static void MixSounds(int32_t *mix, int frames)
{
    pthread_mutex_lock(&mix_lock);
    for (int c = 0; c < CHANNELS; ++c)
    {
        channel_t *channel = &channels[c];
        const sound_t *sound = channel->sound;
        if (sound == NULL) continue;
        for (int i = 0; i < frames; ++i)
        {
            const uint32_t index = (uint32_t) (channel->position >> 32);
            if (index >= sound->length)
            {
                channel->sound = NULL;
                break;
            }
            const int32_t a = sound->samples[index];
            const int32_t b = index + 1 < sound->length ? sound->samples[index + 1] : a;
            const int32_t fraction = (int32_t) ((channel->position >> 16) & 0xffff);
            const int32_t sample = a + (((b - a) * fraction) >> 16);
            mix[i * 2] += (sample * channel->left) >> 8;
            mix[i * 2 + 1] += (sample * channel->right) >> 8;
            channel->position += channel->step;
        }
    }
    pthread_mutex_unlock(&mix_lock);
}

static snddevice_t sound_devices[] =
{
    SNDDEVICE_SB, SNDDEVICE_PAS, SNDDEVICE_GUS,
    SNDDEVICE_WAVEBLASTER, SNDDEVICE_SOUNDCANVAS, SNDDEVICE_AWE32,
};

sound_module_t DG_sound_module =
{
    sound_devices, COUNT(sound_devices), SoundInit, SoundShutdown, GetSfxLumpNum,
    SoundUpdate, UpdateSoundParams, StartSound, StopSound, SoundIsPlaying, CacheSounds,
};

// ---- Software OPL driver ---------------------------------------------------
// Adapted from Chocolate Doom's opl_sdl.c: the output callback generates OPL
// samples and fires the player's timed callbacks as the sample clock passes them.

typedef struct
{
    unsigned int rate;       // timer ticks per second
    unsigned int enabled;
    unsigned int value;
    uint64_t expire_time;
} opl_timer_t;

// opl_lock (recursive) guards the chip, timers and callback execution; it is
// what OPL_Lock takes. queue_lock guards the callback queue and the clock.
static pthread_mutex_t opl_lock;
static pthread_once_t opl_lock_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t queue_lock = PTHREAD_MUTEX_INITIALIZER;
static opl_callback_queue_t *callback_queue;
static uint64_t current_time;
static uint64_t pause_offset;
static int opl_paused;
static int opl3_mode;
static int register_num;
static Chip opl_chip;
static int32_t opl_buffer[CHUNK * 2];
static opl_timer_t timer1 = { 12500, 0, 0, 0 };
static opl_timer_t timer2 = { 3125, 0, 0, 0 };
static atomic_int opl_ready;
// Chip detection waits on OPL_Delay. While the music module initializes, the
// output stream may not be running, so delays complete immediately instead.
static atomic_int opl_clock_immediate;

static void InitOplLock(void)
{
    pthread_mutexattr_t attributes;
    pthread_mutexattr_init(&attributes);
    pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&opl_lock, &attributes);
    pthread_mutexattr_destroy(&attributes);
}

static int OplInit(unsigned int port_base)
{
    (void) port_base;
    pthread_once(&opl_lock_once, InitOplLock);
    pthread_mutex_lock(&queue_lock);
    if (callback_queue == NULL) callback_queue = OPL_Queue_Create();
    else OPL_Queue_Clear(callback_queue);
    current_time = 0;
    pause_offset = 0;
    opl_paused = 0;
    pthread_mutex_unlock(&queue_lock);

    pthread_mutex_lock(&opl_lock);
    DBOPL_InitTables();
    Chip__Chip(&opl_chip);
    Chip__Setup(&opl_chip, NOTI_AUDIO_RATE);
    opl3_mode = 0;
    register_num = 0;
    timer1.enabled = 0;
    timer2.enabled = 0;
    pthread_mutex_unlock(&opl_lock);
    atomic_store(&opl_ready, 1);
    return 1;
}

static void OplShutdown(void)
{
    atomic_store(&opl_ready, 0);
    pthread_mutex_lock(&queue_lock);
    // The queue is kept: the output callback may still be holding a reference.
    if (callback_queue != NULL) OPL_Queue_Clear(callback_queue);
    pthread_mutex_unlock(&queue_lock);
}

static unsigned int OplReadPort(opl_port_t port)
{
    unsigned int result = 0;
    if (port == OPL_REGISTER_PORT_OPL3) return 0xff;
    if (timer1.enabled && current_time > timer1.expire_time) result |= 0x80 | 0x40;
    if (timer2.enabled && current_time > timer2.expire_time) result |= 0x80 | 0x20;
    return result;
}

static void OplCalculateEndTime(opl_timer_t *timer)
{
    if (timer->enabled)
    {
        const int tics = 0x100 - timer->value;
        timer->expire_time = current_time + ((uint64_t) tics * OPL_SECOND) / timer->rate;
    }
}

static void OplWriteRegister(unsigned int reg, unsigned int value)
{
    switch (reg)
    {
        case OPL_REG_TIMER1:
            timer1.value = value;
            OplCalculateEndTime(&timer1);
            break;
        case OPL_REG_TIMER2:
            timer2.value = value;
            OplCalculateEndTime(&timer2);
            break;
        case OPL_REG_TIMER_CTRL:
            if (value & 0x80)
            {
                timer1.enabled = 0;
                timer2.enabled = 0;
            }
            else
            {
                if ((value & 0x40) == 0)
                {
                    timer1.enabled = (value & 0x01) != 0;
                    OplCalculateEndTime(&timer1);
                }
                if ((value & 0x20) == 0)
                {
                    timer2.enabled = (value & 0x02) != 0;
                    OplCalculateEndTime(&timer2);
                }
            }
            break;
        case OPL_REG_NEW:
            opl3_mode = value & 0x01;
            Chip__WriteReg(&opl_chip, reg, value);
            break;
        default:
            Chip__WriteReg(&opl_chip, reg, value);
            break;
    }
}

static void OplWritePort(opl_port_t port, unsigned int value)
{
    pthread_mutex_lock(&opl_lock);
    if (port == OPL_REGISTER_PORT) register_num = value;
    else if (port == OPL_REGISTER_PORT_OPL3) register_num = value | 0x100;
    else if (port == OPL_DATA_PORT) OplWriteRegister(register_num, value);
    pthread_mutex_unlock(&opl_lock);
}

static void OplSetCallback(uint64_t us, opl_callback_t callback, void *data)
{
    if (atomic_load(&opl_clock_immediate))
    {
        pthread_mutex_lock(&queue_lock);
        current_time += us;
        pthread_mutex_unlock(&queue_lock);
        callback(data);
        return;
    }
    pthread_mutex_lock(&queue_lock);
    OPL_Queue_Push(callback_queue, callback, data, current_time - pause_offset + us);
    pthread_mutex_unlock(&queue_lock);
}

static void OplClearCallbacks(void)
{
    pthread_mutex_lock(&queue_lock);
    OPL_Queue_Clear(callback_queue);
    pthread_mutex_unlock(&queue_lock);
}

static void OplLock(void) { pthread_mutex_lock(&opl_lock); }
static void OplUnlock(void) { pthread_mutex_unlock(&opl_lock); }

static void OplSetPaused(int paused)
{
    pthread_mutex_lock(&queue_lock);
    opl_paused = paused;
    pthread_mutex_unlock(&queue_lock);
}

static void OplAdjustCallbacks(float factor)
{
    pthread_mutex_lock(&queue_lock);
    OPL_Queue_AdjustCallbacks(callback_queue, current_time, factor);
    pthread_mutex_unlock(&queue_lock);
}

// opl.c is compiled with opl_sdl_driver=noti_opl_driver.
opl_driver_t noti_opl_driver =
{
    "Noti software OPL", OplInit, OplShutdown, OplReadPort, OplWritePort, OplSetCallback,
    OplClearCallbacks, OplLock, OplUnlock, OplSetPaused, OplAdjustCallbacks,
};

static void AdvanceOpl(unsigned int frames)
{
    opl_callback_t callback;
    void *data;

    pthread_mutex_lock(&queue_lock);
    const uint64_t us = ((uint64_t) frames * OPL_SECOND) / NOTI_AUDIO_RATE;
    current_time += us;
    if (opl_paused) pause_offset += us;
    while (!OPL_Queue_IsEmpty(callback_queue)
        && current_time >= OPL_Queue_Peek(callback_queue) + pause_offset)
    {
        if (!OPL_Queue_Pop(callback_queue, &callback, &data)) break;
        pthread_mutex_unlock(&queue_lock);
        pthread_mutex_lock(&opl_lock);
        callback(data);
        pthread_mutex_unlock(&opl_lock);
        pthread_mutex_lock(&queue_lock);
    }
    pthread_mutex_unlock(&queue_lock);
}

static void MixMusic(int32_t *mix, int frames)
{
    if (!atomic_load(&opl_ready) || atomic_load(&opl_clock_immediate)) return;
    int filled = 0;
    while (filled < frames)
    {
        // Generate up to the next callback so register writes land on time.
        int count = frames - filled;
        pthread_mutex_lock(&queue_lock);
        if (!opl_paused && !OPL_Queue_IsEmpty(callback_queue))
        {
            const uint64_t next = OPL_Queue_Peek(callback_queue) + pause_offset;
            const uint64_t until = next > current_time
                ? ((next - current_time) * NOTI_AUDIO_RATE + OPL_SECOND - 1) / OPL_SECOND : 0;
            if (until < (uint64_t) count) count = (int) until;
        }
        pthread_mutex_unlock(&queue_lock);

        if (count > 0)
        {
            int32_t *out = mix + filled * 2;
            pthread_mutex_lock(&opl_lock);
            if (opl3_mode)
            {
                Chip__GenerateBlock3(&opl_chip, count, opl_buffer);
                for (int i = 0; i < count * 2; ++i) out[i] += opl_buffer[i];
            }
            else
            {
                Chip__GenerateBlock2(&opl_chip, count, opl_buffer);
                for (int i = 0; i < count; ++i)
                {
                    out[i * 2] += opl_buffer[i];
                    out[i * 2 + 1] += opl_buffer[i];
                }
            }
            pthread_mutex_unlock(&opl_lock);
        }
        filled += count;
        AdvanceOpl(count);
    }
}

// ---- Music module ----------------------------------------------------------
// i_oplmusic.c is compiled with music_opl_module=noti_opl_music_module; this
// wrapper only makes its chip detection independent of the output stream.

extern music_module_t noti_opl_music_module;

static boolean MusicInit(void)
{
    atomic_store(&opl_clock_immediate, 1);
    const boolean ok = noti_opl_music_module.Init();
    atomic_store(&opl_clock_immediate, 0);
    if (!ok) AudioLog("OPL music is unavailable");
    return ok;
}

static void MusicShutdown(void) { noti_opl_music_module.Shutdown(); }
static void MusicSetVolume(int volume) { noti_opl_music_module.SetMusicVolume(volume); }
static void MusicPause(void) { noti_opl_music_module.PauseMusic(); }
static void MusicResume(void) { noti_opl_music_module.ResumeMusic(); }
static void *MusicRegister(void *data, int length) { return noti_opl_music_module.RegisterSong(data, length); }
static void MusicUnregister(void *handle) { noti_opl_music_module.UnRegisterSong(handle); }
static void MusicPlay(void *handle, boolean looping) { noti_opl_music_module.PlaySong(handle, looping); }
static void MusicStop(void) { noti_opl_music_module.StopSong(); }
static boolean MusicIsPlaying(void) { return noti_opl_music_module.MusicIsPlaying(); }

static snddevice_t music_devices[] = { SNDDEVICE_ADLIB, SNDDEVICE_SB };

music_module_t DG_music_module =
{
    music_devices, COUNT(music_devices), MusicInit, MusicShutdown, MusicSetVolume,
    MusicPause, MusicResume, MusicRegister, MusicUnregister, MusicPlay, MusicStop,
    MusicIsPlaying, NULL,
};

// ---- Output ----------------------------------------------------------------

static pthread_mutex_t control_lock = PTHREAD_MUTEX_INITIALIZER;
static int engine_running;
static atomic_int volume_q8 = 256;

void noti_audio_render(int16_t *output, int frames)
{
    int32_t mix[CHUNK * 2];
    const int32_t volume = atomic_load(&volume_q8);
    while (frames > 0)
    {
        const int count = frames < CHUNK ? frames : CHUNK;
        memset(mix, 0, sizeof(int32_t) * 2 * count);
        MixSounds(mix, count);
        MixMusic(mix, count);
        for (int i = 0; i < count * 2; ++i)
        {
            const int32_t value = (mix[i] * volume) >> 8;
            output[i] = (int16_t) (value > 32767 ? 32767 : value < -32768 ? -32768 : value);
        }
        output += count * 2;
        frames -= count;
    }
}

#ifdef __ANDROID__
static AAudioStream *stream;
static int stream_started;

static aaudio_data_callback_result_t OnAudio(AAudioStream *source, void *user, void *data,
                                            int32_t frames)
{
    (void) source;
    (void) user;
    noti_audio_render(data, frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void ApplyOutputLocked(void);

static void *ReopenStream(void *unused)
{
    (void) unused;
    pthread_mutex_lock(&control_lock);
    if (stream != NULL) AAudioStream_close(stream);
    stream = NULL;
    stream_started = 0;
    ApplyOutputLocked();
    pthread_mutex_unlock(&control_lock);
    return NULL;
}

static void OnAudioError(AAudioStream *source, void *user, aaudio_result_t error)
{
    (void) source;
    (void) user;
    // Route changes (e.g. headphones) disconnect the stream; it must be reopened
    // outside this callback.
    if (error != AAUDIO_ERROR_DISCONNECTED) return;
    pthread_t thread;
    if (pthread_create(&thread, NULL, ReopenStream, NULL) == 0) pthread_detach(thread);
}

static void OpenStreamLocked(void)
{
    AAudioStreamBuilder *builder;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, NOTI_AUDIO_RATE);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);
    AAudioStreamBuilder_setDataCallback(builder, OnAudio, NULL);
    AAudioStreamBuilder_setErrorCallback(builder, OnAudioError, NULL);
    const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK)
    {
        stream = NULL;
        AudioLog("Could not open the audio output");
        return;
    }
    // The mixer produces exactly this format.
    if (AAudioStream_getSampleRate(stream) != NOTI_AUDIO_RATE
     || AAudioStream_getChannelCount(stream) != 2
     || AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16)
    {
        AAudioStream_close(stream);
        stream = NULL;
        AudioLog("The audio output does not support 48 kHz stereo 16-bit");
        return;
    }
    AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getFramesPerBurst(stream) * 2);
}

static void ApplyOutputLocked(void)
{
    const int play = engine_running && atomic_load(&volume_q8) > 0;
    if (play && stream == NULL) OpenStreamLocked();
    if (stream == NULL) return;
    if (play && !stream_started)
    {
        stream_started = AAudioStream_requestStart(stream) == AAUDIO_OK;
    }
    else if (!play && stream_started)
    {
        // Pausing also stops the music clock and holds sounds where they are.
        AAudioStream_requestPause(stream);
        stream_started = 0;
    }
}
#else
static void ApplyOutputLocked(void) {}
#endif

void noti_audio_set_running(int running)
{
    pthread_mutex_lock(&control_lock);
    engine_running = running;
    ApplyOutputLocked();
    pthread_mutex_unlock(&control_lock);
}

void noti_audio_set_volume(float volume)
{
    const int q8 = volume <= 0.0f ? 0 : volume >= 1.0f ? 256 : (int) (volume * 256.0f + 0.5f);
    pthread_mutex_lock(&control_lock);
    atomic_store(&volume_q8, q8);
    ApplyOutputLocked();
    pthread_mutex_unlock(&control_lock);
}
