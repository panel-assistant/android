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
static const char *fixture_path(const char *name) {
    static char paths[4][512]; static unsigned next;
    char *path = paths[next++ % 4];
    snprintf(path, 512, "%s/%s", getenv("HAPANELD_ADB_FIXTURE"), name); return path;
}
/* Per-run fixture identity (the Makefile force-includes adb_recovery_paths.h). */
char fixture_app_data[512], fixture_app_user_data[512];
static char fixture_socket[64];
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
#include "../src/adb_recovery.c"
#undef close
#undef fstatfs
#undef write
#undef fork
#undef open
#undef openat
#undef unlinkat
#undef execve
#undef mmap
static int failures;
#define CHECK(c,m) do { if (!(c)) { fprintf(stderr,"FAIL: %s\n",m); failures++; } } while (0)
static pid_t owner = -1, guardian = -1;
static void put(const char *name, const char *value) {
    FILE *f = fopen(fixture_path(name), "w"); if (!f) abort(); fputs(value, f); fclose(f);
}
static char *get(const char *name) {
    static char text[1024]; FILE *f = fopen(fixture_path(name), "r"); text[0] = 0;
    if (f) { if (!fgets(text, sizeof text, f)) text[0] = 0; fclose(f); } return text;
}
static int connect_owner(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    memcpy(addr.sun_path + 1, ADB_RECOVERY_SOCKET, strlen(ADB_RECOVERY_SOCKET));
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr, offsetof(struct sockaddr_un,sun_path)+1+strlen(ADB_RECOVERY_SOCKET))) { if (fd >= 0) close(fd); return -1; }
    struct timeval timeout = { .tv_sec = 4 };
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout); return fd;
}
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
    if(guardian>1){kill(guardian,SIGKILL);waitpid(guardian,NULL,0);guardian=-1;}
    while(waitpid(-1,NULL,WNOHANG)>0){}
}
static void start(void) {
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
    owner=fork();if(!owner)_exit(adb_recovery_main());
    char response[160];int ready=0;
    for(int i=0;i<100;i++){if(!request("STATUS\n",response,sizeof response)){ready=1;break;}usleep(10000);}
    CHECK(ready,"independent recovery socket starts");
    char proc[128];snprintf(proc,sizeof proc,"/proc/%ld/task/%ld/children",(long)owner,(long)owner);
    FILE*f=fopen(proc,"r");long child=-1;if(f){if (fscanf(f,"%ld",&child) != 1) child = -1;fclose(f);}guardian=(pid_t)child;
    CHECK(guardian>1,"recovery owner has an independent guardian");
}
static const char *nonce="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
static void arm(void) {
    char command[100],response[160];snprintf(command,sizeof command,"ARM %s\n",nonce);
    CHECK(!request(command,response,sizeof response)&&!strcmp(response,"OK"),"lease arms before runtime close");
    CHECK(network_is("stopped"),"managed lease closes adbd");
    CHECK(!strcmp(get("persist.adb.tcp.port"),"5555"),"closure preserves positive reboot fallback");
}
/* Observe lease deadlines, with margins for property subprocesses and host scheduling. */
static void wait_until(long long deadline) {
    while (now_ms() < deadline) usleep(5000);
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
    snprintf(command, sizeof command, "BEAT %s\n", nonce);
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
 * cannot act, and the 60-second lease keeps the guardian's expiry path idle. */
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
    pid_t stranger = fork();
    if (!stranger) {
        if (setuid(65534)) _exit(99);
        int fd = connect_owner();
        if (fd < 0) _exit(98);
        (void)send(fd, "STATUS\n", 7, MSG_NOSIGNAL);
        char byte; int count = (int)read(fd, &byte, 1); close(fd);
        _exit(count <= 0 ? 0 : 97);
    }
    int status; while(waitpid(stranger,&status,0)<0&&errno==EINTR){}
    CHECK(WIFEXITED(status) && WEXITSTATUS(status)==0,"unrelated local UID cannot access administrator socket");
}
static pid_t command_worker(void) {
    char proc[128]; snprintf(proc,sizeof proc,"/proc/%ld/task/%ld/children",(long)owner,(long)owner);
    FILE *input=fopen(proc,"r"); if(!input)return -1;
    long child, result=-1;
    while(fscanf(input,"%ld",&child)==1)if(child!=(long)guardian)result=child;
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
        snprintf(name, sizeof name, "custody-%ld", (long)guardian);
        CHECK(!strcmp(get(name), "root"), "guardian inherits independent recovery custody");
        arm();
        int status; pid_t killed = waitpid(service, &status, WNOHANG);
        CHECK(killed == service && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL,
              "ARM really terminates the modeled adbd service cgroup");
        CHECK(!kill(owner, 0) && !kill(guardian, 0), "owner and guardian survive adbd service termination");
        char reply[160];
        CHECK(!request("STATUS\n", reply, sizeof reply), "recovery remains ready after adbd service termination");
        if (!killed) { kill(service, SIGKILL); waitpid(service, NULL, 0); }
        stop(); clear_custody_receipts();
    }
    fixture_custody = 1; fixture_migrated = 1; fixture_magic = 0;
    start(); /* Already independent custody needs no filesystem mutation. */
    CHECK(!kill(owner, 0) && !kill(guardian, 0), "existing root custody is reused without migration");
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
int main(int argc,char **argv) {
    if(argc==2){fputs(get(argv[1]),stdout);put("getprop.calls","yes");return 0;}
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
    fixture_lease_region=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    if(fixture_lease_region==MAP_FAILED)abort();
    if (mkdir(fixture_path("no_backup"),0700)) abort();
    if (mkdir(APP_DATA,0700) || mkdir(APP_USER_DATA,0700)) abort();
    custody_tests(); heartbeat_tests(); absent_data_test(); managed_absent_trigger_test();
    start();unauthorized_test();shell_tests();malformed_request_tests();worker_death_test();stop();start();arm();
    int slow=connect_owner();write_all(slow,"S",1);
    long long slow_deadline = now_ms() + 1200;
    while (!network_is("running") && now_ms() < slow_deadline) usleep(10000);
    CHECK(network_is("running"),"guardian expires a lease while request framing is blocked");close(slow);
    char rearm[100],rearm_reply[160];int rearmed=0;
    snprintf(rearm,sizeof rearm,"ARM %s\n",nonce);
    long long rearm_deadline=now_ms()+1200;
    while(now_ms()<rearm_deadline){
        if(!request(rearm,rearm_reply,sizeof rearm_reply)&&!strcmp(rearm_reply,"OK")){rearmed=1;break;}
        usleep(10000);
    }
    CHECK(rearmed,"healthy app can rearm the same session after recovery finishes");
    CHECK(network_is("stopped"),"healthy app can rearm after automatic recovery without a new websocket");
    stop();fixture_lease_ms=60000;start();arm();
    kill(guardian,SIGKILL);waitpid(guardian,NULL,0);guardian=-1;
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
