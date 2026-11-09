#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <dirent.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/file.h>
static const char *fixture_path(const char *name) {
    static char paths[4][512]; static unsigned next;
    char *path = paths[next++ % 4];
    snprintf(path, 512, "%s/%s", getenv("HAPANELD_ADB_FIXTURE"), name); return path;
}
/* Per-run fixture identity (the Makefile force-includes adb_recovery_paths.h). */
char fixture_app_data[512], fixture_app_user_data[512];
static char fixture_socket[64], fixture_main_socket[64];
static void *fixture_lease_region;
static void *fixture_mmap(void *address, size_t length, int protection, int flags, int file, off_t offset) {
    (void)address; (void)length; (void)protection; (void)flags; (void)file; (void)offset;
    return fixture_lease_region; /* shared with the test so it can hold the actuator */
}
/* Lease tests fork their owner in-process. Model its canonical self pin only here;
 * the real replacement driver executes and verifies the actual canonical ELF inode. */
static int fixture_custody, fixture_migrated, fixture_root_fd = -1, fixture_root_directory = -1;
static long fixture_magic = 0x63677270;
static void custody_receipt(void) {
    char path[700]; snprintf(path, sizeof path, "%s/custody-%ld", getenv("HAPANELD_ADB_FIXTURE"), (long)getpid());
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (fd < 0 || write(fd, fixture_migrated ? "root" : "service", fixture_migrated ? 4 : 7) < 0) abort();
    close(fd);
}
static int custody_proc(const char *path) {
    char text[512];
    const char *group = fixture_migrated || !strcmp(path, "/proc/1/cgroup") ? "/" :
        fixture_custody == 2 ? "/apps/uid_0/pid_42" : fixture_custody == 3 ? "/unknown" : "/uid_0/pid_42";
    if (fixture_custody == 4) snprintf(text, sizeof text, "4:memory:/\n2:cpuacct:/uid_0/pid_42\n");
    else snprintf(text, sizeof text, fixture_custody == 2 ? "4:memory:%s\n3:cpuset:/\n" : "4:memory:/\n0::%s\n", group);
    FILE *stream = tmpfile(); if (!stream) abort();
    if (fputs(text, stream) < 0 || fflush(stream) || fseek(stream, 0, SEEK_SET)) abort();
    int fd = dup(fileno(stream)); fclose(stream);
    if (!strcmp(path, "/proc/self/cgroup")) custody_receipt();
    return fd;
}
static int __attribute__((unused)) fixture_fstatfs(int fd, struct statfs *fs) {
    int result = fstatfs(fd, fs);
    if (!result && fixture_custody && fd == fixture_root_directory) fs->f_type = fixture_magic;
    return result;
}
static ssize_t fixture_write(int fd, const void *bytes, size_t count) {
    if (fixture_custody && fd == fixture_root_fd) {
        char expected[32]; int length = snprintf(expected, sizeof expected, "%ld\n", (long)getpid());
        if ((size_t)length != count || memcmp(expected, bytes, count)) abort();
        if (access(fixture_path("custody-write-fails"), F_OK) == 0) { errno = EPERM; return -1; }
        if (access(fixture_path("custody-no-effect"), F_OK)) fixture_migrated = 1;
        custody_receipt(); return (ssize_t)count;
    }
    return write(fd, bytes, count);
}
static int fixture_close(int fd) {
    if (fd == fixture_root_fd) fixture_root_fd = -1;
    if (fd == fixture_root_directory) fixture_root_directory = -1;
    return close(fd);
}
static pid_t fixture_fork(void) {
    pid_t child = fork();
    if (!child && fixture_custody) custody_receipt();
    return child;
}
static int fixture_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap); }
    if (fixture_custody && (!strcmp(path, "/proc/self/cgroup") || !strcmp(path, "/proc/1/cgroup"))) return custody_proc(path);
    if (fixture_custody && (!strcmp(path, "/sys/fs/cgroup") || !strcmp(path, "/dev/memcg"))) {
        fixture_root_directory = open(fixture_path("cgroup"), flags, mode); return fixture_root_directory;
    }
    return open(!strcmp(path, "/proc/self/exe") ? fixture_path("helper/helper") : path, flags, mode);
}
static int fixture_openat(int directory, const char *name, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list arguments; va_start(arguments, flags); mode = (mode_t)va_arg(arguments, int); va_end(arguments); }
    if ((flags & O_CREAT) && access(fixture_path("fence-write-fails"),F_OK)==0 &&
        (!strcmp(name,"network-adb-recovery.fence") || !strcmp(name,".remote-debug-security-authority.v1.publish"))) { errno=ENOSPC; return -1; }
    int fd = openat(directory,name,flags,mode);
    if (fixture_custody && directory == fixture_root_directory && !strcmp(name, "cgroup.procs")) fixture_root_fd = fd;
    return fd;
}
static int fixture_unlinkat(int directory, const char *name, int flags) {
    if(!strcmp(name,"remote-debug-security-authority.v1") && access(fixture_path("authority-readonly"),F_OK)==0) { errno=EROFS; return -1; }
    return unlinkat(directory,name,flags);
}
static int fixture_execve(const char *path, char *const arguments[], char *const environment[]) {
    extern char **environ;
    /* Only property fixtures need the host fixture directory. Shells use the real clean env. */
    return execve(path, arguments, !strcmp(path,"/proc/self/exe") ? environ : environment);
}
int __android_log_write(int priority, const char *tag, const char *text) {
    char line[1024];
    int length = snprintf(line, sizeof line, "%d %s %s\n", priority, tag, text);
    int fd = open(fixture_path("android.log"), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0 || length < 0 || (size_t)length >= sizeof line || write(fd, line, (size_t)length) != length) abort();
    close(fd); return 1;
}
#define close fixture_close
#define fstatfs fixture_fstatfs
#define write fixture_write
#define fork fixture_fork
#define open fixture_open
#define openat fixture_openat
#define unlinkat fixture_unlinkat
#define execve fixture_execve
#define mmap fixture_mmap
#define ADB_RECOVERY_GETPROP "/proc/self/exe"
#define ADB_RECOVERY_SETPROP "/proc/self/exe"
#define ADB_RECOVERY_SHELL "/bin/sh"
#define ADB_RECOVERY_TCP fixture_path("tcp")
#define ADB_RECOVERY_TCP6 fixture_path("tcp6")
#define ADB_RECOVERY_FENCE_PARENT fixture_path("no_backup")
#define ADB_RECOVERY_SOCKET fixture_socket
#define ADB_RECOVERY_DIRECTORY fixture_path("helper")
static unsigned fixture_lease_ms=600;
#define ADB_RECOVERY_LEASE_MS fixture_lease_ms
#define __ANDROID__ 1
#include "../src/adb_recovery.c"
#undef __ANDROID__
#undef close
#undef fstatfs
#undef write
#undef fork
#undef open
#undef openat
#undef unlinkat
#undef execve
#undef mmap
static int fixture_main_bind(int fd, const struct sockaddr *address, socklen_t length) {
    const char *path = getenv("HAPANELD_MAIN_FIXTURE_PATH");
    const struct sockaddr_un *unix_address = (const struct sockaddr_un *)address;
    if (path && *path && address->sa_family == AF_UNIX && !unix_address->sun_path[0] &&
        length == offsetof(struct sockaddr_un, sun_path) + 1 + strlen(fixture_main_socket) &&
        !memcmp(unix_address->sun_path + 1, fixture_main_socket, strlen(fixture_main_socket))) {
        struct sockaddr_un mapped = { .sun_family = AF_UNIX };
        if (strlen(path) >= sizeof mapped.sun_path) { errno = ENAMETOOLONG; return -1; }
        memcpy(mapped.sun_path, path, strlen(path) + 1);
        return bind(fd, (struct sockaddr *)&mapped, offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);
    }
    return bind(fd, address, length);
}
#define bind fixture_main_bind
#define SOCK_NAME fixture_main_socket
#define main lifecycle_fixture_main
#include "../src/main.c"
#undef main
#undef bind
static int failures;
#define CHECK(c,m) do { if (!(c)) { fprintf(stderr,"FAIL: %s\n",m); failures++; } } while (0)
static pid_t owner = -1, fixture_guardian = -1, socket_worker = -1;
static void put(const char *name, const char *value) {
    FILE *f = fopen(fixture_path(name), "w"); if (!f) abort(); fputs(value, f); fclose(f);
}
static char *get(const char *name) {
    static char text[1024]; FILE *f = fopen(fixture_path(name), "r"); text[0] = 0;
    if (f) { if (!fgets(text, sizeof text, f)) text[0] = 0; fclose(f); } return text;
}
static int connect_recovery(const char *socket_name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    socklen_t length = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(socket_name);
    const char *path = getenv("HAPANELD_MAIN_FIXTURE_PATH");
    if (path && *path && !strcmp(socket_name, fixture_main_socket)) {
        if (strlen(path) >= sizeof addr.sun_path) abort();
        memcpy(addr.sun_path, path, strlen(path) + 1);
        length = offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1;
    } else memcpy(addr.sun_path + 1, socket_name, strlen(socket_name));
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr, length)) { if (fd >= 0) close(fd); return -1; }
    struct timeval timeout = { .tv_sec = 4 };
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout); return fd;
}
static int connect_owner(void) { return connect_recovery(fixture_main_socket); }
static int request(const char *text, char *out, size_t size) {
    int fd = connect_owner(); if (fd < 0) return -1;
    if (write_all(fd,text,strlen(text))) { close(fd); return -1; }
    read_deadline=now_ms()+4000;
    int rc=read_line(fd,out,size); close(fd); return rc;
}
static int network_is(const char *state) { return !strcmp(get("init.svc.adbd"),state); }
static int wait_network(const char *state) {
    for(int i=0;i<150;i++){if(network_is(state))return 1;usleep(20000);} return 0;
}
static void stop(void) {
    if(owner>1){kill(owner,SIGKILL);waitpid(owner,NULL,0);owner=-1;}
    if(fixture_guardian>1){kill(fixture_guardian,SIGKILL);waitpid(fixture_guardian,NULL,0);fixture_guardian=-1;}
    if(socket_worker>1){kill(socket_worker,SIGKILL);waitpid(socket_worker,NULL,0);socket_worker=-1;}
    while(waitpid(-1,NULL,WNOHANG)>0){}
}
static void prepare(void) {
    unlink(fixture_path("android.log"));
    unlink(fixture_path("no_backup/network-adb-recovery.fence"));
    unlink(fixture_path("no_backup/.remote-debug-security-authority.v1.publish"));
    unlink(fixture_path("unfenced-reopen"));
    put("init.svc.adbd","running");put("persist.adb.tcp.port","");put("service.adb.tcp.port","5555");
    put("tcp","0: 00000000:15B3 00000000:0000 0A\n");put("tcp6","");
    if (mkdir(ADB_RECOVERY_DIRECTORY,0700) && errno != EEXIST) abort();
    int source = open("/proc/self/exe", O_RDONLY), target = open(fixture_path("helper/helper"), O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW,0700);
    if(source<0 || target<0)abort();
    char bytes[16384]; ssize_t n;
    while((n=read(source,bytes,sizeof bytes))>0){ ssize_t used=0; while(used<n){ssize_t w=write(target,bytes+used,(size_t)(n-used));if(w<=0)abort();used+=w;} }
    if(n<0 || close(source) || close(target))abort();
}
static void start(void) {
    prepare();
    owner=fork();if(!owner){
        int null = open("/dev/null", O_WRONLY);
        if (null < 0 || dup2(null, STDERR_FILENO) < 0) _exit(99);
        if (null != STDERR_FILENO) close(null);
        char *arguments[] = {"hapaneld-helper", NULL};
        _exit(lifecycle_fixture_main(1, arguments));
    }
    char response[160];int ready=0;
    /* Fixture readiness, not a product deadline: a loaded host can take over a second to copy and start it. */
    for(int i=0;i<500;i++){if(!request("STATUS\n",response,sizeof response)){ready=1;break;}usleep(10000);}
    CHECK(ready,"main worker socket starts with independent lifecycle recovery");
    int connected = connect_owner(); struct ucred peer; socklen_t peer_size = sizeof peer;
    if (connected >= 0 && !getsockopt(connected, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size))
        CHECK(peer.pid == owner, "main socket credentials identify the lifecycle owner that reserved the listener");
    if (connected >= 0) close(connected);
    char proc[128];snprintf(proc,sizeof proc,"/proc/%ld/task/%ld/children",(long)owner,(long)owner);
    FILE*f=fopen(proc,"r");long child=-1, worker_pid=-1;if(f){if (fscanf(f,"%ld %ld",&child,&worker_pid) != 2) worker_pid = -1;fclose(f);}fixture_guardian=(pid_t)child; socket_worker=(pid_t)worker_pid;
    CHECK(socket_worker > 1 && socket_worker != owner, "lifecycle owner has a separate main socket worker");
    CHECK(fixture_guardian>1,"recovery owner has an independent guardian");
}
static const char *fixture_nonce="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
static void arm(void) {
    char command[100],response[160];snprintf(command,sizeof command,"ARM %s\n",fixture_nonce);
    CHECK(!request(command,response,sizeof response)&&!strcmp(response,"OK"),"lease arms before runtime close");
    CHECK(network_is("stopped"),"managed lease closes adbd");
    CHECK(!strcmp(get("persist.adb.tcp.port"),"5555"),"closure preserves positive reboot fallback");
}
/* Observe lease deadlines, with margins for property subprocesses and host scheduling. */
static void wait_until(long long deadline) {
    while (now_ms() < deadline) usleep(5000);
}
static int line_count(const char *name) {
    FILE *input = fopen(fixture_path(name), "r");
    char line[1024]; int count = 0;
    while (input && fgets(line, sizeof line, input)) count++;
    if (input) fclose(input);
    return count;
}
static int reopen_log_count(void) { return line_count("android.log"); }
static void check_reopen_log(const char *expected_actor, pid_t expected_pid,
                             long long renewed_before, long long renewed_after,
                             long long reopened_after) {
    long long until = now_ms() + 500;
    while (!reopen_log_count() && now_ms() < until) usleep(5000);
    CHECK(reopen_log_count() == 1, "successful lease reopen emits exactly one Android warning with stderr discarded");
    char line[1024], build[65], boot[65], actor[16], extra;
    unsigned gap = 0, threshold = 0; long emitter = 0, recorded_owner = 0;
    long long renewed = 0, triggered = 0, reopened = 0, owner_started = 0;
    snprintf(line, sizeof line, "%s", get("android.log"));
    if (*line) printf("reopen diagnostic: %s", line);
    int fields = sscanf(line, "5 AdbLease adb_lease event=reopen cause=lease gap_ms=%u lease_ms=%u renewed_ms=%lld triggered_ms=%lld reopened_ms=%lld helper_build=%64s boot_id=%64s owner_pid=%ld owner_started_ms=%lld pid=%ld actor=%15s %c",
        &gap, &threshold, &renewed, &triggered, &reopened, build, boot, &recorded_owner, &owner_started, &emitter, actor, &extra);
    CHECK(fields == 11, "lease reopen diagnostic has the structured Android warning schema");
    if (fields != 11) return;
    CHECK(gap >= fixture_lease_ms && threshold == fixture_lease_ms && triggered - renewed == gap &&
          renewed >= renewed_before && renewed <= renewed_after && reopened >= triggered && reopened >= reopened_after &&
          reopened <= now_ms() && owner_started > 0 && owner_started <= renewed,
          "lease reopen diagnostic reports the triggering renewal gap and monotonic recovery time");
    char actual_boot[65] = ""; FILE *input = fopen("/proc/sys/kernel/random/boot_id", "r");
    if (input) { if (fscanf(input, "%64s", actual_boot) != 1) actual_boot[0] = 0; fclose(input); }
    int actor_matches = expected_actor ? emitter == expected_pid && !strcmp(actor, expected_actor) :
        (!strcmp(actor, "owner") && emitter == owner) || (!strcmp(actor, "guardian") && emitter == fixture_guardian);
    CHECK(!strcmp(build, HAPANELD_BUILD_ID) && !strcmp(boot, actual_boot) && recorded_owner == owner && actor_matches,
          "lease reopen diagnostic identifies the exact helper boot owner and recovery actor");
}
static void reopen_diagnostic_tests(void) {
    char command[100], response[160];
    long long arm_before, arm_after;
    start();
    CHECK(!kill(fixture_guardian, SIGSTOP), "diagnostic fixture isolates owner expiry from the guardian");
    arm(); wait_until(now_ms() + 350);
    long long beat_before = now_ms();
    snprintf(command, sizeof command, "BEAT %s\n", fixture_nonce);
    CHECK(!request(command, response, sizeof response) && !strcmp(response, "OK"), "diagnostic fixture renews the actual recovery lease");
    long long beat_after = now_ms();
    CHECK(wait_network("running"), "owner expiry successfully reopens for the diagnostic fixture");
    check_reopen_log("owner", owner, beat_before, beat_after, beat_after);
    CHECK(!kill(fixture_guardian, SIGCONT), "diagnostic fixture resumes the independent guardian");
    usleep(150000);
    CHECK(reopen_log_count() == 1, "owner and guardian do not duplicate one lease reopen diagnostic");
    arm(); CHECK(wait_network("running"), "a rearmed lease expires through the same recovery owner");
    long long second_until = now_ms() + 500;
    while (reopen_log_count() < 2 && now_ms() < second_until) usleep(5000);
    CHECK(reopen_log_count() == 2, "a later armed lease emits its own reopen diagnostic");
    stop();

    start(); CHECK(!kill(fixture_guardian, SIGSTOP), "diagnostic fixture defers guardian until owner starts reopening");
    arm_before = now_ms(); arm(); arm_after = now_ms();
    put("pause-restart", "yes"); unlink(fixture_path("restart.calls"));
    long long concurrent_until = now_ms() + 2000;
    while (line_count("restart.calls") < 1 && now_ms() < concurrent_until) usleep(5000);
    CHECK(line_count("restart.calls") == 1, "owner begins a lease-driven network reopen");
    CHECK(!kill(fixture_guardian, SIGCONT), "guardian resumes during the owner's network proof");
    concurrent_until = now_ms() + 1000;
    while (line_count("restart.calls") < 2 && now_ms() < concurrent_until) usleep(5000);
    CHECK(line_count("restart.calls") == 2, "owner and guardian both attempt the same lease reopen");
    unlink(fixture_path("pause-restart"));
    CHECK(wait_network("running"), "concurrent lease recovery successfully restores network ADB");
    usleep(150000);
    check_reopen_log(NULL, 0, arm_before, arm_after, arm_after);
    stop();

    start(); arm_before = now_ms(); arm(); arm_after = now_ms();
    put("loopback-only", "yes");
    int slow = connect_recovery(fixture_socket); CHECK(slow >= 0 && !write_all(slow, "S", 1), "diagnostic fixture blocks owner framing to force guardian expiry");
    wait_until(arm_after + 1000);
    CHECK(reopen_log_count() == 0, "unproved network reopen emits no success diagnostic");
    long long allowed_at = now_ms(); unlink(fixture_path("loopback-only"));
    long long deadline = now_ms() + 5000;
    while (!reopen_log_count() && now_ms() < deadline) usleep(10000);
    check_reopen_log("guardian", fixture_guardian, arm_before, arm_after, allowed_at);
    close(slow); usleep(150000);
    CHECK(reopen_log_count() == 1, "delayed guardian recovery and owner retry emit one success diagnostic");
    stop();

    start(); arm();
    snprintf(command, sizeof command, "RECOVER %s\n", fixture_nonce);
    CHECK(!request(command, response, sizeof response) && !strcmp(response, "OK"), "diagnostic fixture explicitly recovers before lease expiry");
    usleep(750000);
    CHECK(reopen_log_count() == 0, "explicit recovery is not reported as lease expiry");
    stop();

    start(); CHECK(!kill(fixture_guardian, SIGSTOP), "diagnostic fixture isolates app-removal recovery from guardian expiry");
    arm(); put("loopback-only", "yes"); unlink(fixture_path("restart.calls"));
    CHECK(!rmdir(APP_DATA) && !rmdir(APP_USER_DATA), "diagnostic fixture removes app data before lease expiry");
    deadline = now_ms() + 400;
    while (!line_count("restart.calls") && now_ms() < deadline) usleep(5000);
    CHECK(line_count("restart.calls") == 1, "app-removal recovery begins before lease expiry");
    wait_until(now_ms() + 750);
    CHECK(!mkdir(APP_DATA, 0700) && !mkdir(APP_USER_DATA, 0700), "diagnostic fixture restores app data during pending recovery");
    unlink(fixture_path("loopback-only"));
    CHECK(!kill(fixture_guardian, SIGCONT), "diagnostic fixture resumes guardian after nonlease recovery crossed expiry");
    deadline = now_ms() + 5000;
    while (strcmp(get("tcp"), "0: 00000000:15B3 00000000:0000 0A\n") && now_ms() < deadline) usleep(5000);
    CHECK(!strcmp(get("tcp"), "0: 00000000:15B3 00000000:0000 0A\n"), "pending app-removal recovery proves network ADB after the lease deadline");
    usleep(150000);
    CHECK(reopen_log_count() == 0, "app-removal recovery crossing expiry retains its initiating nonlease cause");
    stop();
}
static void heartbeat_tests(void) {
    const char *second = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    char command[100], response[160];
    start(); arm();
    snprintf(command, sizeof command, "ARM %s\n", second);
    CHECK(!request(command,response,sizeof response) && !strcmp(response,"OK"),
          "replacement session arms a fresh lease");
    long long armed_at = now_ms();
    wait_until(armed_at + 300);
    snprintf(command, sizeof command, "BEAT %s\n", fixture_nonce);
    CHECK(!request(command,response,sizeof response) && !strcmp(response,"ERR"),
          "superseded heartbeat is rejected");
    wait_until(armed_at + 850);
    CHECK(network_is("running"),"superseded heartbeat cannot renew the current lease");
    stop();

    start(); arm();
    snprintf(command, sizeof command, "ARM %s\n", second);
    CHECK(!request(command,response,sizeof response) && !strcmp(response,"OK"),
          "current heartbeat session arms");
    armed_at = now_ms();
    wait_until(armed_at + 350);
    snprintf(command, sizeof command, "BEAT %s\n", second);
    CHECK(!request(command,response,sizeof response) && !strcmp(response,"OK"),
          "current heartbeat is accepted");
    long long beat_at = now_ms();
    wait_until(armed_at + 750);
    CHECK(network_is("stopped"),"current heartbeat keeps ADB closed beyond the original lease");
    wait_until(beat_at + 850);
    CHECK(network_is("running"),"current heartbeat renewal eventually expires");
    stop();
}
static void absent_data_test(void) {
    fixture_lease_ms = 60000;
    start(); arm();
    long long removed_at = now_ms();
    CHECK(!rmdir(APP_DATA) && !rmdir(APP_USER_DATA),"controlled app data is removed");
    /* Two 100ms polls plus 300ms allowance for host scheduling/property subprocesses;
     * the 60-second lease makes this distinguish absence recovery from expiry. */
    while (!network_is("running") && now_ms() - removed_at < 500) usleep(5000);
    CHECK(network_is("running"),"absent app data promptly reopens ADB before lease expiry");
    stop();
    CHECK(!mkdir(APP_DATA,0700) && !mkdir(APP_USER_DATA,0700),"controlled app data is restored");
    fixture_lease_ms = 600;
}
/* Only the managed trigger can reopen here: the actuator is held, so the retirement block
 * cannot act, and the 60-second lease keeps the fixture_guardian's expiry path idle. */
