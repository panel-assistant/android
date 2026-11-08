// Production socket reader and dispatcher, with only recovery handoff/execution replaced. The
// replacement returns the captured header and declared body as bytes, so no host command can run.
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#include "cmd.h"
#include "server.h"
#include "version.h"

static size_t body_size;
static int input_fd = -1;
static size_t first_read;
static int failed;

ssize_t __real_read(int fd, void *buf, size_t count);
ssize_t __wrap_read(int fd, void *buf, size_t count) {
    if (fd == input_fd && first_read) {
        if (count > first_read) count = first_read;
        first_read = 0;
    }
    return __real_read(fd, buf, count);
}

static void send_bytes(int fd, const void *bytes, size_t size) {
    const unsigned char *p = bytes;
    while (size) {
        ssize_t n = write(fd, p, size);
        if (n <= 0) abort();
        p += n; size -= (size_t)n;
    }
}

void __wrap_cmd_adb_recovery(conn_ctx *ctx, const char *args) {
    (void)args;
    ctx->terminal = 1;
    if (!ctx->request) abort();
    unsigned char body[1024];
    if (body_size > sizeof body) abort();
    size_t used = ctx->body_prefix_len < body_size ? ctx->body_prefix_len : body_size;
    memcpy(body, ctx->body_prefix, used);
    while (used < body_size) {
        ssize_t n = read(ctx->fd, body + used, body_size - used);
        if (n <= 0) { send_bytes(ctx->fd, "SHORT\n", 6); return; }
        used += (size_t)n;
    }
    char header[80];
    int n = snprintf(header, sizeof header, "CAPTURE %zu %d\n", ctx->request_len,
                     ctx->request_terminator);
    send_bytes(ctx->fd, header, (size_t)n);
    send_bytes(ctx->fd, ctx->request, ctx->request_len);
    send_bytes(ctx->fd, body, used);
}

static void check(const char *label, const void *request, size_t size, size_t declared,
                  size_t split, const void *expected, size_t expected_size) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv)) abort();
    body_size = declared; input_fd = sv[0]; first_read = split;
    send_bytes(sv[1], request, size);
    shutdown(sv[1], SHUT_WR);
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);
    shutdown(sv[0], SHUT_WR);
    unsigned char output[4096]; size_t used = 0;
    for (;;) {
        ssize_t n = read(sv[1], output + used, sizeof output - used);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        used += (size_t)n;
        if (used == sizeof output) abort();
    }
    if (used != expected_size || memcmp(output, expected, used)) {
        fprintf(stderr, "FAIL: %s split=%zu: got %zu bytes, expected %zu\n", label, split,
                used, expected_size);
        failed++;
    }
    close(sv[0]); close(sv[1]); input_fd = -1;
}
#define CASE(label, input, declared, output) \
    check(label, input, sizeof(input) - 1, declared, 0, output, sizeof(output) - 1)

static long long milliseconds(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) abort();
    return now.tv_sec * 1000LL + now.tv_nsec / 1000000;
}

static void stalled_header(void) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv)) abort();
    send_bytes(sv[1], "SHELL ", 6);
    long long started = milliseconds();
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);
    long long elapsed = milliseconds() - started;
    if (elapsed < 1800 || elapsed > 3500) {
        fprintf(stderr, "FAIL: recognized recovery header deadline: %lldms\n", elapsed);
        failed++;
    }
    close(sv[0]); close(sv[1]);
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    const char input[] = "SHELL 100 17\n\nPING\nOFF\n\0binaryPING\n";
    const char output[] = "CAPTURE 12 10\nSHELL 100 17\nPING\nOFF\n\0binary";
    for (size_t split = 0; split < sizeof input - 1; split++)
        check("coalesced/split binary body never redispatched", input, sizeof input - 1,
              17, split, output, sizeof output - 1);
    CASE("ordinary CR/LF persistent commands", "PING\r\nPING\n", 0, "OK\nOK\n");
    CASE("control after recovery verb cannot expose body commands", "SHELL\x01 100 4\nPING\n", 0, "ERR\n");
    CASE("non-ASCII after recovery verb cannot expose body commands", "STATUS\x7f\nPING\n", 0, "ERR\n");
    CASE("ordinary unknown verb remains persistent", "SHELLX\nPING\n", 0, "ERR\nOK\n");
    CASE("raw NUL header preserved", "STATUS\0hidden\nPING\n", 0,
         "CAPTURE 13 10\nSTATUS\0hidden");
    CASE("raw CR delimiter preserved and terminal", "STATUS\r\nPING\n", 0,
         "CAPTURE 6 13\nSTATUS");
    CASE("raw whitespace preserved", " \tSTATUS extra\nPING\n", 0,
         "CAPTURE 14 10\n \tSTATUS extra");
    CASE("partial header EOF ignored", "SHELL 100 8", 8, "");
    CASE("short body EOF", "SHELL 100 8\nabc", 8, "SHORT\n");
    CASE("empty declared body discards trailing commands", "SHELL 100 0\nPING\n", 0,
         "CAPTURE 11 10\nSHELL 100 0");
    CASE("control recovery operation terminal", "ARM nonce\nPING\n", 0,
         "CAPTURE 9 10\nARM nonce");
    CASE("heartbeat terminal", "BEAT nonce\nPING\n", 0,
         "CAPTURE 10 10\nBEAT nonce");
    CASE("recovery terminal", "RECOVER nonce\nPING\n", 0,
         "CAPTURE 13 10\nRECOVER nonce");
    char request[1024], expected[1024];
    const char suffix[] = "CAPTURE 6 10\nSTATUS";
    snprintf(request, sizeof request, "VERSION\nBUILDID\nSTATUS\nPING\n");
    snprintf(expected, sizeof expected, "%s\n%s\n%s", helper_identity(),
             helper_build_id_record(), suffix);
    check("identity admission can precede terminal operation", request, strlen(request), 0,
          0, expected, strlen(expected));
    memset(request, 'x', 600); memcpy(request, "SHELL ", 6);
    memcpy(request + 600, "\nPING\n", 6);
    check("oversized recovery header cannot expose body commands", request, 606, 0, 0, "", 0);
    memset(request, 'x', 600); memcpy(request + 600, "\nPING\n", 6);
    check("ordinary oversized line keeps discard behavior", request, 606, 0, 0, "OK\n", 3);
    stalled_header();
    if (failed) return 1;
    puts("socket framing: binary prefix, raw header, terminal dispatch, EOF and boundaries passed");
    return 0;
}
