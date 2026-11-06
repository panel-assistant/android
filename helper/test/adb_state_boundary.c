// Drive production dispatch with raw property captures and redirected fixed TCP files.
// This fixture also compiles against pre-ADBSTATE sources: only behavioral replies turn red.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include "dispatch.h"
static int failures, captures, opens, failed_table = -1, table_fds[2] = {-1,-1}, read_once[2];
static char paths[2][128];
// Wire-independent fixed capture layout permits this fixture to compile against the old base.
struct fixture_capture { char output[5][257]; size_t used[5]; int status[5]; };
static struct fixture_capture properties;
#define CHECK(c,text) do { if (!(c)) { printf("FAIL: %s (%s:%d)\n",text,__FILE__,__LINE__); failures++; } } while(0)
void __wrap_sysexec_capture_adb_properties(void *output) { captures++; memcpy(output,&properties,sizeof properties); }
int __real_open(const char *path,int flags,...);
int __wrap_open(const char *path,int flags,...) {
    for(int i=0;i<2;i++) if(!strcmp(path,i ? "/proc/net/tcp6" : "/proc/net/tcp")) {
        opens++; read_once[i]=0;
        int fd=__real_open(paths[i],flags);
        // A new table can reuse the closed previous table's numeric descriptor.
        for(int j=0;j<2;j++) if(table_fds[j]==fd)table_fds[j]=-1;
        table_fds[i]=fd;
        return fd;
    }
    if(!(flags&O_CREAT)) return __real_open(path,flags);
    va_list ap; va_start(ap,flags); mode_t mode=(mode_t)va_arg(ap,int); va_end(ap);
    return __real_open(path,flags,mode);
}
ssize_t __real_read(int fd,void *buf,size_t size);
ssize_t __wrap_read(int fd,void *buf,size_t size) {
    if(failed_table>=0 && fd==table_fds[failed_table] && read_once[failed_table]++) { errno=EIO; return -1; }
    return __real_read(fd,buf,size);
}
ssize_t __wrap___read_chk(int fd,void *buf,size_t size,size_t capacity) {
    if(size>capacity)abort();
    return __wrap_read(fd,buf,size);
}
static const char header[]="  sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
static void table(int i,const char *raw,size_t size) {
    FILE *f=fopen(paths[i],"wb"); CHECK(f!=NULL,"ADB table fixture opens");
    if(f){CHECK(fwrite(raw,1,size,f)==size,"ADB table fixture writes complete bytes");fclose(f);}
    table_fds[i]=-1;
}
static void text_table(int i,const char *body) {
    char raw[2048];snprintf(raw,sizeof raw,"%s%s",header,body);table(i,raw,strlen(raw));
}
static void reset(void) { memset(&properties,0,sizeof properties);failed_table=-1;text_table(0,"");text_table(1,""); }
static void prop(unsigned i,const char *raw,int status) {
    properties.used[i]=strlen(raw);memcpy(properties.output[i],raw,properties.used[i]);properties.status[i]=status;
}
static void command(const char *input,char out[128]) {
    int pair[2];CHECK(!socketpair(AF_UNIX,SOCK_STREAM,0,pair),"ADB dispatch fixture socket opens");
    conn_ctx ctx={0};ctx.fd=pair[0];char line[256];snprintf(line,sizeof line,"%s",input);
    dispatch(&ctx,line);shutdown(pair[0],SHUT_WR);size_t used=0;ssize_t n;
    while(used<127 && (n=read(pair[1],out+used,127-used))>0)used+=(size_t)n;
    out[used]=0;close(pair[0]);close(pair[1]);
    table_fds[0]=table_fds[1]=-1;
}
static void expect(const char *wanted,const char *assertion) {char out[128];command("ADBSTATE",out);CHECK(!strcmp(out,wanted),assertion);}
int main(void) {
    char dir[]="/tmp/hapaneld-adb-state-XXXXXX";CHECK(mkdtemp(dir)!=NULL,"ADB fixture temporary directory opens");
    for(int i=0;i<2;i++)snprintf(paths[i],sizeof paths[i],"%s/tcp%d",dir,i);
    reset();char out[128];command("PING",out);CHECK(!strcmp(out,"OK\n"),"ADB pre-feature PING control stays green");
    const char *bad[]={"ADBSTATE extra","ADBSTATE /proc/net/tcp","ADBSTATE ;reboot","ADBSTATE\nPING"};
    for(unsigned i=0;i<sizeof bad/sizeof bad[0];i++){int before=captures+opens;command(bad[i],out);CHECK(!strcmp(out,"ERR\n") && before==captures+opens,"ADB rejects every argument before observation");}
    expect("PROPS=0 LISTEN=0\n","ADB successful empty properties and complete empty tables prove inactive");
    for(unsigned i=0;i<5;i++){reset();prop(i,i==0?"tcp:5555\n":i==4?"1\n":"5555\n",0);expect("PROPS=1 LISTEN=0\n","ADB each fixed property can establish active state");}
    reset();prop(0,"",1);prop(3,"5555",0);expect("PROPS=1 LISTEN=0\n","ADB property positive wins earlier failed observation");
    reset();prop(0,"tcp:5555",0);prop(4,"",-2);expect("PROPS=1 LISTEN=0\n","ADB property positive wins later timeout");
    for(int status=-2;status<=1;status++){if(!status)continue;reset();prop(2,"",status);expect("PROPS=? LISTEN=0\n","ADB failed property cannot prove inactive");}
    const char *off[]={"-2147483648","-1","-0","+0","0000"," \t-12\r\n"};
    for(unsigned i=0;i<sizeof off/sizeof off[0];i++){reset();prop(1,off[i],0);expect("PROPS=0 LISTEN=0\n","ADB signed int32 nonpositive ports remain inactive");}
    const char *on[]={"1","65535","+05555"," 5555\n"};
    for(unsigned i=0;i<sizeof on/sizeof on[0];i++){reset();prop(2,on[i],0);expect("PROPS=1 LISTEN=0\n","ADB signed int32 positive ports preserve legacy behavior");}
    const char *unknown[]={"65536","2147483647","2147483648","-2147483649","5555junk","+","1\n2"};
    for(unsigned i=0;i<sizeof unknown/sizeof unknown[0];i++){reset();prop(3,unknown[i],0);expect("PROPS=? LISTEN=0\n","ADB malformed or out-of-range port stays unknown");}
    reset();prop(4,"true",0);expect("PROPS=? LISTEN=0\n","ADB enable property accepts only zero or one");
    reset();prop(4,"2",0);expect("PROPS=? LISTEN=0\n","ADB enable property rejects numeric values outside zero or one");
    reset();prop(0,"unfamiliar socket specification",0);expect("PROPS=1 LISTEN=0\n","ADB nonempty listen addresses fail closed without narrowing grammar");
    reset();memcpy(properties.output[1],"-1\0hidden",9);properties.used[1]=9;expect("PROPS=? LISTEN=0\n","ADB embedded NUL property invalidates inactive prefix");
    reset();memcpy(properties.output[0],"tcp:5555\0hidden",15);properties.used[0]=15;expect("PROPS=? LISTEN=0\n","ADB embedded NUL invalidates active socket specification");
    reset();memset(properties.output[1],'0',256);properties.used[1]=256;expect("PROPS=0 LISTEN=0\n","ADB complete 256 byte property remains readable");
    properties.output[1][256]='0';properties.used[1]=257;expect("PROPS=? LISTEN=0\n","ADB property byte overflow cannot prove inactive");
    const char row4[]="0: 0100007F:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n";
    const char row6[]="0: 00000000000000000000000001000000:15B3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n";
    reset();text_table(0,row4);expect("PROPS=0 LISTEN=1\n","ADB IPv4 listener includes loopback local address");
    reset();text_table(1,row6);expect("PROPS=0 LISTEN=1\n","ADB IPv6 listener independently establishes active state");
    reset();text_table(0,row4);unlink(paths[1]);expect("PROPS=0 LISTEN=1\n","ADB complete positive table wins other unreadable table");
    reset();unlink(paths[0]);expect("PROPS=0 LISTEN=?\n","ADB one unreadable table cannot prove no listener");
    reset();text_table(0,"0: 00000000:15B3 00000000:0000 01 00000000:00000000 00:00000000 00000000 0 0 1\n");expect("PROPS=0 LISTEN=0\n","ADB non-LISTEN socket at target port stays inactive");
    reset();text_table(0,"0: 00000000:15B4 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n");expect("PROPS=0 LISTEN=0\n","ADB other listening port stays inactive");
    const char *malformed[]={"0: Z0000000:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n","0: 00000000:15B3 00000000:0000 ZZ 00000000:00000000 00:00000000 00000000 0 0 1\n","0: 00000000:15B3 00000000:0000 0A\n","broken\n","\n"};
    for(unsigned i=0;i<sizeof malformed/sizeof malformed[0];i++){reset();text_table(0,malformed[i]);expect("PROPS=0 LISTEN=?\n","ADB malformed TCP row cannot prove listener absence");}
    reset();table(0,"invalid header\n",15);expect("PROPS=0 LISTEN=?\n","ADB malformed TCP header cannot prove absence");
    reset();unlink(paths[0]);CHECK(!mkfifo(paths[0],0600),"ADB nonregular fixture opens");expect("PROPS=0 LISTEN=?\n","ADB nonregular TCP table cannot prove absence");unlink(paths[0]);
    reset();char raw[1024];snprintf(raw,sizeof raw,"%s%sbroken\n",header,row4);table(0,raw,strlen(raw));expect("PROPS=0 LISTEN=?\n","ADB malformed later row invalidates same-table positive prefix");
    reset();snprintf(raw,sizeof raw,"%s%s",header,row4);table(0,raw,strlen(raw)-1);expect("PROPS=0 LISTEN=?\n","ADB unterminated TCP record cannot prove complete inventory");
    reset();failed_table=0;expect("PROPS=0 LISTEN=?\n","ADB later read error invalidates readable table prefix");
    reset();failed_table=0;text_table(1,row6);expect("PROPS=0 LISTEN=1\n","ADB failed IPv4 read cannot contaminate fresh IPv6 descriptor");
    reset();char *large=malloc(131074);memset(large,' ',131074);memcpy(large,header,strlen(header)-1);large[131071]='\n';table(0,large,131072);expect("PROPS=0 LISTEN=0\n","ADB complete exactly bounded TCP table proves inactive");
    large[131072]='\n';table(0,large,131073);expect("PROPS=0 LISTEN=?\n","ADB TCP byte overflow invalidates readable prefix");free(large);
    reset();memcpy(raw,header,strlen(header));memcpy(raw+strlen(header),"\0hidden\n",8);table(0,raw,strlen(header)+8);expect("PROPS=0 LISTEN=?\n","ADB embedded NUL table cannot prove absence");
    unlink(paths[0]);unlink(paths[1]);rmdir(dir);
    if(failures)return 1;
    puts("ADB STATE BOUNDARY OK");return 0;
}
