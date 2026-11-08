#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdint.h>
#ifdef RECOVERY_REPLACEMENT_TEST_ROOT
static int fixture_new(void);
static int ensure_sync(int fd, int *handled);
static size_t decimal(char *out, uint64_t value);
static void receipt(const char *path,const char *bytes,size_t size);
#define FIXTURE(name) RECOVERY_REPLACEMENT_TEST_ROOT "/" name
#define NEW_MARKER(name) (fixture_new() && !access(FIXTURE(name), F_OK))
#else
#define NEW_MARKER(name) 0
#endif
static int owner_terminated;
int __real_kill(pid_t pid, int signal_number);
int __wrap_kill(pid_t pid, int signal_number) {
#ifdef RECOVERY_REPLACEMENT_TEST_ROOT
    if (fixture_new() && signal_number == SIGTERM) {
        char line[64]; size_t used=decimal(line,(uint64_t)pid); line[used++]=' '; used+=decimal(line+used,(unsigned)signal_number);line[used++]='\n';
        receipt(FIXTURE("ensure-signals"),line,used);
    }
#endif
    int result = __real_kill(pid, signal_number);
    if (!result && signal_number == SIGTERM) owner_terminated = 1;
    return result;
}
int __real_fstatat(int directory, const char *name, struct stat *metadata, int flags);
int __wrap_fstatat(int directory, const char *name, struct stat *metadata, int flags) {
    if (owner_terminated && (getenv("RECOVERY_REPLACEMENT_SCAN_ERROR") || NEW_MARKER("scan-error")) && !strcmp(name, "exe")) {
        errno = EACCES; return -1;
    }
    return __real_fstatat(directory, name, metadata, flags);
}
#include <stdio.h>
FILE *__real_fopen(const char *path, const char *mode);
FILE *__wrap_fopen(const char *path, const char *mode) {
    if ((getenv("RECOVERY_REPLACEMENT_HIDE_LISTENER") || NEW_MARKER("hide-listener")) && !strcmp(path, ADB_RECOVERY_TCP))
        return __real_fopen("/dev/null", mode);
    return __real_fopen(path, mode);
}

int __real_usleep(useconds_t duration);
int __wrap_usleep(useconds_t duration) {
    const char *block = getenv("RECOVERY_REPLACEMENT_GUARDIAN_BLOCK");
    if (duration == 200000 && block) {
        char marker[4096];
        if (snprintf(marker, sizeof marker, "%s.entered", block) >= (int)sizeof marker) abort();
        FILE *file = fopen(marker, "w");
        if (!file) abort();
        fclose(file);
        while (!access(block, F_OK)) __real_usleep(20000);
    }
    /* Model a slow old guardian after owner death without changing the shipped source. */
    return __real_usleep(duration == 200000 && getenv("RECOVERY_REPLACEMENT_SLOW_GUARDIAN") ? 1000000 : duration);
}

int __real_fsync(int descriptor);
int __wrap_fsync(int descriptor) {
    if (getenv("RECOVERY_REPLACEMENT_RECEIPT_SYNC_ERROR") || NEW_MARKER("receipt-sync-error")) {
        char path[64], target[4096];
        snprintf(path, sizeof path, "/proc/self/fd/%d", descriptor);
        ssize_t n = readlink(path, target, sizeof target - 1);
        if (n >= 0) {
            target[n] = 0;
            const char *name = strrchr(target, '/');
            if (name && !strcmp(name, "/.replacement-retiring")) { errno = EIO; return -1; }
        }
    }
#ifdef RECOVERY_REPLACEMENT_TEST_ROOT
    int handled, result = ensure_sync(descriptor, &handled);
    if (handled) return result;
#endif
    return __real_fsync(descriptor);
}

