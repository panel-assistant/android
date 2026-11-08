// Exercise the production argv parser without opening a socket or changing signal handlers.
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

static unsigned socket_attempts;
static int refused_socket(int domain, int type, int protocol) {
    (void)domain; (void)type; (void)protocol;
    socket_attempts++;
    errno = EACCES;
    return -1;
}
static int quiet_fprintf(FILE *stream, const char *format, ...) {
    (void)stream; (void)format; return 0;
}
static void quiet_perror(const char *label) { (void)label; }
static int ignored_sigaction(int number, const struct sigaction *action, struct sigaction *old) {
    (void)number; (void)action; (void)old; return 0;
}
static __sighandler_t ignored_signal(int number, __sighandler_t handler) {
    (void)number; (void)handler; return SIG_DFL;
}
#define socket refused_socket
#define sigaction(...) ignored_sigaction(__VA_ARGS__)
#define signal ignored_signal
#define fprintf quiet_fprintf
#define perror quiet_perror
#define main cdprelay_program_main
#include "cdprelay.c" /* from -I.: the helper copy being built */
#undef main
#undef socket
#undef sigaction
#undef signal
#undef fprintf
#undef perror

static int exercise(const uint8_t *data, size_t size) {
    if (size > 8192) size = 8192;
    char *input = malloc(size + 1);
    if (!input) abort();
    memcpy(input, data, size); input[size] = 0;
    size_t split = strnlen(input, size);
    char *name = split < size ? input + split + 1 : (char *)"webview_devtools_remote_1";
    char *args[] = {(char *)"cdprelay", input, name, NULL};
    socket_attempts = 0;
    int result = cdprelay_program_main(3, args);
    // A valid argument pair must reach exactly the refused socket; rejection must precede it.
    if ((result != 1 && result != 2) || socket_attempts != (unsigned)(result == 1)) {
        fprintf(stderr, "FAIL: fuzz-oracle: argument-gate (result=%d sockets=%u)\n", result, socket_attempts);
        abort(); // libFuzzer saves the input only for a crash, not an exit
    }
    free(input);
    return result;
}
static void startup(void) {
    static int done;
    if (done) return;
    done = 1;
    static const char good[] = "1024\0webview_devtools_remote_1";
    static const char bad_port[] = "65536\0webview_devtools_remote_1";
    static const char bad_name[] = "1024\0webview_devtools_remote_0";
    if (exercise((const uint8_t *)good, sizeof good - 1) != 1 ||
        exercise((const uint8_t *)bad_port, sizeof bad_port - 1) != 2 ||
        exercise((const uint8_t *)bad_name, sizeof bad_name - 1) != 2) {
        fprintf(stderr, "FAIL: fuzz-oracle: argument-gate (fixed valid/invalid argument pairs)\n");
        abort(); // libFuzzer saves the input only for a crash, not an exit
    }
}
int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    startup();
    (void)exercise(data, size);
    // The relay carries arbitrary bytes, not an HTTP grammar. Its other parsed input is IPv4.
    if (size >= sizeof(uint32_t)) {
        uint32_t address; memcpy(&address, data, sizeof address);
        volatile int allowed = cdprelay_peer_allowed(address);
        (void)allowed;
    }
    return 0;
}
#ifndef HAPANELD_LIBFUZZER
int main(int argc, char **argv) {
    unsigned long rounds = argc > 1 ? strtoul(argv[1], NULL, 10) : 100000;
    static const char *const ports[] = {"1023", "1024", "65535", "65536", "-1", "", "1e9", "999999999999999999999999999999999", "+1024", " 1024"};
    static const char *const names[] = {"webview_devtools_remote_1", "webview_devtools_remote_0", "webview_devtools_remote_", "webview_devtools_remote_1;id", "other"};
    uint8_t input[8192];
    for (size_t p = 0; p < sizeof ports / sizeof *ports; p++) for (size_t n = 0; n < sizeof names / sizeof *names; n++) {
        size_t a = strlen(ports[p]), b = strlen(names[n]);
        memcpy(input, ports[p], a + 1); memcpy(input + a + 1, names[n], b);
        LLVMFuzzerTestOneInput(input, a + 1 + b);
    }
    uint32_t state = 0xc0ffee;
    for (unsigned long i = 0; i < rounds; i++) {
        size_t n = i % sizeof input;
        for (size_t j = 0; j < n; j++) { state ^= state << 13; state ^= state >> 17; state ^= state << 5; input[j] = (uint8_t)state; }
        LLVMFuzzerTestOneInput(input, n);
    }
    puts("CDPRELAY SANITIZER SMOKE OK");
    return 0;
}
#endif
