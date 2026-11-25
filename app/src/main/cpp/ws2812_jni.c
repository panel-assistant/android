// Addressable WS2812 LED strips behind the vendor /dev/ws2812_pwm driver (Electron WF1589T variants).
// Protocol from an owner's documentation of the vendor library (ioctl magic 'W'): SET_PARAM once after
// open with {bit_period, t0h, t1h, reset, zero} = {1250, 400, 800, 60000, 0}; then per frame SET_FRAME
// with {led_count, 24, pointer to GRB bytes} and START. The node is world read-write on these panels,
// so no root or helper is needed. No vendor library is bundled or loaded.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define WS2812_NODE "/dev/ws2812_pwm"
#define WS2812_SET_PARAM 0x40145704UL
#define WS2812_SET_FRAME 0x40105701UL
#define WS2812_START 0x5702UL
#define WS2812_STOP 0x5703UL
#define WS2812_MAX_LEDS 1024

struct ws2812_param { uint32_t bit_period, t0h, t1h, reset, zero; };
struct ws2812_frame { uint32_t led_count; uint32_t bits_per_led; uint64_t data; };

JNIEXPORT jint JNICALL
Java_io_panelassistant_android_hardware_NativeWs2812_nativeOpen(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    // Selection succeeds only when SET_PARAM succeeds on the descriptor used for output.
    int fd = open(WS2812_NODE, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct ws2812_param param = {1250, 400, 800, 60000, 0};
    if (ioctl(fd, WS2812_SET_PARAM, &param) < 0) {
        int failure = errno;
        close(fd);
        return -failure;
    }
    return fd;
}

JNIEXPORT void JNICALL
Java_io_panelassistant_android_hardware_NativeWs2812_nativeClose(JNIEnv *env, jobject thiz, jint fd) {
    (void)env; (void)thiz;
    ioctl(fd, WS2812_STOP, 0);
    close(fd);
}

// Fill [count] LEDs with one colour and transmit. Returns 0 on success or a negative errno.
JNIEXPORT jint JNICALL
Java_io_panelassistant_android_hardware_NativeWs2812_nativeFill(JNIEnv *env, jobject thiz, jint fd, jint r, jint g, jint b, jint count) {
    (void)env; (void)thiz;
    if (count < 1 || count > WS2812_MAX_LEDS) return -EINVAL;
    uint8_t *grb = malloc((size_t)count * 3);
    if (grb == NULL) return -ENOMEM;
    for (int i = 0; i < count; i++) {
        grb[i * 3] = (uint8_t)(g & 0xff);
        grb[i * 3 + 1] = (uint8_t)(r & 0xff);
        grb[i * 3 + 2] = (uint8_t)(b & 0xff);
    }
    int result;
    struct ws2812_frame frame = {(uint32_t)count, 24, (uint64_t)(uintptr_t)grb};
    if (ioctl(fd, WS2812_SET_FRAME, &frame) < 0) {
        result = -errno;
    } else {
        result = -EBUSY;
        // EBUSY means the previous frame is still in flight (about 7 ms for 234 LEDs).
        for (int attempt = 0; attempt < 10 && result == -EBUSY; attempt++) {
            if (ioctl(fd, WS2812_START, 0) == 0) result = 0;
            else if (errno == EBUSY) usleep(5000);
            else result = -errno;
        }
    }
    free(grb);
    return result;
}
