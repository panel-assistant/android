#define _GNU_SOURCE
#include "adb_recovery.h"
#include "identity.h"
#include "version.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <stdatomic.h>
#include <stdint.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef ADB_RECOVERY_SOCKET
#define ADB_RECOVERY_SOCKET "hapaneld-adb-recovery"
#endif
#ifndef ADB_RECOVERY_LEASE_MS
#define ADB_RECOVERY_LEASE_MS 300000
#endif
#ifndef ADB_RECOVERY_SHELL
#define ADB_RECOVERY_SHELL "/system/bin/sh"
#endif
#ifndef ADB_RECOVERY_SETPROP
#define ADB_RECOVERY_SETPROP "/system/bin/setprop"
#endif
#ifndef ADB_RECOVERY_GETPROP
#define ADB_RECOVERY_GETPROP "/system/bin/getprop"
#endif
#define OUTPUT_LIMIT 32768
#define COMMAND_LIMIT 65536
#define MAX_EXECUTORS 4
static long long read_deadline;
typedef struct { _Atomic unsigned renewed; _Atomic int armed, recovering, recovered, ever_armed, actuator, security_unverified; _Atomic int groups[MAX_EXECUTORS]; } recovery_lease;
static recovery_lease *lease;
static int executing_slot = -1;
static long long now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return -1;
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static int write_all(int fd, const void *bytes, size_t count) {
    const char *p = bytes;
    while (count) {
        ssize_t n = send(fd, p, count, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n; count -= (size_t)n;
    }
    return 0;
}
static int exact_read(int fd, void *bytes, size_t count) {
    char *p = bytes;
    while (count) {
        long long remaining = read_deadline - now_ms();
        if (remaining <= 0) return -1;
        struct pollfd ready = { .fd = fd, .events = POLLIN };
        int polled = poll(&ready, 1, remaining > INT_MAX ? INT_MAX : (int)remaining);
        if (polled < 0 && errno == EINTR) continue;
        if (polled <= 0) return -1;
        ssize_t n = recv(fd, p, count, MSG_DONTWAIT);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (n <= 0) return -1;
        p += n; count -= (size_t)n;
    }
    return 0;
}
static int read_line(int fd, char *out, size_t capacity) {
    size_t used = 0;
    while (used + 1 < capacity) {
        char ch;
        if (exact_read(fd, &ch, 1)) return -1;
        if (ch == '\n') { out[used] = 0; return 0; }
        if (ch < 32 || ch > 126) return -1;
        out[used++] = ch;
    }
    return -1;
}
static int peer_allowed(int fd) {
    struct ucred peer; socklen_t size = sizeof peer;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size)) return 0;
    if (!peer.uid) return 1;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {
        struct stat st;
        if (!stat(HELPER_APP_PACKAGES[i].data_dir, &st) && st.st_uid == peer.uid) return 1;
    }
    return 0;
}
static void *reap_later(void *argument) {
    pid_t child = (pid_t)(intptr_t)argument;
    while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {}
    return NULL;
}
static void bounded_reap(pid_t child, int *status) {
    long long until = now_ms() + 500;
    while (now_ms() < until) {
        pid_t done = waitpid(child, status, WNOHANG);
        if (done == child || (done < 0 && errno != EINTR)) return;
        usleep(1000);
    }
    pthread_t reaper;
    if (!pthread_create(&reaper, NULL, reap_later, (void *)(intptr_t)child)) pthread_detach(reaper);
}
/* Every child has a separate process group; timeout also kills descendants holding a pipe open. */
static int capture(const char *path, char *const argv[], unsigned timeout, unsigned char *output,
                   size_t capacity, size_t *used, int *timed_out, int *truncated) {
    *used = 0; *timed_out = 0; *truncated = 0;
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC)) return -1;
    pid_t child = fork();
    if (!child) {
        pid_t parent = getppid();
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent) _exit(127);
        (void)setpgid(0, 0);
        if (executing_slot >= 0) atomic_store(&lease->groups[executing_slot], getpid());
        int null = open("/dev/null", O_RDONLY);
        if (null < 0 || dup2(null, 0) < 0 || dup2(pipes[1], 1) < 0 || dup2(pipes[1], 2) < 0) _exit(127);
        close(pipes[0]); close(pipes[1]); if (null > 2) close(null);
        char *const environment[] = {
            "PATH=/system/bin:/system/xbin:/usr/bin:/bin",
            "ANDROID_ROOT=/system", "ANDROID_DATA=/data", NULL
        };
        execve(path, argv, environment);
        _exit(127);
    }
    close(pipes[1]);
    if (child < 0) { close(pipes[0]); return -1; }
    (void)setpgid(child, child);
    if (executing_slot >= 0) atomic_store(&lease->groups[executing_slot], child);
    int flags = fcntl(pipes[0], F_GETFL, 0);
    if (flags >= 0) (void)fcntl(pipes[0], F_SETFL, flags | O_NONBLOCK);
    long long deadline = now_ms() + timeout;
    int status = 0, reaped = 0, eof = 0;
    while (!reaped || !eof) {
        unsigned char buffer[4096]; ssize_t n = -1;
        int drains = 0;
        while (drains++ < 16 && now_ms() < deadline && (n = read(pipes[0], buffer, sizeof buffer)) > 0) {
            size_t keep = (size_t)n;
            if (keep > capacity - *used) { keep = capacity - *used; *truncated = 1; }
            if (keep) memcpy(output + *used, buffer, keep);
            *used += keep;
        }
        if (!n) eof = 1;
        if (!reaped) {
            pid_t done = waitpid(child, &status, WNOHANG);
            if (done == child) reaped = 1;
            else if (done < 0 && errno != EINTR) { close(pipes[0]); return -1; }
        }
        if (reaped && eof) break;
        if (now_ms() >= deadline) {
            *timed_out = 1; (void)kill(-child, SIGKILL);
            if (!reaped) bounded_reap(child, &status);
            break;
        }
        struct pollfd pfd = { .fd = pipes[0], .events = POLLIN };
        (void)poll(&pfd, 1, 10);
    }
    /* A command that returns with detached descendants never owns processes past this request. */
    (void)kill(-child, SIGKILL);
    close(pipes[0]);
    if (executing_slot >= 0) atomic_store(&lease->groups[executing_slot], 0);
    return !*timed_out && WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}
