// Exercise ordinary socket callers with production dispatch, deadlines and child supervision.
// Only execve's Android destinations are mapped to real host shell fixtures. Their two-second
// self-completion bounds the broken-base proof even if the caller selects an unbounded primitive.
#define _GNU_SOURCE
#include "test_tmp.h"
#include "guard_maintenance.h"
#include "server.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int failures, checks;
#define CHECK(condition, message) do { checks++; if (!(condition)) { \
    fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

enum fixture { FAST, WEDGE_FIRST, WEDGE_SECOND, SHARED_RELOAD,
    SHARED_BOOTCHIME, SHARED_ACCESSIBILITY, BATTERY_FALLBACK, BATTERY_TIMEOUT,
    SLOW_INSTALL, FAILED_INSTALL };
static enum fixture mode;
static char directory[256];
struct observations { unsigned calls, invalid; char commands[8][384]; };
static struct observations *observed;

static unsigned long now_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) exit(2);
    return (unsigned long)now.tv_sec * 1000UL + (unsigned long)now.tv_nsec / 1000000UL;
}

int __real_execve(const char *path, char *const argv[], char *const envp[]);
int __wrap_execve(const char *path, char *const argv[], char *const envp[]) {
    static const char *const permitted[] = { "/system/bin/am", "/system/bin/monkey",
        "/system/bin/wm", "/system/bin/settings", "/system/bin/pm", "/system/bin/appops",
        "/system/bin/cmd", "/system/bin/dumpsys", "/system/bin/pidof" };
    int allowed = 0;
    for (unsigned i = 0; i < sizeof permitted / sizeof permitted[0]; i++)
        allowed |= strcmp(path, permitted[i]) == 0;
    if (!allowed) { observed->invalid++; errno = ENOENT; return -1; }
    unsigned call = __atomic_fetch_add(&observed->calls, 1, __ATOMIC_SEQ_CST);
    if (call >= 8) _exit(126);
    size_t used = 0;
    for (unsigned i = 0; argv[i]; i++) {
        int n = snprintf(observed->commands[call] + used,
            sizeof observed->commands[call] - used, "%s%s", i ? " " : "", argv[i]);
        if (n < 0 || (size_t)n >= sizeof observed->commands[call] - used) _exit(126);
        used += (size_t)n;
    }
    unsigned delay = mode == SLOW_INSTALL ? 400 :
        mode == SHARED_RELOAD ? 140 :
        mode == SHARED_BOOTCHIME || mode == SHARED_ACCESSIBILITY ? 110 : 0;
    int wedge = (mode == WEDGE_FIRST && call == 0) ||
        (mode == WEDGE_SECOND && call == 1) || mode == BATTERY_TIMEOUT;
    const char *output = "";
    if (!strcmp(path, "/system/bin/wm")) output = "Physical density: 320\\n";
    if (!strcmp(path, "/system/bin/settings") && argv[1] && !strcmp(argv[1], "get"))
        output = "null\\n";
    if (!strcmp(path, "/system/bin/pidof")) output = "123\\n";
    if (!strcmp(path, "/system/bin/dumpsys") && argv[1] && !strcmp(argv[1], "window"))
        output = "mCurrentFocus=Window{ com.example.app/.Main }\\n";
    char script[1024];
    if (wedge) {
        snprintf(script, sizeof script,
            "printf '%s'; /bin/sleep 2 & descendant=$!; "
            "printf '%%s %%s\\n' \"$$\" \"$descendant\" > '%s/child-%u'; wait",
            output, directory, call);
    } else {
        snprintf(script, sizeof script, "/bin/sleep %u.%03u; printf '%s'; exit %d",
            delay / 1000, delay % 1000, output,
            mode == FAILED_INSTALL ? 7 : mode == BATTERY_FALLBACK && call == 0 ? 1 : 0);
    }
    char *mapped[] = { "sh", "-c", script, NULL };
    return __real_execve("/bin/sh", mapped, envp);
}

static void *serve(void *arg) {
    int fd = *(int *)arg;
    server_serve(fd, HELPER_CALLER_SUCCESSOR);
    close(fd);
    conn_release();
    return NULL;
}

static int start_request(int *server_fd, pthread_t *worker) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0 || !conn_admit()) exit(2);
    *server_fd = pair[0];
    if (pthread_create(worker, NULL, serve, server_fd) != 0) exit(2);
    return pair[1];
}

static void send_all(int fd, const char *text) {
    size_t remaining = strlen(text);
    while (remaining) {
        ssize_t n = write(fd, text, remaining);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) exit(2);
        text += n;
        remaining -= (size_t)n;
    }
}

static void receive(int fd, char *output, size_t capacity) {
    size_t used = 0;
    while (used + 1 < capacity) {
        struct pollfd event = { .fd = fd, .events = POLLIN | POLLHUP };
        int ready;
        do { ready = poll(&event, 1, 5000); } while (ready < 0 && errno == EINTR);
        if (ready <= 0) { fputs("FAIL: fixture did not complete within its watchdog\n", stderr); exit(1); }
        ssize_t n = read(fd, output + used, capacity - used - 1);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) exit(2);
        if (n == 0) break;
        used += (size_t)n;
    }
    output[used] = '\0';
}

