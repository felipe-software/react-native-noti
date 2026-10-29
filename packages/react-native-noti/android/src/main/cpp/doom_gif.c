// SPDX-License-Identifier: GPL-2.0-or-later
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>

// Largest integral 8:5 canvas below the default resolver's 960 px sampling threshold.
// A 960 px animated image can become 480 px wide. Keep the Kotlin screen in sync.
#define OUTPUT_WIDTH 952
#define OUTPUT_HEIGHT 595

struct Codes { uint8_t* data; int size; int bit; int overflow; };
static void emit(struct Codes* out, int code, int width) {
    if (out->bit + width > out->size * 8) { out->overflow = 1; return; }
    uint32_t bits = (uint32_t)code << (out->bit & 7);
    int at = out->bit >> 3;
    for (int i = 0; i < 3 && at + i < out->size; ++i) out->data[at + i] |= (uint8_t)(bits >> (i * 8));
    out->bit += width;
}
static int sample_step = 1;
static int encoded_size = 0;
JNIEXPORT jint JNICALL
Java_expo_modules_notificationmotion_DoomGifEncoder_encodedSize(JNIEnv* env, jobject self) {
    (void)env; (void)self; return encoded_size;
}
JNIEXPORT jint JNICALL
Java_expo_modules_notificationmotion_DoomGifEncoder_sampleStep(JNIEnv* env, jobject self) {
    (void)env; (void)self; return sample_step;
}
static int color_at(const jint* input, int i, int step) {
    int y = (i / OUTPUT_WIDTH * 200 / OUTPUT_HEIGHT) / step * step;
    int x = (i % OUTPUT_WIDTH * 320 / OUTPUT_WIDTH) / step * step;
    uint32_t argb = (uint32_t)input[y * 320 + x];
    return ((argb >> 16) & 0xe0) | ((argb >> 11) & 0x1c) | ((argb >> 6) & 3);
}
static int compressed_frame(const jint* input, uint8_t* raw, int size, int step) {
    uint32_t keys[8192]; uint16_t values[8192];
    memset(keys, 0xff, sizeof(keys));
    struct Codes out = {raw, size, 0, 0};
    int width = 9, next = 258, prefix = color_at(input, 0, step);
    emit(&out, 256, width);
    for (int i = 1; i < OUTPUT_WIDTH * OUTPUT_HEIGHT; ++i) {
        int color = color_at(input, i, step);
        uint32_t key = ((uint32_t)prefix << 8) | color;
        uint32_t hash = (key * 2654435761u) >> 19;
        while (keys[hash] != UINT32_MAX && keys[hash] != key) hash = (hash + 1) & 8191;
        if (keys[hash] == key) { prefix = values[hash]; continue; }
        emit(&out, prefix, width);
        if (next < 4096) {
            keys[hash] = key; values[hash] = next++;
            if (next > (1 << width) && width < 12) ++width;
        } else {
            emit(&out, 256, width); memset(keys, 0xff, sizeof(keys)); next = 258; width = 9;
        }
        prefix = color;
        if (out.overflow) return 0;
    }
    emit(&out, prefix, width);
    emit(&out, 257, width);
    return out.overflow ? 0 : (out.bit + 7) / 8;
}

JNIEXPORT jint JNICALL
Java_expo_modules_notificationmotion_DoomGifEncoder_direct(JNIEnv* env, jobject self, jint fd) {
    (void)env; (void)self;
    #ifdef O_DIRECT
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_DIRECT) < 0) return -errno;
    return fcntl(fd, F_GETFL);
    #else
    return -ENOTSUP;
    #endif
}

// Fixed-size RGB332 GIF frame with LZW and bounded spatial fallback.
// Padded data blocks keep image offsets stable across streamed frames.
JNIEXPORT jbyteArray JNICALL
Java_expo_modules_notificationmotion_DoomGifEncoder_encode(JNIEnv* env, jobject self, jintArray source, jint budget) {
    (void)self;
    if (!source || (*env)->GetArrayLength(env, source) != 320 * 200) return NULL;
    if (budget != 32768 && budget != 65536) return NULL;
    const int raw_size = budget;
    const int total = 19 + raw_size + (raw_size + 254) / 255 + 1;
    uint8_t* raw = calloc(raw_size, 1);
    uint8_t* out = malloc(total);
    if (!raw || !out) { free(raw); free(out); return NULL; }
    jint* input = (*env)->GetIntArrayElements(env, source, NULL);
    if (!input) { free(raw); free(out); return NULL; }
    int compressed = 0;
    for (sample_step = 1; sample_step <= 32; sample_step *= 2) {
        memset(raw, 0, raw_size);
        compressed = compressed_frame(input, raw, raw_size, sample_step);
        if (compressed) break;
    }
    encoded_size = compressed;
    if (!compressed) { (*env)->ReleaseIntArrayElements(env, source, input, JNI_ABORT); free(raw); free(out); return NULL; }
    (*env)->ReleaseIntArrayElements(env, source, input, JNI_ABORT);
    // A 40 ms request reduced recorded reversals on the tested Android 14 host.
    // Faster requests increased replays; 50 ms reduced motion without improving them.
    const uint8_t header[] = {0x21,0xf9,4,4,4,0,0,0, 0x2c,0,0,0,0,
        OUTPUT_WIDTH & 255, OUTPUT_WIDTH >> 8, OUTPUT_HEIGHT & 255, OUTPUT_HEIGHT >> 8,0,8};
    for (int i = 0; i < 19; ++i) out[i] = header[i];
    int at = 19;
    for (int offset = 0; offset < raw_size;) {
        const int count = raw_size - offset > 255 ? 255 : raw_size - offset;
        out[at++] = (uint8_t)count;
        for (int j = 0; j < count; ++j) out[at++] = raw[offset++];
    }
    out[at++] = 0;
    jbyteArray result = (*env)->NewByteArray(env, at);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, at, (jbyte*)out);
    free(raw); free(out); return result;
}
