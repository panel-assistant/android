// Exercise the lifecycle owner's real header and SHELL body parsers. No owner/guardian starts.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "sandbox.h"

static unsigned refused_forks;
static pid_t recovery_fuzz_fork(void) {
    refused_forks++; errno = EACCES; return -1;
}
// The fixture models an admitted app peer. Authentication is covered by native process tests.
static int admitted_peer(int fd, int level, int option, void *value, socklen_t *length) {
    if (level == SOL_SOCKET && option == SO_PEERCRED) {
        if (*length < sizeof(struct ucred)) abort();
        struct ucred peer = {.pid = getpid(), .uid = 0, .gid = 0};
        memcpy(value, &peer, sizeof peer); *length = sizeof peer; return 0;
    }
    return getsockopt(fd, level, option, value, length);
}
#define getsockopt(...) admitted_peer(__VA_ARGS__)
#define fork recovery_fuzz_fork
#include "adb_recovery.c" /* from -Isrc: the helper copy being built */
#undef getsockopt
#undef fork

static recovery_lease fixture_lease;
static void initialize(void) {
    static int done;
    if (done) return;
    done = 1;
    fuzz_sandbox_init();
    fuzz_sandbox_enter();
    if (open("/dev/null", O_RDONLY) != -1 || errno != EACCES) abort();
    fuzz_sandbox_leave();
}
static void prepare(void) {
    fuzz_sandbox_leave(); fuzz_sandbox_reset();
    memset(&fixture_lease, 0, sizeof fixture_lease); lease = &fixture_lease;
    memset(workers, 0, sizeof workers); memset(pending_reaps, 0, sizeof pending_reaps);
    memset(nonce, 'a', 64); nonce[64] = 0;
    managed = hold = recovery_pending = owner_draining = 0;
    guardian = -1; executing_slot = -1; refused_forks = 0;
    fuzz_sandbox_enter();
}
// Rule check: with worker launch refused, every owner request answers exactly one printable line.
static void check_owner_reply(int peer, const char *line) {
    char buffer[512]; size_t used = 0; ssize_t n;
    while (used < sizeof buffer && (n = recv(peer, buffer + used, sizeof buffer - used, MSG_DONTWAIT)) > 0)
        used += (size_t)n;
    size_t lines = 0;
    for (size_t i = 0; i < used; i++) {
        unsigned char c = (unsigned char)buffer[i];
        if (c == '\n') lines++;
        else if (c < 32 || c == 127) lines = 99;
    }
    if (!used || lines != 1 || buffer[used - 1] != '\n') {
        fprintf(stderr, "FAIL: fuzz-oracle: reply-line (recovery request=%.40s)\n", line);
        abort(); // libFuzzer saves the input only for a crash, not an exit
    }
}
static unsigned exercise(const uint8_t *data, size_t size) {
    if (size > 65536 + 160) size = 65536 + 160;
    // Use the production line reader: its bounded header, newline and EOF behavior matter too.
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0, pair)) abort();
    if (size && send(pair[1], data, size, MSG_NOSIGNAL) != (ssize_t)size) abort();
    if (shutdown(pair[1], SHUT_WR)) abort();
    prepare();
    char line[160];
    read_deadline = now_ms() + 100;
    if (!read_line(pair[0], line, sizeof line)) {
        // owner_request owns and closes its fd. Duplicate it to preserve the input body for
        // execute_shell's direct parser pass when the refused worker launch proves admission.
        int request_fd = dup(pair[0]);
        if (request_fd < 0) abort();
        owner_request(request_fd, line, NULL, 0);
        check_owner_reply(pair[1], line);
        unsigned timeout; size_t bytes; char extra;
        if (refused_forks && sscanf(line, "SHELL %u %zu %c", &timeout, &bytes, &extra) == 2 &&
                timeout >= 1 && timeout <= 180000 && bytes >= 1 && bytes <= COMMAND_LIMIT) {
            read_deadline = now_ms() + 100;
            (void)execute_shell(pair[0], timeout, bytes, NULL, 0);
        }
    }
    unsigned reached = refused_forks;
    fuzz_sandbox_leave(); close(pair[0]); close(pair[1]);
    return reached;
}
int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    initialize();
    static int checked;
    if (!checked) {
        checked = 1;
        // A complete SHELL body reaches both the owner admission fork and capture's refused fork.
        static const uint8_t valid_body[] = "SHELL 100 3\nabc";
        static const uint8_t bad_timeout[] = "SHELL 180001 3\nabc";
        static const uint8_t nul_body[] = "SHELL 100 3\na\0b";
        if (exercise(valid_body, sizeof valid_body - 1) != 2 ||
            exercise(bad_timeout, sizeof bad_timeout - 1) != 0 ||
            exercise(nul_body, sizeof nul_body - 1) != 1) abort();
    }
    (void)exercise(data, size);
    return 0;
}
#ifndef HAPANELD_LIBFUZZER
int main(int argc, char **argv) {
    unsigned long rounds = argc > 1 ? strtoul(argv[1], NULL, 10) : 100000;
    static const char *const seeds[] = {"STATUS\n", "SHELL 100 3\nabc", "SHELL 100 4\nabc", "SHELL 180001 3\nabc", "SHELL 100 65537\nabc", "SHELL 100 3 extra\nabc", "ARM a\n", "BEAT a\n", "RECOVER a\n", "SHELL -1 -1\n"};
    for (size_t i = 0; i < sizeof seeds / sizeof *seeds; i++) LLVMFuzzerTestOneInput((const uint8_t *)seeds[i], strlen(seeds[i]));
    uint8_t data[8192]; uint32_t state = 0xc0ffee;
    for (unsigned long i = 0; i < rounds; i++) {
        size_t n = i % sizeof data;
        for (size_t j = 0; j < n; j++) { state ^= state << 13; state ^= state >> 17; state ^= state << 5; data[j] = (uint8_t)state; }
        LLVMFuzzerTestOneInput(data, n);
    }
    puts("RECOVERY SANITIZER SMOKE OK");
    return 0;
}
#endif