static int process_inactive(pid_t pid) {
    char path[64], line[512];
    snprintf(path, sizeof path, "/proc/%ld/stat", (long)pid);
    FILE *stream = fopen(path, "r");
    if (!stream) return errno == ENOENT;
    int inactive = 0;
    if (fgets(line, sizeof line, stream)) {
        char *end = strrchr(line, ')');
        inactive = end && (end[2] == 'Z' || end[2] == 'X');
    }
    fclose(stream);
    return inactive;
}

static void check_cleanup(void) {
    unsigned announced = 0;
    for (unsigned call = 0; call < observed->calls && call < 8; call++) {
        char path[320];
        snprintf(path, sizeof path, "%s/child-%u", directory, call);
        FILE *stream = fopen(path, "r");
        if (!stream) continue;
        announced++;
        long leader = -1, descendant = -1;
        int parsed = fscanf(stream, "%ld %ld", &leader, &descendant);
        fclose(stream);
        int stopped = 0;
        for (unsigned i = 0; parsed == 2 && i < 25; i++) {
            if (process_inactive((pid_t)leader) && process_inactive((pid_t)descendant)) {
                stopped = 1; break;
            }
            usleep(20000);
        }
        CHECK(parsed == 2 && leader > 1 && descendant > 1 && stopped,
            "timed-out wrapper and its announced descendant both stop acting");
        // A failed kill assertion still lets this fixture's two-second fallback finish before
        // teardown. This later observation cannot turn the failed deadline assertion green.
        for (unsigned i = 0; parsed == 2 && !stopped && i < 150; i++) {
            if (process_inactive((pid_t)descendant)) break;
            usleep(20000);
        }
        unlink(path);
    }
    unsigned expected = mode == BATTERY_TIMEOUT ? observed->calls :
        mode == WEDGE_FIRST || mode == WEDGE_SECOND ? 1 : 0;
    CHECK(announced == expected,
        "every wedged wrapper announces a real descendant before its deadline");
    // WNOWAIT observes production reaping without consuming the zombie we are trying to detect.
    siginfo_t info;
    int reaped = 0;
    for (unsigned i = 0; i < 150; i++) {
        memset(&info, 0, sizeof info);
        if (waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) < 0 && errno == ECHILD) {
            reaped = 1; break;
        }
        usleep(20000);
    }
    CHECK(reaped, "actuator caller reaps its real child without a test-side wait sweep");
    CHECK(observed->invalid == 0, "caller executes only expected Android framework destinations");
}

static unsigned long request(const char *command, char *out, size_t capacity) {
    int server_fd;
    pthread_t worker;
    int client = start_request(&server_fd, &worker);
    unsigned long began = now_ms();
    send_all(client, command);
    shutdown(client, SHUT_WR);
    receive(client, out, capacity);
    close(client);
    pthread_join(worker, NULL);
    CHECK(conn_active() == MAX_CONN - 1, "completed actuator returns its reserved connection slot");
    return now_ms() - began;
}

static void reset(enum fixture next) {
    mode = next;
    memset(observed, 0, sizeof *observed);
}

static void control(const char *command, const char *expected, unsigned calls) {
    char out[128];
    reset(FAST);
    request(command, out, sizeof out);
    CHECK(!strcmp(out, expected) && observed->calls == calls,
        "healthy retry after timeout executes the actuator and answers the following PING");
    check_cleanup();
}

static void timed_case(enum fixture next, const char *command, const char *expected,
    unsigned calls, const char *label) {
    char out[128];
    reset(next);
    unsigned long elapsed = request(command, out, sizeof out);
    CHECK(!strcmp(out, expected) && observed->calls == calls, label);
    CHECK(elapsed >= 150 && elapsed < 1200,
        "framework caller and following PING finish inside one bounded operation window");
    check_cleanup();
}

static unsigned long install(enum fixture next, const char *expected) {
    reset(next);
    int server_fd;
    pthread_t worker;
    int client = start_request(&server_fd, &worker);
    send_all(client, "INSTALLSTREAM 4\n");
    char ready[32] = "";
    size_t used = 0;
    while (used + 1 < sizeof ready) {
        struct pollfd event = { .fd = client, .events = POLLIN | POLLHUP };
        if (poll(&event, 1, 5000) <= 0) {
            fputs("FAIL: stream install upload admission exceeded its watchdog\n", stderr);
            exit(1);
        }
        ssize_t n = read(client, ready + used, 1);
        if (n <= 0) exit(2);
        used += (size_t)n;
        if (ready[used - 1] == '\n') break;
    }
    CHECK(!strcmp(ready, "READY\n"), "stream install reaches the real upload gate");
    if (strcmp(ready, "READY\n")) {
        shutdown(client, SHUT_WR);
        close(client);
        pthread_join(worker, NULL);
        return 0;
    }
    unsigned long began = now_ms();
    send_all(client, "APK!");
    shutdown(client, SHUT_WR);
    char out[128];
    receive(client, out, sizeof out);
    close(client);
    pthread_join(worker, NULL);
    CHECK(!strcmp(out, expected) && observed->calls == 1 &&
        strstr(observed->commands[0], "pm install -r -d ") == observed->commands[0],
        "stream install reports only its adjudicated result after invoking PackageManager once");
    CHECK(conn_active() == MAX_CONN - 1, "stream install returns its reserved connection slot");
    CHECK(access(HAPANELD_TEST_TMP "/.hapaneld-helper-test/hapaneld-install.apk", F_OK) != 0 && errno == ENOENT,
        "stream install removes its staged APK after success or timeout");
    check_cleanup();
    return now_ms() - began;
}

