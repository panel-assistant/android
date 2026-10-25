#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <errno.h>
#include <stdarg.h>
static const char *fixture_path(const char *name) {
    static char paths[4][512]; static unsigned next;
    char *path = paths[next++ % 4];
    snprintf(path, 512, "%s/%s", getenv("HAPANELD_ADB_FIXTURE"), name); return path;
}
static int fixture_openat(int directory, const char *name, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list arguments; va_start(arguments, flags); mode = (mode_t)va_arg(arguments, int); va_end(arguments); }
    if ((flags & O_CREAT) && access(fixture_path("fence-write-fails"),F_OK)==0 &&
        (!strcmp(name,"network-adb-recovery.fence") || !strcmp(name,".remote-debug-security-authority.v1.publish"))) { errno=ENOSPC; return -1; }
    return openat(directory,name,flags,mode);
}
static int fixture_unlinkat(int directory, const char *name, int flags) {
    if(!strcmp(name,"remote-debug-security-authority.v1") && access(fixture_path("authority-readonly"),F_OK)==0) { errno=EROFS; return -1; }
    return unlinkat(directory,name,flags);
}
#define openat fixture_openat
#define unlinkat fixture_unlinkat
#define HAPANELD_TEST_ADB_RECOVERY 1
#define ADB_RECOVERY_GETPROP "/proc/self/exe"
#define ADB_RECOVERY_SETPROP "/proc/self/exe"
#define ADB_RECOVERY_SHELL "/bin/sh"
#define ADB_RECOVERY_TCP fixture_path("tcp")
#define ADB_RECOVERY_TCP6 fixture_path("tcp6")
#define ADB_RECOVERY_FENCE_PARENT fixture_path("no_backup")
#define ADB_RECOVERY_SOCKET "hapaneld-adb-recovery-test"
#define ADB_RECOVERY_LEASE_MS 600
#include "../src/adb_recovery.c"
#undef openat
#undef unlinkat
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
}
int main(int argc,char **argv) {
    if(argc==2){fputs(get(argv[1]),stdout);put("getprop.calls","yes");return 0;}
    if(argc==3){
        if(!strcmp(argv[1],"ctl.stop")){put("init.svc.adbd","stopped");put("tcp","");}
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
    if (mkdir(fixture_path("no_backup"),0700)) abort();
    start();unauthorized_test();shell_tests();worker_death_test();stop();start();arm();
    int slow=connect_owner();write_all(slow,"S",1);
    long long slow_deadline = now_ms() + 1200;
    while (!network_is("running") && now_ms() < slow_deadline) usleep(10000);
    CHECK(network_is("running"),"guardian expires a lease while request framing is blocked");close(slow);
    stop();start();arm();kill(guardian,SIGKILL);waitpid(guardian,NULL,0);guardian=-1;
    CHECK(wait_network("running"),"guardian death reopens network ADB");stop();
    start();arm();
    int pending=connect_owner();
    const char *late="touch /tmp/.hapaneld-adb-orphan-executed";
    char late_header[100];snprintf(late_header,sizeof late_header,"SHELL 1000 %zu\n",strlen(late));
    unlink("/tmp/.hapaneld-adb-orphan-executed");write_all(pending,late_header,strlen(late_header));usleep(30000);
    kill(owner,SIGKILL);waitpid(owner,NULL,0);owner=-1;
    usleep(300000);(void)send(pending,late,strlen(late),MSG_NOSIGNAL);close(pending);
    usleep(100000);CHECK(access("/tmp/.hapaneld-adb-orphan-executed",F_OK)!=0,"owner death before payload cannot launch an orphan shell");
    CHECK(wait_network("running"),"owner death independently reopens network ADB");
    CHECK(access(fixture_path("no_backup/network-adb-recovery.fence"),F_OK)==0 &&
          access(fixture_path("no_backup/.remote-debug-security-authority.v1.publish"),F_OK)==0,
          "independent reopen durably fences existing security authority");
    CHECK(access(fixture_path("unfenced-reopen"),F_OK)!=0,"network reopen cannot precede durable authority invalidation");stop();
    start();arm();char reply[160];
    CHECK(!request("RECOVER bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n",reply,sizeof reply)&&!strcmp(reply,"OK"),"a fresh session may recover before ARM");
    usleep(800000);CHECK(network_is("running"),"recovery hold survives the previous lease deadline");stop();
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
    CHECK(!request("STATUS\n",reply,sizeof reply) && strstr(reply," 0 1 1")!=NULL,"emergency recovery reports unverified security and no managed-OFF proof");
    unlink(fixture_path("fence-write-fails"));unlink(fixture_path("authority-readonly"));stop();
    char command[700];snprintf(command,sizeof command,"rm -rf -- '%s'",directory);int cleanup_status = system(command); CHECK(cleanup_status == 0,"fixture cleanup succeeds");
    if(!failures)puts("adb recovery behavioral contracts passed");
    return failures?1:0;
}
