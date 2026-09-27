#include <errno.h>
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
        server_serve(client, HELPER_CALLER_LEGACY);
        input_unsubscribe(client);
        gpio_unsubscribe(client);
        close(client);
    }

    unlink(address.sun_path);
    return 0;
}
