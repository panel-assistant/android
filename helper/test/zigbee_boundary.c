// Drive real dispatch/handlers against fixed compile-time filesystem fixtures. No new module APIs.
// This same source also links against the pre-feature CORE_SRCS: unknown verbs compile, then fail
// the declared positive wire assertions while PING/argument rejection controls stay green.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include "dispatch.h"
#include "sysexec_stub.h"

static int failures;
#define CHECK(c, text) do { if (!(c)) { printf("FAIL: %s (%s:%d)\n", text, __FILE__, __LINE__); failures++; } } while (0)
static int remove_entry(const char *path, const struct stat *st, int type, struct FTW *f) {
    (void)st; (void)type; (void)f; return remove(path);
}
static void clean(void) { (void)nftw(ZIGBEE_TEST_ROOT, remove_entry, 32, FTW_DEPTH | FTW_PHYS); }
static void bytes(const char *path, const void *data, size_t length, mode_t mode) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, mode);
    CHECK(fd >= 0, "Zigbee fixture file opens");
    if (fd >= 0) {
        CHECK(write(fd, data, length) == (ssize_t)length, "Zigbee fixture file writes");
        CHECK(close(fd) == 0, "Zigbee fixture file closes");
    }
}
static void text(const char *path, const char *value, mode_t mode) { bytes(path, value, strlen(value), mode); }
static void setup(void) {
    clean();
    CHECK(mkdir(ZIGBEE_TEST_ROOT, 0700) == 0, "Zigbee fixture root created");
    CHECK(mkdir(ZIGBEE_DIR, 0700) == 0, "Zigbee fixture vendor directory created");
    CHECK(mkdir(ZIGBEE_DATA, 0700) == 0, "Zigbee fixture data directory created");
    CHECK(mkdir(ZIGBEE_PROC, 0700) == 0, "Zigbee fixture proc directory created");
    text(ZIGBEE_SH, "shell", 0700);
    text(ZIGBEE_PROC "/stat", "cpu 100 2 30 400 5 6 7 8 20 1\ncpu0 50 1 15 200\n", 0600);
    sysexec_stub_reset();
}
static void command(const char *line, char *out, size_t capacity) {
    int pair[2]; CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Zigbee dispatch socket opens");
    conn_ctx ctx = { .fd = pair[0] }; char input[256]; snprintf(input, sizeof input, "%s", line);
    dispatch(&ctx, input); shutdown(pair[0], SHUT_WR);
    size_t used = 0;
    while (used + 1 < capacity) {
        ssize_t n = read(pair[1], out+used, capacity-1-used);
        if (n <= 0) break;
        used += (size_t)n;
    }
    out[used] = 0; close(pair[0]); close(pair[1]);
}
static void proc_stat(unsigned pid, unsigned long long start, unsigned long long flags, char state) {
    char path[1024], data[1024];
    snprintf(path, sizeof path, "%s/%u/stat", ZIGBEE_PROC, pid);
    snprintf(data, sizeof data, "%u (gateway ) tricky) %c 1 2 3 4 5 %llu 0 0 0 0 20 5 0 0 0 0 1 0 %llu 0\n", pid, state, flags, start);
    text(path, data, 0600);
}
static void process(unsigned pid, int guard, unsigned long long start) {
    char path[1024]; snprintf(path, sizeof path, "%s/%u", ZIGBEE_PROC, pid); CHECK(mkdir(path,0700)==0,"Zigbee process directory created");
    proc_stat(pid,start,0,'S');
    snprintf(path, sizeof path, "%s/%u/cmdline", ZIGBEE_PROC,pid);
    if (guard) { const char args[] = "sh\0" ZIGBEE_DIR "/guard_process.sh"; bytes(path,args,sizeof args,0600); }
    else { const char args[] = "zgateway"; bytes(path,args,sizeof args,0600); }
    snprintf(path,sizeof path,"%s/%u/exe",ZIGBEE_PROC,pid);
    CHECK(symlink(guard ? ZIGBEE_SH : ZIGBEE_DIR "/zgateway",path)==0,"Zigbee exe fixture links fixed inode");
}
static void layout(void) {
    text(ZIGBEE_DIR "/guard_process.sh", "#!/system/bin/sh\nexport LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}\n",0600);
    text(ZIGBEE_DIR "/zgateway","binary",0700);
    text(ZIGBEE_DIR "/mosquitto","broker",0700);
    text(ZIGBEE_DIR "/mosquitto_sub","client",0700);
}
static void admission_and_absence(void) {
    setup(); char out[10000];
    command("PING",out,sizeof out); CHECK(!strcmp(out,"OK\n"),"Zigbee pre-feature PING control stays green");
    const char *bad[]={"ZIGBEESTATE x","ZIGBEEHEALTH x","ZIGBEEROLE x","ZIGBEESTATE;reboot","ZIGBEEHEALTH /proc","ZIGBEEROLE ../x"};
    for(size_t i=0;i<sizeof bad/sizeof bad[0];i++){command(bad[i],out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee rejects every argument and verb injection");}
    command("ZIGBEESTATE",out,sizeof out);
    CHECK(!strcmp(out,"ZIGBEESTATE 1\nmanaged=0\nguard=0\nrun=0\nbinary=0\nbroker=0\npackage_marker=0\nrunning=0\nguard_running=0\npackage=\nEND\n"),"Zigbee complete empty scan proves absent state");
    command("ZIGBEEHEALTH",out,sizeof out);
    CHECK(strstr(out,"gateway_pid=-\ngateway_start=-\ngateway_jiffies=-\nguard_pid=-\nguard_start=-\nguard_jiffies=-\ntotal_jiffies=558\nrecursive=0\nnetinfo=-\nEND\n")!=NULL,"Zigbee complete empty health preserves absence and total CPU");
    CHECK(sysexec_stub_count_run("")==0 && sysexec_stub_count_argv_calls()==0,"Zigbee state and health never execute subprocesses");
}
static void identities(void) {
    setup();layout();process(71,0,100);process(72,1,200);char out[10000];
    text(ZIGBEE_DIR "/run_guard_process.sh","managed",0600);text(ZIGBEE_DIR "/package_version","sonoff-v3.5.4\n",0600);
    command("ZIGBEESTATE",out,sizeof out);
    CHECK(strstr(out,"managed=1\nguard=1\nrun=0\nbinary=1\nbroker=1\npackage_marker=1\nrunning=1\nguard_running=1\npackage=736f6e6f66662d76332e352e34\nEND\n")!=NULL,"Zigbee state preserves footprint package and exact live processes");
    command("ZIGBEEHEALTH",out,sizeof out);
    CHECK(strstr(out,"gateway_pid=71\ngateway_start=100\ngateway_jiffies=25\nguard_pid=72\nguard_start=200\nguard_jiffies=25\ntotal_jiffies=558\nrecursive=1\n")!=NULL,"Zigbee health reports exact PID start and jiffy samples");
    // An executable symlink to the platform shell must compare by inode, not literal readlink text.
    unlink(ZIGBEE_SH);text(ZIGBEE_TEST_ROOT "/mksh","shell",0700);CHECK(symlink(ZIGBEE_TEST_ROOT "/mksh",ZIGBEE_SH)==0,"Zigbee shell fixture is a platform alias");
    command("ZIGBEESTATE",out,sizeof out);CHECK(strstr(out,"guard_running=1\n")!=NULL,"Zigbee verifies shell aliases by executable inode");
    process(73,0,300);command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee duplicate exact processes remain unknown");
}
static void failures_remain_unknown(void) {
    char out[10000],path[1024];setup();layout();process(71,0,100);
    text(ZIGBEE_PROC "/71/stat","malformed stat\n",0600);command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee malformed process record never proves absence");
    proc_stat(71,100,0,'S');unlink(ZIGBEE_PROC "/71/cmdline");CHECK(mkdir(ZIGBEE_PROC "/71/cmdline",0700)==0,"Zigbee denied read fixture created");command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee incomplete process read never proves absence");
    setup();layout();process(71,0,100);unlink(ZIGBEE_PROC "/71/exe");CHECK(symlink(ZIGBEE_SH,ZIGBEE_PROC "/71/exe")==0,"Zigbee wrong exe fixture created");command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee rejects a same-name foreign executable");
    setup();layout();process(71,0,100);unlink(ZIGBEE_DIR "/zgateway");command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee missing binary with live candidate cannot prove absence");
    setup();rmdir(ZIGBEE_DIR);CHECK(symlink(ZIGBEE_DATA,ZIGBEE_DIR)==0,"Zigbee directory alias fixture created");command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee symlinked layout is unknown");
    setup();unlink(ZIGBEE_PROC "/stat");rmdir(ZIGBEE_PROC);command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee missing proc scan remains unknown");
    setup();snprintf(path,sizeof path,"%s/9",ZIGBEE_PROC);mkdir(path,0700);proc_stat(9,1,0,'Z');command("ZIGBEESTATE",out,sizeof out);CHECK(strstr(out,"running=0\n")!=NULL,"Zigbee skips zombies without requiring cmdline");
    proc_stat(9,1,0x00200000,'S');command("ZIGBEESTATE",out,sizeof out);CHECK(strstr(out,"running=0\n")!=NULL,"Zigbee skips kernel threads without requiring cmdline");
    unlink(ZIGBEE_PROC "/9/stat");command("ZIGBEESTATE",out,sizeof out);CHECK(strstr(out,"running=0\n")!=NULL,"Zigbee skips expected vanished process entries");
}
static void process_bounds(void) {
    setup(); layout(); process(71,0,100); char out[10000], raw[4097];
    memset(raw,'x',sizeof raw); raw[sizeof raw-1]=0;
    bytes(ZIGBEE_PROC "/71/cmdline",raw,sizeof raw,0600);
    command("ZIGBEESTATE",out,sizeof out);
    CHECK(!strcmp(out,"ERR\n"),"Zigbee oversized cmdline remains unknown");
    setup();
    for (unsigned i=1;i<=8193;i++) {
        char path[1024]; snprintf(path,sizeof path,"%s/%u",ZIGBEE_PROC,i);
        CHECK(mkdir(path,0700)==0,"Zigbee scan bound fixture created");
    }
    command("ZIGBEESTATE",out,sizeof out);
    CHECK(!strcmp(out,"ERR\n"),"Zigbee incomplete scan at process bound remains unknown");
    setup();text(ZIGBEE_PROC "/stat","cpu 1 broken 3 4\n",0600);
    command("ZIGBEEHEALTH",out,sizeof out);
    CHECK(!strcmp(out,"ERR\n"),"Zigbee malformed total CPU remains unknown");
}
static void bounds_and_network(void) {
    setup();layout();char out[10000],raw[65538];
    memset(raw,'a',121);bytes(ZIGBEE_DIR "/package_version",raw,120,0600);command("ZIGBEESTATE",out,sizeof out);CHECK(strstr(out,"package=6161")!=NULL,"Zigbee accepts the exact package byte bound");
    bytes(ZIGBEE_DIR "/package_version",raw,121,0600);command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee rejects package overflow");
    text(ZIGBEE_DIR "/package_version","bad\001value",0600);command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee rejects control bytes in package");
    { const unsigned char invalid[]={0xc0,0xaf};bytes(ZIGBEE_DIR "/package_version",invalid,sizeof invalid,0600); }command("ZIGBEESTATE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee rejects malformed UTF8 package");
    unlink(ZIGBEE_DIR "/package_version");
    text(ZIGBEE_DATA "/netinfo","{\"netinfo\":{\"roleType\":2,\"secret\":\"not-public\"}}",0600);command("ZIGBEEHEALTH",out,sizeof out);CHECK(strstr(out,"netinfo=7b226e6574696e666f22")!=NULL && !strstr(out,"secret"),"Zigbee health frames bounded canonical netinfo as opaque hex");
    memset(raw,'a',4096);bytes(ZIGBEE_DATA "/netinfo",raw,4096,0600);command("ZIGBEEHEALTH",out,sizeof out);CHECK(strlen(out)>8192 && strlen(out)<=9216 && strstr(out,"END\n"),"Zigbee accepts the exact network byte bound");
    bytes(ZIGBEE_DATA "/netinfo",raw,4097,0600);text(ZIGBEE_DIR "/zgateway.log","netinfo {\"nodeId\":1}\n",0600);command("ZIGBEEHEALTH",out,sizeof out);CHECK(strstr(out,"netinfo=-\nEND\n")!=NULL,"Zigbee oversized canonical source never falls back to stale logs");
    unlink(ZIGBEE_DATA "/netinfo");command("ZIGBEEHEALTH",out,sizeof out);CHECK(strstr(out,"netinfo=6e6574696e666f20")!=NULL,"Zigbee missing canonical source retains fixed log fallback");
    CHECK(mkfifo(ZIGBEE_DATA "/netinfo",0600)==0,"Zigbee FIFO fixture created");command("ZIGBEEHEALTH",out,sizeof out);CHECK(strstr(out,"netinfo=-\nEND\n")!=NULL,"Zigbee nonregular canonical source never falls back");
    memset(raw,'x',65537);bytes(ZIGBEE_DIR "/guard_process.sh",raw,65537,0600);command("ZIGBEEHEALTH",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee bounded guard read never returns a false recursive flag");
}
static void roles(void) {
    setup();layout();char out[10000];
    sysexec_stub_add_popen("mosquitto_sub","{\"role\":\"Repeater\"}\n",0);command("ZIGBEEROLE",out,sizeof out);CHECK(!strcmp(out,"ZIGBEEROLE 7b22726f6c65223a225265706561746572227d0a\n"),"Zigbee role frames the complete bounded broker response");
    sysexec_stub_clear_popen_rules();sysexec_stub_add_popen("mosquitto_sub","partial",1<<8);command("ZIGBEEROLE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee role rejects child failure and partial output");
    sysexec_stub_clear_popen_rules();command("ZIGBEEROLE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee role rejects empty broker output");
    sysexec_stub_timeout_run("mosquitto_sub");command("ZIGBEEROLE",out,sizeof out);CHECK(!strcmp(out,"ERR\n"),"Zigbee role rejects deadline expiry");
}
int main(void) { admission_and_absence();identities();failures_remain_unknown();process_bounds();bounds_and_network();roles();clean();if(failures)return 1;puts("ZIGBEE BOUNDARY OK");return 0; }
