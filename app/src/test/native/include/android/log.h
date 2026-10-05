// Host stand-in for the NDK's <android/log.h>, for build-host-jni.sh only: the JNI binding test loads
// the libraries on the JVM, where there is no Android log to write to.
#ifndef HAPANELD_HOST_ANDROID_LOG_H
#define HAPANELD_HOST_ANDROID_LOG_H

#define ANDROID_LOG_WARN 5
#define __android_log_print(priority, tag, ...) ((void)(priority), (void)(tag))

#endif
