// Clean-room NDK driver for the Electron WF1589T rk3576 panel's front RGB LED.
//
// The RGB LED is reached only via the char device /dev/ledjni, which is world-rwx and labelled
// app-accessible (SELinux `device` domain), so a sandboxed app can drive it WITHOUT root or a
// helper. The ioctl protocol below was reverse-engineered clean-room from the vendor libjnielc.so
// (capstone disasm, 2026-06-03); only the binary interface (ioctl request numbers, value range,
// open flags) is reproduced here — an interop fact about the hardware, not vendor code. ha-paneld
// bundles NO vendor bytes; this file is its own implementation.
//
// Protocol:  open(O_RDONLY|O_NOCTTY); ioctl(fd,0xa1,R); ioctl(fd,0xa2,G); ioctl(fd,0xa3,B); close.
//            all-off = ioctl(fd,0x99,0). The vendor lib passed 0..15, but a hardware probe (an rk3576 panel,
//            2026-07-11) showed the node responds across the full 0..255 range per channel (brightness
//            ramps 15->31->63->127->255) — it is NOT 4-bit. Per-channel response is non-linear, though,
//            so the correct value comes from a per-profile LedTransfer (Kotlin), not raw passthrough.

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <android/log.h>

#define LOG_TAG "ha-paneld/led-ndk"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define DEV_PATH    "/dev/ledjni"
#define IOCTL_RED   0xa1
#define IOCTL_GREEN 0xa2
#define IOCTL_BLUE  0xa3
#define IOCTL_OFF   0x99

// Open the LED node with the vendor's flags. Returns fd >= 0, or -1 (errno set).
static int led_open(void) {
    return open(DEV_PATH, O_RDONLY | O_NOCTTY);
}

// True if the node exists and we can open it (presence + permission probe; issues no ioctls).
JNIEXPORT jboolean JNICALL
Java_io_panelassistant_android_hardware_NativeLed_nativeProbe(JNIEnv *env, jobject thiz) {
    int fd = led_open();
    if (fd < 0) return JNI_FALSE;
    close(fd);
    return JNI_TRUE;
}

// Set R/G/B (each already scaled to 0..15 by the caller). Returns 0 on success, -errno on failure.
JNIEXPORT jint JNICALL
Java_io_panelassistant_android_hardware_NativeLed_nativeSetRgb(JNIEnv *env, jobject thiz,
                                                                jint r, jint g, jint b) {
    int fd = led_open();
    if (fd < 0) {
        LOGW("open %s failed: errno=%d", DEV_PATH, errno);
        return -errno;
    }
    int rc = 0;
    if (ioctl(fd, IOCTL_RED, r) < 0 && rc == 0) rc = -errno;
    if (ioctl(fd, IOCTL_GREEN, g) < 0 && rc == 0) rc = -errno;
    if (ioctl(fd, IOCTL_BLUE, b) < 0 && rc == 0) rc = -errno;
    close(fd);
    if (rc != 0) LOGW("ioctl set rgb failed: rc=%d", rc);
    return rc;
}

// All channels off. Returns 0 on success, -errno on failure.
JNIEXPORT jint JNICALL
Java_io_panelassistant_android_hardware_NativeLed_nativeOff(JNIEnv *env, jobject thiz) {
    int fd = led_open();
    if (fd < 0) return -errno;
    int rc = 0;
    if (ioctl(fd, IOCTL_OFF, 0) < 0) rc = -errno;
    close(fd);
    return rc;
}