#ifdef RECOVERY_REPLACEMENT_TEST_ROOT
#include <fcntl.h>
#include <stdint.h>
#include <pthread.h>
#include <poll.h>
static _Thread_local int ensure_published;
static int fixture_new(void) { return !strcmp(HAPANELD_BUILD_ID, "2222222222222222222222222222222222222222222222222222222222222222"); }
/* These helpers are also used after fork: fixed storage and async-safe syscalls only. */
static size_t decimal(char *out, uint64_t value) {
    char reversed[32]; size_t n=0;
    do { reversed[n++]=(char)('0'+value%10); value/=10; } while(value);
    for(size_t i=0;i<n;i++)out[i]=reversed[n-i-1];
    return n;
}
static int descriptor_target(int fd, char out[4096]) {
    char path[64]="/proc/self/fd/"; size_t used=14; used+=decimal(path+used,(unsigned)fd);path[used]=0;
    ssize_t n=readlink(path,out,4095);if(n<0||n>=4095)return-1;out[n]=0;return 0;
}
ssize_t __real_write(int fd,const void *bytes,size_t size);
static void receipt(const char *path,const char *bytes,size_t size) {
    int fd=open(path,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0600);
    if(fd<0)_exit(126);
    if(__real_write(fd,bytes,size)!=(ssize_t)size){close(fd);_exit(126);}close(fd);
}
static int stage_descriptor(int fd) {
    char target[4096];static const char prefix[]=ADB_RECOVERY_DIRECTORY "/.ensure-";
    return !descriptor_target(fd,target)&&!strncmp(target,prefix,sizeof prefix-1);
}
ssize_t __wrap_write(int fd,const void *bytes,size_t size) {
    if(fixture_new()&&!access(FIXTURE("ensure-copy-error"),F_OK)&&stage_descriptor(fd)){errno=ENOSPC;return-1;}
    return __real_write(fd,bytes,size);
}
int __real_renameat(int olddir,const char *oldname,int newdir,const char *newname);
int __wrap_renameat(int olddir,const char *oldname,int newdir,const char *newname) {
    int result=__real_renameat(olddir,oldname,newdir,newname);
    if(!result&&!strncmp(oldname,".ensure-",8)&&!strcmp(newname,"helper")) {
        ensure_published=1;receipt(FIXTURE("ensure-fsync"),"RENAME\n",7);
    }
    return result;
}
static int ensure_sync(int fd,int *handled) {
    char target[4096];*handled=0;
    if(descriptor_target(fd,target))return 0;
    const char *kind=NULL;
    if(stage_descriptor(fd))kind="FILE_OK\n";
    else if(!strcmp(target,ADB_RECOVERY_DIRECTORY))kind=ensure_published?"DIR_AFTER_OK\n":"DIR_PRE_OK\n";
    else if(!strcmp(target,RECOVERY_REPLACEMENT_TEST_ROOT))kind="PARENT_OK\n";
    if(!kind)return 0;
    *handled=1;
    if(fixture_new()&&((stage_descriptor(fd)&&!access(FIXTURE("ensure-file-sync-error"),F_OK))||
        (ensure_published&&!strcmp(target,ADB_RECOVERY_DIRECTORY)&&!access(FIXTURE("ensure-dir-sync-error"),F_OK)))){errno=EIO;return-1;}
    int result=__real_fsync(fd);
    if(!result)receipt(FIXTURE("ensure-fsync"),kind,strlen(kind));
    return result;
}
int __real_pthread_create(pthread_t *thread,const pthread_attr_t *attr,void *(*start)(void*),void *arg);
int __wrap_pthread_create(pthread_t *thread,const pthread_attr_t *attr,void *(*start)(void*),void *arg) {
    int detached;
    if(fixture_new()&&attr&&!pthread_attr_getdetachstate(attr,&detached)&&detached==PTHREAD_CREATE_DETACHED&&
        !access(FIXTURE("ensure-reaper-error"),F_OK)){
        receipt(FIXTURE("ensure-reaper-refused"),"REAPER\n",7);return EAGAIN;
    }
    return __real_pthread_create(thread,attr,start,arg);
}
static unsigned long long start_ticks(void) {
    char text[4096];int fd=open("/proc/self/stat",O_RDONLY|O_CLOEXEC);if(fd<0)_exit(126);
    ssize_t n=read(fd,text,sizeof text-1);close(fd);if(n<=0||n==(ssize_t)sizeof text-1)_exit(126);text[n]=0;
    char *last=NULL;for(char *p=text;*p;p++)if(*p==')')last=p;
    if(!last||last[1]!=' ')_exit(126);
    char *p=last+2;
    for(int field=3;field<22;field++){while(*p&&*p!=' ')p++;if(!*p)_exit(126);p++;}
    unsigned long long value=0;if(*p<'0'||*p>'9')_exit(126);
    while(*p>='0'&&*p<='9'){value=value*10+(unsigned)(*p-'0');p++;}return value;
}
int __real_execve(const char *path,char *const argv[],char *const env[]);
static void exec_contract(int pin, const struct stat *published, char *const argv[], char *const env[]) {
    static const char *const expected[] = {"PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LANG=C", "LC_ALL=C"};
    int valid = argv[0] && !strcmp(argv[0], "hapaneld-helper") && argv[1] &&
        !strcmp(argv[1], "--adb-recovery") && !argv[2] && env;
    for (unsigned i=0;valid&&i<5;i++) if(!env[i]||strcmp(env[i],expected[i]))valid=0;
    if(valid&&env[5])valid=0;
    int flags=fcntl(pin,F_GETFL), descriptor=fcntl(pin,F_GETFD);
    if(flags<0||(flags&O_ACCMODE)!=O_RDONLY||descriptor<0||!(descriptor&FD_CLOEXEC))valid=0;
    char target[4096];
    for(int fd=0;fd<3;fd++)if(descriptor_target(fd,target)||strcmp(target,"/dev/null"))valid=0;
    unsigned pipes=0;
    /* No allocation/stdio after fork. The publisher refuses bounds above this fixed ceiling. */
    for(int fd=3;fd<1048576;fd++) {
        int fdflags=fcntl(fd,F_GETFD);
        if(fdflags<0){if(errno!=EBADF)valid=0;continue;}
        struct stat st;int mode=fcntl(fd,F_GETFL);
        if(fstat(fd,&st)||mode<0){valid=0;continue;}
        if(st.st_dev==published->st_dev&&st.st_ino==published->st_ino&&(mode&O_ACCMODE)!=O_RDONLY)valid=0;
        if(fd==pin)continue;
        if(!S_ISFIFO(st.st_mode)||(mode&O_ACCMODE)!=O_WRONLY||!(mode&O_NONBLOCK)||!(fdflags&FD_CLOEXEC))valid=0;
        pipes++;
    }
    if(pipes!=1)valid=0;
    char line[64];size_t used=decimal(line,(uint64_t)getpid());line[used++]=' ';
    const char *result=valid?"VALID\n":"INVALID\n";size_t size=valid?6:8;
    memcpy(line+used,result,size);receipt(FIXTURE("ensure-exec-contract"),line,used+size);
}
int __wrap_execve(const char *path,char *const argv[],char *const env[]) {
    static const char prefix[]="/proc/self/fd/";
    if(!strncmp(path,prefix,sizeof prefix-1)&&argv&&argv[1]&&!strcmp(argv[1],"--adb-recovery")) {
        unsigned fd=0;const char *p=path+sizeof prefix-1;
        if(!*p)_exit(126);
        while(*p>='0'&&*p<='9'){fd=fd*10+(unsigned)(*p-'0');p++;}if(*p)_exit(126);
        struct stat current,published;int self=open("/proc/self/exe",O_RDONLY|O_CLOEXEC);
        if(self<0||fstat(self,&current)||fstat((int)fd,&published))_exit(126);
        close(self);
        exec_contract((int)fd,&published,argv,env);
        char line[256];size_t used=0;uint64_t values[]={getpid(),start_ticks(),getsid(0),current.st_dev,current.st_ino,published.st_dev,published.st_ino};
        for(unsigned i=0;i<7;i++){used+=decimal(line+used,values[i]);line[used++]=i==6?'\n':' ';}
        receipt(FIXTURE("ensure-spawns"),line,used);
        if(fixture_new()&&!access(FIXTURE("ensure-before-exec-block"),F_OK)) {
            receipt(FIXTURE("ensure-before-exec-block.entered"),"ENTERED\n",8);
            while(!access(FIXTURE("ensure-before-exec-block"),F_OK))poll(NULL,0,20);
        }
        if(fixture_new()&&!access(FIXTURE("ensure-exec-error"),F_OK)){errno=EACCES;return-1;}
    }
    return __real_execve(path,argv,env);
}
#endif

/* Preserve the legacy independent recovery engine in these compatibility cases. The current
 * production --adb-recovery entry bootstraps the merged main owner and refuses its replacement. */
#ifdef RECOVERY_REPLACEMENT_STANDALONE_ENTRY
#include "adb_recovery.h"
int __real_main(int, char **);
int __wrap_main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--adb-recovery")) return adb_recovery_main();
    return __real_main(argc, argv);
}
#endif
