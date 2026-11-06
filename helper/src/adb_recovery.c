#define _GNU_SOURCE
#include "adb_recovery.h"
#include "identity.h"
#include "version.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <stdatomic.h>
#include <stdint.h>
#include <inttypes.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/resource.h>
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
    if (on) {
        char state[32];
        if (!property("init.svc.adbd", state, sizeof state) && !strcmp(state, "running") &&
            tcp_listener(1) == 1) return 0;
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
/* Capture the pathname before inspecting its inode. A bootstrap may replace "helper" at
 * any time; only the privately named captured inode belongs to this retirement. */
static int restore_custody(int directory, const char *custody) {
    /* linkat cannot replace a newer bootstrap's canonical executable. The source is
     * already inside our exclusive private directory, not the live pathname. */
    if (linkat(directory, custody, directory, "helper", 0)) return -1;
    return unlinkat(directory, custody, 0);
}
static int restore_executable(int directory, int executable, const char *custody) {
    if (!restore_custody(directory, custody) || errno == EEXIST) return 0;
    if (errno != ENOENT) return -1;
    int output = openat(directory, "helper", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0700);
    if (output < 0) return errno == EEXIST ? 0 : -1;
    int result = 0;
    char bytes[8192]; ssize_t count;
    if (lseek(executable, 0, SEEK_SET) < 0) result = -1;
    while (!result && (count = read(executable, bytes, sizeof bytes)) != 0) {
        if (count < 0) { if (errno == EINTR) continue; result = -1; break; }
        ssize_t used = 0;
        while (used < count) {
            ssize_t written = write(output, bytes + used, (size_t)(count - used));
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) { result = -1; break; }
            used += written;
        }
    }
    if (fsync(output)) result = -1;
    close(output);
    if (result) (void)unlinkat(directory, "helper", 0);
    return result;
}
/* A cancelled retirement may have restored a byte-identical copy with a new inode. */
static int same_executable(int executable, int directory, const char *name, off_t size) {
    int file = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (file < 0) return 0;
    struct stat metadata;
    int same = !fstat(file, &metadata) && S_ISREG(metadata.st_mode) && metadata.st_size == size;
    for (off_t offset = 0; same && offset < size;) {
        unsigned char a[8192], b[8192];
        size_t bytes = size - offset > (off_t)sizeof a ? sizeof a : (size_t)(size - offset);
        ssize_t first = pread(executable, a, bytes, offset);
        if (first < 0 && errno == EINTR) continue;
        ssize_t second = pread(file, b, bytes, offset);
        if (second < 0 && errno == EINTR) continue;
        if (first != (ssize_t)bytes || second != first || memcmp(a, b, bytes)) same = 0;
        offset += (off_t)bytes;
    }
    close(file); return same;
}
static int retire_files(void) {
    int directory = open(ADB_RECOVERY_DIRECTORY, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return errno == ENOENT ? 0 : -1;
    int executable = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    if (executable < 0) { close(directory); return -1; }
    char holder[64], custody[80];
    snprintf(holder, sizeof holder, ".retiring-%ld-%lld", (long)getpid(), now_ms());
    snprintf(custody, sizeof custody, "%s/helper", holder);
    int result = -1, captured = 0, removed = 0;
    struct stat self, installed;
    if (!helper_app_data_absent() || mkdirat(directory, holder, 0700)) goto done;
    /* The newly created root-only directory gives renameat an unoccupied target
     * on Android kernels predating renameat2. Capture precedes the inode check. */
    if (renameat(directory, "helper", directory, custody)) {
        if (errno == ENOENT && helper_app_data_absent()) result = 0;
        goto done;
    }
    captured = 1;
    if (fstat(executable, &self) || fstatat(directory, custody, &installed, AT_SYMLINK_NOFOLLOW) ||
        !helper_app_data_absent()) goto done;
    if (!S_ISREG(installed.st_mode) ||
        ((installed.st_dev != self.st_dev || installed.st_ino != self.st_ino) &&
         !same_executable(executable, directory, custody, self.st_size))) {
        /* A newer/foreign executable belongs to its bootstrap, not this obsolete owner. */
        if (!restore_custody(directory, custody) || errno == EEXIST) {
            captured = 0;
            if (helper_app_data_absent()) result = 0;
        }
        goto done;
    }
    if (unlinkat(directory, custody, 0)) goto done;
    removed = 1;
    if (unlinkat(directory, holder, AT_REMOVEDIR)) goto done;
    if (!helper_app_data_absent()) goto done;
    if (rmdir(ADB_RECOVERY_DIRECTORY) && errno != ENOENT && errno != ENOTEMPTY) goto done;
    if (helper_app_data_absent()) result = 0;
    else {
        if (mkdir(ADB_RECOVERY_DIRECTORY, 0700) && errno != EEXIST) goto done;
        close(directory);
        directory = open(ADB_RECOVERY_DIRECTORY, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (directory < 0) { close(executable); return -1; }
    }
 done:
    if (result && captured) {
        if (removed) (void)restore_executable(directory, executable, custody);
        else (void)restore_custody(directory, custody);
    }
    (void)unlinkat(directory, holder, AT_REMOVEDIR);
    close(executable); close(directory); return result;
}
static int owner_actuator(void) {
    int expected = 0;
    return atomic_compare_exchange_strong(&lease->actuator, &expected, 1);
}
/* Serialize launchers on the existing root-owned directory, leaving no retirement artifact. */
static int replacement_directory(void) {
    int fd = open(ADB_RECOVERY_DIRECTORY, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0) return -1;
    if (fstat(fd, &st) || st.st_uid || st.st_gid || (st.st_mode & 07777) != 0700) {
        close(fd); return -1;
    }
    long long until = now_ms() + 2000;
    while (flock(fd, LOCK_EX | LOCK_NB)) {
        if ((errno != EWOULDBLOCK && errno != EINTR) || now_ms() >= until) { close(fd); return -1; }
        usleep(20000);
    }
    return fd;
}
static int process_state(int process, char *state, unsigned long long *started) {
    int fd = openat(process, "stat", O_RDONLY | O_CLOEXEC);
    char line[4096];
    ssize_t n = fd < 0 ? -1 : read(fd, line, sizeof line - 1);
    if (fd >= 0) close(fd);
    if (n <= 0) return -1;
    line[n] = 0;
    char *at = strrchr(line, ')');
    if (!at || at[1] != ' ') return -1;
    at += 2; *state = *at;
    for (int field = 3; field < 22; field++) {
        at = strchr(at, ' ');
        if (!at) return -1;
        at++;
    }
    char *end;
    *started = strtoull(at, &end, 10);
    return end == at || (*end != ' ' && *end != '\n') ? -1 : 0;
}
/* Waiting on the stale executable, rather than a pre-signal child snapshot, also covers
 * a guardian or worker forked immediately before its owner dies. Never signal these children. */
static int stale_processes(const struct stat *old) {
    DIR *proc = opendir("/proc");
    if (!proc) return -1;
    struct dirent *entry; int found = 0;
    for (;;) {
        errno = 0;
        entry = readdir(proc);
        if (!entry) { if (errno) found = -1; break; }
        char *end; long pid = strtol(entry->d_name, &end, 10);
        if (!entry->d_name[0] || *end || pid <= 0) continue;
        int process = openat(dirfd(proc), entry->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (process < 0) {
            if (errno == ENOENT || errno == ESRCH || errno == ENOTDIR) continue;
            found = -1; break;
        }
        struct stat st; char state; unsigned long long started;
        if (fstat(process, &st)) { close(process); found = -1; break; }
        /* Native guardians and workers retain root; unrelated unprivileged processes
         * may legitimately deny /proc/exe inspection (e.g. dropped-privilege daemons). */
        if (st.st_uid) { close(process); continue; }
        if (fstatat(process, "exe", &st, 0)) {
            int vanished = errno == ENOENT || errno == ESRCH;
            close(process);
            if (vanished) continue; /* exited processes and kernel threads have no executable */
            found = -1; break;
        }
        if (st.st_dev == old->st_dev && st.st_ino == old->st_ino) {
            if (process_state(process, &state, &started)) { close(process); found = -1; break; }
            if (state != 'Z') found++;
        }
        close(process);
    }
    closedir(proc); return found;
}
#define REPLACEMENT_RETIRING ".replacement-retiring"
/* This is only the inode still owed a drain, not a process id or permission to signal.
 * A durable receipt prevents a failed launcher from losing custody after its owner exits. */
static int retiring_inode(int directory, struct stat *old, struct stat *receipt) {
    int fd = openat(directory, REPLACEMENT_RETIRING, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat metadata;
    char text[128], expected[128]; uintmax_t device, inode; char extra;
    if (fstat(fd, &metadata) || !S_ISREG(metadata.st_mode) || metadata.st_uid || metadata.st_gid ||
        metadata.st_nlink != 1 || (metadata.st_mode & 07777) != 0600) { close(fd); return -1; }
    ssize_t n = read(fd, text, sizeof text - 1);
    close(fd);
    if (n <= 0 || metadata.st_size != n) return -1;
    text[n] = 0;
    if (sscanf(text, "RETIRING 1 %ju %ju%c", &device, &inode, &extra) != 3 || extra != '\n' || !inode) return -1;
    int count = snprintf(expected, sizeof expected, "RETIRING 1 %ju %ju\n", device, inode);
    if (count != n || memcmp(text, expected, (size_t)n) ||
        (uintmax_t)(dev_t)device != device || (uintmax_t)(ino_t)inode != inode) return -1;
    struct stat named;
    if (fstatat(directory, REPLACEMENT_RETIRING, &named, AT_SYMLINK_NOFOLLOW) ||
        named.st_dev != metadata.st_dev || named.st_ino != metadata.st_ino) return -1;
    memset(old, 0, sizeof *old); old->st_dev = (dev_t)device; old->st_ino = (ino_t)inode;
    if (receipt) *receipt = metadata;
    return 1;
}
static int record_retiring_inode(int directory, const struct stat *old) {
    struct stat recorded, receipt;
    int existing = retiring_inode(directory, &recorded, &receipt);
    if (existing < 0 || (existing && (recorded.st_dev != old->st_dev || recorded.st_ino != old->st_ino))) return -1;
    if (existing) {
        int fd = openat(directory, REPLACEMENT_RETIRING, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
        struct stat metadata;
        int failed = fd < 0 || fstat(fd, &metadata) || metadata.st_dev != receipt.st_dev ||
            metadata.st_ino != receipt.st_ino || fsync(fd);
        if (fd >= 0 && close(fd)) failed = 1;
        return failed || fsync(directory) ? -1 : 0;
    }
    int fd = openat(directory, REPLACEMENT_RETIRING, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    struct stat metadata;
    char text[128];
    int count = snprintf(text, sizeof text, "RETIRING 1 %ju %ju\n", (uintmax_t)old->st_dev, (uintmax_t)old->st_ino);
    int failed = fstat(fd, &metadata) || !S_ISREG(metadata.st_mode) || metadata.st_uid || metadata.st_gid ||
        metadata.st_nlink != 1 || (metadata.st_mode & 07777) != 0600 ||
        write(fd, text, (size_t)count) != count || fsync(fd);
    if (close(fd)) failed = 1;
    return failed || fsync(directory) ? -1 : 0;
}
static int drain_retiring_inode(int directory, const struct stat *old) {
    struct stat recorded, receipt, current;
    if (retiring_inode(directory, &recorded, &receipt) != 1 ||
        recorded.st_dev != old->st_dev || recorded.st_ino != old->st_ino) return -1;
    int result = -1;
    long long until = now_ms() + 6000;
    while (now_ms() < until) {
        int remaining = stale_processes(old);
        if (!remaining) { result = 0; break; }
        if (remaining < 0) break;
        usleep(20000);
    }
    if (!result && (retiring_inode(directory, &recorded, &current) != 1 ||
        current.st_dev != receipt.st_dev || current.st_ino != receipt.st_ino ||
        recorded.st_dev != old->st_dev || recorded.st_ino != old->st_ino ||
        unlinkat(directory, REPLACEMENT_RETIRING, 0) || fsync(directory))) result = -1;
    return result;
}
/* 1: already current; 0: safely retired; -1: refuse. RECOVER is supported by shipped daemons. */
static int replace_incumbent(const struct sockaddr_un *address, socklen_t length, int directory) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0), process = -1, result = -1;
    if (fd < 0) return -1;
    if (connect(fd, (const struct sockaddr *)address, length)) goto done;
    struct timeval timeout = { .tv_sec = 5 };
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
    struct ucred peer; socklen_t size = sizeof peer;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid || peer.pid <= 1) goto done;
    pid_t owner = peer.pid;
    char path[64], executable[PATH_MAX];
    snprintf(path, sizeof path, "/proc/%ld", (long)owner);
    process = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (process < 0) goto done;
    ssize_t n = readlinkat(process, "exe", executable, sizeof executable - 1);
    if (n < 0) goto done;
    executable[n] = 0;
    char canonical[PATH_MAX];
    if (snprintf(canonical, sizeof canonical, "%s/helper", ADB_RECOVERY_DIRECTORY) >= (int)sizeof canonical) goto done;
    size_t canonical_length = strlen(canonical);
    if (strncmp(executable, canonical, canonical_length) ||
        (executable[canonical_length] && strcmp(executable + canonical_length, " (deleted)"))) goto done;
    struct stat old, current; char state; unsigned long long started, checked;
    if (fstatat(process, "exe", &old, 0) || !S_ISREG(old.st_mode) || old.st_uid || old.st_gid ||
        (old.st_mode & 07777) != 0700 || old.st_nlink > 1 ||
        process_state(process, &state, &started) || state == 'Z') goto done;
    char response[160], build[65], extra; int managed, hold, security;
    read_deadline = now_ms() + 5000;
    if (write_all(fd, "STATUS\n", 7) || read_line(fd, response, sizeof response) ||
        sscanf(response, "READY 1 %64s %d %d %d %c", build, &managed, &hold, &security, &extra) != 4 ||
        !nonce_valid(build) || managed < 0 || managed > 1 || hold < 0 || hold > 1 || security < 0 || security > 1) goto done;
    if (!strcmp(build, helper_build_id())) { result = 1; goto done; }
    close(fd); fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0 || connect(fd, (const struct sockaddr *)address, length) ||
        getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid || peer.pid != owner) goto done;
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
    read_deadline = now_ms() + 5000;
    const char *recover = "RECOVER 0000000000000000000000000000000000000000000000000000000000000000\n";
    if (write_all(fd, recover, strlen(recover)) || read_line(fd, response, sizeof response) || strcmp(response, "OK")) goto done;
    char service[32];
    if (property("init.svc.adbd", service, sizeof service) || strcmp(service, "running") || tcp_listener(1) != 1) goto done;
    /* Older Android kernels lack pidfd. Recheck retained /proc identity and executable
     * immediately before signalling, as in the ordinary helper replacement discipline. */
    if (record_retiring_inode(directory, &old)) goto done;
    struct stat pinned, reached;
    if (fstat(process, &pinned) || stat(path, &reached) || pinned.st_dev != reached.st_dev || pinned.st_ino != reached.st_ino ||
        fstatat(process, "exe", &current, 0) || current.st_dev != old.st_dev || current.st_ino != old.st_ino ||
        process_state(process, &state, &checked) || checked != started || state == 'Z' || kill(owner, SIGTERM)) goto done;
    result = drain_retiring_inode(directory, &old);
 done:
    if (process >= 0) close(process);
    if (fd >= 0) close(fd);
    return result;
}
#define ENSURE_TOTAL_MS 25000
#define ENSURE_PROBE_MS 500
#define ENSURE_SOURCE_LIMIT (8u * 1024u * 1024u)
#define ENSURE_CHILD_LIMIT 2
static _Atomic unsigned ensure_children, ensure_sequence;

static int ensure_live(long long deadline) { long long now = now_ms(); return now >= 0 && now < deadline; }
static int ensure_metadata(const struct stat *st) {
    return S_ISREG(st->st_mode) && !st->st_uid && !st->st_gid &&
        (st->st_mode & 07777) == 0700 && st->st_nlink == 1;
}
static int ensure_same(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}
static int ensure_directory_current(int fd) {
    struct stat held, named;
    return !fstat(fd, &held) && !lstat(ADB_RECOVERY_DIRECTORY, &named) &&
        S_ISDIR(named.st_mode) && !named.st_uid && !named.st_gid &&
        (named.st_mode & 07777) == 0700 && ensure_same(&held, &named);
}
/* A superseded publication must never acquire or mutate incumbent custody. */
static int ensure_startup_current(int directory) {
    int fd = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    struct stat self, installed;
    int valid = fd >= 0 && ensure_directory_current(directory) && !fstat(fd, &self) &&
        ensure_metadata(&self) && !fstatat(directory, "helper", &installed, AT_SYMLINK_NOFOLLOW) &&
        ensure_metadata(&installed) && ensure_same(&self, &installed);
    if (fd >= 0) close(fd);
    return valid;
}
static int ensure_wait(int fd, short events, long long deadline) {
    for (;;) {
        long long now = now_ms();
        if (now < 0 || now >= deadline) return -1;
        struct pollfd ready = { .fd = fd, .events = events };
        int n = poll(&ready, 1, (int)(deadline - now));
        if (n < 0 && errno == EINTR) continue;
        return n > 0 && ensure_live(deadline) && !(ready.revents & POLLNVAL) ? 0 : -1;
    }
}
/* Ordinary connections use only their own absolute deadline, never read_deadline. */
static int ensure_status(long long deadline) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0), process = -1, result = 0;
    if (fd < 0 || !ensure_live(deadline)) goto done;
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(ADB_RECOVERY_SOCKET);
    if (length + 1 > sizeof address.sun_path) goto done;
    memcpy(address.sun_path + 1, ADB_RECOVERY_SOCKET, length);
    if (connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + length)) {
        if (errno != EINPROGRESS || ensure_wait(fd, POLLOUT, deadline)) goto done;
        int error; socklen_t size = sizeof error;
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) || error) goto done;
    }
    struct ucred peer; socklen_t size = sizeof peer;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid || peer.pid <= 1) goto done;
    char path[64], executable[PATH_MAX], canonical[PATH_MAX];
    snprintf(path, sizeof path, "/proc/%ld", (long)peer.pid);
    process = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (process < 0) goto done;
    ssize_t n = readlinkat(process, "exe", executable, sizeof executable - 1);
    if (n < 0 || n == (ssize_t)sizeof executable - 1) goto done;
    executable[n] = 0;
    int count = snprintf(canonical, sizeof canonical, "%s/helper", ADB_RECOVERY_DIRECTORY);
    if (count < 0 || count >= (int)sizeof canonical) goto done;
    if (strncmp(executable, canonical, (size_t)count) ||
        (executable[count] && strcmp(executable + count, " (deleted)"))) goto done;
    struct stat image, pinned, reached; char state; unsigned long long started, checked;
    if (fstatat(process, "exe", &image, 0) || !S_ISREG(image.st_mode) || image.st_uid || image.st_gid ||
        (image.st_mode & 07777) != 0700 || image.st_nlink > 1 ||
        fstat(process, &pinned) || process_state(process, &state, &started) || state == 'Z') goto done;
    const char request[] = "STATUS\n"; size_t sent = 0;
    while (sent < sizeof request - 1) {
        if (ensure_wait(fd, POLLOUT, deadline)) goto done;
        n = send(fd, request + sent, sizeof request - 1 - sent, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (n <= 0) goto done;
        sent += (size_t)n;
    }
    char response[160]; size_t used = 0;
    for (;;) {
        if (used + 1 >= sizeof response || ensure_wait(fd, POLLIN, deadline)) goto done;
        char ch; n = recv(fd, &ch, 1, MSG_DONTWAIT);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (n != 1) goto done;
        if (ch == '\n') { response[used] = 0; break; }
        if (ch < 32 || ch > 126) goto done;
        response[used++] = ch;
    }
    char build[65];
    if (used != 78 || memcmp(response, "READY 1 ", 8) || response[72] != ' ' ||
        response[74] != ' ' || response[76] != ' ' ||
        (response[73] != '0' && response[73] != '1') ||
        (response[75] != '0' && response[75] != '1') ||
        (response[77] != '0' && response[77] != '1')) goto done;
    memcpy(build, response + 8, 64); build[64] = 0;
    if (!nonce_valid(build) || strcmp(build, helper_build_id()) || stat(path, &reached) || !ensure_same(&pinned, &reached) ||
        process_state(process, &state, &checked) || state == 'Z' || checked != started ||
        fstatat(process, "exe", &reached, 0) || !ensure_same(&image, &reached) || !ensure_live(deadline)) goto done;
    result = 1;
 done:
    if (process >= 0) close(process);
    if (fd >= 0) close(fd);
    return result;
}