static void managed_absent_trigger_test(void) {
    recovery_lease *shared = fixture_lease_region;
    fixture_lease_ms = 60000;
    start(); arm();
    int held = 0;
    for (int i = 0; i < 100 && !held; i++) { int expected = 0; held = atomic_compare_exchange_strong(&shared->actuator, &expected, 2); if (!held) usleep(5000); }
    CHECK(held,"test holds the recovery actuator");
    long long removed_at = now_ms();
    CHECK(!rmdir(APP_DATA) && !rmdir(APP_USER_DATA),"controlled app data is removed under a held actuator");
    while (!network_is("running") && now_ms() - removed_at < 500) usleep(5000);
    CHECK(network_is("running"),"managed lease reopens ADB on absent app data while the actuator is held");
    CHECK(atomic_load(&shared->actuator) == 2,"retirement path did not take the held actuator");
    CHECK(!mkdir(APP_DATA,0700) && !mkdir(APP_USER_DATA,0700),"controlled app data is restored after held-actuator case");
    atomic_store(&shared->actuator, 0);
    stop();
    fixture_lease_ms = 600;
}
static void unauthorized_test(void) {
    for (int alias = 0; alias < 2; alias++) {
    pid_t stranger = fork();
    if (!stranger) {
        if (setuid(65534)) _exit(99);
        int fd = connect_recovery(alias ? fixture_socket : fixture_main_socket);
        if (fd < 0) _exit(98);
        (void)send(fd, "STATUS\n", 7, MSG_NOSIGNAL);
        char byte; int count = (int)read(fd, &byte, 1); close(fd);
        _exit(count <= 0 ? 0 : 97);
    }
    int status; while(waitpid(stranger,&status,0)<0&&errno==EINTR){}
    CHECK(WIFEXITED(status) && WEXITSTATUS(status)==0,"unrelated local UID cannot access administrator socket");
    }
}
static pid_t command_worker(void) {
    char proc[128]; snprintf(proc,sizeof proc,"/proc/%ld/task/%ld/children",(long)owner,(long)owner);
    FILE *input=fopen(proc,"r"); if(!input)return -1;
    long child, result=-1;
    while(fscanf(input,"%ld",&child)==1)if(child!=(long)fixture_guardian && child!=(long)socket_worker)result=child;
    fclose(input);return (pid_t)result;
}
static void worker_death_test(void) {
    usleep(200000);
    arm();
    int fd=connect_owner();const char *program="sleep 30";char header[100];
    snprintf(header,sizeof header,"SHELL 30000 %zu\n",strlen(program));write_all(fd,header,strlen(header));write_all(fd,program,strlen(program));
    pid_t worker=-1;
    for(int i=0;i<20;i++){worker=command_worker();if(worker>1)break;usleep(5000);}
    CHECK(worker>1,"shell has a supervised execution worker");
    if(worker>1)kill(worker,SIGKILL);
    long long until=now_ms()+400;
    while(!network_is("running")&&now_ms()<until)usleep(10000);
    CHECK(network_is("running"),"execution worker death reopens before lease expiry");close(fd);
}
static void occupied_main_test(void) {
    int listener = open_main_listener(0);
    CHECK(listener >= 0, "occupied-main fixture reserves an incompatible main listener");
    unlink(fixture_path("restart.calls"));
    put("init.svc.adbd", "stopped");
    pid_t candidate = fork();
    if (!candidate) {
        char *arguments[] = {"hapaneld-helper", NULL};
        _exit(lifecycle_fixture_main(1, arguments));
    }
    int status = 0; pid_t reaped = 0;
    for (int i = 0; i < 100 && !reaped; i++) { reaped = waitpid(candidate, &status, WNOHANG); if (!reaped) usleep(10000); }
    CHECK(reaped == candidate && WIFEXITED(status) && WEXITSTATUS(status) == 1,
          "occupied main refuses startup instead of looping or replacing its owner");
    CHECK(network_is("stopped") && access(fixture_path("restart.calls"), F_OK),
          "occupied main refusal cannot mutate the network recovery route");
    int alias = connect_recovery(fixture_socket);
    CHECK(alias < 0, "occupied main refusal cannot publish a second recovery owner");
    if (alias >= 0) close(alias);
    if (!reaped) { kill(candidate, SIGKILL); waitpid(candidate, NULL, 0); }
    if (listener >= 0) close(listener);
}
static void main_worker_loss_test(void) {
    fixture_lease_ms = 60000;
    start(); arm();
    put("pause-restart", "yes"); unlink(fixture_path("restart.calls"));
    CHECK(!kill(socket_worker, SIGKILL), "main worker loss fixture kills only the socket worker");
    long long until = now_ms() + 1500;
    while (!line_count("restart.calls") && now_ms() < until) usleep(5000);
    CHECK(line_count("restart.calls") == 1, "main worker loss starts independent network recovery");
    int pending = connect_owner();
    if (pending >= 0) (void)write_all(pending, "PING\n", 5);
    struct pollfd ready = { .fd = pending, .events = POLLIN };
    CHECK(pending >= 0 && poll(&ready, 1, 100) == 0,
          "main worker cannot restart before network recovery is proved");
    unlink(fixture_path("pause-restart"));
    CHECK(wait_network("running"), "main worker loss restores the surviving network route");
    read_deadline = now_ms() + 4000; char response[160];
    CHECK(pending >= 0 && !read_line(pending, response, sizeof response) && !strcmp(response, "OK"),
          "main worker restarts after recovery proof and serves ordinary commands");
    if (pending >= 0) close(pending);
    socket_worker = command_worker();
    CHECK(socket_worker > 1, "main worker successor remains owned by the same lifecycle parent");
    CHECK(!request("STATUS\n", response, sizeof response) && strstr(response, " 0 0 "),
          "main worker restart leaves effective management disarmed");
    stop(); fixture_lease_ms = 600;
}
static void ipc_peer_test(void) {
    char name[80]; snprintf(name, sizeof name, "hapaneld-ipc-auth-%ld", (long)getpid());
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    memcpy(address.sun_path + 1, name, strlen(name));
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name)) || listen(listener, 1)) abort();
    pid_t stranger = fork();
    if (!stranger) {
        close(listener);
        if (setuid(65534)) _exit(99);
        int connection = connect_recovery(name);
        if (connection < 0) _exit(98);
        (void)write_all(connection, "STATUS\n", 7);
        char response; int count = (int)recv(connection, &response, 1, 0);
        close(connection); _exit(count <= 0 ? 0 : 97);
    }
    int client = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    if (client < 0 || owner_channel_open()) abort();
    char header[7]; if (recv(client, header, sizeof header, MSG_WAITALL) != sizeof header) abort();
    lease = fixture_lease_region; memset(lease, 0, sizeof *lease);
    conn_ctx fake = { .fd = client, .caller = HELPER_CALLER_ROOT, .request = "STATUS", .request_len = 6,
        .request_terminator = '\n', .request_started_ms = now_ms() };
    cmd_adb_recovery(&fake, "");
    CHECK(fake.terminal, "private handoff consumes the connection even with forged caller metadata");
    close(client); owner_receive();
    close(listener); close(owner_channel); close(worker_channel); owner_channel = worker_channel = -1; lease = NULL;
    int status; while (waitpid(stranger, &status, 0) < 0 && errno == EINTR) {}
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0,
          "owner rechecks actual passed peer credentials despite forged root caller metadata");
}
static void combined_frame_test(void) {
    int fd = connect_owner(); char frame[512], response[160], bytes[7] = {0};
    const char *program = "printf prefix";
    int size = snprintf(frame, sizeof frame, "SHELL 1000 %zu\n%sPING\n", strlen(program), program);
    CHECK(fd >= 0 && send(fd, frame, (size_t)size, MSG_NOSIGNAL) == size,
          "combined main frame submits header body and trailing command together");
    read_deadline = now_ms() + 4000;
    CHECK(!read_line(fd, response, sizeof response) && !strcmp(response, "RESULT 0 0 0 6") &&
          !exact_read(fd, bytes, 6) && !strcmp(bytes, "prefix"),
          "combined main frame preserves every already-read shell body byte");
    char tail; CHECK(recv(fd, &tail, 1, 0) <= 0, "terminal main shell payload cannot redispatch trailing PING");
    close(fd);
    static const unsigned char nul[] = "STATUS\0hidden\nPING\n";
    static const unsigned char high[] = "STATUS\177\nPING\n";
    static const unsigned char cr[] = "STATUS\r\nPING\n";
    unsigned char huge[166]; memset(huge, ' ', sizeof huge); memcpy(huge, "STATUS", 6);
    memcpy(huge + 160, "\nPING\n", 6);
    const unsigned char *cases[] = {nul, high, cr, huge};
    size_t sizes[] = {sizeof nul - 1, sizeof high - 1, sizeof cr - 1, sizeof huge};
    for (size_t i = 0; i < sizeof sizes / sizeof *sizes; i++) {
        fd = connect_owner(); CHECK(fd >= 0, "malformed raw main framing connects");
        if (fd < 0) continue;
        (void)send(fd, cases[i], sizes[i], MSG_NOSIGNAL); (void)shutdown(fd, SHUT_WR);
        ssize_t received = recv(fd, response, sizeof response - 1, 0);
        int rejected = received <= 0;
        if (received > 0) {
            response[received] = 0;
            char trailing;
            rejected = !strncmp(response, "ERR", 3) && !strstr(response, "\nOK") && recv(fd, &trailing, 1, 0) <= 0;
        }
        CHECK(rejected, "malformed raw recovery header closes without executing following PING");
        close(fd);
    }
}
static void guardian_drain_exec_test(void) {
    pid_t child = fork();
    if (!child) {
        if (adb_recovery_owner_init()) _exit(91);
        pid_t previous = guardian; long long until = now_ms() + 3000;
        int result;
        do { result = adb_recovery_owner_drain(); if (result) adb_recovery_owner_poll(10); } while (result && now_ms() < until);
        if (result || previous <= 1 || kill(previous, 0) == 0) _exit(92);
        for (int i = 0; i < 4; i++) adb_recovery_owner_poll(10);
        if (guardian > 0) _exit(93);
        int channel[2]; if (socketpair(AF_UNIX, SOCK_STREAM, 0, channel)) _exit(94);
        char arm_line[80]; snprintf(arm_line, sizeof arm_line, "ARM %s", fixture_nonce);
        owner_request(channel[0], arm_line, NULL, 0);
        char reply[8] = {0}; if (read(channel[1], reply, sizeof reply) != 4 || strcmp(reply, "ERR\n")) _exit(95);
        close(channel[1]);
        char previous_pid[40]; snprintf(previous_pid, sizeof previous_pid, "%ld", (long)previous);
        char *arguments[] = {"adb_recovery_test", "--drain-exec", previous_pid, NULL};
        execv("/proc/self/exe", arguments); _exit(96);
    }
    int status = 0; while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0,
          "guardian drain reaps guardian suppresses restart rejects ARM and permits same-PID exec");
}
static int wait_tracked_child(pid_t *child, int milliseconds) {
    if (*child <= 1) return 0;
    long long deadline = now_ms() + milliseconds;
    for (;;) {
        pid_t done = waitpid(*child, NULL, WNOHANG);
        if (done == *child) { *child = -1; return 1; }
        if (done < 0 && errno == ECHILD) { *child = -1; return 0; }
        if (done < 0 && errno != EINTR) return 0;
        if (now_ms() >= deadline) return 0;
        usleep(10000);
    }
}
static void successor_guardian_test(void) {
    int client = -1, reaped;
    fixture_lease_ms = 60000; start(); arm();
    CHECK(!adb_recovery_legacy_main(fixture_main_socket),
          "merged incumbent cannot authorize the generic legacy process sweep");
    put("pause-restart", "yes"); unlink(fixture_path("restart.calls"));
    kill(owner, SIGKILL);
    reaped = wait_tracked_child(&owner, 2000);
    CHECK(reaped, "killed lifecycle owner exits before successor admission");
    if (!reaped) goto cleanup;
    reaped = wait_tracked_child(&socket_worker, 2000);
    CHECK(reaped, "socket worker exits after lifecycle owner death");
    if (!reaped) goto cleanup;
    long long until = now_ms() + 2000;
    while (!line_count("restart.calls") && now_ms() < until) usleep(5000);
    CHECK(line_count("restart.calls") == 1, "orphan guardian begins recovery before successor admission");
    owner = fork();
    if (!owner) { char *arguments[] = {"hapaneld-helper", NULL}; _exit(lifecycle_fixture_main(1, arguments)); }
    usleep(150000);
    client = connect_owner(); if (client >= 0) (void)write_all(client, "ARM aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n", 69);
    struct pollfd ready = {.fd = client, .events = POLLIN};
    CHECK(client >= 0 && poll(&ready, 1, 150) == 0 && network_is("stopped"),
          "successor cannot admit ARM while orphan guardian recovery is unproved");
    unlink(fixture_path("pause-restart"));
    reaped = wait_tracked_child(&fixture_guardian, 4000);
    CHECK(reaped, "orphan guardian exits after reopening before successor admission");
    if (!reaped) goto cleanup;
    char reply[160]; read_deadline = now_ms() + 4000;
    CHECK(client >= 0 && !read_line(client, reply, sizeof reply) && !strcmp(reply, "OK"),
          "successor admits queued ARM only after orphan guardian finishes recovery");
    if (client >= 0) { close(client); client = -1; }
    CHECK(!request("RECOVER aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n", reply, sizeof reply) && !strcmp(reply, "OK") && network_is("running"),
          "successor retains a working independent recovery route");
    int ready_main = 0;
    for (int i = 0; i < 100; i++) { if (!request("STATUS\n", reply, sizeof reply) && !strncmp(reply, "READY 1 ", 8)) { ready_main = 1; break; } usleep(10000); }
    CHECK(ready_main, "successor gains recovery custody after orphan guardian exits");
    char proc[128]; snprintf(proc, sizeof proc, "/proc/%ld/task/%ld/children", (long)owner, (long)owner);
    FILE *file = fopen(proc, "r"); long first = -1, second = -1;
    if (file) { if (fscanf(file, "%ld %ld", &first, &second) != 2) second = -1; fclose(file); }
    fixture_guardian = (pid_t)first; socket_worker = (pid_t)second;
 cleanup:
    if (client >= 0) close(client);
    unlink(fixture_path("pause-restart"));
    stop(); fixture_lease_ms = 600;
}
static void shell_tests(void) {
    int fd=connect_owner();const char*program="printf out; printf err >&2; exit 7";char header[100];
    snprintf(header,sizeof header,"SHELL 1000 %zu\n",strlen(program));write_all(fd,header,strlen(header));write_all(fd,program,strlen(program));
    read_deadline=now_ms()+4000;char reply[160];CHECK(!read_line(fd,reply,sizeof reply)&&!strcmp(reply,"RESULT 7 0 0 6"),"shell returns exact nonzero status and byte count");
    char bytes[7]={0};CHECK(!exact_read(fd,bytes,6)&&!strcmp(bytes,"outerr"),"shell merges stdout and stderr without text conversion");close(fd);
    fd=connect_owner();program="while :; do printf 0123456789; done";snprintf(header,sizeof header,"SHELL 100 %zu\n",strlen(program));
    write_all(fd,header,strlen(header));write_all(fd,program,strlen(program));read_deadline=now_ms()+4000;
    CHECK(!read_line(fd,reply,sizeof reply)&&!strcmp(reply,"RESULT -1 1 1 32768"),"endless output remains bounded and times out");close(fd);
    fd=connect_owner();program="sleep 30 & wait";snprintf(header,sizeof header,"SHELL 100 %zu\n",strlen(program));write_all(fd,header,strlen(header));write_all(fd,program,strlen(program));read_deadline=now_ms()+4000;
    CHECK(!read_line(fd,reply,sizeof reply)&&!strcmp(reply,"RESULT -1 1 0 0"),"timeout terminates the whole shell process group");close(fd);
    fd=connect_owner();
    program="if [ \"$ANDROID_ROOT\" != /system ] || [ \"$ANDROID_DATA\" != /data ] || [ \"${CLASSPATH-unset}\" != unset ]; then exit 134; fi; printf package:/system/framework/framework-res.apk";
    snprintf(header,sizeof header,"SHELL 1000 %zu\n",strlen(program));write_all(fd,header,strlen(header));write_all(fd,program,strlen(program));read_deadline=now_ms()+4000;
    int read_result=read_line(fd,reply,sizeof reply);
    if(read_result || strcmp(reply,"RESULT 0 0 0 43"))fprintf(stderr,"platform environment result: %s\n",read_result ? "unreadable" : reply);
    CHECK(!read_result&&!strcmp(reply,"RESULT 0 0 0 43"),"platform command receives fixed Android roots without inherited classpath");
    char package[44]={0};CHECK(!exact_read(fd,package,43)&&!strcmp(package,"package:/system/framework/framework-res.apk"),"platform package response survives the production shell environment");close(fd);
}
/* Adversarial request framing against the real owner. Every reply must be one of the protocol's
 * fixed answers (or a close), malformed SHELL framing must never execute its program, and the owner
 * must still answer afterwards. A well-formed request with the same program is the positive control
 * that proves the sentinel check can go red. */
