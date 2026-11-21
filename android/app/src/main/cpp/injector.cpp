#include <jni.h>
#include "injector_core.h"

/*
 * JNI entry point. The host app process is unprivileged, so in practice the
 * CLI (run under su) is the working path. This wrapper exists for root installs
 * and for local self-tests; it shares the exact same core with the CLI.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_mmc_patcher_mmc_1patcher_NativeInjector_nativeInject(
        JNIEnv *env, jclass clazz, jint pid, jstring path) {
    (void) clazz;
    if (!path) return INJECT_ERR_ARGS;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return INJECT_ERR_ARGS;
    int rc = injector_inject(static_cast<pid_t>(pid), chars);
    env->ReleaseStringUTFChars(path, chars);
    return static_cast<jint>(rc);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mmc_patcher_mmc_1patcher_NativeInjector_nativeInjectMemfd(
        JNIEnv *env, jclass clazz, jint pid, jstring path) {
    (void) clazz;
    if (!path) return INJECT_ERR_ARGS;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return INJECT_ERR_ARGS;
    int rc = injector_inject_memfd(static_cast<pid_t>(pid), chars);
    env->ReleaseStringUTFChars(path, chars);
    return static_cast<jint>(rc);
}