/* Publication owns only its exclusive stage. Canonical state is never rolled back on failure. */
static int ensure_publish(long long deadline) {
    int source = -1, parent = -1, directory = -1, writer = -1, pin = -1, result = -1, staged = 0;
    struct stat original, stage = {0}, st, before = {0}; int existed = 0;
    char parent_path[PATH_MAX], stage_name[80];
    size_t path_size = strlen(ADB_RECOVERY_DIRECTORY);
    if (path_size >= sizeof parent_path) goto done;
    memcpy(parent_path, ADB_RECOVERY_DIRECTORY, path_size + 1);
    char *leaf = strrchr(parent_path, '/');
    if (!leaf || leaf == parent_path || !leaf[1]) goto done;
    *leaf++ = 0;
    source = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    if (source < 0 || fstat(source, &original) || !S_ISREG(original.st_mode) || original.st_uid || original.st_gid ||
        !(original.st_mode & 0111) || original.st_size <= 0 || original.st_size > ENSURE_SOURCE_LIMIT) goto done;
    parent = open(parent_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0 || fstat(parent, &st) || st.st_uid || (st.st_mode & 0022)) goto done;
    if (mkdirat(parent, leaf, 0700) && errno != EEXIST) goto done;
    directory = openat(parent, leaf, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0 || !ensure_directory_current(directory)) goto done;
    long long lock_until = now_ms() + 2000;
    if (lock_until > deadline) lock_until = deadline;
    while (flock(directory, LOCK_EX | LOCK_NB)) {
        if ((errno != EWOULDBLOCK && errno != EINTR) || !ensure_live(lock_until)) goto done;
        usleep(20000);
    }
    if (!ensure_live(deadline) || !ensure_directory_current(directory) || fsync(directory) || fsync(parent)) goto done;
    if (!fstatat(directory, "helper", &before, AT_SYMLINK_NOFOLLOW)) { if (!ensure_metadata(&before)) goto done; existed = 1; }
    else if (errno != ENOENT) goto done;
    for (unsigned attempt = 0; attempt < 8; attempt++) {
        snprintf(stage_name, sizeof stage_name, ".ensure-%ld-%u", (long)getpid(), atomic_fetch_add(&ensure_sequence, 1));
        writer = openat(directory, stage_name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0700);
        if (writer >= 0) break;
        if (errno != EEXIST) goto done;
    }
    if (writer < 0 || fstat(writer, &stage)) goto done;
    staged = 1;
    if (fchown(writer, 0, 0) || fchmod(writer, 0700) || fstat(writer, &stage) || !ensure_metadata(&stage)) goto done;
    off_t copied = 0;
    while (copied < original.st_size) {
        char bytes[16384]; size_t amount = (size_t)(original.st_size - copied);
        if (amount > sizeof bytes) amount = sizeof bytes;
        if (!ensure_live(deadline)) goto done;
        ssize_t n = read(source, bytes, amount);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) goto done;
        size_t written = 0;
        while (written < (size_t)n) {
            if (!ensure_live(deadline)) goto done;
            ssize_t w = write(writer, bytes + written, (size_t)n - written);
            if (w < 0 && errno == EINTR) continue;
            if (w <= 0) goto done;
            written += (size_t)w;
        }
        copied += n;
    }
    char excess;
    if (read(source, &excess, 1) != 0 || fstat(source, &st) || !ensure_same(&original, &st) ||
        original.st_size != st.st_size || original.st_mtim.tv_sec != st.st_mtim.tv_sec ||
        original.st_mtim.tv_nsec != st.st_mtim.tv_nsec || original.st_ctim.tv_sec != st.st_ctim.tv_sec ||
        original.st_ctim.tv_nsec != st.st_ctim.tv_nsec || fsync(writer)) goto done;
    /* No writable alias may survive actual exec (ETXTBSY), even if its pin is read-only. */
    if (close(writer)) { writer = -1; goto done; }
    writer = -1;
    pin = openat(directory, stage_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (pin < 0 || fstat(pin, &st) || !ensure_metadata(&st) || !ensure_same(&stage, &st) || st.st_size != original.st_size) goto done;
    if (!ensure_live(deadline) || !ensure_directory_current(directory)) goto done;
    if (!fstatat(directory, "helper", &st, AT_SYMLINK_NOFOLLOW)) {
        if (!existed || !ensure_metadata(&st) || !ensure_same(&before, &st)) goto done;
    } else if (errno != ENOENT || existed) goto done;
    if (renameat(directory, stage_name, directory, "helper")) goto done;
    staged = 0;
    if (fsync(directory) || !ensure_directory_current(directory) ||
        fstatat(directory, "helper", &st, AT_SYMLINK_NOFOLLOW) || !ensure_metadata(&st) ||
        !ensure_same(&stage, &st) || !ensure_live(deadline)) goto done;
    result = pin; pin = -1;
 done:
    if (writer >= 0) close(writer);
    if (staged && directory >= 0 && !fstatat(directory, stage_name, &st, AT_SYMLINK_NOFOLLOW) && ensure_same(&stage, &st))
        (void)unlinkat(directory, stage_name, 0);
    if (pin >= 0) close(pin);
    if (source >= 0) close(source);
    if (directory >= 0) close(directory); /* Release publication flock before the child starts. */
    if (parent >= 0) close(parent);
    return result;
}