static int allowed_reply(const char *reply) {
    return !strcmp(reply,"ERR") || !strcmp(reply,"OK") || !strcmp(reply,"HELD") || !strcmp(reply,"BUSY") ||
        !strncmp(reply,"READY 1 ",8) || !strncmp(reply,"RESULT ",7);
}
static void send_framed_reply(const char *header, const char *payload, size_t payload_size, int *bad_reply, char *reply, size_t reply_size) {
    reply[0]=0;
    int fd=connect_owner(); if(fd<0){*bad_reply=1;return;}
    (void)send(fd,header,strlen(header),MSG_NOSIGNAL);
    if(payload_size)(void)send(fd,payload,payload_size,MSG_NOSIGNAL);
    shutdown(fd,SHUT_WR);
    read_deadline=now_ms()+3000;
    if(read_line(fd,reply,reply_size))reply[0]=0;
    else if(!allowed_reply(reply)){fprintf(stderr,"unexpected reply '%s' to '%.60s'\n",reply,header);*bad_reply=1;}
    close(fd);
}
static void send_framed(const char *header, const char *payload, size_t payload_size, int *bad_reply) {
    char reply[160]; send_framed_reply(header,payload,payload_size,bad_reply,reply,sizeof reply);
}
static void wait_executors_idle(void) {
    for(int i=0;i<300 && command_worker()>1;i++)usleep(10000);
}
static void malformed_request_tests(void) {
    char sentinel[600],program[700],header[200];int bad_reply=0;
    snprintf(sentinel,sizeof sentinel,"%s",fixture_path("malformed-executed"));
    snprintf(program,sizeof program,"touch '%s'",sentinel);
    size_t n=strlen(program);
    char *with_nul=malloc(n+2); if(!with_nul)abort(); memcpy(with_nul,program,n); with_nul[n]=0; with_nul[n+1]='x';
    /* expect: "ERR" for a refused header, "" for a close with no reply (framing or payload refused). */
    struct { const char *format; size_t declared; size_t sent; const char *payload; const char *expect; } cases[]={
        {"SHELL 0 %zu\n",n,n,program,"ERR"}, {"SHELL 180001 %zu\n",n,n,program,"ERR"},
        {"SHELL 1000 0\n",0,n,program,"ERR"}, {"SHELL 1000 65537\n",0,n,program,"ERR"},
        {"SHELL 1000 %zu x\n",n,n,program,"ERR"}, {"SHELL 1000\n",0,n,program,"ERR"},
        {"shell 1000 %zu\n",n,n,program,"ERR"}, {"SHELLX 1000 %zu\n",n,n,program,"ERR"},
        {"SHELL 1000 %zu\n",n,n-1,program,""}, {"SHELL 1000 %zu\n",n+2,n+2,with_nul,""},
        {"SHELL 1000 %zu\r\n",n,n,program,""}, {"SHELL\t1000\t%zu\x01\n",n,n,program,""},
    };
    unlink(sentinel);
    for(size_t i=0;i<sizeof cases/sizeof cases[0];i++){
        snprintf(header,sizeof header,cases[i].format,cases[i].declared);
        char reply[160];
        send_framed_reply(header,cases[i].payload,cases[i].sent,&bad_reply,reply,sizeof reply);
        wait_executors_idle();
        if(strcmp(reply,cases[i].expect)){fprintf(stderr,"malformed case %zu replied '%s', want '%s'\n",i,reply,cases[i].expect);
            CHECK(0,"malformed SHELL framing is refused with the fixed reply");}
        if(access(sentinel,F_OK)==0){fprintf(stderr,"malformed case %zu executed: %.60s",i,header);unlink(sentinel);
            CHECK(0,"malformed SHELL framing never executes its program");}
    }
    /* Overlong and control-byte lines are closed without a reply. */
    char overlong[400];memset(overlong,'S',sizeof overlong-2);overlong[sizeof overlong-2]='\n';overlong[sizeof overlong-1]=0;
    send_framed(overlong,NULL,0,&bad_reply);
    /* Deterministic pseudo-random lines over the protocol's own alphabet. */
    static const char alphabet[]="SHELLARMBEATRECOVERSTATUS 0123456789abcdef\t-+x";
    unsigned seed=0x5eed1234u;
    for(int i=0;i<300;i++){
        char line[200];size_t length=(seed=seed*1103515245u+12345u)%170;
        for(size_t k=0;k<length;k++){seed=seed*1103515245u+12345u;line[k]=alphabet[(seed>>16)%(sizeof alphabet-1)];}
        line[length]='\n';line[length+1]=0;
        send_framed(line,NULL,0,&bad_reply);
    }
    wait_executors_idle();
    CHECK(!bad_reply,"adversarial framing receives only fixed protocol replies");
    CHECK(access(sentinel,F_OK)!=0,"adversarial framing never executes a program");
    char reply[160];
    CHECK(!request("STATUS\n",reply,sizeof reply)&&!strncmp(reply,"READY 1 ",8),"owner still answers after adversarial framing");
    CHECK(network_is("running"),"adversarial framing leaves the unarmed network route open");
    snprintf(header,sizeof header,"SHELL 1000 %zu\n",n);
    send_framed(header,program,n,&bad_reply);wait_executors_idle();
    CHECK(access(sentinel,F_OK)==0,"positive control: well-formed SHELL executes the same program");
    unlink(sentinel);free(with_nul);
}
static void clear_custody_receipts(void) {
    DIR *directory = opendir(getenv("HAPANELD_ADB_FIXTURE")); struct dirent *entry;
    while (directory && (entry = readdir(directory))) {
        if (!strncmp(entry->d_name, "custody-", 8)) unlink(fixture_path(entry->d_name));
    }
    if (directory) closedir(directory);
}
static void custody_tests(void) {
    CHECK(!mkdir(fixture_path("cgroup"), 0700), "custody fixture creates its root");
    put("cgroup/cgroup.procs", "1\n");
    CHECK(!chmod(fixture_path("cgroup/cgroup.procs"), 0600), "custody fixture root control is private");
    for (int hierarchy = 1; hierarchy <= 2; hierarchy++) {
        fixture_custody = hierarchy; fixture_migrated = 0;
        fixture_magic = hierarchy == 1 ? 0x63677270 : 0x27e0eb;
        pid_t service = fork();
        if (!service) { custody_receipt(); for (;;) pause(); }
        usleep(20000);
        start();
        char name[80]; snprintf(name, sizeof name, "custody-%ld", (long)owner);
        CHECK(!strcmp(get(name), "root"), "recovery owner leaves the service cgroup before readiness");
        snprintf(name, sizeof name, "custody-%ld", (long)fixture_guardian);
        CHECK(!strcmp(get(name), "root"), "guardian inherits independent recovery custody");
        arm();
        /* SIGKILL is delivered asynchronously: a loaded host may not have reaped the service the
         * instant ARM replies. Nothing else signals it, so a bounded wait still proves ARM killed it. */
        int status = 0; pid_t killed = 0;
        for (int waited = 0; waited < 200 && (killed = waitpid(service, &status, WNOHANG)) == 0; waited++) usleep(10000);
        CHECK(killed == service && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL,
              "ARM really terminates the modeled adbd service cgroup");
        CHECK(!kill(owner, 0) && !kill(fixture_guardian, 0), "owner and guardian survive adbd service termination");
        char reply[160];
        CHECK(!request("STATUS\n", reply, sizeof reply), "recovery remains ready after adbd service termination");
        if (!killed) { kill(service, SIGKILL); waitpid(service, NULL, 0); }
        stop(); clear_custody_receipts();
    }
    fixture_custody = 1; fixture_migrated = 1; fixture_magic = 0;
    start(); /* Already independent custody needs no filesystem mutation. */
    CHECK(!kill(owner, 0) && !kill(fixture_guardian, 0), "existing root custody is reused without migration");
    pid_t incumbent = owner;
    for (int fault = 0; fault < 6; fault++) {
        fixture_migrated = 0; fixture_magic = fault == 0 ? 0 : 0x63677270;
        fixture_custody = fault == 4 ? 3 : fault == 5 ? 4 : 1;
        if (fault == 1) put("cgroup/cgroup.procs", "42\n");
        if (fault == 2) put("custody-write-fails", "yes");
        if (fault == 3) put("custody-no-effect", "yes");
        pid_t refused = fork(); if (!refused) _exit(adb_recovery_main());
        int status; waitpid(refused, &status, 0);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 1, "unproved recovery custody refuses startup");
        char reply[160];
        CHECK(owner == incumbent && !kill(incumbent, 0) && !request("STATUS\n", reply, sizeof reply),
              "custody refusal preserves the matching incumbent and its socket");
        /* Also isolate admission from incumbent checks: an unsafe fresh candidate
         * must fail before it can bind a socket, even if no old owner exists. */
        stop();
        refused = fork(); if (!refused) _exit(adb_recovery_main());
        pid_t exited = 0;
        for (int i = 0; i < 100 && !exited; i++) { exited = waitpid(refused, &status, WNOHANG); if (!exited) usleep(10000); }
        CHECK(exited == refused && WIFEXITED(status) && WEXITSTATUS(status) == 1,
              "unproved fresh recovery custody refuses startup");
        if (!exited) {
            char path[128]; snprintf(path, sizeof path, "/proc/%ld/task/%ld/children", (long)refused, (long)refused);
            FILE *children = fopen(path, "r"); long child;
            if (children) { while (fscanf(children, "%ld", &child) == 1) if (child > 1) kill((pid_t)child, SIGKILL); fclose(children); }
            kill(refused, SIGKILL); waitpid(refused, NULL, 0);
        }
        put("cgroup/cgroup.procs", "1\n"); unlink(fixture_path("custody-write-fails")); unlink(fixture_path("custody-no-effect"));
        fixture_migrated = 1; fixture_custody = 1; fixture_magic = 0; start(); incumbent = owner;
    }
    stop(); clear_custody_receipts(); fixture_custody = fixture_migrated = 0;
    unlink(fixture_path("cgroup/cgroup.procs")); rmdir(fixture_path("cgroup"));
}