static int property(const char *name, char *out, size_t capacity) {
    char *const argv[] = { (char *)ADB_RECOVERY_GETPROP, (char *)name, NULL };
    size_t used; int timeout, truncated;
    int rc = capture(ADB_RECOVERY_GETPROP, argv, 2000, (unsigned char *)out, capacity - 1, &used, &timeout, &truncated);
    if (rc || timeout || truncated) return -1;
    out[used] = 0; out[strcspn(out, "\r\n")] = 0; return 0;
}
static int set_property(const char *name, const char *value) {
    char *const argv[] = { (char *)ADB_RECOVERY_SETPROP, (char *)name, (char *)value, NULL };
    unsigned char ignored[128]; size_t used; int timeout, truncated;
    return capture(ADB_RECOVERY_SETPROP, argv, 2000, ignored, sizeof ignored, &used, &timeout, &truncated) == 0 ? 0 : -1;
}
#define RECOVERY_FENCE "network-adb-recovery.fence"
#define AUTHORITY_PUBLICATION ".remote-debug-security-authority.v1.publish"
/* Root never edits SQLite or the authority record. Both old and new app authority loaders reject
 * the existing publication fence; the separate recovery fence also prevents concurrent resealing. */
static int fence_directory(void) {
#ifdef ADB_RECOVERY_FENCE_PARENT
    return open(ADB_RECOVERY_FENCE_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
#else
    int absent = 0;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {
        const char *paths[] = { HELPER_APP_PACKAGES[i].data_dir, HELPER_APP_PACKAGES[i].user_dir };
        for (size_t j = 0; j < 2; j++) {
            int app = open(paths[j], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (app < 0) { if (errno == ENOENT) { absent++; continue; } return -1; }
            int directory = openat(app, "no_backup", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (directory < 0 && errno == ENOENT) {
                struct stat metadata;
                if (fstat(app, &metadata) || mkdirat(app, "no_backup", 0700)) { close(app); return -1; }
                directory = openat(app, "no_backup", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
                if (directory >= 0 && fchown(directory, metadata.st_uid, metadata.st_gid)) { close(directory); directory = -1; }
            }
            close(app); return directory;
        }
    }
    return absent == (int)(HELPER_APP_PACKAGE_COUNT * 2) ? -2 : -1;
#endif
}
static int recovery_fence(int create) {
    int directory = fence_directory();
    if (directory == -2) return 0; /* uninstall removed every possible authority with the app */
    if (directory < 0) return -1;
    int result = 0;
    if (create) {
        const char *names[] = { RECOVERY_FENCE, AUTHORITY_PUBLICATION };
        for (size_t i = 0; i < (create == 1 ? 2U : 1U); i++) {
            int file = openat(directory, names[i], O_WRONLY | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
            struct stat metadata;
            if (file < 0 || fstat(file, &metadata) || !S_ISREG(metadata.st_mode) || fsync(file)) result = -1;
            if (file >= 0) close(file);
            if (result) break;
        }
    } else if (unlinkat(directory, RECOVERY_FENCE, 0) && errno != ENOENT) result = -1;
    if (fsync(directory)) result = -1;
    close(directory); return result;
}
/* Runtime-only stop closes every adbd listener, including alternate TCP/TLS ports. Persistent ON
 * remains the independent reboot fallback. Never call the app's durable explicit-OFF transition. */
#ifndef ADB_RECOVERY_TCP
#define ADB_RECOVERY_TCP "/proc/net/tcp"
#endif
#ifndef ADB_RECOVERY_TCP6
#define ADB_RECOVERY_TCP6 "/proc/net/tcp6"
#endif
static int tcp_listener(int network) {
    const char *paths[] = { ADB_RECOVERY_TCP, ADB_RECOVERY_TCP6 };
    for (size_t i = 0; i < 2; i++) {
        FILE *input = fopen(paths[i], "r");
        if (!input) return -1;
        char line[1024];
        while (fgets(line, sizeof line, input)) {
            unsigned port, state; char local[33];
            if (sscanf(line, "%*u: %32[0123456789abcdefABCDEF]:%x %*s %x", local, &port, &state) == 3 && port == 5555 && state == 10 &&
                (!network || strspn(local, "0") == strlen(local))) {
                fclose(input); return 1;
            }
        }
        int failed = ferror(input); fclose(input);
        if (failed) return -1;
    }
    return 0;
}
static int retire_security_authority(void) {
    int directory = fence_directory();
    if (directory == -2) return 0;
    if (directory < 0) return -1;
    int result = unlinkat(directory, "remote-debug-security-authority.v1", 0);
    if (result && errno == ENOENT) result = 0;
    if (fsync(directory)) result = -1;
    close(directory); return result;
}
static int set_network(int on) {
    if (on && recovery_fence(1)) {
        if (retire_security_authority()) {
            atomic_store(&lease->security_unverified, 1);
            fprintf(stderr, "hapaneld-adb-recovery: emergency network recovery; durable security authority invalidation unavailable\n");
        }
    }
    if ((on && set_property("service.adb.listen_addrs", "")) ||
        set_property("service.adb.tcp.port", on ? "5555" : "-1") ||
        set_property(on ? "ctl.restart" : "ctl.stop", "adbd")) return -1;
    long long deadline = now_ms() + 3000;
    while (now_ms() < deadline) {
        char state[32];
        if (!property("init.svc.adbd", state, sizeof state) &&
            !strcmp(state, on ? "running" : "stopped") && tcp_listener(on) == on) return 0;
        usleep(50000);
    }
    return -1;
}
static int establish_boot_fallback(void) {
    char port[32];
    return !set_property("persist.adb.tcp.port", "5555") &&
        !property("persist.adb.tcp.port", port, sizeof port) && !strcmp(port, "5555") ? 0 : -1;
}
static int nonce_valid(const char *nonce) {
    if (strlen(nonce) != 64) return 0;
    for (size_t i = 0; i < 64; i++) if (!((nonce[i] >= '0' && nonce[i] <= '9') || (nonce[i] >= 'a' && nonce[i] <= 'f'))) return 0;
    return 1;
}
static int execute_shell(int fd, unsigned timeout, size_t bytes) {
    char *command = malloc(bytes + 1);
    if (!command) return -1;
    if (exact_read(fd, command, bytes) || memchr(command, 0, bytes)) { free(command); return -1; }
    command[bytes] = 0;
    unsigned char output[OUTPUT_LIMIT]; size_t used; int timed_out, truncated;
    char *const argv[] = { (char *)ADB_RECOVERY_SHELL, "-c", command, NULL };
    int exit_code = capture(ADB_RECOVERY_SHELL, argv, timeout, output, sizeof output, &used, &timed_out, &truncated);
    free(command);
    char header[128];
    int n = snprintf(header, sizeof header, "RESULT %d %d %d %zu\n", exit_code, timed_out, truncated, used);
    return n > 0 && !write_all(fd, header, (size_t)n) && !write_all(fd, output, used) ? 0 : -1;
}
/* Two independent owners: guardian loss makes the socket owner reopen, and socket-owner loss
 * makes the guardian reopen. Neither relies on the app, the old helper, or app-data cleanup. */
static pid_t start_guardian(int server) {
    pid_t owner = getpid();
    pid_t guardian = fork();
    if (!guardian) {
        close(server);
        for (;;) {
            int owner_dead = getppid() != owner;
            int expired = atomic_load(&lease->armed) &&
                (unsigned)((unsigned)now_ms() - atomic_load(&lease->renewed)) >= ADB_RECOVERY_LEASE_MS;
            if (owner_dead && !atomic_load(&lease->ever_armed)) _exit(0);
            if (owner_dead || expired) {
                int expected = 0;
                if (!owner_dead && !atomic_compare_exchange_strong(&lease->actuator, &expected, 2)) { usleep(20000); continue; }
                if (owner_dead) atomic_store(&lease->actuator, 2);
                atomic_store(&lease->recovering, 1);
                atomic_store(&lease->recovered, 1);
                atomic_store(&lease->armed, 0);
                if (owner_dead) {
                    for (int i = 0; i < MAX_EXECUTORS; i++) {
                        int group = atomic_load(&lease->groups[i]);
                        if (group > 1) (void)kill(-group, SIGKILL);
                    }
                    usleep(200000);
                }
                while (set_network(1)) usleep(100000);
                atomic_store(&lease->recovering, 0);
                atomic_store(&lease->actuator, 0);
                if (owner_dead) _exit(0);
            }
            usleep(20000);
        }
    }
    return guardian;
}
#ifndef ADB_RECOVERY_DIRECTORY
#define ADB_RECOVERY_DIRECTORY "/data/local/panel-assistant-adb-recovery"
#endif
/* Unlink only our running inode: an upgrade may already have replaced the pathname. */
static int retire_files(void) {
    int directory = open(ADB_RECOVERY_DIRECTORY, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return errno == ENOENT ? 0 : -1;
    struct stat self, installed;
    int result = -1;
    if (!helper_app_data_absent()) { close(directory); return -1; }
    if (!fstatat(directory, "helper", &installed, AT_SYMLINK_NOFOLLOW)) {
        if (stat("/proc/self/exe", &self) || !S_ISREG(installed.st_mode) ||
            installed.st_dev != self.st_dev || installed.st_ino != self.st_ino) {
            close(directory); return -1;
        }
        if (!helper_app_data_absent() || unlinkat(directory, "helper", 0)) {
            close(directory); return -1;
        }
    } else if (errno != ENOENT) { close(directory); return -1; }
    if (!helper_app_data_absent()) { close(directory); return -1; }
    if (!rmdir(ADB_RECOVERY_DIRECTORY) || errno == ENOENT) result = 0;
    close(directory); return result;
}
static int owner_actuator(void) {
    int expected = 0;
    return atomic_compare_exchange_strong(&lease->actuator, &expected, 1);
}
int adb_recovery_main(void) {
    if (geteuid()) return 1;
    signal(SIGPIPE, SIG_IGN);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t socket_length = strlen(ADB_RECOVERY_SOCKET);
    if (socket_length + 1 > sizeof address.sun_path) return 1;
    memcpy(address.sun_path + 1, ADB_RECOVERY_SOCKET, socket_length);
    if (server < 0 || bind(server, (struct sockaddr *)&address,
            offsetof(struct sockaddr_un, sun_path) + 1 + socket_length) || listen(server, 8)) {
        if (server >= 0) close(server);
        return 1;
    }
    lease = mmap(NULL, sizeof *lease, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (lease == MAP_FAILED) { close(server); return 1; }
    memset(lease, 0, sizeof *lease);
    pid_t guardian = start_guardian(server);
    if (guardian < 0) { close(server); return 1; }
    char nonce[65] = ""; long long renewed = 0;
    int managed = 0, hold = 0, recovery_pending = 0;
    pid_t workers[MAX_EXECUTORS] = {0};
    for (;;) {
        if (atomic_load(&lease->recovered)) managed = 0;
        if (guardian > 0) {
            pid_t done = waitpid(guardian, NULL, WNOHANG);
            if (done == guardian || (done < 0 && errno != EINTR)) {
                guardian = -1; atomic_store(&lease->actuator, 0);
                if (atomic_load(&lease->ever_armed)) recovery_pending = 1;
            }
        }
        if (guardian < 0 && !managed) guardian = start_guardian(server);
        for (int i = 0; i < MAX_EXECUTORS; i++) if (workers[i] > 0) {
            int status; pid_t done = waitpid(workers[i], &status, WNOHANG);
            if (done == workers[i] || (done < 0 && errno != EINTR)) {
                workers[i] = 0;
                int group = atomic_exchange(&lease->groups[i], 0);
                if (group > 1) (void)kill(-group, SIGKILL);
                if (done < 0 || !WIFEXITED(status) || WEXITSTATUS(status)) recovery_pending = 1;
            }
        }
        if (managed && (now_ms() - renewed >= ADB_RECOVERY_LEASE_MS || helper_app_data_absent())) recovery_pending = 1;
        if (recovery_pending) {
            if (!set_network(1)) { managed = 0; recovery_pending = 0; atomic_store(&lease->armed, 0); }
        }
        if (helper_app_data_absent() && owner_actuator()) {
            /* Reopen and prove the surviving route even if this owner was never armed. */
            if (!set_network(1) && helper_app_data_absent()) {
                atomic_store(&lease->armed, 0);
                managed = 0;
                if (guardian > 0) {
                    (void)kill(guardian, SIGKILL);
                    while (waitpid(guardian, NULL, 0) < 0 && errno == EINTR) {}
                    guardian = -1;
                }
                for (int i = 0; i < MAX_EXECUTORS; i++) {
                    int group = atomic_exchange(&lease->groups[i], 0);
                    if (group > 1) (void)kill(-group, SIGKILL);
                    if (workers[i] > 0) {
                        (void)kill(workers[i], SIGKILL);
                        while (waitpid(workers[i], NULL, 0) < 0 && errno == EINTR) {}
                        workers[i] = 0;
                    }
                }
                if (!retire_files()) { close(server); return 0; }
            }
            atomic_store(&lease->actuator, 0);
        }
        struct pollfd pfd = { .fd = server, .events = POLLIN };
        if (poll(&pfd, 1, 100) <= 0) continue;
        int fd = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) continue;
        struct timeval wait = { .tv_sec = 2 };
        (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, sizeof wait);
        (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &wait, sizeof wait);
        read_deadline = now_ms() + 2000;
        char line[160];
        if (!peer_allowed(fd) || read_line(fd, line, sizeof line)) { close(fd); continue; }
        char candidate[65], extra;
        unsigned timeout; size_t bytes;
        const char *reply = "ERR\n";
        if (!strcmp(line, "STATUS")) {
            char response[160], service[32];
            int effective_managed = managed && !hold && atomic_load(&lease->armed) &&
                !atomic_load(&lease->recovering) && !atomic_load(&lease->recovered) &&
                (unsigned)((unsigned)now_ms() - atomic_load(&lease->renewed)) < ADB_RECOVERY_LEASE_MS &&
                !property("init.svc.adbd", service, sizeof service) && !strcmp(service, "stopped") && tcp_listener(0) == 0;
            int n = snprintf(response, sizeof response, "READY 1 %s %d %d %d\n", helper_build_id(), effective_managed, hold, atomic_load(&lease->security_unverified));
            (void)write_all(fd, response, (size_t)n);
        } else if (sscanf(line, "ARM %64s %c", candidate, &extra) == 1 && nonce_valid(candidate)) {
            if (strcmp(candidate, nonce)) {
                /* A later session releases a removal hold; retries within that session do not. */
                memcpy(nonce, candidate, sizeof nonce); hold = 0;
            }
            if (hold) reply = "HELD\n";
            else if (guardian > 0 && !atomic_load(&lease->recovering) && owner_actuator()) {
                if (establish_boot_fallback() || recovery_fence(2)) {
                    atomic_store(&lease->actuator, 0);
                    (void)write_all(fd, reply, strlen(reply)); close(fd); continue;
                }
                renewed = now_ms(); managed = 1; /* recovery armed BEFORE any close attempt */
                atomic_store(&lease->recovered, 0);
                atomic_store(&lease->renewed, (unsigned)renewed);
                atomic_store(&lease->ever_armed, 1);
                atomic_store(&lease->armed, 1);
                if (!set_network(0) && !atomic_load(&lease->recovered) && !recovery_fence(0)) { atomic_store(&lease->security_unverified, 0); reply = "OK\n"; }
                else recovery_pending = 1;
                atomic_store(&lease->actuator, 0);
            }
            (void)write_all(fd, reply, strlen(reply));
        } else if (sscanf(line, "BEAT %64s %c", candidate, &extra) == 1 && nonce_valid(candidate) && !strcmp(candidate, nonce)) {
            if (managed && !hold && !atomic_load(&lease->recovering) && owner_actuator()) {
                renewed = now_ms(); atomic_store(&lease->renewed, (unsigned)renewed);
                if (!set_network(0) && !atomic_load(&lease->recovered)) reply = "OK\n";
                else recovery_pending = 1;
                atomic_store(&lease->actuator, 0);
            }
            else if (hold) reply = "HELD\n";
            (void)write_all(fd, reply, strlen(reply));
        } else if (sscanf(line, "RECOVER %64s %c", candidate, &extra) == 1 && nonce_valid(candidate)) {
            /* Recovery may also precede the first ARM, e.g. removal immediately after bootstrap. */
            {
                memcpy(nonce, candidate, sizeof nonce); hold = 1;
                if (!set_network(1)) { managed = 0; recovery_pending = 0; atomic_store(&lease->armed, 0); reply = "OK\n"; }
                else recovery_pending = 1;
            }
            (void)write_all(fd, reply, strlen(reply));
        } else if (sscanf(line, "SHELL %u %zu %c", &timeout, &bytes, &extra) == 2 && timeout >= 1 && timeout <= 180000 && bytes >= 1 && bytes <= COMMAND_LIMIT) {
            int slot = 0; while (slot < MAX_EXECUTORS && workers[slot] > 0) slot++;
            if (slot == MAX_EXECUTORS) (void)write_all(fd, "BUSY\n", 5);
            else {
                pid_t owner_pid = getpid();
                pid_t worker = fork();
                if (!worker) {
                    if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != owner_pid) _exit(1);
                    close(server); executing_slot = slot;
                    _exit(execute_shell(fd, timeout, bytes) ? 1 : 0);
                }
                if (worker > 0) workers[slot] = worker;
                else (void)write_all(fd, "ERR\n", 4);
            }
        } else (void)write_all(fd, reply, strlen(reply));
        close(fd);
    }
}
