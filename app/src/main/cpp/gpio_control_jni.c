// Relays on the Electron SMT97T, switched through the vendor kernel's /dev/gpio_control.
// The argument is a pointer to an int holding the GPIO number. Write: cmd 0x40000000 | value (0 or 1).
// Read: cmd 0x80000000, and the ioctl return value is the state. The node is world read-write on the
// panels that have it, so no root or helper is needed. Written from the protocol an owner documented;
// no vendor library is bundled or loaded.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define GPIO_CONTROL_NODE "/dev/gpio_control"
#define GPIO_CONTROL_WRITE 0x40000000UL
#define GPIO_CONTROL_READ 0x80000000UL

static int gpio_control_ioctl(unsigned long cmd, int gpio) {
    int fd = open(GPIO_CONTROL_NODE, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    int arg = gpio;
    int result = ioctl(fd, cmd, &arg);
    int failure = errno;
    close(fd);
    return result < 0 ? -failure : result;
}

JNIEXPORT jboolean JNICALL
Java_io_panelassistant_android_hardware_NativeGpioControl_nativeProbe(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return access(GPIO_CONTROL_NODE, R_OK | W_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_panelassistant_android_hardware_NativeGpioControl_nativeWrite(JNIEnv *env, jobject thiz, jint gpio, jboolean on) {
    (void)env; (void)thiz;
    if (gpio < 0 || gpio > 255) return -EINVAL;
    return gpio_control_ioctl(GPIO_CONTROL_WRITE | (on ? 1UL : 0UL), gpio);
}

JNIEXPORT jint JNICALL
Java_io_panelassistant_android_hardware_NativeGpioControl_nativeRead(JNIEnv *env, jobject thiz, jint gpio) {
    (void)env; (void)thiz;
    if (gpio < 0 || gpio > 255) return -EINVAL;
    return gpio_control_ioctl(GPIO_CONTROL_READ, gpio);
}
