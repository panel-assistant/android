// The real ordinary dispatch boundary; publication/replacement is qualified by the real driver.
#include "dispatch.h"
#include "sysexec_stub.h"
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
static int failures;
#define CHECK(c,m) do { if (!(c)) { printf("FAIL: %s\n",m); failures++; } } while (0)
static void request(const char *text, enum helper_caller caller, char out[128]) {
    int pair[2]; if (socketpair(AF_UNIX,SOCK_STREAM,0,pair)) { failures++; out[0]=0; return; }
    conn_ctx ctx={.fd=pair[0],.caller=caller}; char line[256]; snprintf(line,sizeof line,"%s",text);
    dispatch(&ctx,line); shutdown(pair[0],SHUT_WR);
    ssize_t n=read(pair[1],out,127); out[n>0?n:0]=0; close(pair[0]);close(pair[1]);
}
int main(void) {
    char out[128]; sysexec_stub_reset();
    request("PING",HELPER_CALLER_ROOT,out); CHECK(!strcmp(out,"OK\n"),"ENSURE pre-feature PING control stays green");
    const char *bad[]={"ADBRECOVERYENSURE extra","ADBRECOVERYENSURE /tmp/helper","ADBRECOVERYENSURE ;reboot"};
    for(unsigned i=0;i<sizeof bad/sizeof bad[0];i++) {request(bad[i],HELPER_CALLER_ROOT,out);CHECK(!strcmp(out,"ERR\n")&&!sysexec_stub_count_ensure(),"ENSURE rejects every argument before any recovery operation");}
    request("ADBRECOVERYENSURE",HELPER_CALLER_NONE,out);
    CHECK(!strcmp(out,"ERR\n")&&!sysexec_stub_count_ensure(),"ENSURE rejects unauthenticated callers before any recovery operation");
    request("ADBRECOVERYENSURE",HELPER_CALLER_ROOT,out);
    CHECK(!strcmp(out,"ADBRECOVERYENSURE UNAVAILABLE\n")&&sysexec_stub_count_ensure()==1,"ENSURE unavailable is exact and bounded on ordinary dispatch");
    sysexec_stub_set_ensure_result(1);request("ADBRECOVERYENSURE",HELPER_CALLER_SUCCESSOR,out);
    CHECK(!strcmp(out,"ADBRECOVERYENSURE READY\n")&&sysexec_stub_count_ensure()==2&&strlen(out)<=32,"ENSURE ready is exact and bounded for the authenticated app");
    if(!failures)puts("ADB ENSURE BOUNDARY OK");
    return failures?1:0;
}
