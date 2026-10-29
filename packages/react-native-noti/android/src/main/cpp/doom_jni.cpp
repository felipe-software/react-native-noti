// SPDX-License-Identifier: GPL-2.0-or-later
#include "doom_runtime.h"
#include <array>
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_expo_modules_notificationmotion_DoomEngine_start(JNIEnv* env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return JNI_FALSE;
    std::string wad_path(chars);
    env->ReleaseStringUTFChars(path, chars);
    return noti_doom::start(wad_path) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_expo_modules_notificationmotion_DoomEngine_render(JNIEnv* env, jobject) {
    std::array<int32_t, noti_doom::kPixelCount> pixels;
    if (!noti_doom::render(pixels.data())) return nullptr;
    jintArray frame = env->NewIntArray(noti_doom::kPixelCount);
    if (frame) env->SetIntArrayRegion(frame, 0, noti_doom::kPixelCount, pixels.data());
    return frame;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_expo_modules_notificationmotion_DoomEngine_renderState(JNIEnv* env, jobject, jlongArray state) {
    if (!state || env->GetArrayLength(state) < 5) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Doom frame state needs five longs");
        return nullptr;
    }
    std::array<int32_t, noti_doom::kPixelCount> pixels;
    noti_doom::FrameInfo info;
    if (!noti_doom::render(pixels.data(), &info)) return nullptr;
    jintArray frame = env->NewIntArray(noti_doom::kPixelCount);
    if (!frame) return nullptr;
    const jlong values[] = {static_cast<jlong>(info.sequence), static_cast<jlong>(info.applied_input),
        static_cast<jlong>(info.discarded_input), info.game_tic, info.rendered_at_ns};
    env->SetLongArrayRegion(state, 0, 5, values);
    if (!env->ExceptionCheck()) env->SetIntArrayRegion(frame, 0, noti_doom::kPixelCount, pixels.data());
    return frame;
}

extern "C" JNIEXPORT jlong JNICALL
Java_expo_modules_notificationmotion_DoomEngine_keyTracked(JNIEnv*, jobject, jint code, jboolean pressed) {
    return static_cast<jlong>(noti_doom::key(code, pressed == JNI_TRUE));
}

extern "C" JNIEXPORT void JNICALL
Java_expo_modules_notificationmotion_DoomEngine_key(JNIEnv*, jobject, jint code, jboolean pressed) {
    noti_doom::key(code, pressed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_expo_modules_notificationmotion_DoomEngine_stop(JNIEnv*, jobject) {
    noti_doom::stop();
}

extern "C" JNIEXPORT void JNICALL
Java_expo_modules_notificationmotion_DoomEngine_setVolume(JNIEnv*, jobject, jfloat volume) {
    noti_doom::set_volume(volume);
}
