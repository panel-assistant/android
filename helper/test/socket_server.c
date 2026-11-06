#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "input.h"
#include "gpio.h"
#include "guard_maintenance.h"
#include "led.h"
#include "screen.h"
#include "server.h"
#include "sysexec_stub.h"

// Host-only redirection keeps production GOVREAD paths fixed while socket clients supply files.
int __real_open(const char *path, int flags, ...);
int __wrap_open(const char *path, int flags, ...) {
    // Host tests must never change any real CPU's governor, even when sysfs is writable.
    const char suffix[] = "/cpufreq/scaling_governor";
    size_t path_size = strlen(path);
    if ((flags & O_ACCMODE) != O_RDONLY &&
        strncmp(path, "/sys/devices/system/cpu/", 24) == 0 &&
        path_size >= sizeof suffix - 1 && strcmp(path + path_size - (sizeof suffix - 1), suffix) == 0) {
        errno = EACCES;
        return -1;
    }
    const char *fixture = NULL;
    int governor = 0;
    if (strcmp(path, "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") == 0) {
        governor = 1;
        fixture = getenv("HAPANELD_TEST_GOV_CURRENT");
    } else if (strcmp(path, "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors") == 0) {
        governor = 1;
        fixture = getenv("HAPANELD_TEST_GOV_AVAILABLE");
    }
    if (governor) {
        if (!fixture || !*fixture || (flags & O_ACCMODE) != O_RDONLY) { errno = EACCES; return -1; }
        return __real_open(fixture, flags);
    }
    int needs_mode = (flags & O_CREAT) != 0;
#ifdef O_TMPFILE
    needs_mode = needs_mode || (flags & O_TMPFILE) == O_TMPFILE;
#endif
    if (!needs_mode) return __real_open(path, flags);
    va_list ap;
    va_start(ap, flags);
    mode_t mode = (mode_t)va_arg(ap, int);
    va_end(ap);
    return __real_open(path, flags, mode);
}

static volatile sig_atomic_t running = 1;
static int listener = -1;

static void stop_server(int signal_number) {
    (void)signal_number;
    running = 0;
    if (listener >= 0) close(listener);
}

int main(int argc, char **argv) {
    if (argc != 3 || argv[1][0] == '\0' || argv[2][0] == '\0' ||
        strlen(argv[1]) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "usage: socket-test-server <socket-path> <guard-test-parent>\n");
        return 2;
    }
    if (setenv("HAPANELD_GUARD_TEST_PARENT", argv[2], 1) != 0) return 2;

    sysexec_stub_reset();
    sysexec_stub_add_popen("screencap -p", "PNG\nfixture\n", 0);
    if (guard_maintenance_init() != 0) return 5;
    input_init();
    gpio_init();
    screen_init();
    led_init();

    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, stop_server);
    signal(SIGINT, stop_server);

    listener = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0) return 3;

    struct sockaddr_un address;
    memset(&address, 0, sizeof address);
    address.sun_family = AF_UNIX;
    snprintf(address.sun_path, sizeof address.sun_path, "%s", argv[1]);
    unlink(address.sun_path);
    socklen_t length = offsetof(struct sockaddr_un, sun_path) + strlen(address.sun_path) + 1;
    if (bind(listener, (struct sockaddr *)&address, length) < 0 || listen(listener, MAX_CONN) < 0) {
        unlink(address.sun_path);
        close(listener);
        return 4;
    }

    puts("READY");
    fflush(stdout);
    while (running) {
        int client = accept(listener, NULL, NULL);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        server_serve(client, HELPER_CALLER_SUCCESSOR);
        input_unsubscribe(client);
        gpio_unsubscribe(client);
        close(client);
    }

    unlink(address.sun_path);
    return 0;
}