int main(void) {
    char parent[] = "/tmp/hapaneld-actuator-exec-XXXXXX";
    if (!mkdtemp(parent)) return 2;
    snprintf(directory, sizeof directory, "%s", parent);
    int lock = open("/tmp/hapaneld-helper-socket-composition.lock", O_RDWR | O_CREAT, 0600);
    if (lock < 0 || flock(lock, LOCK_EX) != 0) return 2;
    if (setenv("HAPANELD_GUARD_TEST_PARENT", directory, 1) != 0 || guard_maintenance_init() != 0) return 2;
    observed = mmap(NULL, sizeof *observed, PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (observed == MAP_FAILED) return 2;
    signal(SIGPIPE, SIG_IGN);
    for (unsigned i = 0; i < MAX_CONN - 1; i++) if (!conn_admit()) return 2;

    timed_case(WEDGE_FIRST, "RELOAD com.example.app\nPING\n", "ERR\nOK\n", 1,
        "RELOAD expires its wedged force-stop without launching a replacement");
    control("RELOAD com.example.app\nPING\n", "OK\nOK\n", 2);
    timed_case(WEDGE_SECOND, "RELOAD com.example.app\nPING\n", "ERR\nOK\n", 2,
        "RELOAD expires its wedged launcher after a successful force-stop");
    timed_case(WEDGE_FIRST, "DENSITY\nPING\n", "ERR\nOK\n", 1,
        "DENSITY rejects valid partial stdout when its framework query times out");
    control("DENSITY\nPING\n", "PHYS=320 OVER=-\nOK\n", 1);
    timed_case(SHARED_RELOAD, "RELOAD com.example.app\nPING\n", "ERR\nOK\n", 2,
        "RELOAD shares its deadline across force-stop and launch");
    timed_case(WEDGE_SECOND, "BOOTCHIME SILENCE\nPING\n", "PARTIAL\nOK\n", 2,
        "BOOTCHIME reports PARTIAL when a later setting times out after a completed write");
    timed_case(SHARED_BOOTCHIME, "BOOTCHIME SILENCE\nPING\n", "PARTIAL\nOK\n", 2,
        "BOOTCHIME stops its five-step sequence at the shared operation deadline");
    control("BOOTCHIME SILENCE\nPING\n", "OK\nOK\n", 5);
    timed_case(SHARED_ACCESSIBILITY, "GRANT io.panelassistant.android ACCESSIBILITY\nPING\n",
        "ERR\nOK\n", 2, "ACCESSIBILITY read-modify-write shares one operation deadline");
    control("GRANT io.panelassistant.android ACCESSIBILITY\nPING\n", "OK\nOK\n", 3);
    timed_case(BATTERY_TIMEOUT, "GRANT io.panelassistant.android BATTERY\nPING\n", "ERR\nOK\n", 1,
        "BATTERY cannot start its legacy fallback after the shared deadline expires");
    reset(BATTERY_FALLBACK);
    char out[128];
    request("GRANT io.panelassistant.android BATTERY\nPING\n", out, sizeof out);
    CHECK(!strcmp(out, "OK\nOK\n") && observed->calls == 2 &&
        !strncmp(observed->commands[1], "dumpsys deviceidle whitelist ", 29),
        "BATTERY retains its successful fallback after ordinary nonzero actuator exit");
    check_cleanup();

    unsigned long elapsed = install(WEDGE_FIRST, "");
    CHECK(elapsed >= 800 && elapsed < 1800,
        "timed-out PackageManager closes without a false definite installation result");
    elapsed = install(SLOW_INSTALL, "OK\n");
    CHECK(elapsed >= 350 && elapsed < 900,
        "successful slow PackageManager keeps its longer install budget and released install custody");
    install(FAILED_INSTALL, "ERR\n");
    control("PING\n", "OK\n", 0);

    for (unsigned i = 0; i < MAX_CONN - 1; i++) conn_release();
    CHECK(conn_active() == 0, "actuator fixture leaves the connection admission gate empty");
    munmap(observed, sizeof *observed);
    char owner[384], guard[320];
    snprintf(guard, sizeof guard, "%s/.hapaneld-guard-db-test", directory);
    snprintf(owner, sizeof owner, "%s/.owner.lock", guard);
    unlink(owner);
    rmdir(guard);
    CHECK(rmdir(directory) == 0, "actuator fixture removes its isolated guard state");
    close(lock);
    printf("actuator execution: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