typedef struct { pthread_mutex_t mutex; pthread_cond_t ready; int assigned; pid_t pid; } ensure_reaper;
static void *ensure_reap(void *argument) {
    ensure_reaper *job = argument;
    pthread_mutex_lock(&job->mutex);
    while (!job->assigned) pthread_cond_wait(&job->ready, &job->mutex);
    pid_t pid = job->pid;
    pthread_mutex_unlock(&job->mutex);
    if (pid > 0) while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
    pthread_cond_destroy(&job->ready); pthread_mutex_destroy(&job->mutex); free(job);
    atomic_fetch_sub(&ensure_children, 1);
    return NULL;
}
static ensure_reaper *ensure_reserve(void) {
    if (atomic_fetch_add(&ensure_children, 1) >= ENSURE_CHILD_LIMIT) { atomic_fetch_sub(&ensure_children, 1); return NULL; }
    ensure_reaper *job = calloc(1, sizeof *job);
    if (!job) goto failed;
    if (pthread_mutex_init(&job->mutex, NULL)) goto free_job;
    if (pthread_cond_init(&job->ready, NULL)) goto destroy_mutex;
    pthread_attr_t attr;
    if (pthread_attr_init(&attr)) goto destroy_cond;
    pthread_t thread;
    int error = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (!error) error = pthread_create(&thread, &attr, ensure_reap, job);
    pthread_attr_destroy(&attr);
    if (!error) return job;
 destroy_cond: pthread_cond_destroy(&job->ready);
 destroy_mutex: pthread_mutex_destroy(&job->mutex);
 free_job: free(job);
 failed: atomic_fetch_sub(&ensure_children, 1); return NULL;
}
static void ensure_assign(ensure_reaper *job, pid_t pid) {
    pthread_mutex_lock(&job->mutex); job->pid = pid; job->assigned = 1;
    pthread_cond_signal(&job->ready); pthread_mutex_unlock(&job->mutex);
}
/* Existing descriptors may exceed a limit lowered before helper startup. New descriptors
 * opened concurrently cannot exceed the current limit; scan existing ones before fork. */
