#include <jni.h>

#include "native_bridge.h"

extern "C" JNIEXPORT jstring JNICALL
Java_io_warpnect_NativeBridge_nativeProtocolName(JNIEnv* env, jclass /* clazz */) {
    const auto info = warpnect::scl::bridge::native_core_info();
    return env->NewStringUTF(info.protocol_name);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeProtocolVersion(JNIEnv* /* env */, jclass /* clazz */) {
    const auto info = warpnect::scl::bridge::native_core_info();
    return static_cast<jint>(info.protocol_version);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeProtocolAbiVersion(JNIEnv* /* env */, jclass /* clazz */) {
    const auto info = warpnect::scl::bridge::native_core_info();
    return static_cast<jint>(info.protocol_abi_version);
}
