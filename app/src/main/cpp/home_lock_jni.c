#include <jni.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef HOME_LOCK_PATH
#define HOME_LOCK_PATH "/system"
#endif

/* Never create or unlink a lock file: every participant must open the same immutable inode. */
static int open_home_lock(struct stat *info) {
    int fd = open(HOME_LOCK_PATH, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    if (fstat(fd, info) != 0 || !S_ISDIR(info->st_mode) || info->st_uid != 0 ||
        (info->st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

JNIEXPORT jint JNICALL
Java_io_panelassistant_android_platform_NativeHomeLock_nativeAcquire(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    struct stat info;
    int fd = open_home_lock(&info);
    if (fd >= 0 && flock(fd, LOCK_EX | LOCK_NB) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

JNIEXPORT jstring JNICALL
Java_io_panelassistant_android_platform_NativeHomeLock_nativeIdentity(JNIEnv *env, jobject self) {
    (void)self;
    struct stat info;
    int fd = open_home_lock(&info);
    if (fd < 0) return NULL;
    char identity[64];
    snprintf(identity, sizeof(identity), "%" PRIuMAX ":%" PRIuMAX,
             (uintmax_t)info.st_dev, (uintmax_t)info.st_ino);
    close(fd);
    return (*env)->NewStringUTF(env, identity);
}

JNIEXPORT void JNICALL
Java_io_panelassistant_android_platform_NativeHomeLock_nativeClose(JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    /* Closing the held description releases exclusion. Never unlock early or remove the inode. */
    close(fd);
}