/* Recovery custody retry: a lifecycle owner whose first init failed keeps serving and
 * acquires custody once the obstacle clears, without killing a healthy same-build owner. */
static pid_t launch_logged_lifecycle(void) {
    unlink(fixture_path("lifecycle.err"));
    pid_t child = fork();
    if (!child) {
        int log = open(fixture_path("lifecycle.err"), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (log < 0 || dup2(log, STDERR_FILENO) < 0) _exit(99);
        char *arguments[] = {"hapaneld-helper", NULL};
        _exit(lifecycle_fixture_main(1, arguments));
    }
    return child;
}
static int log_count(const char *needle) {
    FILE *f = fopen(fixture_path("lifecycle.err"), "r"); char line[256]; int count = 0;
    if (f) { while (fgets(line, sizeof line, f)) if (strstr(line, needle)) count++; fclose(f); }
    return count;
}
static int main_answers(const char *command, const char *prefix, int milliseconds) {
    char reply[160]; long long until = now_ms() + milliseconds;
    do {
        if (!request(command, reply, sizeof reply) && !strncmp(reply, prefix, strlen(prefix))) return 1;
        usleep(20000);
    } while (now_ms() < until);
    return 0;
}
static void kill_lifecycle(pid_t *lifecycle) {
    if (*lifecycle <= 1) return;
    char proc[128]; snprintf(proc, sizeof proc, "/proc/%ld/task/%ld/children", (long)*lifecycle, (long)*lifecycle);
    FILE *f = fopen(proc, "r"); long child;
    if (f) { while (fscanf(f, "%ld", &child) == 1) if (child > 1) kill((pid_t)child, SIGKILL); fclose(f); }
    kill(*lifecycle, SIGKILL); waitpid(*lifecycle, NULL, 0); *lifecycle = -1;
    long long until = now_ms() + 2000;
    while (now_ms() < until) { pid_t r = waitpid(-1, NULL, WNOHANG); if (r < 0) break; if (!r) usleep(10000); }
}
static pid_t refusing_incumbent(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    memcpy(address.sun_path + 1, fixture_socket, strlen(fixture_socket));
    if (fd < 0 || bind(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + strlen(fixture_socket)) ||
        listen(fd, 8)) abort();
    pid_t child = fork();
    if (!child) { for (;;) { int c = accept(fd, NULL, NULL); if (c >= 0) close(c); } }
    close(fd); return child;
}
static void retry_refused_incumbent_test(void) {
    prepare();
    /* Not a recovery image: replacement must refuse it, as for an unrecognised legacy owner. */
    pid_t incumbent = refusing_incumbent();
    pid_t lifecycle = launch_logged_lifecycle();
    CHECK(main_answers("PING\n", "OK", 3000), "refused recovery custody still serves the main socket");
    CHECK(main_answers("STATUS\n", "ERR", 1000), "recovery STATUS refuses while another process holds custody");
    long long window = now_ms(); int before = log_count("retry refused");
    while (now_ms() < window + 2000) usleep(20000);
    int attempts = log_count("retry refused") - before;
    CHECK(attempts >= 2, "refused recovery custody is retried");
    CHECK(attempts <= 6, "recovery custody retry backs off");
    char reply[160];
    CHECK(!request("ARM aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n", reply, sizeof reply) && !strcmp(reply, "ERR") &&
          network_is("running"), "ARM stays refused until custody is acquired");
    kill(incumbent, SIGKILL); waitpid(incumbent, NULL, 0);
    CHECK(main_answers("STATUS\n", "READY 1 ", 3000), "main helper acquires recovery custody once the incumbent exits");
    CHECK(log_count("acquired on retry") == 1, "custody retry reports acquisition once");
    CHECK(!request("ARM aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n", reply, sizeof reply) && !strcmp(reply, "OK") &&
          network_is("stopped"), "retried custody admits ARM through the main socket");
    kill_lifecycle(&lifecycle);
}
static void retry_same_build_owner_test(void) {
    prepare(); unlink(fixture_path("restart.calls"));
    if (setenv("HAPANELD_FIXTURE_SOCKET", fixture_socket, 1)) abort();
    pid_t incumbent = fork();
    if (!incumbent) {
        char *arguments[] = {"helper", "--standalone-owner", NULL};
        extern char **environ;
        execve(fixture_path("helper/helper"), arguments, environ); _exit(98);
    }
    char reply[160]; int ready = 0, direct;
    for (int i = 0; i < 300 && !ready; i++) {
        direct = connect_recovery(fixture_socket);
        if (direct >= 0) {
            read_deadline = now_ms() + 4000;
            ready = !write_all(direct, "STATUS\n", 7) && !read_line(direct, reply, sizeof reply) && !strncmp(reply, "READY 1 " HAPANELD_BUILD_ID " ", strlen("READY 1 " HAPANELD_BUILD_ID " "));
            close(direct);
        }
        if (!ready) usleep(10000);
    }
    CHECK(ready, "same-build standalone owner serves recovery");
    pid_t lifecycle = launch_logged_lifecycle();
    CHECK(main_answers("PING\n", "OK", 3000), "main socket serves beside a same-build recovery owner");
    long long until = now_ms() + 1500;
    while (now_ms() < until) usleep(20000);
    CHECK(log_count("retry refused") >= 2, "main helper retries beside a same-build owner");
    CHECK(waitpid(incumbent, NULL, WNOHANG) == 0 && !kill(incumbent, 0), "retry never terminates a healthy same-build owner");
    direct = connect_recovery(fixture_socket); struct ucred peer; socklen_t size = sizeof peer;
    CHECK(direct >= 0 && !getsockopt(direct, SOL_SOCKET, SO_PEERCRED, &peer, &size) && peer.pid == incumbent,
          "same-build owner keeps the recovery socket");
    if (direct >= 0) close(direct);
    CHECK(access(fixture_path("restart.calls"), F_OK) != 0, "same-build owner is not asked to recover");
    kill(incumbent, SIGKILL); waitpid(incumbent, NULL, 0);
    CHECK(main_answers("STATUS\n", "READY 1 ", 3000), "main helper converges on sole custody after the other owner exits");
    kill_lifecycle(&lifecycle);
    unsetenv("HAPANELD_FIXTURE_SOCKET");
}
static void retry_custody_lock_test(void) {
    prepare();
    /* A separate holder: flock follows the open description, which a forked lifecycle would share. */
    unlink(fixture_path("lock-held"));
    pid_t holder = fork();
    if (!holder) {
        int lock = open(fixture_path("helper/.lease-owner.lock"), O_RDWR | O_CREAT | O_CLOEXEC, 0600);
        if (lock < 0 || fchmod(lock, 0600) || flock(lock, LOCK_EX | LOCK_NB)) _exit(1);
        put("lock-held", "yes"); for (;;) pause();
    }
    for (int i = 0; i < 200 && access(fixture_path("lock-held"), F_OK); i++) usleep(10000);
    CHECK(!access(fixture_path("lock-held"), F_OK), "fixture holds the custody lock as a draining guardian would");
    pid_t lifecycle = launch_logged_lifecycle();
    long long until = now_ms() + 7500;
    while (now_ms() < until) usleep(50000);
    CHECK(waitpid(lifecycle, NULL, WNOHANG) == 0, "a held custody lock does not end the main helper");
    CHECK(main_answers("PING\n", "OK", 2000), "a held custody lock leaves the main socket serving");
    CHECK(main_answers("STATUS\n", "ERR", 1000), "a held custody lock keeps recovery refused");
    kill(holder, SIGKILL); waitpid(holder, NULL, 0);
    CHECK(main_answers("STATUS\n", "READY 1 ", 9000), "main helper takes custody once the lock is released");
    kill_lifecycle(&lifecycle);
}
int main(int argc,char **argv) {
    if (argc == 3 && !strcmp(argv[1], "--drain-exec")) {
        pid_t prior = (pid_t)strtol(argv[2], NULL, 10);
        int directory = open(ADB_RECOVERY_DIRECTORY, O_RDONLY | O_DIRECTORY);
        int fd = directory >= 0 ? openat(directory, ".lease-owner.lock", O_RDWR | O_CLOEXEC) : -1;
        int valid = prior > 1 && kill(prior, 0) < 0 && errno == ESRCH && fd >= 0 && !flock(fd, LOCK_EX | LOCK_NB);
        if (fd >= 0) close(fd);
        if (directory >= 0) close(directory);
        return valid ? 0 : 97;
    }
    if (argc == 2 && !strcmp(argv[1], "--standalone-owner")) {
        const char *directory = getenv("HAPANELD_ADB_FIXTURE"), *name = getenv("HAPANELD_FIXTURE_SOCKET");
        if (!directory || !name) return 90;
        snprintf(fixture_socket, sizeof fixture_socket, "%s", name);
        snprintf(fixture_app_data, sizeof fixture_app_data, "%s/app-data", directory);
        snprintf(fixture_app_user_data, sizeof fixture_app_user_data, "%s/app-user-data", directory);
        fixture_lease_region = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        if (fixture_lease_region == MAP_FAILED) return 91;
        return adb_recovery_main();
    }
    int serve = argc == 2 && !strcmp(argv[1], "--serve");
    if(argc==2 && !serve){fputs(get(argv[1]),stdout);put("getprop.calls","yes");return 0;}
    if(argc==3){
        if(!strcmp(argv[1],"ctl.stop")){
            put("init.svc.adbd","stopped");put("tcp","");
            DIR *members = opendir(getenv("HAPANELD_ADB_FIXTURE")); struct dirent *entry;
            while (members && (entry = readdir(members))) {
                long pid = 0; char extra;
                if (sscanf(entry->d_name,"custody-%ld%c",&pid,&extra) == 1 && pid > 1 &&
                    !strcmp(get(entry->d_name),"service")) (void)kill((pid_t)pid,SIGKILL);
            }
            if (members) closedir(members);
        }
        else if(!strcmp(argv[1],"ctl.restart")){
            int calls = open(fixture_path("restart.calls"), O_WRONLY | O_CREAT | O_APPEND, 0600);
            if (calls < 0 || write(calls, "restart\n", 8) != 8) abort();
            close(calls);
            while (access(fixture_path("pause-restart"), F_OK) == 0) usleep(5000);
            if(access(fixture_path("no_backup/network-adb-recovery.fence"),F_OK) || access(fixture_path("no_backup/.remote-debug-security-authority.v1.publish"),F_OK)) put("unfenced-reopen","yes");
            put("init.svc.adbd","running");
            put("tcp",access(fixture_path("loopback-only"),F_OK)==0 ? "0: 0100007F:15B3 00000000:0000 0A\n" : "0: 00000000:15B3 00000000:0000 0A\n");
        }
        else put(argv[1],argv[2]);
        return 0;
    }
    prctl(PR_SET_CHILD_SUBREAPER,1);
    char directory[]="/tmp/adb-recovery-XXXXXX";if(!mkdtemp(directory)||setenv("HAPANELD_ADB_FIXTURE",directory,1))abort();
    if(setenv("ANDROID_ROOT","/poison-root",1)||setenv("ANDROID_DATA","/poison-data",1)||setenv("CLASSPATH","/poison-classpath",1))abort();
    snprintf(fixture_app_data,sizeof fixture_app_data,"%s/app-data",directory);
    snprintf(fixture_app_user_data,sizeof fixture_app_user_data,"%s/app-user-data",directory);
    snprintf(fixture_socket,sizeof fixture_socket,"hapaneld-adb-recovery-test-%ld",(long)getpid());
    snprintf(fixture_main_socket,sizeof fixture_main_socket,"hapaneld-main-recovery-test-%ld",(long)getpid());
    fixture_lease_region=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    if(fixture_lease_region==MAP_FAILED)abort();
    if (mkdir(fixture_path("no_backup"),0700)) abort();
    if (mkdir(APP_DATA,0700) || mkdir(APP_USER_DATA,0700)) abort();
    if (serve) {
        fixture_lease_ms = 60000;
        /* Serve as an owner already in the root cgroup, as after a panel's migration, so the
         * JVM composition test does not depend on the host's cgroup layout (a systemd CI host
         * runs jobs in a service cgroup that the Android custody check rightly refuses). */
        fixture_custody = 1; fixture_migrated = 1; fixture_magic = 0;
        start();
        const char *path = getenv("HAPANELD_MAIN_FIXTURE_PATH");
        printf("%s\n", path && *path ? path : fixture_main_socket); fflush(stdout);
        (void)getchar(); stop();
        if (path && *path) unlink(path);
        char cleanup[700]; snprintf(cleanup, sizeof cleanup, "rm -rf -- '%s'", directory);
        return system(cleanup) || failures ? 1 : 0;
    }
    if (getenv("HAPANELD_RETRY_ONLY")) {
        retry_refused_incumbent_test(); retry_same_build_owner_test(); retry_custody_lock_test();
        char command[700]; snprintf(command, sizeof command, "rm -rf -- '%s'", directory); if (system(command)) failures++;
        if (!failures) puts("adb recovery retry contracts passed");
        return failures ? 1 : 0;
    }
    retry_refused_incumbent_test(); retry_same_build_owner_test(); retry_custody_lock_test();
    ipc_peer_test(); occupied_main_test(); main_worker_loss_test(); guardian_drain_exec_test(); successor_guardian_test();
    custody_tests(); heartbeat_tests(); reopen_diagnostic_tests(); absent_data_test(); managed_absent_trigger_test();
    start();unauthorized_test();combined_frame_test();shell_tests();malformed_request_tests();worker_death_test();stop();start();arm();
    int slow=connect_owner();write_all(slow,"S",1);
    long long slow_deadline = now_ms() + 1200;
    while (!network_is("running") && now_ms() < slow_deadline) usleep(10000);
    CHECK(network_is("running"),"guardian expires a lease while request framing is blocked");close(slow);
    char rearm[100],rearm_reply[160];int rearmed=0;
    snprintf(rearm,sizeof rearm,"ARM %s\n",fixture_nonce);
    long long rearm_deadline=now_ms()+1200;
    while(now_ms()<rearm_deadline){
        if(!request(rearm,rearm_reply,sizeof rearm_reply)&&!strcmp(rearm_reply,"OK")){rearmed=1;break;}
        usleep(10000);
    }
    CHECK(rearmed,"healthy app can rearm the same session after recovery finishes");
    CHECK(network_is("stopped"),"healthy app can rearm after automatic recovery without a new websocket");
    stop();fixture_lease_ms=60000;start();arm();
    kill(fixture_guardian,SIGKILL);waitpid(fixture_guardian,NULL,0);fixture_guardian=-1;
    CHECK(wait_network("running"),"guardian death reopens before lease expiry");
    stop();fixture_lease_ms=600;
    start();arm();
    int pending=connect_owner();
    char late[600];snprintf(late,sizeof late,"touch '%s'",fixture_path("orphan-executed"));
    char late_header[100];snprintf(late_header,sizeof late_header,"SHELL 1000 %zu\n",strlen(late));
    unlink(fixture_path("orphan-executed"));write_all(pending,late_header,strlen(late_header));usleep(30000);
    kill(owner,SIGKILL);waitpid(owner,NULL,0);owner=-1;
    usleep(300000);(void)send(pending,late,strlen(late),MSG_NOSIGNAL);close(pending);
    usleep(100000);CHECK(access(fixture_path("orphan-executed"),F_OK)!=0,"owner death before payload cannot launch an orphan shell");
    CHECK(wait_network("running"),"owner death independently reopens network ADB");
    CHECK(access(fixture_path("no_backup/network-adb-recovery.fence"),F_OK)==0 &&
          access(fixture_path("no_backup/.remote-debug-security-authority.v1.publish"),F_OK)==0,
          "independent reopen durably fences existing security authority");
    CHECK(access(fixture_path("unfenced-reopen"),F_OK)!=0,"network reopen cannot precede durable authority invalidation");stop();
    start();arm();char reply[160];
    CHECK(!request("RECOVER bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n",reply,sizeof reply)&&!strcmp(reply,"OK"),"a fresh session may recover before ARM");
    usleep(800000);CHECK(network_is("running"),"recovery hold survives the previous lease deadline");
    CHECK(!request("ARM bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n",reply,sizeof reply)&&!strcmp(reply,"HELD"),"explicit recovery hold rejects reclosure in the same session");
    CHECK(!request("ARM cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n",reply,sizeof reply)&&!strcmp(reply,"OK"),"a fresh compatible session releases the explicit recovery hold");stop();
    start();arm();put("loopback-only","yes");
    CHECK(!request("RECOVER aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n",reply,sizeof reply)&&!strcmp(reply,"ERR"),"loopback listener is not a network recovery acknowledgement");
    unlink(fixture_path("loopback-only"));stop();
    start();arm();
    put("no_backup/remote-debug-security-authority.v1","old-hardened-authority");put("fence-write-fails","yes");
    CHECK(wait_network("running"),"fence creation failure still recovers the dead app route");
    CHECK(access(fixture_path("no_backup/remote-debug-security-authority.v1"),F_OK)!=0,"allocation failure retires existing authority without a new file");
    unlink(fixture_path("fence-write-fails"));stop();
    start();arm();put("no_backup/remote-debug-security-authority.v1","old-hardened-authority");
    put("fence-write-fails","yes");put("authority-readonly","yes");
    CHECK(wait_network("running"),"read-only authority storage cannot strand emergency recovery");
    CHECK(!request("STATUS\n",reply,sizeof reply) && strstr(reply," 0 0 1")!=NULL,"emergency recovery reports unverified security and no managed-OFF proof");
    unlink(fixture_path("fence-write-fails"));unlink(fixture_path("authority-readonly"));stop();
    CHECK(!rmdir(APP_DATA) && !rmdir(APP_USER_DATA),"controlled app data cleanup succeeds");
    char command[700];snprintf(command,sizeof command,"rm -rf -- '%s'",directory);int cleanup_status = system(command); CHECK(cleanup_status == 0,"fixture cleanup succeeds");
    if(!failures)puts("adb recovery behavioral contracts passed");
    return failures?1:0;
}