static int ensure_close_bound(long long deadline) {
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit) || limit.rlim_cur == RLIM_INFINITY ||
        limit.rlim_cur > 1048576) return -1;
    int bound = (int)limit.rlim_cur, valid = 1;
    DIR *fds = opendir("/proc/self/fd");
    if (!fds) return -1;
    size_t entries = 0;
    for (;;) {
        if (!ensure_live(deadline)) { valid = 0; break; }
        errno = 0;
        struct dirent *entry = readdir(fds);
        if (!entry) { if (errno) valid = 0; break; }
        if (++entries > 1048578) { valid = 0; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        unsigned fd = 0;
        for (const char *p = entry->d_name; *p; p++) {
            if (*p < '0' || *p > '9' || fd > (1048575u - (unsigned)(*p - '0')) / 10u) { valid = 0; break; }
            fd = fd * 10u + (unsigned)(*p - '0');
        }
        if (!valid) break;
        if (fd + 1 > (unsigned)bound) bound = (int)fd + 1;
    }
    if (closedir(fds)) valid = 0;
    return valid ? bound : -1;
}
static int ensure_spawn(int executable, ensure_reaper *job, long long deadline) {
    int errors[2] = {-1,-1}, null = -1, result = -1;
    int close_bound = ensure_close_bound(deadline);
    char path[64]; snprintf(path, sizeof path, "/proc/self/fd/%d", executable);
    char *const argv[] = { "hapaneld-helper", "--adb-recovery", NULL };
    char *const environment[] = { "PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LANG=C", "LC_ALL=C", NULL };
    if (executable < 3 || close_bound <= executable ||
        !ensure_live(deadline) || pipe2(errors, O_CLOEXEC | O_NONBLOCK)) goto cancel;
    null = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null < 3 || errors[0] < 3 || errors[1] < 3) goto cancel;
    pid_t child = fork();
    if (!child) {
        int failure = 0;
        if (setsid() < 0 || dup2(null, 0) < 0 || dup2(null, 1) < 0 || dup2(null, 2) < 0) failure = errno ? errno : EIO;
        if (!failure) {
            for (int fd = 3; fd < close_bound; fd++) if (fd != executable && fd != errors[1]) close(fd);
            execve(path, argv, environment); failure = errno ? errno : EIO;
        }
        const char *bytes = (const char *)&failure; size_t sent = 0;
        while (sent < sizeof failure) {
            ssize_t n = write(errors[1], bytes + sent, sizeof failure - sent);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            sent += (size_t)n;
        }
        _exit(127);
    }
    ensure_assign(job, child > 0 ? child : 0); job = NULL;
    close(errors[1]); errors[1] = -1;
    close(null); null = -1;
    if (child < 0) goto done;
    /* Exec EOF is only permission to probe readiness; it never implies READY. */
    for (;;) {
        if (ensure_wait(errors[0], POLLIN, deadline)) goto done;
        int failure; ssize_t n = read(errors[0], &failure, sizeof failure);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (!n && ensure_live(deadline)) result = 0;
        break;
    }
    goto done;
 cancel:
    ensure_assign(job, 0);
 done:
    if (null >= 0) close(null);
    if (errors[0] >= 0) close(errors[0]);
    if (errors[1] >= 0) close(errors[1]);
    return result;
}
int adb_recovery_ensure(void) {
    long long start = now_ms();
    if (geteuid() || start < 0) return 0;
    long long deadline = start + ENSURE_TOTAL_MS;
    if (ensure_status(start + ENSURE_PROBE_MS)) return 1;
    if (!ensure_live(deadline)) return 0;
    ensure_reaper *job = ensure_reserve();
    if (!job) return 0;
    int executable = ensure_publish(deadline);
    if (executable < 0) { ensure_assign(job, 0); return 0; }
    int spawned = ensure_spawn(executable, job, deadline);
    close(executable);
    if (spawned) return 0;
    while (ensure_live(deadline)) {
        long long until = now_ms() + ENSURE_PROBE_MS;
        if (until > deadline) until = deadline;
        if (ensure_status(until)) return 1;
        if (ensure_live(deadline)) usleep(20000);
    }
    return 0;
}

