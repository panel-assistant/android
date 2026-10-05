// Derived from the Home Assistant Companion App for Android (Apache-2.0); see THIRD_PARTY.md.
// JNI binding for io.panelassistant.android.assist.wakeword.NativeMicroWakeWord. Methods are
// registered from JNI_OnLoad, so JNI_OnLoad is the library's only exported symbol.
#include <jni.h>
#include <memory>

#include "Logging.h"
#include "MicroWakeWordEngine.h"

static constexpr char LOG_TAG[] = "ha-paneld/mww-jni";
static constexpr char KOTLIN_CLASS[] = "io/panelassistant/android/assist/wakeword/NativeMicroWakeWord";

static jlong nativeCreate(
    JNIEnv* env, jclass /*clazz*/, jobject modelBuffer, jint sampleRate,
    jint featureStepSizeMs, jint tensorArenaBytes) {
    if (modelBuffer == nullptr) {
        LOGE(LOG_TAG, "Model ByteBuffer is null");
        return 0;
    }
    auto* modelData = static_cast<uint8_t*>(env->GetDirectBufferAddress(modelBuffer));
    if (modelData == nullptr) {
        LOGE(LOG_TAG, "Failed to get direct buffer address from model ByteBuffer");
        return 0;
    }
    jlong modelSize = env->GetDirectBufferCapacity(modelBuffer);
    if (modelSize <= 0) {
        LOGE(LOG_TAG, "Invalid model buffer capacity: %lld", static_cast<long long>(modelSize));
        return 0;
    }

    auto engine = std::make_unique<MicroWakeWordEngine>(
        modelData,
        static_cast<size_t>(modelSize),
        static_cast<int>(sampleRate),
        static_cast<int>(featureStepSizeMs),
        tensorArenaBytes > 0 ? static_cast<size_t>(tensorArenaBytes) : 0
    );
    if (!engine->isInitialized()) {
        return 0;
    }
    return reinterpret_cast<jlong>(engine.release());
}

static jint nativeProcessAudio(JNIEnv* env, jclass /*clazz*/, jlong handle, jshortArray samplesArray, jint count) {
    if (handle == 0 || samplesArray == nullptr) return MicroWakeWordEngine::NO_INFERENCE;
    auto* engine = reinterpret_cast<MicroWakeWordEngine*>(handle);

    const jsize length = env->GetArrayLength(samplesArray);
    if (count < 0 || count > length) return MicroWakeWordEngine::NO_INFERENCE;

    // GetShortArrayElements may pin (zero-copy) or copy; either way no per-call heap allocation
    // on this 10 ms hot path.
    jshort* samples = env->GetShortArrayElements(samplesArray, nullptr);
    if (samples == nullptr) return MicroWakeWordEngine::NO_INFERENCE;
    const int result = engine->processAudio(samples, static_cast<size_t>(count));
    env->ReleaseShortArrayElements(samplesArray, samples, JNI_ABORT);
    return result;
}

static jint nativeStride(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return 0;
    return reinterpret_cast<MicroWakeWordEngine*>(handle)->stride();
}

static void nativeReset(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<MicroWakeWordEngine*>(handle)->reset();
}

static void nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<MicroWakeWordEngine*>(handle);
}

static const JNINativeMethod methods[] = {
    {"nativeCreate", "(Ljava/nio/ByteBuffer;III)J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeProcessAudio", "(J[SI)I", reinterpret_cast<void*>(nativeProcessAudio)},
    {"nativeStride", "(J)I", reinterpret_cast<void*>(nativeStride)},
    {"nativeReset", "(J)V", reinterpret_cast<void*>(nativeReset)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
};

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass(KOTLIN_CLASS);
    if (clazz == nullptr) {
        LOGE(LOG_TAG, "Failed to find %s for JNI registration", KOTLIN_CLASS);
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE(LOG_TAG, "Failed to register native methods");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