int adb_recovery_main(void) {
    if (geteuid()) return 1;
    signal(SIGPIPE, SIG_IGN);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t socket_length = strlen(ADB_RECOVERY_SOCKET);
    if (socket_length + 1 > sizeof address.sun_path) return 1;
    memcpy(address.sun_path + 1, ADB_RECOVERY_SOCKET, socket_length);
    socklen_t address_length = offsetof(struct sockaddr_un, sun_path) + 1 + socket_length;
    if (server < 0) return 1;
    int directory = replacement_directory();
    if (directory < 0 || !ensure_startup_current(directory)) { if (directory >= 0) close(directory); close(server); return 1; }
    struct stat retiring;
    int pending = retiring_inode(directory, &retiring, NULL);
    if (pending < 0) { close(directory); close(server); return 1; }
    if (pending) {
        /* A previous launcher may have died before signalling its owner, or while its
         * guardian drained. Resume either case before even trying an uncontended bind. */
        (void)replace_incumbent(&address, address_length, directory);
        pending = retiring_inode(directory, &retiring, NULL);
        if (pending < 0 || (pending && drain_retiring_inode(directory, &retiring))) {
            close(directory); close(server); return 1;
        }
    }
    if (bind(server, (struct sockaddr *)&address, address_length)) {
        if (errno != EADDRINUSE) { close(directory); close(server); return 1; }
        int replaced = replace_incumbent(&address, address_length, directory);
        if (replaced || bind(server, (struct sockaddr *)&address, address_length)) {
            close(directory); close(server); return replaced == 1 ? 0 : 1;
        }
    }
    /* Every launcher, including a currently free bind, retains custody through publication. */
    if (listen(server, 8)) { close(directory); close(server); return 1; }
    close(directory);
    lease = mmap(NULL, sizeof *lease, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (lease == MAP_FAILED) { close(server); return 1; }
    memset(lease, 0, sizeof *lease);
    pid_t guardian = start_guardian(server);
    if (guardian < 0) { close(server); return 1; }
    char nonce[65] = ""; long long renewed = 0;
    int managed = 0, hold = 0, recovery_pending = 0;
    long long retirement_after_ms = 0;
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
        int absent = helper_app_data_absent();
        if (!absent) retirement_after_ms = 0;
        if (managed && (now_ms() - renewed >= ADB_RECOVERY_LEASE_MS || absent)) recovery_pending = 1;
        if (recovery_pending && (!absent || now_ms() >= retirement_after_ms)) {
            if (!set_network(1)) { managed = 0; recovery_pending = 0; atomic_store(&lease->armed, 0); }
            else if (absent) retirement_after_ms = now_ms() + 1000;
        }
        if (absent && now_ms() >= retirement_after_ms && owner_actuator()) {
            retirement_after_ms = now_ms() + 1000;
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
